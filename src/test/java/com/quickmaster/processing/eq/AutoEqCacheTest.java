package com.quickmaster.processing.eq;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoEqCacheTest {
    private static AutoEqProcessor processor(int rate, int length) {
        var p = new AutoEqProcessor(); p.setEnabled(true); p.setAmount(.5); p.prepare(rate, length); return p;
    }
    @Test void changesBetweenOldFingerprintProbesCannotReuseOldAudio() {
        int frames = 32768;
        float[] source = new float[frames];
        for (int i = 0; i < frames; i++) source[i] = (float)(.2 * Math.sin(i * .14));
        var reused = processor(48000, frames); reused.analyze(source, 1);
        // Preserve every old signature probe but change all other samples.
        for (int i = 0; i < frames; i++) if (i % (frames / 512) != 0) source[i] *= -.6f;
        reused.analyze(source, 1);
        var cold = processor(48000, frames); cold.analyze(source, 1);
        assertArrayEquals(cold.process(source.clone(), 1), reused.process(source.clone(), 1));
    }
    @Test void sampleRateIsPartOfTheCacheKey() {
        float[] source = new float[32768];
        for (int i = 0; i < source.length; i++) source[i] = (float)(.2 * Math.sin(i * .8));
        var reused = processor(44100, source.length); reused.analyze(source, 1);
        reused.prepare(96000, source.length); reused.analyze(source, 1);
        var cold = processor(96000, source.length); cold.analyze(source, 1);
        assertArrayEquals(cold.process(source.clone(), 1), reused.process(source.clone(), 1));
    }
}
