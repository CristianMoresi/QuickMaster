package com.quickmaster.processing.dynamics;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Unbound source-tree tests are fail-closed controls, never substitutes for bound positive audio. */
class LevelerActivePublicationTest {
    private static float[] tone() {
        float[] input = new float[48_000];
        for (int i = 0; i < input.length; i++) input[i] = (float) (.1 * StrictMath.sin(2 * Math.PI * 440 * i / 48_000));
        return input;
    }

    @Test void unboundAnalysisCannotPublishLegacyOrActiveCorrection() {
        LevelerProcessor stage = new LevelerProcessor();
        float[] input = tone();
        stage.setEnabled(true); stage.prepare(48_000, input.length); stage.analyze(input, 1);
        assertNotNull(stage.getShadowAnalysis());
        assertEquals(AnalysisStatus.STANDARD_VALIDATION_FAILED, stage.publishedGain().status());
        assertFalse(stage.isAnalyzed());
        assertNull(stage.gainEnv);
        float[] output = input.clone(); stage.process(output, 1);
        for (int i = 0; i < input.length; i++) assertEquals(Float.floatToRawIntBits(input[i]), Float.floatToRawIntBits(output[i]));
    }

    @Test void exactInputReuseSharesImmutableAnalysisButOneBitForcesNewAnalysis() {
        LevelerProcessor first = new LevelerProcessor();
        float[] input = tone();
        first.prepare(48_000, input.length); first.analyze(input, 1);
        assertNotNull(first.getShadowAnalysis());
        LevelerProcessor second = first.forkForAnalysis(.75, .25);
        second.prepare(48_000, input.length); second.analyze(input.clone(), 1);
        assertSame(first.getShadowAnalysis(), second.getShadowAnalysis());
        float[] changed = input.clone(); changed[100] = Math.nextUp(changed[100]);
        second.analyze(changed, 1);
        assertNotSame(first.getShadowAnalysis(), second.getShadowAnalysis());
        assertFalse(Arrays.equals(first.getShadowAnalysis().cache().copyPcmFingerprintSha256(),
                second.getShadowAnalysis().cache().copyPcmFingerprintSha256()));
    }

    @Test void invalidInputAndClearDropThePriorAnalysisAndPublication() {
        LevelerProcessor stage = new LevelerProcessor();
        float[] input = tone();
        stage.prepare(48_000, input.length); stage.analyze(input, 1);
        assertNotNull(stage.getShadowAnalysis());
        stage.analyze(new float[]{Float.NaN}, 1);
        assertNull(stage.getShadowAnalysis());
        assertEquals(AnalysisStatus.INVALID_INPUT, stage.publishedGain().status());
        stage.analyze(input, 1); stage.clearAnalysis();
        assertNull(stage.getShadowAnalysis());
        assertEquals(AnalysisStatus.CLEARED, stage.publishedGain().status());
    }

    @Test void controlEditsInvalidateAndNonFiniteValuesPreservePriorControls() {
        LevelerProcessor stage = new LevelerProcessor();
        stage.setLeveling(.8); stage.setSpeed(.2);
        long before = stage.publishedGain().analysisGeneration();
        stage.setLeveling(0);
        assertTrue(stage.publishedGain().analysisGeneration() > before);
        assertEquals(AnalysisStatus.UNIT, stage.publishedGain().status());
        for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> stage.setLeveling(invalid));
            assertThrows(IllegalArgumentException.class, () -> stage.setSpeed(invalid));
        }
        assertEquals(0, stage.getLeveling()); assertEquals(.2, stage.getSpeed());
    }

    @Test void staleControlSnapshotDoesNotReplaceCurrentAnalysis() {
        LevelerProcessor live = new LevelerProcessor();
        float[] input = tone();
        LevelerProcessor worker = live.forkForAnalysis(live.getLeveling(), live.getSpeed());
        worker.prepare(48_000, input.length); worker.analyze(input, 1);
        live.setLeveling(.9);
        PublishedGain current = live.publishedGain();
        live.adoptEnvelope(worker);
        assertSame(current, live.publishedGain());
        assertNull(live.getShadowAnalysis());
        assertThrows(IllegalArgumentException.class, () -> live.adoptEnvelope(new BeatCompProcessor()));
    }
}
