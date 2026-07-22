package com.quickmaster.processing;
import com.quickmaster.processing.eq.EqualizerProcessor;
import com.quickmaster.processing.limit.MultibandLimiterProcessor;
import com.quickmaster.processing.limit.BroadbandLimiterProcessor;

import com.quickmaster.audio.WavFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the offline render in {@link ProcessingPipeline#process}:
 * the block-streaming, latency-compensated render path.
 * <p>
 * The key guarantees exercised here are that the rendered buffer (1)
 * keeps the source length, (2) is time-aligned with the source even when
 * the chain introduces latency, and (3) still applies a global analyze
 * stage (the {@link PeakNormalizer}) correctly. A pure-delay test processor
 * stands in for the real block-/look-ahead-based stages so the alignment
 * maths can be checked exactly, independent of any DSP numerics.
 */
class ProcessingPipelineRenderTest
{
    private static final int SR = 48000;
    private static final int CH = 2;

    @Test
    @DisplayName("An empty chain returns the audio unchanged, same length")
    void emptyChainIsIdentity()
    {
        float[] input = signal(2000, CH);
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();
        assertEquals(0, pipeline.getLatencyFrames());
        pipeline.process(file);

        assertEquals(input.length, file.getSamples().length);
        assertArrayEquals(input, file.getSamples(), 0.0f);
    }

    @Test
    @DisplayName("getLatencyFrames sums the chain's per-processor latency")
    void latencySums()
    {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new DelayProcessor(100));
        pipeline.addProcessor(new PeakNormalizer());          // zero latency
        pipeline.addProcessor(new DelayProcessor(250));
        assertEquals(350, pipeline.getLatencyFrames());
    }

    @Test
    @DisplayName("Normalizer brings the peak to target through the render")
    void normalizerNormalizesThroughRender()
    {
        float[] input = scaledSignal(2000, CH, 0.25f);    // quiet
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new PeakNormalizer());          // enabled, target -0.1 dBTP

        pipeline.process(file);

        assertEquals(input.length, file.getSamples().length);
        assertEquals((float) Math.pow(10.0, -0.1 / 20.0), maxAbs(file.getSamples()), 2e-3f);
    }

    @Test
    @DisplayName("Latency is compensated: a pure delay round-trips exactly (single block)")
    void latencyCompensatedSmallDelay()
    {
        float[] input = signal(2000, CH);                 // > 1 block, delay < 1 block
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new DelayProcessor(300));

        pipeline.process(file);

        assertEquals(input.length, file.getSamples().length);
        assertArrayEquals(input, file.getSamples(), 0.0f);
    }

    @Test
    @DisplayName("Latency is compensated across many blocks (delay > block size)")
    void latencyCompensatedLargeDelayMultiBlock()
    {
        // 2500 frames spans 3 blocks of 1024; the 1500-frame delay
        // exceeds a single block, exercising the flush across blocks.
        float[] input = signal(2500, CH);
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new DelayProcessor(1500));

        pipeline.process(file);

        assertEquals(input.length, file.getSamples().length);
        assertArrayEquals(input, file.getSamples(), 0.0f);
    }

    @Test
    @DisplayName("Delay + Normalizer stays aligned and correctly scaled")
    void delayThenNormalizerAlignedAndScaled()
    {
        float[] input = scaledSignal(3000, CH, 0.5f);     // quiet, peak 0.5 -> gain x2
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new DelayProcessor(700));
        pipeline.addProcessor(new PeakNormalizer());          // target -0.1 dBTP

        pipeline.process(file);

        float[] out = file.getSamples();
        assertEquals(input.length, out.length);
        // Delay is compensated and the static gain (true peak -> -0.1 dBTP) is uniform.
        float gain = (float) (Math.pow(10.0, -0.1 / 20.0)
                / com.dspark.analysis.TruePeak.measureMax(input, CH));
        for (int i = 0; i < input.length; i++)
        {
            assertEquals(input[i] * gain, out[i], 1e-4f);
        }
    }

    @Test
    @DisplayName("The real default chain renders with zero latency and keeps length")
    void realDefaultChainPreservesLength()
    {
        float[] input = signal(5000, CH);
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new EqualizerProcessor());  // transparent by default
        pipeline.addProcessor(new FadeProcessor());
        pipeline.addProcessor(new MultibandLimiterProcessor());   // disabled by default
        pipeline.addProcessor(new BroadbandLimiterProcessor());   // disabled by default
        pipeline.addProcessor(new PeakNormalizer());

        assertEquals(0, pipeline.getLatencyFrames(),
                "all new stages are disabled, so the default chain is zero-latency");

        pipeline.process(file);
        assertEquals(input.length, file.getSamples().length);
    }

    @Test
    @DisplayName("An ACTIVE multiband limiter renders time-aligned (crossover latency compensated)")
    void activeMultibandLimiterStaysAligned()
    {
        // Zero push on every band keeps the limiter transparent while still
        // engaging the linear-phase crossover (split + recombine), so any
        // uncompensated crossover latency shows up as a time shift here.
        float[] input = signal(24000, CH);
        WavFile file = mem(input);

        MultibandLimiterProcessor mb = new MultibandLimiterProcessor();
        mb.setEnabled(true);
        for (int b = 0; b < MultibandLimiterProcessor.BANDS; b++) mb.setPushDb(b, 0.0);

        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(mb);
        pipeline.process(file);

        float[] out = file.getSamples();
        assertEquals(input.length, out.length);
        // Skip the crossover warm-up at the very edges; the middle must match
        // the input sample-for-sample (within the FIR reconstruction error).
        int guard = 4096 * CH;
        for (int i = guard; i < input.length - guard; i++)
        {
            assertEquals(input[i], out[i], 3e-3f,
                    "active multiband render must stay aligned at i=" + i);
        }
    }

    @Test
    @DisplayName("Peak Normalizer analyzes the post-upstream (post-EQ) signal")
    void peakNormalizerAccountsForUpstreamEq()
    {
        float[] input = scaledSignal(3000, CH, 0.4f);     // peak 0.4
        WavFile file = mem(input);

        // EQ first: a flat +6 dB GAIN band lifts the whole signal, so the
        // peak reaching the normalizer is 0.4 * 10^(6/20), not 0.4.
        EqualizerProcessor eq = new EqualizerProcessor();
        com.dspark.effects.MasterEqualizer.Band g = new com.dspark.effects.MasterEqualizer.Band();
        g.type = com.dspark.effects.MasterEqualizer.BandType.GAIN;
        g.channel = com.dspark.effects.MasterEqualizer.Channel.STEREO;
        g.gainDb = 6.0;
        eq.setBand(0, g);

        PeakNormalizer norm = new PeakNormalizer();        // target -0.1 dBTP

        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(eq);
        pipeline.addProcessor(norm);
        pipeline.process(file);

        // The normalizer must scale to the EQ's real output peak, not the
        // original source peak: gain = 1 / (peak · 10^(6/20)). The normalizer
        // targets dBTP, and the official BS.1770-5 Annex 2 interpolator reads
        // this signal's inter-sample peak a hair above its sample peak, so the
        // tolerance allows that small (~0.015 dB) true-peak margin.
        double postEqPeak = maxAbs(input) * Math.pow(10.0, 6.0 / 20.0);
        assertEquals(Math.pow(10.0, -0.1 / 20.0) / postEqPeak, norm.getGain(), 4e-3);
        // ...and the rendered peak lands on the -0.1 dBTP ceiling (no clipping).
        assertEquals((float) Math.pow(10.0, -0.1 / 20.0), maxAbs(file.getSamples()), 3e-3f);
    }

    /* ====================================================================
     *  Oversampled render (bounded-memory streaming path)
     * ==================================================================== */

    @Test
    @DisplayName("Oversampled empty chain round-trips: length, alignment and fidelity (4x)")
    void oversampledEmptyChainRoundTrips()
    {
        // A low tone (far below Nyquist) survives the half-band up/down round
        // trip; the latency compensation must land it back in place sample-wise.
        float[] input = tone(4000, CH, 200.0);
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();   // identity chain
        pipeline.processOversampled(file, 4, null);

        float[] out = file.getSamples();
        assertEquals(input.length, out.length, "oversampled render must keep the source length");
        assertTrue(relErrorMiddle(out, input) < 0.06,
                "round-trip error/misalignment too high: " + relErrorMiddle(out, input));
    }

    @Test
    @DisplayName("Oversampled render applies the chain (a -6 dB gain) and stays aligned")
    void oversampledAppliesGain()
    {
        float[] input = tone(4000, CH, 200.0);
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new GainProcessor(0.5f));           // -6 dB, zero latency
        pipeline.processOversampled(file, 4, null);

        float[] out = file.getSamples();
        assertEquals(input.length, out.length);
        float[] expected = input.clone();
        for (int i = 0; i < expected.length; i++) expected[i] *= 0.5f;
        assertTrue(relErrorMiddle(out, expected) < 0.06,
                "gain not applied / misaligned: " + relErrorMiddle(out, expected));
    }

    @Test
    @DisplayName("Oversampled render reports monotone progress ending at 1.0")
    void oversampledReportsProgress()
    {
        float[] input = tone(5000, CH, 200.0);
        WavFile file = mem(input);

        double[] last = { -1.0 };
        int[] count = { 0 };
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.processOversampled(file, 4, frac ->
        {
            assertTrue(frac >= last[0], "progress must not go backwards");
            last[0] = frac;
            count[0]++;
        });
        assertTrue(count[0] > 0, "progress should have been reported");
        assertEquals(1.0, last[0], 1e-9, "progress should end at 1.0");
    }

    @Test
    @DisplayName("Factor 1 delegates to the plain render (identity, exact)")
    void oversampledFactorOneIsPlainRender()
    {
        float[] input = signal(2000, CH);
        WavFile file = mem(input);

        ProcessingPipeline pipeline = new ProcessingPipeline();   // empty
        pipeline.processOversampled(file, 1, null);

        assertEquals(input.length, file.getSamples().length);
        assertArrayEquals(input, file.getSamples(), 0.0f);
    }

    /* ====================================================================
     *  Helpers
     * ==================================================================== */

    private static WavFile mem(float[] samples)
    {
        return new WavFile("mem.wav", SR, CH, samples, 16, false);
    }

    /** Deterministic, in-range, per-sample-distinct test signal. */
    private static float[] signal(int frames, int channels)
    {
        float[] s = new float[frames * channels];
        for (int i = 0; i < s.length; i++)
        {
            s[i] = (float) (0.7 * Math.sin(i * 0.013 + 0.5));
        }
        return s;
    }

    /** Like {@link #signal} but scaled so its peak is exactly {@code peak}. */
    private static float[] scaledSignal(int frames, int channels, float peak)
    {
        float[] s = signal(frames, channels);
        float max = maxAbs(s);
        float k = peak / max;
        for (int i = 0; i < s.length; i++) s[i] *= k;
        return s;
    }

    private static float maxAbs(float[] b)
    {
        float m = 0.0f;
        for (float v : b) m = Math.max(m, Math.abs(v));
        return m;
    }

    /** A pure interleaved tone at {@code freqHz}, identical on every channel. */
    private static float[] tone(int frames, int channels, double freqHz)
    {
        float[] s = new float[frames * channels];
        for (int f = 0; f < frames; f++)
        {
            float v = (float) (0.6 * Math.sin(2.0 * Math.PI * freqHz * f / SR));
            for (int c = 0; c < channels; c++) s[f * channels + c] = v;
        }
        return s;
    }

    /** RMS error of {@code out} vs {@code ref} over the middle, relative to the
     *  reference RMS - robust to the filter warm-up at the very edges. */
    private static double relErrorMiddle(float[] out, float[] ref)
    {
        int n = Math.min(out.length, ref.length);
        int start = Math.min(1024, n / 4), end = n - start;
        double err = 0.0, energy = 0.0;
        for (int i = start; i < end; i++)
        {
            double d = out[i] - ref[i];
            err += d * d;
            energy += ref[i] * (double) ref[i];
        }
        return (energy <= 0.0) ? Double.POSITIVE_INFINITY : Math.sqrt(err / energy);
    }

    /**
     * Test-only processor that delays the signal by a fixed number of
     * frames using a FIFO, and reports that delay as its latency. Pure
     * sample copies (no arithmetic), so a latency-compensated render
     * must reproduce the input bit-for-bit.
     */
    private static final class DelayProcessor implements AudioProcessor
    {
        private final int delayFrames;
        private float[] tail;          // delayFrames * channels samples
        private int tailChannels = -1;

        DelayProcessor(int delayFrames) { this.delayFrames = delayFrames; }

        @Override
        public void prepare(int sampleRate, long totalSamples)
        {
            tail = null;
            tailChannels = -1;
        }

        @Override
        public int getLatencyFrames() { return delayFrames; }

        @Override
        public float[] process(float[] buffer, int channels)
        {
            int d = delayFrames * channels;
            if (tail == null || tailChannels != channels)
            {
                tail = new float[d];
                tailChannels = channels;
            }
            int n = buffer.length;
            float[] combined = new float[d + n];
            System.arraycopy(tail, 0, combined, 0, d);
            System.arraycopy(buffer, 0, combined, d, n);
            System.arraycopy(combined, 0, buffer, 0, n);   // output: first n
            System.arraycopy(combined, n, tail, 0, d);     // new tail: last d
            return buffer;
        }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public void setEnabled(boolean enabled) { /* always on for the test */ }
    }

    /** Test-only zero-latency static gain, to check the chain runs at the high rate. */
    private static final class GainProcessor implements AudioProcessor
    {
        private final float gain;

        GainProcessor(float gain) { this.gain = gain; }

        @Override
        public void prepare(int sampleRate, long totalSamples) { }

        @Override
        public float[] process(float[] buffer, int channels)
        {
            for (int i = 0; i < buffer.length; i++) buffer[i] *= gain;
            return buffer;
        }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public void setEnabled(boolean enabled) { /* always on for the test */ }
    }
}
