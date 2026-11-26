package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.dspark.effects.TubeTestSupport.TWO_PI;
import static com.dspark.effects.TubeTestSupport.toDb;
import static com.dspark.effects.TubeTestSupport.toneAmplitude;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The continuous-time core against the point circuit (factor 1) run at 32x
 * the base rate, the wet-path phase against the 1x circuit, automation
 * against the block partition, and the grid conduction model (the C++
 * TestEffectsTone.cpp and TestAnalogAudit.cpp cases).
 */
class TubePreampCircuitTest
{
    /** A 0.25 tone through a fresh preamp, the latency removed. */
    static double[] renderTone(int factor, double rate, int stageCount, double drive,
                               double treble, double bass, double middle, double hz, boolean conduction)
    {
        final TubePreamp amp = new TubePreamp();
        if (conduction) amp.setGridModel(TubePreamp.GridModel.CONDUCTION);
        amp.setOversampling(factor);
        amp.setStages(stageCount);
        amp.setDrive(drive);
        amp.setTreble(treble);
        amp.setBass(bass);
        amp.setMiddle(middle);
        amp.prepare(rate, 1, 4096);
        final int latency = amp.getLatency();
        final int total = (int) (0.15 * rate) + latency;
        final double[] y = new double[total];
        for (int i = 0; i < total; ++i)
            y[i] = 0.25 * Math.sin(2.0 * Math.PI * hz * (double) i / rate);
        TubeTestSupport.renderMono(amp, y, 4096);
        return Arrays.copyOfRange(y, latency, total);
    }

    /** Complex harmonic over the last 30 periods, as {re, im}. */
    static double[] harmonic(double[] y, double rate, double hz, int h)
    {
        final int count = (int) (30.0 * rate / hz + 0.5), from = y.length - count;
        double re = 0.0, im = 0.0;
        for (int i = from; i < y.length; ++i)
        {
            final double ph = -2.0 * Math.PI * h * hz * (double) (i - from) / rate;
            re += y[i] * Math.cos(ph);
            im += y[i] * Math.sin(ph);
        }
        return new double[] { 2.0 * re / count, 2.0 * im / count };
    }

    /** h / (r1 / |r1|)^h / |r1| as {re, im}: the harmonic with the fundamental's phase removed. */
    static double[] normalised(double[] hv, double[] r1, int h)
    {
        final double mag = Math.hypot(r1[0], r1[1]);
        final double angle = -h * Math.atan2(r1[1], r1[0]);
        final double c = Math.cos(angle), s = Math.sin(angle);
        return new double[] { (hv[0] * c - hv[1] * s) / mag, (hv[0] * s + hv[1] * c) / mag };
    }

    /** Worst harmonic error (dB) and fundamental gain error (dB) of actual against reference. */
    static double[] compare(double[] reference, double referenceRate, double[] actual, double rate, double hz, int maxHarmonic)
    {
        final double[] r1 = harmonic(reference, referenceRate, hz, 1);
        final double[] a1 = harmonic(actual, rate, hz, 1);
        final double gainError = toDb(Math.hypot(a1[0], a1[1]) / Math.hypot(r1[0], r1[1]));
        double worst = 0.0;
        for (int h = 2; h <= maxHarmonic; ++h)
        {
            final double[] r = normalised(harmonic(reference, referenceRate, hz, h), r1, h);
            final double[] a = normalised(harmonic(actual, rate, hz, h), a1, h);
            worst = Math.max(worst, Math.hypot(a[0] - r[0], a[1] - r[1]));
        }
        return new double[] { toDb(worst), gainError };
    }

    @Test
    @DisplayName("TubePreamp_continuous_core_matches_independent_point_circuit")
    void continuousCoreMatchesIndependentPointCircuit()
    {
        // The point-sampled circuit at 32x the base rate is the reference. At
        // the default 2x the continuous core must reproduce the reference gain
        // and harmonic structure (each harmonic below 20 kHz relative to the
        // fundamental, with the fundamental's phase removed).
        final double[][] cases = { { 2, 36.0, 1.0, 1.0, 1.0, 3000.0, -55.0 },
                                   { 2, 24.0, 0.5, 0.0, 1.0, 1000.0, -70.0 } };
        for (double[] c : cases)
        {
            final double referenceRate = 48000.0 * 32.0;
            final double[] reference = renderTone(1, referenceRate, (int) c[0], c[1], c[2], c[3], c[4], c[5], false);
            final double[] actual = renderTone(2, 48000.0, (int) c[0], c[1], c[2], c[3], c[4], c[5], false);
            final double[] result = compare(reference, referenceRate, actual, 48000.0, c[5], (int) (20000.0 / c[5]));
            System.out.printf("core vs point circuit at 32x, %d stages +%.0f dB %.0f Hz: harmonics %.1f dB, gain %.4f dB%n",
                              (int) c[0], c[1], c[5], result[0], result[1]);
            assertTrue(Math.abs(result[1]) < 0.05);
            assertTrue(result[0] < c[6], "harmonic error " + result[0]);
        }
    }

    @Test
    @DisplayName("AnalogAudit_tube_grid_conduction_is_cathode_referenced (circuit)")
    void gridConductionCoreMatchesPointCircuit()
    {
        // The 2x core against the point circuit at 32x, both with the
        // conduction model (two stages, +24 dB, 1 kHz, bass 0, middle 1).
        final double referenceRate = 48000.0 * 32.0;
        final double[] reference = renderTone(1, referenceRate, 2, 24.0, 0.5, 0.0, 1.0, 1000.0, true);
        final double[] actual = renderTone(2, 48000.0, 2, 24.0, 0.5, 0.0, 1.0, 1000.0, true);
        final double[] result = compare(reference, referenceRate, actual, 48000.0, 1000.0, 20);
        System.out.printf("conduction core vs point circuit at 32x: harmonics %.1f dB, gain %.4f dB%n",
                          result[0], result[1]);
        assertTrue(Math.abs(result[1]) < 0.05);
        assertTrue(result[0] < -70.0, "harmonic error " + result[0]);

        final TubePreamp amp = new TubePreamp();
        assertEquals(TubePreamp.GridModel.CLAMP, amp.getGridModel());
        amp.setGridModel(TubePreamp.GridModel.CONDUCTION);
        assertEquals(TubePreamp.GridModel.CONDUCTION, amp.getGridModel());
    }

    @Test
    @DisplayName("TubePreamp_wet_path_latency_matches_point_circuit")
    void wetPathLatencyMatchesPointCircuit()
    {
        // The point circuit at 1x has no resampler and no core delay: its wet
        // phase anchors the reported latency of every oversampled factor.
        final int size = 8192, block = 256;
        final int[] bins = { 171, 853, 1707, 2561 };   // about 1, 5, 10, 15 kHz
        final double[][][][] phasor = new double[2][5][4][];
        int row = 0;
        for (int factor : new int[] { 1, 2, 4, 8, 16 })
        {
            final TubePreamp amp = new TubePreamp();
            amp.setOversampling(factor);
            amp.setDrive(-12.0);
            amp.setSag(0.0);
            amp.prepare(48000.0, 1, block);
            final int latency = amp.getLatency();
            final double[][] audio = new double[1][block];
            for (int stageCount : new int[] { 1, 2 })
            {
                amp.setStages(stageCount);
                for (int column = 0; column < bins.length; ++column)
                {
                    final int bin = bins[column];
                    amp.reset();
                    double sr = 0.0, si = 0.0;
                    final int total = 2 * size + latency;
                    for (int pos = 0; pos < total; pos += block)
                    {
                        final int count = Math.min(block, total - pos);
                        for (int j = 0; j < count; ++j)
                            audio[0][j] = 0.1 * Math.sin(2.0 * Math.PI * bin * (pos + j) / size);
                        amp.process(audio, count);
                        for (int j = 0; j < count; ++j)
                        {
                            final int n = pos + j - latency - size;
                            if (n >= 0)
                            {
                                final double ph = -2.0 * Math.PI * bin * n / size;
                                sr += audio[0][j] * Math.cos(ph);
                                si += audio[0][j] * Math.sin(ph);
                            }
                        }
                    }
                    phasor[stageCount - 1][row][column] = new double[] { sr, si };
                }
            }
            ++row;
        }
        double worstDelay = 0.0, worstGain = 0.0;
        for (double[][][] stage : phasor)
            for (int factor = 1; factor < stage.length; ++factor)
                for (int k = 0; k < bins.length; ++k)
                {
                    final double[] p = stage[factor][k], q = stage[0][k];
                    // ratio = p / q
                    final double den = q[0] * q[0] + q[1] * q[1];
                    final double rr = (p[0] * q[0] + p[1] * q[1]) / den;
                    final double ri = (p[1] * q[0] - p[0] * q[1]) / den;
                    final double delay = -Math.atan2(ri, rr) * size / (2.0 * Math.PI * bins[k]);
                    final double gain = 20.0 * Math.log10(Math.hypot(rr, ri));
                    worstDelay = Math.max(worstDelay, Math.abs(delay));
                    worstGain = Math.max(worstGain, Math.abs(gain));
                    assertTrue(Math.abs(delay) < 0.05);
                    assertTrue(Math.abs(gain) < 0.1);
                }
        System.out.printf("wet path against the 1x circuit: worst delay %.4f samples, gain %.4f dB%n",
                          worstDelay, worstGain);
    }

    // -- Automation against the block partition ---------------------------------------

    private static double[][] renderAutomation(int factor, int partition, int control)
    {
        final int frames = 4096, boundary = 173, maximumBlock = 257;
        final TubePreamp amp = new TubePreamp();
        amp.setOversampling(factor);
        amp.setDrive(0.0);
        amp.setOutput(-3.0);
        amp.setStages(1);
        amp.prepare(48000.0, 2, maximumBlock);
        final double[][] result = new double[2][frames];
        for (int ch = 0; ch < 2; ++ch)
            for (int i = 0; i < frames; ++i)
                result[ch][i] = 0.12 * Math.sin(2.0 * Math.PI * (317.0 + ch * 701.0) * i / 48000.0);

        final int[] irregular = { 7, 89, 1, 113, 53, 257 };
        int position = 0;
        int block = 0;
        while (position < frames)
        {
            if (position == boundary)
            {
                if (control == 0) amp.setDrive(24.0);
                if (control == 1) amp.setOutput(12.0);
                if (control == 2) amp.setStages(2);
            }
            if (control == 2)
            {
                // Revert during warm-up, request again, reverse an audible
                // fade, and finally settle.
                if (position == 311 || position == 1907) amp.setStages(1);
                if (position == 503 || position == 2341) amp.setStages(2);
            }
            final int requested = partition == 0 ? maximumBlock
                                : partition == 1 ? irregular[block++ % irregular.length] : 1;
            int count = Math.min(requested, frames - position);
            if (position < boundary) count = Math.min(count, boundary - position);
            if (control == 2)
                for (int event : new int[] { 311, 503, 1907, 2341 })
                    if (position < event) count = Math.min(count, event - position);
            amp.process(result, position, count);
            position += count;
        }
        return result;
    }

    @Test
    @DisplayName("TubePreamp_gain_automation_is_independent_of_block_partition")
    void gainAutomationIsIndependentOfBlockPartition()
    {
        for (int factor : new int[] { 1, 2, 16 })
            for (int control = 0; control < 3; ++control)
            {
                final double[][] reference = renderAutomation(factor, 0, control);
                for (int partition : new int[] { 1, 2 })
                {
                    final double[][] actual = renderAutomation(factor, partition, control);
                    double maximumDifference = 0.0;
                    for (int ch = 0; ch < 2; ++ch)
                        for (int i = 0; i < actual[ch].length; ++i)
                            maximumDifference = Math.max(maximumDifference,
                                Math.abs(actual[ch][i] - reference[ch][i]));
                    assertEquals(0.0, maximumDifference, 1e-11,
                                 "factor " + factor + " control " + control + " partition " + partition);
                }
            }
    }

    // -- Conduction model ---------------------------------------------------------------

    private static double blockingProbe(TubePreamp.GridModel model, int factor)
    {
        final TubePreamp amp = new TubePreamp();
        amp.setGridModel(model);
        amp.setOversampling(factor);
        amp.setDrive(24.0);
        amp.prepare(48000.0, 1, 512);
        final int latency = amp.getLatency();
        final int burst = 14400, total = 14400 + 19200 + latency;
        final double[] y = new double[total];
        for (int i = 0; i < total; ++i)
            y[i] = (i < burst ? 0.5 : 0.01) * Math.sin(TWO_PI * 1000.0 * (double) i / 48000.0);
        TubeTestSupport.renderMono(amp, y, 512);
        // 1 kHz level over 10 ms windows starting 2 ms and 300 ms after the burst.
        final int from2 = burst + (int) (2.0 * 48.0) + latency;
        final int from300 = burst + (int) (300.0 * 48.0) + latency;
        return toDb(toneAmplitude(y, from2, 480, 1000.0, 48000.0) / toneAmplitude(y, from300, 480, 1000.0, 48000.0));
    }

    @Test
    @DisplayName("AnalogAudit_tube_conduction_models_blocking_distortion")
    void conductionModelsBlockingDistortion()
    {
        for (int factor : new int[] { 2, 1 })
        {
            final double conduction = blockingProbe(TubePreamp.GridModel.CONDUCTION, factor);
            final double clamp = blockingProbe(TubePreamp.GridModel.CLAMP, factor);
            System.out.printf("blocking at %dx: conduction %.1f dB, clamp %.1f dB (2 ms after the burst)%n",
                              factor, conduction, clamp);
            assertTrue(conduction < -10.0);
            assertTrue(clamp > -3.0);
        }
    }
}
