package com.dspark.effects;

import com.dspark.core.FFTRealDouble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dspark.effects.TubeTestSupport.coherentWorstDbc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aliasing of {@link TubePreamp}: the coherent sweep of the C++ suite and the
 * TestAnalogAudit conduction and decimation cases, each a coherent tone at
 * an odd bin measured against every non-harmonic line below 20 kHz.
 */
class TubePreampAliasTest
{
    /** Worst component below 20 kHz other than DC and exact harmonics (dB re the tone), 16384 points. */
    private static double coherentSpurDbc(TubePreamp amp, double nominal, double level)
    {
        final int size = 16384, block = 256;
        int bin = (int) (nominal * size / 48000.0 + 0.5);
        if ((bin & 1) == 0) ++bin;
        amp.reset();
        final double[][] audio = new double[1][block];
        final double[] capture = new double[size], spectrum = new double[size + 2];
        final double gain = Math.pow(10.0, level / 20.0);
        for (int pos = 0; pos < 6 * size; pos += block)
        {
            for (int j = 0; j < block; ++j)
                audio[0][j] = gain * Math.sin(2.0 * Math.PI * bin * (pos + j) / size);
            amp.process(audio, block);
            if (pos >= 5 * size)
                System.arraycopy(audio[0], 0, capture, pos - 5 * size, block);
        }
        new FFTRealDouble(size).forward(capture, spectrum);
        double folded = 0.0;
        for (int k = 1; k * 48000 < 20000 * size; ++k)
            if (k % bin != 0) folded = Math.max(folded, Math.hypot(spectrum[2 * k], spectrum[2 * k + 1]));
        return 20.0 * Math.log10(folded / Math.hypot(spectrum[2 * bin], spectrum[2 * bin + 1]));
    }

    @Test
    @DisplayName("TubePreamp_coherent_sweep_resolves_aliases_next_to_fundamental")
    void coherentSweepResolvesAliasesNextToFundamental()
    {
        // Exact coherent bins: a +/-2-bin Hann mask would hide the
        // fifth-harmonic alias next to 16 kHz.
        final double[][] settings = { { 2, 24.0, -75.0 }, { 2, 36.0, -75.0 }, { 1, 36.0, -75.0 } };
        final double[] tones = { 1000., 2000., 3000., 4000., 5000., 6000., 7000., 8000., 9000., 10000., 11000.,
            12000., 12750., 13000., 14000., 15000., 15250., 16000., 17000., 18000., 19000., 19250., 20000. };
        for (double[] setting : settings)
        {
            final TubePreamp amp = new TubePreamp();
            amp.setStages((int) setting[0]);
            amp.setDrive(setting[1]);
            amp.prepare(48000.0, 1, 256);
            double worst = -400.0, worstTone = 0.0;
            for (double nominal : tones)
            {
                final double ratio = coherentSpurDbc(amp, nominal, -6.0);
                if (ratio > worst) { worst = ratio; worstTone = nominal; }
                assertTrue(ratio < setting[2], (int) setting[0] + " stage(s) +" + setting[1] + " dB tone "
                           + nominal + " Hz: " + ratio + " dBc");
            }
            System.out.printf("coherent sweep, %d stage(s) +%.0f dB, -6 dBFS: worst %.1f dBc at %.0f Hz%n",
                              (int) setting[0], setting[1], worst, worstTone);
        }
    }

    private static TubePreamp conduction(int stageCount, double drive, int factor)
    {
        final TubePreamp amp = new TubePreamp();
        amp.setGridModel(TubePreamp.GridModel.CONDUCTION);
        amp.setOversampling(factor);
        amp.setStages(stageCount);
        amp.setDrive(drive);
        amp.prepare(48000.0, 1, 512);
        return amp;
    }

    @Test
    @DisplayName("AnalogAudit_tube_conduction_reference_is_continuous")
    void conductionReferenceIsContinuous()
    {
        final double a = coherentWorstDbc(conduction(2, 36.0, 2), 48000.0, 16384, 19250.0, 0.5, 24000);
        final double b = coherentWorstDbc(conduction(1, 36.0, 2), 48000.0, 16384, 20000.0, 0.5, 24000);
        System.out.printf("conduction +36 dB: 2 stages 19.25 kHz %.1f dBc, 1 stage 20 kHz %.1f dBc%n", a, b);
        assertTrue(a < -71.0);
        assertTrue(b < -71.0);
    }

    @Test
    @DisplayName("AnalogAudit_tube_conduction_charge_is_integrated")
    void conductionChargeIsIntegrated()
    {
        final double a = coherentWorstDbc(conduction(1, 24.0, 2), 48000.0, 16384, 19250.0, 0.5, 48000);
        final double b = coherentWorstDbc(conduction(2, 24.0, 2), 48000.0, 16384, 16000.0, 0.5, 48000);
        final double c = coherentWorstDbc(conduction(2, 36.0, 2), 48000.0, 16384, 15250.0, 0.5, 48000);
        System.out.printf("conduction charge: %.1f / %.1f / %.1f dBc%n", a, b, c);
        assertTrue(a < -86.0);
        assertTrue(b < -86.0);
        assertTrue(c < -81.0);
    }

    @Test
    @DisplayName("AnalogAudit_tube_decimation_is_deep_from_4x")
    void decimationIsDeepFrom4x()
    {
        final double a = coherentWorstDbc(conduction(1, 36.0, 4), 48000.0, 16384, 20000.0, 0.5, 48000);
        final double b = coherentWorstDbc(conduction(2, 36.0, 4), 48000.0, 16384, 20000.0, 0.5, 48000);
        final TubePreamp clamp1 = new TubePreamp();
        clamp1.setOversampling(4);
        clamp1.setStages(1);
        clamp1.setDrive(24.0);
        clamp1.prepare(48000.0, 1, 512);
        final double c = coherentWorstDbc(clamp1, 48000.0, 16384, 20000.0, 0.5, 48000);
        final TubePreamp clamp2 = new TubePreamp();
        clamp2.setOversampling(4);
        clamp2.setStages(2);
        clamp2.setDrive(24.0);
        clamp2.prepare(48000.0, 1, 512);
        final double d = coherentWorstDbc(clamp2, 48000.0, 16384, 19000.0, 0.5, 48000);
        System.out.printf("4x decimation: %.1f / %.1f / %.1f / %.1f dBc%n", a, b, c, d);
        assertTrue(a < -100.0);
        assertTrue(b < -100.0);
        assertTrue(c < -95.0);
        assertTrue(d < -100.0);

        final TubePreamp amp = new TubePreamp();
        amp.setOversampling(4);
        amp.prepare(48000.0, 1, 512);
        assertEquals(93, amp.getLatency());
    }
}
