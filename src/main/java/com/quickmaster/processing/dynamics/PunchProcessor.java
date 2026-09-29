package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;

import com.dspark.core.DspMath;

/**
 * <b>Punch</b> - transient expander.
 * <p>
 * Boosts detected musical attacks with a bounded stereo-linked envelope.
 * <p>
 * Makes the track hit harder by <b>boosting the transients</b> while leaving the
 * body outside the attack/release region at unity. Transients are located with
 * DSPark's SuperFlux detector ({@link TrackAnalysis}), independent of the
 * tempo detector's strong-pulse gate. Detection is heuristic, not a guarantee
 * that every instrument attack can be isolated in a dense mix. From the onset map a boost shape is placed at each
 * transient: it ramps up over a few ms <i>before</i> the onset so it is already
 * at {@code +Amount} dB when the attack lands, holds across the attack, then
 * falls. The boost is the same for every transient, regardless of its strength.
 * <p>
 * The single control is <b>Amount</b> (dB of transient boost).
 */
@SuppressWarnings("deprecation")
public final class PunchProcessor extends AnalysisDynamicsProcessor
{
    /** Default and bounds for the punch amount (dB of transient boost). */
    public static final double DEFAULT_AMOUNT_DB = 0.5;
    public static final double MIN_AMOUNT_DB = 0.0;
    public static final double MAX_AMOUNT_DB = 12.0;

    /** Boost shape around each onset, in ms / s. */
    private static final double RISE_MS = 5.0;          // ramp up, ending AT the onset
    private static final double FALL_MS = 45.0;         // ramp down after the attack
    private static final double MIN_ATTACK_SEC = 0.015;
    private static final double MAX_ATTACK_SEC = 0.050;

    private volatile double amountDb = DEFAULT_AMOUNT_DB;
    private TrackAnalysis trackAnalysis;

    // Cached feature: per-frame transient weight in [0,1] (1 over a transient).
    private float[] weight = new float[0];

    /** Injects the shared per-track onset analysis (transient positions). */
    public void setTrackAnalysis(TrackAnalysis ta) { this.trackAnalysis = ta; }

    public double getAmountDb() { return amountDb; }

    /** Sets the punch amount in dB. */
    public void setAmountDb(double db)
    {
        if (Double.isFinite(db)) this.amountDb = DspMath.clamp(db, MIN_AMOUNT_DB, MAX_AMOUNT_DB);
    }

    @Override
    protected void computeFeatures(float[] samples, int channels, int sampleRate, int frames)
    {
        float[] w = new float[frames];
        TrackAnalysis ta = trackAnalysis;
        if (ta != null && sampleRate > 0)
        {
            double[] times = ta.getTransientTimesSec();
            double[] durs = ta.getTransientDurationsSec();

            int rise = Math.max(1, (int) (RISE_MS / 1000.0 * sampleRate));
            int fall = Math.max(1, (int) (FALL_MS / 1000.0 * sampleRate));
            for (int k = 0; k < times.length; k++)
            {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                int detected = (int) (times[k] * sampleRate);
                int s = refineAttack(samples, channels, frames, sampleRate, detected);
                int hold = (int) (DspMath.clamp((k < durs.length) ? durs[k] : 0.03,
                        MIN_ATTACK_SEC, MAX_ATTACK_SEC) * sampleRate) + detected - s;
                // Ramp up to 1 by the onset, hold across the attack, then ramp down.
                for (int i = Math.max(0, s - rise); i < s && i < frames; i++)
                {
                    float v = (float) (0.5 - 0.5 * Math.cos(Math.PI * (i - (s - rise)) / rise));
                    if (v > w[i]) w[i] = v;
                }
                for (int i = Math.max(0, s); i <= s + hold && i < frames; i++) w[i] = 1.0f;
                for (int i = Math.max(0, s + hold); i <= s + hold + fall && i < frames; i++)
                {
                    float v = (float) (0.5 + 0.5 * Math.cos(Math.PI * (i - (s + hold)) / fall));
                    if (v > w[i]) w[i] = v;
                }
            }
        }
        this.weight = w;
    }

    /**
     * A spectral event is a window-centre estimate, not a sample-accurate attack.
     * Within its 25 ms localisation tolerance, find the strongest increase of
     * stereo-pooled 1 ms energy. Advance by one energy window so the cosine rise
     * ends before the attack, never after it. This does NOT create extra events
     * or change the tempo map. The original spectral event remains in the hold.
     */
    private static int refineAttack(float[] samples, int channels, int frames, int rate, int detected)
    {
        int window = Math.max(1, (int) Math.round(rate * .001));
        int radius = Math.max(window, (int) Math.round(rate * .025));
        int first = Math.max(window, detected - radius);
        int end = Math.min(frames - window, detected + radius);
        if (first >= end) return Math.max(0, Math.min(frames, detected));
        double before = 0, after = 0;
        for (int f = first - window; f < first; f++) before += power(samples, channels, f);
        for (int f = first; f < first + window; f++) after += power(samples, channels, f);
        double strongest = 0;
        int attack = detected;
        for (int f = first; f < end; f++)
        {
            double rise = after - before;
            if (rise > strongest) { strongest = rise; attack = f - window; }
            before += power(samples, channels, f) - power(samples, channels, f - window);
            after += power(samples, channels, f + window) - power(samples, channels, f);
        }
        return Math.max(0, Math.min(detected, attack));
    }

    private static double power(float[] samples, int channels, int frame)
    {
        double sum = 0;
        for (int c = 0; c < channels; c++) { double v = samples[frame * channels + c]; sum += v * v; }
        return sum;
    }

    @Override
    protected void mapFeaturesToGain()
    {
        int n = weight.length;
        float[] env = new float[n];
        if (amountDb <= 0.0)
        {
            java.util.Arrays.fill(env, 1.0f);
            gainEnv = env;
            return;
        }
        for (int i = 0; i < n; i++)
        {
            if ((i & 16383) == 0 && Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            env[i] = (float) DspMath.decibelsToGain(amountDb * weight[i]);   // >= 1
        }
        gainEnv = env;
    }
}
