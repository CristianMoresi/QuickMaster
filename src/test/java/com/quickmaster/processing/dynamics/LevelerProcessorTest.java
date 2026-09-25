package com.quickmaster.processing.dynamics;

import com.quickmaster.processing.dynamics.leveler.RuntimeLevelerAcceptanceTest;
import com.quickmaster.processing.dynamics.leveler.RuntimeLevelerAcceptanceTest.BoundProcessor;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Active own-JAR integration; former short-intro median-rider expectations are intentionally replaced. */
class LevelerProcessorTest {
    private static final int RATE = 48000;
    private static float[] positive;
    @BeforeAll static void prepareAuthenticPackageAndMusicalInput() throws Exception {
        RuntimeLevelerAcceptanceTest.candidate();
        positive = RuntimeLevelerAcceptanceTest.positivePcm(RATE, 1);
    }
    @AfterAll static void releaseFixture() { positive = null; }
    private static BoundProcessor analyzed(float[] pcm, double leveling, double speed) throws Exception {
        BoundProcessor processor = RuntimeLevelerAcceptanceTest.processor();
        processor.controls(leveling, speed);
        processor.analyze(pcm, RATE, 1);
        assertEquals("PASSED", processor.conformance(), "Only the actual current package can authorize this test");
        return processor;
    }
    private static void rawEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            if (Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) fail("Changed raw sample " + i);
    }
    private static double renderedGain(BoundProcessor processor, int second) throws Exception {
        float[] input = Arrays.copyOfRange(positive, second * RATE, (second + 1) * RATE);
        float[] output = processor.render(input, 1, (long)second * RATE);
        double inputEnergy = 0, outputEnergy = 0;
        for (int i = 0; i < input.length; i++) {
            assertTrue(Float.isFinite(output[i]));
            inputEnergy += (double)input[i] * input[i];
            outputEnergy += (double)output[i] * output[i];
        }
        assertTrue(inputEnergy > 0);
        return 10 * StrictMath.log10(outputEnergy / inputEnergy);
    }
    @Test void comparableLoudChorusIsActuallyAttenuatedAndIntroBitsAreUntouched() throws Exception {
        try (var processor = analyzed(positive, 1, .5)) {
            assertEquals("STRUCTURAL_READY", processor.status());
            assertTrue(renderedGain(processor, 56) < -.8);
            float[] intro = Arrays.copyOfRange(positive, 0, 20 * RATE);
            rawEquals(intro, processor.render(intro, 1, 0));
        }
    }
    @Test void comparableQuietChorusIsActuallyBoostedAndOutroBitsAreUntouched() throws Exception {
        try (var processor = analyzed(positive, 1, .5)) {
            assertTrue(renderedGain(processor, 104) > .8);
            float[] outro = Arrays.copyOfRange(positive, 116 * RATE, positive.length);
            rawEquals(outro, processor.render(outro, 1, 116L * RATE));
        }
    }
    @Test void amountScalesActualGainAndInvalidatesStalePublicationImmediately() throws Exception {
        try (var processor = analyzed(positive, .5, .5)) {
            double half = renderedGain(processor, 104);
            processor.controls(1, .5);
            assertEquals("UNIT", processor.status());
            assertEquals(0, renderedGain(processor, 104), 0);
            processor.analyze(positive, RATE, 1);
            double full = renderedGain(processor, 104);
            assertTrue(half > .3 && full > .8 && full > half + .3);
        }
    }
    @Test void speedChangesTheActualBoundSparseTransition() throws Exception {
        try (var processor = analyzed(positive, 1, 0)) {
            double slow = processor.gainAt(44.5 * RATE);
            processor.controls(1, 1);
            processor.analyze(positive, RATE, 1);
            double fast = processor.gainAt(44.5 * RATE);
            assertTrue(fast < slow - .05, "Faster negative transition must settle sooner: " + slow + " / " + fast);
            assertEquals(processor.gainAt(56L * RATE), renderedGain(processor, 56), 1e-6);
        }
    }
    @Test void aQuietShortIntroIsNotPromotedToTheLouderBody() throws Exception {
        float[] pcm = twoPart(.22, .8);
        try (var processor = analyzed(pcm, 1, .5)) {
            assertEquals("STRUCTURAL_READY", processor.status());
            rawEquals(pcm, processor.render(pcm, 1, 0));
        }
    }
    @Test void aLoudShortIntroIsNotFlattenedToTheQuieterBody() throws Exception {
        float[] pcm = twoPart(.8, .22);
        try (var processor = analyzed(pcm, 1, .5)) {
            assertEquals("STRUCTURAL_READY", processor.status());
            rawEquals(pcm, processor.render(pcm, 1, 0));
        }
    }
    @Test void anUnrepairableProtectedPeakIsExplicitlyInfeasibleAndNeverLimited() throws Exception {
        float[] pcm = positive.clone(); pcm[5 * RATE] = 1.2f;
        try (var processor = analyzed(pcm, 1, .5)) {
            assertEquals("INFEASIBLE_INPUT_BASELINE", processor.status());
            rawEquals(pcm, processor.render(pcm, 1, 0));
        }
    }
    @Test void disabledAndZeroAmountRemainRawBitExactWithAnOtherwiseActiveAnalysis() throws Exception {
        try (var processor = analyzed(positive, 1, .5)) {
            assertTrue(renderedGain(processor, 104) > .8);
            processor.enabled(false);
            rawEquals(positive, processor.render(positive, 1, 0));
            processor.controls(0, .5);
            processor.analyze(positive, RATE, 1);
            assertEquals("UNIT", processor.status());
            rawEquals(positive, processor.render(positive, 1, 0));
        }
    }
    private static float[] twoPart(double intro, double body) {
        float[] pcm = new float[20 * RATE];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float)((i < 6 * RATE ? intro : body) * StrictMath.sin(2 * StrictMath.PI * 220 * i / RATE));
        return pcm;
    }
}
