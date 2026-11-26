package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FIRFilterTest
{
    private static final double TWO_PI = 2.0 * Math.PI;

    /** |H(f)| of a FIR kernel, evaluated directly. */
    private static double firMagnitudeAt(double[] h, double freq, double fs)
    {
        double re = 0.0, im = 0.0;
        final double w = 2.0 * Math.PI * freq / fs;
        for (int k = 0; k < h.length; k++)
        {
            re += h[k] * Math.cos(w * k);
            im -= h[k] * Math.sin(w * k);
        }
        return Math.sqrt(re * re + im * im);
    }

    private static double magDb(double m) { return 20.0 * Math.log10(Math.max(m, 1e-12)); }

    private static FIRFilter filter(double[] taps, int channels)
    {
        FIRFilter fir = new FIRFilter();
        fir.prepare(taps.length, channels);
        fir.setCoefficients(taps);
        return fir;
    }

    private static double[] noise(int n, long seed)
    {
        Random r = new Random(seed);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = r.nextDouble() - 0.5;
        return x;
    }

    @Test
    @DisplayName("Low-pass passes 200 Hz (FIR_LP_passband)")
    void lowPassPassband()
    {
        FIRFilter fir = filter(FIRFilter.FIRDesign.lowPass(44100.0, 2000.0, 65), 1);
        for (int i = 0; i < 256; i++) fir.processSample(Math.sin(TWO_PI * 200.0 * i / 44100.0), 0);
        double peak = 0.0;
        for (int i = 0; i < 4096; i++)
            peak = Math.max(peak, Math.abs(fir.processSample(Math.sin(TWO_PI * 200.0 * (256 + i) / 44100.0), 0)));
        assertTrue(peak > 0.85, "peak " + peak);
    }

    @Test
    @DisplayName("Low-pass stops 10 kHz (FIR_LP_stopband)")
    void lowPassStopband()
    {
        FIRFilter fir = filter(FIRFilter.FIRDesign.lowPass(44100.0, 2000.0, 65), 1);
        for (int i = 0; i < 256; i++) fir.processSample(Math.sin(TWO_PI * 10000.0 * i / 44100.0), 0);
        double peak = 0.0;
        for (int i = 0; i < 4096; i++)
            peak = Math.max(peak, Math.abs(fir.processSample(Math.sin(TWO_PI * 10000.0 * (256 + i) / 44100.0), 0)));
        assertTrue(peak < 0.1, "peak " + peak);
    }

    @Test
    @DisplayName("Designed kernels are symmetric (FIR_linear_phase_symmetry)")
    void linearPhaseSymmetry()
    {
        double[] taps = FIRFilter.FIRDesign.lowPass(44100.0, 5000.0, 33);
        for (int i = 0; i < taps.length / 2; i++)
            assertEquals(taps[i], taps[taps.length - 1 - i], 1e-6);
    }

    @Test
    @DisplayName("High-pass, band-pass and band-stop responses (FIR_design_highpass_bandpass_bandstop_responses)")
    void highPassBandPassBandStopResponses()
    {
        final double fs = 48000.0, beta = 7.857;
        double[] hp = FIRFilter.FIRDesign.highPass(fs, 4000.0, 101, beta);
        assertTrue(magDb(firMagnitudeAt(hp, 0.0, fs)) < -70.0);
        assertTrue(magDb(firMagnitudeAt(hp, 1000.0, fs)) < -70.0);
        assertEquals(0.0, magDb(firMagnitudeAt(hp, 20000.0, fs)), 0.1);

        double[] bp = FIRFilter.FIRDesign.bandPass(fs, 2000.0, 8000.0, 151, beta);
        assertTrue(magDb(firMagnitudeAt(bp, 0.0, fs)) < -70.0);
        assertTrue(magDb(firMagnitudeAt(bp, 300.0, fs)) < -70.0);
        assertEquals(0.0, magDb(firMagnitudeAt(bp, 5000.0, fs)), 0.2);
        assertTrue(magDb(firMagnitudeAt(bp, 15000.0, fs)) < -70.0);

        double[] bs = FIRFilter.FIRDesign.bandStop(fs, 2000.0, 8000.0, 151, beta);
        assertEquals(0.0, magDb(firMagnitudeAt(bs, 0.0, fs)), 0.1);
        assertTrue(magDb(firMagnitudeAt(bs, 4500.0, fs)) < -60.0);
        assertEquals(0.0, magDb(firMagnitudeAt(bs, 20000.0, fs)), 0.2);

        for (double[] taps : new double[][] { hp, bp, bs })
            for (int i = 0; i < taps.length / 2; i++)
                assertEquals(taps[i], taps[taps.length - 1 - i], 1e-7);
    }

    @Test
    @DisplayName("Even and too-short requests become the next odd count of at least 3")
    void tapCountsAreSanitised()
    {
        assertEquals(65, FIRFilter.FIRDesign.lowPass(48000.0, 5000.0, 64).length);
        assertEquals(3, FIRFilter.FIRDesign.lowPass(48000.0, 5000.0, 0).length);
        assertEquals(65, FIRFilter.FIRDesign.bandStop(48000.0, 2000.0, 8000.0, 64).length);
        double[] bs = FIRFilter.FIRDesign.bandStop(48000.0, 2000.0, 8000.0, 64);
        for (int i = 0; i < bs.length / 2; i++) assertEquals(bs[i], bs[bs.length - 1 - i], 1e-12);
    }

    @Test
    @DisplayName("Kaiser estimates: taps and beta")
    void kaiserEstimates()
    {
        // (60 - 7.95) / (14.36 * 1000 / 48000) = 173.98 -> 174 -> 175 (odd).
        assertEquals(175, FIRFilter.FIRDesign.estimateTaps(48000.0, 1000.0, 60.0));
        assertEquals(3, FIRFilter.FIRDesign.estimateTaps(48000.0, 24000.0, 10.0));
        assertTrue(FIRFilter.FIRDesign.estimateTaps(48000.0, 0.0, 60.0) > 1000);
        assertEquals(0.1102 * (80.0 - 8.7), FIRFilter.FIRDesign.estimateKaiserBeta(80.0), 0.0);
        assertEquals(0.5842 * Math.pow(9.0, 0.4) + 0.07886 * 9.0, FIRFilter.FIRDesign.estimateKaiserBeta(30.0), 0.0);
        assertEquals(0.0, FIRFilter.FIRDesign.estimateKaiserBeta(20.0), 0.0);
    }

    @Test
    @DisplayName("The low-pass design equals the existing FirDesign bit for bit")
    void lowPassMatchesExistingDesign()
    {
        for (int taps : new int[] { 7, 31, 63, 127, 255 })
            for (double beta : new double[] { 2.35, 7.857, 10.056 })
                assertArrayEquals(FirDesign.lowPass(1.0, 0.25, taps, beta),
                                  FIRFilter.FIRDesign.lowPass(1.0, 0.25, taps, beta));
    }

    @Test
    @DisplayName("Symmetric Kaiser window: symmetric, peak 1 at the centre, ends at 1 / I0(beta)")
    void symmetricKaiserWindow()
    {
        double[] w = new double[63];
        WindowFunctions.kaiser(w, 63, 7.857, false);
        for (int i = 0; i < 31; i++) assertEquals(w[i], w[62 - i], 1e-15);
        assertEquals(1.0, w[31], 1e-15);
        double i0 = 0.0, term = 1.0;
        for (int k = 0; k < 60; k++) { i0 += term; term *= (7.857 / 2.0 / (k + 1)) * (7.857 / 2.0 / (k + 1)); }
        assertEquals(1.0 / i0, w[0], 1e-12);
        double[] p = new double[64];
        WindowFunctions.kaiser(p, 64, 7.857, true);
        assertEquals(1.0, p[32], 1e-15);   // periodic: the peak sits at size / 2
    }

    @Test
    @DisplayName("Channels are independent (FIR_multichannel_isolation)")
    void multichannelIsolation()
    {
        FIRFilter fir = filter(FIRFilter.FIRDesign.lowPass(48000.0, 5000.0, 65), 2);
        double peakSilent = 0.0;
        for (int i = 0; i < 1024; i++)
        {
            fir.processSample(Math.sin(TWO_PI * 1000.0 * i / 48000.0), 0);
            peakSilent = Math.max(peakSilent, Math.abs(fir.processSample(0.0, 1)));
        }
        assertEquals(0.0, peakSilent, 0.0);
    }

    @Test
    @DisplayName("DC gain is the tap sum, latency (N - 1) / 2, re-prepare works (FIR_double_instantiation_lockfree_and_reprepare)")
    void dcGainLatencyAndReprepare()
    {
        FIRFilter fir = new FIRFilter();
        fir.prepare(127, 2);
        double[] taps = FIRFilter.FIRDesign.lowPass(48000.0, 4000.0, 127);
        fir.setCoefficients(taps);
        double dcOut = 0.0;
        for (int i = 0; i < 400; i++) dcOut = fir.processSample(1.0, 0);
        double tapSum = 0.0;
        for (double t : taps) tapSum += t;
        assertEquals(tapSum, dcOut, 1e-12);
        assertEquals(63, fir.getLatency());

        fir.prepare(31, 1);
        double[] taps2 = FIRFilter.FIRDesign.lowPass(48000.0, 2000.0, 31);
        fir.setCoefficients(taps2);
        double dc2 = 0.0;
        for (int i = 0; i < 200; i++) dc2 = fir.processSample(1.0, 0);
        double sum2 = 0.0;
        for (double t : taps2) sum2 += t;
        assertEquals(sum2, dc2, 1e-12);
        assertEquals(15, fir.getLatency());
    }

    @Test
    @DisplayName("Stopband rejection at 15 kHz beats 30 dB (QA_FIR_stopband_rejection)")
    void stopbandRejection()
    {
        FIRFilter fir = filter(FIRFilter.FIRDesign.lowPass(44100.0, 5000.0, 65), 1);
        for (int i = 0; i < 512; i++) fir.processSample(Math.sin(TWO_PI * 15000.0 * i / 44100.0), 0);
        double peak = 0.0;
        for (int i = 512; i < 8192; i++)
            peak = Math.max(peak, Math.abs(fir.processSample(Math.sin(TWO_PI * 15000.0 * i / 44100.0), 0)));
        assertTrue(DspMath.gainToDecibels(peak) < -30.0, "rejection " + DspMath.gainToDecibels(peak));
    }

    @Test
    @DisplayName("Passband ripple within 0.5 dB (QA_FIR_passband_ripple)")
    void passbandRipple()
    {
        FIRFilter fir = filter(FIRFilter.FIRDesign.lowPass(44100.0, 5000.0, 65), 1);
        for (double freq : new double[] { 100.0, 500.0, 1000.0, 2000.0, 3000.0 })
        {
            fir.reset();
            for (int i = 0; i < 512; i++) fir.processSample(Math.sin(TWO_PI * freq * i / 44100.0), 0);
            double peak = 0.0;
            for (int i = 512; i < 8192; i++)
                peak = Math.max(peak, Math.abs(fir.processSample(Math.sin(TWO_PI * freq * i / 44100.0), 0)));
            assertTrue(Math.abs(DspMath.gainToDecibels(peak)) < 0.5, freq + " Hz: " + DspMath.gainToDecibels(peak));
        }
    }

    @Test
    @DisplayName("processBlock is the block kernel exactly, for any partition (CoreReg_fir_block_path_is_the_block_kernel)")
    void blockPathIsTheBlockKernel()
    {
        double[] h = FIRFilter.FIRDesign.lowPass(48000.0, 5000.0, 63, 7.0);
        double[] in = noise(4000, 9);
        double[] hist = new double[62 + in.length];
        System.arraycopy(in, 0, hist, 62, in.length);
        double[] rev = new double[63];
        for (int i = 0; i < 63; i++) rev[i] = h[62 - i];
        double[] ref = new double[in.length];
        FIRFilter.firCorrelate(hist, 0, rev, 0, 63, ref, 0, in.length, 1.0);

        FIRFilter a = filter(h, 1), b = filter(h, 1);
        double[][] x = { in.clone() };
        for (int pos = 0; pos < 4000; pos += 500) a.processBlock(x, pos, 500);
        double[][] y = { in.clone() };
        Random r = new Random(9);
        int pos = 0;
        while (pos < 4000)
        {
            int n = Math.min(1 + r.nextInt(700), 4000 - pos);
            b.processBlock(y, pos, n);
            pos += n;
        }
        assertArrayEquals(ref, x[0]);
        assertArrayEquals(ref, y[0]);
    }

    @Test
    @DisplayName("Blocks of 1, 37, 512 and 4096 give identical output, short and long kernels")
    void blockSizeIndependence()
    {
        for (int taps : new int[] { 65, 301 })
        {
            double[] h = FIRFilter.FIRDesign.lowPass(48000.0, 3000.0, taps, 8.0);
            double[] in = noise(9000, taps);
            double[] reference = null;
            for (int block : new int[] { 4096, 512, 37, 1 })
            {
                FIRFilter fir = filter(h, 2);
                double[][] x = { in.clone(), in.clone() };
                for (int pos = 0; pos < in.length; pos += block)
                    fir.processBlock(x, pos, Math.min(block, in.length - pos));
                assertArrayEquals(x[0], x[1]);
                if (reference == null) reference = x[0];
                else assertArrayEquals(reference, x[0], "taps " + taps + " block " + block);
            }
        }
    }

    @Test
    @DisplayName("A long kernel's block path is the per-sample path; a short one agrees to rounding")
    void blockAndSamplePaths()
    {
        for (int taps : new int[] { 63, 301 })
        {
            double[] h = FIRFilter.FIRDesign.lowPass(48000.0, 3000.0, taps, 8.0);
            double[] in = noise(3000, 3);
            FIRFilter a = filter(h, 1), b = filter(h, 1);
            double[][] x = { in.clone() };
            a.processBlock(x, in.length);
            double worst = 0.0;
            for (int i = 0; i < in.length; i++)
            {
                double s = b.processSample(in[i], 0);
                worst = Math.max(worst, Math.abs(s - x[0][i]));
                if (taps > 257) assertEquals(s, x[0][i], 0.0);
            }
            assertTrue(worst < 1e-14, "taps " + taps + " worst " + worst);
        }
    }

    @Test
    @DisplayName("The mono overload is channel 0; reset() equals a fresh filter")
    void monoOverloadAndReset()
    {
        double[] h = FIRFilter.FIRDesign.lowPass(48000.0, 3000.0, 33);
        double[] in = noise(700, 5);
        FIRFilter a = filter(h, 1), b = filter(h, 1);
        double[] mono = in.clone();
        a.processBlock(mono, 0, 700);
        double[][] planar = { in.clone() };
        b.processBlock(planar, 700);
        assertArrayEquals(planar[0], mono);

        double[] first = in.clone();
        a.processBlock(first, 0, 700);
        a.reset();
        double[] again = in.clone();
        a.processBlock(again, 0, 700);
        assertArrayEquals(mono, again);
    }

    @Test
    @DisplayName("Measured group delay of a symmetric kernel equals getLatency()")
    void latencyIsTheGroupDelay()
    {
        FIRFilter fir = filter(FIRFilter.FIRDesign.lowPass(48000.0, 6000.0, 101, 8.0), 1);
        final double f = 100.0, w = TWO_PI * f / 48000.0;   // a phase under half a turn
        double c = 0.0, s = 0.0;
        for (int i = 0; i < 48000; i++)
        {
            double y = fir.processSample(Math.sin(w * i), 0);
            if (i >= 4800) { c += y * Math.cos(w * i); s += y * Math.sin(w * i); }
        }
        double delay = Math.atan2(-c, s) / w;
        assertEquals(fir.getLatency(), delay, 1e-6);
    }

    @Test
    @DisplayName("Coefficients are ignored before prepare(), when empty or too long; no kernel passes the input")
    void coefficientGuards()
    {
        FIRFilter fir = new FIRFilter();
        fir.setCoefficients(new double[] { 0.5, 0.5 });
        assertEquals(0.3, fir.processSample(0.3, 0), 0.0);
        assertEquals(0, fir.getLatency());
        fir.prepare(4, 1);
        assertEquals(0.3, fir.processSample(0.3, 0), 0.0);   // no kernel yet
        fir.setCoefficients(new double[0]);
        fir.setCoefficients(new double[5]);
        assertEquals(0, fir.getLatency());
        fir.setCoefficients(new double[] { 0.25, 0.5, 0.25 });
        assertEquals(1, fir.getLatency());
        assertEquals(0.4, fir.processSample(0.4, 7), 0.0);   // channel not prepared
        assertEquals(0.25, fir.processSample(1.0, 0), 0.0);
        assertEquals(0.75, fir.processSample(1.0, 0), 0.0);
        assertEquals(1.0, fir.processSample(1.0, 0), 0.0);
    }
}
