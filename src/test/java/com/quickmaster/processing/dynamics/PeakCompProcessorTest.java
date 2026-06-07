package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link PeakCompProcessor} (absolute-peak reducer for loudness gain). */
class PeakCompProcessorTest
{
    private static final int SR = 44100;

    /** Steady tone (the sustain, ~0.3) with one short peak at 0.9. */
    private static float[] signal()
    {
        int n = SR;
        float[] s = new float[n];
        double step = 2.0 * Math.PI * 300.0 / SR;
        for (int i = 0; i < n; i++) s[i] = (float) (0.3 * Math.sin(step * i));
        int start = (int) (0.5 * SR), len = (int) (0.002 * SR);
        for (int i = 0; i < len; i++) s[start + i] = 0.9f;     // absolute peak
        return s;
    }

    private static float[] processed(PeakCompProcessor p, float[] s)
    {
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        p.prepare(SR, s.length);
        return p.process(s.clone(), 1);
    }

    private static float maxAbs(float[] b, int from, int to)
    {
        float m = 0;
        for (int i = from; i < to; i++) m = Math.max(m, Math.abs(b[i]));
        return m;
    }

    @Test
    @DisplayName("The absolute peak is lowered by exactly the target (so the normalizer can gain it back)")
    void lowersAbsolutePeakByTarget()
    {
        float[] s = signal();
        PeakCompProcessor p = new PeakCompProcessor();
        p.setEnabled(true);
        p.setTargetDb(-4.0);
        float[] out = processed(p, s);
        double newPeakDb = 20.0 * Math.log10(maxAbs(out, 0, out.length));
        double origPeakDb = 20.0 * Math.log10(0.9);
        assertEquals(-4.0, newPeakDb - origPeakDb, 0.8);
    }

    @Test
    @DisplayName("The body (below the ceiling) is left untouched")
    void bodyUntouched()
    {
        float[] s = signal();
        PeakCompProcessor p = new PeakCompProcessor();
        p.setEnabled(true);
        p.setTargetDb(-4.0);
        float[] out = processed(p, s);
        // Early region (no peak nearby): the 0.3 tone is preserved.
        assertEquals(0.3f, maxAbs(out, 0, (int) (0.2 * SR)), 0.02f);
    }

    @Test
    @DisplayName("Max reduction is the headroom between the peak and the loudest sustain")
    void autoMaxIsHeadroom()
    {
        float[] s = signal();
        PeakCompProcessor p = new PeakCompProcessor();
        p.setTargetDb(-4.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        assertTrue(p.getMaxReductionDb() > 7.0, "headroom was " + p.getMaxReductionDb());

        // A target beyond the headroom is auto-limited to it (peak → sustain level).
        p.setEnabled(true);
        p.setTargetDb(-15.0);
        float[] out = processed(p, s);
        assertTrue(maxAbs(out, 0, out.length) < 0.4f, "peak should be shaved to ~the sustain");
    }

    @Test
    @DisplayName("Release defaults to the floor and follows the track's transients")
    void releaseFollowsTransients()
    {
        float[] s = signal();
        PeakCompProcessor p = new PeakCompProcessor();
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        assertEquals(PeakCompProcessor.MIN_RELEASE_MS, p.getReleaseMs(), 1e-6);   // no analysis -> floor

        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);
        p.setTrackAnalysis(ta);
        p.analyze(s, 1);
        assertTrue(p.getReleaseMs() >= PeakCompProcessor.MIN_RELEASE_MS);
        assertTrue(p.getReleaseMs() <= PeakCompProcessor.MAX_RELEASE_MS);
    }

    @Test
    @DisplayName("Target 0 dB and disabled both pass through")
    void noEffectCases()
    {
        float[] s = signal();
        PeakCompProcessor p = new PeakCompProcessor();
        p.setEnabled(true);
        p.setTargetDb(0.0);
        float[] out = processed(p, s);
        assertEquals(0.9f, maxAbs(out, 0, out.length), 0.01f);

        p.setTargetDb(-4.0);
        p.setEnabled(false);
        out = processed(p, s);
        assertEquals(0.9f, maxAbs(out, 0, out.length), 1e-4f);
    }
}
