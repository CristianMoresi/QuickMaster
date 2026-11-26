package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-pass cascade {@link BiquadCascade}. */
class BiquadCascadeTest
{
    /** A varied cascade: bells, shelves and cuts across the band. */
    static BiquadCoeffs cascadeSection(int index, double rate)
    {
        switch (index % 6)
        {
            case 0: return BiquadDesign.makePeak(rate, 120.0 + 37.0 * index, 1.4, 6.0);
            case 1: return BiquadDesign.makeHighShelf(rate, 6000.0, -4.5);
            case 2: return BiquadDesign.makePeak(rate, 2500.0 + 311.0 * index, 4.0, -9.0);
            case 3: return BiquadDesign.makeHighPass(rate, 40.0, 0.9);
            case 4: return BiquadDesign.makeLowShelf(rate, 200.0, 3.0);
            default: return BiquadDesign.makeLowPass(rate, 15000.0, 0.6);
        }
    }

    @Test
    @DisplayName("The cascade matches a chain of biquads for every count and every cut of the blocks")
    void matchesAChainOfBiquads()
    {
        final double rate = 48000.0;
        final int channels = 3, total = 6000;
        final int[] lengths = { 1, 63, 64, 65, 200, 7, 512 };
        for (int sections : new int[] { 1, 2, 3, 4, 5, 7, 8 })
        {
            final BiquadCascade cascade = new BiquadCascade(8, 4);
            cascade.setNumSections(sections);
            final BiquadFilter[] chain = new BiquadFilter[8];
            for (int s = 0; s < sections; ++s)
            {
                final BiquadCoeffs c = cascadeSection(s, rate);
                cascade.setCoeffs(s, c);
                chain[s] = new BiquadFilter(4);
                chain[s].setCoeffsNow(c);
            }
            assertEquals(sections, cascade.getNumSections());

            final double[][] block = new double[channels][512];
            final PortSignals.Lcg seed = new PortSignals.Lcg(12345 + sections);
            double worst = 0.0, peak = 0.0;
            int done = 0, turn = 0;
            while (done < total)
            {
                final int length = Math.min(lengths[turn++ % 7], total - done);
                final double[][] expected = new double[channels][length];
                for (int ch = 0; ch < channels; ++ch)
                    for (int i = 0; i < length; ++i)
                    {
                        final double x = 0.5 * seed.next();
                        block[ch][i] = x;
                        double y = x;
                        for (int s = 0; s < sections; ++s) y = chain[s].processSampleCore(y, ch);
                        expected[ch][i] = y;
                    }
                cascade.process(block, channels, 0, length);
                for (int ch = 0; ch < channels; ++ch)
                    for (int i = 0; i < length; ++i)
                    {
                        worst = Math.max(worst, Math.abs(block[ch][i] - expected[ch][i]));
                        peak = Math.max(peak, Math.abs(expected[ch][i]));
                    }
                done += length;
            }
            assertTrue(peak > 0.05);
            assertTrue(worst < 1e-9, "worst " + worst);
        }
    }

    private static double[] noise(int n, int seed)
    {
        final double[] x = new double[n];
        final PortSignals.Lcg rng = new PortSignals.Lcg(seed);
        for (int i = 0; i < n; ++i) x[i] = 0.5 * rng.next();
        return x;
    }

    private static double[] render(double[] input, int blockLength, boolean withRamp)
    {
        final double rate = 48000.0;
        final int total = input.length;
        final BiquadCascade cascade = new BiquadCascade(8, 2);
        cascade.setNumSections(7);
        for (int s = 0; s < 7; ++s) cascade.setCoeffs(s, cascadeSection(s, rate));
        final double[] out = input.clone();
        final double[][] block = new double[1][blockLength];
        int pos = 0;
        while (pos < total)
        {
            final int length = Math.min(blockLength, total - pos);
            if (withRamp && pos <= 1000 && pos + length > 1000)
                cascade.rampCoeffs(2, BiquadDesign.makePeak(rate, 900.0, 2.0, 9.0), 700);
            System.arraycopy(input, pos, block[0], 0, length);
            cascade.process(block, 1, 0, length);
            System.arraycopy(block[0], 0, out, pos, length);
            pos += length;
        }
        return out;
    }

    @Test
    @DisplayName("Exact across block partitions, with and without a ramp, and the sample form agrees")
    void exactAcrossBlockPartitionsAndSampleForm()
    {
        final double rate = 48000.0;
        final double[] input = noise(3000, 99);

        final double[] whole = render(input, 3000, false);
        for (int blockLength : new int[] { 1, 50, 64, 250 })
        {
            final double[] cut = render(input, blockLength, false);
            for (int i = 0; i < input.length; ++i) assertEquals(whole[i], cut[i], 0.0);
        }
        final double[] rampWhole = render(input, 1000, true);
        for (int blockLength : new int[] { 50, 250, 500 })
        {
            final double[] cut = render(input, blockLength, true);
            for (int i = 0; i < input.length; ++i) assertEquals(rampWhole[i], cut[i], 0.0);
        }
        boolean rampHeard = false;
        for (int i = 1000; i < input.length; ++i) rampHeard = rampHeard || Math.abs(rampWhole[i] - whole[i]) > 1e-3;
        assertTrue(rampHeard);

        final BiquadCascade blockForm = new BiquadCascade(8, 2), sampleForm = new BiquadCascade(8, 2);
        for (BiquadCascade each : new BiquadCascade[] { blockForm, sampleForm })
        {
            each.setNumSections(5);
            for (int s = 0; s < 5; ++s) each.setCoeffs(s, cascadeSection(s, rate));
        }
        final BiquadCoeffs target = BiquadDesign.makePeak(rate, 3000.0, 3.0, -12.0);
        blockForm.rampCoeffs(4, target, 333);
        sampleForm.rampCoeffs(4, target, 333);
        assertTrue(blockForm.isRamping());
        assertTrue(blockForm.isRamping(4));
        assertFalse(blockForm.isRamping(0));

        final double[][] block = { java.util.Arrays.copyOf(input, 1000) };
        blockForm.process(block, 1000);
        double worst = 0.0;
        for (int i = 0; i < 1000; ++i)
        {
            sampleForm.advanceRamps(1);
            worst = Math.max(worst, Math.abs(sampleForm.processSample(input[i], 0) - block[0][i]));
        }
        assertTrue(worst < 1e-9, "worst " + worst);
        for (BiquadCascade each : new BiquadCascade[] { blockForm, sampleForm })
        {
            assertFalse(each.isRamping());
            assertTrue(PortSignals.same(target, each.getCoeffs(4)));
        }
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 samples give identical output with a ramp in flight")
    void blockSizesGiveIdenticalOutput()
    {
        final double rate = 44100.0;
        final int total = 9000;
        final double[] left = noise(total, 3), right = noise(total, 4);
        double[][] reference = null;
        for (int blockSize : new int[] { 1, 37, 512, 4096 })
        {
            final BiquadCascade cascade = new BiquadCascade(4, 2);
            cascade.setNumSections(4);
            for (int s = 0; s < 4; ++s) cascade.setCoeffs(s, cascadeSection(s + 1, rate));
            cascade.rampCoeffs(1, BiquadDesign.makePeak(rate, 3000.0, 2.0, 8.0), 700);
            cascade.rampToBypass(3, 1500);
            final double[][] buf = { left.clone(), right.clone() };
            for (int pos = 0; pos < total; pos += blockSize)
                cascade.process(buf, 2, pos, Math.min(blockSize, total - pos));
            assertFalse(cascade.isRamping());
            if (reference == null)
            {
                reference = buf;
                continue;
            }
            for (int ch = 0; ch < 2; ++ch)
                for (int i = 0; i < total; ++i) assertEquals(reference[ch][i], buf[ch][i], 0.0);
        }
    }

    @Test
    @DisplayName("Bypass leaves and returns without a click")
    void bypassLeavesAndReturnsWithoutAClick()
    {
        final double rate = 48000.0;
        final int blockLength = 4800;
        final BiquadCoeffs bell = BiquadDesign.makePeak(rate, 1000.0, 4.0, 12.0);
        final BiquadCoeffs flat = BiquadDesign.bypassed(bell);
        assertTrue(flat.a1 == bell.a1 && flat.a2 == bell.a2 && flat.b0 == 1.0 && flat.b1 == bell.a1 && flat.b2 == bell.a2);
        assertEquals(1.0, BiquadDesign.getMagnitude(flat, 1000.0, rate), 1e-12);
        assertEquals(1.0, BiquadDesign.getMagnitude(flat, 15000.0, rate), 1e-12);

        final BiquadCascade cascade = new BiquadCascade(4, 2);
        cascade.setNumSections(1);
        cascade.setCoeffs(0, bell);
        final double[][] block = new double[1][blockLength];
        final long[] n = { 0 };
        final Runnable run = () -> {
            for (int i = 0; i < blockLength; ++i) block[0][i] = tone(n[0] + i, rate);
            cascade.process(block, blockLength);
            n[0] += blockLength;
        };

        run.run();
        run.run();
        double last = block[0][blockLength - 1];
        final double boostedStep = largestStep(block[0], block[0][0]);
        assertEquals(0.2 * 3.981 * 2.0 * Math.sin(Math.PI * 1000.0 / rate), boostedStep, 0.01);

        cascade.rampToBypass(0, 96);
        run.run();
        assertTrue(largestStep(block[0], last) < boostedStep * 1.02);
        run.run();
        double residue = 0.0;
        for (int i = 0; i < blockLength; ++i)
            residue = Math.max(residue, Math.abs(block[0][i] - tone(n[0] - blockLength + i, rate)));
        assertTrue(residue < 1e-12, "residue " + residue);

        cascade.setSectionEnabled(0, false);
        assertFalse(cascade.isSectionEnabled(0));
        run.run();
        for (int i = 0; i < blockLength; ++i) assertEquals(tone(n[0] - blockLength + i, rate), block[0][i], 0.0);
        last = block[0][blockLength - 1];

        cascade.setSectionEnabled(0, true);
        cascade.rampCoeffs(0, bell, 96);
        run.run();
        assertTrue(largestStep(block[0], last) < boostedStep * 1.02);
        run.run();
        assertEquals(0.2 * 3.981, PortSignals.peak(block[0], 0, blockLength), 0.005);
    }

    private static double tone(long n, double rate)
    {
        return 0.2 * Math.sin(2.0 * 3.14159265358979323846 * 1000.0 * (double) n / rate);
    }

    private static double largestStep(double[] x, double previous)
    {
        double step = 0.0;
        for (double v : x)
        {
            step = Math.max(step, Math.abs(v - previous));
            previous = v;
        }
        return step;
    }

    @Test
    @DisplayName("Sections enter, leave and recover; wild arguments are ignored or clamped")
    void sectionsEnterLeaveAndRecover()
    {
        final double rate = 48000.0;
        final BiquadCascade cascade = new BiquadCascade(6, 2);
        cascade.setNumSections(4);
        for (int s = 0; s < 6; ++s) cascade.setCoeffs(s, cascadeSection(s, rate));

        cascade.setCoeffs(-1, BiquadCoeffs.IDENTITY);
        cascade.setCoeffs(6, BiquadCoeffs.IDENTITY);
        cascade.rampCoeffs(99, BiquadCoeffs.IDENTITY, 10);
        cascade.setSectionEnabled(17, false);
        assertFalse(cascade.isSectionEnabled(17));
        cascade.setNumSections(99);
        assertEquals(6, cascade.getNumSections());
        cascade.setNumSections(-3);
        assertEquals(0, cascade.getNumSections());

        final double[][] block = new double[2][256];
        PortSignals.fillNoise(block, 256, 0.5f, 12345);
        double[] before = block[0].clone();
        cascade.process(block, 256);
        for (int i = 0; i < 256; ++i) assertEquals(before[i], block[0][i], 0.0);

        cascade.setNumSections(1);
        cascade.setCoeffs(0, BiquadDesign.bypassed(cascadeSection(0, rate)));
        PortSignals.fillNoise(block, 256, 0.5f, 77);
        before = block[0].clone();
        cascade.process(block, 256);
        double worst = 0.0;
        for (int i = 0; i < 256; ++i) worst = Math.max(worst, Math.abs(block[0][i] - before[i]));
        assertTrue(worst < 1e-6);

        cascade.setNumSections(4);
        for (int s = 0; s < 4; ++s) cascade.setCoeffs(s, cascadeSection(s, rate));
        PortSignals.fillNoise(block, 256, 0.5f, 5);
        block[0][10] = Double.NaN;
        block[1][20] = Double.POSITIVE_INFINITY;
        cascade.process(block, 256);
        PortSignals.fillNoise(block, 256, 0.5f, 6);
        cascade.process(block, 256);
        assertTrue(PortSignals.allFinite(block[0]));
        assertTrue(PortSignals.allFinite(block[1]));
        double sum = 0.0;
        for (double v : block[0]) sum += v * v;
        assertTrue(Math.sqrt(sum / 256) > 0.01);

        cascade.reset();
        java.util.Arrays.fill(block[0], 0.0);
        java.util.Arrays.fill(block[1], 0.0);
        cascade.process(block, 256);
        for (double[] ch : block) for (double v : ch) assertEquals(0.0, v, 0.0);
    }

    @Test
    @DisplayName("Low corners at 768 kHz stay clean: no level comes through a high-pass")
    void lowCornersAtHighRatesStayClean()
    {
        final double rate = 768000.0;
        final BiquadCoeffs highPass = BiquadDesign.makeHighPass(rate, 20.0, 0.7071067811865476);
        final BiquadCoeffs bell = BiquadDesign.makePeak(rate, 30.0, 1.0, 0.1);
        final BiquadCascade cascade = new BiquadCascade(2, 1);
        cascade.setNumSections(2);
        cascade.setCoeffs(0, highPass);
        cascade.setCoeffs(1, bell);
        final BiquadFilter first = new BiquadFilter(1), second = new BiquadFilter(1);
        first.setCoeffsNow(highPass);
        second.setCoeffsNow(bell);

        final int total = 1152000;
        double worst = 0.0, mean = 0.0;
        for (int n = 0; n < total; ++n)
        {
            final double x = 0.25 + 0.25 * Math.sin(2.0 * 3.14159265358979323846 * 1000.0 * (double) n / rate);
            final double y = cascade.processSample(x, 0);
            final double want = second.processSampleCore(first.processSampleCore(x, 0), 0);
            if (n > total / 2) worst = Math.max(worst, Math.abs(y - want));
            if (n >= total - 768) mean += y / 768.0;
        }
        assertTrue(worst < 1e-8);
        assertTrue(Math.abs(mean) < 1e-9, "mean " + mean);
    }

    @Test
    @DisplayName("A skipped section keeps its memory and passes its input on")
    void skipMaskLeavesSectionsOut()
    {
        final double rate = 48000.0;
        final BiquadCascade masked = new BiquadCascade(4, 2), plain = new BiquadCascade(4, 2);
        masked.setNumSections(3);
        plain.setNumSections(2);
        masked.setCoeffs(0, cascadeSection(0, rate));
        masked.setCoeffs(1, cascadeSection(1, rate));
        masked.setCoeffs(2, cascadeSection(2, rate));
        plain.setCoeffs(0, cascadeSection(0, rate));
        plain.setCoeffs(1, cascadeSection(2, rate));
        final double[] x = noise(2000, 11);
        for (int i = 0; i < x.length; ++i)
            assertEquals(plain.processSample(x[i], 0), masked.processSample(x[i], 0, 1L << 1), 1e-12);
        // With no bit set it is the plain sample form.
        final BiquadCascade a = new BiquadCascade(4, 1), b = new BiquadCascade(4, 1);
        for (BiquadCascade c : new BiquadCascade[] { a, b })
        {
            c.setNumSections(3);
            for (int s = 0; s < 3; ++s) c.setCoeffs(s, cascadeSection(s, rate));
        }
        for (double v : x) assertEquals(a.processSample(v, 0), b.processSample(v, 0, 0L), 0.0);
    }

    @Test
    @DisplayName("The memory of a pair turns to middle and sides and back")
    void turnPairMemoryCarriesTheState()
    {
        final double rate = 48000.0;
        final BiquadCascade turned = new BiquadCascade(4, 2), midSide = new BiquadCascade(4, 2);
        for (BiquadCascade c : new BiquadCascade[] { turned, midSide })
        {
            c.setNumSections(3);
            for (int s = 0; s < 3; ++s) c.setCoeffs(s, cascadeSection(s, rate));
        }
        final double[] l = noise(4000, 21), r = noise(4000, 22);
        double worst = 0.0;
        for (int i = 0; i < 4000; ++i)
        {
            final double m = 0.5 * (l[i] + r[i]), s = 0.5 * (l[i] - r[i]);
            final double sm = midSide.processSample(m, 0), ss = midSide.processSample(s, 1);
            if (i < 2000)
            {
                turned.processSample(l[i], 0);
                turned.processSample(r[i], 1);
                if (i == 1999) turned.turnPairMemory(0, 1, false);
                continue;
            }
            worst = Math.max(worst, Math.abs(turned.processSample(m, 0) - sm));
            worst = Math.max(worst, Math.abs(turned.processSample(s, 1) - ss));
        }
        assertTrue(worst < 1e-12, "worst " + worst);
        // Back: middle and sides become left and right again, in both.
        turned.turnPairMemory(0, 1, true);
        midSide.turnPairMemory(0, 1, true);
        for (int i = 0; i < 500; ++i)
        {
            assertEquals(midSide.processSample(l[i], 0), turned.processSample(l[i], 0), 1e-12);
            assertEquals(midSide.processSample(r[i], 1), turned.processSample(r[i], 1), 1e-12);
        }
    }

    @Test
    @DisplayName("reset() equals a fresh instance; an empty path keeps the input history for a section that enters")
    void resetAndEmptyPath()
    {
        final double rate = 48000.0;
        final double[] x = noise(3000, 31);
        final BiquadCascade used = new BiquadCascade(4, 1), fresh = new BiquadCascade(4, 1);
        for (BiquadCascade c : new BiquadCascade[] { used, fresh })
        {
            c.setNumSections(4);
            for (int s = 0; s < 4; ++s) c.setCoeffs(s, cascadeSection(s, rate));
        }
        final double[][] warm = { x.clone() };
        used.process(warm, 1000);
        used.reset();
        final double[][] a = { x.clone() }, b = { x.clone() };
        used.process(a, 3000);
        fresh.process(b, 3000);
        for (int i = 0; i < 3000; ++i) assertEquals(b[0][i], a[0][i], 0.0);

        // A transparent section entering from an empty path: the output is its input.
        final BiquadCascade empty = new BiquadCascade(2, 1);
        empty.setNumSections(1);
        empty.setSectionEnabled(0, false);
        final double[][] first = { java.util.Arrays.copyOf(x, 100) };
        empty.process(first, 100);
        empty.setCoeffs(0, BiquadDesign.bypassed(cascadeSection(0, rate)));
        empty.setSectionEnabled(0, true);
        final double[][] second = { java.util.Arrays.copyOfRange(x, 100, 300) };
        empty.process(second, 200);
        for (int i = 0; i < 200; ++i) assertEquals(x[100 + i], second[0][i], 1e-12);
    }
}
