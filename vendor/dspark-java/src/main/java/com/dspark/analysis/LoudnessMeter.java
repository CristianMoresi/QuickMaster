package com.dspark.analysis;

/**
 * EBU R128 / ITU-R BS.1770 loudness meter.
 * <p>
 * Provides Momentary (400 ms), Short-term (3 s) and gated Integrated
 * loudness in LUFS, plus the Loudness Range (LRA, EBU Tech 3342) and a
 * convenience for one-shot offline measurement.
 * <p>
 * <b>K-weighting</b> coefficients are recomputed for the actual sample
 * rate with a pre-warped analog parameterization that reproduces the
 * official ITU-R BS.1770-5 table 1/2 coefficients at 48 kHz to machine
 * precision and generalizes to any sample rate with the same frequency
 * response, so the measurement stays accurate from 44.1 kHz up to 192 kHz.
 * <p>
 * <b>Block model.</b> Audio is accumulated in 100 ms blocks. Momentary
 * loudness is the mean power of the last 4 blocks (400 ms); short-term
 * is the last 30 blocks (3 s). The integrated value is gated per
 * BS.1770: 400 ms gating blocks (one every 100 ms), an absolute gate at
 * −70 LUFS and a relative gate 10 LU below the ungated mean. LRA (EBU
 * Tech 3342) samples the short-term (3 s) loudness once per second, gates
 * the distribution at −70 LUFS and 20 LU below its mean, then takes the
 * spread between the 95th and 10th percentiles.
 * <p>
 * Reading methods are safe to call from a UI thread while audio is being
 * fed on another thread (single writer, plain reads — adequate for
 * metering).
 */
public final class LoudnessMeter
{
    /** Loudness reported when there is no measurable signal. */
    public static final double SILENCE_LUFS = -100.0;

    private static final double ABSOLUTE_GATE_LUFS = -70.0;
    private static final double RELATIVE_GATE_LU = 10.0;   // integrated
    private static final double LRA_RELATIVE_GATE_LU = 20.0;
    private static final double LOUDNESS_OFFSET = -0.691;  // BS.1770-4

    private static final int RING = 30;          // 30 × 100 ms = 3 s
    private static final int MOMENTARY_BLOCKS = 4;
    private static final int SHORT_TERM_BLOCKS = 30;

    // Histogram: −70..+30 LUFS at 0.1 LU resolution.
    private static final double HIST_MIN_LUFS = -70.0;
    private static final double BIN_WIDTH = 0.1;
    private static final int NUM_BINS = 1000;

    private double sampleRate = 48000.0;
    private int blockFrames = 4800;
    private boolean prepared = false;

    // K-weighting coefficients (shared across channels).
    private double preB0, preB1, preB2, preA1, preA2;
    private double rlbB0, rlbB1, rlbB2, rlbA1, rlbA2;

    // Per-channel filter state (max 2 channels).
    private final double[] preZ1 = new double[2];
    private final double[] preZ2 = new double[2];
    private final double[] rlbZ1 = new double[2];
    private final double[] rlbZ2 = new double[2];

    private double currentBlockPower = 0.0;
    private int currentBlockFrames = 0;

    private final double[] ringPowers = new double[RING];
    private volatile int writePos = 0;
    private long blocksCommitted = 0;

    private final int[] integratedHistogram = new int[NUM_BINS];
    private final int[] shortTermHistogram = new int[NUM_BINS];

    /**
     * Prepares the meter for the given sample rate and resets all state.
     * A non-finite or non-positive sample rate is ignored (conservative
     * no-op keeping the previous state).
     */
    public void prepare(double sampleRate)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        // 100 ms block length, clamped before the cast so an absurd finite
        // rate cannot overflow the int conversion.
        this.blockFrames = (int) Math.min(Math.max(Math.round(sampleRate * 0.1), 1L), 1_000_000_000L);
        computeKWeighting(sampleRate);
        reset();
        prepared = true;
    }

    /** Clears all measurements and filter state. */
    public void reset()
    {
        java.util.Arrays.fill(preZ1, 0.0);
        java.util.Arrays.fill(preZ2, 0.0);
        java.util.Arrays.fill(rlbZ1, 0.0);
        java.util.Arrays.fill(rlbZ2, 0.0);
        java.util.Arrays.fill(ringPowers, 0.0);
        java.util.Arrays.fill(integratedHistogram, 0);
        java.util.Arrays.fill(shortTermHistogram, 0);
        writePos = 0;
        blocksCommitted = 0;
        currentBlockPower = 0.0;
        currentBlockFrames = 0;
    }

    /**
     * Feeds an interleaved buffer to the meter (read-only).
     *
     * @param buffer    interleaved samples
     * @param channels  number of channels (1 or 2; extra channels ignored)
     */
    public void process(float[] buffer, int channels)
    {
        // No-op before prepare: measuring with identity coefficients would
        // silently publish un-weighted (miscalibrated) readings as real.
        if (!prepared || buffer == null || channels < 1) return;
        int ch = Math.min(channels, 2);
        for (int i = 0; i < buffer.length; i += channels)
        {
            double power = 0.0;
            for (int c = 0; c < ch; c++)
            {
                double f = applyKWeighting(buffer[i + c], c);
                power += f * f;
            }
            currentBlockPower += power;
            if (++currentBlockFrames >= blockFrames)
            {
                commitBlock();
            }
        }
    }

    /** Momentary loudness (last 400 ms) in LUFS. */
    public double getMomentaryLufs()
    {
        return powerToLufs(meanOfLastBlocks(MOMENTARY_BLOCKS));
    }

    /** Short-term loudness (last 3 s) in LUFS. */
    public double getShortTermLufs()
    {
        return powerToLufs(meanOfLastBlocks(SHORT_TERM_BLOCKS));
    }

    /** Gated integrated loudness (BS.1770) in LUFS, or {@link #SILENCE_LUFS}. */
    public double getIntegratedLufs()
    {
        // Pass 1: absolute gate (−70 LUFS) → ungated mean.
        double sumPower = 0.0;
        long count = 0;
        for (int i = 0; i < NUM_BINS; i++)
        {
            int c = integratedHistogram[i];
            if (c > 0)
            {
                sumPower += lufsToPower(binLufs(i)) * c;
                count += c;
            }
        }
        if (count == 0) return SILENCE_LUFS;

        double ungatedLufs = powerToLufs(sumPower / count);

        // Pass 2: relative gate (10 LU below the ungated mean).
        double relGate = ungatedLufs - RELATIVE_GATE_LU;
        int relBin = clampBin((int) Math.floor((relGate - HIST_MIN_LUFS) / BIN_WIDTH));

        double sumGated = 0.0;
        long countGated = 0;
        for (int i = relBin; i < NUM_BINS; i++)
        {
            int c = integratedHistogram[i];
            if (c > 0)
            {
                sumGated += lufsToPower(binLufs(i)) * c;
                countGated += c;
            }
        }
        if (countGated == 0) return SILENCE_LUFS;
        return powerToLufs(sumGated / countGated);
    }

    /**
     * Loudness Range (LRA) in LU, per EBU Tech 3342. Returns 0 when there
     * is not enough gated short-term data (e.g. material shorter than a
     * few seconds).
     */
    public double getLoudnessRange()
    {
        // Mean of the absolute-gated short-term distribution.
        double sumPower = 0.0;
        long count = 0;
        for (int i = 0; i < NUM_BINS; i++)
        {
            int c = shortTermHistogram[i];
            if (c > 0)
            {
                sumPower += lufsToPower(binLufs(i)) * c;
                count += c;
            }
        }
        if (count == 0) return 0.0;

        double meanLufs = powerToLufs(sumPower / count);
        double relGate = meanLufs - LRA_RELATIVE_GATE_LU;
        int relBin = clampBin((int) Math.floor((relGate - HIST_MIN_LUFS) / BIN_WIDTH));

        long total = 0;
        for (int i = relBin; i < NUM_BINS; i++) total += shortTermHistogram[i];
        if (total == 0) return 0.0;

        // 10th / 95th percentiles above the relative gate. The targets are
        // ceil-based with a floor of 1 so a tiny gated population (a short
        // steady tone) degenerates to LRA 0 instead of the gate span.
        long target10 = Math.max(1L, (long) Math.ceil(0.10 * total));
        long target95 = Math.max(1L, (long) Math.ceil(0.95 * total));
        double p10 = HIST_MIN_LUFS;
        double p95 = HIST_MIN_LUFS + (NUM_BINS - 1) * BIN_WIDTH;
        long running = 0;
        boolean have10 = false;
        for (int i = relBin; i < NUM_BINS; i++)
        {
            running += shortTermHistogram[i];
            if (!have10 && running >= target10)
            {
                p10 = binLufs(i);
                have10 = true;
            }
            if (running >= target95)
            {
                p95 = binLufs(i);
                break;
            }
        }
        return Math.max(0.0, p95 - p10);
    }

    /**
     * One-shot offline integrated loudness for a whole buffer.
     *
     * @param samples     interleaved samples
     * @param sampleRate  sample rate in Hz
     * @param channels    channel count
     * @return integrated LUFS, or {@link #SILENCE_LUFS} for silence
     */
    public static double measureIntegrated(float[] samples, double sampleRate, int channels)
    {
        LoudnessMeter m = new LoudnessMeter();
        m.prepare(sampleRate);
        m.process(samples, channels);
        return m.getIntegratedLufs();
    }

    /* ====================================================================
     *  Internals
     * ==================================================================== */

    private void commitBlock()
    {
        double meanPower = currentBlockPower / currentBlockFrames;
        currentBlockPower = 0.0;
        currentBlockFrames = 0;

        // A non-finite block (NaN/Inf fed by the caller) would poison the
        // sliding window and freeze the histograms forever: the K-filter
        // recursion never drains a NaN. A meter must report the signal as it
        // is NOW, so drop the block, clear the filter states and resume
        // measuring clean on the next one.
        if (!Double.isFinite(meanPower))
        {
            java.util.Arrays.fill(preZ1, 0.0);
            java.util.Arrays.fill(preZ2, 0.0);
            java.util.Arrays.fill(rlbZ1, 0.0);
            java.util.Arrays.fill(rlbZ2, 0.0);
            return;
        }

        ringPowers[writePos] = meanPower;
        writePos = (writePos + 1) % RING;
        blocksCommitted++;

        // BS.1770 integrated gating uses 400 ms blocks with 75% overlap.
        // With a 100 ms sub-block hop, that is exactly the mean of the last
        // four sub-blocks committed at every 100 ms step.
        if (blocksCommitted >= MOMENTARY_BLOCKS)
        {
            double gatingPower = meanOfLastBlocks(MOMENTARY_BLOCKS);
            addToHistogram(integratedHistogram, gatingPower);
        }
        // EBU Tech 3342 loudness range: short-term (3 s) values sampled once
        // per second (every 10 sub-blocks), absolute-gated at -70 LUFS.
        if (blocksCommitted >= SHORT_TERM_BLOCKS && (blocksCommitted % 10) == 0)
        {
            double shortPower = meanOfLastBlocks(SHORT_TERM_BLOCKS);
            addToHistogram(shortTermHistogram, shortPower);
        }
    }

    private void addToHistogram(int[] histogram, double power)
    {
        double lufs = powerToLufs(power);
        if (lufs < ABSOLUTE_GATE_LUFS) return;
        int bin = clampBin((int) Math.round((lufs - HIST_MIN_LUFS) / BIN_WIDTH));
        histogram[bin]++;
    }

    private double meanOfLastBlocks(int k)
    {
        double sum = 0.0;
        int pos = writePos;
        for (int i = 0; i < k; i++)
        {
            int idx = ((pos - 1 - i) % RING + RING) % RING;
            sum += ringPowers[idx];
        }
        return sum / k;
    }

    private double applyKWeighting(double input, int channel)
    {
        double v = preB0 * input + preZ1[channel];
        preZ1[channel] = preB1 * input - preA1 * v + preZ2[channel];
        preZ2[channel] = preB2 * input - preA2 * v + 1e-18;

        double w = rlbB0 * v + rlbZ1[channel];
        rlbZ1[channel] = rlbB1 * v - rlbA1 * w + rlbZ2[channel];
        rlbZ2[channel] = rlbB2 * v - rlbA2 * w + 1e-18;
        return w;
    }

    private void computeKWeighting(double sr)
    {
        // Pre-warped analog parameterization that reproduces the official
        // ITU-R BS.1770-5 table 1/2 coefficients at 48 kHz to machine
        // precision and generalizes to any sample rate with the same
        // frequency response, as the spec requires. The -0.691 constant in
        // powerToLufs assumes this exact cascade gain (+0.691 dB at 997 Hz);
        // an RBJ shelf or a gain-normalized RLB high-pass reads ~0.26 LU low
        // on the EBU conformance vectors.

        // Stage 1: high-shelf.
        {
            double g = 3.999843853973347;      // dB
            double q = 0.7071752369554196;
            double fc = 1681.9744509555319;    // Hz
            double k = Math.tan(Math.PI * fc / sr);
            double vh = Math.pow(10.0, g / 20.0);
            double vb = Math.pow(vh, 0.4996667741545416);
            double a0 = 1.0 + k / q + k * k;
            preB0 = (vh + vb * k / q + k * k) / a0;
            preB1 = 2.0 * (k * k - vh) / a0;
            preB2 = (vh - vb * k / q + k * k) / a0;
            preA1 = 2.0 * (k * k - 1.0) / a0;
            preA2 = (1.0 - k / q + k * k) / a0;
        }
        // Stage 2: RLB high-pass.
        {
            double q = 0.5003270373238773;
            double fc = 38.13547087602444;     // Hz
            double k = Math.tan(Math.PI * fc / sr);
            double a0 = 1.0 + k / q + k * k;
            // The official table 2 numerator is exactly [1, -2, 1] - NOT
            // normalized to unity passband gain (it passes ~+0.04 dB).
            rlbB0 = 1.0;
            rlbB1 = -2.0;
            rlbB2 = 1.0;
            rlbA1 = 2.0 * (k * k - 1.0) / a0;
            rlbA2 = (1.0 - k / q + k * k) / a0;
        }
    }

    private static double binLufs(int bin)
    {
        return HIST_MIN_LUFS + bin * BIN_WIDTH;
    }

    private static int clampBin(int bin)
    {
        if (bin < 0) return 0;
        if (bin >= NUM_BINS) return NUM_BINS - 1;
        return bin;
    }

    private static double powerToLufs(double meanPower)
    {
        if (meanPower <= 1e-10) return SILENCE_LUFS;
        return LOUDNESS_OFFSET + 10.0 * Math.log10(meanPower);
    }

    private static double lufsToPower(double lufs)
    {
        return Math.pow(10.0, (lufs - LOUDNESS_OFFSET) / 10.0);
    }
}
