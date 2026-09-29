package com.quickmaster.processing.stereo;

import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class StereoImageTest {
    static StereoImageSettings settings(boolean gen, boolean low, boolean harmonic, double leveling, boolean guard) {
        return new StereoImageSettings(gen, 1, low, harmonic, leveling > 0, leveling, guard, 3, StereoProfile.REFERENCE, .12);
    }
    static StereoImageProcessor processor(StereoImageSettings settings) {
        var p = new StereoImageProcessor(); p.setSettings(settings); p.setEnabled(true); return p;
    }
    static float[] render(float[] x, int rate, StereoImageProcessor p) {
        var chain = new ProcessingPipeline(); chain.addProcessor(p); chain.prepare(rate, x.length);
        return chain.analyzeAndRender(x, 2, 0, null, null, null);
    }
    static float[] signal(int rate, double seconds, double sideGain) {
        float[] x = new float[(int)(rate * seconds) * 2];
        Random random = new Random(8237);
        for (int i = 0; i < x.length / 2; i++) {
            double m = .2 * Math.sin(2 * Math.PI * 401 * i / rate) + .07 * (random.nextDouble() - .5);
            double s = sideGain * .2 * Math.sin(2 * Math.PI * 1301 * i / rate);
            x[2 * i] = (float)(m + s); x[2 * i + 1] = (float)(m - s);
        }
        return x;
    }
    static double share(float[] x, int from, int to) {
        double m = 0, s = 0;
        for (int i = from; i < to; i++) {
            double a = .5 * (x[2 * i] + (double)x[2 * i + 1]), b = .5 * (x[2 * i] - (double)x[2 * i + 1]);
            m += a * a; s += b * b;
        }
        return StereoImageAnalyzer.share(m, s);
    }
    static void assertMono(float[] x, float[] y) {
        assertEquals(x.length, y.length);
        for (int i = 0; i < x.length; i += 2)
            assertEquals(x[i] + (double)x[i + 1], y[i] + (double)y[i + 1], 2e-6, "Mid changed at " + i / 2);
    }

    @Test void partitionedConvolutionMatchesDirectOracleIncludingTail() {
        Random r = new Random(4); double[] taps = new double[153];
        double[] x = new double[711];
        for (int i = 0; i < taps.length; i++) taps[i] = .01 * r.nextGaussian();
        for (int i = 0; i < 311; i++) x[i] = .1 * r.nextGaussian();
        var fir = new PartitionedFir(taps, 32);
        for (int i = 0; i < x.length; i++) {
            double expected = 0;
            for (int k = 0; k < taps.length; k++) if (i - 32 - k >= 0) expected += taps[k] * x[i - 32 - k];
            assertEquals(expected, fir.process(x[i]), 3e-8, "Convolution frame " + i);
        }
    }
    @Test void bassFilterIsLinearPhaseAndMeetsDeclaredEdges() {
        for (int rate : new int[]{44100, 48000, 96000, 192000}) {
            double[] h = PartitionedFir.bassExclusion(rate);
            for (int i = 0; i < h.length; i++) assertEquals(h[i], h[h.length - 1 - i], 1e-15);
            for (int hz = 0; hz <= 150; hz++) assertTrue(response(h, hz, rate) < Math.pow(10, -90 / 20.0), "Stopband " + rate + "/" + hz);
            for (int hz : new int[]{200, 250, 500, 1000, 3000})
                assertTrue(Math.abs(20 * Math.log10(response(h, hz, rate))) < .05, "Passband " + rate + "/" + hz);
        }
    }
    static double response(double[] h, double hz, int rate) {
        double re = 0;
        for (int i = 0; i < h.length; i++) re += h[i] * Math.cos(2 * Math.PI * hz / rate * (i - h.length / 2));
        return Math.abs(re);
    }
    @Test void generationCreatesSideFromDualMonoAndKeepsMonoAtAllModes() {
        int rate = 48000; float[] x = signal(rate, 2, 0), saved = x.clone();
        for (boolean low : new boolean[]{false, true}) {
            var generator = processor(settings(true, low, true, 0, false));
            float[] y = render(x, rate, generator);
            assertEquals("", generator.plan().notice(), "Generation is already active on this dual-mono source");
            assertMono(x, y); assertTrue(share(y, rate / 2, rate * 3 / 2) > .15,
                    "Full generation must create meaningful Side energy, not merely different float bits");
        }
        assertArrayEquals(saved, x);
    }
    @Test void sectionsStartDisabledAndHarmonicsCannotBeDisabledByOldPresets() {
        var s=StereoImageSettings.DEFAULT;
        assertFalse(s.generation());assertFalse(s.leveling());assertFalse(s.guard());assertFalse(s.active());
        assertTrue(s.harmonics());assertTrue(settings(true,false,false,0,false).harmonics());
        var legacy=new com.google.gson.Gson().fromJson("{\"generation\":true,\"generationAmount\":0.5,\"harmonics\":false,\"profile\":\"AUTO\",\"referenceSideShare\":0.12}",StereoImageSettings.class);
        assertTrue(legacy.harmonics());
    }
    @Test void generationAmountControlsOnlyNewTrackAndZeroIsExact() {
        int rate=48000;float[] x=signal(rate,1,.4);
        var full=settings(true,false,true,0,false);
        var half=new StereoImageSettings(true,.5,false,true,false,0,false,3,StereoProfile.AUTO,.12);
        var zero=new StereoImageSettings(true,0,false,true,false,0,false,3,StereoProfile.AUTO,.12);
        float[] a=render(x,rate,processor(full)),b=render(x,rate,processor(half));
        assertArrayEquals(x,render(x,rate,processor(zero)));
        for(int i=0;i<x.length;i++)assertEquals(.5*(a[i]-(double)x[i]),b[i]-(double)x[i],1e-7);
    }
    @Test void checkboxProtectsOnlyNewBassNotOriginalSide() {
        int rate = 48000; float[] x = new float[rate * 2 * 2];
        for (int i = 0; i < x.length / 2; i++) { x[i * 2] = (float)(.2 * Math.sin(2 * Math.PI * 100 * i / rate)); x[i * 2 + 1] = x[i * 2] * .4f; }
        var p = processor(settings(true, false, false, 0, false));
        float[] y = render(x, rate, p); assertMono(x, y);
        double residual = 0, original = 0;
        for (int i = rate; i < rate * 3 / 2; i++) {
            residual += Math.pow(y[2 * i] - x[2 * i], 2); original += Math.pow(x[2 * i], 2);
        }
        assertTrue(10 * Math.log10(residual / original) < -80, "Bass rejection dB=" + 10 * Math.log10(residual / original));
        assertEquals(share(x, rate, rate * 3 / 2), share(y, rate, rate * 3 / 2), 1e-5);
        float[] generated = render(x, rate, processor(settings(true, true, false, 0, false)));
        double change = 0;
        for (int i = rate; i < rate * 3 / 2; i++) change += Math.pow(generated[2 * i] - x[2 * i], 2);
        assertTrue(change > residual * 10000);
    }
    @Test void sideLevelerRaisesAndLowersSectionsAndAmountZeroIsExact() {
        int rate = 8000, part = rate * 5;
        float[] x = new float[part * 6];
        double[] side = {.12, .9, .35};
        for (int p = 0; p < 3; p++) System.arraycopy(signal(rate, 5, side[p]), 0, x, p * part * 2, part * 2);
        float[] y = render(x, rate, processor(settings(false, false, false, 1, false)));
        assertMono(x, y);
        for (int p = 0; p < 3; p++) assertEquals(.12, share(y, p * part + rate * 2, p * part + rate * 3), .002);
        assertTrue(share(y, rate * 2, rate * 3) > share(x, rate * 2, rate * 3));
        assertTrue(share(y, part + rate * 2, part + rate * 3) < share(x, part + rate * 2, part + rate * 3));
        assertArrayEquals(x, render(x, rate, processor(settings(false, false, false, 0, false))));
    }
    @Test void guardIsIndependentDownwardOnlyAndRespectsEnergyCells() {
        int rate = 8000; float[] x = signal(rate, 4, 1.8);
        StereoImageProcessor p = processor(settings(false, false, false, 0, true));
        float[] y = render(x, rate, p); assertMono(x, y);
        double ratio = .12 / .88 * Math.pow(10, .3), ceiling = ratio / (1 + ratio);
        for (int from = rate / 2; from < 3 * rate; from += rate / 100) {
            assertTrue(share(y, from, from + rate / 25) <= ceiling + 1e-6);
            assertTrue(p.plan().guardGainDb(from / (double)rate) <= 0);
        }
    }
    @Test void streamingIsIndependentOfBlockSizeAndResetReproducible() {
        int rate = 8000; float[] x = signal(rate, 1, .3);
        {
            boolean harmonic=true;
            var p = processor(settings(true, false, harmonic, .5, true));
            float[] expected = render(x, rate, p), out = new float[x.length];
            p.prepare(rate, x.length); int latency = p.getLatencyFrames();
            for (int f = 0; f < x.length / 2 + latency; ) {
                int n = Math.min(37, x.length / 2 + latency - f); float[] block = new float[n * 2];
                int copy = Math.max(0, Math.min(n, x.length / 2 - f));
                if (copy > 0) System.arraycopy(x, 2 * f, block, 0, copy * 2);
                p.process(block, 2);
                for (int j = 0; j < n; j++) if (f + j >= latency && f + j < latency + x.length / 2)
                    System.arraycopy(block, j * 2, out, (f + j - latency) * 2, 2);
                f += n;
            }
            assertArrayEquals(expected, out);
        }
    }
    @Test void silenceMonoAntiphaseCancellationAndInvalidPcmAreExplicit() {
        var p = processor(StereoImageSettings.DEFAULT); p.prepare(48000, 100);
        p.analyze(new float[100], 1); assertEquals(0, p.getLatencyFrames());
        assertTrue(p.plan().notice().contains("Mono file"));
        float[] mono = {.1f,.2f}; assertSame(mono, p.process(mono, 1));
        float[] silence = new float[1000]; assertArrayEquals(silence, render(silence, 8000, p));
        assertTrue(Double.isNaN(p.plan().inputSideShare()));
        var cancelled = new CancellationToken(); cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> p.analyze(silence, 2, cancelled));
        p.prepare(8000, 2);
        assertThrows(IllegalArgumentException.class, () -> p.analyze(new float[]{Float.NaN, 0}, 2));
        assertThrows(IllegalArgumentException.class, () -> new StereoImageSettings(true, Double.NaN, false, false, true, .5, true, 3, StereoProfile.AUTO, .1));
    }
}
