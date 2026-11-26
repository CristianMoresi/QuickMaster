package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TapeMachine} rendered in stretches: with the transport steady and no
 * noise, hum, bleed or alignment tolerance, a machine started from rest 1.5 s
 * before a sample N gives, from N on, the output of one that ran from the
 * start, to within 1e-6 of the signal's peak (the way QuickMaster renders a
 * song in parallel stretches).
 */
class TapeMachineStretchTest
{
    private static final double WARM_SECONDS = 1.5;
    private static final int AFTER = 1237;     // N is 1.5 s plus this, an odd count
    private static final int CHECK = 4800;     // samples compared after N

    /** A programme: pink noise at -18 dBFS RMS with a low and a high tone. */
    private static double[] programme(int n, double fs)
    {
        final double[] v = TapeSignals.sat8Pink(n, -18.0, 4242);
        for (int i = 0; i < n; ++i)
            v[i] += 0.2 * Math.sin(2.0 * Math.PI * 97.0 * i / fs) + 0.1 * Math.sin(2.0 * Math.PI * 3517.0 * i / fs);
        return v;
    }

    private static TapeMachine machine(double fs, TapeMachine.Speed speed, TapeMachine.Standard standard,
                                       TapeMachine.Electronics electronics, TapeMachine.HeadModel head,
                                       TapeMachine.Transport transport)
    {
        final TapeMachine t = new TapeMachine();
        t.setAutomaticOversampling(true);
        t.setDrive(6.0);
        t.setWowFlutter(0.0);
        t.setSpeed(speed);
        t.setStandard(standard);
        t.setElectronics(electronics);
        t.setHeadModel(head);
        t.setTransport(transport);
        t.prepare(fs, 1, 1024);
        return t;
    }

    /** Renders in[from, to) in blocks; output index 0 is input index from. */
    private static double[] render(TapeMachine t, double[] in, int from, int to, int block)
    {
        final double[] out = new double[to - from];
        final double[][] buf = new double[1][block];
        for (int off = from; off < to; off += block)
        {
            final int n = Math.min(block, to - off);
            System.arraycopy(in, off, buf[0], 0, n);
            t.process(buf, n);
            System.arraycopy(buf[0], 0, out, off - from, n);
        }
        return out;
    }

    /** Worst difference after N over the peak of the continuous output there. */
    private static double apart(double fs, TapeMachine.Speed speed, TapeMachine.Standard standard,
                                TapeMachine.Electronics electronics, TapeMachine.HeadModel head,
                                TapeMachine.Transport transport)
    {
        final int warm = (int) Math.round(WARM_SECONDS * fs);
        final int n = warm + AFTER;
        final int start = n - warm;
        final double[] in = programme(n + CHECK, fs);
        final double[] whole = render(machine(fs, speed, standard, electronics, head, transport), in, 0, n + CHECK, 512);
        final double[] stretch = render(machine(fs, speed, standard, electronics, head, transport), in, start, n + CHECK, 1000);
        double worst = 0.0, peak = 0.0;
        for (int k = 0; k < CHECK; ++k)
        {
            final double a = whole[n + k], b = stretch[n - start + k];
            worst = Math.max(worst, Math.abs(a - b));
            peak = Math.max(peak, Math.abs(a));
        }
        return worst / peak;
    }

    @Test
    @DisplayName("A stretch started from rest 1.5 s before N joins one continuous pass after N (every speed, standard, electronics and head; 48 and 44.1 kHz)")
    void stretchesJoinAfterWarmUp()
    {
        double worstOfAll = 0.0;
        int identical = 0, count = 0;
        final StringBuilder failures = new StringBuilder();
        for (final double fs : new double[] { 48000.0, 44100.0 })
            for (final TapeMachine.Speed speed : TapeMachine.Speed.values())
                for (final TapeMachine.Standard standard : TapeMachine.Standard.values())
                    for (final TapeMachine.Electronics electronics : TapeMachine.Electronics.values())
                        for (final TapeMachine.HeadModel head : TapeMachine.HeadModel.values())
                        {
                            final double r = apart(fs, speed, standard, electronics, head, TapeMachine.Transport.MODULATED);
                            ++count;
                            if (r == 0.0) ++identical;
                            worstOfAll = Math.max(worstOfAll, r);
                            if (!(r < 1e-6))
                                failures.append(String.format("%.1f kHz %s %s %s %s: %.3g%n", fs / 1000.0, speed, standard, electronics, head, r));
                        }
        System.out.printf("  [tape] stretches: %d settings, %d identical to the bit, worst difference %.3g of the peak%n",
                          count, identical, worstOfAll);
        assertTrue(failures.length() == 0, failures.toString());
    }

    @Test
    @DisplayName("The same at 96 kHz (2x) and 192 kHz (1x, where a stretch starts at another phase of the carrier)")
    void stretchesJoinAtHighRates()
    {
        // At 1x the carrier moves on two of its eight phases a sample, so a
        // stretch starting at an odd sample meets it with its sign turned: the
        // push-pull pair gives the same sum.
        double worstOfAll = 0.0;
        for (final double fs : new double[] { 96000.0, 192000.0 })
            for (final TapeMachine.Electronics electronics : TapeMachine.Electronics.values())
            {
                final double r = apart(fs, TapeMachine.Speed.IPS_15, TapeMachine.Standard.NAB, electronics,
                                       TapeMachine.HeadModel.BUMP, TapeMachine.Transport.MODULATED);
                worstOfAll = Math.max(worstOfAll, r);
                assertTrue(r < 1e-6, fs + " Hz, " + electronics + ": " + r);
            }
        System.out.printf("  [tape] stretches at 96 and 192 kHz: worst difference %.3g of the peak%n", worstOfAll);
    }

    @Test
    @DisplayName("The same with the speed transport")
    void stretchesJoinWithTheSpeedTransport()
    {
        double worstOfAll = 0.0;
        for (final double fs : new double[] { 48000.0, 44100.0 })
            for (final TapeMachine.Electronics electronics : TapeMachine.Electronics.values())
            {
                final double r = apart(fs, TapeMachine.Speed.IPS_7_5, TapeMachine.Standard.CCIR, electronics,
                                       TapeMachine.HeadModel.CONTOUR, TapeMachine.Transport.SPEED);
                worstOfAll = Math.max(worstOfAll, r);
                assertTrue(r < 1e-6, fs + " Hz, " + electronics + ": " + r);
            }
        System.out.printf("  [tape] stretches with the speed transport: worst difference %.3g of the peak%n", worstOfAll);
    }
}
