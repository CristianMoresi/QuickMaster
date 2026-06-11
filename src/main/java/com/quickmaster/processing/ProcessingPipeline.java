package com.quickmaster.processing;

import com.dspark.core.OversamplingEngine;
import com.quickmaster.audio.AudioFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.ObjIntConsumer;

/**
 * Ordered chain of {@link AudioProcessor} instances that are
 * applied sequentially to an audio buffer. The pipeline is the
 * single entry point through which all DSP transformations are
 * executed in QuickMaster.
 * <p>
 * The pipeline owns a mutable, ordered list of processors. The
 * order in which processors are added is the order in which
 * they are invoked: each processor receives the output of the
 * previous one. The caller is responsible for choosing a
 * sensible order (typically low-cut, then EQ, then the stereo
 * stage, then fades, then normalization and limiting), but the
 * pipeline does not
 * enforce any particular arrangement - that is a deliberate
 * design choice that leaves the chain configuration in the
 * hands of the application/user.
 * <p>
 * <b>Lifecycle.</b> A full processing pass goes through three
 * phases that mirror the {@link AudioProcessor} contract:
 * <ol>
 *   <li>{@link #prepare(int, long)} - propagates sample rate
 *       and total sample count to every processor so they can
 *       configure rate-dependent state (smoothers, fade
 *       envelopes, etc.).</li>
 *   <li>{@link #analyze(float[], int)} - gives every processor
 *       a chance to inspect the entire audio. Most processors
 *       inherit the default no-op; only processors like
 *       {@link PeakNormalizer} actually do work here.</li>
 *   <li>{@link #execute(float[], int)} - runs each processor
 *       in order on the audio buffer and returns the
 *       transformed result.</li>
 * </ol>
 * The convenience method {@link #process(AudioFile)} performs
 * all three phases in sequence and stores the result back into
 * the {@link AudioFile}, which is the typical usage pattern
 * from the application layer.
 * <p>
 * <b>Single cumulative analysis pass.</b> Stages that report
 * {@link AudioProcessor#usesAnalysis()} must see their <i>actual</i>
 * input: the source rendered through every upstream stage. Rather than
 * rendering one upstream prefix per such stage (which costs one full
 * render of the file per analysis stage), {@link #analyzeAndRender}
 * streams the source through the chain once, stage by stage: each
 * stage is analysed on the accumulated buffer and then applied to it,
 * with its latency compensated immediately, so every stage (and the
 * final result) stays time-aligned with the source. The whole analysis
 * therefore costs one full render, and that render <i>is</i> the
 * processed output, which {@link #process(AudioFile)} stores directly.
 * <p>
 * <b>Disabled processors.</b> Each processor manages its own
 * enabled flag. The pipeline does not skip disabled
 * processors - it still calls their {@code process} method -
 * because each processor is contractually obliged to act as a
 * passthrough when disabled. This keeps the pipeline logic
 * uniform and avoids special-casing.
 */
public class ProcessingPipeline
{
    /**
     * Block size, in frames, used by the offline renderer in
     * {@link #process(AudioFile)}. Bounded so that block- and
     * look-ahead-oriented stages (a linear-phase FFT equalizer, a
     * look-ahead limiter) receive blocks no larger than they are
     * prepared for. Matches the player's real-time buffer size so a
     * single block bound holds across both processing paths.
     */
    public static final int OFFLINE_BLOCK_FRAMES = 1024;

    private final List<AudioProcessor> processors;

    // Last prepare() arguments, retained so the analysis pass can reset
    // stateful processors around its single cumulative render.
    private int lastSampleRate = 0;
    private long lastTotalSamples = 0;

    /**
     * Creates an empty pipeline with no processors. Use
     * {@link #addProcessor(AudioProcessor)} to populate it.
     */
    public ProcessingPipeline()
    {
        this.processors = new ArrayList<>();
    }

    /**
     * Appends a processor to the end of the chain.
     *
     * @param processor  the processor to add (must not be null)
     * @throws IllegalArgumentException if {@code processor} is null
     */
    public void addProcessor(AudioProcessor processor)
    {
        if (processor == null)
        {
            throw new IllegalArgumentException("Processor must not be null.");
        }
        processors.add(processor);
    }

    /**
     * Removes a previously added processor from the chain.
     * Has no effect if the processor is not present.
     *
     * @param processor  the processor to remove
     * @return true if the processor was present and removed
     */
    public boolean removeProcessor(AudioProcessor processor)
    {
        return processors.remove(processor);
    }

    /**
     * Removes all processors from the chain, leaving an empty
     * pipeline.
     */
    public void clear()
    {
        processors.clear();
    }

    /**
     * Replaces the whole chain with the given processors, in order. Used by
     * the UI when the user reorders the processing chain (the chain tab bar):
     * the order in {@code newOrder} becomes the new signal-flow order. Null
     * entries are skipped. Callers should re-run {@link #prepare(int, long)}
     * afterwards so every processor reconfigures for the current audio.
     *
     * @param newOrder  processors in the desired execution order
     */
    public void setProcessors(java.util.List<AudioProcessor> newOrder)
    {
        processors.clear();
        if (newOrder != null)
        {
            for (AudioProcessor p : newOrder)
            {
                if (p != null) processors.add(p);
            }
        }
    }

    /**
     * Returns an unmodifiable view of the current processor list,
     * for inspection (UI, logging, tests). Modifications to the
     * pipeline must go through
     * {@link #addProcessor(AudioProcessor)},
     * {@link #removeProcessor(AudioProcessor)} or {@link #clear()}.
     *
     * @return read-only list of processors in execution order
     */
    public List<AudioProcessor> getProcessors()
    {
        return Collections.unmodifiableList(processors);
    }

    /**
     * Propagates {@code prepare} to every processor in order.
     * Must be called before {@link #execute(float[], int)}
     * whenever the audio source changes (new file loaded,
     * trim applied, sample rate changed).
     *
     * @param sampleRate    audio sample rate in Hz
     * @param totalSamples  total interleaved samples in the audio
     */
    public void prepare(int sampleRate, long totalSamples)
    {
        this.lastSampleRate = sampleRate;
        this.lastTotalSamples = totalSamples;
        for (AudioProcessor p : processors)
        {
            p.prepare(sampleRate, totalSamples);
        }
    }

    /**
     * Runs the whole-signal pre-scan for every processor that needs it
     * (those reporting {@link AudioProcessor#usesAnalysis()}), feeding
     * each one its <i>actual</i> input - the source rendered through all
     * upstream processors - rather than the raw original samples.
     * <p>
     * Implemented as one cumulative pass (see the class Javadoc): the
     * whole analysis costs a single full render of the source. The
     * rendered output is discarded here; callers that also want the
     * render should use {@link #analyzeAndRender} directly. The method
     * returns with every processor freshly reset, ready for a subsequent
     * {@link #execute} (playback) pass. Must be called after
     * {@link #prepare(int, long)}.
     *
     * @param samples   interleaved float samples (entire audio)
     * @param channels  number of channels (1 or 2)
     */
    public void analyze(float[] samples, int channels)
    {
        analyzeAndRender(samples, channels, 0, null, null, null);
    }

    /**
     * The single cumulative analysis-and-render pass. Streams the source
     * through the chain stage by stage: each analysis stage is given the
     * accumulated (upstream-processed, latency-aligned) buffer, then the
     * stage is applied to it in {@link #OFFLINE_BLOCK_FRAMES}-frame blocks
     * with its own latency compensated (a silent tail flushes its delay
     * line and the same number of leading frames is dropped), so the
     * buffer entering every stage - and the returned result - is exactly
     * time-aligned with the source.
     * <p>
     * The processors are used from a freshly prepared state and the
     * method re-prepares them before returning, so the pipeline is left
     * reset for a subsequent streaming pass.
     *
     * @param samples     interleaved source samples (entire audio)
     * @param channels    channel count
     * @param startStage  index of the first stage to run (0 = whole chain)
     * @param startBuffer when {@code startStage > 0}, the latency-aligned
     *                    signal as it leaves stage {@code startStage - 1}
     *                    (from a previous pass's {@code stageTap}); must
     *                    not be mutated by the caller afterwards
     * @param progress    progress sink in [0, 1], or {@code null}
     * @param stageTap    receives the accumulated buffer after each stage
     *                    (buffer, stageIndex); the buffer must be treated
     *                    as read-only; {@code null} for none
     * @return the fully processed, latency-aligned render (same length as
     *         {@code samples}); the source array itself when the chain or
     *         the stage range is empty
     */
    public float[] analyzeAndRender(float[] samples, int channels,
                                    int startStage, float[] startBuffer,
                                    DoubleConsumer progress,
                                    ObjIntConsumer<float[]> stageTap)
    {
        int n = processors.size();
        prepare(lastSampleRate, lastTotalSamples);

        float[] current = (startStage > 0 && startBuffer != null) ? startBuffer : samples;
        for (int i = Math.max(0, startStage); i < n; i++)
        {
            AudioProcessor p = processors.get(i);
            if (p.usesAnalysis())
            {
                p.analyze(current, channels);
            }
            final int stageIndex = i;
            final int stageCount = n - Math.max(0, startStage);
            DoubleConsumer stageProgress = (progress == null) ? null
                    : frac -> progress.accept(
                            (stageIndex - Math.max(0, startStage) + frac) / stageCount);
            current = renderStage(p, current, channels, stageProgress);
            if (stageTap != null) stageTap.accept(current, i);
        }
        if (progress != null) progress.accept(1.0);

        // Leave every processor reset for the caller's subsequent
        // execute() (playback) or render pass.
        prepare(lastSampleRate, lastTotalSamples);
        return current;
    }

    /**
     * Renders {@code input} through one processor, streaming it (then a
     * silent flush tail) in blocks of at most {@link #OFFLINE_BLOCK_FRAMES}
     * frames and dropping the stage's leading latency, so the result is
     * the same length as the input and time-aligned with it. Never
     * mutates {@code input}.
     */
    private static float[] renderStage(AudioProcessor p, float[] input, int channels,
                                       DoubleConsumer progress)
    {
        int totalFrames   = input.length / channels;
        int latencyFrames = p.getLatencyFrames();
        int outFrames     = totalFrames + latencyFrames;
        float[] output    = new float[outFrames * channels];

        int frameCursor = 0;
        while (frameCursor < outFrames)
        {
            int blockFrames = Math.min(OFFLINE_BLOCK_FRAMES, outFrames - frameCursor);
            float[] block = new float[blockFrames * channels];

            int copyFrames = Math.min(blockFrames, Math.max(0, totalFrames - frameCursor));
            if (copyFrames > 0)
            {
                System.arraycopy(input, frameCursor * channels,
                        block, 0, copyFrames * channels);
            }
            // Frames past the end of the input stay zero (the flush tail).

            float[] processed = p.process(block, channels);
            System.arraycopy(processed, 0, output,
                    frameCursor * channels, blockFrames * channels);
            frameCursor += blockFrames;
            if (progress != null) progress.accept(frameCursor / (double) outFrames);
        }

        if (latencyFrames == 0)
        {
            return output;
        }
        float[] aligned = new float[totalFrames * channels];
        System.arraycopy(output, latencyFrames * channels,
                aligned, 0, totalFrames * channels);
        return aligned;
    }

    /**
     * Runs the pipeline on the given buffer, invoking each
     * processor in order. The output of each processor is fed
     * as input to the next.
     *
     * @param buffer    interleaved float samples to process
     * @param channels  number of channels (1 or 2)
     * @return the transformed buffer (may be the same reference
     *         as {@code buffer} or a new array, depending on
     *         the processors involved)
     */
    public float[] execute(float[] buffer, int channels)
    {
        float[] current = buffer;
        for (AudioProcessor p : processors)
        {
            current = p.process(current, channels);
        }
        return current;
    }

    /** Tells every stage the source-frame index of the next buffer (see
     * {@link AudioProcessor#setPlaybackPosition(long)}). */
    public void setPlaybackPosition(long frame)
    {
        for (AudioProcessor p : processors)
        {
            p.setPlaybackPosition(frame);
        }
    }

    /**
     * Like {@link #execute} but streams the buffer through the chain in
     * sub-blocks of at most {@code maxFrames} frames, so block-oriented stages
     * (a linear-phase FFT EQ, a look-ahead limiter) never receive more than they
     * are prepared for. Processor state is carried across sub-blocks, so the
     * result equals one continuous pass. Used by the oversampled playback path,
     * where an upsampled block can exceed a stage's maximum block size.
     *
     * @param buffer    interleaved float samples
     * @param channels  channel count
     * @param maxFrames  maximum frames per sub-block (e.g. {@link #OFFLINE_BLOCK_FRAMES})
     * @return the transformed buffer (a new array when sub-blocking occurs)
     */
    public float[] executeBlocks(float[] buffer, int channels, int maxFrames)
    {
        int totalFrames = buffer.length / channels;
        if (totalFrames <= maxFrames)
        {
            return execute(buffer, channels);
        }
        float[] out = new float[buffer.length];
        int cursor = 0;
        while (cursor < totalFrames)
        {
            int n = Math.min(maxFrames, totalFrames - cursor);
            float[] chunk = new float[n * channels];
            System.arraycopy(buffer, cursor * channels, chunk, 0, n * channels);
            float[] processed = execute(chunk, channels);
            System.arraycopy(processed, 0, out, cursor * channels, n * channels);
            cursor += n;
        }
        return out;
    }

    /**
     * Convenience method for offline rendering: runs the full
     * {@code prepare → analyze-and-render} pass on the audio held by an
     * {@link AudioFile} and stores the result back into the file's
     * editable sample buffer. This is the standard entry point used by
     * the application's export path.
     * <p>
     * The audio is streamed through the chain in blocks of at most
     * {@link #OFFLINE_BLOCK_FRAMES} frames - the same granularity used
     * during live playback - with every stage's latency compensated, so
     * the rendered buffer has exactly the same length as the source and
     * is time-aligned with it, with no warm-up delay and no lost tail.
     * <p>
     * The original samples held by the file are not affected:
     * processing reads from the editable buffer into freshly allocated
     * blocks and writes a new buffer back, leaving the input array (and
     * the non-destructive snapshot behind it) intact.
     *
     * @param file  the audio file to process; must already be
     *              loaded (samples populated)
     * @throws IllegalArgumentException if {@code file} has no
     *         samples loaded
     */
    public void process(AudioFile file)
    {
        process(file, null);
    }

    /**
     * Like {@link #process(AudioFile)} but reports rendering progress in
     * {@code [0, 1]} through {@code progress} (may be {@code null}). The callback
     * is invoked from the calling thread as blocks are rendered, so a UI can
     * drive a progress bar off a background task.
     *
     * @param file      the audio file to process; must already be loaded
     * @param progress  progress sink in [0, 1], or {@code null} for none
     */
    public void process(AudioFile file, DoubleConsumer progress)
    {
        if (file == null)
        {
            throw new IllegalArgumentException("AudioFile must not be null.");
        }
        float[] input = file.getSamples();
        if (input == null || input.length == 0)
        {
            throw new IllegalArgumentException(
                    "AudioFile has no samples loaded; call load() or set samples first.");
        }

        int sampleRate = file.getSampleRate();
        int channels   = file.getChannels();

        prepare(sampleRate, input.length);
        float[] output = analyzeAndRender(input, channels, 0, null, progress, null);
        file.setSamples(output);
    }

    /**
     * Offline render with power-of-two <b>oversampling</b>, bounded in memory.
     * Mirrors what the live player does, but for the whole file and with latency
     * compensation: the chain is analysed at the base rate (so e.g. the peak
     * normalizer's gain is correct), prepared at {@code factor ×} the base rate,
     * then driven one base block at a time through
     * {@code upsample → chain → downsample}, carrying the oversampler's and the
     * processors' state across blocks so the result is one continuous pass.
     * <p>
     * Crucially, only a single block is ever upsampled at a time - never the
     * whole file - so peak memory stays at roughly the output size regardless of
     * the factor.
     * <p>
     * The leading {@code oversampler + chain} latency is dropped and an equal
     * silent tail is fed to flush the delay lines, so the rendered buffer has the
     * same length as the source and is time-aligned with it. {@code factor <= 1}
     * delegates to {@link #process(AudioFile, DoubleConsumer)}.
     *
     * @param file      the audio file to process; must already be loaded
     * @param factor    oversampling factor (1, 2, 4, 8, 16)
     * @param progress  progress sink in [0, 1], or {@code null} for none
     */
    public void processOversampled(AudioFile file, int factor, DoubleConsumer progress)
    {
        if (factor <= 1)
        {
            process(file, progress);
            return;
        }
        if (file == null)
        {
            throw new IllegalArgumentException("AudioFile must not be null.");
        }
        float[] input = file.getSamples();
        if (input == null || input.length == 0)
        {
            throw new IllegalArgumentException(
                    "AudioFile has no samples loaded; call load() or set samples first.");
        }

        int sampleRate = file.getSampleRate();
        int channels   = file.getChannels();
        int baseFrames = input.length / channels;

        // 1) Analyse at the base rate (peak-normalizer gain, etc.), exactly as
        //    process() does. One cumulative pass; leaves every processor reset.
        //    Reported as the first ~35% of the overall progress.
        prepare(sampleRate, input.length);
        DoubleConsumer analyzeProgress = (progress == null) ? null
                : frac -> progress.accept(frac * 0.35);
        analyzeAndRender(input, channels, 0, null, analyzeProgress, null);

        // 2) Prepare the chain at the oversampled rate for the real render.
        long osTotal = (long) input.length * factor;
        prepare(sampleRate * factor, osTotal);

        OversamplingEngine os = new OversamplingEngine();
        os.prepare(factor, channels, OFFLINE_BLOCK_FRAMES, OversamplingEngine.Quality.HIGH);

        // Total warm-up latency in base frames: the oversampler's up→down round
        // trip plus the chain's own latency (measured at the high rate, referred
        // back to the base rate).
        int pipeHiLatency = latencyThrough(processors.size());
        int latBase = os.getLatencyBaseFrames()
                + (int) Math.round(pipeHiLatency / (double) factor);

        // The upsampled signal lags the source by the up-path delay; start the
        // position-aware stages (fades, precomputed envelopes, the Auto EQ
        // render) that far behind so their modulation lands on the audio it
        // was computed for.
        setPlaybackPosition(-os.getUpsampleLatencyHiFrames());

        // Drive (baseFrames + latBase) base frames in - the tail is silence that
        // flushes the chain + engine delay lines - rounded up to whole blocks so
        // the engine always sees a full, valid block (its scratch is block-sized).
        int drive  = baseFrames + latBase;
        int blocks = (drive + OFFLINE_BLOCK_FRAMES - 1) / OFFLINE_BLOCK_FRAMES;

        float[] output  = new float[baseFrames * channels];
        float[] block   = new float[OFFLINE_BLOCK_FRAMES * channels];
        float[] downBuf = new float[OFFLINE_BLOCK_FRAMES * channels];

        for (int b = 0; b < blocks; b++)
        {
            int srcStart = b * OFFLINE_BLOCK_FRAMES;
            int copy = Math.max(0, Math.min(OFFLINE_BLOCK_FRAMES, baseFrames - srcStart));
            if (copy > 0)
            {
                System.arraycopy(input, srcStart * channels, block, 0, copy * channels);
            }
            if (copy < OFFLINE_BLOCK_FRAMES)
            {
                java.util.Arrays.fill(block, copy * channels, OFFLINE_BLOCK_FRAMES * channels, 0.0f);
            }

            float[] up = os.upsample(block, OFFLINE_BLOCK_FRAMES);
            float[] hi = executeBlocks(up, channels, OFFLINE_BLOCK_FRAMES);
            os.downsample(hi, OFFLINE_BLOCK_FRAMES, downBuf);

            // Place this block's output shifted left by latBase, dropping the
            // warm-up so the result is time-aligned with the source.
            int outBase = srcStart - latBase;
            for (int i = 0; i < OFFLINE_BLOCK_FRAMES; i++)
            {
                int dst = outBase + i;
                if (dst >= 0 && dst < baseFrames)
                {
                    System.arraycopy(downBuf, i * channels, output, dst * channels, channels);
                }
            }
            if (progress != null) progress.accept(0.35 + 0.65 * ((b + 1) / (double) blocks));
        }
        file.setSamples(output);
    }

    /**
     * Returns the total processing latency of the chain, in frames: the
     * sum of every processor's {@link AudioProcessor#getLatencyFrames()}
     * under its current configuration. Zero-latency stages - and any
     * disabled stage acting as a passthrough - contribute nothing, so a
     * chain of only real-time processors reports {@code 0}.
     *
     * @return the chain latency in frames (≥ 0)
     */
    public int getLatencyFrames()
    {
        return latencyThrough(processors.size());
    }

    /** Summed latency (frames) of the first {@code count} processors. */
    private int latencyThrough(int count)
    {
        int sum = 0;
        for (int i = 0; i < count; i++)
        {
            sum += processors.get(i).getLatencyFrames();
        }
        return sum;
    }
}
