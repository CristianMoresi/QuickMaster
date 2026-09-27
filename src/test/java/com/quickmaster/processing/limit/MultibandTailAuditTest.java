package com.quickmaster.processing.limit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultibandTailAuditTest {
    @Test void detectedBandPeaksIncludeLatencyAlignedTailInsteadOfOnlyTheCausalPrefix() throws Exception {
        for (int rate : new int[]{8000, 44100, 48000, 96000}) for (int frames : new int[]{257, 8193}) {
            float[] source = new float[frames]; source[frames - 1] = .8f;
            var crossover = new com.dspark.effects.MultibandCrossover();
            crossover.prepare(rate, 1, MultibandLimiterProcessor.CROSSOVERS);
            int latency = crossover.getLatency();
            float[][] bands = crossover.splitWhole(java.util.Arrays.copyOf(source, frames + latency), 1);
            var limiter = new MultibandLimiterProcessor(); limiter.setEnabled(true);
            limiter.prepare(rate, frames); limiter.analyze(source, 1);
            var field = MultibandLimiterProcessor.class.getDeclaredField("bandPeak"); field.setAccessible(true);
            double[] actual = (double[]) field.get(limiter);
            for (int band = 0; band < 4; band++) {
                double expected = 0;
                for (int f = latency; f < latency + frames; f++) expected = Math.max(expected, Math.abs(bands[band][f]));
                assertEquals(expected, actual[band], 2e-7, "Peak excludes final transient: rate="+rate+", frames="+frames+", band="+band);
            }
        }
    }
    @Test void finalSourceImpulseIsIncludedInEveryBandsPeakAndGainAnalysis() {
        for (int frames : new int[]{257, 8193}) {
            float[] source = new float[frames]; source[frames - 1] = .8f;
            MultibandLimiterProcessor limiter = new MultibandLimiterProcessor(); limiter.setEnabled(true);
            for (int band = 0; band < 4; band++) limiter.setPushDb(band, 3);
            limiter.prepare(48000, frames); limiter.analyze(source, 1);
            for (int band = 0; band < 4; band++)
                assertEquals(-3, limiter.getBandGrDeepest(band, 0, frames), .01, "Ignored tail: frames=" + frames + " band=" + band);
        }
    }
}
