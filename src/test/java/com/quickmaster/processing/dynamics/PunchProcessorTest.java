package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link PunchProcessor} (onset-based, consistent transient boost). */
class PunchProcessorTest
{
    private static final int SR = 44100;

    /** Clicks of alternating amplitude (strong/weak) to check the boost is consistent. */
    private static float[] varyingClicks(double durSec, double intervalSec, double offsetSec)
    {
        int n = (int) (SR * durSec);
        float[] x = new float[n];
        Random rnd = new Random(7);
        int burst = (int) (0.008 * SR);
        double tau = 0.003 * SR;
        int k = 0;
        for (double t = offsetSec; t < durSec; t += intervalSec, k++)
        {
            double amp = (k % 2 == 0) ? 0.9 : 0.5;     // strong / weak
            int start = (int) (t * SR);
            for (int j = 0; j < burst && start + j < n; j++)
            {
                double decay = Math.exp(-j / tau);
                x[start + j] += (float) ((rnd.nextDouble() * 2 - 1) * decay * amp);
            }
        }
        return x;
    }

    private static float[] gainEnvelope(PunchProcessor p, int frames)
    {
        p.prepare(SR, frames);
        float[] ones = new float[frames];
        java.util.Arrays.fill(ones, 1.0f);
        return p.process(ones, 1);
    }

    @Test
    @DisplayName("Every transient is boosted by the same amount, regardless of its strength")
    void boostsAllTransientsConsistently()
    {
        float[] s = varyingClicks(8.0, 0.5, 0.2);
        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);
        assertTrue(ta.getOnsetCount() >= 8, "needs onsets: " + ta.getOnsetCount());

        PunchProcessor p = new PunchProcessor();
        p.setTrackAnalysis(ta);
        p.setEnabled(true);
        p.setAmountDb(6.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        float[] env = gainEnvelope(p, s.length);

        float boost = (float) Math.pow(10.0, 6.0 / 20.0);    // ~2.0
        float minB = Float.MAX_VALUE, maxB = 0f;
        for (double t : ta.getOnsetTimesSec())
        {
            // The boost must be at full right AT the attack (no delay), so sample
            // at the onset itself (the ramp finishes a few ms before it).
            int i0 = (int) (t * SR);
            float atAttack = env[Math.min(i0, env.length - 1)];
            minB = Math.min(minB, atAttack);
            maxB = Math.max(maxB, atAttack);
        }
        // Weakest and strongest transient get nearly the same boost (~+6 dB) at the attack.
        assertTrue(minB > 1.7f, "transients under-boosted at the attack: " + minB);
        assertTrue(maxB <= boost + 0.1f, "over-boosted: " + maxB);
        assertTrue(maxB - minB < 0.3f, "boost should be consistent, spread=" + (maxB - minB));
    }

    @Test
    @DisplayName("Amount 0 and disabled pass through; sustain stays at unity")
    void noEffectAndSustain()
    {
        float[] s = varyingClicks(4.0, 0.5, 0.2);
        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);

        PunchProcessor p = new PunchProcessor();
        p.setTrackAnalysis(ta);
        p.setEnabled(true);
        p.setAmountDb(6.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        float[] env = gainEnvelope(p, s.length);
        // Mid-way between clicks (0.45 s, no onset there) → unity.
        assertEquals(1.0f, env[(int) (0.45 * SR)], 0.03f);

        p.setAmountDb(0.0);
        p.analyze(s, 1);
        for (float v : gainEnvelope(p, s.length)) assertEquals(1.0f, v, 1e-4f);

        p.setAmountDb(6.0);
        p.setEnabled(false);
        for (float v : gainEnvelope(p, s.length)) assertEquals(1.0f, v, 1e-6f);
    }
}
