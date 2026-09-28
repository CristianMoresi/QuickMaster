package com.quickmaster.processing.limit;
import com.quickmaster.processing.AudioProcessor;

import com.dspark.analysis.TruePeak;
import com.dspark.effects.MultibandCrossover;
import com.quickmaster.processing.analysis.AnalysisInputKey;

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
public final class MultibandLimiterProcessor implements AudioProcessor, com.quickmaster.processing.OfflineBlockSizing
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
    private double[] appliedPushDb = new double[BANDS];    // controls represented by published envelopes
    private AnalysisInputKey featureKey;
    private double inputTruePeak;
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

    /** Shared Limit-module ceiling, measured BEFORE any band drive. */
    public double getInputTruePeak() { return inputTruePeak; }

    public double getPushDb(int band) { return pushDb[band]; }

    public void setPushDb(int band, double db)
    {
        if (band < 0 || band >= BANDS) return;
        pushDb[band] = clamp(db, 0.0, MAX_PUSH_DB);
        mapBandToEnvelope(band);   // rebuild only this band (cheap; keeps the knob responsive)
    }

    /** UI request only; the analysis worker constructs and publishes the new envelope. */
    public void requestPushDb(int band, double db)
    {
        if (band >= 0 && band < BANDS) pushDb[band] = clamp(db, 0.0, MAX_PUSH_DB);
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
        double gr = 20.0 * Math.log10(Math.max(g, 1e-6)) - appliedPushDb[band];
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
        double gr = 20.0 * Math.log10(Math.max(minG, 1e-6)) - appliedPushDb[band];
        return Math.max(Math.min(gr, 0.0), -MAX_PUSH_DB);
    }

    @Override
    public boolean usesAnalysis() { return true; }

    @Override
    public int getLatencyFrames()
    {
        // The crossover is prepared eagerly at prepare() time, so this is valid
        // before the first block; the offline renderer relies on that to
        // compensate the linear-phase split's constant delay.
        return (enabled && bandEnv != null) ? crossover.getLatency() : 0;
    }

    @Override
    public void prepare(int sampleRate, long totalSamples)
    {
        if (this.sampleRate != sampleRate) this.crossoverReady = false;
        this.sampleRate = sampleRate;
        this.framesProcessed = 0L;
        // Eagerly (re)prepare the crossover for this rate so getLatencyFrames()
        // is correct before the first process()/analyze() call.
        ensureCrossover(preparedChannels > 0 ? preparedChannels : 2);
        crossover.reset();
    }

    @Override public int preferredOfflineBlockFrames() { return crossover.getBlockSize(); }

    @Override
    public void setPlaybackPosition(long frame) { this.framesProcessed = frame; }

    @Override
    public void analyze(float[] samples, int channels)
    {
        if (samples == null || channels < 1) return;
        ensureCrossover(channels);
        AnalysisInputKey key = AnalysisInputKey.of(samples, sampleRate, channels);
        if (key.equals(featureKey) && bandPeakMap != null) {
            mapToEnvelopes();
            return;
        }
        int frames = samples.length / channels;
        inputTruePeak = TruePeak.measureMax(samples, channels);
        envRate = sampleRate;
        envFrames = frames;
        atkSamples = Math.max(1, (int) (ATTACK_MS * 0.001 * sampleRate));
        relSamples = Math.max(1, (int) (RELEASE_MS * 0.001 * sampleRate));

        float[][] pmaps = new float[BANDS][frames];
        java.util.Arrays.fill(bandPeak, 0.0);
        crossover.reset();
        int blockSize = crossover.getBlockSize();
        int latency = crossover.getLatency();
        int end = Math.addExact(frames, latency);
        float[] block = new float[Math.min(blockSize, end) * channels];
        float[][] bands = new float[BANDS][block.length];
        // Store source-aligned peaks and flush the crossover's delay. Without
        // this tail the final transient's real peak never enters the analysis.
        // Keep only linked peaks, never four whole stereo PCM tracks.
        for (int start = 0; start < end; )
        {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int count = Math.min(blockSize, end - start);
            if (block.length != count * channels) block = new float[count * channels];
            java.util.Arrays.fill(block, 0);
            int available = Math.max(0, Math.min(count, frames - start));
            if (available > 0) System.arraycopy(samples, start * channels, block, 0, available * channels);
            crossover.process(block, channels, bands);
            for (int b = 0; b < BANDS; b++)
            {
                for (int f = 0; f < count; f++)
                {
                    int sourceFrame = start + f - latency;
                    if (sourceFrame < 0 || sourceFrame >= frames) continue;
                    float peak = 0;
                    for (int c = 0; c < channels; c++)
                        peak = Math.max(peak, Math.abs(bands[b][f * channels + c]));
                    pmaps[b][sourceFrame] = peak;
                    bandPeak[b] = Math.max(bandPeak[b], peak);
                }
            }
            start += count;
        }
        crossover.reset();
        bandPeakMap = pmaps;
        featureKey = key;
        mapToEnvelopes();
    }

    /** Rebuilds all bands' gain envelopes (used after analysis). */
    private void mapToEnvelopes()
    {
        float[][] pmaps = bandPeakMap;
        if (pmaps == null) return;
        float[][] envs = new float[BANDS][];
        for (int b = 0; b < BANDS; b++) envs[b] = buildBandEnv(b, pmaps);
        appliedPushDb = pushDb.clone();
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
        double[] applied = appliedPushDb.clone();
        applied[band] = pushDb[band];
        appliedPushDb = applied;
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
        float[] e = OfflineLimiterEnvelope.compute(pmaps[b], th, atkSamples, relSamples);
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
            double pos = (framesProcessed + f - crossover.getLatency()) * ratio;
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
        reuseAnalysisFeatures(other);
        if (java.util.Arrays.equals(pushDb, other.appliedPushDb)) {
            // Immutable analysis arrays are safe to share. Never remap a track
            // on the FX thread when the worker has already done exactly that.
            this.appliedPushDb = other.appliedPushDb;
            this.bandEnv = other.bandEnv;
        } else mapToEnvelopes(); // synchronous API for callers requesting different controls
    }

    /** FX publication of an already current snapshot, including bypass (null envelope).
     * Unlike the synchronous convenience API this never remaps, even after an off-state edit. */
    public void adoptPreparedAnalysis(MultibandLimiterProcessor other)
    {
        reuseAnalysisFeatures(other);
        this.appliedPushDb = other.appliedPushDb;
        this.bandEnv = other.bandEnv;
    }

    /** Constant-time transfer to a worker; analysis validates the complete new input key. */
    public void reuseAnalysisFeatures(MultibandLimiterProcessor other)
    {
        this.envRate = other.envRate;
        this.envFrames = other.envFrames;
        this.atkSamples = other.atkSamples;
        this.relSamples = other.relSamples;
        System.arraycopy(other.bandPeak, 0, this.bandPeak, 0, BANDS);
        this.bandPeakMap = other.bandPeakMap;
        this.featureKey = other.featureKey;
        this.inputTruePeak = other.inputTruePeak;
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
        if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite multiband parameter.");
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
