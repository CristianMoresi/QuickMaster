package com.dspark.analysis;

/**
 * Inter-sample (true) peak detection, ITU-R BS.1770 style.
 * <p>
 * A signal sampled at the base rate can reconstruct (on a DAC) to a level higher
 * than any of its samples: the inter-sample, or "true", peak. This detector
 * estimates it with the official ITU-R BS.1770-5 Annex 2 4&times; over-sampling
 * interpolator: the reference 48-tap, 4-phase polyphase FIR (12 taps per phase).
 * The EBU Tech 3341 true-peak tolerances (+0.2/-0.4 dB, cases 15-23) are defined
 * around exactly this filter. The reading is the maximum of |sample| and the
 * absolute values of the four interpolation phases (together they tile the
 * 4&times;-oversampled reconstruction grid).
 * <p>
 * Use {@link #process(double)} for streaming per-sample detection, or the static
 * {@link #measureMax(float[], int)} to get the maximum true peak of a whole
 * interleaved signal (for setting a true-peak delivery ceiling).
 */
public final class TruePeak
{
    private static final int TAPS = 12;
    private static final int PHASES = 4;       // the 4 phases of the official 4x interpolator
    private static final int HIST = 16;        // power of two >= TAPS
    private static final int MASK = HIST - 1;

    /**
     * Group delay of the streaming detector, in base-rate frames: the official
     * 48-tap 4&times; prototype delays its estimate by (48-1)/2 high-rate samples,
     * about 6 base frames. A per-frame peak map built with {@link #process}
     * lags the audio by this much; shift it left by this constant to align it.
     */
    public static final int GROUP_DELAY_FRAMES = TAPS / 2;
    /** Samples needed to observe the complete FIR tail of a finite input. */
    public static final int TAIL_FRAMES = TAPS - 1;

    /**
     * Official ITU-R BS.1770-5 Annex 2 polyphase interpolator, verbatim from
     * the Annex 2 table ("one set of filter coefficients (for the order 48,
     * 4-phase, FIR interpolating) that would satisfy the requirements").
     * Row k of phase p is h[4k+p] of the symmetric 48-tap low-pass and
     * multiplies x[n-k] (k growing into the past).
     */
    private static final double[][] ANNEX2 = {
        {  0.0017089843750,  0.0109863281250, -0.0196533203125,
           0.0332031250000, -0.0594482421875,  0.1373291015625,
           0.9721679687500, -0.1022949218750,  0.0476074218750,
          -0.0266113281250,  0.0148925781250, -0.0083007812500 },
        { -0.0291748046875,  0.0292968750000, -0.0517578125000,
           0.0891113281250, -0.1665039062500,  0.4650878906250,
           0.7797851562500, -0.2003173828125,  0.1015625000000,
          -0.0582275390625,  0.0330810546875, -0.0189208984375 },
        { -0.0189208984375,  0.0330810546875, -0.0582275390625,
           0.1015625000000, -0.2003173828125,  0.7797851562500,
           0.4650878906250, -0.1665039062500,  0.0891113281250,
          -0.0517578125000,  0.0292968750000, -0.0291748046875 },
        { -0.0083007812500,  0.0148925781250, -0.0266113281250,
           0.0476074218750, -0.1022949218750,  0.9721679687500,
           0.1373291015625, -0.0594482421875,  0.0332031250000,
          -0.0196533203125,  0.0109863281250,  0.0017089843750 },
    };

    // Mirrored ring: each 12-sample dot product reads contiguous history without
    // a wrap/mask operation for every tap and every interpolation phase.
    private final double[] hist = new double[HIST * 2];
    private int writePos = 0;

    /** Clears the detector's history. */
    public void reset()
    {
        java.util.Arrays.fill(hist, 0.0);
        writePos = 0;
    }

    /**
     * Feeds one sample and returns the larger of its magnitude and the magnitudes
     * of the four official Annex 2 interpolation phases evaluated at this position.
     */
    public double process(double sample)
    {
        hist[writePos] = sample;
        hist[writePos + HIST] = sample;
        int newest = writePos + HIST;
        writePos = (writePos + 1) & MASK;

        double peak = Math.abs(sample);
        double p0 = 0, p1 = 0, p2 = 0, p3 = 0;
        final double[] c0 = ANNEX2[0], c1 = ANNEX2[1], c2 = ANNEX2[2], c3 = ANNEX2[3];
        for (int k = 0; k < TAPS; k++) {
            double value = hist[newest - k];
            p0 += value * c0[k]; p1 += value * c1[k];
            p2 += value * c2[k]; p3 += value * c3[k];
        }
        peak = Math.max(peak, Math.max(Math.abs(p0), Math.abs(p1)));
        peak = Math.max(peak, Math.max(Math.abs(p2), Math.abs(p3)));
        return peak;
    }

    /**
     * Maximum true peak (linear) of an interleaved signal, 4&times; oversampled,
     * detected per channel.
     *
     * @param interleaved interleaved samples
     * @param channels    channel count
     * @return the maximum true peak (linear; may exceed 1.0)
     */
    public static double measureMax(float[] interleaved, int channels)
    {
        if (interleaved == null || channels < 1) return 0.0;
        int frames = interleaved.length / channels;
        TruePeak[] det = new TruePeak[channels];
        for (int c = 0; c < channels; c++) det[c] = new TruePeak();
        double max = 0.0;
        for (int f = 0; f < frames; f++)
        {
            int base = f * channels;
            for (int c = 0; c < channels; c++)
            {
                double tp = det[c].process(interleaved[base + c]);
                if (tp > max) max = tp;
            }
        }
        // The final input samples' interpolation phases emerge after EOF.
        // Omitting these zeros can under-report a true peak at a hard ending.
        for (int f = 0; f < TAIL_FRAMES; f++)
            for (TruePeak detector : det) max = Math.max(max, detector.process(0.0));
        return max;
    }
}
