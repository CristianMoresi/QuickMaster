package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Hysteresis}, the Jiles-Atherton core. */
class HysteresisTest
{
    private static final double MS = 3.5e5;
    private static final double A = 2.2e4;

    /** The last of 12 cycles of a 100 Hz sine field at 96 kHz. */
    private static double[][] runLoop(double k, double c, double amp)
    {
        Hysteresis hyst = new Hysteresis();
        hyst.prepare(96000.0);
        hyst.setParameters(3.5e5, 2.2e4, 1.6e-3, k, c);
        final int perCycle = 960;
        double[] h = new double[perCycle];
        double[] m = new double[perCycle];
        for (int cyc = 0; cyc < 12; ++cyc)
            for (int i = 0; i < perCycle; ++i)
            {
                final double field = amp * Math.sin(2.0 * 3.14159265358979 * i / perCycle);
                final double mag = hyst.processSample(field);
                if (cyc == 11)
                {
                    h[i] = field;
                    m[i] = mag;
                }
            }
        return new double[][] { h, m };
    }

    private static double loopArea(double[][] loop)
    {
        double[] h = loop[0];
        double[] m = loop[1];
        double area = 0.0;
        final int n = h.length;
        for (int i = 0; i < n; ++i)
        {
            final int j = (i + 1) % n;
            area += 0.5 * (m[i] + m[j]) * (h[j] - h[i]);
        }
        return Math.abs(area);
    }

    // -- Ports of the C++ assertions -------------------------------------------

    @Test
    @DisplayName("The steady-state B-H loop is odd-symmetric and bounded")
    void loopHasOddSymmetry()
    {
        double[][] loop = runLoop(2.7e4, 0.17, 3.0 * 2.2e4);
        double[] m = loop[1];
        double worst = 0.0;
        final int n = m.length;
        for (int i = 0; i < n / 2; ++i)
            worst = Math.max(worst, Math.abs(m[i] + m[i + n / 2]) / 3.5e5);
        assertTrue(worst < 0.005, "asymmetry " + worst);
        for (double v : m)
            assertTrue(Double.isFinite(v) && Math.abs(v) <= 3.5e5);
    }

    @Test
    @DisplayName("Loop area grows with k and shrinks as c tends to 1")
    void loopAreaFollowsKAndC()
    {
        final double amp = 3.0 * 2.2e4;
        assertTrue(loopArea(runLoop(1.0e4, 0.17, amp)) < loopArea(runLoop(2.7e4, 0.17, amp)));
        assertTrue(loopArea(runLoop(2.7e4, 0.90, amp)) < loopArea(runLoop(2.7e4, 0.17, amp)));
    }

    @Test
    @DisplayName("Magnetic memory remains after the field is removed")
    void remanenceAfterFieldRemoval()
    {
        Hysteresis hyst = new Hysteresis();
        hyst.prepare(96000.0);
        hyst.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
        final int n = 9600;
        double m = 0.0;
        for (int i = 0; i < n; ++i)
            m = hyst.processSample(3.0 * 2.2e4 * i / n);
        for (int i = 0; i < n; ++i)
            m = hyst.processSample(3.0 * 2.2e4 * (1.0 - (double) i / n));
        for (int i = 0; i < 4800; ++i)
            m = hyst.processSample(0.0);
        assertTrue(m / 3.5e5 > 0.02, "remanence " + m / 3.5e5);
    }

    @Test
    @DisplayName("Parameter sets violating the JA stability condition stay finite and bounded")
    void survivesUnstableParameterSets()
    {
        Hysteresis hyst = new Hysteresis();
        hyst.prepare(48000.0);
        hyst.setParameters(3.5e5, 3.3e2, 1.0, 2.7e4, 0.9);
        boolean finite = true;
        for (int i = 0; i < 48000; ++i)
        {
            final double h = 5.0 * 2.2e4 * Math.sin(2.0 * 3.14159265358979 * 137.0 * i / 48000.0);
            final double m = hyst.processSample(h);
            if (!Double.isFinite(m) || Math.abs(m) > 3.5e5) { finite = false; break; }
        }
        assertTrue(finite);
    }

    @Test
    @DisplayName("The small-signal susceptibility matches the measured slope within 5%")
    void smallSignalSusceptibilityMatchesMeasurement()
    {
        Hysteresis hyst = new Hysteresis();
        hyst.prepare(96000.0);
        hyst.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
        final double chi = hyst.getSmallSignalSusceptibility();
        final double amp = 1e-3 * 2.2e4;
        double peak = 0.0;
        for (int i = 0; i < 9600; ++i)
        {
            final double h = amp * Math.sin(2.0 * 3.14159265358979 * 100.0 * i / 96000.0);
            final double m = hyst.processSample(h);
            if (i > 4800) peak = Math.max(peak, Math.abs(m));
        }
        assertEquals(chi, peak / amp, 0.05 * chi);
        // The documented formula: c chi_an / (1 - c alpha chi_an), chi_an = Ms / (3a).
        final double chiAn = MS / (3.0 * A);
        assertEquals(0.17 * chiAn / (1.0 - 0.17 * 1.6e-3 * chiAn), chi, 0.0);
    }

    private static double[] renderTolerance(double... tolerances)
    {
        final double rate = 384000.0;
        Hysteresis h = new Hysteresis();
        h.prepare(rate);
        h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
        for (double t : tolerances) h.setTolerance(t);
        double[] out = new double[96000];
        for (int i = 0; i < out.length; ++i)
        {
            final double t = (double) i / rate;
            out[i] = h.processSample(2.2e4 * (1.5 * Math.sin(6.283185307179586476925 * 997.0 * t)
                                              + 0.4 * Math.sin(6.283185307179586476925 * 7001.0 * t)));
        }
        return out;
    }

    @Test
    @DisplayName("setTolerance keeps the default bit for bit, ignores invalid values, and 1e-6 stays under -130 dB")
    void toleranceKeepsTheDefault()
    {
        double[] exact = renderTolerance();
        assertArrayEquals(exact, renderTolerance(1e-9));
        assertArrayEquals(exact, renderTolerance(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY));
        double[] loose = renderTolerance(1e-6);
        double error = 0.0;
        double power = 0.0;
        for (int i = 0; i < exact.length; ++i)
        {
            error += (loose[i] - exact[i]) * (loose[i] - exact[i]);
            power += exact[i] * exact[i];
        }
        final double apart = 10.0 * Math.log10(error / power + 1e-30);
        assertTrue(apart < -130.0, "a tolerance of 1e-6 stands " + apart + " dB from the exact one");
    }

    @Test
    @DisplayName("Non-finite fields and parameters cannot poison the core")
    void survivesNonFiniteInput()
    {
        Hysteresis h = new Hysteresis();
        h.prepare(192000.0);
        h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
        h.prepare(Double.NaN);
        h.prepare(Double.POSITIVE_INFINITY);
        h.setParameters(Double.NaN, 2.2e4, 1.6e-3, 2.7e4, 0.17);
        h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, Double.NaN);
        assertEquals(3.5e5, h.getSaturation(), 0.0);
        for (int i = 0; i < 500; ++i) h.processSample(2.0e4 * Math.sin(0.01 * i));
        h.processSample(Double.NaN);
        h.processSample(Double.POSITIVE_INFINITY);
        boolean finite = true;
        for (int i = 0; i < 4000; ++i)
        {
            final double m = h.processSample(2.0e4 * Math.sin(0.01 * i));
            if (!Double.isFinite(m) || Math.abs(m) > h.getSaturation() * 1.0000001) finite = false;
        }
        assertTrue(finite);
    }

    @Test
    @DisplayName("A one-sample field tick keeps the remanence (damped alpha transform)")
    void tickKeepsRemanence()
    {
        Hysteresis h = new Hysteresis();
        h.prepare(96000.0);
        final int n = 9600;
        double m = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double e = Math.sin(Math.PI * i / n);
            m = h.processSample(8.0 * A * e * e);
        }
        final double remanence = m;
        h.processSample(0.001 * A);
        for (int i = 0; i < 4800; ++i) m = h.processSample(0.0);
        assertTrue(Math.abs(remanence) > 1e4);
        assertTrue(Math.abs(m / remanence - 1.0) < 0.05, "kept " + m / remanence);
    }

    // -- Documented properties --------------------------------------------------

    @Test
    @DisplayName("A non-finite field returns the last magnetization and leaves the state untouched")
    void nonFiniteFieldHoldsTheState()
    {
        Hysteresis a = new Hysteresis();
        Hysteresis b = new Hysteresis();
        a.prepare(96000.0);
        b.prepare(96000.0);
        double last = 0.0;
        for (int i = 0; i < 2000; ++i)
        {
            final double h = 2.0 * A * Math.sin(0.003 * i);
            last = a.processSample(h);
            b.processSample(h);
        }
        assertEquals(last, a.processSample(Double.NaN), 0.0);
        assertEquals(last, a.processSample(Double.NEGATIVE_INFINITY), 0.0);
        for (int i = 2000; i < 4000; ++i)
        {
            final double h = 2.0 * A * Math.sin(0.003 * i);
            assertEquals(b.processSample(h), a.processSample(h), 0.0);
        }
    }

    @Test
    @DisplayName("reset() equals a fresh instance, and prepare() resets")
    void resetEqualsFresh()
    {
        Hysteresis used = new Hysteresis();
        used.prepare(96000.0);
        used.setTolerance(1e-6);
        used.setPredictorPeriod(4);
        for (int i = 0; i < 5000; ++i) used.processSample(3.0 * A * Math.sin(0.01 * i));
        used.reset();
        Hysteresis fresh = new Hysteresis();
        fresh.prepare(96000.0);
        fresh.setTolerance(1e-6);
        fresh.setPredictorPeriod(4);
        for (int i = 0; i < 5000; ++i)
        {
            final double h = 2.0 * A * Math.sin(0.007 * i) + ((i & 3) == 1 ? A : (i & 3) == 3 ? -A : 0.0);
            assertEquals(fresh.processSample(h), used.processSample(h), 0.0);
        }
        used.prepare(96000.0);
        Hysteresis again = new Hysteresis();
        again.prepare(96000.0);
        again.setTolerance(1e-6);
        again.setPredictorPeriod(4);
        for (int i = 0; i < 1000; ++i)
        {
            final double h = A * Math.sin(0.02 * i);
            assertEquals(again.processSample(h), used.processSample(h), 0.0);
        }
    }

    @Test
    @DisplayName("Parameters clamp like the C++ (Ms, a, k at least 1, alpha at least 0, c in [0, 0.999])")
    void parameterClamps()
    {
        Hysteresis h = new Hysteresis();
        assertEquals(3.5e5, h.getSaturation(), 0.0);
        h.setParameters(-5.0, 0.5, -1.0, 0.0, 2.0);
        assertEquals(1.0, h.getSaturation(), 0.0);
        // chi_an = 1 / 3, c = 0.999, alpha = 0: c chi_an.
        assertEquals(0.999 / 3.0, h.getSmallSignalSusceptibility(), 1e-15);
        h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, -1.0);
        assertEquals(0.0, h.getSmallSignalSusceptibility(), 0.0);
    }

    @Test
    @DisplayName("setPredictorPeriod accepts 0 to MAX_PERIOD and ignores the rest")
    void predictorPeriodRange()
    {
        Hysteresis h = new Hysteresis();
        assertEquals(0, h.getPredictorPeriod());
        h.setPredictorPeriod(4);
        assertEquals(4, h.getPredictorPeriod());
        h.setPredictorPeriod(Hysteresis.MAX_PERIOD + 1);
        h.setPredictorPeriod(-1);
        assertEquals(4, h.getPredictorPeriod());
        h.setPredictorPeriod(Hysteresis.MAX_PERIOD);
        assertEquals(8, h.getPredictorPeriod());
        h.setPredictorPeriod(0);
        assertEquals(0, h.getPredictorPeriod());
    }

    /** A programme beside a carrier of period 4 (0, +b, 0, -b), as the push-pull tape core sees it. */
    private static double[] renderCarrier(double tolerance, int period)
    {
        final double rate = 192000.0;
        Hysteresis h = new Hysteresis();
        h.prepare(rate);
        h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
        h.setTolerance(tolerance);
        h.setPredictorPeriod(period);
        final double[] carrier = { 0.0, 3.0 * A, 0.0, -3.0 * A };
        double[] out = new double[48000];
        for (int i = 0; i < out.length; ++i)
        {
            final double programme = 0.8 * A * Math.sin(2.0 * Math.PI * 1000.0 * i / rate);
            out[i] = h.processSample(programme + carrier[i & 3]);
        }
        return out;
    }

    @Test
    @DisplayName("The periodic predictor gives the same answer within the tolerance")
    void predictorKeepsTheAnswer()
    {
        double[] exact = renderCarrier(1e-9, 0);
        double[] predicted = renderCarrier(1e-6, 4);
        double worst = 0.0;
        double error = 0.0;
        double power = 0.0;
        for (int i = 0; i < exact.length; ++i)
        {
            final double d = predicted[i] - exact[i];
            worst = Math.max(worst, Math.abs(d));
            error += d * d;
            power += exact[i] * exact[i];
        }
        final double apart = 10.0 * Math.log10(error / power + 1e-30);
        assertTrue(apart < -130.0, "predictor stands " + apart + " dB from the exact one");
        assertTrue(worst < 1e-6 * MS, "worst sample " + worst);
    }

    /** Solves M = Ms L((H + alpha M) / a) by bisection. */
    private static double anhysteretic(double h, double alpha)
    {
        double lo = -MS;
        double hi = MS;
        for (int it = 0; it < 200; ++it)
        {
            final double mid = 0.5 * (lo + hi);
            final double q = (h + alpha * mid) / A;
            final double l = Math.abs(q) < 1e-6 ? q / 3.0 : 1.0 / Math.tanh(q) - 1.0 / q;
            if (MS * l - mid > 0.0) lo = mid; else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    @Test
    @DisplayName("As c tends to 1 the magnetization follows the anhysteretic curve")
    void anhystereticLimit()
    {
        Hysteresis h = new Hysteresis();
        h.prepare(48000.0);
        h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.999);
        final int n = 48000;
        double worst = 0.0;
        for (int i = 0; i <= n; ++i)
        {
            final double field = 4.0 * A * Math.sin(Math.PI * i / n);   // up to 4a and back
            final double m = h.processSample(field);
            worst = Math.max(worst, Math.abs(m - anhysteretic(field, 1.6e-3)) / MS);
        }
        assertTrue(worst < 0.002, "distance to the anhysteretic curve " + worst);
    }

    @Test
    @DisplayName("Coercivity: the descending branch crosses M = 0 at negative H, the ascending one at positive H")
    void coercivitySign()
    {
        Hysteresis h = new Hysteresis();
        h.prepare(96000.0);
        h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
        final int perCycle = 9600;   // 10 Hz
        double prevM = 0.0;
        double prevH = 0.0;
        double downCross = Double.NaN;
        double upCross = Double.NaN;
        for (int cyc = 0; cyc < 4; ++cyc)
            for (int i = 0; i < perCycle; ++i)
            {
                final double field = 3.0 * A * Math.sin(2.0 * Math.PI * i / perCycle);
                final double m = h.processSample(field);
                if (cyc == 3 && i > 0)
                {
                    if (prevM > 0.0 && m <= 0.0) downCross = prevH + (field - prevH) * prevM / (prevM - m);
                    if (prevM < 0.0 && m >= 0.0) upCross = prevH + (field - prevH) * (-prevM) / (m - prevM);
                }
                prevM = m;
                prevH = field;
            }
        assertTrue(downCross < 0.0, "descending coercive field " + downCross);
        assertTrue(upCross > 0.0, "ascending coercive field " + upCross);
        assertEquals(1.0, -downCross / upCross, 0.05);
    }

    /** Remanence left by a positive or negative saturation, then a decaying AC bias. */
    private static double[] magnetizeThenErase(double sign)
    {
        Hysteresis h = new Hysteresis();
        h.prepare(96000.0);
        h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
        final int n = 9600;
        double m = 0.0;
        for (int i = 0; i < n; ++i)
        {
            final double e = Math.sin(Math.PI * i / n);
            m = h.processSample(sign * 4.0 * A * e * e);
        }
        for (int i = 0; i < 2400; ++i) m = h.processSample(0.0);
        final double remanence = m;
        // AC bias of 4a at 1 kHz, faded out linearly over 0.5 s.
        final int fade = 48000;
        for (int i = 0; i < fade; ++i)
        {
            final double env = 1.0 - (double) i / fade;
            m = h.processSample(4.0 * A * env * Math.sin(2.0 * Math.PI * 1000.0 * i / 96000.0));
        }
        for (int i = 0; i < 2400; ++i) m = h.processSample(0.0);
        return new double[] { remanence, m };
    }

    @Test
    @DisplayName("A decaying AC bias erases the branch memory")
    void acBiasErasesMemory()
    {
        double[] up = magnetizeThenErase(1.0);
        double[] down = magnetizeThenErase(-1.0);
        assertTrue(up[0] > 0.02 * MS && down[0] < -0.02 * MS);
        assertTrue(Math.abs(up[1]) < 0.01 * Math.abs(up[0]), "left " + up[1] / up[0]);
        assertTrue(Math.abs(down[1]) < 0.01 * Math.abs(down[0]), "left " + down[1] / down[0]);
        // The two histories end in the same place: the memory of the sign is gone.
        assertTrue(Math.abs(up[1] - down[1]) < 1e-3 * Math.abs(up[0] - down[0]));
    }

    @Test
    @DisplayName("Saturation: |M| never exceeds Ms at extreme drive")
    void saturationBound()
    {
        Hysteresis h = new Hysteresis();
        h.prepare(44100.0);
        for (int i = 0; i < 44100; ++i)
        {
            final double m = h.processSample(1e3 * A * Math.sin(2.0 * Math.PI * 3000.0 * i / 44100.0));
            assertTrue(Double.isFinite(m) && Math.abs(m) <= MS);
        }
    }
}
