package com.dspark.core;

import com.dspark.core.SmoothedParameter.SmoothingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link SmoothedParameter}, the current C++ SmoothedValue. */
class SmoothedParameterTest
{
    private static int samplesToArrive(SmoothedParameter sv)
    {
        int n = 0;
        while (sv.isSmoothing() && n < 10000000) { sv.getNextValue(); ++n; }
        return n;
    }

    // -- Ports of the C++ assertions ---------------------------------------------------

    @Test
    @DisplayName("Exponential converges")
    void exponentialConverges()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(44100.0, 20.0);
        sv.setTargetValue(1.0);
        for (int i = 0; i < 4410; ++i) sv.getNextValue();
        assertEquals(1.0, sv.getCurrentValue(), 0.05);
    }

    @Test
    @DisplayName("Linear converges")
    void linear()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(44100.0, 10.0);
        sv.setSmoothingType(SmoothingType.LINEAR);
        sv.setTargetValue(1.0);
        for (int i = 0; i < 500; ++i) sv.getNextValue();
        assertEquals(1.0, sv.getCurrentValue(), 0.01);
    }

    @Test
    @DisplayName("The linear ramp time does not depend on the step size")
    void linearRampTimeIsIndependentOfStep()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(48000.0, 10.0);
        sv.setSmoothingType(SmoothingType.LINEAR);
        sv.reset(1000.0);
        sv.setTargetValue(2000.0);
        final int n = samplesToArrive(sv);
        assertEquals(480, n);   // C++: 480 +- 1; exactly 480 here
        assertEquals(2000.0, sv.getCurrentValue(), 0.0);
        sv.setTargetValue(2000.5);
        final double first = sv.getNextValue();
        assertEquals(0.5 / 480.0, first - 2000.0, 1e-9);
    }

    @Test
    @DisplayName("A zero ramp time is instant for Exponential and Linear")
    void zeroRampIsInstant()
    {
        for (SmoothingType type : new SmoothingType[] { SmoothingType.EXPONENTIAL, SmoothingType.LINEAR })
        {
            SmoothedParameter sv = new SmoothedParameter();
            sv.prepare(48000.0, 0.0);
            sv.setSmoothingType(type);
            sv.setTargetValue(0.8);
            assertEquals(0.8, sv.getNextValue(), 0.0);
            assertFalse(sv.isSmoothing());
        }
    }

    @Test
    @DisplayName("Disabled is instant")
    void disabledInstant()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(44100.0, 10.0);
        sv.setSmoothingType(SmoothingType.DISABLED);
        sv.setTargetValue(0.75);
        assertEquals(0.75, sv.getNextValue(), 1e-6);
        assertFalse(sv.isSmoothing());
    }

    @Test
    @DisplayName("Exponential settles and snaps exactly, also on a 2 s ramp")
    void settlesAndSnapsExactly()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(44100.0, 20.0);   // tau = 882 samples
        sv.setTargetValue(1.0);
        int n = samplesToArrive(sv);
        assertTrue(n < 20 * 882);
        assertEquals(1.0, sv.getCurrentValue(), 0.0);
        // About 16 time constants: ln(1e7) = 16.12.
        assertEquals(882.0 * Math.log(1e7), n, 2.0);

        SmoothedParameter slow = new SmoothedParameter();
        slow.prepare(96000.0, 2000.0);
        slow.setTargetValue(1.0);
        n = samplesToArrive(slow);
        assertTrue(n < 20 * 192000);
        assertEquals(1.0, slow.getCurrentValue(), 0.0);
    }

    @Test
    @DisplayName("A default-constructed smoother works at 44.1 kHz and 20 ms")
    void defaultConstructedIsFunctional()
    {
        SmoothedParameter lin = new SmoothedParameter();
        lin.setSmoothingType(SmoothingType.LINEAR);
        lin.setTargetValue(1.0);
        final int n = samplesToArrive(lin);
        assertEquals(1.0, lin.getCurrentValue(), 1e-6);
        assertTrue(n < 2000);
        assertEquals(882, n);

        SmoothedParameter expo = new SmoothedParameter();
        expo.setTargetValue(1.0);
        final double first = expo.getNextValue();
        assertTrue(first > 0.0 && first < 0.05);
    }

    @Test
    @DisplayName("A NaN target is ignored")
    void nanTargetIsIgnored()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(48000.0, 10.0);
        sv.setTargetValue(0.5);
        for (int i = 0; i < 100; ++i) sv.getNextValue();
        sv.setTargetValue(Double.NaN);
        assertEquals(0.5, sv.getTargetValue(), 1e-9);
        double v = 0.0;
        for (int i = 0; i < 10000; ++i) v = sv.getNextValue();
        assertFalse(Double.isNaN(v));
        assertEquals(0.5, v, 1e-6);
    }

    @Test
    @DisplayName("An infinite target is ignored")
    void infiniteTargetIsIgnored()
    {
        SmoothedParameter v = new SmoothedParameter();
        v.prepare(48000.0, 20.0);
        v.reset(1.0);
        v.setTargetValue(Double.POSITIVE_INFINITY);
        v.getNextValue();
        v.setTargetValue(0.5);
        double last = 0.0;
        for (int i = 0; i < 48000; ++i) last = v.getNextValue();
        assertEquals(0.5, last, 0.0);
        assertFalse(v.isSmoothing());
    }

    @Test
    @DisplayName("Chase reaches 99% at the ramp time at 48 and 192 kHz")
    void chaseReaches99PercentAtTheRampTime()
    {
        for (double fs : new double[] { 48000.0, 192000.0 })
            for (double ms : new double[] { 20.0, 171.0, 600.0 })
            {
                SmoothedParameter sv = new SmoothedParameter();
                sv.prepare(fs, ms);
                sv.setSmoothingType(SmoothingType.CHASE);
                sv.reset(0.0);
                sv.setTargetValue(1.0);
                int n99 = -1;
                for (int n = 1; n < (int) (fs * 5) && n99 < 0; ++n)
                    if (sv.getNextValue() >= 0.99) n99 = n;
                assertEquals(ms, n99 * 1000.0 / fs, 0.05 * ms + 0.5, "fs " + fs + " ms " + ms);
            }
    }

    @Test
    @DisplayName("Chase is rate independent, and a zero ramp time snaps")
    void chaseIsRateIndependent()
    {
        final double a = chaseSettleMs(44100.0);
        final double b = chaseSettleMs(192000.0);
        assertEquals(1.0, b / a, 0.05);
        SmoothedParameter z = new SmoothedParameter();
        z.prepare(48000.0, 0.0);
        z.setSmoothingType(SmoothingType.CHASE);
        z.reset(0.0);
        z.setTargetValue(1.0);
        assertEquals(1.0, z.getNextValue(), 0.0);
    }

    private static double chaseSettleMs(double fs)
    {
        SmoothedParameter c = new SmoothedParameter();
        c.prepare(fs, 20.0);
        c.setSmoothingType(SmoothingType.CHASE);
        c.reset(0.0);
        c.setTargetValue(1.0);
        long n = 0;
        while (c.getNextValue() < 0.99 && n < 10000000) ++n;
        return 1000.0 * n / fs;
    }

    @Test
    @DisplayName("Chase snaps onto the target after about 2.06 times the ramp time")
    void chaseSnapTime()
    {
        for (double fs : new double[] { 44100.0, 96000.0 })
        {
            SmoothedParameter c = new SmoothedParameter();
            c.prepare(fs, 100.0);
            c.setSmoothingType(SmoothingType.CHASE);
            c.reset(0.0);
            c.setTargetValue(1.0);
            final int n = samplesToArrive(c);
            assertEquals(2.06, n * 1000.0 / fs / 100.0, 0.05, "fs " + fs);
        }
    }

    @Test
    @DisplayName("processBlock matches getNextValue for all four curves with a retarget mid-run")
    void processBlockMatchesGetNextValue()
    {
        double maxDiff = 0.0;
        for (SmoothingType t : SmoothingType.values())
        {
            SmoothedParameter a = new SmoothedParameter();
            SmoothedParameter b = new SmoothedParameter();
            a.prepare(48000.0, 5.0);
            b.prepare(48000.0, 5.0);
            a.setSmoothingType(t);
            b.setSmoothingType(t);
            a.setTargetValue(0.8);
            b.setTargetValue(0.8);
            double[] block = new double[301];
            for (int chunk = 0; chunk < 8; ++chunk)
            {
                if (chunk == 4)
                {
                    a.setTargetValue(-0.3);
                    b.setTargetValue(-0.3);
                }
                a.processBlock(block);
                for (int i = 0; i < 301; ++i)
                    maxDiff = Math.max(maxDiff, Math.abs(block[i] - b.getNextValue()));
            }
            assertEquals(b.isSmoothing(), a.isSmoothing());
        }
        assertEquals(0.0, maxDiff, 0.0);
    }

    @Test
    @DisplayName("Adaptive ramps last by the size of the step")
    void adaptiveRamp()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(48000.0, 10.0);
        sv.setSmoothingType(SmoothingType.LINEAR);
        sv.setAdaptiveRamp(1.0, 20.0, 150.0);
        assertTrue(sv.hasAdaptiveRamp());

        sv.reset(0.0);
        sv.setTargetValue(1.0);
        assertEquals(960.0, samplesToArrive(sv), 2.0);
        assertEquals(1.0, sv.getCurrentValue(), 0.0);
        sv.setTargetValue(5.0);
        assertEquals(960.0, samplesToArrive(sv), 2.0);
        assertEquals(5.0, sv.getCurrentValue(), 0.0);

        sv.reset(0.0);
        sv.setTargetValue(0.5);
        assertEquals(52.5 * 48.0, samplesToArrive(sv), 2.0);

        sv.reset(0.0);
        sv.setTargetValue(0.001);
        assertEquals((20.0 + 130.0 * 0.999 * 0.999) * 48.0, samplesToArrive(sv), 3.0);
        assertEquals(0.001, sv.getCurrentValue(), 0.0);

        sv.reset(0.0);
        sv.setTargetValue(1.0);
        for (int i = 0; i < 100; ++i) sv.getNextValue();
        final double reached = sv.getCurrentValue();
        assertEquals(100.0 / 960.0, reached, 1e-9);
        sv.setTargetValue(0.0);
        final double next = sv.getNextValue();
        assertTrue(next < reached && next > reached - 1e-3);
        final double closeness = 1.0 - reached;
        assertEquals((20.0 + 130.0 * closeness * closeness) * 48.0, samplesToArrive(sv) + 1.0, 3.0);

        SmoothedParameter a = new SmoothedParameter();
        SmoothedParameter b = new SmoothedParameter();
        for (SmoothedParameter each : new SmoothedParameter[] { a, b })
        {
            each.prepare(48000.0, 10.0);
            each.setSmoothingType(SmoothingType.LINEAR);
            each.setAdaptiveRamp(2.0, 5.0, 60.0);
            each.reset(1.0);
            each.setTargetValue(1.75);
        }
        double[] block = new double[4096];
        a.processBlock(block);
        boolean same = true;
        for (double v : block) same = same && (v == b.getNextValue());
        assertTrue(same);

        sv.setAdaptiveRamp(0.0, 20.0, 150.0);
        assertFalse(sv.hasAdaptiveRamp());
        sv.reset(0.0);
        sv.setTargetValue(0.001);
        assertEquals(480.0, samplesToArrive(sv), 2.0);

        sv.setAdaptiveRamp(1.0, Double.NaN, 150.0);
        assertFalse(sv.hasAdaptiveRamp());
    }

    // -- Further properties -------------------------------------------------------------

    @Test
    @DisplayName("The exponential coefficient is exp(-1 / (fs * ms / 1000))")
    void exponentialCoefficient()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(96000.0, 30.0);
        sv.reset(0.0);
        sv.setTargetValue(1.0);
        final double c = Math.exp(-1.0 / (96000.0 * 30.0 / 1000.0));
        assertEquals(1.0 + c * (0.0 - 1.0), sv.getNextValue(), 0.0);
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give the per-sample values for every curve")
    void blockSplits()
    {
        final int total = 12000;
        for (SmoothingType t : SmoothingType.values())
            for (int block : new int[] { 1, 37, 512, 4096 })
            {
                // The second target lands on the first block start from sample 6000 on.
                final int second = ((6000 + block - 1) / block) * block;
                SmoothedParameter ref = make(t);
                double[] expected = new double[total];
                for (int i = 0; i < total; ++i)
                {
                    retarget(ref, i, second);
                    expected[i] = ref.getNextValue();
                }
                SmoothedParameter sv = make(t);
                double[] out = new double[total];
                for (int at = 0; at < total; at += block)
                {
                    retarget(sv, at, second);
                    sv.processBlock(out, at, Math.min(block, total - at));
                }
                for (int i = 0; i < total; ++i) assertEquals(expected[i], out[i], 0.0, t + " block " + block + " at " + i);
                assertEquals(ref.isSmoothing(), sv.isSmoothing());
                assertEquals(ref.getCurrentValue(), sv.getCurrentValue(), 0.0);
            }
    }

    private static SmoothedParameter make(SmoothingType t)
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(48000.0, 15.0);
        sv.setSmoothingType(t);
        sv.reset(0.2);
        return sv;
    }

    private static void retarget(SmoothedParameter sv, int i, int second)
    {
        if (i == 0) sv.setTargetValue(3.0);
        else if (i == second) sv.setTargetValue(-1.5);
    }

    @Test
    @DisplayName("reset() parks value and target; skip() lands; isSmoothing() follows")
    void resetAndSkip()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(48000.0, 50.0);
        sv.reset(0.25);
        assertFalse(sv.isSmoothing());
        assertEquals(0.25, sv.getNextValue(), 0.0);
        sv.setTargetValue(0.75);
        assertTrue(sv.isSmoothing());
        sv.skip();
        assertFalse(sv.isSmoothing());
        assertEquals(0.75, sv.getCurrentValue(), 0.0);
        sv.reset();
        assertEquals(0.0, sv.getCurrentValue(), 0.0);
        assertEquals(0.0, sv.getTargetValue(), 0.0);
        sv.setSmoothingType(null);
        assertEquals(SmoothingType.EXPONENTIAL, sv.getSmoothingType());
    }

    @Test
    @DisplayName("prepare() floors the rate at 1 Hz and a NaN ramp at 0 (no smoothing)")
    void prepareGuards()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(48000.0, Double.NaN);
        sv.setTargetValue(1.0);
        assertEquals(1.0, sv.getNextValue(), 0.0);
        sv.prepare(Double.NaN, 1000.0);   // 1 Hz: 1 sample of ramp
        sv.setSmoothingType(SmoothingType.LINEAR);
        sv.reset(0.0);
        sv.setTargetValue(1.0);
        assertEquals(1.0, sv.getNextValue(), 0.0);
    }

    @Test
    @DisplayName("Log-gain ramps as TubePreamp runs them settle and report it")
    void logGainRamp()
    {
        SmoothedParameter sv = new SmoothedParameter();
        sv.prepare(96000.0, 30.0);
        sv.reset(Math.log(0.5));
        sv.setTargetValue(Math.log(2.0));
        double[] ramp = new double[512];
        int blocks = 0;
        while (sv.isSmoothing() && blocks < 1000)
        {
            sv.processBlock(ramp);
            ++blocks;
        }
        assertFalse(sv.isSmoothing());
        assertEquals(Math.log(2.0), ramp[511], 0.0);
        // 16.1 time constants of 2880 samples (relative snap at max(1, |target|)).
        assertTrue(blocks * 512 >= 2880 * 15 && blocks * 512 <= 2880 * 18, "blocks " + blocks);
    }
}
