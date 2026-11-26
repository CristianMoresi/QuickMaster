package com.dspark.core;

import com.dspark.core.AnalogRandom.AnalogComponent;
import com.dspark.core.AnalogRandom.BpmDivision;
import com.dspark.core.AnalogRandom.Detail.Xoshiro256pp;
import com.dspark.core.AnalogRandom.Generator;
import com.dspark.core.AnalogRandom.NoiseType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link AnalogRandom} and {@link AnalogConstants}. */
class AnalogRandomTest
{
    // -- Golden values ------------------------------------------------------------
    //
    // The values below were computed by replaying the C++ algorithms of
    // Core/AnalogRandom.h in an independent script using unbounded integers
    // reduced mod 2^64 after every operation (splitmix64: x += 0x9E3779B97F4A7C15,
    // x = (x ^ x>>30) * 0xBF58476D1CE4E5B9, x = (x ^ x>>27) * 0x94D049BB133111EB,
    // x ^ x>>31; reseed: s0 = splitmix64(seed + 0x9E3779B97F4A7C15), s1..s3 =
    // splitmix64 of the previous word; xoshiro256++ next: rotl(s0 + s3, 23) + s0
    // and the published state update), and IEEE doubles for the generator's
    // mapping. The same script reproduces the published xoshiro256++ reference
    // outputs for the raw state {1, 2, 3, 4}.

    private static long u(String unsigned) { return Long.parseUnsignedLong(unsigned); }

    @Test
    @DisplayName("xoshiro256++ reproduces the published reference outputs for the state {1, 2, 3, 4}")
    void xoshiroReferenceVector()
    {
        Xoshiro256pp x = new Xoshiro256pp();
        x.setState(1L, 2L, 3L, 4L);
        final long[] expected = { 41943041L, 58720359L, 3588806011781223L, 3591011842654386L,
                                  u("9228616714210784205"), u("9973669472204895162") };
        for (long e : expected) assertEquals(e, x.next());
    }

    @Test
    @DisplayName("splitmix64 seeding and the first outputs match the C++ for several seeds")
    void goldenSeeds()
    {
        Xoshiro256pp one = new Xoshiro256pp(1L);
        assertEquals(0xbeeb8da1658eec67L, one.getState(0));
        assertEquals(0x778b1aa9c29bc868L, one.getState(1));
        assertEquals(0x063a5277110f4425L, one.getState(2));
        assertEquals(0x427118aca82d6666L, one.getState(3));
        assertEquals(0xe5f26bcacc0f9abaL, one.next());
        assertEquals(0xbcb94c1beee8ac14L, one.next());
        assertEquals(0x6db779bbe9d82c1cL, one.next());
        assertEquals(0x8f378a2f84a35e97L, one.next());

        Xoshiro256pp fortyTwo = new Xoshiro256pp(42L);
        assertEquals(0x28efe333b266f103L, fortyTwo.getState(0));
        assertEquals(0xcc29b55b80849eadL, fortyTwo.next());
        assertEquals(0x2af8c90035a41078L, fortyTwo.next());
        assertEquals(0xcc1bd847bc2e9f88L, fortyTwo.next());
        assertEquals(0xcadb2a14327f7657L, fortyTwo.next());

        // The seeds Saturation gives its left and right drift generators.
        Xoshiro256pp left = new Xoshiro256pp(0x9E3779B97F4A7C15L);
        assertEquals(0x0aafb31e8907a809L, left.next());
        assertEquals(0xc8c25f11b92d657fL, left.next());
        assertEquals(0x77ac79b57fc5a18cL, left.next());
        assertEquals(0xb3f17944093e4865L, left.next());
        Xoshiro256pp right = new Xoshiro256pp(0xBF58476D1CE4E5B9L);
        assertEquals(0xe58400dc8551fbcdL, right.next());
        assertEquals(0x31eb162e034dbc52L, right.next());
        assertEquals(0x7443e3f4c04e1c40L, right.next());
        assertEquals(0x7a14617af056b5afL, right.next());

        // A zero seed is the seed 1.
        Xoshiro256pp zero = new Xoshiro256pp(0L);
        assertEquals(0xe5f26bcacc0f9abaL, zero.next());
        zero.reseed(0L);
        assertEquals(0xe5f26bcacc0f9abaL, zero.next());
    }

    @Test
    @DisplayName("nextDouble takes the top 53 bits")
    void goldenDoubles()
    {
        Xoshiro256pp x = new Xoshiro256pp(1L);
        assertEquals(0.8982303018674983, x.nextDouble(), 0.0);
        assertEquals(0.7372024124332791, x.nextDouble(), 0.0);
        assertEquals(0.4285808643124659, x.nextDouble(), 0.0);
        assertEquals(0.5594412199030904, x.nextDouble(), 0.0);
    }

    @Test
    @DisplayName("A seeded white generator gives the golden first samples")
    void goldenGeneratorSamples()
    {
        // Seed 1, white noise, a new target every sample, range +-0.25, no
        // smoothing: target = -0.25 + ((2 u - 1) / 2 + 1 / 2) * 0.5 with u the
        // doubles above, plus the +-1e-18 offset (absorbed by the rounding).
        Generator g = new Generator(1L);
        g.prepare(48000.0);
        g.setNoiseType(NoiseType.WHITE);
        g.setRateHz(48000.0);
        g.setRange(-0.25, 0.25);
        final double[] expected = { 0.19911515093374915, 0.11860120621663955, -0.035709567843767054,
                                    0.029720609951545185, -0.16785863623313263 };
        for (double e : expected) assertEquals(e, g.getNextSample(), 0.0);
    }

    // -- Ports of the C++ assertions ---------------------------------------------------

    @Test
    @DisplayName("Seeded streams are deterministic and bounded")
    void seededStreamIsDeterministicAndBounded()
    {
        Generator a = new Generator(42L);
        Generator b = new Generator(42L);
        for (Generator g : new Generator[] { a, b })
        {
            g.prepare(48000.0);
            g.setNoiseType(NoiseType.WHITE);
            g.setRateHz(1000.0);
            g.setRange(-0.25, 0.25);
        }
        int mismatches = 0;
        int outOfRange = 0;
        for (int i = 0; i < 4800; ++i)
        {
            final double va = a.getNextSample();
            final double vb = b.getNextSample();
            if (va != vb) ++mismatches;
            if (va < -0.2501 || va > 0.2501) ++outOfRange;
        }
        assertEquals(0, mismatches);
        assertEquals(0, outOfRange);
    }

    @Test
    @DisplayName("Sample-and-hold steps at the rate and lands on the quantization grid")
    void sampleHoldRateAndQuantization()
    {
        Generator g = new Generator(7L);
        g.prepare(48000.0);
        g.setNoiseType(NoiseType.WHITE);
        g.setRateHz(100.0);
        g.setRange(0.5, 1.0);
        g.setQuantization(0.1f);
        double prev = g.getNextSample();
        int steps = 0;
        int offGrid = 0;
        for (int i = 1; i < 48000; ++i)
        {
            final double v = g.getNextSample();
            if (v != prev) ++steps;
            final double snapped = Math.rint(v / 0.1) * 0.1;
            if (Math.abs(v - snapped) > 1e-5) ++offGrid;
            prev = v;
        }
        assertTrue(steps > 60 && steps < 110, "steps " + steps);
        assertEquals(0, offGrid);
    }

    @Test
    @DisplayName("Smoothing bounds the slew, and a zero smoothing time is instantaneous")
    void smoothingBoundsSlew()
    {
        Generator g = new Generator(3L);
        g.prepare(48000.0);
        g.setNoiseType(NoiseType.WHITE);
        g.setRateHz(2.0);
        g.setRange(-1.0, 1.0);
        g.setSmoothing(true, 50.0);
        double prev = g.getNextSample();
        double maxDelta = 0.0;
        for (int i = 1; i < 48000; ++i)
        {
            final double v = g.getNextSample();
            maxDelta = Math.max(maxDelta, Math.abs(v - prev));
            prev = v;
        }
        assertTrue(maxDelta < 2.0 * 4.2e-4 + 1e-6, "max delta " + maxDelta);

        Generator z = new Generator(9L);
        z.prepare(48000.0);
        z.setNoiseType(NoiseType.WHITE);
        z.setRateHz(4800.0);
        z.setRange(0.5, 1.0);
        z.setSmoothing(true, 0.0);
        double p = z.getNextSample();
        int moves = 0;
        for (int i = 1; i < 1000; ++i)
        {
            final double v = z.getNextSample();
            if (Math.abs(v - p) > 1e-3) ++moves;
            p = v;
        }
        assertTrue(moves > 50, "moves " + moves);
    }

    @Test
    @DisplayName("Discrete mapping stays in bounds and reaches every bucket")
    void discreteMappingStaysInBounds()
    {
        Generator g = new Generator(11L);
        g.prepare(48000.0);
        g.setNoiseType(NoiseType.WHITE);
        g.setRateHz(4800.0);
        g.setRange(-1.0, 1.0);
        int[] histo = new int[5];
        int outOfBounds = 0;
        for (int i = 0; i < 5000; ++i)
        {
            final int v = g.getNextDiscreteInt(2, 6);
            if (v < 2 || v > 6) { ++outOfBounds; continue; }
            ++histo[v - 2];
        }
        assertEquals(0, outOfBounds);
        for (int k = 0; k < 5; ++k) assertTrue(histo[k] > 100, "bucket " + k + ": " + histo[k]);
    }

    @Test
    @DisplayName("Generators with different histories reseeded alike give identical streams")
    void reseedIsDeterministic()
    {
        Generator g = new Generator(5L);
        Generator h = new Generator(999L);
        for (Generator p : new Generator[] { g, h })
        {
            p.prepare(48000.0);
            p.setNoiseType(NoiseType.WHITE);
            p.setRateHz(48000.0);
            p.setRange(-1.0, 1.0);
            p.getNextSample();
        }
        g.reseed(1234L);
        h.reseed(1234L);
        int mismatches = 0;
        for (int i = 0; i < 64; ++i)
            if (g.getNextSample() != h.getNextSample()) ++mismatches;
        assertEquals(0, mismatches);
    }

    private static double lag1(NoiseType type)
    {
        Generator g = new Generator(5L);
        g.prepare(48000.0);
        g.setRange(-1.0, 1.0);
        g.setRateHz(240.0);
        g.setNoiseType(type);
        double[] targets = new double[8000];
        int count = 0;
        double prev = 1e9;
        for (int i = 0; i < 200 * 4000; ++i)
        {
            final double v = g.getNextSample();
            if (Math.abs(v - prev) > 1e-12)
            {
                if (count == targets.length) targets = java.util.Arrays.copyOf(targets, 2 * count);
                targets[count++] = v;
                prev = v;
            }
        }
        double mean = 0.0;
        for (int i = 0; i < count; ++i) mean += targets[i];
        mean /= count;
        double c0 = 0.0;
        double c1 = 0.0;
        for (int i = 0; i < count; ++i)
        {
            c0 += (targets[i] - mean) * (targets[i] - mean);
            if (i > 0) c1 += (targets[i] - mean) * (targets[i - 1] - mean);
        }
        return c1 / c0;
    }

    @Test
    @DisplayName("Colored targets drift, and smoothing set before prepare() follows the rate")
    void coloredTargetsDrift()
    {
        assertTrue(Math.abs(lag1(NoiseType.WHITE)) < 0.1);
        assertTrue(lag1(NoiseType.PINK) > 0.5);
        assertTrue(lag1(NoiseType.BROWN) > 0.9);

        Generator s = new Generator(3L);
        s.setNoiseType(NoiseType.WHITE);
        s.setRange(-1.0, 1.0);
        s.setRateHz(0.5);
        s.setSmoothing(true, 50.0);
        s.prepare(96000.0);
        final double v0 = s.getNextSample();
        final double v1 = s.getNextSample();
        final double v2 = s.getNextSample();
        assertEquals(Math.exp(-1.0 / (96000.0 * 0.05)), (v2 - v1) / (v1 - v0), 1e-6);
    }

    @Test
    @DisplayName("Pink noise measures -3 dB/oct (between -3.75 and -2.25) from 100 Hz to 8 kHz")
    void pinkSlope()
    {
        final double fs = 48000.0;
        final int n = 8192;
        final int segs = 48;
        Generator g = new Generator(12345L);
        g.prepare(fs);
        g.setNoiseType(NoiseType.PINK);
        g.setRateHz(fs);
        g.setRange(-1.0, 1.0);
        g.setSmoothing(false);
        double[] win = new double[n];
        double wp = 0.0;
        for (int i = 0; i < n; ++i)
        {
            win[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n);
            wp += win[i] * win[i];
        }
        FFTRealDouble fft = new FFTRealDouble(n);
        double[] buf = new double[n];
        double[] freq = new double[n + 2];
        double[] psd = new double[n / 2 + 1];
        for (int s = 0; s < segs; ++s)
        {
            for (int i = 0; i < n; ++i) buf[i] = g.getNextSample() * win[i];
            fft.forward(buf, freq);
            for (int k = 0; k <= n / 2; ++k) psd[k] += freq[2 * k] * freq[2 * k] + freq[2 * k + 1] * freq[2 * k + 1];
        }
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int count = 0;
        for (int k = 1; k <= n / 2; ++k)
        {
            final double f = k * fs / n;
            if (f < 100.0 || f > 8000.0) continue;
            final double x = Math.log(f) / Math.log(2.0);
            final double y = 10.0 * Math.log10(psd[k] / segs / wp + 1e-30);
            sx += x; sy += y; sxx += x * x; sxy += x * y; ++count;
        }
        final double slope = (count * sxy - sx * sy) / (count * sxx - sx * sx);
        assertTrue(slope > -3.75 && slope < -2.25, "slope " + slope);
    }

    @Test
    @DisplayName("getCurrentValue() and getPhase() follow the generation")
    void readoutIsPublished()
    {
        Generator solo = new Generator(777L);
        solo.prepare(48000.0);
        solo.setNoiseType(NoiseType.PINK);
        solo.setRateHz(500.0);
        solo.setSmoothing(true, 2.0);
        solo.setRange(-1.0, 1.0);
        int distinct = 0;
        int phaseMoves = 0;
        double lastV = solo.getCurrentValue();
        double lastP = solo.getPhase();
        for (int i = 0; i < 4096; ++i)
        {
            final double out = solo.getNextSample();
            final double v = solo.getCurrentValue();
            final double p = solo.getPhase();
            assertEquals(out, v, 0.0);
            assertTrue(Double.isFinite(v) && Math.abs(v) <= 1.001);
            assertTrue(Double.isFinite(p) && p >= 0.0 && p <= 1.0);
            if (v != lastV) { ++distinct; lastV = v; }
            if (p != lastP) { ++phaseMoves; lastP = p; }
        }
        assertTrue(distinct > 100);
        assertTrue(phaseMoves > 100);
        solo.reset();
        assertEquals(0.0, solo.getCurrentValue(), 0.0);
        assertEquals(0.0, solo.getPhase(), 0.0);
    }

    @Test
    @DisplayName("Non-finite rates, ranges and steps are rejected; the full int range is mapped")
    void rejectsNonFiniteInputs()
    {
        {
            Generator g = new Generator(1L);
            g.prepare(48000.0);
            g.setRateHz(100.0);
            g.setRateHz(Double.NaN);
            for (int i = 0; i < 100; ++i) g.getNextSample();
            g.setRateHz(100.0);
            int changes = 0;
            double last = g.getNextSample();
            for (int i = 0; i < 48000; ++i)
            {
                final double v = g.getNextSample();
                if (Math.abs(v - last) > 1e-6) ++changes;
                last = v;
            }
            assertTrue(changes > 50, "changes " + changes);
        }
        {
            Generator g = new Generator(1L);
            g.prepare(48000.0);
            g.setRateHz(1000.0);
            g.setSmoothing(true, 10.0);
            g.setRange(Double.NaN, 1.0);
            g.setQuantization(Double.POSITIVE_INFINITY);
            double v = 0.0;
            for (int i = 0; i < 4800; ++i) v = g.getNextSample();
            assertTrue(Double.isFinite(v));
        }
        {
            Generator g = new Generator(1L);
            g.prepare(48000.0);
            g.setRateHz(48000.0);
            int atMin = 0;
            for (int i = 0; i < 64; ++i)
                if (g.getNextDiscreteInt(Integer.MIN_VALUE, Integer.MAX_VALUE) == Integer.MIN_VALUE) ++atMin;
            assertTrue(atMin < 8, "at the minimum " + atMin);
        }
    }

    // -- Further properties -----------------------------------------------------------

    private static Generator configured()
    {
        Generator g = new Generator(2024L);
        g.prepare(44100.0);
        g.setNoiseType(NoiseType.PINK);
        g.setRateHz(3000.0);
        g.setRange(0.3, -0.7);
        g.setSmoothing(true, 1.5);
        g.setQuantization(0.01);
        return g;
    }

    @Test
    @DisplayName("getNextBlock gives the getNextSample values in blocks of 1, 37, 512 and 4096")
    void blocksMatchSamples()
    {
        final int total = 20000;
        Generator ref = configured();
        double[] expected = new double[total];
        for (int i = 0; i < total; ++i) expected[i] = ref.getNextSample();
        for (int block : new int[] { 1, 37, 512, 4096 })
        {
            Generator g = configured();
            double[] out = new double[total];
            for (int at = 0; at < total; at += block)
                g.getNextBlock(out, at, Math.min(block, total - at));
            for (int i = 0; i < total; ++i) assertEquals(expected[i], out[i], 0.0, "block " + block + " sample " + i);
            assertEquals(ref.getCurrentValue(), g.getCurrentValue(), 0.0);
            assertEquals(ref.getPhase(), g.getPhase(), 0.0);
        }
        // A reseed pending before a block is adopted by the block too.
        Generator a = configured();
        Generator b = configured();
        a.reseed(77L);
        b.reseed(77L);
        double[] blockOut = new double[300];
        a.getNextBlock(blockOut);
        for (int i = 0; i < 300; ++i) assertEquals(b.getNextSample(), blockOut[i], 0.0);
    }

    @Test
    @DisplayName("A reseed is adopted at the next call with a reset; a zero seed is the seed 1")
    void reseedSemantics()
    {
        Generator a = new Generator(1L);
        Generator b = new Generator(31L);
        for (Generator g : new Generator[] { a, b })
        {
            g.prepare(48000.0);
            g.setNoiseType(NoiseType.BROWN);
            g.setRateHz(480.0);
        }
        for (int i = 0; i < 1000; ++i) b.getNextSample();
        b.reseed(0L);   // the seed 1
        // Both now start from the reset state with the seed 1 (the denormal
        // offset is not reset, but 1000 samples flipped it an even number of times).
        for (int i = 0; i < 5000; ++i) assertEquals(a.getNextSample(), b.getNextSample(), 0.0);
    }

    @Test
    @DisplayName("Without a reseed, reset() keeps the PRNG running (a different stream)")
    void resetKeepsThePrng()
    {
        Generator g = new Generator(8L);
        g.prepare(48000.0);
        g.setNoiseType(NoiseType.WHITE);
        g.setRateHz(48000.0);
        final double first = g.getNextSample();
        g.reset();
        assertTrue(g.getNextSample() != first);
    }

    @Test
    @DisplayName("Analog defaults set the documented ranges and rates")
    void analogDefaults()
    {
        Generator g = new Generator(4L);
        g.setAnalogDefault(AnalogComponent.TAPE_MACHINE);
        g.prepare(48000.0);
        final double bound = AnalogConstants.VariationPercent.TAPE_MACHINE / 100.0f;
        int wraps = 0;
        double lastPhase = 0.0;
        for (int i = 0; i < 4 * 48000 + 100; ++i)
        {
            final double v = g.getNextSample();
            assertTrue(Math.abs(v) <= bound + 1e-15);
            if (g.getPhase() < lastPhase) ++wraps;
            lastPhase = g.getPhase();
        }
        assertEquals(2, wraps);   // 0.5 Hz over 4 s
        assertEquals(0.001, bound, 1e-9);

        for (AnalogComponent c : AnalogComponent.values())
        {
            Generator each = new Generator(5L);
            each.setAnalogDefault(c);
            each.prepare(48000.0);
            double peak = 0.0;
            for (int i = 0; i < 48000; ++i) peak = Math.max(peak, Math.abs(each.getNextSample()));
            assertTrue(peak > 0.0 && peak <= 0.05 + 1e-12, c + " peak " + peak);
        }
    }

    @Test
    @DisplayName("Tempo sync retriggers on the C++ period (60 / bpm) * 4 / multiplier")
    void tempoSync()
    {
        Generator g = new Generator(6L);
        g.prepare(48000.0);
        g.setRateBPM(120.0, BpmDivision.EIGHTH);   // (0.5 s) * (4 / 0.5) = 4 s
        int wraps = 0;
        double lastPhase = 0.0;
        for (int i = 0; i < 8 * 48000 + 10; ++i)
        {
            g.getNextSample();
            if (g.getPhase() < lastPhase) ++wraps;
            lastPhase = g.getPhase();
        }
        assertEquals(2, wraps);
        g.updateBPM(240.0);                        // 2 s
        wraps = 0;
        for (int i = 0; i < 8 * 48000; ++i)
        {
            g.getNextSample();
            if (g.getPhase() < lastPhase) ++wraps;
            lastPhase = g.getPhase();
        }
        assertEquals(4, wraps);
        g.setRateHz(0.0);                          // no retriggering at all
        final double held = g.getNextSample();
        for (int i = 0; i < 48000; ++i) assertEquals(held, g.getNextSample(), 1e-17);
    }

    @Test
    @DisplayName("getNextDiscrete with an empty span returns imin without generating")
    void discreteEmptySpan()
    {
        Generator a = new Generator(10L);
        Generator b = new Generator(10L);
        a.prepare(48000.0);
        b.prepare(48000.0);
        a.setRateHz(48000.0);
        b.setRateHz(48000.0);
        assertEquals(5L, a.getNextDiscrete(5L, 5L));
        assertEquals(5L, a.getNextDiscrete(5L, -3L));
        assertEquals(b.getNextSample(), a.getNextSample(), 0.0);
        a.setRange(0.25, 0.25);
        assertEquals(-4L, a.getNextDiscrete(-4L, 4L));
    }

    @Test
    @DisplayName("The unique-seed constructor works and stays in range")
    void uniqueSeed()
    {
        Generator g = new Generator();
        g.prepare(48000.0);
        g.setRateHz(1000.0);
        for (int i = 0; i < 10000; ++i)
        {
            final double v = g.getNextSample();
            assertTrue(Double.isFinite(v) && Math.abs(v) <= 1.0 + 1e-15);
        }
    }

    @Test
    @DisplayName("Reference constants keep the C++ values")
    void constants()
    {
        assertEquals(1.380649e-23, AnalogConstants.NoiseConstants.Thermal.BOLTZMANN_CONSTANT, 0.0);
        assertEquals(1.602176634e-19, AnalogConstants.NoiseConstants.Shot.ELEMENTARY_CHARGE, 0.0);
        double[] hum = AnalogConstants.NoiseConstants.PracticalNoiseFloors.humFrequenciesHz();
        assertEquals(7, hum.length);
        assertEquals(50.0, hum[0], 0.0);
        assertEquals(300.0, hum[6], 0.0);
        hum[0] = 0.0;   // a copy: the table stays
        assertEquals(50.0, AnalogConstants.NoiseConstants.PracticalNoiseFloors.humFrequenciesHz()[0], 0.0);
        assertEquals(-85.0, AnalogConstants.NoiseConstants.PracticalNoiseFloors.humAmplitudesDb()[6], 0.0);
        assertEquals(3, AnalogConstants.SaturationConstants.Tube.transferFunctionCoeffs().length);
        assertEquals(4, AnalogConstants.EquipmentConstants.Equalizers.frequencyPointsLowHz().length);
        assertEquals(10000.0, AnalogConstants.EquipmentConstants.Equalizers.frequencyPointsHighHz()[2], 0.0);
        assertEquals(0.1f, AnalogConstants.VariationPercent.TAPE_MACHINE, 0.0f);
        assertEquals(5.0f, AnalogConstants.VariationPercent.VACUUM_TUBE, 0.0f);
    }
}
