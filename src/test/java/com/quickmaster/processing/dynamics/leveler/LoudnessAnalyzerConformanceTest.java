package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;

class LoudnessAnalyzerConformanceTest
{
    @Test
    void g101UsesAbsolutePowerGateBeforeRelativeGate()
    {
        double[] blocks = new double[101];
        for (int i = 0; i < 99; i++) blocks[i] = -80.0d;
        blocks[99] = -20.0d;
        blocks[100] = -45.0d;

        double[] result = LoudnessAnalyzer.integratedGateFromBlockLufs(blocks);

        assertEquals(-22.996588028312978d, result[0], 1.0e-12d);
        assertEquals(-32.99658802831298d, result[1], 1.0e-12d);
        assertEquals(-20.0d, result[2], 1.0e-12d);
    }

    @Test
    void stereoPowerSumDoesNotCancelAntiphaseAtApprovedRates()
    {
        for (int sampleRate : new int[] { 44_100, 48_000, 96_000 })
        {
            int frames = sampleRate * 4;
            float[] inPhase = new float[frames * 2];
            float[] antiPhase = new float[frames * 2];
            for (int frame = 0; frame < frames; frame++)
            {
                float sample = (float) (0.2d * StrictMath.sin(
                        2.0d * StrictMath.PI * 997.0d * frame / sampleRate));
                inPhase[frame * 2] = sample;
                inPhase[frame * 2 + 1] = sample;
                antiPhase[frame * 2] = sample;
                antiPhase[frame * 2 + 1] = -sample;
            }
            LoudnessAnalyzer analyzer = new LoudnessAnalyzer();
            LoudnessTimeline first = analyzer.analyze(inPhase,
                    new AudioFormat(sampleRate, 2, frames), new CancellationToken());
            LoudnessTimeline second = analyzer.analyze(antiPhase,
                    new AudioFormat(sampleRate, 2, frames), new CancellationToken());
            assertNotNull(first);
            assertNotNull(second);
            assertTrue(first.integrated().present());
            assertTrue(second.integrated().present());
            assertEquals(first.integrated().lufs(), second.integrated().lufs(), 1.0e-12d);
        }
    }

    @Test
    void incompleteTailExistsOnceAndIsMarkedInvalid()
    {
        int sampleRate = 48_000;
        float[] pcm = new float[sampleRate];
        LoudnessTimeline timeline = new LoudnessAnalyzer().analyze(pcm,
                new AudioFormat(sampleRate, 1, sampleRate), new CancellationToken());

        assertEquals(10, timeline.momentaryCount());
        for (int i = 0; i < 7; i++) assertTrue(timeline.momentaryValidAt(i));
        for (int i = 7; i < 10; i++)
        {
            assertFalse(timeline.momentaryValidAt(i));
            assertEquals(Double.doubleToRawLongBits(0.0d),
                    Double.doubleToRawLongBits(timeline.momentaryPowerAt(i)));
        }
        for (int i = 0; i < 10; i++) assertFalse(timeline.shortTermValidAt(i));
        assertFalse(timeline.integrated().present());
    }
}
