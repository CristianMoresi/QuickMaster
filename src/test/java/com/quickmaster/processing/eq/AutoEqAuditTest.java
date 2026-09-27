package com.quickmaster.processing.eq;

import org.junit.jupiter.api.Test;
import java.util.Random;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class AutoEqAuditTest {
    private static AutoEqProcessor processor(int rate) {
        var eq = new AutoEqProcessor(); eq.setEnabled(true); eq.setAmount(1); eq.prepare(rate, 0); return eq;
    }
    @Test void polarityAndDualMonoDoNotChangeLinkedCorrectionAtAnySupportedRate() {
        for (int rate : new int[]{8000, 16000, 22050, 32000, 44100, 48000, 96000, 192000}) {
            float[] mono = new float[16385], stereo = new float[mono.length * 2];
            Random random = new Random(823);
            for (int f=0; f<mono.length; f++) {
                mono[f] = (float)(.2 * random.nextGaussian() + .3 * Math.sin(2 * Math.PI * 700 * f / rate));
                stereo[2*f] = mono[f]; stereo[2*f+1] = -mono[f];
            }
            var m = processor(rate); var s = processor(rate);
            m.analyze(mono, 1); s.analyze(stereo, 2);
            float[] left = m.process(mono.clone(), 1), linked = s.process(stereo.clone(), 2);
            for (int f=0; f<mono.length; f++) {
                assertTrue(Float.isFinite(linked[2*f]));
                assertEquals(left[f], linked[2*f], 1e-6, "left at rate " + rate);
                assertEquals(-left[f], linked[2*f+1], 1e-6, "right at rate " + rate);
            }
        }
    }
    @Test void invalidParametersAndPcmAreRejectedAndEmptyAnalysisCannotReplayAnOldTrack() {
        var eq = processor(48000);
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> eq.setAmount(bad));
            assertThrows(IllegalArgumentException.class, () -> eq.setAttackSec(bad));
            assertThrows(IllegalArgumentException.class, () -> eq.setReleaseSec(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> eq.prepare(0, 0));
        assertThrows(IllegalArgumentException.class, () -> eq.analyze(new float[]{.1f}, 2));
        assertThrows(IllegalArgumentException.class, () -> eq.analyze(new float[]{Float.NaN}, 1));
        assertThrows(IllegalArgumentException.class, () -> eq.analyze(new float[4], 4));
        eq.analyze(new float[]{.5f, .5f}, 1); eq.analyze(new float[0], 1);
        assertArrayEquals(new float[2], eq.process(new float[2], 1));
    }
    @Test void zeroAmountIsBitExactAndAnalysisIsInterruptible() {
        var eq = processor(48000); eq.setAmount(0);
        float[] x = {.31f, -.9f, .7f, -.13f}; eq.analyze(x,1);
        assertArrayEquals(x, eq.process(x.clone(),1));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> eq.analyze(new float[48000],1));
        } finally { Thread.interrupted(); }
    }
}
