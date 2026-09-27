package com.dspark.analysis;

/**
 * Estimates the global tempo (BPM) of a piece of music from an onset
 * detection function.
 * <p>
 * <b>Algorithm.</b> The tempo is the dominant periodicity of the onset
 * detection function (ODF) produced by {@link OnsetDetector}. The ODF is
 * mean-removed and its <i>autocorrelation</i> is evaluated only at lags that
 * correspond to plausible tempi (between {@value #DEFAULT_MIN_BPM} and
 * {@value #DEFAULT_MAX_BPM} BPM). Each lag's correlation is multiplied by a
 * <i>perceptual weight</i> — a log-Gaussian centred on
 * {@value #PREFERRED_BPM} BPM — which biases the result toward the tempo a
 * listener would tap and resolves the octave ambiguity (a steady beat
 * correlates equally at its period and at every multiple). The weighted peak
 * is the tempo; its prominence over the mean gives a {@code [0,1]}
 * confidence.
 * <p>
 * This is a global, one-shot estimate (one value per track), not a beat
 * tracker. When {@link #getConfidence()} is low the caller should fall back
 * to a user-supplied BPM.
 */
public final class TempoEstimator
{
    /** Default lower tempo bound in BPM. */
    public static final double DEFAULT_MIN_BPM = 60.0;
    /** Default upper tempo bound in BPM. */
    public static final double DEFAULT_MAX_BPM = 200.0;
    /** Centre of the perceptual weighting curve, in BPM. */
    public static final double PREFERRED_BPM = 120.0;
    /** Width (in octaves) of the perceptual weighting curve. */
    private static final double WEIGHT_SIGMA_OCT = 0.9;

    private double bpm = 0.0;
    private double confidence = 0.0;

    /** Estimates the tempo from a detector's ODF (default tempo range). */
    public void estimate(OnsetDetector detector)
    {
        estimate(detector.getOdf(), detector.getFrameRate(), DEFAULT_MIN_BPM, DEFAULT_MAX_BPM);
    }

    /** Estimates the tempo from an ODF and its frame rate (default tempo range). */
    public void estimate(float[] odf, double frameRate)
    {
        estimate(odf, frameRate, DEFAULT_MIN_BPM, DEFAULT_MAX_BPM);
    }

    /**
     * Estimates the tempo from an ODF over a custom tempo range.
     *
     * @param odf        the onset detection function (one value per frame)
     * @param frameRate  ODF frame rate in frames per second
     * @param minBpm     lower tempo bound
     * @param maxBpm     upper tempo bound
     */
    public void estimate(float[] odf, double frameRate, double minBpm, double maxBpm)
    {
        bpm = 0.0;
        confidence = 0.0;
        if (odf == null || frameRate <= 0.0 || odf.length < 8) return;

        int n = odf.length;

        // Mean-remove so the autocorrelation reflects periodic structure, not
        // the ODF's overall energy (which would peak trivially at lag 0).
        double mean = 0.0;
        for (float v : odf) mean += v;
        mean /= n;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = odf[i] - mean;

        // Lag range (in frames) corresponding to the BPM range. A higher BPM
        // means a shorter period, hence a smaller lag.
        int lagMin = Math.max(1, (int) Math.floor(frameRate * 60.0 / maxBpm));
        int lagMax = Math.min(n - 1, (int) Math.ceil(frameRate * 60.0 / minBpm));
        if (lagMax <= lagMin) return;

        double bestScore = -Double.MAX_VALUE;
        int bestLag = -1;
        double scoreSum = 0.0;
        int scoreCount = 0;

        for (int lag = lagMin; lag <= lagMax; lag++)
        {
            double acc = 0.0;
            for (int i = 0; i < n - lag; i++) acc += x[i] * x[i + lag];
            acc /= (n - lag);                 // normalise for the overlap length

            double candBpm = frameRate * 60.0 / lag;
            double weighted = acc * perceptualWeight(candBpm);

            scoreSum += weighted;
            scoreCount++;
            if (weighted > bestScore)
            {
                bestScore = weighted;
                bestLag = lag;
            }
        }

        if (bestLag < 0) return;

        bpm = frameRate * 60.0 / bestLag;

        // Confidence: how far the winning score stands above the average score.
        double meanScore = (scoreCount > 0) ? scoreSum / scoreCount : 0.0;
        if (bestScore > 0.0 && meanScore > 0.0)
        {
            double ratio = bestScore / meanScore;     // 1 = flat, larger = peakier
            confidence = clamp01((ratio - 1.0) / 4.0);
        }
        else
        {
            confidence = 0.0;
        }
    }

    /** Log-Gaussian weight centred on {@link #PREFERRED_BPM}. */
    private static double perceptualWeight(double candidateBpm)
    {
        double octaves = Math.log(candidateBpm / PREFERRED_BPM) / Math.log(2.0);
        double z = octaves / WEIGHT_SIGMA_OCT;
        return Math.exp(-0.5 * z * z);
    }

    private static double clamp01(double v)
    {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    /** The estimated tempo in BPM, or {@code 0} if estimation failed. */
    public double getBpm() { return bpm; }

    /** Estimation confidence in {@code [0,1]}; low values mean "ask the user". */
    public double getConfidence() { return confidence; }
}
