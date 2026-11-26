package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link TapeTransport}. */
class TapeTransportTest
{
    private static final double FS = 48000.0;

    @Test
    @DisplayName("The control-rate deviation matches a per-sample one under 100 Hz to better than -80 dB")
    void controlRateMatchesEverySample()
    {
        TapeTransport.Settings s = new TapeTransport.Settings();
        s.noise = 0.0;
        s.scrape = 0.0;
        TapeTransport tape = new TapeTransport();
        tape.prepare(FS, 4800.0, 15.0, s);
        final double dt = 1.0 / FS;
        final double restore = dt / s.restoreSeconds;
        double cap = 0.0;
        double reel = 0.0;
        double ref = 4800.0;
        final int n = 96000;
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; ++i)
        {
            a[i] = tape.next(1.0);
            cap += (15.0 / 3.0) * dt;
            cap -= Math.floor(cap);
            reel += (15.0 * 0.04) * dt;
            reel -= Math.floor(reel);
            final double twoPi = 2.0 * 3.14159265358979323846;
            final double dev = s.reel * Math.sin(twoPi * reel)
                             + s.capstan * (Math.sin(twoPi * cap) + s.capstanSecond * Math.sin(2.0 * twoPi * cap));
            ref += DspMath.clamp(-dev + restore * (4800.0 - ref), -s.maxStep, s.maxStep);
            b[i] = ref;
        }
        final double k = 1.0 - Math.exp(-2.0 * 3.14159265358979323846 * 100.0 / FS);
        double e1 = 0.0, e2 = 0.0, m1 = 0.0, m2 = 0.0, err = 0.0, mod = 0.0;
        for (int i = 0; i < n; ++i)
        {
            e1 += k * ((a[i] - b[i]) - e1);
            e2 += k * (e1 - e2);
            m1 += k * ((b[i] - 4800.0) - m1);
            m2 += k * (m1 - m2);
            if (i > 24000) { err += e2 * e2; mod += m2 * m2; }
        }
        final double db = 10.0 * Math.log10(err / mod);
        assertTrue(db < -80.0, "control rate against every sample: " + db + " dB");
    }

    /**
     * Golden values of the noise stream. The C++ draws one value per control
     * point from the 32-bit LCG x = 1664525 x + 1013904223 seeded 0x2C1B3C6D:
     * the first states are 0x3A8972E8, 0x31DA3927, 0x4C18645A, 0x9E71E7F1,
     * giving the draws ((x >> 8) / 2^23 - 1) / 4 = -0.13567012548446655,
     * -0.1526319682598114, -0.10137641429901123, 0.05946275591850281. The
     * delays below were derived from them by replaying the C++ next() and
     * advanceControl() arithmetic in an independent script (unbounded
     * integers reduced mod 2^32 for the LCG, IEEE doubles for the rest) with
     * the reel and capstan off, noise 0.0004 and scrape 0.003, nominal 4800
     * samples at 15 ips and 48 kHz, depth 1.
     */
    @Test
    @DisplayName("The noise stream reproduces the C++ LCG and its filters (golden delays)")
    void goldenNoiseStream()
    {
        TapeTransport.Settings s = new TapeTransport.Settings();
        s.reel = 0.0;
        s.capstan = 0.0;
        TapeTransport tape = new TapeTransport();
        tape.prepare(FS, 4800.0, 15.0, s);
        final int[] at = { 0, 1, 15, 16, 17, 31, 32, 47 };
        final double[] expected = {
            4800.000002349152, 4800.000007047424, 4800.000319462544, 4800.000359123872,
            4800.000400863974, 4800.001203480572, 4800.001274992842, 4800.002428353051 };
        double[] out = new double[48];
        for (int i = 0; i < out.length; ++i) out[i] = tape.next(1.0);
        for (int j = 0; j < at.length; ++j)
            assertEquals(expected[j] - 4800.0, out[at[j]] - 4800.0, 2e-12, "sample " + at[j]);

        // reset() restarts the same stream.
        tape.reset();
        for (int i = 0; i < out.length; ++i) assertEquals(out[i], tape.next(1.0), 0.0);
    }

    @Test
    @DisplayName("At depth 0 and nominal speed the delay stays at the head distance")
    void neutralDepth()
    {
        TapeTransport tape = new TapeTransport();
        tape.prepare(FS, 96.0, 15.0);
        for (int i = 0; i < 48000; ++i) assertEquals(96.0, tape.next(0.0), 0.0);
        assertEquals(96.0, tape.getNominal(), 0.0);
        assertEquals(1.0, tape.getSpeedRatio(), 0.0);
    }

    @Test
    @DisplayName("A fresh instance and a reset one give the same delays")
    void resetEqualsFresh()
    {
        TapeTransport used = new TapeTransport();
        used.prepare(FS, 480.0, 15.0);
        used.setTargetDelay(700.0);
        for (int i = 0; i < 30000; ++i) used.next(1.0);
        used.setTargetDelay(480.0);
        used.setSpeedNow(15.0);
        used.reset();
        TapeTransport fresh = new TapeTransport();
        fresh.prepare(FS, 480.0, 15.0);
        for (int i = 0; i < 48000; ++i) assertEquals(fresh.next(0.7), used.next(0.7), 0.0);
    }

    @Test
    @DisplayName("setSpeed keeps the tape moving and glides with the slow-down inertia")
    void speedGlide()
    {
        TapeTransport tape = new TapeTransport();
        tape.prepare(FS, 4800.0, 15.0);
        tape.setSpeed(7.5);
        assertEquals(2.0, tape.getSpeedRatio(), 0.0);   // still running at 15 ips
        tape.setSpeed(7.5);                             // unchanged: ignored
        tape.setSpeed(Double.NaN);
        tape.setSpeed(-1.0);
        assertEquals(2.0, tape.getSpeedRatio(), 0.0);
        final double down = Math.exp(-(1.0 / FS) / 0.7);
        double expected = 2.0;
        for (int i = 0; i < 48000; ++i)
        {
            tape.next(0.0);
            expected = 1.0 + down * (expected - 1.0);
        }
        assertEquals(expected, tape.getSpeedRatio(), 1e-12);
        assertEquals(Math.exp(-1.0 / 0.7), tape.getSpeedRatio() - 1.0, 1e-6);

        // Speeding up follows the other time constant.
        TapeTransport fast = new TapeTransport();
        fast.prepare(FS, 4800.0, 7.5);
        fast.setSpeed(15.0);
        assertEquals(0.5, fast.getSpeedRatio(), 0.0);
        for (int i = 0; i < 48000; ++i) fast.next(0.0);
        assertEquals(0.5 * Math.exp(-1.0 / 0.45), 1.0 - fast.getSpeedRatio(), 1e-6);

        // setSpeedNow jumps.
        fast.setSpeedNow(30.0);
        assertEquals(1.0, fast.getSpeedRatio(), 0.0);
    }

    @Test
    @DisplayName("setTargetDelay glides the delay to the new value, bounded by maxStep")
    void targetDelayGlide()
    {
        TapeTransport tape = new TapeTransport();
        tape.prepare(FS, 4800.0, 15.0);
        tape.setTargetDelay(2400.0);
        double prev = tape.getDelay();
        for (int i = 0; i < 20 * 48000; ++i)
        {
            final double d = tape.next(0.0);
            assertTrue(Math.abs(d - prev) <= 0.02 + 1e-9);   // the bound plus the rounding of the sum
            prev = d;
        }
        assertEquals(2.0, tape.getSpeedRatio(), 1e-9);
        assertEquals(2400.0, tape.getDelay(), 0.01);

        tape.setTargetDelayNow(1200.0);
        assertEquals(1200.0, tape.getDelay(), 0.0);
        assertEquals(4.0, tape.getSpeedRatio(), 0.0);
        tape.setTargetDelay(Double.NaN);
        tape.setTargetDelayNow(Double.POSITIVE_INFINITY);
        assertEquals(1200.0, tape.getDelay(), 0.0);
        assertEquals(1200.0, tape.next(0.0), 1e-9);
    }

    @Test
    @DisplayName("The delay stays inside its limits")
    void limits()
    {
        TapeTransport tape = new TapeTransport();
        tape.prepare(FS, 96.0, 15.0);
        tape.setLimits(100.0, 200.0);
        assertEquals(100.0, tape.getDelay(), 0.0);
        tape.syncDelay(500.0);
        assertEquals(200.0, tape.getDelay(), 0.0);
        tape.setTargetDelayNow(1.0);
        assertEquals(100.0, tape.getDelay(), 0.0);
        tape.setLimits(-5.0, -10.0);   // lo becomes 0, hi becomes lo
        assertEquals(0.0, tape.getDelay(), 0.0);
        // prepare() restores the default limits (3 samples up).
        tape.prepare(FS, 96.0, 15.0);
        tape.syncDelay(1.0);
        assertEquals(3.0, tape.getDelay(), 0.0);
        // Invalid rates are ignored.
        tape.prepare(Double.NaN, 1000.0, 15.0);
        tape.prepare(0.0, 1000.0, 15.0);
        assertEquals(96.0, tape.getNominal(), 0.0);
        // The head distance is at least one sample.
        tape.prepare(FS, -4.0, 15.0);
        assertEquals(1.0, tape.getNominal(), 0.0);
    }

    /** The deviation of every sample, recovered from consecutive delays. */
    private static double[] deviations(double ips, int n)
    {
        TapeTransport.Settings s = new TapeTransport.Settings();
        s.noise = 0.0;
        s.scrape = 0.0;
        s.reel = 0.0;
        s.capstanSecond = 0.0;
        TapeTransport tape = new TapeTransport();
        tape.prepare(FS, 4800.0, ips, s);
        final double restore = (1.0 / FS) / s.restoreSeconds;
        double prev = tape.getDelay();
        double[] dev = new double[n];
        for (int i = 0; i < n; ++i)
        {
            final double d = tape.next(1.0);
            dev[i] = -(d - prev) + restore * (4800.0 - prev);
            prev = d;
        }
        return dev;
    }

    @Test
    @DisplayName("The capstan runs at 5 Hz at 15 ips, and the deviation deepens by sqrt(15 / ips)")
    void capstanRateAndDepth()
    {
        double[] at15 = deviations(15.0, 96000);
        double[] at75 = deviations(7.5, 96000);
        int crossings15 = 0;
        int crossings75 = 0;
        double peak15 = 0.0;
        double peak75 = 0.0;
        for (int i = 1; i < at15.length; ++i)
        {
            if ((at15[i - 1] < 0.0) != (at15[i] < 0.0)) ++crossings15;
            if ((at75[i - 1] < 0.0) != (at75[i] < 0.0)) ++crossings75;
            peak15 = Math.max(peak15, Math.abs(at15[i]));
            peak75 = Math.max(peak75, Math.abs(at75[i]));
        }
        // Two seconds: 5 Hz gives 20 zero crossings, 2.5 Hz gives 10.
        assertEquals(20, crossings15, 1);
        assertEquals(10, crossings75, 1);
        assertEquals(0.0006, peak15, 0.0006 * 1e-3);
        assertEquals(Math.sqrt(2.0), peak75 / peak15, 1e-3);
    }

    @Test
    @DisplayName("The delay never moves by more than maxStep per sample")
    void stepIsBounded()
    {
        TapeTransport.Settings s = new TapeTransport.Settings();
        s.maxStep = 0.001;
        s.capstan = 0.05;
        s.scrape = 0.05;
        TapeTransport tape = new TapeTransport();
        tape.prepare(FS, 960.0, 3.75, s);
        double prev = tape.getDelay();
        for (int i = 0; i < 96000; ++i)
        {
            final double d = tape.next(1.0);
            assertTrue(Double.isFinite(d) && Math.abs(d - prev) <= 0.001 + 1e-9);
            prev = d;
        }
    }
}
