package com.dspark.effects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.function.Consumer;

import static com.dspark.effects.SaturationSignals.*;

/**
 * Measurements for the port's report (run with -Dsaturation.report=true):
 * the cost of process() per stereo frame at 48 kHz after warm-up, the
 * latency QuickMaster sees, and the level against the drive.
 */
@EnabledIfSystemProperty(named = "saturation.report", matches = "true")
class SaturationReportTest
{
    private static double nanosPerFrame(Consumer<Saturation> setup)
    {
        final double fs = 48000.0;
        final Saturation s = SaturationPropertiesTest.prepared(setup, fs, 2, 512);
        final double[][] x = SaturationPropertiesTest.programme(fs, 1.0);
        final double[][] buf = new double[2][512];
        final int n = x[0].length;
        long frames = 0, elapsed = 0;
        for (int pass = 0; pass < 6; ++pass)
        {
            final long t0 = System.nanoTime();
            for (int off = 0; off + 512 <= n; off += 512)
            {
                System.arraycopy(x[0], off, buf[0], 0, 512);
                System.arraycopy(x[1], off, buf[1], 0, 512);
                s.process(buf, 512);
            }
            final long t1 = System.nanoTime();
            if (pass >= 2)
            {
                elapsed += t1 - t0;
                frames += (n / 512) * 512L;
            }
        }
        return (double) elapsed / frames;
    }

    @Test
    void costPerAlgorithm()
    {
        for (int warm = 0; warm < 2; ++warm)
            for (Saturation.Algorithm a : Saturation.Algorithm.values()) nanosPerFrame(s -> s.setAlgorithm(a));
        System.out.println("REPORT cost at 48 kHz, stereo, ns per frame (default settings: 2x, drive 0)");
        for (Saturation.Algorithm a : Saturation.Algorithm.values())
            System.out.printf("REPORT %-12s plain %6.0f   first order %6.0f   second order %6.0f%n", a,
                              nanosPerFrame(s -> s.setAlgorithm(a)),
                              nanosPerFrame(s -> { s.setAlgorithm(a); s.setAntialiasing(true); }),
                              nanosPerFrame(s -> { s.setAlgorithm(a); s.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER); }));
        System.out.printf("REPORT QuickMaster Tube (auto 2x, second order, compensation, +7.3 dB): %.0f%n",
                          nanosPerFrame(SaturationPropertiesTest.quickMaster(Saturation.Algorithm.TUBE, 7.3)));
        System.out.printf("REPORT QuickMaster MultiStage (auto 2x, second order, compensation, +4.1 dB): %.0f%n",
                          nanosPerFrame(SaturationPropertiesTest.quickMaster(Saturation.Algorithm.MULTI_STAGE, 4.1)));
        System.out.printf("REPORT QuickMaster MultiStage at +18 dB (kernel chain): %.0f%n",
                          nanosPerFrame(SaturationPropertiesTest.quickMaster(Saturation.Algorithm.MULTI_STAGE, 18.0)));
        final long t0 = System.nanoTime();
        for (int k = 0; k < 10; ++k)
            SaturationPropertiesTest.prepared(SaturationPropertiesTest.quickMaster(Saturation.Algorithm.MULTI_STAGE, 4.1), 48000.0, 2, 4096);
        System.out.printf("REPORT prepare() with drive compensation, MultiStage: %.1f ms%n", (System.nanoTime() - t0) / 10e6);
    }

    @Test
    void latencyWithAutomaticOversampling()
    {
        for (double fs : new double[] { 44100.0, 48000.0, 96000.0 })
            for (Saturation.Algorithm a : new Saturation.Algorithm[] { Saturation.Algorithm.TUBE, Saturation.Algorithm.MULTI_STAGE })
            {
                final Saturation s = SaturationPropertiesTest.prepared(SaturationPropertiesTest.quickMaster(a, 6.0), fs, 2, 4096);
                final Saturation plain = SaturationPropertiesTest.prepared(t -> { t.setAutomaticOversampling(true); t.setAlgorithm(a); }, fs, 2, 4096);
                System.out.printf("REPORT latency %s at %.0f Hz: factor %d, %d samples (second order), %d (antialiasing off)%n",
                                  a, fs, s.getOversamplingFactor(), s.getLatency(), plain.getLatency());
            }
    }

    private static double levelDb(double[] in, Consumer<Saturation> setup)
    {
        final double[] out = run48(in, setup);
        return 20.0 * Math.log10(rms(out, 24000, in.length) / rms(in, 24000, in.length));
    }

    @Test
    void levelAgainstDrive()
    {
        final double[] tone = sine48(1000.0, Math.pow(10.0, -12.0 / 20.0), 72000);
        final double[] noise = pink(72000, -18.0, 77);
        for (int variant = 0; variant < 4; ++variant)
        {
            final int v = variant;
            final String name = v == 0 ? "SoftClip (defaults)" : v == 1 ? "SoftClip, compensation"
                              : v == 2 ? "Tube, QuickMaster" : "MultiStage, QuickMaster";
            final StringBuilder sb = new StringBuilder("REPORT level " + name + ":");
            for (double drive : new double[] { -12.0, 0.0, 12.0, 24.0 })
            {
                final Consumer<Saturation> setup = s -> {
                    if (v >= 2) SaturationPropertiesTest.quickMaster(v == 2 ? Saturation.Algorithm.TUBE : Saturation.Algorithm.MULTI_STAGE, drive).accept(s);
                    else { s.setDrive(drive); s.setDriveCompensation(v == 1); }
                };
                sb.append(String.format("  %+3.0f dB: sine %+6.2f, pink %+6.2f", drive, levelDb(tone, setup), levelDb(noise, setup)));
            }
            System.out.println(sb);
        }
    }
}
