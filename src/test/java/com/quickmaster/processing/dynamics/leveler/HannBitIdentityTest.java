package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ComparisonTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;

/** Exact regression against the two source revisions frozen before local Hann reuse. */
class HannBitIdentityTest
{
    @Test
    void everyPublishedBitMatchesFrozenDirectHannAcrossFormatsAndSignals()
    {
        for (int rate : new int[] { 8_000, 44_100, 48_000, 96_000, 192_000 })
            for (int channels : new int[] { 1, 2 })
                for (int signal = 0; signal < (channels == 1 ? 2 : 3); signal++)
                    assertFixture(fixture(rate, channels, (int) StrictMath.round(rate * .625d) + 7, signal));
    }

    @Test
    void oneFrameAndExactOrPartialCellsPreserveInputAndPublishedBits()
    {
        for (int frames : new int[] { 1, 1_199, 1_200, 1_201, 5_999, 6_000, 6_001, 23_999, 24_000, 24_001 })
            assertFixture(fixture(48_000, 2, frames, 1));
        Fixture signed = fixture(8_000, 2, 37, 1);
        float[] special = { -0.0f, 0.0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL, -Float.MIN_NORMAL };
        for (int i = 0; i < signed.pcm().length; i++) signed.pcm()[i] = special[i % special.length];
        assertFixture(signed);
    }

    @Test
    void invalidInputsCancellationAndMismatchedLoudnessKeepExistingBehavior()
    {
        ComparisonFeatureExtractor comparison = new ComparisonFeatureExtractor();
        StructuralFeatureExtractor structural = new StructuralFeatureExtractor();
        Fixture valid = fixture(48_000, 2, 31, 0);
        assertNull(comparison.extract(null, valid.format(), null));
        assertNull(comparison.extract(valid.pcm(), null, null));
        assertNull(structural.extract(null, valid.format(), valid.loudness(), null));
        assertNull(structural.extract(valid.pcm(), null, valid.loudness(), null));
        assertNull(structural.extract(valid.pcm(), valid.format(), null, null));
        for (float[] invalid : new float[][] { new float[2],
                altered(valid.pcm(), Float.NaN), altered(valid.pcm(), Float.POSITIVE_INFINITY),
                altered(valid.pcm(), Float.NEGATIVE_INFINITY) })
        {
            assertNull(comparison.extract(invalid, valid.format(), null));
            assertNull(structural.extract(invalid, valid.format(), valid.loudness(), null));
        }
        for (int rate : new int[] { 1, 39, 40, Integer.MAX_VALUE })
            assertNull(comparison.extract(new float[1], new AudioFormat(rate, 1, 1), null), "rate=" + rate);
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        assertNull(comparison.extract(valid.pcm(), valid.format(), cancelled));
        assertNull(structural.extract(valid.pcm(), valid.format(), valid.loudness(), cancelled));
        // The existing structural activity contract accepts a shorter/different loudness extent.
        LoudnessTimeline mismatched = loudness(8_000, 1);
        assertStructural(new HannBaselineStructuralExtractor().extract(valid.pcm(), valid.format(), mismatched, null),
                structural.extract(valid.pcm(), valid.format(), mismatched, null), "mismatched loudness extent");
    }

    @Test
    void concurrentCallsOnTheSameExtractorsMatchSequentialReferences() throws Exception
    {
        ComparisonFeatureExtractor comparison = new ComparisonFeatureExtractor();
        StructuralFeatureExtractor structural = new StructuralFeatureExtractor();
        Fixture first = fixture(44_100, 2, 44_107, 0), second = fixture(96_000, 1, 96_007, 1);
        ComparisonTimeline firstComparison = new HannBaselineComparisonExtractor().extract(first.pcm(), first.format(), null);
        ComparisonTimeline secondComparison = new HannBaselineComparisonExtractor().extract(second.pcm(), second.format(), null);
        FeatureTimeline firstStructural = new HannBaselineStructuralExtractor().extract(first.pcm(), first.format(), first.loudness(), null);
        FeatureTimeline secondStructural = new HannBaselineStructuralExtractor().extract(second.pcm(), second.format(), second.loudness(), null);
        var executor = Executors.newFixedThreadPool(2);
        try
        {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var a = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                CancellationToken token = new CancellationToken();
                assertComparison(firstComparison, comparison.extract(first.pcm(), first.format(), token), "concurrent first");
                assertStructural(firstStructural, structural.extract(first.pcm(), first.format(), first.loudness(), token), "concurrent first");
                token.cancel();
                assertNull(comparison.extract(first.pcm(), first.format(), token));
                assertNull(structural.extract(first.pcm(), first.format(), first.loudness(), token));
                return null;
            });
            var b = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int iteration = 0; iteration < 3; iteration++)
                {
                    CancellationToken token = new CancellationToken();
                    assertComparison(secondComparison, comparison.extract(second.pcm(), second.format(), token), "concurrent second");
                    assertStructural(secondStructural, structural.extract(second.pcm(), second.format(), second.loudness(), token), "concurrent second");
                    assertFalse(token.isCancelled());
                }
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void floatCoefficientsAndPrematureReadyHaveDiscriminatingBitOracles() throws Exception
    {
        String source = HannMutant.source("StructuralFeatureExtractor");
        String floatWindow = HannMutant.replaceOnce(source, "double[] hannWindow = new double[fftSize];",
                "float[] hannWindow = new float[fftSize];");
        floatWindow = HannMutant.replaceOnce(floatWindow, "hannWindow[n] = 0.5d - 0.5d * StrictMath.cos(",
                "hannWindow[n] = (float) (0.5d - 0.5d * StrictMath.cos(");
        floatWindow = HannMutant.replaceOnce(floatWindow, "2.0d * StrictMath.PI * n / (fftSize - 1.0d));",
                "2.0d * StrictMath.PI * n / (fftSize - 1.0d)));");
        String premature = HannMutant.replaceOnce(source, "boolean hannReady = false;", "boolean hannReady = true;");
        Fixture fixture = fixture(48_000, 2, 30_007, 0);
        FeatureTimeline expected = new HannBaselineStructuralExtractor().extract(
                fixture.pcm(), fixture.format(), fixture.loudness(), null);
        for (String[] neighbor : new String[][] { { "HannFloatStructuralMutant", floatWindow },
                { "HannPrematureStructuralMutant", premature } })
            try (var mutant = HannMutant.compile("StructuralFeatureExtractor", neighbor[0], neighbor[1]))
            {
                FeatureTimeline actual = (FeatureTimeline) mutant.extract(fixture.pcm(), fixture.format(), fixture.loudness(), null);
                AssertionError cause = assertThrows(AssertionError.class, () -> assertStructural(expected, actual, neighbor[0]));
                assertTrue(cause.getMessage().contains("raw bits"), cause.getMessage());
                System.out.println("HANN_MUTANT_CAUSE " + cause.getMessage());
            }
        String comparison = HannMutant.source("ComparisonFeatureExtractor");
        comparison = HannMutant.replaceOnce(comparison, "double[] hannWindow = new double[support];",
                "float[] hannWindow = new float[support];");
        comparison = HannMutant.replaceOnce(comparison, "hannWindow[n] = hann;", "hannWindow[n] = (float) hann;");
        try (var mutant = HannMutant.compile("ComparisonFeatureExtractor", "HannFloatComparisonMutant", comparison))
        {
            Fixture discriminating = fixture(8_000, 2, 5_007, 2);
            ComparisonTimeline reference = new HannBaselineComparisonExtractor().extract(
                    discriminating.pcm(), discriminating.format(), null);
            ComparisonTimeline actual = (ComparisonTimeline) mutant.extract(discriminating.pcm(), discriminating.format(), null);
            AssertionError cause = assertThrows(AssertionError.class,
                    () -> assertComparison(reference, actual, "float comparison 8000/2"));
            assertTrue(cause.getMessage().contains("short packed 1/22"), cause.getMessage());
            System.out.println("HANN_MUTANT_CAUSE " + cause.getMessage());
        }
    }

    private static void assertFixture(Fixture fixture)
    {
        String label = fixture.format().sampleRateHz() + "/" + fixture.format().channels() + "/" + fixture.format().frames();
        int[] input = rawBits(fixture.pcm());
        ComparisonTimeline expectedComparison = new HannBaselineComparisonExtractor().extract(fixture.pcm(), fixture.format(), null);
        FeatureTimeline expectedStructural = new HannBaselineStructuralExtractor().extract(fixture.pcm(), fixture.format(), fixture.loudness(), null);
        ComparisonFeatureExtractor comparison = new ComparisonFeatureExtractor();
        StructuralFeatureExtractor structural = new StructuralFeatureExtractor();
        for (CancellationToken token : new CancellationToken[] { null, new CancellationToken(), new CancellationToken() })
        {
            assertComparison(expectedComparison, comparison.extract(fixture.pcm(), fixture.format(), token), label);
            assertStructural(expectedStructural, structural.extract(fixture.pcm(), fixture.format(), fixture.loudness(), token), label);
            assertArrayEquals(input, rawBits(fixture.pcm()), label + " PCM raw bits");
        }
    }

    static void assertComparison(ComparisonTimeline expected, ComparisonTimeline actual, String label)
    {
        if (expected == null) { assertNull(actual, label); return; }
        assertNotNull(actual, label);
        assertSame(expected.format(), actual.format(), label + " format identity");
        assertEquals(expected.shortCount(), actual.shortCount(), label + " short count");
        assertEquals(expected.longCount(), actual.longCount(), label + " long count");
        for (int row = 0; row < expected.shortCount(); row++)
        {
            assertEquals(expected.shortFlagsAt(row), actual.shortFlagsAt(row), label + " short flags " + row);
            for (int component = 0; component < 52; component++)
                assertEquals(expected.shortValueAt(row, component), actual.shortValueAt(row, component),
                        label + " short packed " + row + "/" + component);
        }
        for (int row = 0; row < expected.longCount(); row++)
        {
            assertEquals(expected.longFlagsAt(row), actual.longFlagsAt(row), label + " long flags " + row);
            for (int component = 0; component < 36; component++)
                assertEquals(expected.longValueAt(row, component), actual.longValueAt(row, component),
                        label + " long packed " + row + "/" + component);
        }
    }

    static void assertStructural(FeatureTimeline expected, FeatureTimeline actual, String label)
    {
        if (expected == null) { assertNull(actual, label); return; }
        assertNotNull(actual, label);
        assertEquals(expected.hopFrames(), actual.hopFrames(), label + " hop");
        assertEquals(expected.size(), actual.size(), label + " frame count");
        for (int row = 0; row < expected.size(); row++)
        {
            var a = expected.frame(row);
            var b = actual.frame(row);
            assertEquals(a.centerFrame(), b.centerFrame(), label + " center " + row);
            for (int component = 0; component < 12; component++)
                assertBits(a.chromaAt(component), b.chromaAt(component), label + " chroma " + row + "/" + component);
            for (int component = 0; component < 8; component++)
                assertBits(a.spectralAt(component), b.spectralAt(component), label + " spectral " + row + "/" + component);
            assertBits(a.onsetFlux(), b.onsetFlux(), label + " flux " + row);
            assertBits(a.activity(), b.activity(), label + " activity " + row);
        }
    }

    private static void assertBits(double expected, double actual, String label)
    {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), label + " raw bits");
    }

    static Fixture fixture(int rate, int channels, int frames, int signal)
    {
        float[] pcm = new float[frames * channels];
        long noise = 0x1a2b3c4dL;
        int fftSize = 2_048;
        while (fftSize < rate * .0464d && fftSize < 8_192) fftSize *= 2;
        int fftHop = fftSize / 2, structuralHop = (int) StrictMath.round(rate * .5d);
        for (int frame = 0; frame < frames; frame++)
        {
            noise ^= noise << 13;
            noise ^= noise >>> 7;
            noise ^= noise << 17;
            double value = signal == 1 ? (frame == fftHop - 1 || frame == fftHop
                    || frame == structuralHop - 1 || frame == structuralHop || frame == frames - 1 ? .37d : 0.0d)
                    : .17d * StrictMath.sin(2.0d * StrictMath.PI * 440.0d * frame / rate)
                            + .11d * StrictMath.cos(2.0d * StrictMath.PI * 731.25d * frame / rate)
                            + ((noise >>> 40) / 16_777_216.0d - .5d) * .035d;
            pcm[frame * channels] = (float) value;
            if (channels == 2)
                pcm[frame * channels + 1] = signal == 2 ? -pcm[frame * channels]
                        : (float) (-.63d * value + (signal == 1 ? 0.0d : .04d * StrictMath.sin(frame * .017d)));
        }
        return new Fixture(pcm, new AudioFormat(rate, channels, frames), loudness(rate, frames));
    }

    private static LoudnessTimeline loudness(int rate, int frames)
    {
        int hop = Math.max(1, (int) StrictMath.round(rate * .1d));
        int count = (frames + hop - 1) / hop;
        double[] powers = new double[count], lufs = new double[count];
        Arrays.fill(powers, .01d);
        Arrays.fill(lufs, -20.691d);
        BitSet valid = new BitSet(count);
        valid.set(0, count);
        return new LoudnessTimeline(Math.max(1, (int) StrictMath.round(rate * .4d)),
                rate * 3, hop, powers, valid, lufs, valid, MeasuredLoudness.absent());
    }

    private static int[] rawBits(float[] pcm)
    {
        int[] result = new int[pcm.length];
        for (int i = 0; i < pcm.length; i++) result[i] = Float.floatToRawIntBits(pcm[i]);
        return result;
    }

    private static float[] altered(float[] source, float value)
    {
        float[] copy = source.clone();
        copy[0] = value;
        return copy;
    }

    record Fixture(float[] pcm, AudioFormat format, LoudnessTimeline loudness) { }
}
