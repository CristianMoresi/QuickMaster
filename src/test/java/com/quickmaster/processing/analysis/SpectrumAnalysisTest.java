package com.quickmaster.processing.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link SpectrumAnalysis}. */
class SpectrumAnalysisTest
{
    private static final int SR = 48000;

    private static float[] sine(double hz, double sec)
    {
        int n = (int) (SR * sec);
        float[] s = new float[n];
        double step = 2.0 * Math.PI * hz / SR;
        for (int i = 0; i < n; i++) s[i] = (float) (0.5 * Math.sin(step * i));
        return s;
    }

    @Test
    @DisplayName("The average spectrum peaks at the tone's frequency")
    void peaksAtToneFrequency()
    {
        SpectrumAnalysis sa = new SpectrumAnalysis();
        sa.analyze(sine(1000.0, 2.0), 1, SR);
        assertTrue(sa.isReady());
        double at1k = sa.levelDbAt(1000.0);
        assertTrue(at1k > sa.levelDbAt(250.0) + 20.0, "no peak vs 250 Hz");
        assertTrue(at1k > sa.levelDbAt(8000.0) + 20.0, "no peak vs 8 kHz");
    }

    @Test
    @DisplayName("A file shorter than one FFT window still produces a spectrum")
    void shortFileIsHandled()
    {
        SpectrumAnalysis sa = new SpectrumAnalysis();
        sa.analyze(sine(440.0, 0.1), 1, SR);     // shorter than FFT_SIZE
        assertTrue(sa.isReady());
    }
}
