package com.quickmaster.processing.eq;

import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AutoEqCacheTest {
    private static AutoEqProcessor processor(int rate, int length) {
        var p = new AutoEqProcessor(); p.setEnabled(true); p.setAmount(1); p.prepare(rate, length); return p;
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
    @Test void bothControlsArePartOfTheCacheKey() {
        float[] source = new float[24000];
        var random = new java.util.Random(31);
        for (int i = 0; i < source.length; i++) source[i] = (float)(.1 * random.nextGaussian() + .2 * Math.sin(i * .21));
        var first = processor(48000, source.length);
        first.analyze(source, 1);
        float[] base = first.process(source.clone(), 1);
        java.util.List<Consumer<AutoEqProcessor>> changes = java.util.List.of(
                p -> p.setAmount(.3), p -> p.setCutBoost(-1), p -> p.setCutBoost(.5));
        for (int k = 0; k < changes.size(); k++) {
            var reused = processor(48000, source.length);
            reused.analyze(source, 1);
            changes.get(k).accept(reused);
            reused.analyze(source, 1);
            reused.prepare(48000, source.length);
            float[] moved = reused.process(source.clone(), 1);
            var cold = processor(48000, source.length);
            changes.get(k).accept(cold);
            cold.analyze(source, 1);
            assertArrayEquals(cold.process(source.clone(), 1), moved, "change " + k + " is rendered afresh");
            assertFalse(java.util.Arrays.equals(base, moved), "change " + k + " changes the render");
        }
        // The same settings again are served from the render already made.
        var same = processor(48000, source.length);
        same.adopt(first); same.analyze(source, 1);
        assertArrayEquals(base, same.process(source.clone(), 1));
    }
}
