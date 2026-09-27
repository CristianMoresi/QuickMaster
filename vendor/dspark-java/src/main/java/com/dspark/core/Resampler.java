package com.dspark.core;

import java.util.concurrent.CancellationException;

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
        /** 128 source taps for upsampling; 256 target-rate taps for downsampling. */
        ULTRA(128, 14.5);

        final int points;
        final double beta;
        Quality(int p, double b) { this.points = p; this.beta = b; }
    }

    private static final int OVERSAMPLE = 256;
    private static final int MAX_SINC_POINTS = 65536;

    private double ratio = 1.0;
    private double sourceRate = 1.0, targetRate = 1.0;
    private int sincPoints = 32;
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
        if (sourceRate <= 0 || targetRate <= 0)
            throw new IllegalArgumentException("Sample rates must be positive");
        double nextRatio = targetRate / sourceRate;
        // A fixed SOURCE tap count widens the transition in the TARGET band as
        // the decimation ratio grows. Preserve the target-rate filter support.
        // ULTRA's 256 target taps retain 20 kHz at 44.1 kHz with a .95 cutoff.
        double points = nextRatio < 1 ? (quality == Quality.ULTRA ? 256 : quality.points) / nextRatio : quality.points;
        if (!Double.isFinite(nextRatio) || nextRatio <= 0 || points > MAX_SINC_POINTS)
            throw new IllegalArgumentException("Unsupported conversion ratio");
        int nextPoints = ((int)Math.ceil(points) + 1) & ~1;
        double[] nextTable = buildSincTable(nextRatio, nextPoints, quality.beta);
        // Preparation failure or cancellation leaves the previous converter intact.
        ratio = nextRatio;
        this.sourceRate = sourceRate;
        this.targetRate = targetRate;
        sincPoints = nextPoints;
        sincTable = nextTable;
    }

    public double getRatio() { return ratio; }

    /** Output length (samples) for a given input length. */
    public int getOutputLength(int inputLength)
    {
        if (inputLength < 0) throw new IllegalArgumentException("Negative input length");
        // Integer-rate products are exact here for representable audio buffers.
        // Multiplying by the rounded ratio first can add a spurious frame:
        // 176400 * (48000.0 / 176400) is slightly greater than 48000.
        double outLen = Math.ceil(inputLength * targetRate / sourceRate);
        if (!Double.isFinite(outLen) || outLen > Integer.MAX_VALUE - 8)
            throw new IllegalArgumentException("Converted buffer exceeds the array limit");
        return (int) outLen;
    }

    /**
     * Resamples a mono buffer.
     *
     * @param input  source samples
     * @return a new buffer at the target rate
     */
    public float[] process(float[] input)
    {
        return resampleInterleaved(input, 1);
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
        if (interleaved == null || channels <= 0 || interleaved.length % channels != 0)
            throw new IllegalArgumentException("A complete interleaved PCM buffer is required");
        checkCancelled();
        int inFrames = interleaved.length / channels;
        int outFrames = getOutputLength(inFrames);
        long samples = (long)outFrames * channels;
        if (samples > Integer.MAX_VALUE - 8)
            throw new IllegalArgumentException("Converted buffer exceeds the array limit");
        for (int i=0;i<interleaved.length;i++) {
            if ((i & 16383) == 0) checkCancelled();
            if (!Float.isFinite(interleaved[i])) throw new IllegalArgumentException("Non-finite PCM sample");
        }
        if (ratio == 1) return interleaved.clone();
        // Strided reads eliminate the two additional full-track planar copies.
        float[] result = new float[(int)samples];
        int half = sincPoints / 2;
        for (int f = 0; f < outFrames; f++)
        {
            if ((f & 255) == 0) checkCancelled();
            double srcPos = f / ratio;
            if (srcPos >= inFrames) break;
            int intPos = (int)srcPos;
            double frac = srcPos - intPos;
            for (int c=0;c<channels;c++)
                result[f*channels+c] = interpolate(interleaved, inFrames, intPos, frac, half, channels, c);
        }
        return result;
    }

    private static double[] buildSincTable(double ratio, int sincPoints, double beta)
    {
        // OVERSAMPLE + 1 phases: the extra phase holds the frac = 1.0 kernel,
        // so the 2-point phase interpolation in the read path never has to
        // clamp or wrap (exact at both ends of the fractional range).
        double[] sincTable = new double[(OVERSAMPLE + 1) * sincPoints];

        int half = sincPoints / 2;
        double i0Beta = besselI0(beta);

        // 0.95 margin on downsampling to prevent transition-band aliasing.
        double cutoff = (ratio < 1.0) ? (ratio * 0.95) : 1.0;

        for (int phase = 0; phase <= OVERSAMPLE; phase++)
        {
            checkCancelled();
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
        return sincTable;
    }

    private float interpolate(float[] data, int length, int intPos, double frac, int half, int stride, int channel)
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
        int start = Math.max(0, -firstSrc);
        int end = (int)Math.min(sincPoints, (long)length - firstSrc);
        if (pf == 0)
        {
            // Integer-ratio decimation uses exactly one phase, not two dot products.
            for (int tap = start; tap < end; tap++)
            {
                double sample = data[(firstSrc + tap)*stride+channel];
                s0 += sample * sincTable[off0 + tap];
            }
            return (float)s0;
        }
        else
        {
            for (int tap = start; tap < end; tap++)
            {
                double sample = data[(firstSrc + tap)*stride+channel];
                s0 += sample * sincTable[off0 + tap];
                s1 += sample * sincTable[off1 + tap];
            }
        }
        return (float) (s0 + pf * (s1 - s0));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Resampling cancelled");
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
