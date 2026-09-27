package com.quickmaster.processing.eq;

import com.dspark.effects.MasterEqualizer;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.ProcessingPipeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EqMeterTimelineTest {
    private static EqualizerProcessor eq() {
        EqualizerProcessor eq = new EqualizerProcessor();
        eq.setNumBands(1);
        MasterEqualizer.Band band = eq.getBand(0);
        band.enabled = true; band.dynamic = true; band.phase = MasterEqualizer.BandPhase.MINIMUM;
        band.type = MasterEqualizer.BandType.GAIN; band.gainDb = 0;
        band.threshold = -20; band.aboveRangeDb = 6; band.aboveBoost = false; band.aboveRatio = 4;
        eq.setBand(0, band); return eq;
    }
    @Test void metersSurvivePrepareAndCacheReuseAndDescribeTheRenderedGain() {
        for (int factor : new int[]{1, 2, 4}) {
            int rate = 32000; float[] samples = new float[rate * 2];
            for (int i = 0; i < samples.length; i++) samples[i] = i < rate ? .8f : .001f;
            WavFile file = new WavFile("generated", rate, 1, samples, 32, true);
            EqualizerProcessor eq = eq(); ProcessingPipeline pipeline = new ProcessingPipeline(); pipeline.addProcessor(eq);
            pipeline.processOversampled(file, factor, null);
            double high = eq.getBandMeterAt(0, 25000, rate, false), low = eq.getBandMeterAt(0, 63000, rate, false);
            assertTrue(high < -3, "High-level passage must show gain reduction");
            assertTrue(Math.abs(low) < Math.abs(high), "Quiet passage must release");
            double actual = 20 * Math.log10(Math.abs(file.getSamples()[25000] / samples[25000]));
            assertEquals(actual, high, .15, "Timeline tracks actual PCM gain, including oversampling");
            assertTrue(Double.isFinite(eq.getBandMeterAt(0, 25000, rate, true)));
            EqualizerProcessor copy = eq(); copy.adoptMeters(eq); copy.prepare(rate, samples.length);
            assertEquals(high, copy.getBandMeterAt(0, 25000, rate, false));
            double[] magnitude = new double[1];
            copy.getMagnitudeResponseAt(MasterEqualizer.Channel.STEREO, new double[]{1000}, magnitude, 25000, rate);
            assertEquals(high, 20 * Math.log10(magnitude[0]), .001);
        }
    }
}
