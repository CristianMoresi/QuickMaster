package com.quickmaster.processing.eq;
import com.quickmaster.processing.analysis.SpectrumAnalysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link AutoEqProcessor}. */
class AutoEqProcessorTest
{
    private static final int SR = 48000;

    private static float[] whiteNoise(double sec)
    {
        int n = (int) (SR * sec);
        float[] x = new float[n];
        Random r = new Random(42);
        for (int i = 0; i < n; i++) x[i] = (float) ((r.nextDouble() * 2 - 1) * 0.3);
        return x;
    }

    /** Spectral tilt = level(8 kHz) - level(200 Hz), in dB. */
    private static double tilt(float[] x)
    {
        SpectrumAnalysis s = new SpectrumAnalysis();
        s.analyze(x, 1, SR);
        return s.levelDbAt(8000.0) - s.levelDbAt(200.0);
    }

    @Test
    @DisplayName("Toward pink, a flat (white) input gets a downward spectral tilt")
    void pushesTowardPink()
    {
        float[] noise = whiteNoise(4.0);
        double tiltIn = tilt(noise);

        AutoEqProcessor p = new AutoEqProcessor();
        p.setEnabled(true);
        p.setTarget(AutoEqProcessor.Target.PINK);
        p.setAmount(1.0);
        p.prepare(SR, noise.length);
        p.analyze(noise, 1);
        p.prepare(SR, noise.length);
        float[] out = p.process(noise.clone(), 1);

        double tiltOut = tilt(out);
        assertTrue(tiltOut < tiltIn - 2.0,
                "spectrum should tilt down toward pink (in=" + tiltIn + ", out=" + tiltOut + ")");
    }

    @Test
    @DisplayName("Disabled passes through unchanged")
    void disabledPassThrough()
    {
        float[] noise = whiteNoise(2.0);
        AutoEqProcessor p = new AutoEqProcessor();
        p.setEnabled(true);
        p.setAmount(1.0);
        p.prepare(SR, noise.length);
        p.analyze(noise, 1);

        p.setEnabled(false);
        p.prepare(SR, noise.length);
        float[] out = p.process(noise.clone(), 1);
        for (int i = 0; i < noise.length; i++) assertEquals(noise[i], out[i], 1e-6f);
    }

    @Test
    @DisplayName("Amount 0 leaves the spectrum essentially unchanged")
    void amountZeroIsTransparent()
    {
        float[] noise = whiteNoise(3.0);
        double tiltIn = tilt(noise);
        AutoEqProcessor p = new AutoEqProcessor();
        p.setEnabled(true);
        p.setTarget(AutoEqProcessor.Target.PINK);
        p.setAmount(0.0);
        p.prepare(SR, noise.length);
        p.analyze(noise, 1);
        p.prepare(SR, noise.length);
        float[] out = p.process(noise.clone(), 1);
        assertTrue(Math.abs(tilt(out) - tiltIn) < 1.5, "amount 0 should not reshape");
    }
}
