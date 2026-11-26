package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DryPhaseTest
{
    @Test
    @DisplayName("Unit gain, the lead of two one-pole high-passes, the input to the bit faded out, no state from a non-finite value (DryPhase_leads_without_touching_the_level)")
    void leadsWithoutTouchingTheLevel()
    {
        DryPhase lead = new DryPhase();
        lead.prepare(48000.0, 1);
        lead.setCorner(24.0);
        lead.aim(false);
        lead.land();
        for (int i = 0; i < 480; i++)
        {
            final double x = 0.3 * Math.sin(0.01 * i);
            assertEquals(x, lead.processSample(0, x, lead.advance()), 0.0);
        }
        lead.aim(true);
        lead.land();
        for (double hz : new double[] { 10.0, 20.0, 30.0, 100.0, 1000.0, 10000.0 })
        {
            lead.reset();
            final double w = 2.0 * Math.PI * hz / 48000.0;
            double re = 0.0, im = 0.0;
            for (int i = 0; i < 96000; i++)
            {
                final double y = lead.processSample(0, Math.sin(w * i), lead.advance());
                if (i >= 48000) { re += y * Math.sin(w * i); im += y * Math.cos(w * i); }
            }
            re /= 24000.0;
            im /= 24000.0;
            assertEquals(1.0, Math.sqrt(re * re + im * im), 1e-3, hz + " Hz");
            assertEquals(2.0 * Math.atan(Math.tan(Math.PI * 24.0 / 48000.0) / Math.tan(0.5 * w)), Math.atan2(im, re), 1e-3, hz + " Hz");
        }
        final double bad = lead.processSample(0, Double.POSITIVE_INFINITY, lead.advance());
        assertTrue(!Double.isNaN(bad));
        assertTrue(Double.isFinite(lead.processSample(0, 0.1, lead.advance())));
    }

    @Test
    @DisplayName("The fade takes 20 ms (960 frames at 48 kHz); the Butterworth share is 0.8")
    void fadeTimeAndShare()
    {
        assertEquals(0.8, DryPhase.BUTTERWORTH_SHARE, 0.0);
        DryPhase lead = new DryPhase();
        lead.prepare(48000.0, 1);
        lead.aim(true);
        int frames = 0;
        while (lead.advance() < 1.0 && frames < 2000) frames++;
        assertEquals(959, frames);   // the 960th advance lands on 1
        lead.aim(false);
        frames = 0;
        while (lead.advance() > 0.0 && frames < 2000) frames++;
        assertEquals(959, frames);
        DryPhase bad = new DryPhase();
        bad.prepare(Double.NaN, 1);
        bad.aim(true);
        assertEquals(1.0, bad.advance(), 0.0);   // a rate that is no rate fades in one frame
    }

    @Test
    @DisplayName("Channels that were not prepared pass; the corner clamps at 0.45 of the rate")
    void guards()
    {
        DryPhase lead = new DryPhase();
        assertEquals(0.7, lead.processSample(0, 0.7, 1.0), 0.0);   // not prepared
        lead.prepare(48000.0, 2);
        assertEquals(0.7, lead.processSample(2, 0.7, 1.0), 0.0);
        assertEquals(0.7, lead.processSample(-1, 0.7, 1.0), 0.0);
        lead.setCorner(30000.0);
        DryPhase edge = new DryPhase();
        edge.prepare(48000.0, 1);
        edge.setCorner(0.45 * 48000.0);
        for (int i = 0; i < 50; i++)
            assertEquals(edge.processSample(0, Math.sin(i), 1.0), lead.processSample(0, Math.sin(i), 1.0), 0.0);
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give identical output through a fade, with offsets; reset() equals fresh")
    void blockSizeIndependenceAndReset()
    {
        double[] signal = new double[9000];
        for (int i = 0; i < signal.length; i++) signal[i] = Math.sin(0.003 * i) + 0.2 * Math.sin(0.9 * i);
        double[] reference = null;
        for (int block : new int[] { 4096, 512, 37, 1 })
        {
            DryPhase lead = new DryPhase();
            lead.prepare(48000.0, 2);
            lead.setCorner(DryPhase.BUTTERWORTH_SHARE * 30.0);
            lead.aim(true);
            double[][] x = { signal.clone(), signal.clone() };
            for (int pos = 0; pos < signal.length; pos += block)
            {
                if (pos >= 4000) lead.aim(false);
                lead.process(x, pos, Math.min(block, signal.length - pos));
            }
            assertArrayEquals(x[0], x[1]);
            if (reference == null) reference = x[0];
            else if (block >= 512) assertArrayEquals(reference, x[0], "block " + block);
        }

        DryPhase used = new DryPhase(), fresh = new DryPhase();
        for (DryPhase p : new DryPhase[] { used, fresh })
        {
            p.prepare(44100.0, 1);
            p.setCorner(20.0);
            p.aim(true);
            p.land();
        }
        double[][] junk = { signal.clone() };
        used.process(junk, 500);
        used.reset();
        double[][] a = { signal.clone() }, b = { signal.clone() };
        used.process(a, 800);
        fresh.process(b, 800);
        assertArrayEquals(b[0], a[0]);
    }

    @Test
    @DisplayName("A constant amount is independent of the partition")
    void constantAmountPartitions()
    {
        double[] signal = new double[5000];
        for (int i = 0; i < signal.length; i++) signal[i] = Math.cos(0.011 * i);
        double[] reference = null;
        for (int block : new int[] { 4096, 512, 37, 1 })
        {
            DryPhase lead = new DryPhase();
            lead.prepare(48000.0, 1);
            lead.setCorner(40.0);
            lead.aim(true);
            lead.land();
            double[][] x = { signal.clone() };
            for (int pos = 0; pos < signal.length; pos += block)
                lead.process(x, pos, Math.min(block, signal.length - pos));
            if (reference == null) reference = x[0];
            else assertArrayEquals(reference, x[0], "block " + block);
        }
    }
}
