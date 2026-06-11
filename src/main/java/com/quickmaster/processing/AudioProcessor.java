package com.quickmaster.processing;

/**
 * Common contract implemented by every audio processing module
 * in the QuickMaster pipeline.
 * <p>
 * A processor takes a buffer of interleaved float samples in
 * the normalized range [-1.0, +1.0], applies its specific
 * transformation (volume adjustment, mid/side processing, fading,
 * peak normalization, etc.), and returns the transformed buffer.
 * <p>
 * Processors are designed to be invoked once per audio buffer,
 * which is what makes streaming, real-time playback possible:
 * during playback the player reads a
 * small buffer of samples from the source, passes it through
 * the pipeline, writes the result to the audio output, and
 * repeats. The same processors also work for offline processing
 * by passing the entire file as a single buffer.
 * <p>
 * <b>Lifecycle.</b> Before the first buffer is processed,
 * {@link #prepare(int, long)} must be called to inform the
 * processor of the audio sample rate and total sample count.
 * This pattern, borrowed from professional audio plugin APIs,
 * separates one-off setup work (such as recomputing filter
 * coefficients, sample-rate-aware smoothing factors, or
 * position-dependent envelopes) from the per-sample inner loop,
 * keeping the inner loop as fast as possible. The pipeline calls
 * {@code prepare} whenever a new audio file is loaded or any of
 * its properties change.
 * <p>
 * Most processors only need the sample rate; some, like
 * {@code FadeProcessor}, also need the total number of samples
 * because their effect depends on absolute position within the
 * audio. Passing both values to every processor is simpler than
 * differentiating per-processor APIs and incurs no measurable
 * cost.
 * <p>
 * <b>Whole-audio analysis.</b> Some processors (such as
 * {@code PeakNormalizer}) cannot decide what to do until they
 * have seen the entire audio: their behaviour depends on a
 * global property like the maximum absolute sample value across
 * all frames. The {@link #analyze(float[], int)} hook lets
 * those processors perform a one-shot offline pre-scan over the
 * whole audio before any per-buffer processing begins. The
 * default implementation does nothing, so processors that don't
 * need it are unaffected. The pipeline calls {@code analyze} on
 * every processor in order before the first call to
 * {@link #process(float[], int)}.
 * <p>
 * <b>Buffer semantics.</b> Implementations may transform the
 * buffer in place and return the same array reference, or
 * allocate a new array of a different size when the operation
 * requires it (for example, a resampler). Callers must use the
 * returned array as the new buffer rather than assuming the
 * input array still holds the result.
 * <p>
 * <b>Channel layout.</b> Samples are interleaved: for stereo
 * audio, even indices are the left channel and odd indices are
 * the right channel. The {@code channels} parameter is supplied
 * to every {@link #process(float[], int)} call so processors
 * can correctly address each channel.
 * <p>
 * <b>Enabled state.</b> Each processor carries an enabled flag.
 * When disabled, {@link #process(float[], int)} should
 * return the buffer unchanged. The default helper
 * {@link #passthrough(float[])} is provided for that purpose.
 */
public interface AudioProcessor
{
    /**
     * Performs sample-rate-aware setup. Must be called at least
     * once before {@link #process(float[], int)} is first invoked,
     * and again whenever the source audio changes (for example,
     * when loading a new file).
     * <p>
     * Implementations should configure any internal state that
     * depends on sample rate or total sample count here - for
     * instance, parameter smoothers, filter coefficients, fade
     * envelopes, or look-ahead buffers - so the per-sample loop
     * in {@code process} stays free of setup work. Implementations
     * should also reset any per-audio counters or position
     * indices to zero.
     *
     * @param sampleRate    audio sample rate in Hz (must be > 0)
     * @param totalSamples  total number of float samples in the
     *                      whole audio, summed across channels
     *                      (e.g. 1 second of 44.1 kHz stereo =
     *                      88200). Declared {@code long} so an
     *                      oversampled total cannot overflow on
     *                      long, high-rate files. Use 0 if unknown
     *                      or not applicable to the implementation.
     */
    void prepare(int sampleRate, long totalSamples);

    /**
     * Optional whole-audio pre-scan. The pipeline calls this
     * method on every processor, in order, after {@link #prepare}
     * and before the first {@link #process} call. Processors that
     * need a global property of the audio (peak maximization,
     * loudness analysis, dynamic range estimation) implement it;
     * processors that do not are unaffected by the default
     * no-op implementation.
     * <p>
     * The samples passed in are the entire audio that will be
     * processed downstream (already trimmed if applicable).
     * Implementations must not modify the array - analyze is
     * read-only.
     *
     * @param samples   interleaved float samples, the entire audio
     * @param channels  number of channels (1 = mono, 2 = stereo)
     */
    default void analyze(float[] samples, int channels) { /* no-op by default */ }

    /**
     * Indicates whether this processor derives a parameter from a
     * whole-signal pre-scan of its <i>actual</i> input - the signal as
     * it arrives at this stage, after every upstream processor has run.
     * <p>
     * The {@link com.quickmaster.processing.PeakNormalizer} is the prime
     * example: its gain must be computed from the post-upstream peak, so
     * that an upstream EQ boost (or any earlier gain) is accounted for.
     * When this returns {@code true}, the pipeline renders the upstream
     * sub-chain on a copy of the source and passes that result to
     * {@link #analyze(float[], int)}, instead of the raw original
     * samples. Processors that need no analysis (the default) are
     * skipped entirely by that pass.
     *
     * @return {@code true} if {@link #analyze} must see the
     *         upstream-processed signal
     */
    default boolean usesAnalysis() { return false; }

    /**
     * Reports the processing latency this processor introduces, in
     * <i>frames</i> (not interleaved samples), under its current
     * configuration. A frame is one sample per channel, so the value
     * is channel-independent.
     * <p>
     * Most processors are zero-latency and inherit the default of
     * {@code 0}. Block- or look-ahead-based processors - a
     * linear-phase (FFT) equalizer, a look-ahead limiter - delay the
     * signal by a fixed number of frames and must report it here so
     * that offline rendering can compensate: the pipeline feeds an
     * extra {@code getLatencyFrames()} frames of silence at the end to
     * flush the delay lines, then discards the same number of leading
     * output frames, keeping the rendered file the same length as the
     * source and time-aligned with it.
     * <p>
     * The value may change at runtime (for example, when the user
     * switches a linear-phase EQ to minimum phase, or bypasses a
     * limiter). A disabled processor that acts as a pure passthrough
     * must report {@code 0}.
     *
     * @return the latency introduced, in frames (≥ 0)
     */
    default int getLatencyFrames() { return 0; }

    /**
     * Sets the frame index, at this processor's current sample rate, of the next
     * buffer's first sample. Position-based stages (fades, the analysis-driven
     * dynamics) use it so their output stays aligned with the audio after a seek
     * or a start from a non-zero position. Default no-op.
     *
     * @param frame  the current-rate frame index of the next buffer
     */
    default void setPlaybackPosition(long frame) { /* no-op by default */ }

    /**
     * Applies this processor's transformation to the given buffer
     * of interleaved float samples.
     *
     * @param buffer    interleaved samples in the range [-1.0, +1.0]
     * @param channels  number of audio channels (1 = mono, 2 = stereo)
     * @return the transformed buffer (may be the same reference
     *         as {@code buffer}, modified in place)
     */
    float[] process(float[] buffer, int channels);

    /**
     * Indicates whether this processor is currently active.
     * When {@code false}, {@link #process(float[], int)} must
     * leave the buffer unchanged.
     *
     * @return {@code true} if this processor is enabled
     */
    boolean isEnabled();

    /**
     * Enables or disables this processor at runtime, allowing
     * the user to toggle individual modules of the pipeline
     * without removing them from the chain.
     *
     * @param enabled  {@code true} to enable, {@code false} to bypass
     */
    void setEnabled(boolean enabled);

    /**
     * Convenience helper for implementations: returns the buffer
     * unchanged. Intended to be used as the early-return value
     * when the processor is disabled.
     *
     * @param buffer  the buffer to pass through
     * @return the same buffer reference, unchanged
     */
    default float[] passthrough(float[] buffer)
    {
        return buffer;
    }
}