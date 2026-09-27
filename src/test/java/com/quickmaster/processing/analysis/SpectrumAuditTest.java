package com.quickmaster.processing.analysis;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumAuditTest {
    private static float[] tone(int rate, int channels, boolean invert) {
        float[] pcm = new float[32768 * channels];
        for (int f = 0; f < 32768; f++) for (int c = 0; c < channels; c++)
            pcm[f * channels + c] = (float) (.5 * Math.sin(2 * Math.PI * 1000 * f / rate) * (invert && c == 1 ? -1 : 1));
        return pcm;
    }

    @Test void everySupportedRateIsFiniteAndAboveNyquistIsAbsent() {
        for (int rate : new int[]{8000, 16000, 22050, 32000, 44100, 48000, 96000, 192000}) {
            float[] signal = tone(rate, 1, false);
            SpectrumAnalysis spectrum = new SpectrumAnalysis(); spectrum.analyze(signal, 1, rate);
            LiveSpectrum live = new LiveSpectrum(); live.setSampleRate(rate); live.push(signal, 1); live.update();
            assertTrue(spectrum.isReady()); assertTrue(live.isReady());
            assertTrue(Double.isFinite(spectrum.levelDbAt(1000))); assertTrue(Double.isFinite(live.levelDbAt(1000)));
            assertEquals(-120, spectrum.levelDbAt(rate * .51)); assertEquals(-120, live.levelDbAt(rate * .51));
        }
    }

    @Test void stereoPhaseAndChannelSwapDoNotChangePowerSpectrum() {
        SpectrumAnalysis in = new SpectrumAnalysis(), anti = new SpectrumAnalysis(), mono = new SpectrumAnalysis();
        in.analyze(tone(48000, 2, false), 2, 48000);
        anti.analyze(tone(48000, 2, true), 2, 48000);
        mono.analyze(tone(48000, 1, false), 1, 48000);
        for (double f : new double[]{20, 100, 1000, 8000, 20000}) {
            assertEquals(in.levelDbAt(f), anti.levelDbAt(f), 1e-6);
            assertEquals(in.levelDbAt(f), mono.levelDbAt(f), 1e-6);
        }
        LiveSpectrum left = new LiveSpectrum(), right = new LiveSpectrum();
        left.push(tone(48000, 2, false), 2); right.push(tone(48000, 2, true), 2);
        for (int i = 0; i < 30; i++) { left.update(); right.update(); }
        assertEquals(left.levelDbAt(1000), right.levelDbAt(1000), 1e-6);
    }

    @Test void firstAndLastSamplesAreNotLostByTheWindow() {
        for (int frames : new int[]{1, 31, 16384, 16385, 18000}) {
            double[] levels = new double[2];
            for (int edge = 0; edge < 2; edge++) {
                float[] pcm = new float[frames]; pcm[edge == 0 ? 0 : frames - 1] = 1;
                SpectrumAnalysis spectrum = new SpectrumAnalysis(); spectrum.analyze(pcm, 1, 48000);
                assertTrue(spectrum.isReady()); levels[edge] = spectrum.levelDbAt(1000);
                assertTrue(levels[edge] > -80, "Missing boundary impulse: " + frames);
            }
            assertEquals(levels[0], levels[1], .001);
        }
    }

    @Test void monoMidPowerEqualsSignalPower() {
        var result = OutputAnalysis.measure(tone(48000, 1, false), 1, 48000);
        assertEquals(.125, result.midPower(), .001); assertEquals(0, result.sidePower()); assertEquals(1, result.correlation());
    }

    @Test void invalidInputDoesNotPublishAnInvalidSpectrum() {
        SpectrumAnalysis spectrum = new SpectrumAnalysis();
        assertThrows(IllegalArgumentException.class, () -> spectrum.analyze(new float[]{1}, 2, 48000));
        assertThrows(IllegalArgumentException.class, () -> spectrum.analyze(new float[]{Float.NaN}, 1, 48000));
        assertThrows(IllegalArgumentException.class, () -> spectrum.analyze(new float[]{1}, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new LiveSpectrum().setSampleRate(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> OutputAnalysis.measure(new float[]{1}, 2, 48000));
        assertThrows(IllegalArgumentException.class, () -> OutputAnalysis.measure(new float[]{Float.NaN}, 1, 48000));
        assertFalse(spectrum.isReady());
        spectrum.analyze(new float[32], 1, 48000); spectrum.analyze(new float[0], 1, 48000);
        assertFalse(spectrum.isReady()); assertEquals(-120, spectrum.getMaxDb());
    }

    @Test void liveChannelChangeCannotReuseThePreviousTransform() {
        LiveSpectrum live = new LiveSpectrum();
        live.push(tone(48000, 1, false), 1);
        for (int i = 0; i < 30; i++) live.update();
        live.push(new float[32768 * 2], 2);
        for (int i = 0; i < 200; i++) live.update();
        assertEquals(-120, live.levelDbAt(1000), .001);
        live.reset(); assertFalse(live.isReady());
    }

    @Test void liveWriterAndUiConsumerCanRunConcurrently() throws Exception {
        LiveSpectrum live = new LiveSpectrum();
        float[] block = Arrays.copyOf(tone(48000, 2, true), 2048);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> producer = executor.submit(() -> { for (int i = 0; i < 1000; i++) live.push(block, 2); });
            for (int i = 0; i < 60; i++) { live.update(); assertTrue(Double.isFinite(live.levelDbAt(1000))); }
            producer.get(5, TimeUnit.SECONDS); live.update(); assertTrue(live.isReady());
        } finally { executor.shutdownNow(); }
    }
}
