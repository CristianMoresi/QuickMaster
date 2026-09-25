package com.quickmaster.processing.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OnsetTempoEstimatorTest
{
    @Test
    void findsBeatPeriodAmongSubdivisionsAndMissingAttacks()
    {
        List<Double> times = new ArrayList<>();
        List<Float> strengths = new ArrayList<>();
        double beat = 60.0 / 82.0;
        for (int i = 0; i < 80; i++)
        {
            if (i % 11 != 0)
            {
                times.add(0.2 + i * beat + (i % 3 - 1) * 0.002);
                strengths.add(i % 4 == 0 ? 0.9f : 0.65f);
            }
            times.add(0.2 + (i + 0.5) * beat);
            strengths.add(0.3f);
            times.add(0.2 + (i + 0.75) * beat);
            strengths.add(0.18f);
        }
        OnsetTempoEstimator.Estimate estimate = OnsetTempoEstimator.estimate(doubleValues(times), floatValues(strengths));
        assertEquals(82.0, estimate.bpm(), 1.0);
        assertTrue(estimate.confidence() >= TrackAnalysis.RELIABLE_CONFIDENCE);
    }

    @Test
    void conventionalOctaveChoiceKeepsStraight120AndSlow60()
    {
        for (int bpm : new int[] {60, 82, 120})
        {
            double[] times = new double[64];
            float[] strengths = new float[times.length];
            for (int i = 0; i < times.length; i++)
            {
                times[i] = 0.1 + i * 60.0 / bpm;
                strengths[i] = 0.8f;
            }
            assertEquals(bpm, OnsetTempoEstimator.estimate(times, strengths).bpm(), 1.0);
        }
    }

    @Test
    void uniformFastPulsesExposeTheirHalfTimeAmbiguity()
    {
        // Equal pulses contain no accent/meter cue that uniquely labels each
        // pulse a beat rather than an eighth note. Either grid is defensible,
        // but the estimator must not advertise certainty for that choice.
        for (int pulseBpm : new int[] {160, 180})
        {
            double[] times = new double[64];
            float[] strengths = new float[times.length];
            for (int i = 0; i < times.length; i++)
            {
                times[i] = 0.1 + i * 60.0 / pulseBpm;
                strengths[i] = 0.8f;
            }
            OnsetTempoEstimator.Estimate estimate = OnsetTempoEstimator.estimate(times, strengths);
            assertTrue(Math.abs(estimate.bpm() - pulseBpm) <= 1.0 || Math.abs(estimate.bpm() - pulseBpm * 0.5) <= 1.0);
            assertTrue(estimate.confidence() <= 0.65, "A competing octave must lower reported confidence");
        }
    }

    @Test
    void weakSixteenthNoteActivityDoesNotDoubleTheAccentedBeat()
    {
        double[] times = new double[320];
        float[] strengths = new float[times.length];
        for (int beat = 0; beat < 80; beat++)
            for (int subdivision = 0; subdivision < 4; subdivision++)
            {
                int i = beat * 4 + subdivision;
                times[i] = 0.2 + (beat + subdivision * 0.25) * 60.0 / 82.0;
                strengths[i] = subdivision == 0 ? 0.5f : subdivision == 2 ? 0.2f : 0.1f;
            }
        OnsetTempoEstimator.Estimate estimate = OnsetTempoEstimator.estimate(times, strengths);
        assertEquals(82.0, estimate.bpm(), 1.0, "Subdivisions are weaker than the repeated musical accents");
        assertTrue(estimate.confidence() >= TrackAnalysis.RELIABLE_CONFIDENCE);
    }

    @Test
    void irregularOnsetsAndInvalidDataStayUnknown()
    {
        Random random = new Random(4711);
        double[] times = new double[200];
        float[] strengths = new float[200];
        double time = 0.0;
        for (int i = 0; i < times.length; i++)
        {
            time += 0.25 + random.nextDouble() * 1.25;
            times[i] = time;
            strengths[i] = random.nextFloat();
        }
        assertEquals(0.0, OnsetTempoEstimator.estimate(times, strengths).bpm());
        assertEquals(0.0, OnsetTempoEstimator.estimate(new double[] {0, 1}, new float[] {1, 1}).bpm());
        times[7] = times[6];
        assertEquals(0.0, OnsetTempoEstimator.estimate(times, strengths).bpm());
    }

    @Test
    void incompatibleTemposInTwoLongHalvesHaveNoSingleAutomaticBpm()
    {
        double[] times = new double[96];
        float[] strengths = new float[96];
        for (int i = 0; i < 48; i++)
        {
            times[i] = 0.1 + i * 60.0 / 80.0;
            times[48 + i] = 40.1 + i * 60.0 / 120.0;
            strengths[i] = strengths[48 + i] = 0.8f;
        }
        assertEquals(0.0, OnsetTempoEstimator.estimate(times, strengths).bpm());
    }

    private static double[] doubleValues(List<Double> source)
    {
        double[] result = new double[source.size()];
        for (int i = 0; i < result.length; i++) result[i] = source.get(i);
        return result;
    }

    private static float[] floatValues(List<Float> source)
    {
        float[] result = new float[source.size()];
        for (int i = 0; i < result.length; i++) result[i] = source.get(i);
        return result;
    }
}
