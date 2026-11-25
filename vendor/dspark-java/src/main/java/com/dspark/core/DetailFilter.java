package com.dspark.core;

/**
 * A time-varying minimum-phase filter drawn on a log-frequency grid, with
 * detail in the low end too (a 24th of an octave from 200 Hz).
 * <p>
 * A gain curve a twelfth of an octave fine at 100 Hz needs an impulse
 * response of a third of a second: one transform long enough for it, every
 * hop and over the whole band, costs several times what the top needs. The
 * curve is split in decibels by a crossover an octave wide (900 to 1800 Hz
 * at 48 kHz) into two minimum-phase filters in series, whose product is the
 * curve. Under the crossover they share it by resolution as well: what is
 * broad is the high part's, and the low part gets what is left, the fine
 * detail, whose mean is zero.
 * <ul>
 *   <li>The low part runs at an eighth of the rate (a sixteenth from 88.2
 *       kHz). It works as a correction: the programme, delayed, plus the
 *       interpolated difference the low filter makes, so a flat curve
 *       returns the programme whatever the decimator does to the top.</li>
 *   <li>The high part runs at the rate, in windows of 1024 samples with a
 *       response of up to three windows.</li>
 * </ul>
 * The curve has {@link #POINTS} values in decibels, evenly in log frequency
 * from 20 Hz to 20 kHz (the end values hold beyond). The owner's callback
 * writes it every {@link #getHopSize()} samples: one curve per channel, or
 * one for all with {@link #setLinked}. An owner that designs from the signal
 * reads it through the feed callback, which keeps its analysis on the same
 * samples whatever the blocks.
 * <p>
 * This is the framework's block-latency form: kernels are convolved in
 * partitions and the output runs through the last three by a quadratic
 * B-spline. Latency ({@link #getLatency()}): 774 samples at 44.1 and 48 kHz,
 * doubling by octaves with the rate. The output does not depend on how the
 * stream is cut into blocks.
 * <p>
 * {@link #prepare} allocates; {@link #process} and {@link #reset} run on the
 * owner's thread without allocation.
 */
public final class DetailFilter
{
    /** Points of a curve. */
    public static final int POINTS = DetailAnalysis.POINTS;
    /** Channels filtered at most. */
    public static final int MAX_CHANNELS = 16;

    /** Reads the samples about to be taken: [offset, offset + count) of the block, still untouched. */
    public interface Feed { void feed(int offset, int count); }

    /** Writes the {@link #POINTS} gains wanted now, in decibels (the channel is 0 when linked). */
    public interface Design { void design(int channel, double[] curveDb); }

    private static final int FACTOR = 8;          // decimation of the low part at 44.1 and 48 kHz
    private static final int SLOW_QUANTUM = 4;    // slow samples worked on at once
    private static final int SLOW_WINDOW = 64;    // window of the low filter, in slow samples
    private static final int SLOW_PADDING = 32;   // its transform over its window: 2.9 Hz a bin
    private static final int WINDOW = 1024;       // window of the high filter
    private static final int SLOW_STEPS = 4;      // blocks of the low part from a kernel to the next
    private static final int STEPS = 1;           // and of the high part
    private static final int BLOCK_LATENCY = 774; // the latency kept at 44.1 and 48 kHz
    private static final int PADDING = 4;         // the high transform over its window: 11.7 Hz a bin

    /** The frequency of a point of the curve, in hertz. */
    public static double frequency(int point) { return DetailAnalysis.frequency(point); }

    private double fs = 48000.0;
    private int channels = 0, factor = FACTOR, quantum = FACTOR * SLOW_QUANTUM, latency = 0;
    private int correctionDelay = 0, hopQuanta = 1, quanta = 0, filled = 0, curveCount = 0;
    private boolean prepared = false, linked = false;
    private final RateChange rate = new RateChange();
    private final Convolver low = new Convolver(), high = new Convolver();
    private final BinMap lowMap = new BinMap(), highMap = new BinMap();
    private final Split split = new Split();
    private final Line align = new Line(), programme = new Line(), slowDry = new Line();
    private double[][] slow = new double[0][], before = new double[0][], change = new double[0][];
    private double[][] in = new double[0][], out = new double[0][];
    private double[][] curve = new double[0][], fine = new double[0][], broad = new double[0][];

    private final Convolver.KernelDesign lowDesign = (slot, gain, bins) ->
            System.arraycopy(fine[curveIndex(slot)], 0, gain, 0, Math.min(bins, split.fineBins));
    private final Convolver.KernelDesign highDesign = (slot, gain, bins) ->
    {
        int c = curveIndex(slot);
        highMap.write(curve[c], gain, bins);
        split.addBroad(broad[c], gain, bins);
    };

    /**
     * Allocates for a layout (an invalid one is ignored; channels over
     * {@link #MAX_CHANNELS} are not filtered).
     */
    public void prepare(double sampleRate, int numChannels)
    {
        if (!Double.isFinite(sampleRate) || !(sampleRate > 0.0) || numChannels < 1) return;
        prepared = false;
        fs = sampleRate;
        channels = Math.min(numChannels, MAX_CHANNELS);
        int octaves = (int) Math.max(0, Math.min(lround(log2(Math.max(fs, 44100.0) / 44100.0)), 3));
        int scale = 1 << octaves;

        // Everything grows with the rate; the factor stops at 16, and the
        // slow windows grow from there. It shrinks with the rate as well, to
        // two octaves down, at the same hertz a bin.
        int down = (int) Math.max(0, Math.min(-lround(log2(fs / 48000.0)), 2));
        int baseFactor = FACTOR >> down;
        factor = Math.min(16, baseFactor * scale);
        int slowScale = scale * baseFactor / factor;
        quantum = SLOW_QUANTUM * factor;
        double slowRate = fs / factor;

        rate.prepare(channels, factor);
        int slowWindow = SLOW_WINDOW * slowScale, window = (WINDOW >> down) * scale;
        low.prepare(slowRate, channels, slowWindow, SLOW_PADDING, slowWindow / 4, SLOW_STEPS);
        high.prepare(fs, channels, window, PADDING, window / 4, STEPS);

        int lowSpan = low.getLatency() * factor;
        correctionDelay = 2 * rate.half + lowSpan;
        int needed = quantum + correctionDelay + high.getLatency();
        // What is over the engine's own delay is lead for the high part.
        latency = Math.max(needed, (BLOCK_LATENCY * scale) >> down);

        align.prepare(channels, latency - needed);
        programme.prepare(channels, correctionDelay);
        slowDry.prepare(channels, low.getLatency());
        slow = new double[channels][SLOW_QUANTUM];
        before = new double[channels][SLOW_QUANTUM];
        change = new double[channels][quantum];
        in = new double[channels][quantum];
        out = new double[channels][quantum];

        hopQuanta = Math.max(1, low.getHopSize() * factor / quantum);
        curve = new double[channels][POINTS];

        // The crossover: well inside what the decimator keeps at any rate.
        double upper = Math.min(1800.0, 0.3 * slowRate);
        lowMap.prepare(low.getBinHz(), low.getNumBins(), upper, true);
        highMap.prepare(high.getBinHz(), high.getNumBins(), upper, false);
        split.prepare(upper / (Split.STEP * high.getBinHz()), low.getNumBins(), (high.getNumBins() - 1) / Split.STEP + 1);
        fine = new double[channels][split.fineBins];
        broad = new double[channels][split.broadBins];
        prepared = true;
        reset();
    }

    /** Clears the signal and the curves. */
    public void reset()
    {
        if (!prepared) return;
        rate.reset();
        low.reset();
        high.reset();
        align.reset();
        programme.reset();
        slowDry.reset();
        for (double[] a : in) java.util.Arrays.fill(a, 0.0);
        for (double[] a : out) java.util.Arrays.fill(a, 0.0);
        for (double[] a : curve) java.util.Arrays.fill(a, 0.0);
        for (double[] a : fine) java.util.Arrays.fill(a, 0.0);
        for (double[] a : broad) java.util.Arrays.fill(a, 0.0);
        filled = 0;
        quanta = 0;
        curveCount = 0;
    }

    /** One curve and one filter for every channel. */
    public void setLinked(boolean on)
    {
        linked = on;
        low.setLinked(on);
        high.setLinked(on);
    }

    /** Whether one curve serves every channel. */
    public boolean isLinked() { return linked; }

    /** The latency in samples. */
    public int getLatency() { return latency; }

    /** Samples from one curve to the next. */
    public int getHopSize() { return hopQuanta * quantum; }

    /** Curves designed since reset(), modulo 2^30. */
    public int getCurveCount() { return curveCount; }

    /**
     * Filters a block in place.
     *
     * @param buffer  the audio, one array per channel
     * @param samples the samples of the block (from index 0 of each channel)
     * @param feed    the callback that reads the block, or null
     * @param design  the callback that draws a curve, run once at the end of each hop
     */
    public void process(double[][] buffer, int samples, Feed feed, Design design)
    {
        if (!prepared) return;
        int ch = Math.min(buffer.length, channels);
        if (ch <= 0) return;
        int done = 0;
        while (done < samples)
        {
            int count = Math.min(samples - done, quantum - filled);
            if (feed != null) feed.feed(done, count);
            for (int c = 0; c < ch; c++)
            {
                System.arraycopy(buffer[c], done, in[c], filled, count);
                System.arraycopy(out[c], filled, buffer[c], done, count);
            }
            filled += count;
            done += count;
            if (filled == quantum)
            {
                filled = 0;
                runQuantum(ch, design);
            }
        }
    }

    private int curveIndex(int channel) { return linked ? 0 : Math.max(channel, 0); }

    private void runQuantum(int ch, Design design)
    {
        // The last quantum of a hop: the low filter takes its frame in it.
        if (quanta == hopQuanta - 1)
        {
            curveCount = (curveCount + 1) & 0x3FFFFFFF;
            int curves = linked ? 1 : ch;
            for (int c = 0; c < curves; c++)
            {
                design.design(c, curve[c]);
                lowMap.write(curve[c], fine[c], split.fineBins);
                split.run(fine[c], broad[c]);
            }
        }
        quanta = (quanta + 1) % hopQuanta;

        for (int c = 0; c < ch; c++)
        {
            align.process(c, in[c], quantum);
            rate.decimate(c, in[c], slow[c]);
            System.arraycopy(slow[c], 0, before[c], 0, SLOW_QUANTUM);
            slowDry.process(c, before[c], SLOW_QUANTUM);
        }

        // The low filter and what it changed.
        low.processBlock(slow, SLOW_QUANTUM, ch, lowDesign);

        // That change onto the programme, then the high filter.
        for (int c = 0; c < ch; c++)
        {
            final double[] s = slow[c], b = before[c], x = in[c], added = change[c], y = out[c];
            for (int i = 0; i < SLOW_QUANTUM; i++) s[i] -= b[i];
            rate.interpolate(c, s, added);
            programme.process(c, x, quantum);
            for (int i = 0; i < quantum; i++) y[i] = x[i] + added[i];
        }
        high.processBlock(out, quantum, ch, highDesign);
    }

    private static double log2(double x) { return Math.log(x) / Math.log(2.0); }

    /** Rounds half away from zero. */
    private static long lround(double x) { return x < 0.0 ? -(long) Math.floor(-x + 0.5) : (long) Math.floor(x + 0.5); }

    // ------------------------------------------------------------------
    // A whole-sample delay of every channel.
    // ------------------------------------------------------------------
    private static final class Line
    {
        private double[][] data = new double[0][];
        private int[] at = new int[0];
        private int length = 0;

        void prepare(int channels, int delay)
        {
            length = Math.max(0, delay);
            data = new double[channels][length];
            at = new int[channels];
        }

        void reset()
        {
            for (double[] d : data) java.util.Arrays.fill(d, 0.0);
            java.util.Arrays.fill(at, 0);
        }

        void process(int channel, double[] x, int count)
        {
            if (length == 0) return;
            final double[] d = data[channel];
            int p = at[channel];
            // Each sample out is the one in `length` ago.
            for (int i = 0; i < count; i++)
            {
                double held = d[p];
                d[p] = x[i];
                x[i] = held;
                if (++p == length) p = 0;
            }
            at[channel] = p;
        }
    }

    // ------------------------------------------------------------------
    // Down and up by the factor through one linear-phase low-pass, 100 dB
    // down from the slow Nyquist. Together they delay by exactly twice half.
    // ------------------------------------------------------------------
    private static final class RateChange
    {
        private double[] taps = new double[1], phases = new double[1];
        private double[][] down = new double[0][], up = new double[0][];
        private int factor = FACTOR, half = 0, length = 1, perPhase = 1;

        void prepare(int channels, int rateFactor)
        {
            final double depth = 100.0, passEdge = 0.35;   // of the slow rate
            factor = rateFactor;
            double transition = (0.5 - passEdge) / factor;
            half = (int) Math.ceil(0.5 * (depth - 7.95) / (14.36 * transition));
            length = 2 * half + 1;
            perPhase = (length + factor - 1) / factor;
            taps = FirDesign.lowPass(1.0, 0.5 * (0.5 + passEdge) / factor, length, FirDesign.estimateKaiserBeta(depth));
            // Each phase of the interpolator, oldest sample first.
            phases = new double[perPhase * factor];
            for (int r = 0; r < factor; r++)
                for (int i = 0; i < perPhase; i++)
                {
                    int n = (perPhase - 1 - i) * factor + r;
                    if (n < length) phases[r * perPhase + i] = factor * taps[n];
                }
            down = new double[channels][length - 1 + factor * SLOW_QUANTUM];
            up = new double[channels][perPhase - 1 + SLOW_QUANTUM];
        }

        void reset()
        {
            for (double[] d : down) java.util.Arrays.fill(d, 0.0);
            for (double[] u : up) java.util.Arrays.fill(u, 0.0);
        }

        void decimate(int channel, double[] fast, double[] slowOut)
        {
            final double[] past = down[channel], t = taps;
            int count = factor * SLOW_QUANTUM;
            System.arraycopy(fast, 0, past, length - 1, count);
            // The kernel is symmetric, so it correlates as it is.
            for (int j = 0; j < SLOW_QUANTUM; j++)
            {
                int base = j * factor;
                double sum = 0.0;
                for (int k = 0; k < length; k++) sum += past[base + k] * t[k];
                slowOut[j] = sum;
            }
            System.arraycopy(past, count, past, 0, length - 1);
        }

        void interpolate(int channel, double[] slowIn, double[] fast)
        {
            final double[] past = up[channel], ph = phases;
            System.arraycopy(slowIn, 0, past, perPhase - 1, SLOW_QUANTUM);
            for (int j = 0; j < SLOW_QUANTUM; j++)
                for (int r = 0; r < factor; r++)
                {
                    int p = r * perPhase;
                    double sum = 0.0;
                    for (int k = 0; k < perPhase; k++) sum += past[j + k] * ph[p + k];
                    fast[j * factor + r] = sum;
                }
            System.arraycopy(past, SLOW_QUANTUM, past, 0, perPhase - 1);
        }
    }

    // ------------------------------------------------------------------
    // The share of the curve that one filter plays, on that filter's bins.
    // ------------------------------------------------------------------
    private static final class BinMap
    {
        private int[] point = new int[0];
        private double[] part = new double[0], share = new double[0];

        void prepare(double binHz, int bins, double upper, boolean isLow)
        {
            point = new int[bins];
            part = new double[bins];
            share = new double[bins];
            double lastPoint = POINTS - 1, span = Math.log(1000.0);
            for (int k = 0; k < bins; k++)
            {
                double hz = Math.max(k * binHz, 1.0);
                double at = Math.max(0.0, Math.min(lastPoint * Math.log(hz / 20.0) / span, lastPoint));
                int under = Math.min((int) at, POINTS - 2);
                point[k] = under;
                part[k] = at - under;
                // All low under half the upper edge, none from the edge on.
                double over = Math.max(0.0, Math.min(1.0 + log2(hz / upper), 1.0));
                double lowShare = 0.5 + 0.5 * Math.cos(Math.PI * over);
                share[k] = isLow ? lowShare : 1.0 - lowShare;
            }
        }

        /** The natural logarithm of the gain of each bin. */
        void write(double[] curveDb, double[] logGain, int bins)
        {
            final double perDb = 0.11512925464970229;   // ln(10) / 20
            int count = Math.min(bins, share.length);
            for (int k = 0; k < count; k++)
            {
                int p = point[k];
                logGain[k] = perDb * share[k] * (curveDb[p] + part[k] * (curveDb[p + 1] - curveDb[p]));
            }
        }
    }

    // ------------------------------------------------------------------
    // The low share of a curve in two: its broad shape on the high part's
    // bins, and the fine detail left on the low part's.
    // ------------------------------------------------------------------
    private static final class Split
    {
        static final int RATIO = 8;   // bins of the low part in one of the broad shape's, at every rate
        static final int STEP = 2;    // and of the high part
        static final int REACH = 6;   // the smoothing's, each side, in bins of the broad shape
        static final int WIDTH = 2 * REACH + 1;

        int broadBins = 0, fineBins = 0;
        private final double[] kernel = new double[WIDTH];
        private final double[][] between = new double[RATIO][4];
        private double[] binned = new double[0], around = new double[0];

        void prepare(double upperBin, int lowBins, int highBins)
        {
            broadBins = Math.min(highBins, (int) Math.ceil(upperBin) + REACH + 1);
            fineBins = Math.min(lowBins, RATIO * (broadBins + 1) + 1);
            binned = new double[broadBins + 2 * REACH];
            around = new double[broadBins + 4];
            // Three boxes of five bins, over their whole weight.
            double[] box = new double[WIDTH];
            box[0] = 1.0;
            for (int pass = 0, length = 1; pass < 3; pass++, length += 4)
                for (int i = length + 3; i >= 0; i--)
                {
                    double sum = 0.0;
                    for (int j = Math.max(0, i - 4); j <= Math.min(i, length - 1); j++) sum += box[j];
                    box[i] = sum;
                }
            for (int i = 0; i < WIDTH; i++) kernel[i] = box[i] / 125.0;
            // Catmull-Rom between two bins of the high part.
            for (int i = 0; i < RATIO; i++)
            {
                double t = (double) i / RATIO;
                between[i][0] = 0.5 * t * ((2.0 - t) * t - 1.0);
                between[i][1] = 0.5 * (t * t * (3.0 * t - 5.0) + 2.0);
                between[i][2] = 0.5 * t * ((4.0 - 3.0 * t) * t + 1.0);
                between[i][3] = 0.5 * t * t * (t - 1.0);
            }
        }

        private double broadAt(double[] b, int j)
        {
            int k = j < 0 ? -j : j;
            return k < broadBins ? b[k] : 0.0;
        }

        /**
         * Adds a broad shape to the high part's log gains, read between its
         * bins as run() takes it from the low part's: the two make the curve.
         */
        void addBroad(double[] b, double[] gain, int bins)
        {
            int count = Math.min(bins, STEP * (broadBins + 1));
            for (int k = 0; k < count; k++)
            {
                int j = k / STEP;
                double[] w = between[(k % STEP) * (RATIO / STEP)];
                gain[k] += w[0] * broadAt(b, j - 1) + w[1] * broadAt(b, j) + w[2] * broadAt(b, j + 1) + w[3] * broadAt(b, j + 2);
            }
        }

        /** fineGain: the low share's log gains on the low bins on entry, the detail left on return. */
        void run(double[] fineGain, double[] b)
        {
            // Onto the high part's bins by areas, with the spectrum's mirror at zero.
            // binned is offset by REACH; around by 1.
            final double[] bn = binned, sm = around;
            for (int k = 0; k < broadBins + REACH; k++)
            {
                double sum = 0.0;
                for (int j = -RATIO / 2; j <= RATIO / 2; j++)
                {
                    int from = RATIO * k + j, at = from < 0 ? -from : from;
                    if (at < fineBins) sum += (j == -RATIO / 2 || j == RATIO / 2 ? 0.5 : 1.0) * fineGain[at];
                }
                bn[REACH + k] = sum / RATIO;
            }
            for (int k = 1; k <= REACH; k++) bn[REACH - k] = bn[REACH + k];

            for (int k = 0; k < broadBins; k++)
            {
                double sum = 0.0;
                for (int i = 0; i < WIDTH; i++) sum += kernel[i] * bn[k + i];   // binned[k + i - REACH]
                sm[1 + k] = sum;
                b[k] = sum;
            }
            sm[0] = sm[2];   // smooth[-1] = smooth[1]
            for (int k = broadBins; k < broadBins + 3; k++) sm[1 + k] = 0.0;

            for (int j = 0; j < fineBins; j++)
            {
                int s = 1 + Math.min(j / RATIO, broadBins);
                double[] w = between[j % RATIO];
                fineGain[j] -= w[0] * sm[s - 1] + w[1] * sm[s] + w[2] * sm[s + 1] + w[3] * sm[s + 2];
            }
        }
    }

    // ------------------------------------------------------------------
    // A part of the filter: minimum-phase kernels designed at the window and
    // padding given, convolved in partitions of a block. A kernel is designed
    // every `steps` blocks and the output runs through the last three by a
    // quadratic B-spline. A flat curve returns the input to the bit, late by
    // a block.
    // ------------------------------------------------------------------
    private static final class Convolver
    {
        /** Writes natural logarithms of gains (preset to 0); the slot is -1 when linked. */
        interface KernelDesign { void design(int slot, double[] logGain, int bins); }

        private static final int KERNELS = 3;

        private double fs = 48000.0;
        private int channels = 0, size = 0, hop = 0, block = 1, steps = 2, bins = 0, length = 0, partitions = 1, stride = 4;
        private int at = 0, head = 0, count = 0, fading = 2;
        private boolean prepared = false, linked = false;
        private FFTRealDouble designFft, transform;
        private double[] logGain = new double[0], time = new double[0], sum = new double[0];
        private double[] oldest = new double[0], newest = new double[0], older = new double[0], newer = new double[0];
        private double[] spectrum = new double[0], kernel = new double[0], taper = new double[0];
        private double[][] in = new double[0][], before = new double[0][], out = new double[0][], past = new double[0][];
        private double[][][] partition = new double[0][][];
        private boolean[][] flat = new boolean[0][];
        private int[] newestKernel = new int[0];

        void prepare(double sampleRate, int numChannels, int window, int padding, int blockSize, int stepCount)
        {
            prepared = false;
            fs = sampleRate;
            channels = Math.max(numChannels, 0);
            size = window * padding;
            hop = window / 2;
            block = Math.max(Math.max(1, window / 4), Math.min(blockSize, Math.max(1, window)));
            steps = Math.max(stepCount, 1);
            bins = size / 2 + 1;
            length = size - window + 1;
            partitions = (length + block - 1) / block;
            stride = 2 * (block + 1);

            designFft = new FFTRealDouble(size);
            transform = new FFTRealDouble(2 * block);
            logGain = new double[bins];
            spectrum = new double[size + 2];
            kernel = new double[size + 2];
            taper = new double[length];
            java.util.Arrays.fill(taper, 1.0);
            int tail = Math.max(1, length / 4);
            for (int i = 0; i < tail; i++)
                taper[length - tail + i] = 0.5 + 0.5 * Math.cos(Math.PI * (i + 1) / (tail + 1));
            int fade = steps * block;
            older = new double[fade];
            newer = new double[fade];
            for (int i = 0; i < fade; i++)
            {
                double u = (double) (i + 1) / fade;
                older[i] = 0.5 * (1.0 - u) * (1.0 - u);
                newer[i] = 0.5 * u * u;
            }

            time = new double[2 * block + 2];
            sum = new double[stride];
            oldest = new double[block];
            newest = new double[block];
            int spectra = partitions * stride;
            in = new double[channels][block];
            before = new double[channels][block];
            out = new double[channels][block];
            past = new double[channels][spectra];
            partition = new double[channels][KERNELS][spectra];
            flat = new boolean[channels][KERNELS];
            newestKernel = new int[channels];
            prepared = true;
            reset();
        }

        /** Clears the signal and the kernels (every one is then flat). */
        void reset()
        {
            if (!prepared) return;
            for (int c = 0; c < channels; c++)
            {
                java.util.Arrays.fill(in[c], 0.0);
                java.util.Arrays.fill(before[c], 0.0);
                java.util.Arrays.fill(out[c], 0.0);
                java.util.Arrays.fill(past[c], 0.0);
                java.util.Arrays.fill(flat[c], true);
                newestKernel[c] = 0;
            }
            at = 0;
            head = 0;
            count = 0;
            fading = steps;
        }

        void setLinked(boolean on) { linked = on; }

        /** The latency: a block. */
        int getLatency() { return block; }
        /** Half a window: the owner's hop, read from the low part. */
        int getHopSize() { return hop; }
        int getNumBins() { return bins; }
        double getBinHz() { return fs / size; }

        /** Filters a block in place. */
        void processBlock(double[][] buffer, int samples, int numChannels, KernelDesign design)
        {
            if (!prepared) return;
            int ch = Math.min(numChannels, channels);
            for (int done = 0; done < samples;)
            {
                int n = Math.min(samples - done, block - at);
                for (int c = 0; c < ch; c++)
                {
                    final double[] x = buffer[c];
                    System.arraycopy(x, done, in[c], at, n);
                    System.arraycopy(out[c], at, x, done, n);
                }
                done += n;
                at += n;
                if (at < block) continue;
                at = 0;
                step(ch, design);
            }
        }

        private void step(int ch, KernelDesign design)
        {
            if (++count == steps)
            {
                count = 0;
                fading = 0;
                boolean one = linked && ch > 1;
                for (int s = 0; s < (one ? 1 : ch); s++)
                {
                    java.util.Arrays.fill(logGain, 0.0);
                    design.design(one ? -1 : s, logGain, bins);
                    build(s);
                }
            }
            head = head + 1 == partitions ? 0 : head + 1;
            for (int c = 0; c < ch; c++) convolve(c, linked ? 0 : c);
            if (fading < steps) fading++;
        }

        private void build(int s)
        {
            newestKernel[s] = (newestKernel[s] + 1) % KERNELS;
            int nw = newestKernel[s];
            boolean isFlat = true;
            for (int k = 0; k < bins && isFlat; k++) isFlat = logGain[k] == 0.0;
            flat[s][nw] = isFlat;
            if (isFlat) return;
            final double[] h = spectrum, c = kernel;
            for (int k = 0; k < bins; k++)
            {
                h[2 * k] = Math.max(logGain[k], MinimumPhase.LEAST_LOG_GAIN);
                h[2 * k + 1] = 0.0;
            }
            MinimumPhase.build(designFft, h, c, size, taper, length);
            final double[] to = partition[s][nw];
            for (int p = 0; p < partitions; p++)
            {
                java.util.Arrays.fill(time, 0.0);
                int from = p * block, n = Math.min(block, length - from);
                System.arraycopy(c, from, time, 0, n);
                transform.forward(time, 0, to, p * stride);
            }
        }

        private boolean allFlat(int s)
        {
            final boolean[] f = flat[s];
            return f[0] && f[1] && f[2];
        }

        // The newest block through one of a slot's kernels.
        private void play(int c, int s, int which, double[] to)
        {
            if (flat[s][which])
            {
                System.arraycopy(in[c], 0, to, 0, block);
                return;
            }
            playSpectra(c, partition[s][which], to);
        }

        // The newest block through a kernel given as the spectra of its partitions.
        private void playSpectra(int c, double[] kernelSpectra, double[] to)
        {
            final double[] acc = sum, x = past[c];
            java.util.Arrays.fill(acc, 0.0);
            final int n = block + 1;
            // The ring of spectra in its two runs, newest first.
            for (int p = 0; p <= head; p++) mulAccum(acc, x, (head - p) * stride, kernelSpectra, p * stride, n);
            for (int p = head + 1; p < partitions; p++) mulAccum(acc, x, (head - p + partitions) * stride, kernelSpectra, p * stride, n);
            transform.inverse(acc, 0, time, 0);
            System.arraycopy(time, block, to, 0, block);
        }

        private static void mulAccum(double[] acc, double[] a, int aOff, double[] b, int bOff, int n)
        {
            for (int k = 0; k < n; k++)
            {
                double ar = a[aOff + 2 * k], ai = a[aOff + 2 * k + 1];
                double br = b[bOff + 2 * k], bi = b[bOff + 2 * k + 1];
                acc[2 * k] += ar * br - ai * bi;
                acc[2 * k + 1] += ar * bi + ai * br;
            }
        }

        private void convolve(int c, int s)
        {
            System.arraycopy(before[c], 0, time, 0, block);
            System.arraycopy(in[c], 0, time, block, block);
            transform.forward(time, 0, past[c], head * stride);
            playThree(c, s);
            System.arraycopy(in[c], 0, before[c], 0, block);
        }

        private void playThree(int c, int s)
        {
            // The middle one, and what the two others differ from it by: flat or
            // equal kernels play exactly as one.
            final double[] y = out[c];
            int nw = newestKernel[s];
            play(c, s, (nw + KERNELS - 1) % KERNELS, y);
            if (fading < steps && !allFlat(s))
            {
                play(c, s, (nw + 1) % KERNELS, oldest);
                play(c, s, nw, newest);
                int base = fading * block;
                final double[] first = oldest, last = newest;
                for (int i = 0; i < block; i++)
                {
                    double middle = y[i];
                    y[i] = middle + older[base + i] * (first[i] - middle) + newer[base + i] * (last[i] - middle);
                }
            }
        }
    }
}
