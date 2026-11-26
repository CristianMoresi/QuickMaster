package com.dspark.core;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order-N normalized ladder {@link NormalizedLadderN} and the polynomial bilinear transform. */
class NormalizedLadderNTest
{
    private static final double PI = 3.14159265358979323846;

    /** The digital polynomials of a fifth-order Butterworth low-pass at hz. */
    private static void butterworth5(double hz, double fs, double[] b, double[] a)
    {
        final double wc = 2.0 * PI * hz;
        final double[] proto = { 1.0, 3.2360679774997898, 5.2360679774997898, 5.2360679774997898, 3.2360679774997898, 1.0 };
        final double[] den = new double[6], num = { 1.0, 0.0, 0.0, 0.0, 0.0, 0.0 };
        for (int j = 0; j < 6; ++j) den[j] = proto[j] / Math.pow(wc, j);
        assertTrue(FilterDesign.bilinearPolynomial(num, den, 5, fs, hz, b, a));
    }

    @Test
    @DisplayName("A fifth-order network as one ladder: the direct form's samples, the prewarped response, bounded under sweeps")
    void fifthOrderNetworkFromItsPolynomials()
    {
        final double fs = 48000.0;
        final double[] b = new double[6], a = new double[6];
        butterworth5(2000.0, fs, b, a);
        final NormalizedLadderN ladder = new NormalizedLadderN(5, 1);
        assertTrue(ladder.setCoeffs(b, a));

        final Random rng = new Random(5);
        final double[] z = new double[5];
        double err = 0.0, sig = 0.0;
        for (int i = 0; i < 48000; ++i)
        {
            final double x = 0.3 * rng.nextGaussian();
            final double y = b[0] * x + z[0];
            for (int k = 0; k < 4; ++k) z[k] = b[k + 1] * x - a[k + 1] * y + z[k + 1];
            z[4] = b[5] * x - a[5] * y;
            final double l = ladder.processSample(x, 0);
            err += (l - y) * (l - y);
            sig += y * y;
        }
        assertTrue(10.0 * Math.log10(err / sig) < -120.0, "ratio " + 10.0 * Math.log10(err / sig));

        for (double hz : new double[] { 500.0, 2000.0, 6000.0 })
        {
            final NormalizedLadderN l = new NormalizedLadderN(5, 1);
            l.setCoeffs(b, a);
            double e = 0.0;
            for (int i = 0; i < 96000; ++i)
            {
                final double y = l.processSample(Math.sin(2.0 * PI * hz * i / fs), 0);
                if (i >= 48000) e += y * y;
            }
            final double got = 10.0 * Math.log10(e / 24000.0);
            final double analogHz = 2000.0 * Math.tan(PI * hz / fs) / Math.tan(PI * 2000.0 / fs);
            final double want = -10.0 * Math.log10(1.0 + Math.pow(analogHz / 2000.0, 10.0));
            assertEquals(want, got, 0.01);
        }

        final NormalizedLadderN swept = new NormalizedLadderN(5, 1);
        swept.setCoeffs(b, a);
        double worst = 0.0;
        final double[] bb = new double[6], aa = new double[6];
        for (int i = 0; i < 96000; ++i)
        {
            if (i % 8 == 0)
            {
                final double hz = 300.0 * Math.pow(20.0, 0.5 + 0.5 * Math.sin(2.0 * PI * 30.0 * i / fs));
                butterworth5(hz, fs, bb, aa);
                assertTrue(swept.rampCoeffs(bb, aa, 8));
            }
            final double y = swept.processSample(rng.nextGaussian() * 0.3, 0);
            swept.advanceRamp(1);
            worst = Math.max(worst, Math.abs(y));
        }
        assertTrue(Double.isFinite(worst));
        assertTrue(worst < 3.0);

        // A block of three channels (a pair and one alone) with a ramp landing inside it.
        butterworth5(700.0, fs, bb, aa);
        final NormalizedLadderN block = new NormalizedLadderN(5, 3), alone = new NormalizedLadderN(5, 3);
        block.setCoeffs(b, a);
        alone.setCoeffs(b, a);
        final double[][] data = new double[3][512];
        for (double[] d : data) for (int i = 0; i < 512; ++i) d[i] = 0.3 * rng.nextGaussian();
        final double[][] want = { data[0].clone(), data[1].clone(), data[2].clone() };
        assertTrue(block.rampCoeffs(bb, aa, 300));
        block.process(data, 512);
        assertFalse(block.isRamping());
        double gap = 0.0;
        for (int ch = 0; ch < 3; ++ch)
        {
            alone.setCoeffs(b, a);
            assertTrue(alone.rampCoeffs(bb, aa, 300));
            for (int i = 0; i < 512; ++i)
            {
                alone.advanceRamp(1);
                want[ch][i] = alone.processSample(want[ch][i], ch);
                gap = Math.max(gap, Math.abs(want[ch][i] - data[ch][i]));
            }
        }
        assertTrue(gap < 1e-12, "gap " + gap);

        final double[] bad = { 1.0, 0.0, 0.0, 0.0, 0.0, 1.5 };
        assertFalse(ladder.setCoeffs(b, bad));
        assertFalse(ladder.rampCoeffs(b, bad, 10));
        assertFalse(ladder.setCoeffs(b, new double[] { 0.0, 1.0, 0.0, 0.0, 0.0, 0.0 }));
    }

    @Test
    @DisplayName("Order 2 equals the second-order ladder and the biquad; the design is the static one")
    void orderTwoIsTheBiquadLadder()
    {
        final BiquadCoeffs c = BiquadDesign.makePeak(48000.0, 1500.0, 2.0, 7.0);
        final double[] b = { c.b0, c.b1, c.b2 }, a = { 1.0, c.a1, c.a2 };
        final NormalizedLadderN n = new NormalizedLadderN(2, 1);
        assertTrue(n.setCoeffs(b, a));
        final NormalizedLadderN.Coeffs designed = new NormalizedLadderN.Coeffs(2);
        assertTrue(NormalizedLadderN.design(2, b, a, designed));
        final NormalizedLadderN.Coeffs l = n.getLadder();
        for (int m = 0; m <= 2; ++m)
        {
            assertEquals(designed.c[m], l.c[m], 0.0);
            assertEquals(designed.s[m], l.s[m], 0.0);
            assertEquals(designed.v[m], l.v[m], 0.0);
        }
        final BiquadFilter reference = new BiquadFilter(1);
        reference.setCoeffsNow(c);
        final PortSignals.Lcg rng = new PortSignals.Lcg(9);
        double worst = 0.0;
        for (int i = 0; i < 5000; ++i)
        {
            final double x = 0.5 * rng.next();
            worst = Math.max(worst, Math.abs(n.processSample(x, 0) - reference.processSampleCore(x, 0)));
        }
        assertTrue(worst < 1e-12, "worst " + worst);

        // A new filter outputs silence until a design is set.
        assertEquals(0.0, new NormalizedLadderN(3, 1).processSample(1.0, 0), 0.0);
    }

    @Test
    @DisplayName("bilinearPolynomial of order 2 is the bilinear transform of the section; the corner keeps its place")
    void bilinearPolynomialMatchesTheSectionTransform()
    {
        final double fs = 44100.0;
        final AnalogSection h = AnalogSection.peak(9000.0, 1.3, -7.0);
        final BiquadCoeffs c = FilterDesign.bilinear(h, fs, 9000.0);
        final double[] b = new double[3], a = new double[3];
        assertTrue(FilterDesign.bilinearPolynomial(new double[] { h.b0, h.b1, h.b2 }, new double[] { h.a0, h.a1, h.a2 },
                                                   2, fs, 9000.0, b, a));
        assertEquals(1.0, a[0], 0.0);
        assertEquals(c.b0, b[0], 1e-12);
        assertEquals(c.b1, b[1], 1e-12);
        assertEquals(c.b2, b[2], 1e-12);
        assertEquals(c.a1, a[1], 1e-12);
        assertEquals(c.a2, a[2], 1e-12);
        assertFalse(FilterDesign.bilinearPolynomial(new double[18], new double[18], 17, fs, 0.0, b, a));
        assertFalse(FilterDesign.bilinearPolynomial(new double[] { 1.0, 0.0 }, new double[] { 0.0, 0.0 }, 1, fs, 0.0, b, a));
    }

    @Test
    @DisplayName("Block sizes give identical output; a non-finite sample clears the memory of its pair")
    void blockSizesAndRecovery()
    {
        final double fs = 48000.0;
        final double[] b = new double[6], a = new double[6], bb = new double[6], aa = new double[6];
        butterworth5(1200.0, fs, b, a);
        butterworth5(5000.0, fs, bb, aa);
        final double[] l = PortSignals.sine(5000, 440.0, fs, 0.5), r = PortSignals.sine(5000, 3000.0, fs, 0.5);
        double[][] reference = null;
        for (int blockSize : new int[] { 1, 37, 512, 4096 })
        {
            final NormalizedLadderN f = new NormalizedLadderN(5, 2);
            f.setCoeffs(b, a);
            f.rampCoeffs(bb, aa, 2000);
            final double[][] buf = { l.clone(), r.clone() };
            for (int pos = 0; pos < l.length; pos += blockSize)
                f.process(buf, 2, pos, Math.min(blockSize, l.length - pos));
            if (reference == null)
            {
                reference = buf;
                continue;
            }
            for (int ch = 0; ch < 2; ++ch)
                for (int i = 0; i < l.length; ++i) assertEquals(reference[ch][i], buf[ch][i], 0.0);
        }

        final NormalizedLadderN f = new NormalizedLadderN(5, 3);
        f.setCoeffs(b, a);
        final double[][] buf = { l.clone(), r.clone(), l.clone() };
        buf[0][100] = Double.NaN;
        f.process(buf, 512);
        // The pair (0, 1) is cleared; channel 2 keeps its memory.
        assertEquals(0.0, f.getStateEnergy(0), 0.0);
        assertEquals(0.0, f.getStateEnergy(1), 0.0);
        assertTrue(f.getStateEnergy(2) > 0.0);
        f.process(buf, 512, 512);
        for (double[] ch : buf) for (int i = 512; i < 1024; ++i) assertTrue(Double.isFinite(ch[i]));
        f.reset();
        assertEquals(0.0, f.getStateEnergy(2), 0.0);
    }
}
