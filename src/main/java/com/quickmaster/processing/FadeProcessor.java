package com.quickmaster.processing;

/**
 * Linear fade-in and fade-out processor.
 * <p>
 * Applies a linear amplitude ramp at the start (fade-in) and/or
 * the end (fade-out) of the audio. The duration of each fade is
 * configurable in seconds, in the range
 * {@value #MIN_FADE_SECONDS} to {@value #MAX_FADE_SECONDS}, where
 * 0.0 means "no fade".
 * <p>
 * The fade-in linearly ramps the amplitude from 0.0 to 1.0 over
 * the first {@code fadeInSec} seconds of the audio. The fade-out
 * linearly ramps the amplitude from 1.0 to 0.0 over the last
 * {@code fadeOutSec} seconds. Samples outside both ramps pass
 * through at unity gain. If the two ramps would overlap (because
 * the audio is shorter than {@code fadeInSec + fadeOutSec}), each
 * sample is multiplied by both factors, producing the natural
 * intersection of the two envelopes.
 * <p>
 * <b>Trim interaction.</b> The fade is applied relative to the
 * <i>current</i> audio being processed, not to the originally
 * loaded file. If the user has trimmed the audio, the fade-in
 * starts at the new beginning of the trimmed region and the
 * fade-out ends at the new end of the trimmed region. This is
 * the natural and expected behaviour: the fade always envelopes
 * "what the user is going to hear", regardless of what the
 * material looked like before trimming.
 * <p>
 * <b>Position dependence.</b> Unlike most processors in the
 * pipeline, the fade envelope depends on the absolute position
 * of each sample within the audio being processed. The processor
 * maintains an internal frame counter that advances as buffers
 * are processed, and uses the total sample count to locate the
 * start of the fade-out. The counter is reset to zero each time
 * {@link #prepare(int, int)} is called, so the same processor
 * instance can be reused across different audios or across
 * repeated processing passes of the same audio.
 * <p>
 * <b>Total sample count: streaming vs offline.</b> The total is
 * required to position the fade-out correctly. It can be
 * supplied either at {@code prepare} time (for streaming, where
 * the player knows the total in advance) or inferred from the
 * first buffer received (for offline processing, where the
 * pipeline is invoked with the entire audio in a single buffer).
 * If {@code prepare} is called with {@code totalSamples == 0},
 * the processor switches to offline mode and uses the size of
 * the first buffer it sees as the total.
 * <p>
 * <b>No smoothing.</b> Unlike gain processors, the fade ramps
 * are themselves intrinsically smooth (linear), so additional
 * parameter smoothing would be redundant. Changing
 * {@code fadeInSec} or {@code fadeOutSec} during processing is
 * supported but the audible result is not guaranteed to be
 * artefact-free at the transition point.
 * <p>
 * <b>No internal clamping.</b> A linear fade applied to
 * in-range input cannot produce out-of-range output, so no
 * clamp is needed; in addition, mid-chain clamping would hide
 * information from the downstream {@link PeakNormalizer}, which
 * is the processor in charge of keeping the output within the
 * valid range.
 */
public class FadeProcessor implements AudioProcessor
{
    /** Minimum fade duration in seconds. */
    public static final double MIN_FADE_SECONDS = 0.0;
    /** Maximum fade duration in seconds (effectively the whole track). */
    public static final double MAX_FADE_SECONDS = 3600.0;

    /** Fade curve shapes. */
    public enum FadeType { LINEAR, EXPONENTIAL, LOGARITHMIC, SCURVE }

    private double fadeInSec;
    private double fadeOutSec;
    private FadeType fadeType = FadeType.LINEAR;
    private boolean enabled = true;

    /** Audio sample rate, set by {@link #prepare(int, int)}. */
    private int sampleRate;

    /**
     * Total number of float samples (across channels) of the audio
     * being processed. Either supplied at {@link #prepare(int, int)}
     * time (streaming mode) or inferred from the first buffer
     * (offline mode).
     */
    private int totalSamples;

    /** Number of frames already processed since the last prepare(). */
    private long framesProcessed;

    /**
     * Creates a new FadeProcessor with no fade-in and no fade-out
     * (both durations set to 0). {@link #prepare(int, int)} must
     * be called before processing the first buffer.
     */
    public FadeProcessor()
    {
        this(0.0, 0.0);
    }

    /**
     * Creates a new FadeProcessor with the given fade durations.
     * {@link #prepare(int, int)} must be called before processing
     * the first buffer.
     *
     * @param fadeInSec   fade-in duration in seconds (0 to 10)
     * @param fadeOutSec  fade-out duration in seconds (0 to 10)
     * @throws IllegalArgumentException if either duration is
     *         outside the allowed range
     */
    public FadeProcessor(double fadeInSec, double fadeOutSec)
    {
        validateRange(fadeInSec,  "fadeInSec");
        validateRange(fadeOutSec, "fadeOutSec");
        this.fadeInSec  = fadeInSec;
        this.fadeOutSec = fadeOutSec;
    }

    public double getFadeInSec()  { return fadeInSec; }
    public double getFadeOutSec() { return fadeOutSec; }

    public FadeType getFadeType() { return fadeType; }
    public void setFadeType(FadeType t) { this.fadeType = (t == null) ? FadeType.LINEAR : t; }

    /** Advances to the next fade curve and returns it (mouse-wheel gesture). */
    public FadeType cycleFadeType()
    {
        FadeType[] all = FadeType.values();
        fadeType = all[(fadeType.ordinal() + 1) % all.length];
        return fadeType;
    }

    /**
     * Sets the fade-in duration in seconds.
     *
     * @param fadeInSec  duration in [0, 10] seconds
     * @throws IllegalArgumentException if the value is outside
     *         the allowed range
     */
    public void setFadeInSec(double fadeInSec)
    {
        validateRange(fadeInSec, "fadeInSec");
        this.fadeInSec = fadeInSec;
    }

    /**
     * Sets the fade-out duration in seconds.
     *
     * @param fadeOutSec  duration in [0, 10] seconds
     * @throws IllegalArgumentException if the value is outside
     *         the allowed range
     */
    public void setFadeOutSec(double fadeOutSec)
    {
        validateRange(fadeOutSec, "fadeOutSec");
        this.fadeOutSec = fadeOutSec;
    }

    @Override
    public void prepare(int sampleRate, int totalSamples)
    {
        if (sampleRate <= 0)
        {
            throw new IllegalArgumentException(
                    "sampleRate must be > 0; got " + sampleRate);
        }
        if (totalSamples < 0)
        {
            throw new IllegalArgumentException(
                    "totalSamples must be >= 0; got " + totalSamples);
        }
        this.sampleRate = sampleRate;
        this.totalSamples = totalSamples;
        this.framesProcessed = 0L;
    }

    @Override
    public void setPlaybackPosition(long frame) { this.framesProcessed = frame; }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        if (!enabled)
        {
            return passthrough(buffer);
        }
        if (channels != 1 && channels != 2)
        {
            throw new IllegalArgumentException(
                    "FadeProcessor supports mono or stereo only; got " + channels + " channels.");
        }
        if (sampleRate <= 0)
        {
            throw new IllegalStateException(
                    "Sample rate not configured; call prepare(int, int) first.");
        }

        // Offline mode: infer totalSamples from the first buffer if not provided.
        if (totalSamples == 0)
        {
            totalSamples = buffer.length;
        }

        long fadeInFrames  = Math.round(fadeInSec  * sampleRate);
        long fadeOutFrames = Math.round(fadeOutSec * sampleRate);
        long totalFrames   = totalSamples / channels;
        long fadeOutStart  = totalFrames - fadeOutFrames;

        int framesInBuffer = buffer.length / channels;

        for (int f = 0; f < framesInBuffer; f++)
        {
            long globalFrame = framesProcessed + f;
            float factor = computeFadeFactor(
                    globalFrame, fadeInFrames, fadeOutStart, totalFrames, fadeType);

            int sampleIndex = f * channels;
            if (channels == 1)
            {
                buffer[sampleIndex] = buffer[sampleIndex] * factor;
            }
            else
            {
                buffer[sampleIndex]     = buffer[sampleIndex]     * factor;
                buffer[sampleIndex + 1] = buffer[sampleIndex + 1] * factor;
            }
        }

        framesProcessed += framesInBuffer;
        return buffer;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /* --- Helpers --- */

    /**
     * Computes the linear fade factor for a given global frame
     * index. Combines fade-in and fade-out by multiplying their
     * individual factors, which gives the correct envelope when
     * the two ramps overlap on a short audio.
     */
    private static float computeFadeFactor(
            long globalFrame, long fadeInFrames, long fadeOutStart, long totalFrames, FadeType type)
    {
        float levelIn  = 1.0f;
        float levelOut = 1.0f;

        if (fadeInFrames > 0 && globalFrame < fadeInFrames)
        {
            levelIn = (float) globalFrame / (float) fadeInFrames;
        }

        if (totalFrames > 0 && fadeOutStart < totalFrames && globalFrame >= fadeOutStart)
        {
            long framesIntoFadeOut = globalFrame - fadeOutStart;
            long fadeOutFrames = totalFrames - fadeOutStart;
            levelOut = 1.0f - (float) framesIntoFadeOut / (float) fadeOutFrames;
            if (levelOut < 0.0f) levelOut = 0.0f;
        }

        return shape(levelIn, type) * shape(levelOut, type);
    }

    /** Maps a linear level fraction (0..1) through the selected fade curve. */
    private static float shape(float t, FadeType type)
    {
        if (t <= 0.0f) return 0.0f;
        if (t >= 1.0f) return 1.0f;
        switch (type)
        {
            case EXPONENTIAL: return t * t;                       // gentle start, steep end
            case LOGARITHMIC: return (float) Math.sqrt(t);        // steep start, gentle end
            case SCURVE:      return t * t * (3.0f - 2.0f * t);   // smooth S
            default:          return t;                           // LINEAR
        }
    }

    private static void validateRange(double value, String fieldName)
    {
        if (value < MIN_FADE_SECONDS || value > MAX_FADE_SECONDS)
        {
            throw new IllegalArgumentException(
                    fieldName + " must be in [" + MIN_FADE_SECONDS + ", " + MAX_FADE_SECONDS
                            + "] seconds; got " + value);
        }
    }
}