package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The second-order normalized ladder {@link NormalizedLadder}. */
class NormalizedLadderTest
{
    private static final double RATE = 48000.0;

    @Test
    @DisplayName("The ladder realises every biquad: rotations, round trip and the direct form's samples")
    void realisesEveryBiquad()
    {
        final BiquadCoeffs[] sets = {
            BiquadDesign.makeLowPass(RATE, 1000.0, 0.707),
            BiquadDesign.makeLowPass(RATE, 80.0, 12.0),
            BiquadDesign.makeHighPass(RATE, 40.0, 0.9),
            BiquadDesign.makeBandPass(RATE, 3000.0, 5.0),
            BiquadDesign.makeNotch(RATE, 1000.0, 8.0),
            BiquadDesign.makeAllPass(RATE, 500.0, 0.7),
            BiquadDesign.makePeak(RATE, 1000.0, 4.0, 12.0),
            BiquadDesign.makePeak(RATE, 9000.0, 0.5, -18.0),
            BiquadDesign.makeLowShelf(RATE, 200.0, 6.0),
            BiquadDesign.makeHighShelf(RATE, 8000.0, -9.0),
            BiquadCoeffs.IDENTITY,
        };
        for (BiquadCoeffs c : sets)
        {
            final NormalizedLadder.Coeffs ladder = NormalizedLadder.fromBiquad(c);
            assertEquals(1.0, ladder.c1 * ladder.c1 + ladder.s1 * ladder.s1, 1e-14);
            assertEquals(1.0, ladder.c2 * ladder.c2 + ladder.s2 * ladder.s2, 1e-14);
            final BiquadCoeffs back = NormalizedLadder.toBiquad(ladder);
            assertEquals(c.b0, back.b0, 1e-12);
            assertEquals(c.b1, back.b1, 1e-12);
            assertEquals(c.b2, back.b2, 1e-12);
            assertEquals(c.a1, back.a1, 1e-12);
            assertEquals(c.a2, back.a2, 1e-12);

            final NormalizedLadder filter = new NormalizedLadder(1);
            filter.setCoeffs(c);
            final BiquadFilter reference = new BiquadFilter(1);
            reference.setCoeffsNow(c);
            final PortSignals.Lcg seed = new PortSignals.Lcg(4242);
            double worst = 0.0, peak = 0.0;
            for (int i = 0; i < 6000; ++i)
            {
                final double x = 0.5 * seed.next();
                final double want = reference.processSampleCore(x, 0);
                worst = Math.max(worst, Math.abs(filter.processSample(x, 0) - want));
                peak = Math.max(peak, Math.abs(want));
            }
            assertTrue(peak > 0.01);
            assertTrue(worst < 1e-9, "worst " + worst);
        }

        // A set with poles outside the circle is brought inside: the section stays bounded.
        final BiquadCoeffs wild = new BiquadCoeffs(1.0, 0.0, 0.0, -2.5, 1.7);
        final NormalizedLadder safe = new NormalizedLadder(1);
        safe.setCoeffs(wild);
        double largest = 0.0;
        final PortSignals.Lcg seed = new PortSignals.Lcg(1);
        for (int i = 0; i < 20000; ++i)
            largest = Math.max(largest, Math.abs(safe.processSample(i < 100 ? seed.next() : 0.0, 0)));
        assertTrue(Double.isFinite(largest));
        assertTrue(safe.getStateEnergy(0) < 1e6);
    }

    @Test
    @DisplayName("The ladder never adds energy to its memory, under jumps or along a ramp")
    void neverAddsEnergyToItsMemory()
    {
        final NormalizedLadder filter = new NormalizedLadder(1);
        filter.setCoeffs(BiquadDesign.makeLowPass(RATE, 300.0, 20.0));
        final PortSignals.Lcg seed = new PortSignals.Lcg(31);
        for (int i = 0; i < 4000; ++i) filter.processSample(0.5 * seed.next(), 0);
        double energy = filter.getStateEnergy(0);
        assertTrue(energy > 1e-3);

        boolean rose = false;
        for (int i = 0; i < 20000; ++i)
        {
            final double radius = 0.5 + 0.4999 * (0.5 * seed.next() + 0.5);
            final double angle = 3.14159265358979323846 * (0.5 * seed.next() + 0.5);
            filter.setCoeffs(new BiquadCoeffs(1.0, 0.0, 0.0, -2.0 * radius * Math.cos(angle), radius * radius));
            filter.processSample(0.0, 0);
            final double now = filter.getStateEnergy(0);
            rose = rose || now > energy * (1.0 + 1e-12) + 1e-300;
            energy = now;
        }
        assertFalse(rose);

        filter.setCoeffs(BiquadDesign.makeLowPass(RATE, 150.0, 30.0));
        for (int i = 0; i < 4000; ++i) filter.processSample(0.5 * seed.next(), 0);
        energy = filter.getStateEnergy(0);
        filter.rampCoeffs(BiquadDesign.makeLowPass(RATE, 12000.0, 0.5), 300);
        rose = false;
        for (int i = 0; i < 300; ++i)
        {
            filter.advanceRamp(1);
            final NormalizedLadder.Coeffs l = filter.getLadder();
            rose = rose || l.c1 * l.c1 + l.s1 * l.s1 > 1.0 + 1e-12 || l.c2 * l.c2 + l.s2 * l.s2 > 1.0 + 1e-12;
            filter.processSample(0.0, 0);
            final double now = filter.getStateEnergy(0);
            rose = rose || now > energy * (1.0 + 1e-12) + 1e-300;
            energy = now;
        }
        assertFalse(rose);
        assertFalse(filter.isRamping());
    }

    @Test
    @DisplayName("A ramp lands exactly, the block form matches the sample form, a bypassed set is flat")
    void rampLandsExactlyAndBlockMatchesSampleForm()
    {
        final BiquadCoeffs from = BiquadDesign.makePeak(RATE, 700.0, 2.0, 6.0);
        final BiquadCoeffs to = BiquadDesign.makePeak(RATE, 2500.0, 3.0, -9.0);
        final NormalizedLadder blockForm = new NormalizedLadder(2), sampleForm = new NormalizedLadder(2);
        blockForm.setCoeffs(from);
        sampleForm.setCoeffs(from);
        blockForm.rampCoeffs(to, 700);
        sampleForm.rampCoeffs(to, 700);
        assertTrue(blockForm.isRamping());

        final double[][] block = new double[2][512];
        double worst = 0.0;
        for (int pass = 0; pass < 3; ++pass)
        {
            PortSignals.fillNoise(block, 512, 0.5f, 100 + pass);
            final double[] left = block[0].clone(), right = block[1].clone();
            blockForm.process(block, 512);
            for (int i = 0; i < 512; ++i)
            {
                sampleForm.advanceRamp(1);
                final double l = sampleForm.processSample(left[i], 0);
                final double r = sampleForm.processSample(right[i], 1);
                worst = Math.max(worst, Math.abs(l - block[0][i]));
                worst = Math.max(worst, Math.abs(r - block[1][i]));
            }
        }
        assertTrue(worst < 1e-12, "worst " + worst);

        final NormalizedLadder.Coeffs target = NormalizedLadder.fromBiquad(to);
        for (NormalizedLadder each : new NormalizedLadder[] { blockForm, sampleForm })
        {
            assertFalse(each.isRamping());
            final NormalizedLadder.Coeffs l = each.getLadder();
            assertTrue(l.c1 == target.c1 && l.s1 == target.s1 && l.c2 == target.c2 && l.s2 == target.s2
                       && l.v0 == target.v0 && l.v1 == target.v1 && l.v2 == target.v2);
        }

        final NormalizedLadder bypassed = new NormalizedLadder(1);
        bypassed.setCoeffs(from);
        final PortSignals.Lcg seed = new PortSignals.Lcg(8);
        for (int i = 0; i < 2000; ++i) bypassed.processSample(0.5 * seed.next(), 0);
        bypassed.setCoeffs(BiquadDesign.bypassed(from));
        final NormalizedLadder.Coeffs flat = bypassed.getLadder();
        assertEquals(flat.c1 * flat.c2, flat.v0, 1e-14);
        assertEquals(flat.s1 * flat.c2, flat.v1, 1e-14);
        assertEquals(flat.s2, flat.v2, 1e-14);
        for (int i = 0; i < 4000; ++i) bypassed.processSample(0.5 * seed.next(), 0);
        double residue = 0.0;
        for (int i = 0; i < 1000; ++i)
        {
            final double x = 0.5 * seed.next();
            residue = Math.max(residue, Math.abs(bypassed.processSample(x, 0) - x));
        }
        assertTrue(residue < 1e-12, "residue " + residue);

        PortSignals.fillNoise(block, 512, 0.5f, 3);
        block[0][7] = Double.NaN;
        blockForm.process(block, 512);
        PortSignals.fillNoise(block, 512, 0.5f, 4);
        blockForm.process(block, 512);
        assertTrue(PortSignals.allFinite(block[0]));
        assertTrue(PortSignals.allFinite(block[1]));
    }

    @Test
    @DisplayName("Segments of each channel then advanceRamp equal the block form to the bit; block sizes agree")
    void segmentsAndBlockSizes()
    {
        final BiquadCoeffs from = BiquadDesign.makeLowPass(RATE, 400.0, 2.0);
        final BiquadCoeffs to = BiquadDesign.makeHighShelf(RATE, 3000.0, 6.0);
        final double[] l = PortSignals.sine(6000, 220.0, RATE, 0.4), r = PortSignals.sine(6000, 3100.0, RATE, 0.3);

        double[][] reference = null;
        for (int blockSize : new int[] { 1, 37, 512, 4096 })
        {
            final NormalizedLadder f = new NormalizedLadder(2);
            f.setCoeffs(from);
            f.rampCoeffs(to, 1000);
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

        final NormalizedLadder seg = new NormalizedLadder(2);
        seg.setCoeffs(from);
        seg.rampCoeffs(to, 1000);
        final double[][] buf = { l.clone(), r.clone() };
        for (int pos = 0; pos < l.length; pos += 300)
        {
            final int n = Math.min(300, l.length - pos);
            seg.processSegment(buf[0], pos, n, 0);
            seg.processSegment(buf[1], pos, n, 1);
            seg.advanceRamp(n);
        }
        for (int ch = 0; ch < 2; ++ch)
            for (int i = 0; i < l.length; ++i) assertEquals(reference[ch][i], buf[ch][i], 0.0);

        // reset() equals a fresh section.
        final NormalizedLadder used = new NormalizedLadder(1), fresh = new NormalizedLadder(1);
        used.setCoeffs(from);
        fresh.setCoeffs(from);
        for (int i = 0; i < 300; ++i) used.processSample(l[i], 0);
        used.reset();
        for (int i = 0; i < 1000; ++i) assertEquals(fresh.processSample(r[i], 0), used.processSample(r[i], 0), 0.0);
    }
}
