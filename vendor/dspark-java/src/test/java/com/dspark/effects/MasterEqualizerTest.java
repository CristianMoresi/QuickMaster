package com.dspark.effects;

import com.dspark.core.Biquad;
import com.dspark.core.BiquadCoeffs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MasterEqualizer}: the unification equivalences (a routed
 * GAIN band reproduces L/R, Mid/Side and stereo-width processing), the static
 * filter path, linear-phase routing invariants (no Mid/Side leakage), the
 * dynamics ballistics, and latency reporting.
 */
class MasterEqualizerTest
{
    private static final int SR = 48000;
    private static final float TOL = 1e-5f;

    /* ---- helpers ---- */

    private static float[] stereoSignal(int frames)
    {
        float[] b = new float[frames * 2];
        for (int i = 0; i < frames; i++)
        {
            b[2 * i]     = (float) (0.6 * Math.sin(i * 0.017 + 0.3));
            b[2 * i + 1] = (float) (0.5 * Math.sin(i * 0.011 + 1.1));
        }
        return b;
    }

    private static MasterEqualizer.Band band(MasterEqualizer.BandType type,
                                             MasterEqualizer.Channel ch, double freq, double gainDb)
    {
        MasterEqualizer.Band b = new MasterEqualizer.Band();
        b.type = type; b.channel = ch; b.frequency = freq; b.gainDb = gainDb;
        b.phase = MasterEqualizer.BandPhase.MINIMUM;
        return b;
    }

    private static float maxAbs(float[] b)
    {
        float m = 0f;
        for (float v : b) m = Math.max(m, Math.abs(v));
        return m;
    }

    /* ---- unification equivalences ---- */

    @Test
    @DisplayName("GAIN bands on LEFT/RIGHT reproduce independent L/R volume")
    void gainBandsAreLrVolume()
    {
        float[] in = stereoSignal(512);
        double gLdb = 3.0, gRdb = -4.0;

        MasterEqualizer eq = new MasterEqualizer();
        eq.prepare(SR, 1024, 2);
        eq.setBand(0, band(MasterEqualizer.BandType.GAIN, MasterEqualizer.Channel.LEFT, 0, gLdb));
        eq.setBand(1, band(MasterEqualizer.BandType.GAIN, MasterEqualizer.Channel.RIGHT, 0, gRdb));

        float[] out = in.clone();
        eq.process(out, 2);

        float gL = (float) Math.pow(10.0, gLdb / 20.0);
        float gR = (float) Math.pow(10.0, gRdb / 20.0);
        for (int i = 0; i < in.length; i += 2)
        {
            assertEquals(in[i] * gL, out[i], TOL);
            assertEquals(in[i + 1] * gR, out[i + 1], TOL);
        }
    }

    @Test
    @DisplayName("GAIN bands on MID/SIDE reproduce Mid/Side gain")
    void gainBandsAreMidSide()
    {
        float[] in = stereoSignal(512);
        double gMdb = 2.0, gSdb = -5.0;

        MasterEqualizer eq = new MasterEqualizer();
        eq.prepare(SR, 1024, 2);
        eq.setBand(0, band(MasterEqualizer.BandType.GAIN, MasterEqualizer.Channel.MID, 0, gMdb));
        eq.setBand(1, band(MasterEqualizer.BandType.GAIN, MasterEqualizer.Channel.SIDE, 0, gSdb));

        float[] out = in.clone();
        eq.process(out, 2);

        double gM = Math.pow(10.0, gMdb / 20.0);
        double gS = Math.pow(10.0, gSdb / 20.0);
        for (int i = 0; i < in.length; i += 2)
        {
            double m = (in[i] + in[i + 1]) * 0.5 * gM;
            double s = (in[i] - in[i + 1]) * 0.5 * gS;
            assertEquals((float) (m + s), out[i], TOL);
            assertEquals((float) (m - s), out[i + 1], TOL);
        }
    }

    @Test
    @DisplayName("A SIDE GAIN band reproduces StereoWidth")
    void sideGainEqualsStereoWidth()
    {
        for (double width : new double[] { 0.5, 1.5 })
        {
            float[] in = stereoSignal(512);

            StereoWidth ref = new StereoWidth();
            ref.prepare(SR);
            ref.setWidth(width);
            float[] refOut = in.clone();
            ref.process(refOut, 2);

            MasterEqualizer eq = new MasterEqualizer();
            eq.prepare(SR, 1024, 2);
            eq.setBand(0, band(MasterEqualizer.BandType.GAIN, MasterEqualizer.Channel.SIDE,
                    0, 20.0 * Math.log10(width)));
            float[] eqOut = in.clone();
            eq.process(eqOut, 2);

            for (int i = 0; i < in.length; i++)
                assertEquals(refOut[i], eqOut[i], TOL, "width=" + width + " idx=" + i);
        }
    }

    /* ---- static filter path ---- */

    @Test
    @DisplayName("A minimum-phase BELL band matches a reference biquad")
    void minimumPhaseBellMatchesBiquad()
    {
        float[] in = stereoSignal(1000);
        double freq = 1000, gain = 6, q = 1.2;

        MasterEqualizer eq = new MasterEqualizer();
        eq.prepare(SR, 1024, 2);
        MasterEqualizer.Band b = band(MasterEqualizer.BandType.BELL, MasterEqualizer.Channel.STEREO, freq, gain);
        b.q = q;
        eq.setBand(0, b);
        float[] eqOut = in.clone();
        eq.process(eqOut, 2);

        Biquad ref = new Biquad(2);
        ref.setCoeffs(BiquadCoeffs.peak(SR, freq, q, gain));
        float[] refOut = in.clone();
        ref.processBlock(refOut, 2);

        for (int i = 0; i < in.length; i++)
            assertEquals(refOut[i], eqOut[i], TOL);
    }

    /* ---- linear-phase routing invariants ---- */

    @Test
    @DisplayName("Linear-phase SIDE band leaves a mono (side=0) signal mono")
    void linearSideKeepsMonoSignalMono()
    {
        int frames = 256;
        float[] in = new float[frames * 2];
        for (int i = 0; i < frames; i++)
        {
            float x = (float) (0.5 * Math.sin(i * 0.05));
            in[2 * i] = x; in[2 * i + 1] = x;     // pure mid: L == R, side == 0
        }

        MasterEqualizer eq = new MasterEqualizer();
        eq.prepare(SR, frames, 2);
        MasterEqualizer.Band b = new MasterEqualizer.Band();
        b.type = MasterEqualizer.BandType.BELL; b.channel = MasterEqualizer.Channel.SIDE;
        b.phase = MasterEqualizer.BandPhase.LINEAR; b.frequency = 1000; b.gainDb = 9; b.q = 1.0;
        eq.setBand(0, b);

        float[] out = in.clone();
        eq.process(out, 2);

        // Scaling a zero side can never create one: L must still equal R.
        for (int i = 0; i < frames; i++)
            assertEquals(out[2 * i], out[2 * i + 1], 1e-4f, "frame " + i);
    }

    @Test
    @DisplayName("Linear-phase MID band leaves a pure-side signal pure-side")
    void linearMidKeepsSideSignalSide()
    {
        int frames = 256;
        float[] in = new float[frames * 2];
        for (int i = 0; i < frames; i++)
        {
            float x = (float) (0.5 * Math.sin(i * 0.05));
            in[2 * i] = x; in[2 * i + 1] = -x;    // pure side: L == -R, mid == 0
        }

        MasterEqualizer eq = new MasterEqualizer();
        eq.prepare(SR, frames, 2);
        MasterEqualizer.Band b = new MasterEqualizer.Band();
        b.type = MasterEqualizer.BandType.BELL; b.channel = MasterEqualizer.Channel.MID;
        b.phase = MasterEqualizer.BandPhase.LINEAR; b.frequency = 1000; b.gainDb = 9; b.q = 1.0;
        eq.setBand(0, b);

        float[] out = in.clone();
        eq.process(out, 2);

        for (int i = 0; i < frames; i++)
            assertEquals(out[2 * i], -out[2 * i + 1], 1e-4f, "frame " + i);
    }

    /* ---- dynamics ---- */

    @Test
    @DisplayName("Dynamic GAIN cuts a level above threshold by the expected amount")
    void dynamicGainCutsAboveThreshold()
    {
        MasterEqualizer eq = new MasterEqualizer();
        eq.prepare(SR, 0, 2);

        MasterEqualizer.Band b = new MasterEqualizer.Band();
        b.type = MasterEqualizer.BandType.GAIN; b.channel = MasterEqualizer.Channel.STEREO;
        b.dynamic = true; b.threshold = -20.0;
        b.aboveRatio = 4.0; b.aboveRangeDb = 6.0; b.aboveBoost = false;
        b.aboveAttackMs = 5.0; b.aboveReleaseMs = 80.0;
        b.belowRatio = 1.0;   // inert below
        eq.setBand(0, b);

        // Constant 0.5 ≈ −6 dBFS, well above −20 → over = 14 dB,
        // 14·(1−1/4)=10.5 capped at range 6 → −6 dB of gain.
        int frames = SR; // 1 s to settle
        float[] buf = new float[frames * 2];
        java.util.Arrays.fill(buf, 0.5f);
        eq.process(buf, 2);

        assertEquals(-6.0, eq.getBandGainReductionDb(0), 0.4,
                "should settle to the −6 dB range cap");
        float expected = (float) (0.5 * Math.pow(10.0, -6.0 / 20.0));
        assertEquals(expected, buf[buf.length - 2], 0.02f);
    }

    @Test
    @DisplayName("Dynamic GAIN boosts a level below threshold (upward)")
    void dynamicGainBoostsBelowThreshold()
    {
        MasterEqualizer eq = new MasterEqualizer();
        eq.prepare(SR, 0, 2);

        MasterEqualizer.Band b = new MasterEqualizer.Band();
        b.type = MasterEqualizer.BandType.GAIN; b.channel = MasterEqualizer.Channel.STEREO;
        b.dynamic = true; b.threshold = -20.0;
        b.aboveRatio = 1.0;   // inert above
        b.belowRatio = 4.0; b.belowRangeDb = 8.0; b.belowBoost = true;
        b.belowAttackMs = 5.0; b.belowReleaseMs = 80.0;
        eq.setBand(0, b);

        // Constant 0.02 ≈ −34 dBFS, below −20 → upward boost, positive gain.
        int frames = SR;
        float[] buf = new float[frames * 2];
        java.util.Arrays.fill(buf, 0.02f);
        eq.process(buf, 2);

        assertTrue(eq.getBandGainReductionDb(0) > 1.0,
                "below-threshold boost should raise the gain, was " + eq.getBandGainReductionDb(0));
        assertTrue(buf[buf.length - 2] > 0.02f, "output should be boosted");
    }

    @Test
    @DisplayName("Dynamic above-threshold boost raises gain (upward expander on transients)")
    void dynamicBoostAboveThreshold()
    {
        MasterEqualizer eq = new MasterEqualizer(2);
        eq.prepare(SR, 1024, 2);

        MasterEqualizer.Band b = band(MasterEqualizer.BandType.BELL,
                MasterEqualizer.Channel.STEREO, 1000.0, 0.0);
        b.dynamic = true; b.threshold = -40.0;
        b.aboveRatio = 4.0; b.aboveRangeDb = 8.0; b.aboveBoost = true;
        b.aboveAttackMs = 5.0; b.aboveReleaseMs = 80.0;
        b.belowRatio = 1.0;   // below inert
        eq.setBand(0, b);

        // A 1 kHz tone at ~−10 dBFS, well above the −40 threshold at the band freq.
        int frames = SR / 2;
        float[] buf = new float[frames * 2];
        for (int i = 0; i < frames; i++)
        {
            float s = (float) (0.3 * Math.sin(2.0 * Math.PI * 1000.0 * i / SR));
            buf[2 * i] = s; buf[2 * i + 1] = s;
        }
        eq.process(buf, 2);

        assertTrue(eq.getBandGainReductionDb(0) > 1.0,
                "above-threshold boost should RAISE the gain, was " + eq.getBandGainReductionDb(0));
    }

    @Test
    @DisplayName("Dynamic BELOW boost engages on quiet sustain and releases on a transient")
    void dynamicBelowReleasesOnTransient()
    {
        MasterEqualizer eq = new MasterEqualizer(2);
        eq.prepare(SR, 1024, 2);

        MasterEqualizer.Band b = band(MasterEqualizer.BandType.BELL,
                MasterEqualizer.Channel.STEREO, 1000.0, 0.0);
        b.dynamic = true; b.threshold = -30.0;
        b.belowRatio = 4.0; b.belowRangeDb = 8.0; b.belowBoost = true;
        b.belowAttackMs = 5.0; b.belowReleaseMs = 40.0;
        b.aboveRatio = 1.0;   // above section off
        eq.setBand(0, b);

        // Quiet 1 kHz tone (~−40 dBFS), below the −30 threshold → boost engages.
        eq.process(tone(1000.0, 0.01f, SR / 4), 2);
        double boosted = eq.getBandGainReductionDb(0);
        assertTrue(boosted > 1.0, "below boost should engage on the quiet part, was " + boosted);

        // Loud 1 kHz tone (~−6 dBFS), above the threshold → boost releases.
        eq.process(tone(1000.0, 0.5f, SR / 4), 2);
        double released = eq.getBandGainReductionDb(0);
        assertTrue(released < boosted * 0.5,
                "boost must release on a transient (" + released + " vs " + boosted + ")");
    }

    private static float[] tone(double freq, float amp, int frames)
    {
        float[] b = new float[frames * 2];
        for (int i = 0; i < frames; i++)
        {
            float s = (float) (amp * Math.sin(2.0 * Math.PI * freq * i / SR));
            b[2 * i] = s; b[2 * i + 1] = s;
        }
        return b;
    }

    /* ---- latency ---- */

    @Test
    @DisplayName("Latency is half the kernel iff a linear-phase static band is active")
    void latencyReporting()
    {
        MasterEqualizer eq = new MasterEqualizer();
        eq.prepare(SR, 512, 2);
        assertEquals(0, eq.getLatencyFrames(), "no bands");

        eq.setBand(0, band(MasterEqualizer.BandType.GAIN, MasterEqualizer.Channel.STEREO, 0, 3));
        assertEquals(0, eq.getLatencyFrames(), "GAIN is phase-neutral");

        MasterEqualizer.Band bMin = band(MasterEqualizer.BandType.BELL, MasterEqualizer.Channel.STEREO, 1000, 3);
        bMin.phase = MasterEqualizer.BandPhase.MINIMUM;
        eq.setBand(1, bMin);
        assertEquals(0, eq.getLatencyFrames(), "minimum-phase adds no latency");

        MasterEqualizer.Band bLin = band(MasterEqualizer.BandType.BELL, MasterEqualizer.Channel.STEREO, 2000, 3);
        bLin.phase = MasterEqualizer.BandPhase.LINEAR;
        eq.setBand(2, bLin);
        // The kernel spans a constant time window, so the latency is rate-derived.
        int expected = (int) Math.round(MasterEqualizer.KERNEL_SECONDS * SR);
        if ((expected & 1) == 1) expected++;
        expected /= 2;
        int lat = eq.getLatencyFrames();
        assertEquals(expected, lat, "a linear-phase band adds half the kernel as latency");

        MasterEqualizer.Band bDyn = band(MasterEqualizer.BandType.BELL, MasterEqualizer.Channel.STEREO, 3000, 0);
        bDyn.phase = MasterEqualizer.BandPhase.LINEAR; bDyn.dynamic = true;
        eq.setBand(3, bDyn);
        // Unchanged (band 2 linear); band 3 dynamic stays minimum-phase regardless.
        assertEquals(lat, eq.getLatencyFrames());
    }

    /* ---- cut slopes ---- */

    @Test
    @DisplayName("Cut slope: 48 dB/oct attenuates far more than 12 dB/oct below the corner")
    void cutSlopeSteeper()
    {
        MasterEqualizer eq = new MasterEqualizer(4);
        eq.prepare(SR, 1024, 2);

        double[] freqs = { 500.0 };                 // one octave below a 1 kHz corner
        double[] m12 = new double[1], m48 = new double[1];

        MasterEqualizer.Band b = band(MasterEqualizer.BandType.LOW_CUT,
                MasterEqualizer.Channel.STEREO, 1000.0, 0.0);
        b.slope = 12;
        eq.setBand(0, b);
        eq.getMagnitudeResponse(MasterEqualizer.Channel.STEREO, freqs, m12);

        MasterEqualizer.Band b2 = band(MasterEqualizer.BandType.LOW_CUT,
                MasterEqualizer.Channel.STEREO, 1000.0, 0.0);
        b2.slope = 48;
        eq.setBand(0, b2);
        eq.getMagnitudeResponse(MasterEqualizer.Channel.STEREO, freqs, m48);

        assertTrue(m12[0] < 0.95, "12 dB/oct should attenuate at 500 Hz, was " + m12[0]);
        assertTrue(m48[0] < m12[0] * 0.25,
                "48 dB/oct must attenuate far more (" + m48[0] + " vs " + m12[0] + ")");
    }
}
