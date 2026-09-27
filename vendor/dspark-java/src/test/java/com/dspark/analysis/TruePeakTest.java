package com.dspark.analysis;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TruePeakTest
{
    @Test
    void recoversInterSamplePeakAboveSamplePeak()
    {
        // A fs/4 sine with a pi/4 phase: every sample lands at +-0.707, but the
        // true (inter-sample) peak is 1.0. The 4x detector must recover it.
        // (Sine, not cosine: a cosine onset starts the tone with an abrupt
        // band-edge alternation from silence, and the official Annex 2
        // interpolator genuinely overshoots ~0.7 dB on that startup
        // transient; the steady-state reading is what the tolerance pins.)
        int n = 4000, ch = 1;
        float[] x = new float[n];
        double samplePeak = 0.0;
        for (int i = 0; i < n; i++)
        {
            x[i] = (float) Math.sin(2.0 * Math.PI * 0.25 * i + Math.PI / 4.0);
            samplePeak = Math.max(samplePeak, Math.abs(x[i]));
        }
        double tp = TruePeak.measureMax(x, ch);
        assertEquals(0.707, samplePeak, 0.01, "sample peak should be ~0.707");
        // EBU Tech 3341 true-peak tolerance is +0.2/-0.4 dB around the
        // official BS.1770-5 Annex 2 interpolator.
        double tpDb = 20.0 * Math.log10(tp);
        assertTrue(tpDb > -0.4, "true peak should recover ~0 dBTP, was " + tpDb + " dB");
        assertTrue(tpDb < 0.2, "true peak should not overshoot, was " + tpDb + " dB");
    }

    @Test
    void ebuStyleQuarterRateCaseReadsMinusSix()
    {
        // EBU Tech 3341 cases 15-18 style: fs/4 sine at -6 dBFS (various
        // phases), expected -6 dBTP within +0.2/-0.4 dB.
        double amp = Math.pow(10.0, -6.0 / 20.0);
        for (double phase : new double[] { 0.0, Math.PI / 4.0, Math.PI / 2.0 })
        {
            int n = 8000;
            float[] x = new float[n];
            for (int i = 0; i < n; i++)
                x[i] = (float) (amp * Math.sin(2.0 * Math.PI * 0.25 * i + phase));
            double tpDb = 20.0 * Math.log10(TruePeak.measureMax(x, 1));
            assertTrue(tpDb > -6.4 && tpDb < -5.8,
                    "phase " + phase + " read " + tpDb + " dBTP");
        }
    }

    @Test
    void lowFrequencyTruePeakNearSamplePeak()
    {
        // A slow sine: samples already capture the peak, so true peak ~= sample peak.
        int n = 4000;
        float[] x = new float[n];
        double samplePeak = 0.0;
        for (int i = 0; i < n; i++)
        {
            x[i] = (float) (0.8 * Math.sin(2.0 * Math.PI * 50.0 * i / 48000.0));
            samplePeak = Math.max(samplePeak, Math.abs(x[i]));
        }
        double tp = TruePeak.measureMax(x, 1);
        assertEquals(samplePeak, tp, 0.02, "true peak should be close to sample peak at low freq");
    }
}
