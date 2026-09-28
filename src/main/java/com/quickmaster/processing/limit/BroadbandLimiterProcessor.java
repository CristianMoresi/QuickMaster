package com.quickmaster.processing.limit;
import com.quickmaster.processing.AudioProcessor;

import com.dspark.analysis.TruePeak;
import com.quickmaster.processing.analysis.AnalysisInputKey;

/**
 * Second (final) limiting layer: an automatic <b>broadband true-peak limiter</b>.
 * <p>
 * Works like a single-band {@link MultibandLimiterProcessor}: one <b>Push</b>
 * input-drive control in dB. Its ceiling is anchored before the multiband
 * stage, while its detector sees the actual multiband output. The level it reads
 * is the 4&times; oversampled <b>true peak</b> (ITU-R BS.1770), so it tames the
 * inter-sample peaks the multiband layer leaves behind, letting the final Peak
 * Normalizer push the true peak right up to the delivery ceiling.
 * <p>
 * The gain envelope is precomputed on the base-rate program with a smooth
 * look-ahead attack baked in, so it has zero latency and the meter
 * ({@link #getGrAtPosition}) reads identically at any oversampling factor.
 * Disabled by default.
 */
public final class BroadbandLimiterProcessor implements AudioProcessor
{
    public static final double MAX_PUSH_DB = 9.0;
    private static final double ATTACK_MS = 1.5;
    private static final double RELEASE_MS = 80.0;

    private volatile boolean enabled = false;
    private volatile double pushDb = 0.0;
    private double appliedPushDb = 0.0;
    private AnalysisInputKey featureKey;
    private double requestedReference = Double.NaN;
    private double ceilingTruePeak, safetyTrimDb;

    /** Pipeline-owned reference; NaN selects this standalone stage's input. */
    public void setCeilingReference(double peak) {
        if (!Double.isNaN(peak) && (!Double.isFinite(peak) || peak < 0))
            throw new IllegalArgumentException("Invalid limiter reference.");
        requestedReference=peak;
    }
    public double getCeilingTruePeak() { return ceilingTruePeak; }
    public double getSafetyTrimDb() { return safetyTrimDb; }

    private volatile float[] peakMapTp = null;   // cached true-peak per frame (for remap)
    private volatile float[] env = null;         // gain envelope (null = unity)
    private double peakTp = 0.0;                  // program true peak

    private double envRate = 0.0;
    private int envFrames = 0;
    private int atkSamples = 64, relSamples = 3840;

    private int sampleRate = 0;
    private long framesProcessed = 0L;

    public double getPushDb() { return pushDb; }

    public void setPushDb(double db)
    {
        requestPushDb(db); // Keep the approved envelope until a complete analysis.
    }

    /** UI request only; analysis is mapped off the UI thread before adoption. */
    public void requestPushDb(double db) { pushDb = clamp(db, 0.0, MAX_PUSH_DB); }

    /** Gain reduction (dB, &le; 0) at base-rate position {@code baseFrame}. */
    public double getGrAtPosition(long baseFrame)
    {
        if (!enabled) return 0.0;
        float[] e = env;
        if (e == null || e.length == 0) return 0.0;
        long i = baseFrame;
        if (i < 0) i = 0; else if (i >= e.length) i = e.length - 1;
        double g = e[(int) i];
        double gr = 20.0 * Math.log10(Math.max(g, 1e-6)) - appliedPushDb;
        return Math.min(gr, 0.0);
    }

    /** Deepest gain reduction (dB, &le; 0) over a base-rate frame range. */
    public double getGrDeepest(long from, long to)
    {
        if (!enabled) return 0.0;
        float[] e = env;
        if (e == null || e.length == 0) return 0.0;
        int a = (int) Math.max(0, Math.min(from, to));
        int b = (int) Math.min(e.length - 1, Math.max(from, to));
        double minG = Double.MAX_VALUE;
        for (int i = a; i <= b; i++) if (e[i] < minG) minG = e[i];
        if (minG == Double.MAX_VALUE) return 0.0;
        double gr = 20.0 * Math.log10(Math.max(minG, 1e-6)) - appliedPushDb;
        return Math.min(gr, 0.0);
    }

    /** The program true peak (linear) this stage last analysed. */
    public double getTruePeak() { return peakTp; }

    @Override
    public boolean usesAnalysis() { return true; }

    @Override
    public int getLatencyFrames() { return 0; }

    @Override
    public void prepare(int sampleRate, long totalSamples)
    {
        this.sampleRate = sampleRate;
        this.framesProcessed = 0L;
    }

    @Override
    public void setPlaybackPosition(long frame) { this.framesProcessed = frame; }

    @Override
    public void analyze(float[] samples, int channels)
    {
        if (samples == null || channels < 1) return;
        AnalysisInputKey key = AnalysisInputKey.of(samples, sampleRate, channels);
        if (key.equals(featureKey) && peakMapTp != null) {
            mapToEnvelope();
            verifyCeiling(samples, channels);
            return;
        }
        int frames = samples.length / channels;
        envRate = sampleRate;
        envFrames = frames;
        atkSamples = Math.max(1, (int) (ATTACK_MS * 0.001 * sampleRate));
        relSamples = Math.max(1, (int) (RELEASE_MS * 0.001 * sampleRate));

        TruePeak[] det = new TruePeak[channels];
        for (int c = 0; c < channels; c++) det[c] = new TruePeak();
        float[] pm = new float[frames];
        double mx = 0.0;
        int lag = TruePeak.GROUP_DELAY_FRAMES;
        for (int f = 0; f < frames + TruePeak.TAIL_FRAMES; f++)
        {
            if ((f & 16383) == 0 && Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            int base = f * channels;
            double p = 0.0;
            for (int c = 0; c < channels; c++)
            {
                double sample = f < frames ? samples[base + c] : 0.0;
                double tp = det[c].process(sample);
                if (tp > p) p = tp;
                // Sample peaks have no FIR delay. Preserve their actual position
                // as well as the latency-aligned interpolation estimate.
                if (f < frames) pm[f] = Math.max(pm[f], (float) Math.abs(sample));
            }
            int aligned = f - lag;
            if (aligned >= 0 && aligned < frames) pm[aligned] = Math.max(pm[aligned], (float) p);
            if (p > mx) mx = p;
        }
        peakMapTp = pm;
        peakTp = mx;
        featureKey = key;
        mapToEnvelope();
        verifyCeiling(samples, channels);
    }

    private void mapToEnvelope()
    {
        float[] pm = peakMapTp;
        if (pm == null) return;
        appliedPushDb = pushDb;
        ceilingTruePeak = Double.isNaN(requestedReference) ? peakTp : requestedReference;
        safetyTrimDb=0;
        if (peakTp <= 1e-9 || ceilingTruePeak <= 0
                || (pushDb <= 1e-6 && peakTp <= ceilingTruePeak))
        {
            env = null;
            return;
        }
        // Drive the post-multiband mix, but do not let its changed peak move
        // the ceiling. Zero drive still limits peaks raised by multiband.
        double makeup = Math.pow(10.0, pushDb / 20.0);
        double th = ceilingTruePeak / makeup;
        float[] e = OfflineLimiterEnvelope.compute(pm, th, atkSamples, relSamples);
        for (int i = 0; i < e.length; i++) e[i] *= (float) makeup;
        env = e;
    }

    /** A modulated signal can create new ISP peaks. Verify the actual float
     * output (including the FIR tail); retain a disclosed rounding/ISP trim,
     * never claim the sidechain estimate alone is a true-peak guarantee. */
    private void verifyCeiling(float[] samples,int channels) {
        if(env==null)return;
        for(int pass=0;pass<3;pass++) {
            TruePeak[] detectors=new TruePeak[channels];
            for(int c=0;c<channels;c++)detectors[c]=new TruePeak();
            double peak=0;
            int frames=samples.length/channels;
            for(int f=0;f<frames+TruePeak.TAIL_FRAMES;f++) {
                if((f&16383)==0 && Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                for(int c=0;c<channels;c++) {
                    float value=f<frames?samples[f*channels+c]*env[f]:0;
                    if(!Float.isFinite(value))throw new IllegalStateException("Non-finite limiter output.");
                    peak=Math.max(peak,detectors[c].process(value));
                }
            }
            if(peak<=ceilingTruePeak*(1+1e-7))return;
            double scale=ceilingTruePeak/peak*(1-2e-7);
            for(int f=0;f<env.length;f++)env[f]*=(float)scale;
            safetyTrimDb+=20*Math.log10(scale);
        }
        throw new IllegalStateException("Limiter true-peak verification did not converge.");
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        if (!enabled) return buffer;
        float[] e = env;
        if (e == null || envRate <= 0.0 || sampleRate <= 0) return buffer;

        int frames = buffer.length / channels;
        double ratio = envRate / sampleRate;
        for (int f = 0; f < frames; f++)
        {
            double pos = (framesProcessed + f) * ratio;
            float g = sampleEnv(e, pos);
            int base = f * channels;
            for (int c = 0; c < channels; c++) buffer[base + c] *= g;
        }
        framesProcessed += frames;
        return buffer;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Adopts the analysis (true-peak map + peak) from a background re-render copy. */
    public void adoptAnalysis(BroadbandLimiterProcessor other)
    {
        reuseAnalysisFeatures(other);
        if (pushDb == other.appliedPushDb) {
            this.appliedPushDb = other.appliedPushDb;
            this.env = other.env;
            ceilingTruePeak=other.ceilingTruePeak;safetyTrimDb=other.safetyTrimDb;
        } else throw new IllegalStateException("Changed limiter controls require fresh analysis.");
    }

    /** Publishes the worker result without recalculation, also for bypass snapshots. */
    public void adoptPreparedAnalysis(BroadbandLimiterProcessor other)
    {
        reuseAnalysisFeatures(other);
        this.appliedPushDb = other.appliedPushDb;
        this.env = other.env;
        this.ceilingTruePeak=other.ceilingTruePeak;
        this.safetyTrimDb=other.safetyTrimDb;
    }

    /** Shares one immutable feature set; worker verifies rate, channels and every sample. */
    public void reuseAnalysisFeatures(BroadbandLimiterProcessor other)
    {
        this.envRate = other.envRate;
        this.envFrames = other.envFrames;
        this.atkSamples = other.atkSamples;
        this.relSamples = other.relSamples;
        this.peakTp = other.peakTp;
        this.peakMapTp = other.peakMapTp;
        this.featureKey = other.featureKey;
    }

    /* --- helpers --- */

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
        if(!Double.isFinite(v))throw new IllegalArgumentException("Non-finite broadband parameter.");
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
