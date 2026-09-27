package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link AutoGain}. */
class AutoGainTest
{
    private static final double SR = 48000.0;

    @Test
    @DisplayName("Halving the signal is compensated back to ~+6 dB / original level")
    void compensatesHalvedSignal()
    {
        AutoGain ag = new AutoGain();
        ag.prepare(SR, 1);

        int block = 1024;
        double step = 2.0 * Math.PI * 1000.0 / SR;
        int globalN = 0;
        double lastRms = 0.0;

        for (int blk = 0; blk < 200; blk++)
        {
            float[] buf = new float[block];
            for (int i = 0; i < block; i++) buf[i] = (float) (0.5 * Math.sin(step * (globalN++)));

            ag.pushReference(buf, 1);          // input level (amp 0.5)
            for (int i = 0; i < block; i++) buf[i] *= 0.5f;  // "processing" halves it
            ag.compensate(buf, 1);             // should pull it back up ~+6 dB

            double s = 0.0;
            for (float v : buf) s += (double) v * v;
            lastRms = Math.sqrt(s / block);
        }

        assertEquals(6.0, ag.getCompensationDb(), 0.5);          // +6 dB to undo the halving
        assertEquals(0.5 / Math.sqrt(2.0), lastRms, 0.02);       // level restored
    }
}
