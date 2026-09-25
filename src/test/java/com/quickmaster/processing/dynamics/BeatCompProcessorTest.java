package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link BeatCompProcessor}. */
class BeatCompProcessorTest
{
    private static final int SR = 44100;

    /** Clicks at a fixed tempo; a couple are much louder than the rest. */
    private static float[] clicks(double durSec, double intervalSec, double offsetSec, int... loudIdx)
    {
        int n = (int) (SR * durSec);
        float[] x = new float[n];
        Random rnd = new Random(13);
        int burst = (int) (0.008 * SR);
        double tau = 0.003 * SR;
        int k = 0;
        java.util.Set<Integer> loud = new java.util.HashSet<>();
        for (int i : loudIdx) loud.add(i);
        for (double t = offsetSec; t < durSec; t += intervalSec, k++)
        {
            double amp = loud.contains(k) ? 0.9 : 0.45;
            int start = (int) (t * SR);
            for (int j = 0; j < burst && start + j < n; j++)
            {
                double decay = Math.exp(-j / tau);
                x[start + j] += (float) ((rnd.nextDouble() * 2 - 1) * decay * amp);
            }
        }
        return x;
    }

    private static float[] gainEnvelope(BeatCompProcessor p, int frames)
    {
        p.prepare(SR, frames);
        float[] ones = new float[frames];
        java.util.Arrays.fill(ones, 1.0f);
        return p.process(ones, 1);
    }

    @Test
    @DisplayName("Release is locked to the tempo and note value")
    void tempoSyncedRelease()
    {
        TrackAnalysis ta = new TrackAnalysis();
        ta.setManualBpm(120.0);
        BeatCompProcessor p = new BeatCompProcessor();
        p.setTrackAnalysis(ta);
        p.setNote(BeatCompProcessor.NoteValue.QUARTER);
        assertEquals(500.0, p.getReleaseMs(), 0.1);
        p.setNote(BeatCompProcessor.NoteValue.EIGHTH);
        assertEquals(250.0, p.getReleaseMs(), 0.1);
        p.setNote(BeatCompProcessor.NoteValue.WHOLE);
        assertEquals(2000.0, p.getReleaseMs(), 0.1);
    }

    @Test
    @DisplayName("Louder beats are turned down toward the median; typical beats are left alone")
    void levelsLouderBeats()
    {
        float[] s = clicks(6.0, 0.5, 0.2, 3, 7);     // beats 3 and 7 are loud
        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);
        assertTrue(ta.getOnsetCount() >= 8);

        BeatCompProcessor p = new BeatCompProcessor();
        p.setTrackAnalysis(ta);
        p.setEnabled(true);
        p.setTargetDb(-6.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        float[] env = gainEnvelope(p, s.length);

        double tLoud = 0.2 + 3 * 0.5;                 // a loud beat
        double tNormal = 0.2 + 2 * 0.5;               // a typical beat
        float gLoud = minNear(env, tLoud);
        float gNormal = minNear(env, tNormal);
        assertTrue(gLoud < 0.8f, "loud beat should be turned down, was " + gLoud);
        assertTrue(gNormal > 0.9f, "typical beat should be ~untouched, was " + gNormal);
    }

    private static float minNear(float[] env, double tSec)
    {
        int i0 = (int) (tSec * SR);
        float m = 1.0f;
        for (int i = i0; i < i0 + (int) (0.03 * SR) && i < env.length; i++) m = Math.min(m, env[i]);
        return m;
    }

    @Test
    @DisplayName("Disabled and 0 dB target pass through")
    void noEffectCases()
    {
        float[] s = clicks(4.0, 0.5, 0.2, 2);
        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);
        BeatCompProcessor p = new BeatCompProcessor();
        p.setTrackAnalysis(ta);
        p.setEnabled(true);
        p.setTargetDb(0.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        for (float v : gainEnvelope(p, s.length)) assertEquals(1.0f, v, 1e-3f);

        p.setTargetDb(-6.0);
        p.setEnabled(false);
        for (float v : gainEnvelope(p, s.length)) assertEquals(1.0f, v, 1e-6f);
    }

    @Test
    @DisplayName("A -1 dB target bounds every applied sample and the actual gain-reduction meter")
    void maximumReductionIsAHardBound()
    {
        float[] s = clicks(6.0, 0.5, 0.2, 3, 7);
        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);
        BeatCompProcessor p = new BeatCompProcessor();
        p.setTrackAnalysis(ta);
        p.setEnabled(true);
        p.setTargetDb(-1.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        float[] gains = gainEnvelope(p, s.length);
        double floor = Math.pow(10.0, -1.0 / 20.0);
        for (float gain : gains)
        {
            assertTrue(gain <= 1.000001f);
            assertTrue(gain >= floor - 1.0e-6, "Gain exceeded the selected -1 dB maximum: " + gain);
        }
        assertTrue(p.getGainReductionDb() >= -1.00001,
                "Meter exceeded the selected -1 dB maximum: " + p.getGainReductionDb());
    }

    @Test
    @DisplayName("A tighter target limits an already published envelope immediately")
    void tighteningTargetDuringPlaybackCannotUseStaleStrongerReduction()
    {
        float[] s = clicks(6.0, 0.5, 0.2, 3, 7);
        TrackAnalysis ta = new TrackAnalysis();
        ta.analyze(s, 1, SR);
        BeatCompProcessor p = new BeatCompProcessor();
        p.setTrackAnalysis(ta);
        p.setEnabled(true);
        p.setTargetDb(-6.0);
        p.prepare(SR, s.length);
        p.analyze(s, 1);
        p.prepare(SR, s.length);
        p.setTargetDb(-1.0); // the UI can display this before the new analysis publishes
        float[] ones = new float[s.length];
        java.util.Arrays.fill(ones, 1.0f);
        p.process(ones, 1);
        float minimum = 1.0f;
        for (float gain : ones) minimum = Math.min(minimum, gain);
        assertTrue(minimum >= Math.pow(10.0, -1.0 / 20.0), "Stale envelope bypassed the new cap: " + minimum);
        assertTrue(p.getGainReductionDb() >= -1.0,
                "Meter reported more reduction than the selected target: " + p.getGainReductionDb());
    }

    @Test
    @DisplayName("Lookahead attenuates the actual loud transient peak, not just its later tail")
    void loudTransientPeaksAreCaughtWithoutAudioLatency()
    {
        float[] input = clicks(6.0, 0.5, 0.2, 3, 7);
        TrackAnalysis analysis = new TrackAnalysis();
        analysis.analyze(input, 1, SR);
        BeatCompProcessor processor = new BeatCompProcessor();
        processor.setTrackAnalysis(analysis);
        processor.setEnabled(true);
        processor.setTargetDb(-1.0);
        processor.prepare(SR, input.length);
        processor.analyze(input, 1);
        float[] gain = gainEnvelope(processor, input.length);
        for (int beat : new int[] {3, 7})
        {
            int start = (int)((0.2 + beat * 0.5) * SR);
            int peak = start;
            for (int frame = start; frame < start + (int)(0.008 * SR); frame++)
                if (Math.abs(input[frame]) > Math.abs(input[peak])) peak = frame;
            assertTrue(gain[peak] <= Math.pow(10.0, -0.95 / 20.0),
                    "The loud peak escaped lookahead at frame " + peak + ": gain=" + gain[peak]);
        }
        processor.prepare(SR, input.length);
        float[] output = processor.process(input.clone(), 1);
        for (int frame = 0; frame < input.length; frame++)
        {
            assertEquals(input[frame] == 0.0f, output[frame] == 0.0f, "Audio support shifted at frame " + frame);
            if (input[frame] != 0.0f) assertEquals(Math.signum(input[frame]), Math.signum(output[frame]));
        }
        assertEquals(0, processor.getLatencyFrames());
    }

    @Test
    @DisplayName("Stereo phase inversion cannot erase transient detection or linked gain reduction")
    void antiphaseStereoKeepsTransientDetectionAndImage()
    {
        float[] mono = clicks(6.0, 0.5, 0.2, 3, 7);
        float[] stereo = new float[mono.length * 2];
        for (int frame = 0; frame < mono.length; frame++)
        {
            stereo[frame * 2] = mono[frame];
            stereo[frame * 2 + 1] = -mono[frame];
        }
        TrackAnalysis analysis = new TrackAnalysis();
        analysis.analyze(stereo, 2, SR);
        assertTrue(analysis.getOnsetCount() >= 8, "Antiphase must not cancel the onset detector");
        BeatCompProcessor processor = new BeatCompProcessor();
        processor.setTrackAnalysis(analysis);
        processor.setTargetDb(-1.0);
        processor.setEnabled(true);
        processor.prepare(SR, mono.length);
        processor.analyze(stereo, 2);
        float[] output = processor.process(stereo.clone(), 2);
        boolean reduced = false;
        for (int frame = 0; frame < mono.length; frame++)
        {
            assertEquals(output[frame * 2], -output[frame * 2 + 1], 0.0f);
            if (Math.abs(stereo[frame * 2]) > 1e-4f && Math.abs(output[frame * 2]) < 0.99f * Math.abs(stereo[frame * 2])) reduced = true;
        }
        assertTrue(reduced, "Loud antiphase transients must receive linked attenuation");
    }

    @Test
    @DisplayName("The measured release time follows the selected note length")
    void renderedReleaseMatchesTempoAndRemainsMonotonic()
    {
        float[] input = java.util.Arrays.copyOf(clicks(4.0, 0.5, 0.2, 3, 7), 7 * SR);
        TrackAnalysis analysis = new TrackAnalysis();
        analysis.analyze(input, 1, SR);
        analysis.setManualBpm(120.0);
        for (var note : new BeatCompProcessor.NoteValue[] { BeatCompProcessor.NoteValue.EIGHTH, BeatCompProcessor.NoteValue.QUARTER })
        {
            BeatCompProcessor processor = new BeatCompProcessor();
            processor.setTrackAnalysis(analysis);
            processor.setNote(note);
            processor.setTargetDb(-1.0);
            processor.setEnabled(true);
            processor.prepare(SR, input.length);
            processor.analyze(input, 1);
            float[] gains = gainEnvelope(processor, input.length);
            int releaseStart = (int)(3.7 * SR);
            for (int frame = releaseStart; frame < (int)(4.1 * SR); frame++)
                if (gains[frame] <= gains[releaseStart]) releaseStart = frame;
            assertTrue(gains[releaseStart] < 0.95f);
            double recovered = 1.0 - (1.0 - gains[releaseStart]) / Math.E;
            int crossing = releaseStart;
            while (crossing < gains.length && gains[crossing] < recovered) crossing++;
            assertTrue(crossing < gains.length, "Release did not recover");
            assertEquals(note.beats * 0.5, (crossing - releaseStart) / (double)SR, 0.002);
            for (int frame = releaseStart + 1; frame < gains.length; frame++)
                assertTrue(gains[frame] >= gains[frame - 1], "Unprompted gain dip during release");
        }
    }
}
