package com.quickmaster.processing;

import com.dspark.analysis.TruePeak;

/**
 * Final peak-normalization stage of the QuickMaster pipeline ("Peak Normalizer").
 * <p>
 * Scans the (post-upstream) signal for its maximum <b>true peak</b> (the 4&times;
 * oversampled inter-sample peak, ITU-R BS.1770) and applies the single static
 * gain that brings it to a target ceiling in <b>dBTP</b>. Because it is a pure
 * scalar that scales the true peak exactly to the ceiling, the output is
 * guaranteed never to exceed the chosen dBTP on a DAC. The analysed peak is
 * cached, so the gain recomputes <i>instantly</i> when the ceiling changes.
 * <p>
 * Placed last, after the limiter layers (which flatten the crest), it cashes that
 * flattening in as loudness while clamping the delivery true-peak. The default
 * ceiling is -1&nbsp;dBTP (lossy-codec safe); 0&nbsp;dBTP is the clean maximum.
 * <p>
 * {@link #usesAnalysis()} is {@code true}, so the pipeline feeds this stage the
 * <i>post-upstream</i> signal. Zero latency.
 */
public final class PeakNormalizer implements AudioProcessor
{
    public static final double MIN_TARGET_DBFS = -60.0;
    public static final double MAX_TARGET_DBFS = 0.0;
    public static final double DEFAULT_TARGET_DBFS = -1.0;

    /** Never amplify by more than this (avoids blowing up near-silent material). */
    private static final double MAX_GAIN_DB = 24.0;

    private volatile double targetDbfs = DEFAULT_TARGET_DBFS;
    private volatile boolean enabled = true;
    private volatile double gain = 1.0;        // applied factor
    private volatile double cachedPeak = 0.0;  // analysed peak (linear, 0..1)

    public PeakNormalizer() { }

    public PeakNormalizer(double targetDbfs) { setTargetDbfs(targetDbfs); }

    public double getTargetDbfs() { return targetDbfs; }

    public void setTargetDbfs(double targetDbfs)
    {
        if (targetDbfs < MIN_TARGET_DBFS || targetDbfs > MAX_TARGET_DBFS)
        {
            throw new IllegalArgumentException("Target dBFS out of range ["
                    + MIN_TARGET_DBFS + ", " + MAX_TARGET_DBFS + "]: " + targetDbfs);
        }
        this.targetDbfs = targetDbfs;
        recomputeGain();
    }

    public double getGain() { return gain; }

    /** Applied gain in dB (positive amplifies). */
    public double getGainDb()
    {
        return (gain <= 0.0) ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(gain);
    }

    private void recomputeGain()
    {
        if (cachedPeak <= 0.0)
        {
            gain = 1.0;
            return;
        }
        double targetLin = Math.pow(10.0, targetDbfs / 20.0);
        double maxGain = Math.pow(10.0, MAX_GAIN_DB / 20.0);
        gain = Math.min(targetLin / cachedPeak, maxGain);
    }

    @Override
    public void prepare(int sampleRate, int totalSamples) { }

    @Override
    public boolean usesAnalysis() { return true; }

    @Override
    public void analyze(float[] samples, int channels)
    {
        cachedPeak = TruePeak.measureMax(samples, channels);   // 4x oversampled true peak
        recomputeGain();
    }

    /**
     * Sets the analysed peak directly and recomputes the gain.
     *
     * @param peak  the peak (linear, 0..1)
     */
    public void setAnalyzedPeak(double peak)
    {
        this.cachedPeak = peak;
        recomputeGain();
    }

    /** The peak (linear, 0..1) this stage last analysed. */
    public double getAnalyzedPeak() { return cachedPeak; }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        if (!enabled || gain == 1.0) return buffer;
        float g = (float) gain;
        for (int i = 0; i < buffer.length; i++)
        {
            float v = buffer[i] * g;
            buffer[i] = (v > 1.0f) ? 1.0f : (v < -1.0f ? -1.0f : v);
        }
        return buffer;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
