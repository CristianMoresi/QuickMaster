package com.quickmaster.playback;

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
 * {@link SourceDataLine}. Supports play, pause, stop, seek and the
 * A/B comparison toggle that bypasses the pipeline to play the
 * original audio for instant comparison.
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
 * output line. This format is supported on every platform the JDK
 * runs on and matches the most common consumer hardware. Higher
 * resolutions (24-bit, float) would be achievable but provide no
 * audible benefit during playback; the downstream export path
 * still preserves the source file's full bit depth.
 * <p>
 * <b>Observable state.</b> JavaFX properties expose the player's
 * state to the UI:
 * <ul>
 *   <li>{@link #playingProperty()} - true while audio is sounding</li>
 *   <li>{@link #positionSamplesProperty()} - frame index in the audio</li>
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

    /* Optional metering tap: fed every processed output buffer from the audio
       thread, so the UI can show live loudness/peak/GR meters. */
    private volatile java.util.function.ObjIntConsumer<float[]> meterTap;

    /* Playback position in frames. Mutations from the UI thread
       (seek) and from the audio thread (loop advance) are serialised
       via synchronized blocks on the player instance. */
    private long positionFrames = 0L;

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
        this.seekRequested = false;
        this.analysisValid = false;   // new source: must be (re)analysed before the next play

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
        seekRequested = true;
        final long fFrames = frames;
        Platform.runLater(() -> positionSamples.set(fFrames));
    }

    /**
     * Toggles A/B comparison mode. When enabled, playback bypasses
     * the {@link ProcessingPipeline} and plays the original samples
     * unaltered. Does not interrupt playback position, so the user
     * can toggle freely between processed and original audio while
     * the audio is sounding.
     */
    public synchronized void toggleAB()
    {
        boolean newMode = !abBypass;
        abBypass = newMode;
        Platform.runLater(() -> abMode.set(newMode));
    }

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

    /** Makes the next {@link #play()} skip the whole-file analyze pass. */
    /** Marks the chain analysis valid (or not) for the current source. When valid, a
     *  playback start reuses it instead of re-running the whole-file analyze pass. */
    public void setAnalysisValid(boolean valid) { this.analysisValid = valid; }

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
        pipeline.prepare(sampleRate * f, (int) Math.min(Integer.MAX_VALUE, osTotal));
        osEngine.prepare(f, channels, BUFFER_FRAMES, OversamplingEngine.Quality.HIGH);
        return f;
    }

    public BooleanProperty playingProperty()      { return playing; }
    public LongProperty positionSamplesProperty() { return positionSamples; }
    public BooleanProperty abModeProperty()       { return abMode; }
    public ObjectProperty<State> stateProperty()  { return state; }

    public boolean isPlaying()           { return playing.get(); }
    public long getPositionSamples()     { return positionFrames; }
    public boolean isAbMode()            { return abMode.get(); }
    public State getState()              { return state.get(); }
    public ProcessingPipeline getPipeline() { return pipeline; }

    /* =========================================================
     *  Internal: the playback loop
     * ========================================================= */

    /**
     * Audio thread body. Opens a {@link SourceDataLine}, primes the
     * pipeline once (so peak analysis and parameter smoothing reset
     * cleanly), then loops: read a buffer from the source, run it
     * through the pipeline unless A/B bypass is active, convert to
     * 16-bit PCM, write to the audio line. The write call blocks
     * when the OS buffer is full, which is what keeps the loop in
     * sync with real-time playback rate. The loop honours pause and
     * seek flags between buffers.
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
        // then (re)prepare at the oversampled rate for the actual render.
        pipeline.prepare(sampleRate, sourceSamples.length);
        if (!analysisValid)
        {
            pipeline.analyze(sourceSamples, channels);   // only if the UI has not already analysed
            analysisValid = true;
        }
        int curFactor = applyOversampling();

        final int bytesPerFrame = channels * 2;            // 16-bit
        final byte[] outBuffer = new byte[BUFFER_FRAMES * bytesPerFrame];

        long totalFrames = sourceSamples.length / channels;

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

                long start;
                long framesThisBuffer;
                synchronized (this)
                {
                    if (positionFrames >= totalFrames)
                    {
                        break;
                    }
                    start = positionFrames;
                    framesThisBuffer = Math.min(BUFFER_FRAMES, totalFrames - start);
                    // Clear the seek flag now we have captured the start.
                    seekRequested = false;
                }

                // Copy the slice from the source into a per-buffer working array
                // so the in-place pipeline cannot corrupt the original samples.
                int samplesThisBuffer = (int) (framesThisBuffer * channels);
                float[] working = new float[samplesThisBuffer];
                System.arraycopy(sourceSamples, (int) (start * channels),
                        working, 0, samplesThisBuffer);

                // Re-prepare the chain if the oversampling factor changed.
                if (osDirty) curFactor = applyOversampling();

                // Index every position-aware stage by the true source position
                // (in current-rate frames), so a seek or a start from a non-zero
                // position stays aligned with the precomputed analysis maps.
                pipeline.setPlaybackPosition(start * curFactor);

                // Apply the pipeline unless A/B bypass is active. When
                // oversampling, upsample the block, run the chain at the high
                // rate (in sub-blocks the stages can handle), then downsample.
                if (!abBypass)
                {
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
                }

                // Feed the processed output to the live metering tap, if any.
                java.util.function.ObjIntConsumer<float[]> tap = meterTap;
                if (tap != null) tap.accept(working, channels);

                // Convert float [-1,+1] to signed 16-bit little-endian bytes.
                floatToPcm16Le(working, outBuffer, samplesThisBuffer);

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
                    }
                }
                final long fPos;
                synchronized (this) { fPos = positionFrames; }
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
    }

    /**
     * Converts an interleaved float buffer ([-1.0, +1.0]) to signed
     * 16-bit PCM little-endian bytes. Samples outside the range are
     * clamped to {±1.0} so that no out-of-range samples reach the
     * output.
     *
     * @param src    source float samples
     * @param dst    target byte buffer, must be at least {@code count*2} bytes
     * @param count  number of float samples to convert
     */
    private static void floatToPcm16Le(float[] src, byte[] dst, int count)
    {
        for (int i = 0; i < count; i++)
        {
            float s = src[i];
            if (s >  1.0f) s =  1.0f;
            if (s < -1.0f) s = -1.0f;
            int v = Math.round(s * 32767.0f);
            dst[i * 2]     = (byte) (v & 0xFF);
            dst[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }
}