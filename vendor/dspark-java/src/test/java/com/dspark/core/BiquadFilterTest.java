package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The double-precision TDF-II filter {@link BiquadFilter}. */
class BiquadFilterTest
{
    /** Steady-state peak of a sine through a filter (the C++ measureFilterResponse). */
    private static double measureFilterResponse(BiquadFilter filter, double freq, double sampleRate)
    {
        for (int i = 0; i < 2048; ++i)
            filter.processSample(Math.sin(2.0 * Math.PI * freq * i / sampleRate), 0);
        double peak = 0.0;
        for (int i = 0; i < 8192; ++i)
        {
            final double out = filter.processSample(Math.sin(2.0 * Math.PI * freq * (2048 + i) / sampleRate), 0);
            peak = Math.max(peak, Math.abs(out));
        }
        return peak;
    }

    private static BiquadFilter with(BiquadCoeffs c)
    {
        final BiquadFilter f = new BiquadFilter();
        f.setCoeffs(c);
        return f;
    }

    @Test
    @DisplayName("Cookbook responses through the filter: pass, stop, -3 dB, boost, all-pass")
    void cookbookResponses()
    {
        assertTrue(measureFilterResponse(with(BiquadDesign.makeLowPass(44100.0, 5000.0)), 100.0, 44100.0) > 0.95);
        assertTrue(measureFilterResponse(with(BiquadDesign.makeLowPass(44100.0, 1000.0)), 10000.0, 44100.0) < 0.1);
        final double cutoff = measureFilterResponse(with(BiquadDesign.makeLowPass(44100.0, 1000.0)), 1000.0, 44100.0);
        assertTrue(cutoff > 0.60 && cutoff < 0.80);
        assertTrue(measureFilterResponse(with(BiquadDesign.makeHighPass(44100.0, 1000.0)), 10000.0, 44100.0) > 0.90);
        assertTrue(measureFilterResponse(with(BiquadDesign.makeHighPass(44100.0, 1000.0)), 100.0, 44100.0) < 0.15);

        final BiquadFilter peak = with(BiquadDesign.makePeak(44100.0, 1000.0, 2.0, 6.0));
        assertTrue(measureFilterResponse(peak, 1000.0, 44100.0) > 1.5);
        final double off = measureFilterResponse(peak, 100.0, 44100.0);
        assertTrue(off > 0.85 && off < 1.15);

        final BiquadFilter allPass = with(BiquadDesign.makeAllPass(44100.0, 1000.0));
        for (double f : new double[] { 100.0, 500.0, 1000.0, 5000.0, 10000.0 })
        {
            allPass.reset();
            assertEquals(1.0, measureFilterResponse(allPass, f, 44100.0), 0.05);
        }
    }

    @Test
    @DisplayName("Silence in, silence out; reset clears the state; a double sample is finite")
    void silenceResetAndFinite()
    {
        final BiquadFilter bq = with(BiquadDesign.makeLowPass(44100.0, 1000.0));
        final double[][] block = new double[2][512];
        bq.process(block, 512);
        for (double[] ch : block) for (double v : ch) assertEquals(0.0, v, 1e-10);

        for (int i = 0; i < 100; ++i) bq.processSample(1.0, 0);
        bq.reset();
        assertEquals(0.0, bq.processSample(0.0, 0), 1e-10);

        final BiquadFilter d = with(BiquadDesign.makeLowPass(44100.0, 1000.0));
        assertTrue(Double.isFinite(d.processSample(1.0, 0)));
    }

    @Test
    @DisplayName("The block path equals the sample path to the bit, and does not depend on the block size")
    void blockMatchesSampleAndBlockSizes()
    {
        final BiquadCoeffs c = BiquadDesign.makeLowPass(44100.0, 2000.0);
        final double[] input = PortSignals.sine(9000, 440.0, 44100.0, 1.0);
        final PortSignals.Lcg rng = new PortSignals.Lcg(7);
        for (int i = 0; i < input.length; ++i) input[i] += 0.3 * rng.next();

        final BiquadFilter sample = with(c);
        final double[] want = new double[input.length];
        for (int i = 0; i < input.length; ++i) want[i] = sample.processSample(input[i], 0);

        for (int blockSize : new int[] { 1, 37, 512, 4096 })
        {
            final BiquadFilter block = with(c);
            final double[][] buf = { input.clone() };
            for (int pos = 0; pos < input.length; pos += blockSize)
                block.process(buf, 1, pos, Math.min(blockSize, input.length - pos));
            for (int i = 0; i < input.length; ++i) assertEquals(want[i], buf[0][i], 0.0);
        }
        // reset() equals a fresh instance.
        final BiquadFilter used = with(c);
        for (int i = 0; i < 500; ++i) used.processSample(input[i], 0);
        used.reset();
        final BiquadFilter fresh = with(c);
        for (int i = 0; i < 2000; ++i) assertEquals(fresh.processSample(input[i], 0), used.processSample(input[i], 0), 0.0);
    }

    @Test
    @DisplayName("setCoeffsNow is immediate and equals a staged set promoted at once")
    void setCoeffsNowIsImmediate()
    {
        final BiquadCoeffs lp = BiquadDesign.makeLowPass(48000.0, 2000.0);
        final BiquadCoeffs hp = BiquadDesign.makeHighPass(48000.0, 500.0);
        final BiquadFilter ref = new BiquadFilter(), direct = new BiquadFilter();
        ref.setCoeffs(lp);
        assertTrue(ref.applyPendingCoeffs());
        assertFalse(ref.applyPendingCoeffs());
        direct.setCoeffsNow(lp);
        assertEquals(lp.b0, direct.getCoeffs().b0, 0.0);
        assertEquals(lp.a2, direct.getCoeffs().a2, 0.0);
        for (int i = 0; i < 96; ++i)
        {
            final double x = Math.sin(0.17 * i);
            if ((i & 15) == 0)
            {
                final BiquadCoeffs c = (i & 16) != 0 ? hp : lp;
                ref.setCoeffs(c);
                ref.applyPendingCoeffs();
                direct.setCoeffsNow(c);
            }
            assertEquals(ref.processSample(x, 0), direct.processSample(x, 0), 0.0);
        }
    }

    @Test
    @DisplayName("A pending staged set overwrites a direct set at the next gate")
    void pendingPublicationOverwritesADirectSet()
    {
        final BiquadCoeffs staged = BiquadDesign.makeLowPass(48000.0, 1000.0);
        final BiquadCoeffs direct = BiquadDesign.makeHighPass(48000.0, 4000.0);
        final BiquadFilter bq = new BiquadFilter();
        bq.setCoeffs(staged);
        bq.setCoeffsNow(direct);
        assertEquals(direct.b0, bq.getCoeffs().b0, 0.0);
        bq.processSample(0.5, 0);
        assertEquals(staged.b0, bq.getCoeffs().b0, 0.0);
        assertEquals(staged.a1, bq.getCoeffs().a1, 0.0);
        bq.setCoeffsNow(direct);
        bq.processSample(0.5, 0);
        assertEquals(direct.b0, bq.getCoeffs().b0, 0.0);
    }

    @Test
    @DisplayName("A staged set lands at the start of the next block")
    void stagedSetLandsAtTheNextBlock()
    {
        final BiquadCoeffs c = BiquadDesign.makePeak(48000.0, 800.0, 1.0, 9.0);
        final BiquadFilter staged = new BiquadFilter(1), now = new BiquadFilter(1);
        staged.setCoeffs(c);
        now.setCoeffsNow(c);
        final double[][] a = { PortSignals.sine(256, 300.0, 48000.0, 0.5) };
        final double[][] b = { a[0].clone() };
        staged.process(a, 256);
        now.process(b, 256);
        for (int i = 0; i < 256; ++i) assertEquals(b[0][i], a[0][i], 0.0);
    }

    @Test
    @DisplayName("One NaN does not latch the block path: the state is cleared at the end of its block")
    void blockRecoversFromNan()
    {
        final BiquadFilter q = new BiquadFilter(4);
        q.setCoeffsNow(BiquadDesign.makeLowPass(48000.0, 1000.0, 0.7));
        final double[][] v = new double[3][512];
        for (double[] ch : v) java.util.Arrays.fill(ch, 0.1);
        v[0][10] = v[1][20] = v[2][30] = Double.NaN;
        q.process(v, 512);
        int bad = 0;
        for (int r = 0; r < 94; ++r)
        {
            for (double[] ch : v) java.util.Arrays.fill(ch, 0.1);
            q.process(v, 512);
            for (double[] ch : v) for (double x : ch) bad += Double.isFinite(x) ? 0 : 1;
        }
        assertEquals(0, bad);
        assertEquals(0.1, v[0][511], 1e-4);
    }

    @Test
    @DisplayName("Channels beyond the maximum are left untouched")
    void channelsBeyondTheMaximumAreUntouched()
    {
        final BiquadFilter q = new BiquadFilter(1);
        q.setCoeffsNow(BiquadDesign.makeLowPass(48000.0, 500.0));
        final double[][] v = { PortSignals.sine(64, 5000.0, 48000.0, 1.0), PortSignals.sine(64, 5000.0, 48000.0, 1.0) };
        final double[] before = v[1].clone();
        q.process(v, 64);
        for (int i = 0; i < 64; ++i) assertEquals(before[i], v[1][i], 0.0);
    }

    @Test
    @DisplayName("processRampCore with a zero step is processSampleCore; with a step it walks c0 + (k + 1) step")
    void rampCoreWalksTheCoefficients()
    {
        final BiquadCoeffs from = BiquadDesign.makePeak(48000.0, 700.0, 2.0, 6.0);
        final BiquadCoeffs to = BiquadDesign.makePeak(48000.0, 2500.0, 3.0, -9.0);
        final BiquadCoeffs zero = new BiquadCoeffs(0.0, 0.0, 0.0, 0.0, 0.0);
        final double[] x = PortSignals.sine(300, 1000.0, 48000.0, 0.5);

        final BiquadFilter ramp = new BiquadFilter(1), plain = new BiquadFilter(1);
        plain.setCoeffsNow(from);
        final double[] y = x.clone();
        ramp.processRampCore(y, y.length, 0, from, zero);
        for (int i = 0; i < x.length; ++i) assertEquals(plain.processSampleCore(x[i], 0), y[i], 0.0);
        assertTrue(PortSignals.same(from, ramp.getCoeffs()));

        final int n = 200;
        final BiquadCoeffs step = new BiquadCoeffs((to.b0 - from.b0) / n, (to.b1 - from.b1) / n, (to.b2 - from.b2) / n,
                                                   (to.a1 - from.a1) / n, (to.a2 - from.a2) / n);
        final BiquadFilter walked = new BiquadFilter(1), manual = new BiquadFilter(1);
        final double[] z = x.clone();
        walked.processRampCore(z, 0, n, 0, from, step);
        double b0 = from.b0, b1 = from.b1, b2 = from.b2, a1 = from.a1, a2 = from.a2;
        for (int k = 0; k < n; ++k)
        {
            b0 += step.b0; b1 += step.b1; b2 += step.b2; a1 += step.a1; a2 += step.a2;
            manual.setCoeffsNow(new BiquadCoeffs(b0, b1, b2, a1, a2));
            assertEquals(manual.processSampleCore(x[k], 0), z[k], 0.0);
        }
        final BiquadCoeffs last = walked.getCoeffs();
        assertTrue(last.b0 == b0 && last.a1 == a1 && last.a2 == a2);
        assertEquals(to.b0, last.b0, 1e-12);
        // The samples after the run are untouched.
        for (int i = n; i < x.length; ++i) assertEquals(x[i], z[i], 0.0);
    }
}
