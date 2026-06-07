package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;

import com.dspark.core.DspMath;

/**
 * <b>Punch</b> - transient expander.
 * <p>
 * Tooltip: <i>"Adds punch by boosting every transient in the song."</i>
 * <p>
 * Makes the track hit harder by <b>boosting the transients</b> while leaving the
 * body at unity. Transients are located with the shared spectral-flux onset
 * detector ({@link TrackAnalysis}), which catches every attack - including
 * snares/kicks masked inside a dense wall, where a plain amplitude detector
 * would miss them. From the onset map a boost shape is placed directly at each
 * transient: it ramps up over a few ms <i>before</i> the onset so it is already
 * at {@code +Amount} dB when the attack lands, holds across the attack, then
 * falls. The boost is the same for every transient, regardless of its strength.
 * <p>
 * The single control is <b>Amount</b> (dB of transient boost).
 */
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
    /** Onsets weaker than this fraction of the strongest are ignored (noise). */
    private static final double SENSITIVITY = 0.12;

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
        this.amountDb = DspMath.clamp(db, MIN_AMOUNT_DB, MAX_AMOUNT_DB);
    }

    @Override
    protected void computeFeatures(float[] samples, int channels, int sampleRate, int frames)
    {
        float[] w = new float[frames];
        TrackAnalysis ta = trackAnalysis;
        if (ta != null && ta.getOnsetCount() > 0 && sampleRate > 0)
        {
            double[] times = ta.getOnsetTimesSec();
            double[] durs = ta.getOnsetDurationsSec();
            float[] strengths = ta.getOnsetStrengths();
            float maxStrength = 0.0f;
            for (float s : strengths) if (s > maxStrength) maxStrength = s;
            float floor = (float) (SENSITIVITY * maxStrength);

            int rise = Math.max(1, (int) (RISE_MS / 1000.0 * sampleRate));
            int fall = Math.max(1, (int) (FALL_MS / 1000.0 * sampleRate));
            for (int k = 0; k < times.length; k++)
            {
                if (strengths[k] < floor) continue;
                int s = (int) (times[k] * sampleRate);
                int hold = (int) (DspMath.clamp((k < durs.length) ? durs[k] : 0.03,
                        MIN_ATTACK_SEC, MAX_ATTACK_SEC) * sampleRate);
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
            env[i] = (float) DspMath.decibelsToGain(amountDb * weight[i]);   // >= 1
        }
        gainEnv = env;
    }
}
