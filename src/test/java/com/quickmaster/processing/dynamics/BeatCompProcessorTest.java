package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link BeatCompProcessor}. */
class BeatCompProcessorTest
{
    private static final int SR = 44100;

    /** Clicks at a fixed tempo; a couple are much louder than the rest. */
    private static float[] clicks(double durSec, double intervalSec, double offsetSec, int... loudIdx)
    {
        int n = (int) (SR * durSec);
        float[] x = new float[n];
        Random rnd = new Random(13);
        int burst = (int) (0.008 * SR);
        double tau = 0.003 * SR;
        int k = 0;
        java.util.Set<Integer> loud = new java.util.HashSet<>();
        for (int i : loudIdx) loud.add(i);
        for (double t = offsetSec; t < durSec; t += intervalSec, k++)
        {
            double amp = loud.contains(k) ? 0.9 : 0.45;
            int start = (int) (t * SR);
            for (int j = 0; j < burst && start + j < n; j++)
            {
                double decay = Math.exp(-j / tau);
                x[start + j] += (float) ((rnd.nextDouble() * 2 - 1) * decay * amp);
            }
        }
        return x;
    }

    private static float[] gainEnvelope(BeatCompProcessor p, int frames)
    {
        p.prepare(SR, frames);
        float[] ones = new float[frames];
        java.util.Arrays.fill(ones, 1.0f);
        return p.process(ones, 1);
    }

    @Test
    @DisplayName("Release is locked to the tempo and note value")
    void tempoSyncedRelease()
    {
        TrackAnalysis ta = new TrackAnalysis();
        ta.setManualBpm(120.0);
        BeatCompProcessor p = new BeatCompProcessor();
        p.setTrackAnalysis(ta);
        p.setNote(BeatCompProcessor.NoteValue.QUARTER);
        assertEquals(500.0, p.getReleaseMs(), 0.1);
        p.setNote(BeatCompProcessor.NoteValue.EIGHTH);
        assertEquals(250.0, p.getReleaseMs(), 0.1);
        p.setNote(BeatCompProcessor.NoteValue.WHOLE);
        assertEquals(2000.0, p.getReleaseMs(), 0.1);
    }

    @Test
    @DisplayName("Louder beats are turned down toward the median; typical beats are left alone")
    void levelsLouderBeats()
    {
        float[] s = clicks(6.0, 0.5, 0.2, 3, 7);     // beats 3 and 7 are loud
        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);
        assertTrue(ta.getOnsetCount() >= 8);

        BeatCompProcessor p = new BeatCompProcessor();
        p.setTrackAnalysis(ta);
        p.setEnabled(true);
        p.setTargetDb(-6.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        float[] env = gainEnvelope(p, s.length);

        double tLoud = 0.2 + 3 * 0.5;                 // a loud beat
        double tNormal = 0.2 + 2 * 0.5;               // a typical beat
        float gLoud = minNear(env, tLoud);
        float gNormal = minNear(env, tNormal);
        assertTrue(gLoud < 0.8f, "loud beat should be turned down, was " + gLoud);
        assertTrue(gNormal > 0.9f, "typical beat should be ~untouched, was " + gNormal);
    }

    private static float minNear(float[] env, double tSec)
    {
        int i0 = (int) (tSec * SR);
        float m = 1.0f;
        for (int i = i0; i < i0 + (int) (0.03 * SR) && i < env.length; i++) m = Math.min(m, env[i]);
        return m;
    }

    @Test
    @DisplayName("Disabled and 0 dB target pass through")
    void noEffectCases()
    {
        float[] s = clicks(4.0, 0.5, 0.2, 2);
        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);
        BeatCompProcessor p = new BeatCompProcessor();
        p.setTrackAnalysis(ta);
        p.setEnabled(true);
        p.setTargetDb(0.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        for (float v : gainEnvelope(p, s.length)) assertEquals(1.0f, v, 1e-3f);

        p.setTargetDb(-6.0);
        p.setEnabled(false);
        for (float v : gainEnvelope(p, s.length)) assertEquals(1.0f, v, 1e-6f);
    }
}
