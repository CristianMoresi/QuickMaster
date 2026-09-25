package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.ChannelLayout;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;

/** One synchronous BS.1770 measurement. A new instance is its only reset. */
public final class LoudnessCore
{
    private final int sampleRateHz;
    private final ChannelLayout layout;
    private final int momentaryWindowFrames;
    private final int shortTermWindowFrames;
    private final double[] coefficients;
    private final double[] shelfZ1;
    private final double[] shelfZ2;
    private final double[] highPassZ1;
    private final double[] highPassZ2;
    private final double[][] weightedPowerRing;
    private final double[][] weightedPowerTree;
    private int ringCursor;
    private long framesSeen;
    private double momentarySum;
    private double shortTermSum;

    public LoudnessCore(int sampleRateHz, ChannelLayout layout)
    {
        if (sampleRateHz <= 0 || layout == null)
            throw new IllegalArgumentException("A positive sample rate and layout are required.");
        long shortFrames = 3L * sampleRateHz;
        long momentaryFrames = StrictMath.round(0.4d * sampleRateHz);
        if (momentaryFrames < 1L) momentaryFrames = 1L;
        // Chunked N leaves and ceil(N/2) cached nodes fit the unchanged conservative
        // byte bound. Check virtual indices before allocation, using exact long arithmetic.
        if (momentaryFrames > shortFrames || 2L * shortFrames - 1L > Integer.MAX_VALUE
                || 16L * shortFrames + 1024L >= 268435456L)
            throw new IllegalArgumentException("Loudness windows exceed the analysis budget.");
        this.sampleRateHz = sampleRateHz;
        this.layout = layout;
        this.momentaryWindowFrames = (int) momentaryFrames;
        this.shortTermWindowFrames = (int) shortFrames;
        this.coefficients = coefficients(sampleRateHz);
        this.shelfZ1 = new double[layout.channels()];
        this.shelfZ2 = new double[layout.channels()];
        this.highPassZ1 = new double[layout.channels()];
        this.highPassZ2 = new double[layout.channels()];
        this.weightedPowerRing = allocateChunks(shortTermWindowFrames);
        this.weightedPowerTree = allocateChunks((int) ((shortTermWindowFrames + 1L) / 2L));
        this.ringCursor = 0;
        this.framesSeen = 0L;
        this.momentarySum = 0.0d;
        this.shortTermSum = 0.0d;
    }

    public void acceptFrame(float[] interleaved, int sampleOffset)
    {
        int channels = layout.channels();
        if (interleaved == null || sampleOffset < 0 || sampleOffset > interleaved.length - channels)
            throw new IllegalArgumentException("A complete source frame is required.");
        if (framesSeen == Long.MAX_VALUE) throw new IllegalArgumentException("Frame counter overflow.");
        // Validate even excluded LFE samples, and do not partially accept a bad frame.
        for (int channel = 0; channel < channels; channel++)
            if (!Float.isFinite(interleaved[sampleOffset + channel]))
                throw new IllegalArgumentException("PCM contains a non-finite sample.");

        double power = 0.0d;
        for (int channel = 0; channel < channels; channel++)
        {
            double x = interleaved[sampleOffset + channel];
            double shelf = coefficients[0] * x + shelfZ1[channel];
            double nextShelfZ1 = coefficients[1] * x - coefficients[3] * shelf + shelfZ2[channel];
            double nextShelfZ2 = coefficients[2] * x - coefficients[4] * shelf;
            double weighted = coefficients[5] * shelf + highPassZ1[channel];
            double nextHighPassZ1 = coefficients[6] * shelf - coefficients[8] * weighted + highPassZ2[channel];
            double nextHighPassZ2 = coefficients[7] * shelf - coefficients[9] * weighted;
            if (!Double.isFinite(shelf) || !Double.isFinite(weighted)
                    || !Double.isFinite(nextShelfZ1) || !Double.isFinite(nextShelfZ2)
                    || !Double.isFinite(nextHighPassZ1) || !Double.isFinite(nextHighPassZ2))
                throw new IllegalArgumentException("Non-finite K-weighting state.");
            double channelPower = weighted * weighted;
            requirePower(channelPower);
            power += layout.powerWeight(channel) * channelPower;
            requirePower(power);
            shelfZ1[channel] = nextShelfZ1;
            shelfZ2[channel] = nextShelfZ2;
            highPassZ1[channel] = nextHighPassZ1;
            highPassZ2[channel] = nextHighPassZ2;
        }

        requirePower(momentarySum);
        requirePower(shortTermSum);
        if (ringCursor < 0 || ringCursor >= shortTermWindowFrames)
            throw new IllegalArgumentException("Invalid loudness ring cursor.");
        requirePower(storagePower(weightedPowerRing, shortTermWindowFrames, ringCursor));
        storePower(weightedPowerRing, shortTermWindowFrames, ringCursor, power);
        rebuildTree(weightedPowerRing, weightedPowerTree, shortTermWindowFrames, ringCursor);
        ringCursor++;
        if (ringCursor == shortTermWindowFrames) ringCursor = 0;
        framesSeen++;

        // Every aggregate reduces only current leaves; expired energy cannot
        // survive through subtraction. Both circular reductions cost O(log N).
        momentarySum = treeWindow(weightedPowerRing, weightedPowerTree, shortTermWindowFrames, ringCursor,
                (int) Math.min(framesSeen, momentaryWindowFrames));
        shortTermSum = treeWindow(weightedPowerRing, weightedPowerTree, shortTermWindowFrames, ringCursor,
                (int) Math.min(framesSeen, shortTermWindowFrames));
        requirePower(momentarySum);
        requirePower(shortTermSum);
    }

    public long framesSeen() { return framesSeen; }

    public double momentaryPower()
    {
        if (framesSeen < momentaryWindowFrames)
            throw new IllegalArgumentException("Momentary window is not complete.");
        requirePower(momentarySum);
        return momentarySum / momentaryWindowFrames;
    }

    public double shortTermPower()
    {
        if (framesSeen < shortTermWindowFrames)
            throw new IllegalArgumentException("Short-term window is not complete.");
        requirePower(shortTermSum);
        return shortTermSum / shortTermWindowFrames;
    }

    /** Returns null only on cancellation; absence is a completed zero-selected-block result. */
    public static MeasuredLoudness integrated(double[] blockPower, int completeBlocks,
                                               long[] gateCounts, CancellationToken cancellation)
    {
        if (blockPower == null || completeBlocks < 0 || completeBlocks > blockPower.length
                || gateCounts == null || gateCounts.length != 3)
            throw new IllegalArgumentException("Invalid Integrated block vector or gate counters.");
        gateCounts[0] = 0L;
        gateCounts[1] = 0L;
        gateCounts[2] = 0L;
        double absoluteSum = 0.0d;
        long absoluteCount = 0L;
        for (int i = 0; i < blockPower.length; i++)
        {
            if ((i & 4095) == 0 && cancellation != null && cancellation.isCancelled()) return null;
            double power = blockPower[i];
            requirePower(power);
            if (i < completeBlocks && power > 0.0d && lufs(power) > -70.0d)
            {
                absoluteSum += power;
                requirePower(absoluteSum);
                absoluteCount++;
            }
        }
        double selectedSum = 0.0d;
        long selectedCount = 0L;
        if (absoluteCount != 0L)
        {
            double relativeGate = lufs(absoluteSum / absoluteCount) - 10.0d;
            for (int i = 0; i < completeBlocks; i++)
            {
                if ((i & 4095) == 0 && cancellation != null && cancellation.isCancelled()) return null;
                double power = blockPower[i];
                if (power > 0.0d)
                {
                    double loudness = lufs(power);
                    if (loudness > -70.0d && loudness > relativeGate)
                    {
                        selectedSum += power;
                        requirePower(selectedSum);
                        selectedCount++;
                    }
                }
            }
        }
        if (cancellation != null && cancellation.isCancelled()) return null;
        gateCounts[0] = completeBlocks;
        gateCounts[1] = absoluteCount;
        gateCounts[2] = selectedCount;
        return selectedCount == 0L ? MeasuredLoudness.absent() : fromPower(selectedSum / selectedCount);
    }

    public static MeasuredLoudness fromPower(double power)
    {
        requirePower(power);
        return power == 0.0d ? MeasuredLoudness.absent() : new MeasuredLoudness(true, lufs(power));
    }

    private static void requirePower(double power)
    {
        if (!Double.isFinite(power) || power < 0.0d)
            throw new IllegalArgumentException("Power must be finite and nonnegative.");
    }

    private static double lufs(double power)
    {
        double value = -0.691d + 10.0d * StrictMath.log10(power);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite loudness.");
        return value;
    }

    private static double[][] allocateChunks(int length)
    {
        if (length <= 0) throw new IllegalArgumentException("Invalid loudness storage length.");
        // At most 256 KiB of payload per object; the final chunk has no padding.
        double[][] storage = new double[(length - 1) / 32768 + 1][];
        for (int page = 0; page < storage.length; page++)
        {
            int remaining = length - page * 32768;
            storage[page] = new double[remaining < 32768 ? remaining : 32768];
        }
        return storage;
    }

    private static double storagePower(double[][] storage, int length, int index)
    {
        if (length <= 0 || index < 0 || index >= length || storage == null
                || storage.length != (length - 1) / 32768 + 1)
            throw new IllegalArgumentException("Invalid loudness storage index.");
        int page = index >>> 15;
        int remaining = length - page * 32768;
        double[] chunk = storage[page];
        if (chunk == null || chunk.length != (remaining < 32768 ? remaining : 32768))
            throw new IllegalArgumentException("Invalid loudness storage chunk.");
        double value = chunk[index & 32767];
        requirePower(value);
        return value;
    }

    private static void storePower(double[][] storage, int length, int index, double value)
    {
        if (length <= 0 || index < 0 || index >= length || storage == null
                || storage.length != (length - 1) / 32768 + 1)
            throw new IllegalArgumentException("Invalid loudness storage index.");
        int page = index >>> 15;
        int remaining = length - page * 32768;
        double[] chunk = storage[page];
        if (chunk == null || chunk.length != (remaining < 32768 ? remaining : 32768))
            throw new IllegalArgumentException("Invalid loudness storage chunk.");
        requirePower(value);
        chunk[index & 32767] = value;
    }

    private static double nodePower(double[][] ring, double[][] tree, int n, int node)
    {
        if (n <= 0 || 2L * n - 1L > Integer.MAX_VALUE || ring == null || tree == null || ring == tree
                || ring.length != (n - 1) / 32768 + 1 || tree.length != ((n + 1L) / 2L - 1L) / 32768 + 1
                || node < 1 || node >= 2L * n)
            throw new IllegalArgumentException("Invalid loudness tree node.");
        int k = (int) ((n + 1L) / 2L);
        if (node >= n) return storagePower(ring, n, node - n);
        if (node < k) return storagePower(tree, k, node);
        // These and only these internal nodes have two leaves. Validate each
        // operand before the same left+right addition used by the flat baseline.
        double value = storagePower(ring, n, 2 * node - n)
                + storagePower(ring, n, 2 * node + 1 - n);
        requirePower(value);
        return value;
    }

    private static void rebuildTree(double[][] ring, double[][] tree, int n, int leaf)
    {
        if (n <= 0 || 2L * n - 1L > Integer.MAX_VALUE || ring == null || tree == null || ring == tree
                || ring.length != (n - 1) / 32768 + 1 || tree.length != ((n + 1L) / 2L - 1L) / 32768 + 1
                || leaf < 0 || leaf >= n)
            throw new IllegalArgumentException("Invalid loudness tree leaf.");
        requirePower(storagePower(ring, n, leaf));
        int k = (int) ((n + 1L) / 2L);
        for (int node = (n + leaf) / 2; node > 0; node /= 2)
        {
            double sum = nodePower(ring, tree, n, 2 * node) + nodePower(ring, tree, n, 2 * node + 1);
            requirePower(sum);
            if (node < k) storePower(tree, k, node, sum);
        }
    }

    private static double treeRange(double[][] ring, double[][] tree, int n, int start, int end)
    {
        if (n <= 0 || 2L * n - 1L > Integer.MAX_VALUE || ring == null || tree == null || ring == tree
                || ring.length != (n - 1) / 32768 + 1 || tree.length != ((n + 1L) / 2L - 1L) / 32768 + 1
                || start < 0 || end < start || end > n)
            throw new IllegalArgumentException("Invalid loudness tree range.");
        long left = (long) n + start, right = (long) n + end;
        double prefix = 0.0d, suffix = 0.0d;
        while (left < right)
        {
            if ((left & 1L) != 0L)
            {
                prefix += nodePower(ring, tree, n, (int) left++);
                requirePower(prefix);
            }
            if ((right & 1L) != 0L)
            {
                suffix = nodePower(ring, tree, n, (int) --right) + suffix;
                requirePower(suffix);
            }
            left /= 2L;
            right /= 2L;
        }
        double result = prefix + suffix;
        requirePower(result);
        return result;
    }

    private static double treeWindow(double[][] ring, double[][] tree, int n, int end, int length)
    {
        if (n <= 0 || 2L * n - 1L > Integer.MAX_VALUE || ring == null || tree == null || ring == tree
                || ring.length != (n - 1) / 32768 + 1 || tree.length != ((n + 1L) / 2L - 1L) / 32768 + 1
                || end < 0 || end >= n || length < 0 || length > n)
            throw new IllegalArgumentException("Invalid loudness tree window.");
        int start = end - length;
        if (start >= 0) return treeRange(ring, tree, n, start, end);
        double result = treeRange(ring, tree, n, start + n, n)
                + treeRange(ring, tree, n, 0, end);
        requirePower(result);
        return result;
    }

    private static double[] coefficients(int rate)
    {
        // BS.1770-5 Annex 1 tables 1/2 specify these coefficients at 48 kHz.
        // At another rate, substitute the inverse/forward bilinear transforms:
        // q48 = ((48000-rate) + (48000+rate)*q) / ((48000+rate) + (48000-rate)*q).
        // This preserves the two specified analogue transfer functions, including
        // the RLB numerator gain. No coefficients are fitted to test-signal levels.
        double[] result = { 1.53512485958697d, -2.69169618940638d, 1.19839281085285d,
                -1.69065929318241d, 0.73248077421585d,
                1.0d, -2.0d, 1.0d, -1.99004745483398d, 0.99007225036621d };
        if (rate != 48_000)
        {
            double ratio = rate / 48_000.0d;
            double u = 1.0d + ratio;
            double v = 1.0d - ratio;
            double uu = u * u;
            double vv = v * v;
            double uv = u * v;
            for (int offset = 0; offset < 10; offset += 5)
            {
                double b0 = result[offset], b1 = result[offset + 1], b2 = result[offset + 2];
                double a1 = result[offset + 3], a2 = result[offset + 4];
                double denominator = uu + a1 * uv + a2 * vv;
                if (offset == 5)
                {
                    // Preserve the exact double zero at DC without cancellation
                    // in u*u-2*u*v+v*v for unusually small positive sample rates.
                    result[offset] = 4.0d * ratio * ratio / denominator;
                    result[offset + 1] = -2.0d * result[offset];
                    result[offset + 2] = result[offset];
                }
                else
                {
                    result[offset] = (b0 * uu + b1 * uv + b2 * vv) / denominator;
                    result[offset + 1] = (2.0d * (b0 + b2) * uv + b1 * (uu + vv)) / denominator;
                    result[offset + 2] = (b0 * vv + b1 * uv + b2 * uu) / denominator;
                }
                result[offset + 3] = (2.0d * (1.0d + a2) * uv + a1 * (uu + vv)) / denominator;
                result[offset + 4] = (vv + a1 * uv + a2 * uu) / denominator;
            }
        }
        for (int i = 0; i < result.length; i++)
            if (!Double.isFinite(result[i])) throw new IllegalArgumentException("Non-finite K-weighting coefficients.");
        return result;
    }
}
