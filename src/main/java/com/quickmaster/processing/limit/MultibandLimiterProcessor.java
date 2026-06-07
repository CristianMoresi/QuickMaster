package com.quickmaster.processing.limit;
import com.quickmaster.processing.AudioProcessor;

import com.dspark.effects.LimiterEnvelope;
import com.dspark.effects.MultibandCrossover;

/**
 * First limiting layer: an automatic, phase-faithful <b>multiband limiter</b>.
 * <p>
 * The signal is split into four bands by a linear-phase crossover (so there is
 * no phase distortion, only a constant latency). Each band has a single
 * <b>Push</b> control in dB: because the whole signal is analysed in advance,
 * the band's threshold is solved so that the band's loudest peak is brickwall
 * limited by exactly that many dB (and quieter peaks proportionally less),
 * which - cashed in by the downstream Peak Normalizer - is extra loudness.
 * It is the brickwall, more transparent sibling of the Peak Comp.
 * <p>
 * The per-band gain envelopes are precomputed on the base-rate program, so the
 * gain-reduction meters ({@link #getBandGrAtPosition}) read the real reduction
 * identically with or without oversampling. Disabled by default.
 */
public final class MultibandLimiterProcessor implements AudioProcessor
{
    /** Crossover frequencies (Hz): Low | Low-Mid | High-Mid | High. */
    public static final double[] CROSSOVERS = { 120.0, 1000.0, 6000.0 };
    public static final String[] BAND_NAMES = { "LOW", "LOW-MID", "HIGH-MID", "HIGH" };
    public static final int BANDS = CROSSOVERS.length + 1;

    public static final double MAX_PUSH_DB = 6.0;
    /** Default per-band push: a gentle 1 dB to tidy each band before the broadband stage. */
    public static final double DEFAULT_PUSH_DB = 1.0;
    private static final double ATTACK_MS = 1.5;
    private static final double RELEASE_MS = 80.0;

    private final MultibandCrossover crossover = new MultibandCrossover();
    private volatile boolean enabled = false;

    private final double[] pushDb = new double[BANDS];     // per-band target max reduction
    private volatile float[][] bandPeakMap = null;         // [band][frame] cached peak (for remap)
    private volatile float[][] bandEnv = null;             // [band][frame] gain (null row = unity)
    private final double[] bandPeak = new double[BANDS];   // per-band program peak

    private double envRate = 0.0;
    private int envFrames = 0;
    private int atkSamples = 64, relSamples = 3840;

    private int sampleRate = 0;
    private int preparedChannels = 0;
    private boolean crossoverReady = false;
    private long framesProcessed = 0L;

    private float[][] bandWork = null;                     // process scratch [band][block]

    public MultibandLimiterProcessor()
    {
        java.util.Arrays.fill(pushDb, DEFAULT_PUSH_DB);
    }

    public int getBands() { return BANDS; }

    public double getPushDb(int band) { return pushDb[band]; }

    public void setPushDb(int band, double db)
    {
        if (band < 0 || band >= BANDS) return;
        pushDb[band] = clamp(db, 0.0, MAX_PUSH_DB);
        mapBandToEnvelope(band);   // rebuild only this band (cheap; keeps the knob responsive)
    }

    /** Gain reduction (dB, &le; 0) of {@code band} at base-rate position {@code baseFrame}. */
    public double getBandGrAtPosition(int band, long baseFrame)
    {
        if (!enabled || band < 0 || band >= BANDS) return 0.0;
        float[][] envs = bandEnv;
        if (envs == null || envs[band] == null) return 0.0;
        float[] e = envs[band];
        if (e.length == 0) return 0.0;
        long i = baseFrame;
        if (i < 0) i = 0; else if (i >= e.length) i = e.length - 1;
        double g = e[(int) i];
        double gr = 20.0 * Math.log10(Math.max(g, 1e-6)) - pushDb[band];   // remove the make-up
        return Math.max(Math.min(gr, 0.0), -MAX_PUSH_DB);
    }

    /** Deepest gain reduction (dB, &le; 0) of {@code band} over a base-rate frame range. */
    public double getBandGrDeepest(int band, long from, long to)
    {
        if (!enabled || band < 0 || band >= BANDS) return 0.0;
        float[][] envs = bandEnv;
        if (envs == null || envs[band] == null) return 0.0;
        float[] e = envs[band];
        if (e.length == 0) return 0.0;
        int a = (int) Math.max(0, Math.min(from, to));
        int b2 = (int) Math.min(e.length - 1, Math.max(from, to));
        double minG = Double.MAX_VALUE;
        for (int i = a; i <= b2; i++) if (e[i] < minG) minG = e[i];
        if (minG == Double.MAX_VALUE) return 0.0;
        double gr = 20.0 * Math.log10(Math.max(minG, 1e-6)) - pushDb[band];   // remove the make-up
        return Math.max(Math.min(gr, 0.0), -MAX_PUSH_DB);
    }

    @Override
    public boolean usesAnalysis() { return true; }

    @Override
    public int getLatencyFrames()
    {
        return (enabled && bandEnv != null && crossoverReady) ? crossover.getLatency() : 0;
    }

    @Override
    public void prepare(int sampleRate, int totalSamples)
    {
        this.sampleRate = sampleRate;
        this.framesProcessed = 0L;
        this.crossoverReady = false;   // re-prepare the crossover for this rate on next use
    }

    @Override
    public void setPlaybackPosition(long frame) { this.framesProcessed = frame; }

    @Override
    public void analyze(float[] samples, int channels)
    {
        if (samples == null || channels < 1) return;
        ensureCrossover(channels);
        int frames = samples.length / channels;
        envRate = sampleRate;
        envFrames = frames;
        atkSamples = Math.max(1, (int) (ATTACK_MS * 0.001 * sampleRate));
        relSamples = Math.max(1, (int) (RELEASE_MS * 0.001 * sampleRate));

        float[][] bands = crossover.splitWhole(samples, channels);
        float[][] pmaps = new float[BANDS][frames];
        for (int b = 0; b < BANDS; b++)
        {
            float[] band = bands[b];
            float[] pm = pmaps[b];
            double mx = 0.0;
            for (int f = 0; f < frames; f++)
            {
                float p = 0.0f;
                int base = f * channels;
                for (int c = 0; c < channels; c++)
                {
                    float a = Math.abs(band[base + c]);
                    if (a > p) p = a;
                }
                pm[f] = p;
                if (p > mx) mx = p;
            }
            pmaps[b] = pm;
            bandPeak[b] = mx;
        }
        bandPeakMap = pmaps;
        mapToEnvelopes();
    }

    /** Rebuilds all bands' gain envelopes (used after analysis). */
    private void mapToEnvelopes()
    {
        float[][] pmaps = bandPeakMap;
        if (pmaps == null) return;
        float[][] envs = new float[BANDS][];
        for (int b = 0; b < BANDS; b++) envs[b] = buildBandEnv(b, pmaps);
        bandEnv = envs;
    }

    /** Rebuilds a single band's envelope and republishes (cheap; keeps a knob live). */
    private void mapBandToEnvelope(int band)
    {
        float[][] pmaps = bandPeakMap;
        if (pmaps == null) return;
        float[][] cur = bandEnv;
        float[][] envs = (cur != null) ? cur.clone() : new float[BANDS][];
        envs[band] = buildBandEnv(band, pmaps);
        bandEnv = envs;   // atomic publish
    }

    /**
     * The gain envelope for one band, or null (unity). The band is pushed UP by the
     * dialled dB (make-up) and brickwall-limited to its original peak, so the body
     * rises toward the peak (denser, louder band) while the loudest peak is held and
     * limited by exactly that many dB.
     */
    private float[] buildBandEnv(int b, float[][] pmaps)
    {
        double d = pushDb[b];
        if (d <= 1e-6 || bandPeak[b] <= 1e-9) return null;   // unity: band passes unprocessed
        double makeup = Math.pow(10.0, d / 20.0);
        double th = bandPeak[b] * Math.pow(10.0, -d / 20.0);
        float[] e = LimiterEnvelope.computeFromPeaks(pmaps[b], th, atkSamples, relSamples);
        for (int i = 0; i < e.length; i++) e[i] *= (float) makeup;   // push up by the dialled dB
        return e;
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        if (!enabled) return buffer;
        float[][] envs = bandEnv;
        if (envs == null) return buffer;   // not analysed yet: stay transparent (no latency)
        ensureCrossover(channels);

        int frames = buffer.length / channels;
        if (bandWork == null || bandWork.length != BANDS || bandWork[0].length < buffer.length)
        {
            bandWork = new float[BANDS][buffer.length];
        }
        crossover.process(buffer, channels, bandWork);   // FFT overlap-add: cheap at any rate

        double ratio = (sampleRate > 0) ? envRate / sampleRate : 1.0;
        for (int f = 0; f < frames; f++)
        {
            double pos = (framesProcessed + f) * ratio;   // base-rate position (rate-invariant)
            int base = f * channels;
            for (int c = 0; c < channels; c++)
            {
                double out = 0.0;
                for (int b = 0; b < BANDS; b++)
                {
                    float g = (envs[b] == null) ? 1.0f : sampleEnv(envs[b], pos);
                    out += bandWork[b][base + c] * g;
                }
                buffer[base + c] = (float) out;
            }
        }
        framesProcessed += frames;
        return buffer;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Adopts the analysis (peak maps + peaks) from a background re-render copy. */
    public void adoptAnalysis(MultibandLimiterProcessor other)
    {
        this.envRate = other.envRate;
        this.envFrames = other.envFrames;
        this.atkSamples = other.atkSamples;
        this.relSamples = other.relSamples;
        System.arraycopy(other.bandPeak, 0, this.bandPeak, 0, BANDS);
        this.bandPeakMap = other.bandPeakMap;
        mapToEnvelopes();
    }

    /* --- helpers --- */

    private void ensureCrossover(int channels)
    {
        if (!crossoverReady || channels != preparedChannels)
        {
            crossover.prepare(sampleRate, channels, CROSSOVERS);
            preparedChannels = channels;
            crossoverReady = true;
        }
    }

    private static float sampleEnv(float[] env, double pos)
    {
        if (pos <= 0.0) return env[0];
        int i = (int) pos;
        if (i >= env.length - 1) return env[env.length - 1];
        float frac = (float) (pos - i);
        return env[i] + (env[i + 1] - env[i]) * frac;
    }

    private static double clamp(double v, double lo, double hi)
    {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
