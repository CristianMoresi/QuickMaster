package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DC blocker {@link DcBlockFilter}. */
class DcBlockFilterTest
{
    private static final double TWO_PI = 6.283185307179586;

    private static double peak(double[] x, int from, int to)
    {
        double p = 0.0;
        for (int i = from; i < to; ++i) p = Math.max(p, Math.abs(x[i]));
        return p;
    }

    private static double[] sine(int n, double freq, double fs, double amp)
    {
        final double[] x = new double[n];
        for (int i = 0; i < n; ++i) x[i] = amp * Math.sin(TWO_PI * freq * i / fs);
        return x;
    }

    @Test
    @DisplayName("Defaults: order 1 at 5 Hz; removes DC, passes AC, silence stays silent")
    void removesDcPassesAcAndSilence()
    {
        final DcBlockFilter dc = new DcBlockFilter();
        assertEquals(1, dc.getOrder());
        assertEquals(5.0, dc.getCutoff(), 0.0);
        dc.prepare(44100.0);
        final int n = 44100;
        final double[][] buf = new double[1][n];
        for (int i = 0; i < n; ++i) buf[0][i] = 0.5 + 0.5 * Math.sin(TWO_PI * 440.0 * i / 44100.0);
        dc.process(buf, n);
        double sum = 0.0;
        for (int i = n / 2; i < n; ++i) sum += buf[0][i];
        assertEquals(0.0, sum / (n - n / 2), 0.02);

        final DcBlockFilter ac = new DcBlockFilter();
        ac.prepare(44100.0);
        final double[][] tone = { sine(8192, 440.0, 44100.0, 1.0) };
        final double before = peak(tone[0], 0, 8192);
        ac.process(tone, 8192);
        assertTrue(peak(tone[0], 2048, 8192) > before * 0.9);

        final DcBlockFilter quiet = new DcBlockFilter();
        quiet.prepare(44100.0);
        final double[][] silence = new double[1][512];
        quiet.process(silence, 512);
        for (double v : silence[0]) assertEquals(0.0, v, 1e-10);
    }

    @Test
    @DisplayName("Preserves a 1 kHz tone and rejects a pure DC offset (TestQualityAudit)")
    void preserves1kHzAndRejectsSubsonic()
    {
        final DcBlockFilter dc = new DcBlockFilter();
        dc.prepare(48000.0);
        final double[][] tone = { sine(48000, 1000.0, 48000.0, 0.8) };
        final double before = peak(tone[0], 0, 48000);
        dc.process(tone, 48000);
        assertTrue(20.0 * Math.log10(peak(tone[0], 4800, 48000) / before) > -0.5);

        final DcBlockFilter sub = new DcBlockFilter();
        sub.prepare(48000.0);
        final double[][] offset = new double[1][96000];
        java.util.Arrays.fill(offset[0], 0.8);
        sub.process(offset, 96000);
        double avg = 0.0;
        for (int i = 48000; i < 96000; ++i) avg += offset[0][i];
        assertEquals(0.0, avg / 48000.0, 0.01);
    }

    @Test
    @DisplayName("prepare keeps a configured cutoff, and the audio agrees")
    void prepareKeepsTheConfiguredCutoff()
    {
        final DcBlockFilter d = new DcBlockFilter();
        d.prepare(48000.0, 2, 5.0);
        d.setCutoff(80.0);
        d.prepare(96000.0, 2);
        assertEquals(80.0, d.getCutoff(), 1e-6);
        final int n = 32768;
        final double[][] buf = { sine(n, 80.0, 96000.0, 0.5) };
        d.process(buf, n);
        double acc = 0.0;
        for (int i = n / 2; i < n; ++i) acc += buf[0][i] * buf[0][i];
        final double db = 20.0 * Math.log10(Math.sqrt(acc / (n / 2)) / (0.5 / Math.sqrt(2.0)));
        assertEquals(-3.01, db, 0.3);
    }

    @Test
    @DisplayName("A live order change rebuilds the design; invalid inputs and wild channels are ignored")
    void liveOrderChangeAndInvalidInputs()
    {
        final DcBlockFilter d = new DcBlockFilter();
        d.prepare(44100.0, 1, 100.0);
        toneGainDb(d, 1000.0);
        d.setOrder(8);
        assertTrue(toneGainDb(d, 25.0) < -60.0);

        final DcBlockFilter subject = new DcBlockFilter(), twin = new DcBlockFilter();
        subject.prepare(44100.0, 1, 5.0);
        twin.prepare(44100.0, 1, 5.0);
        subject.setCutoff(Double.NaN);
        subject.prepare(Double.NaN);
        final double[][] a = new double[1][4096], b = new double[1][4096];
        java.util.Arrays.fill(a[0], 0.25);
        java.util.Arrays.fill(b[0], 0.25);
        subject.process(a, 4096);
        twin.process(b, 4096);
        for (int i = 0; i < 4096; ++i)
        {
            assertTrue(Double.isFinite(a[0][i]));
            assertEquals(b[0][i], a[0][i], 0.0);
        }

        final double[] x = new double[16];
        subject.process(99, x, 0, 16);
        subject.process(-3, x, 0, 16);
        for (double v : x) assertEquals(0.0, v, 0.0);
        double acc = 0.0;
        for (int i = 0; i < 100; ++i) acc += subject.processSample(99, 0.5);
        assertEquals(50.0, acc, 1e-5);
        // Order and cutoff clamps.
        d.setOrder(0);
        assertEquals(1, d.getOrder());
        d.setOrder(42);
        assertEquals(10, d.getOrder());
        d.setCutoff(0.2);
        assertEquals(1.0, d.getCutoff(), 0.0);
        d.setCutoff(Double.POSITIVE_INFINITY);
        assertEquals(1.0, d.getCutoff(), 0.0);
    }

    private static double toneGainDb(DcBlockFilter d, double freq)
    {
        final int n = 32768, skip = n / 2;
        final double[][] buf = new double[1][n];
        final double inc = (float) (TWO_PI * freq / 44100.0);
        for (int i = 0; i < n; ++i) buf[0][i] = 0.5 * Math.sin(inc * i);
        d.process(buf, n);
        double acc = 0.0;
        for (int i = skip; i < n; ++i) acc += buf[0][i] * buf[0][i];
        return 20.0 * Math.log10(Math.sqrt(acc / (n - skip)) / (0.5 / Math.sqrt(2.0)) + 1e-30);
    }

    private static double settledDc(double rate, int order, boolean dcInput)
    {
        final DcBlockFilter d = new DcBlockFilter();
        d.setOrder(order);
        d.prepare(rate, 1, 5.0);
        final int total = (int) (rate * 2.0);
        final int start = total - (int) (rate * 0.5);
        final int block = 512;
        final double[][] buf = new double[1][block];
        double acc = 0.0;
        int count = 0;
        for (int i = 0; i < total; i += block)
        {
            final int n = Math.min(block, total - i);
            for (int k = 0; k < n; ++k)
                buf[0][k] = dcInput ? 0.5 : (float) (0.5 * Math.sin(TWO_PI * 1000.0 * (i + k) / rate));
            d.process(buf, 1, 0, n);
            for (int k = 0; k < n; ++k)
                if (i + k >= start)
                {
                    acc += buf[0][k];
                    ++count;
                }
        }
        return acc / count;
    }

    @Test
    @DisplayName("Rejection is rate independent: DC-free in, DC-free out; steady DC decays away")
    void rejectionIsRateIndependent()
    {
        for (double rate : new double[] { 44100.0, 48000.0, 96000.0, 192000.0, 384000.0, 768000.0 })
            for (int order : new int[] { 1, 2, 4 })
            {
                assertTrue(Math.abs(settledDc(rate, order, false)) < 1e-7, "rate " + rate + " order " + order);
                assertTrue(Math.abs(settledDc(rate, order, true)) < 1e-7, "rate " + rate + " order " + order);
            }

        final DcBlockFilter st = new DcBlockFilter();
        st.setOrder(4);
        st.prepare(192000.0, 2, 5.0);
        final double[][] buf = new double[2][4096];
        for (int i = 0; i < 4096; ++i)
            buf[0][i] = buf[1][i] = (float) (0.4 * Math.sin(TWO_PI * 997.0 * i / 192000.0) + 0.05);
        st.process(buf, 4096);
        for (int i = 0; i < 4096; ++i)
            assertEquals(Double.doubleToLongBits(buf[0][i]), Double.doubleToLongBits(buf[1][i]));
    }

    @Test
    @DisplayName("The response matches the Butterworth design to 0.02 dB")
    void responseMatchesButterworthDesign()
    {
        for (double rate : new double[] { 44100.0, 48000.0 })
            for (int order : new int[] { 2, 4 })
                for (double f : new double[] { 10.0, 20.0, 50.0, 100.0 })
                {
                    final double r = Math.pow(f / 5.0, 2.0 * order);
                    assertEquals(10.0 * Math.log10(r / (1.0 + r)), gainDb(rate, order, f), 0.02);
                }
    }

    private static double gainDb(double rate, int order, double freq)
    {
        final DcBlockFilter d = new DcBlockFilter();
        d.setOrder(order);
        d.prepare(rate, 1, 5.0);
        final int settle = (int) rate;
        final int window = (int) Math.round(10.0 * rate / freq);
        final double w = TWO_PI * freq / rate;
        final int block = 512;
        final double[][] buf = new double[1][block];
        double si = 0.0, co = 0.0;
        for (int i = 0; i < settle + window; i += block)
        {
            final int n = Math.min(block, settle + window - i);
            for (int k = 0; k < n; ++k) buf[0][k] = (float) (0.5 * Math.sin(w * (i + k)));
            d.process(buf, 1, 0, n);
            for (int k = 0; k < n; ++k)
                if (i + k >= settle)
                {
                    final double ph = w * (i + k);
                    si += buf[0][k] * Math.sin(ph);
                    co += buf[0][k] * Math.cos(ph);
                }
        }
        final double amp = Math.sqrt(si * si + co * co) * 2.0 / window;
        return 20.0 * Math.log10(amp / 0.5);
    }

    @Test
    @DisplayName("The sample path equals the block path to the bit, across a live cutoff change")
    void processSampleMatchesProcessBlock()
    {
        final int total = 6000, block = 512;
        final DcBlockFilter blockPath = new DcBlockFilter(), samplePath = new DcBlockFilter();
        blockPath.setOrder(4);
        samplePath.setOrder(4);
        blockPath.prepare(96000.0, 1, 5.0);
        samplePath.prepare(96000.0, 1, 5.0);
        final double[] src = new double[total];
        int rng = 22222;
        for (int i = 0; i < total; ++i)
        {
            rng = rng * 1664525 + 1013904223;
            src[i] = (float) (rng / 2147483648.0) * 0.4f + 0.1f;
        }
        final double[][] a = { src.clone() };
        final double[] b = src.clone();
        for (int i = 0; i < total; i += block)
        {
            final int n = Math.min(block, total - i);
            if (i == 2 * block)
            {
                blockPath.setCutoff(20.0);
                samplePath.setCutoff(20.0);
            }
            blockPath.process(a, 1, i, n);
            for (int k = 0; k < n; ++k) b[i + k] = samplePath.processSample(0, b[i + k]);
        }
        for (int i = 0; i < total; ++i) assertEquals(Double.doubleToLongBits(a[0][i]), Double.doubleToLongBits(b[i]));
        assertEquals(20.0, blockPath.getCutoff(), 1e-6);
    }

    @Test
    @DisplayName("Several channels in one block give what one channel at a time gives, for every order and a NaN")
    void channelsMatchSingleChannels()
    {
        final int total = 7000;
        for (int order = 1; order <= 10; ++order)
        {
            final DcBlockFilter wide = new DcBlockFilter();
            final DcBlockFilter[] alone = { new DcBlockFilter(), new DcBlockFilter(), new DcBlockFilter() };
            wide.setOrder(order);
            wide.prepare(48000.0, 3, 12.0);
            for (DcBlockFilter f : alone)
            {
                f.setOrder(order);
                f.prepare(48000.0, 1, 12.0);
            }
            final double[][] data = new double[3][total], single = new double[3][];
            int rng = 777 + order;
            for (int ch = 0; ch < 3; ++ch)
            {
                for (int i = 0; i < total; ++i)
                {
                    rng = rng * 1664525 + 1013904223;
                    data[ch][i] = (double) rng / 2147483648.0 * 0.5 + 0.2 * ch;
                }
                single[ch] = data[ch].clone();
            }
            data[1][3000] = Double.NaN;
            single[1][3000] = Double.NaN;
            for (int i = 0; i < total; i += 333)
            {
                final int n = Math.min(333, total - i);
                wide.process(data, 3, i, n);
                for (int ch = 0; ch < 3; ++ch)
                {
                    alone[ch].process(new double[][] { single[ch] }, 1, i, n);
                    for (int k = 0; k < n; ++k) assertEquals(single[ch][i + k], data[ch][i + k], 0.0);
                }
            }
            assertTrue(Double.isFinite(data[1][total - 1]));
        }
    }

    @Test
    @DisplayName("Block sizes 1, 37, 512 and 4096 agree; reset equals a fresh filter; a NaN does not latch")
    void blockSizesResetAndNan()
    {
        final int total = 10000;
        final double[] x = sine(total, 60.0, 48000.0, 0.4);
        for (int i = 0; i < total; ++i) x[i] += 0.1;
        for (int order : new int[] { 1, 2, 7, 10 })
        {
            double[] reference = null;
            for (int blockSize : new int[] { 1, 37, 512, 4096 })
            {
                final DcBlockFilter f = new DcBlockFilter();
                f.setOrder(order);
                f.prepare(48000.0, 1, 20.0);
                final double[][] buf = { x.clone() };
                for (int pos = 0; pos < total; pos += blockSize) f.process(buf, 1, pos, Math.min(blockSize, total - pos));
                if (reference == null)
                {
                    reference = buf[0];
                    continue;
                }
                for (int i = 0; i < total; ++i) assertEquals(reference[i], buf[0][i], 0.0);
            }

            final DcBlockFilter used = new DcBlockFilter(), fresh = new DcBlockFilter();
            for (DcBlockFilter f : new DcBlockFilter[] { used, fresh })
            {
                f.setOrder(order);
                f.prepare(48000.0, 1, 20.0);
            }
            used.process(new double[][] { x.clone() }, 3000);
            used.reset();
            final double[][] a = { x.clone() }, b = { x.clone() };
            used.process(a, total);
            fresh.process(b, total);
            for (int i = 0; i < total; ++i) assertEquals(b[0][i], a[0][i], 0.0);

            final DcBlockFilter n = new DcBlockFilter();
            n.setOrder(order);
            n.prepare(48000.0, 1);
            final double[][] y = { sine(4096, 300.0, 48000.0, 0.3) };
            y[0][10] = Double.NaN;
            for (int pos = 0; pos < 4096; pos += 512) n.process(y, 1, pos, 512);
            for (int i = 512; i < 4096; ++i) assertTrue(Double.isFinite(y[0][i]));
            n.processSample(0, Double.NaN);
            assertTrue(Double.isFinite(n.processSample(0, 0.25)));
        }
    }
}
