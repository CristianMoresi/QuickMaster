package com.quickmaster.processing.analysis;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TrackAnalysisTempoTest
{
    @Test
    void conflictingSectionsCannotRegainFalseConfidenceThroughTheFallback()
    {
        int rate = 44_100;
        for (int channels : new int[] {1, 2})
        {
            float[] samples = new float[80 * rate * channels];
            for (int half = 0; half < 2; half++)
                for (int beat = 0; beat < 48; beat++)
                {
                    int first = (int)((0.1 + half * 40 + beat * 60.0 / (half == 0 ? 80 : 120)) * rate);
                    for (int i = 0; i < 150; i++)
                        for (int channel = 0; channel < channels; channel++)
                            samples[(first + i) * channels + channel] = (float)(0.8 * Math.exp(-i / 20.0));
                }
            TrackAnalysis analysis = new TrackAnalysis();
            analysis.analyze(samples, channels, rate);
            assertEquals(96, analysis.getOnsetCount());
            assertEquals(0.0, analysis.getDetectedBpm(), "No single tempo describes both sections");
            assertEquals(0.0, analysis.getConfidence());
            assertFalse(analysis.isTempoReliable());
            analysis.setManualBpm(97.0);
            assertEquals(97.0, analysis.getBpm());
            assertTrue(analysis.isTempoReliable());
        }
    }

    @Test
    void stereoAnalysisIsInvariantToIndependentPolarityAndChannelOrder()
    {
        int rate = 48_000, frames = 12 * rate;
        float[] stereo = new float[frames * 2];
        for (int beat = 0; beat < 40; beat++)
        {
            int first = (int)((0.2 + beat * 0.27) * rate);
            for (int i = 0; i < 300; i++)
            {
                stereo[(first + i) * 2] += (float)((beat % 3 == 0 ? 0.6 : 0.3) * Math.exp(-i / 45.0) * Math.cos(i * 0.31));
                stereo[(first + i + 120) * 2 + 1] += (float)((beat % 4 == 0 ? 0.7 : 0.25) * Math.exp(-i / 55.0) * Math.sin(i * 0.47));
            }
        }
        TrackAnalysis reference = new TrackAnalysis();
        reference.analyze(stereo, 2, rate);
        assertTrue(reference.getOnsetCount() >= 30);
        for (int variant = 0; variant < 3; variant++)
        {
            float[] transformed = new float[stereo.length];
            for (int frame = 0; frame < frames; frame++)
            {
                transformed[2 * frame] = variant == 2 ? stereo[2 * frame + 1] : -stereo[2 * frame];
                transformed[2 * frame + 1] = variant == 2 ? stereo[2 * frame] : (variant == 1 ? -stereo[2 * frame + 1] : stereo[2 * frame + 1]);
            }
            TrackAnalysis actual = new TrackAnalysis();
            actual.analyze(transformed, 2, rate);
            assertArrayEquals(reference.getOnsetTimesSec(), actual.getOnsetTimesSec());
            assertArrayEquals(reference.getOnsetDurationsSec(), actual.getOnsetDurationsSec());
            assertArrayEquals(reference.getOnsetStrengths(), actual.getOnsetStrengths());
            assertEquals(reference.getDetectedBpm(), actual.getDetectedBpm());
            assertEquals(reference.getConfidence(), actual.getConfidence());
        }
    }

    @Test
    void isolatedAttacksInEitherChannelAreCountedOnceAtTheirSourceTime()
    {
        for (int rate : new int[] {44_100, 48_000, 96_000})
        {
            float[] stereo = new float[rate * 12 * 2];
            for (int beat = 0; beat < 40; beat++)
            {
                int first = (int)((0.2 + beat * 0.27) * rate);
                for (int i = 0; i < 200; i++)
                    stereo[(first + i) * 2 + beat % 2] = (float)(0.7 * Math.exp(-i / 30.0));
            }
            TrackAnalysis analysis = new TrackAnalysis();
            analysis.analyze(stereo, 2, rate);
            assertEquals(40, analysis.getOnsetCount(), "Missing or duplicate panned attacks at " + rate);
            for (int beat = 0; beat < 40; beat++)
                assertEquals(0.2 + beat * 0.27, analysis.getOnsetTimesSec()[beat], 1024.0 / rate);
        }
    }

    @Test
    void malformedInputClearsDetectedStateAndPreservesManualTempo()
    {
        TrackAnalysis analysis = new TrackAnalysis();
        analysis.setManualBpm(97.0);
        for (float[] invalid : new float[][] {null, new float[] {1}, new float[] {Float.NaN, 0}, new float[] {0, Float.POSITIVE_INFINITY}})
        {
            analysis.analyze(invalid, 2, 48_000);
            assertEquals(0, analysis.getOnsetCount());
            assertEquals(0.0, analysis.getDetectedBpm());
            assertEquals(97.0, analysis.getBpm());
        }
        analysis.clearManualBpm();
        analysis.analyze(new float[4096], 2, Double.NaN);
        assertFalse(analysis.isTempoReliable());
        assertEquals(0, analysis.getOnsetCount());
    }

    @Test
    void detectsMusicalBeatAndManualOverrideCanBeClearedAfterSilence()
    {
        int rate = 44_100;
        int frames = 28 * rate;
        float[] samples = new float[frames];
        double beat = 60.0 / 82.0;
        for (int count = 0; ; count++)
        {
            int beatFrame = (int) Math.round((0.2 + count * beat) * rate);
            if (beatFrame + 100 >= frames) break;
            for (int j = 0; j < 100; j++) samples[beatFrame + j] += (float) (0.7 * Math.exp(-j / 20.0));
            int offbeatFrame = (int) Math.round((0.2 + (count + 0.5) * beat) * rate);
            for (int j = 0; j < 100 && offbeatFrame + j < frames; j++)
                samples[offbeatFrame + j] += (float) (0.25 * Math.exp(-j / 20.0));
        }
        TrackAnalysis analysis = new TrackAnalysis();
        analysis.analyze(samples, 1, rate);
        assertEquals(82.0, analysis.getDetectedBpm(), 1.0);
        assertTrue(analysis.isTempoReliable());
        analysis.setManualBpm(97.0);
        assertEquals(97.0, analysis.getBpm());
        analysis.analyze(new float[rate * 5], 1, rate);
        assertEquals(97.0, analysis.getBpm());
        analysis.clearManualBpm();
        assertEquals(0.0, analysis.getBpm());
        assertFalse(analysis.isTempoReliable());
    }
}
