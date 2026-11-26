package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link BiquadCascade}: the block path against the per-sample path, and {@code copyFrom}. */
class BiquadCascadeGroupTest
{
    private static BiquadCoeffs randomSection(Random rng)
    {
        final double fs = 48000.0;
        final double f = 30.0 * Math.pow(600.0, rng.nextDouble());
        final double q = 0.3 + 3.0 * rng.nextDouble();
        switch (rng.nextInt(4))
        {
            case 0: return BiquadDesign.makeLowPass(fs, f, q);
            case 1: return BiquadDesign.makeHighPass(fs, f, q);
            case 2: return BiquadDesign.makeAllPass(fs, f, q);
            default: return BiquadDesign.makePeak(fs, f, q, 12.0 * rng.nextDouble() - 6.0);
        }
    }

    @Test
    @DisplayName("Every section count runs its blocks to the bit of the per-sample path, on two channels")
    void blockPathEqualsSamplePath()
    {
        final Random rng = new Random(11);
        final int n = 3000;
        for (int sections = 1; sections <= 8; ++sections)
        {
            final BiquadCascade block = new BiquadCascade(8, 2), sample = new BiquadCascade(8, 2);
            block.setNumSections(sections);
            sample.setNumSections(sections);
            for (int s = 0; s < sections; ++s)
            {
                final BiquadCoeffs c = randomSection(rng);
                block.setCoeffs(s, c);
                sample.setCoeffs(s, c);
            }
            if (sections == 6) // a section out of the path splits the groups differently
            {
                block.setSectionEnabled(2, false);
                sample.setSectionEnabled(2, false);
            }
            final double[][] x = new double[2][n];
            for (final double[] ch : x) for (int i = 0; i < n; ++i) ch[i] = rng.nextDouble() - 0.5;
            final double[][] y = { x[0].clone(), x[1].clone() };
            for (int off = 0; off < n;)
            {
                final int m = Math.min(n - off, 1 + rng.nextInt(200));
                block.process(y, 2, off, m);
                off += m;
            }
            for (int i = 0; i < n; ++i)
                for (int ch = 0; ch < 2; ++ch)
                    assertEquals(sample.processSample(x[ch][i], ch), y[ch][i], 0.0,
                                 sections + " sections, channel " + ch + ", sample " + i);
        }
    }

    @Test
    @DisplayName("copyFrom() carries the coefficients, the ramps and the memory")
    void copyFromCarriesEverything()
    {
        final Random rng = new Random(5);
        final BiquadCascade a = new BiquadCascade(4, 2);
        a.setNumSections(3);
        for (int s = 0; s < 3; ++s) a.setCoeffs(s, randomSection(rng));
        a.rampCoeffs(1, randomSection(rng), 700);
        final double[][] warm = new double[2][500];
        for (final double[] ch : warm) for (int i = 0; i < 500; ++i) ch[i] = rng.nextDouble() - 0.5;
        a.process(warm, 500);
        final BiquadCascade b = new BiquadCascade(4, 2);
        b.copyFrom(a);
        final double[][] x = new double[2][1000];
        for (final double[] ch : x) for (int i = 0; i < 1000; ++i) ch[i] = rng.nextDouble() - 0.5;
        final double[][] ya = { x[0].clone(), x[1].clone() }, yb = { x[0].clone(), x[1].clone() };
        a.process(ya, 1000);
        b.process(yb, 1000);
        for (int ch = 0; ch < 2; ++ch)
            for (int i = 0; i < 1000; ++i) assertEquals(ya[ch][i], yb[ch][i], 0.0);

        final NormalizedLadder la = new NormalizedLadder(2), lb = new NormalizedLadder(2);
        la.setCoeffs(randomSection(rng));
        la.rampCoeffs(randomSection(rng), 300);
        final double[] w = new double[100];
        for (int i = 0; i < 100; ++i) w[i] = rng.nextDouble() - 0.5;
        la.processSegment(w, 100, 0);
        la.advanceRamp(100);
        lb.copyFrom(la);
        for (int i = 0; i < 500; ++i)
        {
            final double v = rng.nextDouble() - 0.5;
            assertEquals(la.processSample(v, 0), lb.processSample(v, 0), 0.0);
            la.advanceRamp(1);
            lb.advanceRamp(1);
        }
    }
}
