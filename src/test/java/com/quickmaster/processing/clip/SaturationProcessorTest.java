package com.quickmaster.processing.clip;

import com.dspark.analysis.LoudnessMeter;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.processing.ProcessingPipeline;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The saturation stage in the real pipeline: its curves as before, its analog models exact against their linear response. */
class SaturationProcessorTest
{
    private static final int RATE = 48000;

    private static double peakDb(float[] x)
    {
        double p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return 20 * Math.log10(p);
    }

    private static float[] render(SaturationProcessor p, float[] in)
    {
        ProcessingPipeline pipe = new ProcessingPipeline();
        pipe.addProcessor(p);
        pipe.prepare(RATE, in.length);
        return pipe.analyzeAndRender(in, 2, 0, null, null, null);
    }

    private static SaturationProcessor stage(SaturationType type, TapePreset preset, double db)
    {
        SaturationProcessor p = new SaturationProcessor();
        p.setEnabled(true);
        p.setType(type);
        if (preset != null) p.setTapePreset(preset);
        p.setSatDb(db);
        return p;
    }

    @Test
    void theCurvesRenderAsTheCurveProcessorDoes()
    {
        float[] in = AnalogSaturationTest.programme(8, 11);
        for (SaturationType t : new SaturationType[] { SaturationType.SINE, SaturationType.TANH,
                SaturationType.GOLDEN })
        {
            SoftClipProcessor curve = new SoftClipProcessor();
            curve.setEnabled(true);
            curve.setCurve(t);
            curve.setSatDb(3.0);
            ProcessingPipeline pipe = new ProcessingPipeline();
            pipe.addProcessor(curve);
            pipe.prepare(RATE, in.length);
            float[] expected = pipe.analyzeAndRender(in, 2, 0, null, null, null);
            assertArrayEquals(expected, render(stage(t, null, 3.0), in), t.name());
        }
    }

    /** The worst reading of the stage's meter over the song, in dB, after checking it never shows a gain. */
    private static double worstMeter(SaturationProcessor p, int frames)
    {
        double worst = 0;
        for (long f = 0; f < frames; f += 512)
        {
            double gr = p.getGrAtPosition(f);
            assertTrue(gr <= 0.0, "meter shows a gain of " + gr + " dB");
            worst = Math.min(worst, gr);
        }
        return worst;
    }

    @Test
    void everyModelTakesOffExactlyTheAmountFromItsLinearResponseAndKeepsTheLoudness()
    {
        float[] in = AnalogSaturationTest.programme(20, 12);
        double lufs = LoudnessMeter.measureIntegrated(in, RATE, 2);
        for (SaturationType t : new SaturationType[] { SaturationType.TUBE_PREAMP, SaturationType.TRANSFORMER,
                SaturationType.MULTI_STAGE, SaturationType.TAPE })
        {
            SaturationProcessor p = stage(t, TapePreset.MASTER_15, 2.0);
            float[] out = render(p, in);
            float[] linear = AnalogSaturationTest.linearResponse(in, 2, AnalogModels.factory(t, TapePreset.MASTER_15));
            assertEquals(peakDb(linear), p.getLinearPeakDb(), 1e-4, t + " linear response");
            assertEquals(2.0, AnalogSaturationTest.mostLostDb(linear, out, 2), 1e-3, t.name());
            double moved = LoudnessMeter.measureIntegrated(out, RATE, 2) - lufs;
            assertTrue(Math.abs(moved) <= AnalogSaturation.LOUDNESS_TOLERANCE_DB + 0.01, t + " loudness " + moved);
            assertNotEquals(0.0, p.getSolvedDriveDb(), t.name());
            assertEquals(-2.0, worstMeter(p, in.length / 2), 1e-3, t + " meter");
        }
    }

    @Test
    void aLimitedSongIsSaturatedOnlyAsFarAsAsked()
    {
        float[] in = AnalogSaturationTest.limited(20, 16);
        for (SaturationType t : new SaturationType[] { SaturationType.TRANSFORMER, SaturationType.TAPE })
            for (TapePreset preset : new TapePreset[] { TapePreset.COLORED_15, TapePreset.WARM_7_5, TapePreset.VINTAGE_3_75 })
            {
                SaturationProcessor p = stage(t, preset, 1.0);
                float[] out = render(p, in);
                String name = t == SaturationType.TAPE ? t + "/" + preset : t.name();
                var f = AnalogModels.factory(t, preset);
                assertEquals(1.0, AnalogSaturationTest.mostLostDb(in, out, 2, f), 1e-3, name);
                assertTrue(!p.isCompletedByKnee(), name + " needed the knee for 1 dB");
                double most = f.get().maxDriveDb();
                assertTrue(p.getSolvedDriveDb() < most - 6.0, name + " drive " + p.getSolvedDriveDb());
                assertEquals(-1.0, worstMeter(p, in.length / 2), 1e-3, name + " meter");
                if (t != SaturationType.TAPE) break;
            }
    }

    @Test
    void everyTapeAlignmentRendersAndDiffers()
    {
        float[] in = AnalogSaturationTest.programme(12, 13);
        float[] first = null;
        for (TapePreset preset : TapePreset.values())
        {
            SaturationProcessor p = stage(SaturationType.TAPE, preset, 1.5);
            float[] out = render(p, in);
            assertEquals(1.5, AnalogSaturationTest.mostLostDb(in, out, 2,
                    AnalogModels.factory(SaturationType.TAPE, preset)), 1e-3, preset.name());
            if (first == null) first = out;
            else assertTrue(!Arrays.equals(first, out), preset + " sounds as the first alignment");
        }
    }

    @Test
    void theStreamedCircuitPlaysWhatTheWholeRenderMade()
    {
        float[] in = AnalogSaturationTest.programme(12, 14);
        SaturationProcessor p = stage(SaturationType.TUBE_PREAMP, null, 3.0);
        float[] whole = render(p, in);
        // The same stage, its solution kept, streamed through the pipeline's block path.
        ProcessingPipeline pipe = new ProcessingPipeline();
        pipe.addProcessor(p);
        pipe.prepare(RATE, in.length);
        int lat = p.getLatencyFrames();
        float[] padded = Arrays.copyOf(in, in.length + 2 * lat);
        float[] streamed = pipe.executeBlocks(padded.clone(), 2, 1024);
        double diff = 0;
        for (int i = 0; i < in.length; i++) diff = Math.max(diff, Math.abs(streamed[i + 2 * lat] - whole[i]));
        assertTrue(diff < 1e-5, "stream differs from the whole render by " + diff);
    }

    @Test
    void zeroIsABypassForEveryType()
    {
        float[] in = AnalogSaturationTest.programme(6, 15);
        for (SaturationType t : SaturationType.values())
        {
            SaturationProcessor p = stage(t, TapePreset.VINTAGE_3_75, 0.0);
            assertArrayEquals(in, render(p, in), t.name());
            assertEquals(0, p.getLatencyFrames(), t.name());
        }
    }

    @Test
    void presetsOfEarlierVersionsKeepTheirCurve()
    {
        assertSame(SaturationType.SINE, SaturationType.fromPreset(null, "TUBE"));
        assertSame(SaturationType.TANH, SaturationType.fromPreset(null, "TAPE"));
        assertSame(SaturationType.GOLDEN, SaturationType.fromPreset(null, "TRANSFORMER"));
        assertSame(SaturationType.SINE, SaturationType.fromPreset(null, null));
        assertSame(SaturationType.TAPE, SaturationType.fromPreset("TAPE", "TUBE"));
        for (SaturationType t : SaturationType.values())
            assertSame(SaturationType.fromPreset(null, t.legacyAlgorithm()).isCurve(), true, t.name());
        ChainPreset p = new ChainPreset();
        assertEquals(null, p.saturationType);
    }
}
