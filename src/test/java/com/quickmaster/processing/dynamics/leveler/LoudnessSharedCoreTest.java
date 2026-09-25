package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ChannelLayout;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;

class LoudnessSharedCoreTest
{
    @Test
    void completeSilentShortTermIsAbsentWithoutInventingFiniteFloor()
    {
        int rate = 48_000;
        LoudnessTimeline timeline = new LoudnessAnalyzer().analyze(new float[rate * 3],
                new AudioFormat(rate, 1, rate * 3), new CancellationToken());
        assertNotNull(timeline);
        assertTrue(timeline.momentaryValidAt(0), "A complete zero-power block was observed.");
        assertEquals(0L, Double.doubleToRawLongBits(timeline.momentaryPowerAt(0)));
        assertFalse(timeline.shortTermValidAt(0), "Zero power is not a finite LUFS measurement.");
        assertEquals(0L, Double.doubleToRawLongBits(timeline.shortTermLufsAt(0)));
        assertFalse(timeline.integrated().present());
    }

    @Test
    void productFilteringMatchesIndependentItu48kDirectFormOracle()
    {
        int rate = 48_000;
        float[] pcm = new float[rate];
        for (int i = 0; i < pcm.length; i++)
            pcm[i] = (float) (0.13d * StrictMath.sin(2.0d * StrictMath.PI * 997.0d * i / rate)
                    + 0.09d * StrictMath.sin(2.0d * StrictMath.PI * 93.0d * i / rate));
        double[] oraclePower = reference48kPowers(pcm);
        LoudnessTimeline timeline = new LoudnessAnalyzer().analyze(pcm,
                new AudioFormat(rate, 1, pcm.length), new CancellationToken());
        assertNotNull(timeline);
        for (int block = 0; block < 7; block++)
        {
            double sum = 0.0d;
            for (int frame = block * 4_800; frame < block * 4_800 + 19_200; frame++)
                sum += oraclePower[frame];
            assertEquals(sum / 19_200, timeline.momentaryPowerAt(block), 2.0e-12d,
                    "BS.1770-5 Annex 1 tables 1/2, independent direct-form-I filter.");
        }
    }

    @Test
    void preCancelledAnalysisHasNoTimeline()
    {
        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();
        assertNull(new LoudnessAnalyzer().analyze(new float[48_000],
                new AudioFormat(48_000, 1, 48_000), cancellation));
    }

    @Test
    void adapterAndAnalyzerUseIdenticalCoreWindowsAtAllApprovedRates()
    {
        for (int rate : new int[] { 44_100, 48_000, 96_000 })
            for (int channels : new int[] { 1, 2 })
            {
                int frames = rate * 3 + rate / 5 + 13;
                int hop = (int) StrictMath.round(0.1d * rate);
                int wm = (int) StrictMath.round(0.4d * rate), ws = rate * 3;
                float[] pcm = new float[frames * channels];
                for (int frame = 0; frame < frames; frame++)
                    for (int channel = 0; channel < channels; channel++)
                        pcm[frame * channels + channel] = (float) (0.11d * StrictMath.sin(
                                2.0d * StrictMath.PI * (997.0d + channel * 100.0d) * frame / rate));
                LoudnessTimeline timeline = new LoudnessAnalyzer().analyze(pcm,
                        new AudioFormat(rate, channels, frames), new CancellationToken());
                assertNotNull(timeline);
                assertEquals((frames - 1) / hop + 1, timeline.momentaryCount());
                LoudnessCore core = new LoudnessCore(rate, channels == 1
                        ? ChannelLayout.MONO_MAIN : ChannelLayout.STEREO_LR);
                double[] blocks = new double[timeline.momentaryCount()];
                int completedBlocks = 0;
                for (int frame = 0; frame < frames; frame++)
                {
                    core.acceptFrame(pcm, frame * channels);
                    int end = frame + 1;
                    if (end >= wm && (end - wm) % hop == 0)
                    {
                        int slot = (end - wm) / hop;
                        assertTrue(timeline.momentaryValidAt(slot));
                        blocks[slot] = core.momentaryPower();
                        assertEquals(Double.doubleToRawLongBits(blocks[slot]),
                                Double.doubleToRawLongBits(timeline.momentaryPowerAt(slot)));
                        completedBlocks++;
                    }
                    if (end >= ws && (end - ws) % hop == 0)
                    {
                        int slot = (end - ws) / hop;
                        MeasuredLoudness reading = LoudnessCore.fromPower(core.shortTermPower());
                        assertEquals(reading.present(), timeline.shortTermValidAt(slot));
                        assertEquals(Double.doubleToRawLongBits(reading.lufs()),
                                Double.doubleToRawLongBits(timeline.shortTermLufsAt(slot)));
                    }
                }
                long[] counts = new long[3];
                MeasuredLoudness integrated = LoudnessCore.integrated(blocks, completedBlocks, counts, null);
                assertEquals(integrated.present(), timeline.integrated().present());
                assertEquals(Double.doubleToRawLongBits(integrated.lufs()),
                        Double.doubleToRawLongBits(timeline.integrated().lufs()));
                assertEquals((frames - wm) / hop + 1, counts[0]);
                for (int i = completedBlocks; i < timeline.momentaryCount(); i++)
                {
                    assertFalse(timeline.momentaryValidAt(i));
                    assertEquals(0L, Double.doubleToRawLongBits(timeline.momentaryPowerAt(i)));
                }
            }
    }

    @Test
    void fullScale997HzCalibrationComesFromItuNotAConformanceFixture()
    {
        // BS.1770-5 Annex 1 note after equation 7: a 997 Hz full-scale main
        // channel sine reads -3.01 LKFS. This steady analytic check is not PASSED.
        for (int rate : new int[] { 44_100, 48_000, 96_000 })
        {
            LoudnessCore core = new LoudnessCore(rate, ChannelLayout.MONO_MAIN);
            float[] frame = new float[1];
            for (int i = 0; i < rate * 6; i++)
            {
                frame[0] = (float) StrictMath.sin(2.0d * StrictMath.PI * 997.0d * i / rate);
                core.acceptFrame(frame, 0);
            }
            assertEquals(-3.01d, LoudnessCore.fromPower(core.shortTermPower()).lufs(), 0.02d);
        }
    }

    @Test
    void compiledProductWiringCallsRealCoreNotAnAlternativeDsp()
    {
        String adapter = bytecode(KWeightingAdapter.class);
        assertTrue(adapter.contains("LoudnessCore.\"<init>\":(ILcom/quickmaster/processing/dynamics/leveler/model/ChannelLayout;)V"));
        assertTrue(adapter.contains("LoudnessCore.acceptFrame:([FI)V"));
        assertTrue(adapter.contains("LoudnessCore.momentaryPower:()D"));
        assertTrue(adapter.contains("LoudnessCore.shortTermPower:()D"));
        assertFalse(adapter.contains("StrictMath.sin"));
        assertFalse(adapter.contains("StrictMath.cos"));
        assertFalse(adapter.contains("newarray"), "Only the core may own filter history or a power ring.");
    }

    @Test
    void analyzerClassfileSharesIntegratedAndContainsNoLogFloor()
    {
        String analyzer = bytecode(LoudnessAnalyzer.class);
        assertTrue(analyzer.contains("KWeightingAdapter.fillWindowPowers:"));
        assertTrue(analyzer.contains("LoudnessCore.integrated:([DI[JLcom/quickmaster/processing/dynamics/leveler/CancellationToken;)"));
        assertTrue(analyzer.contains("LoudnessCore.fromPower:(D)"));
        assertFalse(analyzer.contains("StrictMath.log10"));
        assertFalse(analyzer.contains("integratedFromTimeline"));
    }

    @Test
    void adapterValidatesDimensionsAndNeverSanitizesInvalidPcm()
    {
        AudioFormat format = new AudioFormat(48_000, 1, 48_000);
        float[] pcm = new float[48_000];
        assertThrows(IllegalArgumentException.class, () -> KWeightingAdapter.fillWindowPowers(pcm, format,
                19_200, 144_000, 4_801, new double[10], new java.util.BitSet(10),
                new double[10], new java.util.BitSet(10), null));
        assertThrows(IllegalArgumentException.class, () -> KWeightingAdapter.fillWindowPowers(pcm, format,
                19_200, 144_000, 4_800, new double[9], new java.util.BitSet(10),
                new double[10], new java.util.BitSet(10), null));
        double[] invalid = new double[10];
        invalid[9] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> KWeightingAdapter.fillWindowPowers(pcm, format,
                19_200, 144_000, 4_800, invalid, new java.util.BitSet(10),
                new double[10], new java.util.BitSet(10), null));
        pcm[47_999] = Float.NaN;
        assertNull(new LoudnessAnalyzer().analyze(pcm, format, null));
        assertTrue(Float.isNaN(pcm[47_999]), "Invalid source is neither repaired nor copied back.");
    }

    private static String bytecode(Class<?> owner)
    {
        java.io.StringWriter output = new java.io.StringWriter();
        java.io.PrintWriter writer = new java.io.PrintWriter(output);
        int exit = java.util.spi.ToolProvider.findFirst("javap").orElseThrow().run(writer, writer,
                "-classpath", System.getProperty("java.class.path"), "-c", "-p", owner.getName());
        assertEquals(0, exit, output.toString());
        return output.toString();
    }

    // Test-only direct-form-I oracle; these are the normative 48 kHz table values,
    // not coefficients copied from the implementation or fitted to a fixture.
    static double[] reference48kPowers(float[] pcm)
    {
        double[] powers = new double[pcm.length];
        double x1 = 0.0d, x2 = 0.0d, y1 = 0.0d, y2 = 0.0d;
        double h1 = 0.0d, h2 = 0.0d;
        for (int i = 0; i < pcm.length; i++)
        {
            double x = pcm[i];
            double y = 1.53512485958697d * x - 2.69169618940638d * x1
                    + 1.19839281085285d * x2 + 1.69065929318241d * y1
                    - 0.73248077421585d * y2;
            double high = y - 2.0d * y1 + y2 + 1.99004745483398d * h1
                    - 0.99007225036621d * h2;
            x2 = x1; x1 = x; y2 = y1; y1 = y; h2 = h1; h1 = high;
            powers[i] = high * high;
        }
        return powers;
    }
}
