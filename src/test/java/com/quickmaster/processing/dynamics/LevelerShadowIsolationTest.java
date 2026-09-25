package com.quickmaster.processing.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import com.quickmaster.processing.dynamics.leveler.LevelerAnalysisEngine;
import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisSnapshot;

class LevelerShadowIsolationTest
{
    @Test
    void unboundStructuralEvidenceCannotAuthorizeAnyAudibleGain()
    {
        int sampleRate = 48_000;
        float[] pcm = fixture(sampleRate, 8, 2);
        LevelerProcessor processor = new LevelerProcessor();
        processor.prepare(sampleRate, pcm.length);

        processor.analyze(pcm, 2);

        ShadowAnalysisSnapshot shadow = processor.getShadowAnalysis();
        PublishedGain publication = processor.publishedGain();
        assertNotNull(shadow);
        assertEquals(AnalysisStatus.STANDARD_VALIDATION_FAILED, publication.status());
        assertEquals(GainDomain.LEGACY_LINEAR, publication.schedule().domain());
        assertTrue(publication.schedule() instanceof DenseGainSchedule);
        assertSame(DenseGainSchedule.unit(), publication.schedule());
        assertFalse(processor.isAnalyzed());
        assertEquals(com.quickmaster.processing.dynamics.leveler.model.ConformanceState.NOT_RUN,
                shadow.diagnostics().standardValidation().state());
        assertEquals("", shadow.diagnostics().standardValidation().algorithmSha256());
        assertEquals("", shadow.diagnostics().standardValidation().profileSha256());
        assertEquals("", shadow.diagnostics().standardValidation().attestationSha256());
        assertEquals("", shadow.diagnostics().standardValidation().runnerSha256());
        assertEquals(0L, shadow.diagnostics().memoryCounters().denseEnvelopeCount());
        assertEquals(0L, shadow.diagnostics().memoryCounters().denseEnvelopeElements());
        assertEquals(0L, shadow.diagnostics().memoryCounters().retainedPcmRefs());
    }

    @Test
    void invalidAndCancelledAnalysisClearStaleStructuralEvidence()
    {
        int sampleRate = 48_000;
        float[] pcm = fixture(sampleRate, 4, 1);
        LevelerProcessor processor = new LevelerProcessor();
        processor.prepare(sampleRate, pcm.length);
        processor.analyze(pcm, 1);
        ShadowAnalysisSnapshot previous = processor.getShadowAnalysis();
        assertNotNull(previous);

        float[] invalid = pcm.clone();
        invalid[123] = Float.NaN;
        processor.prepare(sampleRate, invalid.length);
        processor.analyze(invalid, 1);

        assertNull(processor.getShadowAnalysis());
        assertEquals(AnalysisStatus.INVALID_INPUT, processor.publishedGain().status());

        CancellationToken token = new CancellationToken();
        token.cancel();
        assertNull(new LevelerAnalysisEngine().analyzeShadow(
                pcm, new AudioFormat(sampleRate, 1, pcm.length), token));
        processor.analyze(pcm, 1, token);
        assertNull(processor.getShadowAnalysis());
        assertEquals(AnalysisStatus.CANCELLED, processor.publishedGain().status());
    }

    @Test
    void adoptionTransfersOneImmutableAnalysisWithANewReceiverGeneration()
    {
        int sampleRate = 48_000;
        float[] firstPcm = fixture(sampleRate, 4, 1);
        float[] secondPcm = fixture(sampleRate, 5, 1);
        LevelerProcessor donor = new LevelerProcessor();
        LevelerProcessor receiver = new LevelerProcessor();
        donor.prepare(sampleRate, firstPcm.length);
        receiver.prepare(sampleRate, secondPcm.length);
        donor.analyze(firstPcm, 1);
        receiver.analyze(secondPcm, 1);
        ShadowAnalysisSnapshot receiverShadow = receiver.getShadowAnalysis();
        PublishedGain donorPublication = donor.publishedGain();

        receiver.adoptEnvelope(donor);

        assertSame(donor.getShadowAnalysis(), receiver.getShadowAnalysis());
        assertFalse(receiverShadow == receiver.getShadowAnalysis());
        assertSame(donorPublication.schedule(), receiver.publishedGain().schedule());
        assertEquals(donorPublication.status(), receiver.publishedGain().status());
        assertTrue(receiver.publishedGain().analysisGeneration() > donorPublication.analysisGeneration());
    }

    @Test
    void ownerFieldIsExactlyPrivateVolatileShadowSnapshot() throws Exception
    {
        Field field = LevelerProcessor.class.getDeclaredField("shadowAnalysis");
        assertEquals(ShadowAnalysisSnapshot.class, field.getType());
        assertTrue(Modifier.isPrivate(field.getModifiers()));
        assertTrue(Modifier.isVolatile(field.getModifiers()));
        assertFalse(Modifier.isStatic(field.getModifiers()));
    }

    private static float[] fixture(int sampleRate, int seconds, int channels)
    {
        int frames = sampleRate * seconds;
        float[] pcm = new float[frames * channels];
        int state = 0x4d303034;
        for (int frame = 0; frame < frames; frame++)
        {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            double noise = ((state >>> 8) & 0xffff) / 32768.0d - 1.0d;
            double transientValue = frame % (sampleRate / 2) < 128 ? 0.15d : 0.0d;
            double tonal = 0.2d * StrictMath.sin(2.0d * StrictMath.PI * 220.0d * frame / sampleRate);
            float left = (float) (tonal + 0.015625d * noise + transientValue);
            pcm[frame * channels] = left;
            if (channels == 2) pcm[frame * channels + 1] = (float) (0.75d * left + 0.00390625d * noise);
        }
        return pcm;
    }
}
