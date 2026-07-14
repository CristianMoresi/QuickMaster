package com.quickmaster.playback;

import com.dspark.core.Dither;
import com.dspark.core.OversamplingEngine;
import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.AppLogger;
import com.quickmaster.processing.ProcessingPipeline;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Real-time streaming audio player.
 * <p>
 * Plays an {@link AudioFile} through the system's audio output by
 * reading samples buffer-by-buffer from the file's editable sample
 * array, optionally passing each buffer through a
 * {@link ProcessingPipeline}, and writing the result to a
 * {@link SourceDataLine}. Supports play, pause, stop, seek, an
 * optional loop region, and the A/B comparison toggle that bypasses
 * the pipeline to play the original audio for instant comparison.
 * <p>
 * <b>Threading model.</b> Playback runs on a dedicated background
 * thread spawned by {@link #play()}. Control methods
 * ({@link #pause()}, {@link #stop()}, {@link #seekTo(double)},
 * {@link #toggleAB()}) are safe to call from the JavaFX Application
 * Thread; they signal the playback thread via {@code volatile}
 * fields and {@code synchronized} blocks. Position updates that
 * need to reach the UI are dispatched via
 * {@link Platform#runLater(Runnable)} so that JavaFX observable
 * properties are always mutated on the FX thread.
 * <p>
 * <b>Latency handling.</b> Latency-bearing stages (a linear-phase EQ,
 * the multiband crossover, the oversampling engine) delay the audible
 * signal behind the source read position. The player accounts for it
 * three ways: the published position (and {@link #getPositionSamples()})
 * is the <i>audible</i> position, so the cursor and meters track what
 * is actually sounding; when the source runs out, the loop keeps
 * feeding silence until the chain's tail has flushed, so the end of
 * the song is never cut off; and the A/B bypass runs through a delay
 * line of the same length, so toggling the comparison does not jump
 * in time.
 * <p>
 * <b>Seek coordination.</b> Naive seek implementations are racy:
 * the audio thread reads {@code positionFrames}, computes the next
 * buffer, then writes {@code positionFrames + bufferSize} at the
 * end. A seek that lands in the middle of that cycle is silently
 * overwritten when the buffer finishes. To avoid this, every seek
 * sets a {@code seekRequested} flag. The audio thread checks this
 * flag at the end of each buffer: if a seek occurred during the
 * buffer, the post-buffer increment is skipped so the new position
 * survives. The flag is then cleared and the loop continues from
 * the seek target.
 * <p>
 * <b>Output format.</b> Playback always converts the internal
 * float samples to 16-bit signed PCM little-endian for the audio
 * output line, with TPDF dither applied at the conversion so the
 * monitor path carries no truncation distortion. This format is
 * supported on every platform the JDK runs on; the export path
 * preserves the source file's full bit depth.
 * <p>
 * <b>Observable state.</b> JavaFX properties expose the player's
 * state to the UI:
 * <ul>
 *   <li>{@link #playingProperty()} - true while audio is sounding</li>
 *   <li>{@link #positionSamplesProperty()} - audible frame index</li>
 *   <li>{@link #abModeProperty()} - true when bypass is active</li>
 *   <li>{@link #stateProperty()} - STOPPED / PLAYING / PAUSED</li>
 * </ul>
 * UI components can bind to these and react to changes without
 * polling.
 */
public class AudioPlayer
{
    /** Buffer size in frames. ~23 ms at 44.1 kHz, ~5 ms at 192 kHz. */
    public static final int BUFFER_FRAMES = 1024;

    /** Playback states. */
    public enum State { STOPPED, PLAYING, PAUSED }

    private final ProcessingPipeline pipeline;

    /* Live oversampling: the chain runs at osFactor x the base rate so the
       dynamics/limiter alias less. osDirty asks the loop to re-prepare. */
    private final OversamplingEngine osEngine = new OversamplingEngine();
    private volatile int osFactor = 1;
    private volatile boolean osDirty = false;

    /* True when the chain is already analysed for the current source, so a playback
       start can skip the (expensive) whole-file analyze pass and begin at once. The
       UI sets it after its load/change analysis; a new source clears it. */
    private volatile boolean analysisValid = false;

    /* Source audio for the current session. Set on prepare(). */
    private AudioFile audioFile;
    private float[] sourceSamples;
    private int sampleRate;
    private int channels;

    /* Playback control flags. Read by the audio thread, written by
       the UI thread. Marked volatile so writes are seen immediately. */
    private volatile boolean stopRequested = false;
    private volatile boolean paused = false;
    private volatile boolean seekRequested = false;

    /* A/B bypass flag. Mirrors {@link #abMode} but is a plain volatile
       boolean so the audio thread never touches a JavaFX property. */
    private volatile boolean abBypass = false;

    /* Optional pre-rendered, source-aligned output (the active A/B settings
       slot). When non-null the loop plays it back directly instead of running
       the live pipeline, so switching between two such renders is instant and
       needs no re-analysis. Same length, channel count and rate as the source;
       already latency-compensated, so it carries no chain delay. A plain
       volatile reference, swapped atomically: safe to set during playback. */
    private volatile float[] fixedRender = null;

    /* Loop region in source frames (end <= 0 disables). Set from the UI. */
    private volatile long loopStartFrames = 0L;
    private volatile long loopEndFrames = 0L;
    private volatile boolean loopEnabled = false;

    /* Optional metering tap: always fed the post-processing master, even while
       Bypass makes the raw source audible, so output meters never switch to a
       pre-processing signal. */
    private volatile java.util.function.ObjIntConsumer<float[]> meterTap;

    /* Playback position in frames (the source read cursor). Mutations from the
       UI thread (seek) and from the audio thread (loop advance) are serialised
       via synchronized blocks on the player instance; volatile so unsynchronized
       reads cannot tear. */
    private volatile long positionFrames = 0L;

    /* The audible position: the source frame currently sounding, i.e. the read
       cursor minus the chain + oversampler latency. This is what the UI sees. */
    private volatile long audiblePositionFrames = 0L;

    /* A/B bypass delay line: keeps the original signal delayed by the chain's
       current latency, so toggling the comparison stays time-aligned. */
    private float[] abDelay = new float[0];
    private int abDelayPos = 0;

    /* TPDF dither for the 16-bit monitor conversion (audio thread only). */
    private final Dither monitorDither = new Dither(16, false);

    /* Audio output line and worker thread. */
    private SourceDataLine line;
    private Thread playbackThread;

    /* Observable properties for UI binding. */
    private final BooleanProperty playing = new SimpleBooleanProperty(false);
    private final LongProperty positionSamples = new SimpleLongProperty(0);
    private final BooleanProperty abMode = new SimpleBooleanProperty(false);
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.STOPPED);

    /**
     * Creates a new player bound to the given pipeline.
     *
     * @param pipeline  the processing chain to apply during playback;
     *                  must not be null
     * @throws IllegalArgumentException if {@code pipeline} is null
     */
    public AudioPlayer(ProcessingPipeline pipeline)
    {
        if (pipeline == null)
        {
            throw new IllegalArgumentException("pipeline must not be null.");
        }
        this.pipeline = pipeline;
    }

    /**
     * Prepares the player to play the given audio file. Must be
     * called whenever the source changes (load, trim, reset). Stops
     * any in-progress playback first, then captures references to
     * the file's current sample buffer, sample rate and channel
     * count. The play position is reset to zero.
     *
     * @param file  the audio file to play (must already be loaded)
     */
    public synchronized void prepare(AudioFile file)
    {
        stopInternal();

        this.audioFile = file;
        this.sourceSamples = file.getSamples();
        this.sampleRate = file.getSampleRate();
        this.channels = file.getChannels();
        this.positionFrames = 0L;
        this.audiblePositionFrames = 0L;
        this.seekRequested = false;
        this.analysisValid = false;   // new source: must be (re)analysed before the next play
        this.loopEnabled = false;     // a new source invalidates the previous loop region
        this.fixedRender = null;      // a new source invalidates any pre-rendered A/B slot

        Platform.runLater(() ->
        {
            positionSamples.set(0L);
            state.set(State.STOPPED);
        });
    }

    /**
     * Starts or resumes playback. Three cases handled:
     * <ul>
     *   <li>Already playing: no-op.</li>
     *   <li>Paused: clear the pause flag, the playback thread resumes
     *       on its next loop iteration.</li>
     *   <li>Stopped: spawn a fresh playback thread, opening the
     *       audio line and running the buffer loop until stop or
     *       end-of-stream.</li>
     * </ul>
     */
    public synchronized void play()
    {
        if (sourceSamples == null)
        {
            AppLogger.warn("AudioPlayer.play() called with no audio prepared.");
            return;
        }
        if (state.get() == State.PLAYING)
        {
            return;
        }
        if (state.get() == State.PAUSED)
        {
            paused = false;
            Platform.runLater(() ->
            {
                state.set(State.PLAYING);
                playing.set(true);
            });
            return;
        }

        // Fresh start: spawn the playback thread.
        stopRequested = false;
        paused = false;
        seekRequested = false;
        playbackThread = new Thread(this::playbackLoop, "QuickMaster-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();

        Platform.runLater(() ->
        {
            state.set(State.PLAYING);
            playing.set(true);
        });
    }

    /**
     * Pauses playback at the current position. The playback thread
     * stays alive, sleeping in short increments while the pause flag
     * is set, until {@link #play()} clears it.
     */
    public synchronized void pause()
    {
        if (state.get() != State.PLAYING)
        {
            return;
        }
        paused = true;
        Platform.runLater(() ->
        {
            state.set(State.PAUSED);
            playing.set(false);
        });
    }

    /**
     * Stops playback and resets the position to the start. The
     * playback thread is signalled to exit; this method blocks
     * briefly waiting for it to finish so the audio line is closed
     * cleanly.
     */
    public synchronized void stop()
    {
        stopInternal();
        Platform.runLater(() ->
        {
            state.set(State.STOPPED);
            playing.set(false);
            positionSamples.set(0L);
        });
    }

    /**
     * Moves the playback position to the given time in seconds.
     * Safe to call during playback, while paused, or while stopped.
     * The {@code seekRequested} flag tells the playback loop to
     * honour the new position instead of advancing past the buffer
     * it had already started (see class-level Javadoc on seek
     * coordination).
     *
     * @param sec  target position in seconds from the start
     */
    public synchronized void seekTo(double sec)
    {
        if (sourceSamples == null || sampleRate <= 0)
        {
            return;
        }
        long frames = Math.round(sec * sampleRate);
        long totalFrames = sourceSamples.length / channels;
        if (frames < 0) frames = 0;
        if (frames > totalFrames) frames = totalFrames;
        positionFrames = frames;
        audiblePositionFrames = frames;
        seekRequested = true;
        final long fFrames = frames;
        Platform.runLater(() -> positionSamples.set(fFrames));
    }

    /**
     * Toggles A/B comparison mode. When enabled, playback bypasses
     * the {@link ProcessingPipeline} and plays the original samples,
     * delayed by the chain's current latency so the comparison stays
     * time-aligned. Does not interrupt the playback position, so the
     * user can toggle freely between processed and original audio
     * while the audio is sounding.
     */
    public synchronized void toggleAB()
    {
        boolean newMode = !abBypass;
        abBypass = newMode;
        Platform.runLater(() -> abMode.set(newMode));
    }

    /**
     * Sets a loop region in source frames. While playing with a valid
     * region, reaching {@code endFrame} jumps back to {@code startFrame}
     * sample-accurately. Pass {@code endFrame <= startFrame} to disable.
     *
     * @param startFrame  loop start (inclusive), in source frames
     * @param endFrame    loop end (exclusive), in source frames
     */
    public synchronized void setLoopRegion(long startFrame, long endFrame)
    {
        if (sourceSamples == null) return;
        long totalFrames = sourceSamples.length / channels;
        long s = Math.max(0, Math.min(startFrame, totalFrames));
        long e = Math.max(0, Math.min(endFrame, totalFrames));
        if (e > s)
        {
            loopStartFrames = s;
            loopEndFrames = e;
            loopEnabled = true;
        }
        else
        {
            loopEnabled = false;
        }
    }

    /** Disables the loop region. */
    public synchronized void clearLoopRegion() { loopEnabled = false; }

    /** True while a loop region is active. */
    public boolean isLooping() { return loopEnabled; }

    /* =========================================================
     *  Property accessors (for UI binding)
     * ========================================================= */

    /** Registers a tap fed each processed output buffer (for live metering). */
    public void setMeterTap(java.util.function.ObjIntConsumer<float[]> tap) { this.meterTap = tap; }

    /**
     * Sets the live oversampling factor (a power of two, 1..16). The chain then
     * runs at {@code factor x} the base sample rate during playback. Takes
     * effect on the next buffer while playing, or on the next {@link #play()}.
     *
     * @param factor  1 (off), 2, 4, 8 or 16; other values are rounded down to a power of two
     */
    public void setOversampling(int factor)
    {
        int f = (factor < 1) ? 1 : Math.min(factor, 16);
        this.osFactor = Integer.highestOneBit(f);   // snap to a power of two
        this.osDirty = true;
    }

    /** Marks the chain analysis valid (or not) for the current source. When valid, a
     *  playback start reuses it instead of re-running the whole-file analyze pass. */
    public void setAnalysisValid(boolean valid) { this.analysisValid = valid; }

    /**
     * Plays a pre-rendered, source-aligned output buffer directly instead of
     * running the live pipeline. Passing {@code null} restores live processing.
     * The swap is a single volatile write, safe to call during playback: the
     * audio thread picks up the new buffer on its next block. Used by the A/B
     * settings switch so toggling between two rendered slots is instant.
     *
     * @param render a full-length, source-aligned render, or {@code null} for live
     */
    public void setFixedRender(float[] render) { this.fixedRender = render; }

    /** True while a pre-rendered slot is being played back (see {@link #setFixedRender}). */
    public boolean hasFixedRender() { return fixedRender != null; }

    /**
     * (Re)prepares the chain at the current oversampling factor and returns it.
     * At {@code 1} the pipeline runs at the base rate; above it the pipeline is
     * prepared at {@code factor x} the base rate and {@link #osEngine} performs
     * the up/down conversion. Runs on the audio thread.
     */
    private int applyOversampling()
    {
        int f = osFactor;
        osDirty = false;
        if (f <= 1)
        {
            pipeline.prepare(sampleRate, sourceSamples.length);
            return 1;
        }
        long osTotal = (long) sourceSamples.length * f;
        pipeline.prepare(sampleRate * f, osTotal);
        osEngine.prepare(f, channels, BUFFER_FRAMES, OversamplingEngine.Quality.HIGH);
        return f;
    }

    public BooleanProperty playingProperty()      { return playing; }
    public LongProperty positionSamplesProperty() { return positionSamples; }
    public BooleanProperty abModeProperty()       { return abMode; }
    public ObjectProperty<State> stateProperty()  { return state; }

    public boolean isPlaying()           { return playing.get(); }

    /** The audible position in source frames (latency-compensated while playing). */
    public long getPositionSamples()     { return audiblePositionFrames; }

    public boolean isAbMode()            { return abMode.get(); }
    public State getState()              { return state.get(); }
    public ProcessingPipeline getPipeline() { return pipeline; }

    /* =========================================================
     *  Internal: the playback loop
     * ========================================================= */

    /**
     * Chain + oversampler latency referred to base-rate frames, under the
     * current preparation. Audio-thread only.
     */
    private int chainLatencyBaseFrames(int factor)
    {
        int hi = pipeline.getLatencyFrames();
        if (factor <= 1) return hi;
        return (int) Math.round(hi / (double) factor) + osEngine.getLatencyBaseFrames();
    }

    /** Grows/shrinks the A/B bypass delay line to the given latency (frames). */
    private void ensureAbDelay(int latencyBaseFrames)
    {
        int needed = Math.max(0, latencyBaseFrames) * channels;
        if (abDelay.length != needed)
        {
            abDelay = new float[needed];
            abDelayPos = 0;
        }
    }

    /**
     * Feeds the original block into the bypass delay line and (in place)
     * replaces it with the delayed signal, so the bypass path carries the
     * same latency as the processed path.
     */
    private void delayBypass(float[] buf, int count)
    {
        if (abDelay.length == 0) return;
        for (int i = 0; i < count; i++)
        {
            float in = buf[i];
            buf[i] = abDelay[abDelayPos];
            abDelay[abDelayPos] = in;
            abDelayPos++;
            if (abDelayPos == abDelay.length) abDelayPos = 0;
        }
    }

    /**
     * Audio thread body. Opens a {@link SourceDataLine}, primes the
     * pipeline once (so peak analysis and parameter smoothing reset
     * cleanly), then loops: read a buffer from the source, run it
     * through the pipeline unless A/B bypass is active, convert to
     * 16-bit PCM (TPDF-dithered), write to the audio line. The write
     * call blocks when the OS buffer is full, which is what keeps the
     * loop in sync with real-time playback rate. The loop honours
     * pause, seek and loop-region flags between buffers, and after the
     * source ends it keeps feeding silence until the chain's latency
     * tail has flushed, so the end of the audio is fully heard.
     */
    private void playbackLoop()
    {
        AudioFormat outFormat = new AudioFormat(
                sampleRate, 16, channels, true, false);    // signed, little-endian
        try
        {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, outFormat);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(outFormat, BUFFER_FRAMES * channels * 2 * 4);
            line.start();
        }
        catch (LineUnavailableException e)
        {
            AppLogger.error("Audio output line unavailable.", e);
            Platform.runLater(() ->
            {
                state.set(State.STOPPED);
                playing.set(false);
            });
            return;
        }

        // Prepare for this audio: base-rate analysis (peak-normalizer gain),
        // then (re)prepare at the oversampled rate for the actual render. A
        // pre-rendered slot (A/B compare) plays back directly, so the live
        // pipeline needs neither analysis nor preparation here.
        pipeline.prepare(sampleRate, sourceSamples.length);
        if (fixedRender == null && !analysisValid)
        {
            pipeline.analyze(sourceSamples, channels);   // only if the UI has not already analysed
            analysisValid = true;
        }
        int curFactor = applyOversampling();
        monitorDither.reset();

        final int bytesPerFrame = channels * 2;            // 16-bit
        final byte[] outBuffer = new byte[BUFFER_FRAMES * bytesPerFrame];

        long totalFrames = sourceSamples.length / channels;
        long flushRemaining = -1L;     // >= 0 while flushing the chain tail after the source ends

        try
        {
            while (!stopRequested)
            {
                // Handle pause: sleep until resumed or stopped.
                if (paused)
                {
                    try { Thread.sleep(20); } catch (InterruptedException ie) { /* ignore */ }
                    continue;
                }

                // A pre-rendered slot (A/B compare) plays back directly, bypassing
                // the live pipeline; it is source-aligned, so it carries no chain
                // latency. Captured once per buffer (the reference is volatile).
                final float[] render = fixedRender;
                int latBase = (render != null) ? 0 : chainLatencyBaseFrames(curFactor);

                long start;
                long framesThisBuffer;
                boolean flushing;
                synchronized (this)
                {
                    // Loop region: jump back sample-accurately at the boundary.
                    if (loopEnabled && positionFrames >= loopEndFrames
                            && loopEndFrames > loopStartFrames)
                    {
                        positionFrames = loopStartFrames;
                    }
                    if (positionFrames >= totalFrames)
                    {
                        if (flushRemaining < 0) flushRemaining = latBase;
                        if (flushRemaining <= 0) break;
                        flushing = true;
                        start = positionFrames;
                        framesThisBuffer = Math.min(BUFFER_FRAMES, flushRemaining);
                    }
                    else
                    {
                        flushing = false;
                        start = positionFrames;
                        long limit = totalFrames;
                        if (loopEnabled && loopEndFrames > start) limit = loopEndFrames;
                        framesThisBuffer = Math.min(BUFFER_FRAMES, limit - start);
                    }
                    // Clear the seek flag now we have captured the start.
                    seekRequested = false;
                }

                // Build this buffer's output: either a pre-rendered slot (played
                // back directly) or the live pipeline.
                int samplesThisBuffer = (int) (framesThisBuffer * channels);
                float[] out;
                float[] meteredOutput;
                if (render != null)
                {
                    // Pre-rendered, source-aligned slot output. Keep the master in
                    // a separate buffer while bypass is active: playback may use
                    // the source, but metering must remain post-processing.
                    float[] mastered = new float[samplesThisBuffer];
                    if (!flushing)
                    {
                        int off = (int) (start * channels);
                        int n = Math.min(samplesThisBuffer, render.length - off);
                        if (n > 0) System.arraycopy(render, off, mastered, 0, n);
                    }
                    meteredOutput = mastered;
                    if (abBypass && !flushing)
                    {
                        out = new float[samplesThisBuffer];
                        int off = (int) (start * channels);
                        int n = Math.min(samplesThisBuffer, sourceSamples.length - off);
                        if (n > 0) System.arraycopy(sourceSamples, off, out, 0, n);
                    }
                    else out = mastered;
                }
                else
                {
                    // Copy the slice from the source into a per-buffer working array
                    // (silence while flushing the chain tail), so the in-place
                    // pipeline cannot corrupt the original samples.
                    float[] working = new float[samplesThisBuffer];
                    if (!flushing)
                    {
                        System.arraycopy(sourceSamples, (int) (start * channels),
                                working, 0, samplesThisBuffer);
                    }

                    // Re-prepare the chain if the oversampling factor changed.
                    if (osDirty)
                    {
                        curFactor = applyOversampling();
                        latBase = chainLatencyBaseFrames(curFactor);
                    }

                    // Index every position-aware stage by the true source position
                    // (in current-rate frames). At an oversampled rate the signal
                    // additionally lags by the up-path delay, which is subtracted so
                    // precomputed envelopes land on the audio they were built for.
                    if (curFactor == 1)
                    {
                        pipeline.setPlaybackPosition(start);
                    }
                    else
                    {
                        pipeline.setPlaybackPosition(
                                start * curFactor - osEngine.getUpsampleLatencyHiFrames());
                    }

                    // Keep the bypass delay line matched to the current latency and
                    // feed it the original block, so an A/B toggle stays aligned.
                    ensureAbDelay(latBase);
                    float[] original = working.clone();
                    delayBypass(original, samplesThisBuffer);

                    // Apply the pipeline unless A/B bypass is active. When
                    // oversampling, upsample the block, run the chain at the high
                    // rate (in sub-blocks the stages can handle), then downsample.
                    if (curFactor == 1)
                    {
                        working = pipeline.execute(working, channels);
                    }
                    else
                    {
                        float[] up = osEngine.upsample(working, (int) framesThisBuffer);
                        float[] hi = pipeline.executeBlocks(up, channels,
                                ProcessingPipeline.OFFLINE_BLOCK_FRAMES);
                        osEngine.downsample(hi, (int) framesThisBuffer, working);
                    }
                    meteredOutput = working;
                    out = abBypass ? original : working;
                }

                // Always feed the processed master, never the audible bypass source.
                java.util.function.ObjIntConsumer<float[]> tap = meterTap;
                if (tap != null) tap.accept(meteredOutput, channels);

                // Convert float [-1,+1] to dithered signed 16-bit little-endian.
                floatToPcm16Le(out, outBuffer, samplesThisBuffer);

                // Write to the output line. This call blocks if the line
                // buffer is full, keeping the loop in sync with the audio
                // hardware's actual playback rate.
                line.write(outBuffer, 0, samplesThisBuffer * 2);

                // Advance position unless a seek arrived during this buffer.
                // In that case, the new position is already in positionFrames
                // and must not be overwritten.
                synchronized (this)
                {
                    if (!seekRequested)
                    {
                        positionFrames = start + framesThisBuffer;
                        if (flushing) flushRemaining -= framesThisBuffer;
                    }
                    else
                    {
                        flushRemaining = -1L;   // seek cancels an in-progress flush
                    }
                    // Publish the audible position: the source frame actually
                    // sounding now, i.e. the read cursor minus the latency.
                    long audible = positionFrames - latBase;
                    if (audible < 0) audible = 0;
                    if (audible > totalFrames) audible = totalFrames;
                    audiblePositionFrames = audible;
                }
                final long fPos = audiblePositionFrames;
                Platform.runLater(() -> positionSamples.set(fPos));
            }
        }
        finally
        {
            try { line.drain(); } catch (Exception ignored) {}
            try { line.stop();  } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
            line = null;

            // If we reached the end naturally, reset state.
            if (!stopRequested && positionFrames >= totalFrames)
            {
                positionFrames = 0L;
                audiblePositionFrames = 0L;
                Platform.runLater(() ->
                {
                    state.set(State.STOPPED);
                    playing.set(false);
                    positionSamples.set(0L);
                });
            }
        }
    }

    /**
     * Shuts down the playback thread cleanly without firing UI
     * state updates (the caller is responsible for that). Joins
     * with a short timeout so a misbehaving audio backend cannot
     * hang the UI indefinitely.
     */
    private void stopInternal()
    {
        stopRequested = true;
        paused = false;
        Thread t = playbackThread;
        if (t != null && t.isAlive())
        {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        playbackThread = null;
        positionFrames = 0L;
        audiblePositionFrames = 0L;
    }

    /**
     * Converts an interleaved float buffer ([-1.0, +1.0]) to signed
     * 16-bit PCM little-endian bytes with TPDF dither, so the monitor
     * conversion carries a flat noise floor instead of truncation
     * distortion. Samples outside the range are clamped to {±1.0}.
     *
     * @param src    source float samples
     * @param dst    target byte buffer, must be at least {@code count*2} bytes
     * @param count  number of float samples to convert
     */
    private void floatToPcm16Le(float[] src, byte[] dst, int count)
    {
        int ch = Math.max(1, channels);
        for (int i = 0; i < count; i++)
        {
            float s = src[i];
            if (s >  1.0f) s =  1.0f;
            if (s < -1.0f) s = -1.0f;
            float q = monitorDither.processSample(s, i % ch);
            int v = (int) Math.rint(q * 32768.0);
            if (v >  32767) v =  32767;
            if (v < -32768) v = -32768;
            dst[i * 2]     = (byte) (v & 0xFF);
            dst[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }
}
