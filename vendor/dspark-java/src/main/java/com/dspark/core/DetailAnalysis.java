package com.dspark.core;

/**
 * A short-time spectrum on a log-frequency grid whose window follows the
 * detail asked: long where that detail is a few hertz wide, short where it
 * is hundreds.
 * <p>
 * One window cannot read a twelfth of an octave at 100 Hz (it takes a third
 * of a second) and an attack at 10 kHz. The signal is decimated by octaves
 * (up to four) and every level is read through the same window of 1024
 * samples, from 21 ms to 341 ms at 48 kHz. A point of the grid asks for the
 * window that tells apart two lines the width away (four widths' periods),
 * within those bounds, and reads between the two levels around it.
 * <p>
 * What a point reads is the power of what lies at it: the larger of the
 * spectrum there and of the power inside a band a width wide that falls to
 * nothing from a top one step of the grid across (so a line between two
 * points is in both). A sine of full scale reads 1 in both, so a line reads
 * its level at any frequency and at any width; a noise reads the power in the
 * wider of the two.
 * <p>
 * Use: {@link #feed} every sample of each channel, {@link #update} once a
 * hop, then {@link #read}. With {@link #setLinked} the channels are read as
 * the mean of their powers.
 * <p>
 * {@link #prepare} allocates; everything else runs on the owner's thread
 * without allocation.
 */
public final class DetailAnalysis
{
    /** Points of a reading (the grid of the detail filter). */
    public static final int POINTS = 1024;
    /** Channels read at most. */
    public static final int MAX_CHANNELS = 16;
    /** Narrowest width, in octaves. */
    public static final double FINEST = 1.0 / 24.0;
    /** Widest width, in octaves. */
    public static final double WIDEST = 1.0;

    private static final int WINDOW = 1024;      // window of every level at 44.1 and 48 kHz
    private static final int MAX_LEVELS = 5;     // the signal and four octaves down
    private static final double LONGEST = 0.4;   // no window longer, in seconds
    private static final double PERIODS = 4.0;   // window wanted, in periods of one width
    private static final double FOCUS_WIDTH = 1.0 / 6.0;
    private static final int TAPS = 23;          // the decimator of a level

    private static final int[] EVERY = { 1, 1, 2, 4, 4 };
    private static final int[] TURN = { 0, 0, 0, 1, 3 };

    /** The frequency of a point, in hertz: evenly in log frequency from 20 Hz to 20 kHz. */
    public static double frequency(int point)
    {
        int p = Math.max(0, Math.min(point, POINTS - 1));
        return 20.0 * Math.pow(1000.0, (double) p / (POINTS - 1));
    }

    private static final class Level
    {
        double[] ring, history;              // history: the decimator's last TAPS - 1 inputs, then those waiting
        double[] power, sum, sumOfSum;       // per bin, and its first and second integrals
        int write, pending;
        boolean odd;                         // an output falls on the next input
    }

    private double fs = 48000.0, width = 1.0 / 12.0;
    private int channels = 0, n = WINDOW, bins = WINDOW + 1, usedBins = WINDOW + 1, levels = 1, tick = 0;
    private boolean prepared = false, linked = false, exhaustive = false, timeFocus = false;
    private double[] mean = new double[0];
    private FFTRealDouble fft;
    private double[] window = new double[0], frame = new double[0], spectrum = new double[0];
    private double[] decimated = new double[0];
    private final SpectralFocus focus = new SpectralFocus();
    private double[] focusRe = new double[0], focusIm = new double[0];
    private Level[] lag = new Level[0];
    private boolean[] lagged = new boolean[0];
    private double[] taps = new double[TAPS];
    private Level[][] channel = new Level[0][];

    // What each point reads, at its two levels (side 0 and 1): a line and the
    // four corners of its band (under, topUnder, topOver, over).
    private final int[] pointLevel = new int[POINTS];
    private final double[] pointBlend = new double[POINTS];
    private final boolean[] inside = new boolean[2 * POINTS];
    private final int[] lineBin = new int[2 * POINTS];
    private final double[] linePart = new double[2 * POINTS];
    private final int[] cornerBin = new int[8 * POINTS];
    private final double[] cornerPart = new double[8 * POINTS];
    private final double[] cornerBeyond = new double[8 * POINTS];
    private final double[] perUp = new double[2 * POINTS], perDown = new double[2 * POINTS];

    /**
     * Allocates for a layout (an invalid one is ignored).
     *
     * @param sampleRate  the rate
     * @param numChannels the channels
     */
    public void prepare(double sampleRate, int numChannels)
    {
        if (!Double.isFinite(sampleRate) || !(sampleRate > 0.0) || numChannels < 1) return;
        prepared = false;
        fs = sampleRate;
        channels = Math.min(numChannels, MAX_CHANNELS);
        int octaves = (int) Math.max(0, Math.min(Math.round(Math.log(Math.max(fs, 44100.0) / 44100.0) / Math.log(2.0)), 3));
        n = WINDOW << octaves;
        bins = n + 1;   // the transform is twice the window
        // A shorter window takes over beyond these bins of every decimated level, at the finest width.
        usedBins = Math.min(bins, (int) Math.ceil(4.0 * PERIODS / FINEST * Math.pow(2.0, FINEST)) + 8);
        mean = new double[bins];
        levels = 1;
        while (levels < MAX_LEVELS && (double) (n << levels) / fs <= LONGEST) levels++;

        fft = new FFTRealDouble(2 * n);
        window = new double[n];
        WindowFunctions.hann(window, n);
        // No narrower than a bin of the window itself (the padding only reads between them).
        focus.prepare(window, n, 2 * n, FOCUS_WIDTH, 2.0);
        focusRe = new double[bins];
        focusIm = new double[bins];
        lag = new Level[channels];
        lagged = new boolean[channels];
        for (int ch = 0; ch < channels; ch++)
        {
            Level v = new Level();
            v.power = new double[bins + 1];
            v.sum = new double[bins + 1];
            v.sumOfSum = new double[bins + 1];
            lag[ch] = v;
        }
        frame = new double[2 * n];
        spectrum = new double[2 * n + 2];
        // What a level is read for lies under 0.19 of its rate, so the folds of
        // a short half-band fall clear of it.
        taps = FirDesign.lowPass(1.0, 0.25, TAPS, FirDesign.estimateKaiserBeta(100.0));

        channel = new Level[channels][];
        for (int ch = 0; ch < channels; ch++)
        {
            channel[ch] = new Level[levels];
            for (int l = 0; l < levels; l++)
            {
                Level v = new Level();
                v.ring = new double[n];
                v.history = new double[TAPS - 1 + n];
                v.power = new double[bins + 1];
                v.sum = new double[bins + 1];
                v.sumOfSum = new double[bins + 1];
                channel[ch][l] = v;
            }
        }
        decimated = new double[n / 2 + 1];
        prepared = true;
        mapPoints();
        reset();
    }

    /** Clears the signal and the spectra. */
    public void reset()
    {
        if (!prepared) return;
        for (Level[] c : channel)
            for (Level v : c)
            {
                java.util.Arrays.fill(v.ring, 0.0);
                java.util.Arrays.fill(v.history, 0.0);
                java.util.Arrays.fill(v.power, 0.0);
                java.util.Arrays.fill(v.sum, 0.0);
                java.util.Arrays.fill(v.sumOfSum, 0.0);
                v.write = 0;
                v.pending = 0;
                v.odd = false;
            }
        java.util.Arrays.fill(lagged, false);
        tick = 0;
    }

    /**
     * The width of what is told apart, from {@link #WIDEST} to {@link #FINEST}
     * octaves (1/12 by default). No allocation; the next read() follows it.
     */
    public void setWidth(double octaves)
    {
        if (!Double.isFinite(octaves)) return;
        double w = Math.max(FINEST, Math.min(octaves, WIDEST));
        if (w == width) return;
        width = w;
        if (prepared) mapPoints();
    }

    /** The width in octaves. */
    public double getWidth() { return width; }

    /** Reads every level every hop in place of the long ones in turns (for a render). */
    public void setExhaustive(boolean on) { exhaustive = on; }
    public boolean isExhaustive() { return exhaustive; }

    /** One reading for every channel, of their mean power; it holds from the next update(). */
    public void setLinked(boolean on) { linked = on; }
    public boolean isLinked() { return linked; }

    /**
     * Reads the shortest window through one shorter as frequency rises and
     * gives that reading a hop later (off by default); it holds from the next update().
     */
    public void setTimeFocus(boolean on) { timeFocus = on; }
    public boolean isTimeFocus() { return timeFocus; }

    /**
     * Takes the next samples of a channel (any count).
     *
     * @param ch     the channel; one out of range is ignored
     * @param x      the samples
     * @param offset the first sample taken
     * @param count  how many
     */
    public void feed(int ch, double[] x, int offset, int count)
    {
        if (!prepared || ch < 0 || ch >= channels || x == null) return;
        Level[] c = channel[ch];
        Level v = c[0];
        // The signal's own level now; the octaves down are decimated at the
        // next update(), the first to read them (or when a window is waiting).
        for (int at = 0; at < count;)
        {
            int m = Math.min(count - at, n - v.pending);
            int to = TAPS - 1 + v.pending;
            double[] h = v.history;
            for (int i = 0; i < m; i++)
            {
                double s = x[offset + at + i];
                h[to + i] = Double.isFinite(s) ? s : 0.0;
            }
            toRing(v, h, to, m);
            v.pending += m;
            at += m;
            if (levels == 1) v.pending = 0;
            else if (v.pending == n) catchUp(c);
        }
    }

    /**
     * A hop: takes the spectra of the levels due. The two shortest windows
     * are read every hop, the longer ones every two or four, in turns
     * (every one every hop when exhaustive).
     *
     * @param numChannels the channels fed since the last hop
     */
    public void update(int numChannels)
    {
        if (!prepared) return;
        int count = Math.min(numChannels, channels);
        for (Level[] c : channel) catchUp(c);
        for (int l = 0; l < levels; l++)
        {
            if (!exhaustive && tick % EVERY[l] != TURN[l]) continue;
            int b = l == 0 ? bins : usedBins;
            if (linked && count > 1)
            {
                java.util.Arrays.fill(mean, 0, b, 0.0);
                for (int ch = 0; ch < count; ch++) addPower(channel[ch][l], b, 1.0 / count, l == 0 && timeFocus);
                store(channel[0][l], 0, l, b);
                continue;
            }
            for (int ch = 0; ch < count; ch++)
            {
                Level v = channel[ch][l];
                java.util.Arrays.fill(mean, 0, b, 0.0);
                addPower(v, b, 1.0, l == 0 && timeFocus);
                store(v, ch, l, b);
            }
        }
        if (!timeFocus) java.util.Arrays.fill(lagged, false);
        tick = (tick + 1) % 4;
    }

    /**
     * The power at every point (a sine of full scale reads 1).
     *
     * @param ch    the channel; one out of range reads zero
     * @param power {@link #POINTS} values
     */
    public void read(int ch, double[] power)
    {
        read(ch, power, 0, POINTS);
    }

    /**
     * The power at the points from {@code first} to before {@code end} only
     * (the others are left as they are).
     */
    public void read(int ch, double[] power, int first, int end)
    {
        first = Math.max(0, Math.min(first, POINTS));
        end = Math.max(first, Math.min(end, POINTS));
        if (!prepared || ch < 0 || ch >= channels)
        {
            java.util.Arrays.fill(power, first, end, 0.0);
            return;
        }
        Level[] c = channel[linked ? 0 : ch];
        for (int i = first; i < end; i++)
        {
            int level = pointLevel[i];
            double value = readLevel(c[level], 2 * i);
            double blend = pointBlend[i];
            if (blend > 0.0) value += blend * (readLevel(c[level + 1], 2 * i + 1) - value);
            power[i] = value;
        }
    }

    /** The length of the window a point is read through, in seconds. */
    public double getWindowSeconds(int point)
    {
        if (!prepared) return 0.0;
        int p = Math.max(0, Math.min(point, POINTS - 1));
        return n * Math.pow(2.0, pointLevel[p] + pointBlend[p]) / fs;
    }

    // Samples onto a level's ring.
    private void toRing(Level v, double[] x, int from, int m)
    {
        int first = Math.min(m, n - v.write);
        System.arraycopy(x, from, v.ring, v.write, first);
        System.arraycopy(x, from + first, v.ring, 0, m - first);
        v.write = (v.write + m) & (n - 1);
    }

    // Every octave down of a channel up to date: what waits at each level is decimated into the next.
    private void catchUp(Level[] c)
    {
        for (int l = 0; l + 1 < levels; l++)
        {
            Level v = c[l];
            if (v.pending == 0) continue;
            Level next = c[l + 1];
            int produced = decimate(v);
            toRing(next, decimated, 0, produced);
            if (l + 2 < levels)
            {
                System.arraycopy(decimated, 0, next.history, TAPS - 1 + next.pending, produced);
                next.pending += produced;
            }
        }
    }

    // The half-band decimator over what waits at a level, into `decimated`:
    // an output on every other input, of the TAPS inputs up to it.
    private int decimate(Level v)
    {
        int m = v.pending;
        double[] h = v.history;
        int start = v.odd ? 0 : 1;
        int produced = m > start ? (m - start + 1) / 2 : 0;
        final double[] t = taps;
        for (int j = 0; j < produced; j++)
        {
            // y[j] = sum_k taps[k] h[start + 2j + k], the even taps and the odd taps apart.
            int base = start + 2 * j;
            double even = 0.0, odd = 0.0;
            for (int k = 0; k < TAPS; k += 2) even += t[k] * h[base + k];
            for (int k = 1; k < TAPS; k += 2) odd += t[k] * h[base + k];
            decimated[j] = even + odd;
        }
        if ((m & 1) != 0) v.odd = !v.odd;
        System.arraycopy(h, m, h, 0, TAPS - 1);
        v.pending = 0;
        return produced;
    }

    private void mapPoints()
    {
        double binHz = fs / (2.0 * n), shortest = n / fs;
        double down = Math.pow(2.0, -0.5 * width), up = Math.pow(2.0, 0.5 * width);
        double halfStep = Math.pow(1000.0, 0.5 / (POINTS - 1));
        double ln2 = Math.log(2.0);
        for (int i = 0; i < POINTS; i++)
        {
            double hz = frequency(i);
            double wanted = PERIODS / (width * hz);
            double level = Math.max(0.0, Math.min(Math.log(wanted / shortest) / ln2, levels - 1.0));
            int base = Math.min((int) level, Math.max(0, levels - 2));
            pointLevel[i] = base;
            pointBlend[i] = levels > 1 ? level - base : 0.0;
            for (int side = 0; side < (levels > 1 ? 2 : 1); side++)
            {
                int r = 2 * i + side;
                int at = base + side, b = at == 0 ? bins : usedBins;
                double bin = hz / binHz * Math.pow(2.0, at);
                // At a weight of nothing a level is not read (nor kept that high).
                inside[r] = bin < (double) (b - 1);
                if (!inside[r]) continue;
                lineBin[r] = (int) bin;
                linePart[r] = bin - lineBin[r];
                // The bands hold from half a bin under each bin.
                setCorner(4 * r, bin * down, b);
                setCorner(4 * r + 1, bin / halfStep, b);
                setCorner(4 * r + 2, bin * halfStep, b);
                setCorner(4 * r + 3, bin * up, b);
                // A sine spreads 1.5 bins of the window, three of the transform.
                perUp[r] = 1.0 / (3.0 * bin * (1.0 / halfStep - down));
                perDown[r] = 1.0 / (3.0 * bin * (up - halfStep));
            }
        }
    }

    private void setCorner(int c, double x, int b)
    {
        double from = Math.max(x + 0.5, 0.0), end = b;
        if (from >= end)
        {
            cornerBin[c] = b;
            cornerPart[c] = 0.0;
            cornerBeyond[c] = from - end;
            return;
        }
        cornerBin[c] = (int) from;
        cornerPart[c] = from - cornerBin[c];
        cornerBeyond[c] = 0.0;
    }

    // Adds a share of the power of a level's window into mean.
    private void addPower(Level v, int b, double share, boolean focused)
    {
        final double[] r = v.ring, w = window, f = frame;
        int older = n - v.write;
        for (int i = 0; i < older; i++) f[i] = r[v.write + i] * w[i];
        for (int i = 0; i < v.write; i++) f[older + i] = r[i] * w[older + i];
        java.util.Arrays.fill(f, n, 2 * n, 0.0);
        fft.forward(f, spectrum);
        double scale = share * 16.0 / ((double) n * (double) n);
        if (focused)
        {
            // Each bin through a stretch of the window of its own, scaled to read what the whole window would.
            focus.smooth(spectrum, focusRe, focusIm);
            for (int k = 0; k < b; k++)
                mean[k] += scale * focus.getGain(k) * (focusRe[k] * focusRe[k] + focusIm[k] * focusIm[k]);
            return;
        }
        final double[] s = spectrum;
        for (int k = 0; k < b; k++)
        {
            double re = s[2 * k], im = s[2 * k + 1];
            mean[k] += scale * (re * re + im * im);
        }
    }

    // mean into a level, with its first and second integrals.
    private void integrate(Level v, int b)
    {
        double sum = 0.0, sumOfSum = 0.0;
        for (int k = 0; k < b; k++)
        {
            double power = mean[k];
            v.power[k] = power;
            v.sum[k] = sum;
            v.sumOfSum[k] = sumOfSum;
            sumOfSum += sum + 0.5 * power;
            sum += power;
        }
        v.sum[b] = sum;
        v.sumOfSum[b] = sumOfSum;
    }

    // mean into a level; the shortest read through the focus shows the reading of the hop before.
    private void store(Level v, int ch, int l, int b)
    {
        if (l != 0 || !timeFocus)
        {
            integrate(v, b);
            return;
        }
        Level held = lag[ch];
        if (!lagged[ch])
        {
            // The first reading through the focus: no hop before it to show.
            integrate(held, b);
            System.arraycopy(held.power, 0, v.power, 0, held.power.length);
            System.arraycopy(held.sum, 0, v.sum, 0, held.sum.length);
            System.arraycopy(held.sumOfSum, 0, v.sumOfSum, 0, held.sumOfSum.length);
            lagged[ch] = true;
            return;
        }
        double[] swap = v.power; v.power = held.power; held.power = swap;
        swap = v.sum; v.sum = held.sum; held.sum = swap;
        swap = v.sumOfSum; v.sumOfSum = held.sumOfSum; held.sumOfSum = swap;
        integrate(held, b);
    }

    // The integral of the integral of the power up to a corner, a bin k holding from k - 1/2 to k + 1/2.
    private double integral(Level v, int c)
    {
        int u = cornerBin[c];
        double part = cornerPart[c];
        // The bin past the last has no power: straight from there.
        return v.sumOfSum[u] + (part + cornerBeyond[c]) * v.sum[u] + 0.5 * part * part * v.power[u];
    }

    private double readLevel(Level v, int r)
    {
        if (!inside[r]) return 0.0;
        int u = lineBin[r];
        double line = v.power[u] + linePart[r] * (v.power[u + 1] - v.power[u]);
        // The band by parts.
        int c = 4 * r;
        double band = (integral(v, c + 3) - integral(v, c + 2)) * perDown[r]
                    - (integral(v, c + 1) - integral(v, c)) * perUp[r];
        return line < band ? band : line;
    }
}
