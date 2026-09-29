package com.quickmaster.playback;

import com.dspark.core.Dither;
import com.dspark.core.OversamplingEngine;
import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.AppLogger;
import com.quickmaster.processing.ProcessingPipeline;
import javafx.application.Platform;
import javafx.beans.property.*;
import javax.sound.sampled.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/**
 * Single-worker audio transport. Each playback session owns its source, device,
 * oversampler and dither. Stop never joins the worker or closes a device on FX.
 * Obsolete sessions cannot change a replacement session's position or UI state.
 */
public class AudioPlayer implements AutoCloseable {
    public static final int BUFFER_FRAMES = 1024;
    public enum State { STOPPED, PLAYING, PAUSED }

    @FunctionalInterface interface LineFactory {
        SourceDataLine create(AudioFormat format) throws LineUnavailableException;
    }

    private final ProcessingPipeline pipeline;
    private final LineFactory lineFactory;
    private final Consumer<Runnable> dispatch;
    private final Consumer<Throwable> errors;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), task -> daemon(task, "QuickMaster-Playback"));
    private final ExecutorService deviceCloser = Executors.newSingleThreadExecutor(
            task -> daemon(task, "QuickMaster-AudioClose"));
    private Session active;
    private Thread playbackThread;
    private float[] sourceSamples;
    private int sampleRate, channels;
    private volatile float[] fixedRender;
    private volatile float[] publishedRender;
    private volatile PreviewWindow previewWindow;
    private volatile PreviewWindow precedingPreviewWindow;
    private long previewGeneration = -1;
    private long previewFloor = -1;
    private volatile long consumedPreviewGeneration = -1;
    private volatile boolean audiblePreview;
    private volatile PreviewWindow activePreviewWindow;
    private volatile int osFactor = 1;
    private volatile boolean analysisValid, abBypass, paused, loopEnabled;
    private volatile boolean listenInMono;
    private volatile long loopStartFrames, loopEndFrames, positionFrames, audiblePositionFrames;
    private long seekRevision, stateRevision;
    private volatile State requestedState = State.STOPPED;
    private volatile ObjIntConsumer<float[]> meterTap;
    private boolean closed;

    private final BooleanProperty playing = new SimpleBooleanProperty(false);
    private final LongProperty positionSamples = new SimpleLongProperty(0);
    private final BooleanProperty abMode = new SimpleBooleanProperty(false);
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.STOPPED);

    private static final class Session {
        final float[] source;
        final int rate, channels;
        final OversamplingEngine oversampler = new OversamplingEngine();
        final Dither dither = new Dither(16, false);
        final MonoMonitor monitor;
        volatile boolean cancelled;
        volatile SourceDataLine line;
        volatile Thread thread;
        float[] abDelay = new float[0];
        int abIndex;
        Session(float[] source, int rate, int channels, boolean mono) {
            this.source = source; this.rate = rate; this.channels = channels;
            monitor = new MonoMonitor(rate, mono);
        }
    }
    private record PreviewMark(long deviceFrame, long generation) { }

    public AudioPlayer(ProcessingPipeline pipeline) {
        this(pipeline, format -> (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format)),
                Platform::runLater, error -> AppLogger.error("Audio playback failed.", error));
    }

    /** Injectable device and dispatcher keep transport regression tests hardware-independent. */
    AudioPlayer(ProcessingPipeline pipeline, LineFactory factory, Consumer<Runnable> dispatch, Consumer<Throwable> errors) {
        if (pipeline == null) throw new IllegalArgumentException("pipeline must not be null.");
        this.pipeline = pipeline;
        this.lineFactory = java.util.Objects.requireNonNull(factory);
        this.dispatch = java.util.Objects.requireNonNull(dispatch);
        this.errors = java.util.Objects.requireNonNull(errors);
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }

    public synchronized void prepare(AudioFile file) {
        if (file == null || file.getSamples() == null || file.getSampleRate() <= 0
                || (file.getChannels() != 1 && file.getChannels() != 2)
                || file.getSamples().length % file.getChannels() != 0)
            throw new IllegalArgumentException("Playback requires complete mono/stereo audio.");
        stopInternal();
        sourceSamples = file.getSamples(); sampleRate = file.getSampleRate(); channels = file.getChannels();
        fixedRender = null; publishedRender = null; analysisValid = false; loopEnabled = false;
        clearPreview();
        publishState(State.STOPPED);
    }

    public synchronized void play() {
        if (closed) throw new IllegalStateException("Player is closed.");
        if (sourceSamples == null || sourceSamples.length == 0 || requestedState == State.PLAYING) return;
        if (active != null && !active.cancelled) {
            paused = false; publishState(State.PLAYING); return;
        }
        paused = false;
        audiblePreview = false;
        if (positionFrames >= sourceSamples.length / channels) positionFrames = audiblePositionFrames = 0;
        Session session = new Session(sourceSamples, sampleRate, channels, listenInMono);
        active = session;
        publishState(State.PLAYING);
        worker.execute(() -> playbackLoop(session));
    }

    public synchronized void pause() {
        if (requestedState != State.PLAYING) return;
        paused = true; publishState(State.PAUSED);
    }

    public synchronized void stop() {
        stopInternal(); publishState(State.STOPPED);
    }

    private void stopInternal() {
        audiblePreview = false; activePreviewWindow = null;
        Session previous = active;
        active = null;
        worker.getQueue().clear();
        if (previous != null) {
            previous.cancelled = true;
            Thread thread = previous.thread;
            if (thread != null) thread.interrupt();
            SourceDataLine owned = previous.line;
            if (owned != null) deviceCloser.execute(() -> closeLine(owned));
        }
        paused = false;
        seekRevision++;
        positionFrames = audiblePositionFrames = 0;
        // Keep the worker reference until its own finally: it may still be closing.
    }

    public synchronized void seekTo(double seconds) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Seek time must be finite.");
        if (sourceSamples == null) return;
        long frame = Math.max(0, Math.min(Math.round(seconds * sampleRate), sourceSamples.length / channels));
        positionFrames = audiblePositionFrames = frame; seekRevision++;
        publishPosition(active, seekRevision, frame);
    }

    public synchronized void toggleAB() {
        abBypass = !abBypass;
        boolean mode = abBypass;
        dispatch.accept(() -> { if (abBypass == mode) abMode.set(mode); });
    }

    public synchronized void setLoopRegion(long start, long end) {
        if (sourceSamples == null) return;
        long total = sourceSamples.length / channels;
        loopStartFrames = Math.max(0, Math.min(start, total));
        loopEndFrames = Math.max(0, Math.min(end, total));
        loopEnabled = loopEndFrames > loopStartFrames;
    }

    public synchronized void clearLoopRegion() { loopEnabled = false; }
    public boolean isLooping() { return loopEnabled; }
    public void setMeterTap(ObjIntConsumer<float[]> tap) { meterTap = tap; }

    /** Monitoring only: no render invalidation, seek, DSP change or source mutation. */
    public void setListenInMono(boolean mono) { listenInMono = mono; }
    public boolean isListenInMono() { return listenInMono; }

    public synchronized void setOversampling(int factor) {
        int next = Integer.highestOneBit(Math.max(1, Math.min(factor, 16)));
        if (osFactor != next) {
            osFactor = next; analysisValid = false;
            if (fixedRender == null && publishedRender == null) {
                positionFrames = audiblePositionFrames; seekRevision++;
            }
        }
    }

    public void setAnalysisValid(boolean valid) { analysisValid = valid; }

    /** Transfer an immutable completed master. Stale source work is never audible. */
    public synchronized boolean publishRender(float[] expectedSource, float[] render) {
        if (sourceSamples != expectedSource || closed) return false;
        if (render == null || render.length != sourceSamples.length)
            throw new IllegalArgumentException("Published render must match the current source.");
        publishedRender = render;
        clearPreview();
        analysisValid = true;
        return true;
    }

    public boolean hasPublishedRender() { return publishedRender != null; }

    public synchronized boolean beginPreview(float[] expectedSource, long generation) {
        if (closed || sourceSamples != expectedSource || publishedRender == null || fixedRender != null) return false;
        if (previewGeneration < 0) previewFloor = generation;
        previewGeneration = generation; // Keep preceding audible window until replacement is ready.
        return true;
    }
    public synchronized boolean publishPreview(PreviewWindow window) {
        if (closed || window.source() != sourceSamples || previewGeneration < 0
                || window.generation() < previewFloor || window.generation() > previewGeneration
                || previewWindow != null && window.generation() < previewWindow.generation()
                || window.channels() != channels || fixedRender != null) return false;
        precedingPreviewWindow = previewWindow;
        previewWindow = window; return true;
    }
    public synchronized void clearPreview() { previewWindow = null; precedingPreviewWindow = null; previewGeneration = previewFloor = -1; }
    public PreviewWindow getPreviewWindow() { return previewWindow; }
    public PreviewWindow getActivePreviewWindow() { return activePreviewWindow; }
    public boolean isPreviewAudible() { return audiblePreview && !abBypass; }
    public long getConsumedPreviewGeneration() { return consumedPreviewGeneration; }
    /** Next submitted source frame, including device queue; never the lagging UI clock. */
    public long getPreviewRequestFrame() { return positionFrames; }
    public long getPreviewLoopStart() { return loopEnabled ? loopStartFrames : -1; }
    public long getPreviewLoopEnd() { return loopEnabled ? loopEndFrames : -1; }

    private float[] auditionRender() {
        float[] comparison = fixedRender;
        return comparison != null ? comparison : publishedRender;
    }

    public synchronized void setFixedRender(float[] render) {
        if (render != null && (sourceSamples == null || render.length != sourceSamples.length))
            throw new IllegalArgumentException("Fixed render must match the current source.");
        fixedRender = render;
        clearPreview();
    }

    public boolean hasFixedRender() { return fixedRender != null; }
    public BooleanProperty playingProperty() { return playing; }
    public LongProperty positionSamplesProperty() { return positionSamples; }
    public BooleanProperty abModeProperty() { return abMode; }
    public ObjectProperty<State> stateProperty() { return state; }
    public boolean isPlaying() { return requestedState == State.PLAYING; }
    public long getPositionSamples() { return audiblePositionFrames; }
    public boolean isAbMode() { return abBypass; }
    public State getState() { return requestedState; }
    public ProcessingPipeline getPipeline() { return pipeline; }

    private void publishState(State next) {
        requestedState = next;
        long revision = ++stateRevision;
        dispatch.accept(() -> {
            synchronized (AudioPlayer.this) {
                if (stateRevision != revision) return;
                state.set(next); playing.set(next == State.PLAYING);
                if (next == State.STOPPED) positionSamples.set(audiblePositionFrames);
            }
        });
    }

    private void publishPosition(Session session, long revision, long frame) {
        dispatch.accept(() -> {
            synchronized (AudioPlayer.this) {
                if (active == session && seekRevision == revision) positionSamples.set(frame);
            }
        });
    }

    private int prepareChain(Session session, boolean analyze) {
        pipeline.prepare(session.rate, session.source.length);
        if (analyze && !analysisValid) {
            pipeline.analyze(session.source, session.channels);
            if (osFactor > 1) pipeline.renderAnalyzedOversampled(session.source, session.channels, osFactor, null, null);
            if (!session.cancelled) analysisValid = true;
        }
        int factor = osFactor;
        pipeline.prepare(Math.multiplyExact(session.rate, factor), (long) session.source.length * factor);
        if (factor > 1) session.oversampler.prepare(factor, session.channels, BUFFER_FRAMES, OversamplingEngine.Quality.HIGH);
        session.abDelay = new float[0]; session.abIndex = 0;
        return factor;
    }

    private int latency(Session session, int factor) {
        return factor == 1 ? pipeline.getLatencyFrames()
                : (int) Math.round(pipeline.getLatencyFrames() / (double) factor) + session.oversampler.getLatencyBaseFrames();
    }

    private void playbackLoop(Session session) {
        SourceDataLine device = null;
        boolean naturalEnd = false;
        try {
            synchronized (this) {
                if (active != session || session.cancelled) return;
                playbackThread = session.thread = Thread.currentThread();
            }
            AudioFormat format = new AudioFormat(session.rate, 16, session.channels, true, false);
            device = lineFactory.create(format);
            session.line = device;
            if (session.cancelled) return;
            // Published PCM/preview require no DSP on the device thread. One
            // queued block keeps interactive edits from paying a second block.
            device.open(format, BUFFER_FRAMES * session.channels * 2 * (auditionRender() == null ? 2 : 1));
            if (session.cancelled) return;
            int factor = 1;
            boolean prepared = false;
            if (auditionRender() == null) { factor = prepareChain(session, true); prepared = true; }
            if (session.cancelled) return;
            device.start();
            boolean devicePaused = false;
            long submitted = 0, playedOrigin = device.getLongFramePosition();
            long requestedPreviewGeneration = -1;
            java.util.ArrayDeque<PreviewMark> previewMarks = new java.util.ArrayDeque<>();
            long observedSeek = -1;
            boolean looped = false;
            boolean wasFixed = auditionRender() != null;
            int previousLatency = wasFixed ? 0 : latency(session, factor);
            float[] previousRender = auditionRender(), fadeFrom = null;
            PreviewWindow previousPreview = null, previewFadeFrom = null;
            int previewFadePosition = 0;
            int fadePosition = 0, fadeFrames = Math.max(1, session.rate / 50);
            int total = session.source.length / session.channels;
            byte[] bytes = new byte[BUFFER_FRAMES * session.channels * 2];

            while (!session.cancelled) {
                if (paused) {
                    if (!devicePaused) { device.stop(); devicePaused = true; }
                    Thread.sleep(10);
                    continue;
                }
                if (devicePaused) { device.start(); devicePaused = false; }
                long start, revision;
                int frames, lat;
                boolean flushing, discontinuity, loopWrapped = false;
                float[] render = auditionRender();
                if (render != previousRender && render != null && previousRender != null && fixedRender == null) {
                    fadeFrom = previousRender; fadePosition = 0;
                }
                previousRender = render;
                boolean toFixed = render != null && !wasFixed;
                boolean toLive = render == null && wasFixed;
                wasFixed = render != null;
                if (render == null && (!prepared || factor != osFactor)) {
                    factor = prepareChain(session, !prepared); prepared = true;
                }
                lat = render == null ? latency(session, factor) : 0;
                synchronized (this) {
                    if (active != session || session.cancelled) break;
                    // The live feed cursor leads source-aligned cached PCM by
                    // chain latency. Change clocks only at a block boundary.
                    if (toFixed) positionFrames = Math.max(0, positionFrames - previousLatency);
                    if (loopEnabled && positionFrames >= loopEndFrames + lat) {
                        positionFrames = loopStartFrames; seekRevision++; loopWrapped = true;
                    }
                    revision = seekRevision;
                    discontinuity = toLive || (observedSeek < 0 ? positionFrames > 0 : observedSeek != revision);
                    observedSeek = revision;
                    start = positionFrames;
                }
                if (discontinuity) {
                    fadeFrom = null;
                    looped = loopWrapped;
                    // The end of the previous iteration may still be queued at
                    // the device. A loop wrap is not a user seek: never drop it.
                    if (!loopWrapped && !toLive) {
                        device.flush(); submitted = 0; playedOrigin = device.getLongFramePosition();
                        requestedPreviewGeneration = -1; previewMarks.clear();
                    }
                    if (render == null) {
                        factor = prepareChain(session, false); lat = latency(session, factor);
                        long target = Math.min(total, start);
                        primeTo(session, factor, target + lat, revision);
                        start = target + lat;
                        synchronized (this) {
                            if (active != session || session.cancelled) break;
                            if (seekRevision != revision) continue;
                            positionFrames = start;
                        }
                    }
                }
                previousLatency = lat;
                long limit = (loopEnabled ? loopEndFrames : total) + lat;
                if (start >= limit) {
                    if (loopEnabled) continue;
                    naturalEnd = true; break;
                }
                frames = (int) Math.min(BUFFER_FRAMES, limit - start);
                flushing = start >= total;
                int samples = frames * session.channels;
                float[] mastered = new float[samples];
                float[] audible;
                if (render != null) {
                    if (!flushing) System.arraycopy(render, (int) start * session.channels, mastered, 0, samples);
                    if (fadeFrom != null && !flushing) {
                        int count = Math.min(frames, fadeFrames - fadePosition);
                        for (int frame = 0; frame < count; frame++) {
                            double t = (fadePosition + frame + 1.0) / fadeFrames;
                            double mix = t * t * (3 - 2 * t);
                            for (int channel = 0; channel < session.channels; channel++) {
                                int i = frame * session.channels + channel;
                                float old = fadeFrom[(int)start * session.channels + i];
                                mastered[i] = (float)(old + mix * (mastered[i] - old));
                            }
                        }
                        fadePosition += count;
                        if (fadePosition >= fadeFrames) fadeFrom = null;
                    }
                    audible = mastered;
                    if (abBypass && !flushing)
                        audible = Arrays.copyOfRange(session.source, (int) start * session.channels, (int) start * session.channels + samples);
                } else {
                    if (!flushing) System.arraycopy(session.source, (int) start * session.channels, mastered, 0,
                            Math.min(frames, total - (int) start) * session.channels);
                    float[] original = mastered.clone();
                    delayBypass(session, original, lat);
                    pipeline.setPlaybackPosition(start * factor - (factor == 1 ? 0 : session.oversampler.getUpsampleLatencyHiFrames()));
                    if (factor == 1) mastered = pipeline.execute(mastered, session.channels);
                    else {
                        float[] up = session.oversampler.upsample(mastered, frames);
                        // DSPark returns its capacity-sized scratch array, not
                        // a shortened view. Never process stale high-rate tail
                        // samples when this source/loop block is partial.
                        int validSamples = frames * factor * session.channels;
                        if (up.length != validSamples) up = Arrays.copyOf(up, validSamples);
                        float[] hi = pipeline.executeBlocks(up, session.channels, ProcessingPipeline.OFFLINE_BLOCK_FRAMES);
                        session.oversampler.downsample(hi, frames, mastered);
                    }
                    audible = abBypass ? original : mastered;
                }
                if (session.cancelled) break;
                PreviewWindow preview = previewWindow;
                if (preview != null && !usablePreview(preview, start, frames, total, fadeFrames)) {
                    PreviewWindow preceding = precedingPreviewWindow;
                    preview = preceding != null && preceding.generation() == preview.generation()
                            && usablePreview(preceding, start, frames, total, fadeFrames) ? preceding : null;
                }
                if (fixedRender != null || render == null || preview != null && preview.source() != session.source) preview = null;
                if (preview != previousPreview) {
                    previewFadeFrom = previousPreview; previewFadePosition = 0;
                }
                boolean previewTransition = previewFadePosition < fadeFrames
                        && (preview != null || previewFadeFrom != null);
                if (preview != null || previewTransition) {
                    for (int f = 0; f < frames; f++) {
                        double t = Math.min(1, (previewFadePosition + f + 1.0) / fadeFrames);
                        double mix = t * t * (3 - 2 * t);
                        for (int c = 0; c < session.channels; c++) {
                            int i = f * session.channels + c;
                            float next = preview == null ? mastered[i] : preview.sample(start + f, c);
                            float old = previewFadeFrom != null && previewFadeFrom.covers(start + f, 1)
                                    ? previewFadeFrom.sample(start + f, c) : mastered[i];
                            mastered[i] = previewTransition ? (float)(old + mix * (next - old)) : next;
                        }
                    }
                    previewFadePosition += frames;
                }
                previousPreview = preview;
                activePreviewWindow = preview;
                audiblePreview = preview != null;
                if (preview != null && preview.generation() != requestedPreviewGeneration) {
                    requestedPreviewGeneration = preview.generation();
                    if (previewMarks.size() == 64) previewMarks.removeFirst();
                    previewMarks.addLast(new PreviewMark(submitted, preview.generation()));
                }
                // Bypass remains the unmodified source, even during provisional audition.
                if (!abBypass) audible = mastered;
                pcm16(session, audible, bytes);
                int offset = 0, size = samples * 2;
                while (offset < size && !session.cancelled) {
                    int wrote = device.write(bytes, offset, size - offset);
                    if (wrote < 0 || wrote > size - offset || wrote % (session.channels * 2) != 0)
                        throw new IllegalStateException("Invalid audio device write count.");
                    if (wrote == 0) Thread.sleep(1);
                    offset += wrote;
                }
                if (session.cancelled) break;
                submitted += frames;
                long playedFrames = device.getLongFramePosition() - playedOrigin;
                while (!previewMarks.isEmpty() && playedFrames > previewMarks.getFirst().deviceFrame())
                    consumedPreviewGeneration = previewMarks.removeFirst().generation();
                synchronized (this) {
                    if (active != session || session.cancelled) break;
                    if (seekRevision != revision) continue;
                    ObjIntConsumer<float[]> tap = meterTap;
                    if (tap != null) tap.accept(mastered, session.channels);
                    positionFrames = start + frames;
                    long queued = Math.max(0, submitted - (device.getLongFramePosition() - playedOrigin));
                    long audibleFrame = positionFrames - lat - queued;
                    if (loopEnabled && looped && audibleFrame < loopStartFrames)
                        audibleFrame = loopStartFrames + Math.floorMod(audibleFrame - loopStartFrames, loopEndFrames - loopStartFrames);
                    audiblePositionFrames = Math.max(0, Math.min(total, audibleFrame));
                    publishPosition(session, revision, audiblePositionFrames);
                }
            }
            if (naturalEnd && !session.cancelled) device.drain();
        } catch (InterruptedException | CancellationException e) {
            // Control cancellation is normal; unexpected interruption still closes ownership.
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!session.cancelled) errors.accept(e);
        } finally {
            if (device != null) closeLine(device);
            synchronized (this) {
                if (playbackThread == session.thread) playbackThread = null;
                if (active == session) {
                    active = null;
                    audiblePreview = false;
                    activePreviewWindow = null;
                    if (naturalEnd) positionFrames = audiblePositionFrames = 0;
                    publishState(State.STOPPED);
                }
            }
        }
    }

    /** Exact history for stateful FIR/IIR/dynamic stages after a discontinuity.
     * Runs only on the audio worker, publishes no samples and is interruptible.
     * Includes chain latency so the first audible sample is the requested one. */
    private void primeTo(Session session, int factor, long end, long revision) {
        int total = session.source.length / session.channels;
        for (long start = 0; start < end; ) {
            synchronized (this) {
                if (session.cancelled || active != session || seekRevision != revision) return;
            }
            int frames = (int) Math.min(BUFFER_FRAMES, end - start);
            float[] block = new float[frames * session.channels];
            int copy = (int) Math.max(0, Math.min(frames, total - start));
            if (copy > 0) System.arraycopy(session.source, (int)start * session.channels, block, 0, copy * session.channels);
            float[] bypass = block.clone(); delayBypass(session, bypass, latency(session, factor));
            pipeline.setPlaybackPosition(start * factor - (factor == 1 ? 0 : session.oversampler.getUpsampleLatencyHiFrames()));
            if (factor == 1) pipeline.execute(block, session.channels);
            else {
                float[] up = session.oversampler.upsample(block, frames);
                int valid = frames * factor * session.channels;
                if (up.length != valid) up = Arrays.copyOf(up, valid);
                float[] high = pipeline.executeBlocks(up, session.channels, ProcessingPipeline.OFFLINE_BLOCK_FRAMES);
                session.oversampler.downsample(high, frames, block);
            }
            start += frames;
        }
    }

    private static void delayBypass(Session session, float[] buffer, int latency) {
        int size = latency * session.channels;
        if (session.abDelay.length != size) { session.abDelay = new float[size]; session.abIndex = 0; }
        if (size == 0) return;
        for (int i = 0; i < buffer.length; i++) {
            float original = buffer[i]; buffer[i] = session.abDelay[session.abIndex];
            session.abDelay[session.abIndex] = original;
            session.abIndex = (session.abIndex + 1) % size;
        }
    }

    private boolean usablePreview(PreviewWindow window, long start, int frames, int total, int fade) {
        return window.covers(start, frames) && (window.endFrame() >= total || window.covers(start, frames + fade)
                || loopEnabled && window.covers(loopStartFrames, Math.toIntExact(loopEndFrames - loopStartFrames)));
    }

    private void pcm16(Session session, float[] input, byte[] output) {
        if (session.channels == 1) {
            for (int i = 0; i < input.length; i++) {
                if (!Float.isFinite(input[i])) throw new IllegalStateException("Non-finite playback sample.");
                pcm16Sample(session, input[i], output, i);
            }
            return;
        }
        session.monitor.setMono(listenInMono);
        for (int i = 0; i < input.length; i += 2) {
            double left = input[i], right = input[i + 1];
            if (!Double.isFinite(left) || !Double.isFinite(right))
                throw new IllegalStateException("Non-finite playback sample.");
            double sideGain = session.monitor.nextSideGain();
            if (sideGain != 1) {
                // Half-sum preserves centre gain and avoids the +6 dB of L+R.
                // Double intermediates also allow over-range float PCM to cancel
                // before device clamping. Never write back into master/preview.
                double mid = .5 * left + .5 * right, side = .5 * left - .5 * right;
                left = mid + sideGain * side; right = mid - sideGain * side;
            }
            pcm16Sample(session, left, output, i);
            pcm16Sample(session, right, output, i + 1);
        }
    }

    private static void pcm16Sample(Session session, double sample, byte[] output, int index) {
        float value = (float) Math.max(-1, Math.min(1, sample));
        float quantized = session.dither.processSample(value, index % session.channels);
        int pcm = Math.max(-32768, Math.min(32767, (int) Math.rint(quantized * 32768)));
        output[index * 2] = (byte) pcm; output[index * 2 + 1] = (byte) (pcm >>> 8);
    }

    private static void closeLine(SourceDataLine line) {
        try { line.stop(); } catch (Exception ignored) { }
        try { line.flush(); } catch (Exception ignored) { }
        try { line.close(); } catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        if (closed) return;
        stopInternal(); publishState(State.STOPPED); closed = true;
        worker.shutdownNow(); deviceCloser.shutdown();
    }
}
