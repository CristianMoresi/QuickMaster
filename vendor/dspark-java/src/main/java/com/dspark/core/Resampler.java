package com.dspark.core;

/**
 * Windowed-sinc sample-rate converter (offline / batch).
 * <p>
 * Converts audio between sample rates (e.g. 96 kHz → 48 kHz for MP3
 * export, or to hit a delivery rate) using a polyphase Kaiser-windowed
 * sinc (256 tabulated phases plus an explicit frac&nbsp;=&nbsp;1 phase,
 * with linear interpolation between adjacent phases) — the same approach
 * used by professional DAWs. Each polyphase branch is normalized to unity
 * DC gain so the passband stays flat.
 * <p>
 * The Kaiser beta scales with the quality tier's tap budget: the window's
 * sidelobe level caps the achievable image rejection no matter how many
 * taps the kernel has, so the longer kernels spend their extra taps on a
 * deeper stopband while the 8-tap Draft trades stopband depth for less
 * passband droop.
 * <p>
 * The batch path is time-aligned: output sample k interpolates the input
 * at position k / {@link #getRatio()} exactly (no latency; the buffer
 * edges are zero-padded).
 */
public final class Resampler
{
    public enum Quality
    {
        /** 8-point sinc, fastest (previews; HF response drops early). */
        DRAFT(8, 6.0),
        /** 32-point sinc, balanced (~-56 dB worst-case images). */
        NORMAL(32, 10.0),
        /** 64-point sinc, high quality (~-140 dB beyond the transition band). */
        HIGH(64, 12.5),
        /** 128-point sinc, mastering grade (deep stopband even at the band edge). */
        ULTRA(128, 14.5);

        final int points;
        final double beta;
        Quality(int p, double b) { this.points = p; this.beta = b; }
    }

    private static final int OVERSAMPLE = 256;

    private double ratio = 1.0;
    private int sincPoints = 32;
    private double kaiserBeta = 10.0;
    private double[] sincTable = new double[0];

    /**
     * Prepares the converter for {@code sourceRate → targetRate}.
     * Non-finite rates are ignored (conservative no-op keeping the
     * previous state).
     *
     * @param sourceRate  input sample rate in Hz
     * @param targetRate  output sample rate in Hz
     * @param quality     interpolation quality
     */
    public void prepare(double sourceRate, double targetRate, Quality quality)
    {
        if (!Double.isFinite(sourceRate) || !Double.isFinite(targetRate) || quality == null)
            return;
        double src = Math.max(sourceRate, 1.0);
        double tgt = Math.max(targetRate, 1.0);
        ratio = tgt / src;
        sincPoints = quality.points;
        kaiserBeta = quality.beta;
        buildSincTable();
    }

    public double getRatio() { return ratio; }

    /** Output length (samples) for a given input length. */
    public int getOutputLength(int inputLength)
    {
        double outLen = Math.ceil(inputLength * ratio);
        return (int) Math.min(outLen, Integer.MAX_VALUE);
    }

    /**
     * Resamples a mono buffer.
     *
     * @param input  source samples
     * @return a new buffer at the target rate
     */
    public float[] process(float[] input)
    {
        int inputLength = input.length;
        int outputLength = getOutputLength(inputLength);
        float[] output = new float[outputLength];
        int half = sincPoints / 2;

        for (int outIdx = 0; outIdx < outputLength; outIdx++)
        {
            double srcPos = outIdx / ratio;
            if (srcPos >= inputLength) break;
            int intPos = (int) srcPos;
            double frac = srcPos - intPos;
            output[outIdx] = interpolate(input, inputLength, intPos, frac, half);
        }
        return output;
    }

    /**
     * Resamples an interleaved multi-channel buffer.
     *
     * @param interleaved  interleaved source samples
     * @param channels     number of channels
     * @return a new interleaved buffer at the target rate
     */
    public float[] resampleInterleaved(float[] interleaved, int channels)
    {
        int inFrames = interleaved.length / channels;
        float[][] in = new float[channels][inFrames];
        for (int f = 0; f < inFrames; f++)
        {
            for (int c = 0; c < channels; c++) in[c][f] = interleaved[f * channels + c];
        }

        float[][] out = new float[channels][];
        for (int c = 0; c < channels; c++) out[c] = process(in[c]);

        int outFrames = out[0].length;
        float[] result = new float[outFrames * channels];
        for (int f = 0; f < outFrames; f++)
        {
            for (int c = 0; c < channels; c++) result[f * channels + c] = out[c][f];
        }
        return result;
    }

    private void buildSincTable()
    {
        // OVERSAMPLE + 1 phases: the extra phase holds the frac = 1.0 kernel,
        // so the 2-point phase interpolation in the read path never has to
        // clamp or wrap (exact at both ends of the fractional range).
        sincTable = new double[(OVERSAMPLE + 1) * sincPoints];

        int half = sincPoints / 2;
        double beta = kaiserBeta;
        double i0Beta = besselI0(beta);

        // 0.95 margin on downsampling to prevent transition-band aliasing.
        double cutoff = (ratio < 1.0) ? (ratio * 0.95) : 1.0;

        for (int phase = 0; phase <= OVERSAMPLE; phase++)
        {
            double frac = (double) phase / OVERSAMPLE;
            int base = phase * sincPoints;
            double sum = 0.0;
            for (int tap = 0; tap < sincPoints; tap++)
            {
                // Tap alignment: tap j weighs source sample intPos-half+1+j,
                // so its position relative to the interpolation point
                // intPos + frac is t = (j - half + 1) - frac. This is the
                // symmetric placement for an even-length kernel: every tap
                // stays inside the open window support (-half, half) for
                // frac in (0, 1). The MINUS sign on frac is essential:
                // `+ frac` would sample the kernel time-reversed (heavy
                // zipper distortion).
                double t = (tap - half + 1) - frac;
                double x = t * cutoff;
                double sincVal = (Math.abs(x) < 1e-10)
                        ? cutoff : cutoff * Math.sin(Math.PI * x) / (Math.PI * x);

                // Continuous Kaiser window evaluated at the SAME shifted
                // position as the sinc (a per-tap fixed window leaves a small
                // phase-dependent ripple in the passband).
                double wx = t / half;
                double win = (Math.abs(wx) >= 1.0)
                        ? 0.0 : besselI0(beta * Math.sqrt(1.0 - wx * wx)) / i0Beta;

                double v = sincVal * win;
                sincTable[base + tap] = v;
                sum += v;
            }
            // Normalize each polyphase branch to unity DC gain so the
            // passband is flat.
            if (Math.abs(sum) > 1e-12)
            {
                double inv = 1.0 / sum;
                for (int tap = 0; tap < sincPoints; tap++) sincTable[base + tap] *= inv;
            }
        }
    }

    private float interpolate(float[] data, int length, int intPos, double frac, int half)
    {
        // Linear interpolation between two adjacent table phases: with 256
        // phases (plus the explicit frac=1 phase) the phase-quantization
        // images sit below -90 dB.
        double exactPhase = frac * OVERSAMPLE;
        int p0 = (int) exactPhase;
        double pf = exactPhase - p0;
        int off0 = p0 * sincPoints;
        int off1 = off0 + sincPoints;   // safe: table holds OVERSAMPLE+1 phases

        // Tap j weighs data[intPos - half + 1 + j] (see buildSincTable).
        int firstSrc = intPos - half + 1;
        double s0 = 0.0, s1 = 0.0;
        if (firstSrc >= 0 && firstSrc + sincPoints <= length)
        {
            for (int tap = 0; tap < sincPoints; tap++)
            {
                double sample = data[firstSrc + tap];
                s0 += sample * sincTable[off0 + tap];
                s1 += sample * sincTable[off1 + tap];
            }
        }
        else
        {
            for (int tap = 0; tap < sincPoints; tap++)
            {
                int srcIdx = firstSrc + tap;
                double sample = (srcIdx >= 0 && srcIdx < length) ? data[srcIdx] : 0.0;
                s0 += sample * sincTable[off0 + tap];
                s1 += sample * sincTable[off1 + tap];
            }
        }
        return (float) (s0 + pf * (s1 - s0));
    }

    private static double besselI0(double x)
    {
        double sum = 1.0, term = 1.0;
        for (int k = 1; k <= 50; k++)
        {
            double half = x / (2.0 * k);
            term *= half * half;
            sum += term;
            if (term < 1e-15 * sum) break;
        }
        return sum;
    }
}
