package com.quickmaster.processing.dynamics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("deprecation")
class DenseGainScheduleCompatibilityTest
{
    private static final int RATE = 48_000;
    private static final float[] GOLDEN_ENV = {
            0x1.529a5p-1f, 0x1.0p-1f, 0x1.cp-1f, 0x1.2p0f
    };

    private static final class FixedDynamics extends AnalysisDynamicsProcessor
    {
        private float[] nextEnvelope;
        private int lastMappedIdentity;

        FixedDynamics(float... envelope)
        {
            nextEnvelope = envelope.clone();
            setEnabled(true);
        }

        void envelope(float... envelope)
        {
            nextEnvelope = envelope.clone();
        }

        float[] legacyEnvelope()
        {
            return gainEnv;
        }

        int lastMappedIdentity()
        {
            return lastMappedIdentity;
        }

        void poisonLegacySlot(float... envelope)
        {
            gainEnv = envelope;
        }

        void remapNow()
        {
            remap();
        }

        @Override
        protected void computeFeatures(float[] samples, int channels, int sampleRate, int frames)
        {
            // The envelope itself is the deterministic test feature.
        }

        @Override
        protected void mapFeaturesToGain()
        {
            float[] mapped = nextEnvelope.clone();
            lastMappedIdentity = System.identityHashCode(mapped);
            gainEnv = mapped;
        }
    }

    @Test
    @DisplayName("Dense preserves legacy clamps and fractional interpolation at 1x, 2x and 4x")
    void normativeLookupTableIsRawBitExact()
    {
        double[] sourcePositions = { -2, -1, 0, .25, .5, .75, 1.25, 1.5, 1.75, 2.5, 3, 4 };
        int[] expectedBits = {
                0x3f294d28, 0x3f294d28, 0x3f294d28, 0x3f1ef9de,
                0x3f14a694, 0x3f0a534a, 0x3f180000, 0x3f300000,
                0x3f480000, 0x3f800000, 0x3f900000, 0x3f900000
        };

        for (int factor : new int[] { 1, 2, 4 })
        {
            FixedDynamics processor = analyzed(GOLDEN_ENV);
            processor.prepare(RATE * factor, 64L);
            for (int i = 0; i < sourcePositions.length; i++)
            {
                double prepared = sourcePositions[i] * factor;
                if (prepared != Math.rint(prepared)) continue;
                processor.setPlaybackPosition((long) prepared);
                float[] sample = { 1.0f };
                processor.process(sample, 1);
                assertEquals(expectedBits[i], Float.floatToRawIntBits(sample[0]),
                        "factor=" + factor + ", sourcePos=" + sourcePositions[i]);
            }
        }
    }

    @Test
    @DisplayName("Dense multiplies in float and never round-trips through dB")
    void mandatoryOneUlpCounterexampleAndMeter()
    {
        FixedDynamics processor = analyzed(0x1.529a5p-1f);
        processor.prepare(RATE, 1L);
        float[] sample = { 0x1.7p-1f };
        processor.process(sample, 1);

        assertEquals(0x3ef35eea, Float.floatToRawIntBits(sample[0]));
        assertNotEquals(0x3ef35ee9, Float.floatToRawIntBits(sample[0]));
        assertEquals(0xc00cbb92e1bffe12L,
                Double.doubleToRawLongBits(processor.getGainReductionDb()));
    }

    @Test
    @DisplayName("Dense exclusively owns the transferred O(F) array after atomic publication")
    void publicationValidationAndOwnershipTransfer() throws Exception
    {
        FixedDynamics processor = analyzed(.5f, .75f, 1.0f, 1.25f);
        PublishedGain publication = processor.publishedGain();

        assertEquals(AnalysisStatus.LEGACY_READY, publication.status());
        assertEquals(GainDomain.LEGACY_LINEAR, publication.schedule().domain());
        assertEquals(4L, publication.schedule().sourceFrames());
        assertTrue(PublishedGain.class.isRecord());
        for (Field field : PublishedGain.class.getDeclaredFields())
        {
            assertTrue(Modifier.isFinal(field.getModifiers()), field.getName());
        }

        Field envelopeField = DenseGainSchedule.class.getDeclaredField("sampleEnv");
        envelopeField.setAccessible(true);
        float[] ownedEnvelope = (float[]) envelopeField.get(publication.schedule());
        assertEquals(processor.lastMappedIdentity(), System.identityHashCode(ownedEnvelope),
                "Dense must own the exact mapper allocation, not an O(F) copy");
        assertNull(processor.legacyEnvelope(),
                "the protected legacy transfer slot must be cut before publication");

        long generation = publication.analysisGeneration();
        processor.poisonLegacySlot(.25f, .25f, .25f, .25f);
        float[] afterLegacyReplacement = { 1.0f };
        processor.process(afterLegacyReplacement, 1);
        assertEquals(Float.floatToRawIntBits(.5f),
                Float.floatToRawIntBits(afterLegacyReplacement[0]));
        assertSame(publication, processor.publishedGain());
        assertEquals(generation, processor.publishedGain().analysisGeneration());

        processor.poisonLegacySlot(Float.NaN);
        processor.setPlaybackPosition(0L);
        float[] afterNanPoison = { 1.0f };
        processor.process(afterNanPoison, 1);
        assertEquals(Float.floatToRawIntBits(.5f), Float.floatToRawIntBits(afterNanPoison[0]),
                "a NaN written through the deprecated field cannot contaminate the schedule");
        assertTrue(Float.isFinite(afterNanPoison[0]));

        processor.envelope(.625f, .75f, 1.0f, 1.25f);
        processor.remapNow();
        assertNull(processor.legacyEnvelope(), "remap must transfer and cut its fresh array too");
        assertEquals(generation + 1L, processor.publishedGain().analysisGeneration());
        processor.setPlaybackPosition(0L);
        float[] remapped = { 1.0f };
        processor.process(remapped, 1);
        assertEquals(Float.floatToRawIntBits(.625f), Float.floatToRawIntBits(remapped[0]));

        assertThrows(IllegalArgumentException.class,
                () -> new DenseGainSchedule(Double.NaN, new float[] { 1.0f }));
        assertThrows(IllegalArgumentException.class,
                () -> new DenseGainSchedule(RATE, new float[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new DenseGainSchedule(RATE, new float[] { Float.NaN }));
        assertThrows(IllegalArgumentException.class,
                () -> new PublishedGain(DenseGainSchedule.unit(), RATE, 3, 1,
                        AnalysisStatus.LEGACY_READY));
        assertThrows(IllegalArgumentException.class,
                () -> PublishedGain.unit(1, AnalysisStatus.LEGACY_READY));
    }

    @Test
    @DisplayName("prepare-analyze-prepare, seek, adoption and invalid formats keep one coherent publication")
    void lifecycleAndSafeFallbacks()
    {
        FixedDynamics source = analyzed(.25f, .5f, .75f, 1.0f);
        PublishedGain first = source.publishedGain();
        source.prepare(RATE * 2, 8L);
        assertSame(first, source.publishedGain(), "second prepare must retain analysis");
        source.setPlaybackPosition(-2L);
        float[] negative = { 1.0f };
        source.process(negative, 1);
        assertEquals(Float.floatToRawIntBits(.25f), Float.floatToRawIntBits(negative[0]));

        FixedDynamics destination = analyzed(.9f);
        destination.poisonLegacySlot(Float.NaN);
        destination.adoptEnvelope(source);
        // The schedule stays immutable/shared; adoption now has a receiver-local
        // generation so two independent analysis forks cannot reuse a stale cursor.
        assertSame(source.publishedGain().schedule(), destination.publishedGain().schedule());
        assertEquals(source.publishedGain().sourceRateHz(), destination.publishedGain().sourceRateHz());
        assertEquals(source.publishedGain().sourceChannels(), destination.publishedGain().sourceChannels());
        assertEquals(source.publishedGain().status(), destination.publishedGain().status());
        assertTrue(destination.publishedGain().analysisGeneration() > source.publishedGain().analysisGeneration());
        assertNull(destination.legacyEnvelope(), "adoption must not recreate a mutable alias");
        destination.prepare(RATE, 4L);
        float[] adopted = { 1.0f };
        destination.process(adopted, 1);
        assertEquals(Float.floatToRawIntBits(.25f), Float.floatToRawIntBits(adopted[0]));

        destination.prepare(RATE, 4L);
        destination.analyze(new float[] { Float.NaN }, 1);
        assertEquals(AnalysisStatus.INVALID_INPUT, destination.publishedGain().status());
        float[] invalidAnalysis = { .75f };
        destination.process(invalidAnalysis, 1);
        assertEquals(Float.floatToRawIntBits(.75f), Float.floatToRawIntBits(invalidAnalysis[0]));
        assertEquals(0L, Double.doubleToRawLongBits(destination.getGainReductionDb()));

        FixedDynamics valid = analyzed(.25f, .5f, .75f);
        valid.prepare(RATE, 4L);
        float[] wrongChannels = { .2f, .3f, .4f, .5f };
        int[] originalBits = rawBits(wrongChannels);
        valid.process(wrongChannels, 2);
        assertTrue(Arrays.equals(originalBits, rawBits(wrongChannels)));
        float[] afterMismatch = { 1.0f };
        valid.process(afterMismatch, 1);
        assertEquals(Float.floatToRawIntBits(.75f), Float.floatToRawIntBits(afterMismatch[0]),
                "a bypassed format-mismatch block must still advance its frame clock");
        float[] malformed = { .2f, .3f, .4f };
        int[] malformedBits = rawBits(malformed);
        valid.process(malformed, 2);
        assertTrue(Arrays.equals(malformedBits, rawBits(malformed)));

        float[] nonFinite = { Float.intBitsToFloat(0x7fc01234), .5f };
        int[] nonFiniteBits = rawBits(nonFinite);
        valid.process(nonFinite, 1);
        assertTrue(Arrays.equals(nonFiniteBits, rawBits(nonFinite)),
                "a non-finite block is bypassed, not partially multiplied");
        assertEquals(0.0, valid.getGainReductionDb());
    }

    @Test
    @DisplayName("A concurrent adoption cannot mix publications inside one process block")
    void oneSnapshotPerBlockUnderConcurrentAdoption() throws Exception
    {
        FixedDynamics destination = analyzed(.25f);
        FixedDynamics replacement = analyzed(.75f);
        destination.prepare(RATE, 5_000_000L);
        float[] block = new float[5_000_000];
        Arrays.fill(block, 1.0f);
        CountDownLatch started = new CountDownLatch(1);

        Thread render = new Thread(() ->
        {
            started.countDown();
            destination.process(block, 1);
        }, "dense-publication-test");
        render.start();
        started.await();
        destination.poisonLegacySlot(Float.NaN);
        destination.adoptEnvelope(replacement);
        render.join();

        assertNull(destination.legacyEnvelope(), "concurrent adoption must cut legacy poison");

        int first = Float.floatToRawIntBits(block[0]);
        assertTrue(first == Float.floatToRawIntBits(.25f)
                || first == Float.floatToRawIntBits(.75f));
        for (float value : block)
        {
            assertEquals(first, Float.floatToRawIntBits(value),
                    "a block must contain either the old or the new publication, never both");
        }
    }

    @Test
    @DisplayName("Dense process performs no allocation proportional to samples")
    void processDoesNotAllocatePerSample()
    {
        java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
        assertTrue(standard instanceof com.sun.management.ThreadMXBean,
                "HotSpot allocation accounting is required by the Windows delivery runtime");
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) standard;
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);

        FixedDynamics processor = analyzed(.75f);
        processor.prepare(RATE, 1_000_000L);
        float[] block = new float[64];
        Arrays.fill(block, 1.0f);
        for (int i = 0; i < 10_000; i++) processor.process(block, 1);

        long threadId = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < 10_000; i++) processor.process(block, 1);
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;
        assertTrue(allocated <= 1_024L, "process allocated " + allocated + " bytes");
    }

    private static FixedDynamics analyzed(float... envelope)
    {
        FixedDynamics processor = new FixedDynamics(envelope);
        processor.prepare(RATE, envelope.length);
        processor.analyze(new float[envelope.length], 1);
        return processor;
    }

    private static int[] rawBits(float[] values)
    {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) bits[i] = Float.floatToRawIntBits(values[i]);
        return bits;
    }
}
