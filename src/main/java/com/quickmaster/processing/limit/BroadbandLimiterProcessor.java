package com.quickmaster.processing.limit;
import com.quickmaster.processing.AudioProcessor;

import com.dspark.analysis.TruePeak;
import com.dspark.effects.LimiterEnvelope;

/**
 * Second (final) limiting layer: an automatic <b>broadband true-peak limiter</b>.
 * <p>
 * Works like a single-band {@link MultibandLimiterProcessor}: one <b>Push</b>
 * control in dB whose threshold is solved from the whole-signal analysis so the
 * loudest peak is brickwall limited by exactly that amount. The level it reads
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
        pushDb = clamp(db, 0.0, MAX_PUSH_DB);
        mapToEnvelope();
    }

    /** Gain reduction (dB, &le; 0) at base-rate position {@code baseFrame}. */
    public double getGrAtPosition(long baseFrame)
    {
        if (!enabled) return 0.0;
        float[] e = env;
        if (e == null || e.length == 0) return 0.0;
        long i = baseFrame;
        if (i < 0) i = 0; else if (i >= e.length) i = e.length - 1;
        double g = e[(int) i];
        double gr = 20.0 * Math.log10(Math.max(g, 1e-6)) - pushDb;   // remove the make-up
        return Math.max(Math.min(gr, 0.0), -MAX_PUSH_DB);
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
        double gr = 20.0 * Math.log10(Math.max(minG, 1e-6)) - pushDb;   // remove the make-up
        return Math.max(Math.min(gr, 0.0), -MAX_PUSH_DB);
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
        int frames = samples.length / channels;
        envRate = sampleRate;
        envFrames = frames;
        atkSamples = Math.max(1, (int) (ATTACK_MS * 0.001 * sampleRate));
        relSamples = Math.max(1, (int) (RELEASE_MS * 0.001 * sampleRate));

        TruePeak[] det = new TruePeak[channels];
        for (int c = 0; c < channels; c++) det[c] = new TruePeak();
        float[] pm = new float[frames];
        double mx = 0.0;
        for (int f = 0; f < frames; f++)
        {
            int base = f * channels;
            double p = 0.0;
            for (int c = 0; c < channels; c++)
            {
                double tp = det[c].process(samples[base + c]);
                if (tp > p) p = tp;
            }
            pm[f] = (float) p;
            if (p > mx) mx = p;
        }
        // The detector's polyphase FIR lags its estimate by a few frames;
        // shift the map left so the envelope lands on the audio it measured.
        int lag = TruePeak.GROUP_DELAY_FRAMES;
        if (frames > lag)
        {
            System.arraycopy(pm, lag, pm, 0, frames - lag);
            java.util.Arrays.fill(pm, frames - lag, frames, pm[frames - lag - 1]);
        }
        peakMapTp = pm;
        peakTp = mx;
        mapToEnvelope();
    }

    private void mapToEnvelope()
    {
        float[] pm = peakMapTp;
        if (pm == null) return;
        if (pushDb <= 1e-6 || peakTp <= 1e-9)
        {
            env = null;
            return;
        }
        // Push the whole mix UP by the dialled dB (make-up) and true-peak limit it
        // back to the original peak: the body rises (louder, denser) and the loudest
        // true peak is held and limited by exactly that many dB.
        double makeup = Math.pow(10.0, pushDb / 20.0);
        double th = peakTp * Math.pow(10.0, -pushDb / 20.0);
        float[] e = LimiterEnvelope.computeFromPeaks(pm, th, atkSamples, relSamples);
        for (int i = 0; i < e.length; i++) e[i] *= (float) makeup;
        env = e;
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
        this.envRate = other.envRate;
        this.envFrames = other.envFrames;
        this.atkSamples = other.atkSamples;
        this.relSamples = other.relSamples;
        this.peakTp = other.peakTp;
        this.peakMapTp = other.peakMapTp;
        mapToEnvelope();
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
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
