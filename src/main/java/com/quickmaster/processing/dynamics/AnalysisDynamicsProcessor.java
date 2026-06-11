package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.AudioProcessor;

/**
 * Base class for the analysis-driven dynamics processors (Peak Comp, Beat Comp,
 * Leveler, Punch).
 * <p>
 * Because the whole signal is available before playback, these processors react
 * to peaks and transients with no attack lag: each one precomputes a per-frame
 * <b>gain envelope</b> over the entire signal in {@link #analyze} and then
 * applies it.
 * <p>
 * <b>Features vs. gain.</b> {@link #analyze} computes the input-dependent
 * <i>features</i> (level / transient envelopes); {@link #mapFeaturesToGain()}
 * then turns those, together with this processor's parameters, into the gain
 * envelope, which is published as a single {@code volatile} array reference so
 * it can be replaced atomically.
 * <p>
 * <b>Rate independence.</b> {@link #process} applies the envelope by time
 * position ({@code t = framesProcessed / sampleRate}, index {@code t · envRate}),
 * so the same base-rate envelope applies correctly during live playback, offline
 * export and at any oversampling factor. {@link #prepare} resets only the
 * playback position; the analysis is kept. Latency is zero.
 * <p>
 * Subclasses implement {@link #computeFeatures} and {@link #mapFeaturesToGain()}.
 * Processors are disabled by default and stereo-linked (one gain for both
 * channels).
 */
public abstract class AnalysisDynamicsProcessor implements AudioProcessor
{
    /** Whether this stage is active (default off). */
    protected volatile boolean enabled = false;

    /**
     * Per-frame linear gain envelope (base rate), published atomically.
     * {@code null} until {@link #analyze} runs. <b>Not</b> cleared by
     * {@link #prepare}. Subclasses must build a fresh array in
     * {@link #mapFeaturesToGain()} and assign it here so readers never see a
     * partially written envelope.
     */
    protected volatile float[] gainEnv = null;

    /** Sample rate at which {@link #gainEnv} is indexed (the base rate). */
    protected double envRate = 0.0;

    /** Number of frames the analysed audio had (length of {@link #gainEnv}). */
    protected int envFrames = 0;

    /** Current sample rate from the last {@link #prepare} (may be oversampled). */
    private int sampleRate = 0;

    /** Frames processed since the last {@link #prepare}; positions the envelope. */
    private long framesProcessed = 0L;

    /** Worst (most negative) gain reduction applied in the last block, in dB. */
    private volatile double currentGrDb = 0.0;

    @Override
    public boolean usesAnalysis() { return true; }

    @Override
    public int getLatencyFrames() { return 0; }

    @Override
    public void prepare(int sampleRate, long totalSamples)
    {
        this.sampleRate = sampleRate;
        this.framesProcessed = 0L;
        // The analysis (gainEnv / envRate / envFrames) is intentionally kept.
    }

    @Override
    public void setPlaybackPosition(long frame) { this.framesProcessed = frame; }

    @Override
    public void analyze(float[] samples, int channels)
    {
        if (samples == null || channels < 1)
        {
            return;
        }
        int frames = samples.length / channels;
        this.envRate = (sampleRate > 0) ? sampleRate : 0.0;
        this.envFrames = frames;
        computeFeatures(samples, channels, sampleRate, frames);
        mapFeaturesToGain();
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        if (channels < 1)
        {
            return buffer;
        }
        int frames = buffer.length / channels;
        float[] env = gainEnv;
        if (enabled && env != null && env.length > 0 && envRate > 0.0 && sampleRate > 0)
        {
            double rateRatio = envRate / sampleRate;        // env frames per output frame
            double extreme = 0.0;                            // signed: - reduction, + boost
            for (int f = 0; f < frames; f++)
            {
                double pos = (framesProcessed + f) * rateRatio;
                float g = sampleEnv(env, pos);
                int base = f * channels;
                for (int c = 0; c < channels; c++)
                {
                    buffer[base + c] *= g;
                }
                double dB = 20.0 * Math.log10(Math.max(g, 1e-6f));
                if (Math.abs(dB) > Math.abs(extreme)) extreme = dB;
            }
            currentGrDb = extreme;
        }
        else
        {
            currentGrDb = 0.0;
        }
        framesProcessed += frames;
        return buffer;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Current gain reduction in dB (&le; 0; 0 when not reducing), for live
     * metering. Reports the deepest reduction applied in the most recent block.
     */
    public double getGainReductionDb() { return currentGrDb; }

    /** True once {@link #analyze} has produced a gain envelope. */
    public boolean isAnalyzed() { return gainEnv != null && envFrames > 0; }

    /* --- For subclasses --- */

    /**
     * Computes and caches the expensive, input-dependent features (level or
     * transient envelopes). Called once per analysis pass with the
     * <i>post-upstream</i> signal.
     *
     * @param samples     interleaved post-upstream samples (whole audio)
     * @param channels    channel count
     * @param sampleRate  base sample rate of {@code samples}
     * @param frames      number of frames ({@code samples.length / channels})
     */
    protected abstract void computeFeatures(float[] samples, int channels, int sampleRate, int frames);

    /**
     * Builds {@link #gainEnv} (length {@link #envFrames}) from the cached
     * features and this processor's current parameters. Must allocate a fresh
     * array and assign it to {@link #gainEnv} atomically. Cheap (no FFT) so it
     * can run live on a parameter change.
     */
    protected abstract void mapFeaturesToGain();

    /** Rebuilds the gain envelope from the cached features, if any. */
    protected void remap()
    {
        if (envFrames > 0)
        {
            mapFeaturesToGain();
        }
    }

    /**
     * Replaces this processor's gain envelope with another's. {@link #envRate} is
     * written before the {@code volatile} {@link #gainEnv} so a reader always
     * sees a consistent pair.
     *
     * @param src  the processor whose envelope to copy
     */
    public void adoptEnvelope(AnalysisDynamicsProcessor src)
    {
        this.envRate = src.envRate;
        this.envFrames = src.envFrames;
        this.gainEnv = src.gainEnv;     // volatile publish, last
    }

    /**
     * Peak magnitude over the first ~50&nbsp;ms - a stable level to prime an
     * envelope follower with, so a steady passage at the very start is not
     * mistaken for one enormous transient.
     */
    protected static double primeLevel(float[] samples, int channels, int frames, int sampleRate)
    {
        int pre = Math.min(frames, Math.max(1, (int) (0.05 * sampleRate)));
        float init = 0.0f;
        for (int f = 0; f < pre; f++)
        {
            int base = f * channels;
            for (int c = 0; c < channels; c++)
            {
                float a = Math.abs(samples[base + c]);
                if (a > init) init = a;
            }
        }
        return init;
    }

    /** Linear interpolation of the envelope at a fractional frame position. */
    private static float sampleEnv(float[] env, double pos)
    {
        if (pos <= 0.0) return env[0];
        int i = (int) pos;
        if (i >= env.length - 1) return env[env.length - 1];
        float frac = (float) (pos - i);
        return env[i] + (env[i + 1] - env[i]) * frac;
    }
}
