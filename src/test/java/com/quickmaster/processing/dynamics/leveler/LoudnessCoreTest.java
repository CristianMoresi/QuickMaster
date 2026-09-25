package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ChannelLayout;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;

class LoudnessCoreTest
{
    @Test
    void exactFifteenFieldsChunkedStorageAndFourFieldlessLayouts() throws Exception
    {
        String[] names = { "sampleRateHz", "layout", "momentaryWindowFrames", "shortTermWindowFrames",
                "coefficients", "shelfZ1", "shelfZ2", "highPassZ1", "highPassZ2", "weightedPowerRing",
                "weightedPowerTree", "ringCursor", "framesSeen", "momentarySum", "shortTermSum" };
        Field[] fields = LoudnessCore.class.getDeclaredFields();
        assertArrayEquals(names, Arrays.stream(fields).map(Field::getName).toArray(String[]::new));
        assertEquals(Modifier.PUBLIC | Modifier.FINAL, LoudnessCore.class.getModifiers());
        for (int i = 0; i < fields.length; i++)
            assertEquals(Modifier.PRIVATE | (i < 11 ? Modifier.FINAL : 0), fields[i].getModifiers());
        assertEquals(0, KWeightingAdapter.class.getDeclaredFields().length);
        assertEquals(0, LoudnessAnalyzer.class.getDeclaredFields().length);
        assertArrayEquals(new String[] { "MONO_MAIN", "STEREO_LR", "SURROUND_5_0", "SURROUND_5_1" },
                Arrays.stream(ChannelLayout.values()).map(Enum::name).toArray(String[]::new));
        assertEquals(5, ChannelLayout.class.getDeclaredFields().length);
        for (Field field : ChannelLayout.class.getDeclaredFields())
            assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()));
        for (int rate : new int[] { 44_100, 48_000, 96_000 })
            for (ChannelLayout layout : ChannelLayout.values())
            {
                LoudnessCore core = new LoudnessCore(rate, layout);
                assertEquals(rate, value(core, "sampleRateHz"));
                assertSame(layout, value(core, "layout"));
                Object[] arrays = new Object[7];
                for (int i = 4; i < 11; i++)
                {
                    arrays[i - 4] = value(core, names[i]);
                    if (i < 9)
                    {
                        assertInstanceOf(double[].class, arrays[i - 4]);
                        assertEquals(i == 4 ? 10 : layout.channels(), ((double[]) arrays[i - 4]).length);
                    }
                    else
                    {
                        double[][] chunks = assertInstanceOf(double[][].class, arrays[i - 4]);
                        int expected = i == 9 ? rate * 3 : (rate * 3 + 1) / 2;
                        assertEquals((expected - 1) / 32768 + 1, chunks.length);
                        int total = 0;
                        for (int page = 0; page < chunks.length; page++)
                        {
                            assertEquals(Math.min(32768, expected - page * 32768), chunks[page].length);
                            total += chunks[page].length;
                        }
                        assertEquals(expected, total);
                    }
                    for (int j = 4; j < i; j++) assertNotSame(arrays[j - 4], arrays[i - 4]);
                }
                assertEquals(0L, core.framesSeen());
            }
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.quickmaster.processing.dynamics.leveler.model.ChannelLayout$1"));
    }

    @Test
    void powerConversionHasCategoricalZeroAndNoFiniteFloor()
    {
        for (double zero : new double[] { 0.0d, -0.0d })
        {
            MeasuredLoudness reading = LoudnessCore.fromPower(zero);
            assertFalse(reading.present());
            assertEquals(0L, Double.doubleToRawLongBits(reading.lufs()));
        }
        for (double power : new double[] { Double.MIN_VALUE, 1.0e-100d, 1.0e-40d, 0.1d, 1.0d, Double.MAX_VALUE })
        {
            MeasuredLoudness reading = LoudnessCore.fromPower(power);
            assertTrue(reading.present());
            assertEquals(-0.691d + 10.0d * StrictMath.log10(power), reading.lufs(), 0.0d);
        }
        for (double bad : new double[] { -Double.MIN_VALUE, -1.0d, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
            assertThrows(IllegalArgumentException.class, () -> LoudnessCore.fromPower(bad));
    }

    @Test
    void strictAbsoluteAndRelativeGatesUsePowerMeansAndCountPhysicalBlocks()
    {
        double[] powers = new double[101];
        Arrays.fill(powers, power(-80.0d));
        powers[99] = power(-20.0d);
        powers[100] = power(-45.0d);
        long[] counts = new long[3];
        MeasuredLoudness result = LoudnessCore.integrated(powers, 101, counts, null);
        assertArrayEquals(new long[] { 101, 2, 1 }, counts);
        assertEquals(-20.0d, result.lufs(), 1.0e-12d);

        double absoluteEqual = power(-70.0d);
        assertEquals(-70.0d, LoudnessCore.fromPower(absoluteEqual).lufs(), 0.0d);
        assertFalse(LoudnessCore.integrated(new double[] { absoluteEqual }, 1, counts, null).present());
        assertArrayEquals(new long[] { 1, 0, 0 }, counts);
        for (double lufs : new double[] { Math.nextDown(-70.0d), Math.nextUp(-70.0d) })
        {
            double p = power(lufs);
            assertEquals(lufs, LoudnessCore.fromPower(p).lufs(), 0.0d);
            boolean above = lufs > -70.0d;
            assertEquals(above, LoudnessCore.integrated(new double[] { p }, 1, counts, null).present());
            assertArrayEquals(new long[] { 1, above ? 1 : 0, above ? 1 : 0 }, counts);
        }

        // Mean(0.1,1.9)=1, so the relative threshold is exactly L(0.1).
        double gate = -0.691d - 10.0d;
        assertEquals(gate, LoudnessCore.fromPower(0.1d).lufs(), 0.0d);
        result = LoudnessCore.integrated(new double[] { 0.1d, 1.9d }, 2, counts, null);
        assertArrayEquals(new long[] { 2, 2, 1 }, counts);
        assertEquals(LoudnessCore.fromPower(1.9d).lufs(), result.lufs(), 0.0d);
        for (double neighbour : new double[] { Math.nextDown(gate), Math.nextUp(gate) })
        {
            double candidate = power(neighbour);
            double other = 2.0d - candidate;
            double actualThreshold = -0.691d + 10.0d * StrictMath.log10((candidate + other) / 2.0d) - 10.0d;
            assertEquals(gate, actualThreshold, 0.0d);
            assertEquals(neighbour, LoudnessCore.fromPower(candidate).lufs(), 0.0d);
            LoudnessCore.integrated(new double[] { candidate, other }, 2, counts, null);
            assertEquals(neighbour > gate ? 2L : 1L, counts[2]);
        }
        result = LoudnessCore.integrated(new double[] { 0.0d, 0.0d, 100.0d }, 2, counts, null);
        assertFalse(result.present());
        assertArrayEquals(new long[] { 2, 0, 0 }, counts);
    }

    @Test
    void integratedRefusesMalformedAndNonfiniteStorageIncludingUnusedTail()
    {
        long[] counts = new long[3];
        assertThrows(IllegalArgumentException.class, () -> LoudnessCore.integrated(null, 0, counts, null));
        assertThrows(IllegalArgumentException.class, () -> LoudnessCore.integrated(new double[0], -1, counts, null));
        assertThrows(IllegalArgumentException.class, () -> LoudnessCore.integrated(new double[0], 1, counts, null));
        assertThrows(IllegalArgumentException.class, () -> LoudnessCore.integrated(new double[0], 0, new long[4], null));
        for (double bad : new double[] { -1.0d, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
            assertThrows(IllegalArgumentException.class,
                    () -> LoudnessCore.integrated(new double[] { 1.0d, bad }, 1, counts, null));
        assertThrows(IllegalArgumentException.class,
                () -> LoudnessCore.integrated(new double[] { Double.MAX_VALUE, Double.MAX_VALUE }, 2, counts, null));
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        Arrays.fill(counts, 99L);
        assertNull(LoudnessCore.integrated(new double[] { 1.0d }, 1, counts, cancelled));
        assertArrayEquals(new long[3], counts);
        assertFalse(LoudnessCore.integrated(new double[0], 0, counts, null).present());
    }

    @Test
    void everyLayoutChannelHasItsIndependentNormativePowerWeight()
    {
        ChannelLayout[] layouts = ChannelLayout.values();
        double[][] weights = { { 1.0d }, { 1.0d, 1.0d }, { 1.0d, 1.0d, 1.0d, 1.41d, 1.41d },
                { 1.0d, 1.0d, 1.0d, 0.0d, 1.41d, 1.41d } };
        for (int rate : new int[] { 44_100, 48_000, 96_000 })
        {
            double[] mono = oneChannelReadings(rate, ChannelLayout.MONO_MAIN, 0);
            for (int l = 0; l < layouts.length; l++)
            {
                assertEquals(weights[l].length, layouts[l].channels());
                for (int channel = 0; channel < weights[l].length; channel++)
                {
                    assertEquals(weights[l][channel], layouts[l].powerWeight(channel), 0.0d);
                    double[] actual = oneChannelReadings(rate, layouts[l], channel);
                    assertEquals(mono[0] * weights[l][channel], actual[0], 5.0e-14d);
                    assertEquals(mono[1] * weights[l][channel], actual[1], 5.0e-14d);
                    assertEquals(weights[l][channel] > 0.0d, LoudnessCore.fromPower(actual[1]).present());
                }
                final ChannelLayout layout = layouts[l];
                assertThrows(IllegalArgumentException.class, () -> layout.powerWeight(-1));
                assertThrows(IllegalArgumentException.class, () -> layout.powerWeight(layout.channels()));
            }
        }
        for (int channels : new int[] { 0, 3, 5, 6 })
            assertThrows(IllegalArgumentException.class, () -> new AudioFormat(48_000, channels, 1));
    }

    @Test
    void allCompleteFrameWindowsMatchIndependentDirectFormPrefixOracle()
    {
        int frames = 160_123;
        float[] pcm = new float[frames];
        for (int i = 0; i < frames; i++)
            pcm[i] = (float) ((i > 12_000 && i < 152_123 ? 0.08d : 0.0d)
                    * StrictMath.sin(2.0d * StrictMath.PI * 997.0d * i / 48_000));
        double[] reference = LoudnessSharedCoreTest.reference48kPowers(pcm);
        double[] prefix = new double[frames + 1];
        for (int i = 0; i < frames; i++) prefix[i + 1] = prefix[i] + reference[i];
        LoudnessCore core = new LoudnessCore(48_000, ChannelLayout.MONO_MAIN);
        assertThrows(IllegalArgumentException.class, core::momentaryPower);
        assertThrows(IllegalArgumentException.class, core::shortTermPower);
        int momentaryCount = 0, shortCount = 0;
        for (int i = 0; i < frames; i++)
        {
            core.acceptFrame(pcm, i);
            int end = i + 1;
            assertEquals(end, core.framesSeen());
            if (end >= 19_200)
            {
                assertEquals((prefix[end] - prefix[end - 19_200]) / 19_200, core.momentaryPower(), 2.0e-12d);
                momentaryCount++;
            }
            if (end >= 144_000)
            {
                assertEquals((prefix[end] - prefix[end - 144_000]) / 144_000, core.shortTermPower(), 2.0e-12d);
                shortCount++;
            }
        }
        assertEquals(frames - 19_200 + 1, momentaryCount);
        assertEquals(frames - 144_000 + 1, shortCount);
        double atEof = core.shortTermPower();
        assertEquals(atEof, core.shortTermPower(), 0.0d);
        assertEquals(frames, core.framesSeen(), "Readout never advances or pads EOF.");
    }

    @Test
    void frameClockCanObserveAnOffHopMaximum()
    {
        int rate = 48_000, window = 19_200, impulseFrame = 12_347;
        float[] pcm = new float[window * 3];
        for (int frame = impulseFrame; frame < impulseFrame + window; frame++)
            pcm[frame] = (float) (0.02d * StrictMath.sin(2.0d * StrictMath.PI * 997.0d * frame / rate));
        double[] reference = LoudnessSharedCoreTest.reference48kPowers(pcm);
        LoudnessCore core = new LoudnessCore(rate, ChannelLayout.MONO_MAIN);
        double maximum = -1.0d, gridMaximum = -1.0d;
        int argmax = -1;
        for (int end = 1; end <= pcm.length; end++)
        {
            core.acceptFrame(pcm, end - 1);
            if (end < window) continue;
            double value = core.momentaryPower();
            if (value > maximum) { maximum = value; argmax = end; }
            if ((end - window) % 4_800 == 0) gridMaximum = Math.max(gridMaximum, value);
        }
        double direct = 0.0d;
        for (int i = argmax - window; i < argmax; i++) direct += reference[i];
        assertEquals(direct / window, maximum, 2.0e-15d);
        assertNotEquals(0, (argmax - window) % 4_800);
        assertTrue(maximum > gridMaximum, "Every-frame M exposes a maximum missed by the hop grid.");
    }

    @Test
    void arbitraryChunkCutsEmptyChunksAndFileOrderAreBitIdentical()
    {
        int rate = 31;
        float[] first = new float[260], second = new float[260];
        for (int i = 0; i < first.length; i++)
        {
            first[i] = (float) (0.1d * StrictMath.sin(i * 0.41d));
            second[i] = (float) (0.2d * StrictMath.cos(i * 0.17d));
        }
        double[] expectedFirst = chunked(rate, first, 0);
        double[] expectedSecond = chunked(rate, second, 130);
        for (int cut = 0; cut <= 130; cut++)
        {
            assertArrayEquals(expectedFirst, chunked(rate, first, cut));
            assertArrayEquals(expectedSecond, chunked(rate, second, cut));
            assertArrayEquals(expectedSecond, chunked(rate, second, cut));
            assertArrayEquals(expectedFirst, chunked(rate, first, cut));
        }
        assertFalse(Arrays.equals(expectedFirst, expectedSecond));
    }

    @Test
    void finiteZeroLfeIsObservedButBadInputAndOverflowFailEarly() throws Exception
    {
        LoudnessCore core = new LoudnessCore(48_000, ChannelLayout.SURROUND_5_1);
        float[] frame = { 0.0f, 0.0f, 0.0f, 0.75f, 0.0f, 0.0f };
        for (int i = 0; i < 144_000; i++) core.acceptFrame(frame, 0);
        assertEquals(144_000L, core.framesSeen());
        assertEquals(0L, Double.doubleToRawLongBits(core.momentaryPower()));
        assertFalse(LoudnessCore.fromPower(core.shortTermPower()).present());
        for (float bad : new float[] { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY })
        {
            frame[3] = bad;
            assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(frame, 0));
            assertEquals(144_000L, core.framesSeen());
        }
        assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(null, 0));
        assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(new float[5], 0));
        assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(new float[6], Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(new float[6], -1));
        assertThrows(IllegalArgumentException.class, () -> new LoudnessCore(0, ChannelLayout.MONO_MAIN));
        assertThrows(IllegalArgumentException.class, () -> new LoudnessCore(-1, ChannelLayout.MONO_MAIN));
        assertThrows(IllegalArgumentException.class, () -> new LoudnessCore(48_000, null));
        assertThrows(IllegalArgumentException.class, () -> new LoudnessCore(Integer.MAX_VALUE, ChannelLayout.MONO_MAIN));
        assertThrows(IllegalArgumentException.class, () -> new LoudnessCore(12_000_000, ChannelLayout.MONO_MAIN));
        assertNull(new LoudnessAnalyzer().analyze(new float[1], new AudioFormat(Integer.MAX_VALUE, 1, 1), null));
        Field counter = LoudnessCore.class.getDeclaredField("framesSeen");
        counter.setAccessible(true);
        counter.setLong(core, Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(new float[6], 0));
        assertEquals(Long.MAX_VALUE, core.framesSeen());
    }

    @Test
    void invalidFilterAndPowerIntermediatesAreNotConvertedToAbsence() throws Exception
    {
        for (String name : new String[] { "coefficients", "shelfZ1", "shelfZ2", "highPassZ1", "highPassZ2" })
        {
            LoudnessCore core = new LoudnessCore(48_000, ChannelLayout.MONO_MAIN);
            ((double[]) value(core, name))[0] = Double.NaN;
            assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(new float[1], 0), name);
        }
        for (String name : new String[] { "momentarySum", "shortTermSum" })
            for (double bad : new double[] { -1.0d, Double.NaN, Double.POSITIVE_INFINITY })
            {
                LoudnessCore core = new LoudnessCore(48_000, ChannelLayout.MONO_MAIN);
                Field field = LoudnessCore.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setDouble(core, bad);
                assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(new float[1], 0), name);
            }
        LoudnessCore core = new LoudnessCore(48_000, ChannelLayout.MONO_MAIN);
        ((double[][]) value(core, "weightedPowerRing"))[0][0] = -1.0d;
        assertThrows(IllegalArgumentException.class, () -> core.acceptFrame(new float[1], 0));
    }

    @Test
    void tinyPositiveRatesAndExtremeFinitePcmRemainFinite()
    {
        for (int rate : new int[] { 1, 3, 29, 31, 500 })
        {
            LoudnessCore core = new LoudnessCore(rate, ChannelLayout.STEREO_LR);
            float[] frame = new float[2];
            for (int i = 0; i < rate * 4; i++)
            {
                frame[0] = (i & 1) == 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
                frame[1] = -frame[0];
                core.acceptFrame(frame, 0);
            }
            assertTrue(Double.isFinite(core.momentaryPower()));
            assertTrue(Double.isFinite(core.shortTermPower()));
            assertTrue(core.momentaryPower() >= 0.0d);
            assertTrue(core.shortTermPower() >= 0.0d);
        }
    }

    private static Object value(LoudnessCore core, String name) throws Exception
    {
        Field field = LoudnessCore.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(core);
    }

    private static double power(double lufs) { return StrictMath.pow(10.0d, (lufs + 0.691d) / 10.0d); }

    private static double[] oneChannelReadings(int rate, ChannelLayout layout, int channel)
    {
        LoudnessCore core = new LoudnessCore(rate, layout);
        float[] frame = new float[layout.channels()];
        for (int i = 0; i < rate * 3 + 17; i++)
        {
            frame[channel] = (float) (0.17d * StrictMath.sin(2.0d * StrictMath.PI * 997.0d * i / rate));
            core.acceptFrame(frame, 0);
        }
        return new double[] { core.momentaryPower(), core.shortTermPower() };
    }

    private static double[] chunked(int rate, float[] pcm, int cut)
    {
        LoudnessCore core = new LoudnessCore(rate, ChannelLayout.STEREO_LR);
        double[] readouts = new double[pcm.length];
        int momentary = (int) StrictMath.round(0.4d * rate), shortTerm = rate * 3;
        int position = 0;
        for (int end : new int[] { 0, cut, cut, pcm.length / 2, pcm.length / 2 })
            while (position < end)
            {
                core.acceptFrame(pcm, position * 2);
                int completed = ++position;
                if (completed >= momentary) readouts[(completed - 1) * 2] = core.momentaryPower();
                if (completed >= shortTerm) readouts[(completed - 1) * 2 + 1] = core.shortTermPower();
            }
        assertEquals(pcm.length / 2, core.framesSeen());
        return readouts;
    }
}
