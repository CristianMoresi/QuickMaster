package com.dspark.core;

import java.util.Arrays;

/**
 * A cascade of second-order sections run in one pass, with coefficient ramps
 * and click-free bypass.
 * <p>
 * Up to {@link #GROUP} sections run together inside one loop over the
 * samples: the recursions of different sections do not depend on each other
 * across samples, so a pass over four sections costs little more than a pass
 * over one.
 * <p>
 * The sections are in direct form I with shared node history: the memory of
 * the cascade is the last two samples at each node (the input, and the output
 * of every section), in double precision, so the output history of a section
 * is the input history of the next. That choice has three consequences:
 * <ul>
 * <li>The state does not depend on the coefficients. It is signal, so the
 *     coefficients can move every sample ({@link #rampCoeffs} interpolates all
 *     five linearly) and the filter carries on from what it has just
 *     played.</li>
 * <li>A section whose numerator equals its denominator is transparent with
 *     its poles still running. {@link BiquadDesign#bypassed} is that set, and
 *     ramping a section to it ({@link #rampToBypass}) takes it out of the path
 *     without a crossfade or a click: what the section was adding dies away
 *     at the rate of its own poles.</li>
 * <li>A disabled section costs nothing and comes back clean: switched on
 *     again it starts from the history of its input, as if it had been
 *     transparent all along.</li>
 * </ul>
 * The samples stay in double from the input of the cascade to its output.
 */
public final class BiquadCascade
{
    /** Sections run together in one pass over the samples. */
    public static final int GROUP = 4;

    private static final int CHUNK = 64;

    /** Five coefficients that move (a ramp walks them in place). */
    private static final class Coefs
    {
        double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;

        void set(double nb0, double nb1, double nb2, double na1, double na2)
        {
            b0 = nb0;
            b1 = nb1;
            b2 = nb2;
            a1 = na1;
            a2 = na2;
        }

        void set(Coefs o)
        {
            set(o.b0, o.b1, o.b2, o.a1, o.a2);
        }
    }

    private final int maxSections;
    private final int maxChannels;

    private final Coefs[] current;
    private final Coefs[] target;
    private final Coefs[] step;
    private final Coefs[] next;            // coefficients a ramping loop arrived at
    private final boolean[] moved;
    private final int[] rampLeft;
    private final boolean[] enabled;
    private final int[] active;            // sections in the path, in order
    private final int[] input;             // node each section in the path reads
    private int numSections = 0;
    private int numActive = 0;
    private boolean ramping = false;

    private final double[][] nodeH1;       // [channel][node]: the sample before the current one
    private final double[][] nodeH2;       // [channel][node]: the one before that

    // Scratch of the block loop.
    private final double[] io = new double[CHUNK];
    private final double[] startU1;
    private final double[] startU2;
    private final int[] groupSection = new int[GROUP];
    private final double[] gb0 = new double[GROUP], gb1 = new double[GROUP], gb2 = new double[GROUP];
    private final double[] ga1 = new double[GROUP], ga2 = new double[GROUP];
    private final double[] gy1 = new double[GROUP], gy2 = new double[GROUP];
    private final double[] gs0 = new double[GROUP], gs1 = new double[GROUP], gs2 = new double[GROUP];
    private final double[] gt1 = new double[GROUP], gt2 = new double[GROUP];

    /** A cascade of up to 8 sections for up to 8 channels. */
    public BiquadCascade()
    {
        this(8, 8);
    }

    /**
     * A cascade of up to {@code maxSections} sections for up to
     * {@code maxChannels} channels.
     *
     * @param maxSections largest number of sections (1 to 64)
     * @param maxChannels largest number of channels (at least 1)
     */
    public BiquadCascade(int maxSections, int maxChannels)
    {
        if (maxSections < 1 || maxSections > 64)
            throw new IllegalArgumentException("maxSections must be in [1, 64]; got " + maxSections);
        if (maxChannels < 1)
            throw new IllegalArgumentException("maxChannels must be >= 1; got " + maxChannels);
        this.maxSections = maxSections;
        this.maxChannels = maxChannels;
        current = newCoefs(maxSections);
        target = newCoefs(maxSections);
        step = newCoefs(maxSections);
        next = newCoefs(maxSections);
        moved = new boolean[maxSections];
        rampLeft = new int[maxSections];
        enabled = new boolean[maxSections];
        active = new int[maxSections];
        input = new int[maxSections];
        nodeH1 = new double[maxChannels][maxSections + 1];
        nodeH2 = new double[maxChannels][maxSections + 1];
        final int maxGroups = Math.max(1, (maxSections + GROUP - 1) / GROUP);
        startU1 = new double[maxGroups];
        startU2 = new double[maxGroups];
        Arrays.fill(enabled, true);
        rebuildRoute();
    }

    private static Coefs[] newCoefs(int n)
    {
        final Coefs[] a = new Coefs[n];
        for (int i = 0; i < n; ++i) a[i] = new Coefs();
        return a;
    }

    /** Largest number of sections. */
    public int getMaxSections()
    {
        return maxSections;
    }

    /** Largest number of channels. */
    public int getMaxChannels()
    {
        return maxChannels;
    }

    /**
     * Sets how many sections the cascade has. Sections from the new count on
     * leave the path as they are (their coefficients are kept); a section
     * that comes into the path starts from the history of its input.
     *
     * @param count number of sections, clamped to [0, maxSections]
     */
    public void setNumSections(int count)
    {
        final int clamped = Math.max(0, Math.min(count, maxSections));
        if (clamped == numSections) return;
        final int before = numSections;
        numSections = clamped;
        rebuildRoute();
        for (int s = Math.max(before, 0); s < clamped; ++s)
            if (enabled[s]) seedFromInput(s);
    }

    /** Number of sections in the cascade. */
    public int getNumSections()
    {
        return numSections;
    }

    /**
     * Makes a coefficient set a section's own from the next sample; ends any
     * ramp of the section. The state is signal, so the section carries on
     * from what it has played.
     *
     * @param section section index in [0, maxSections); others are ignored
     */
    public void setCoeffs(int section, BiquadCoeffs c)
    {
        setCoeffs(section, c.b0, c.b1, c.b2, c.a1, c.a2);
    }

    private void setCoeffs(int section, double b0, double b1, double b2, double a1, double a2)
    {
        if (section < 0 || section >= maxSections) return;
        current[section].set(b0, b1, b2, a1, a2);
        target[section].set(b0, b1, b2, a1, a2);
        rampLeft[section] = 0;
        updateRamping();
    }

    /**
     * Moves a section's five coefficients linearly to a set. The ramp starts
     * from the coefficients in use (wherever a ramp in flight has brought
     * them) and lands exactly on the target after {@code samples} samples.
     *
     * @param section section index in [0, maxSections); others are ignored
     * @param c       coefficients to arrive at
     * @param samples length of the ramp; 0 or less sets the target at once
     */
    public void rampCoeffs(int section, BiquadCoeffs c, int samples)
    {
        rampCoeffs(section, c.b0, c.b1, c.b2, c.a1, c.a2, samples);
    }

    private void rampCoeffs(int section, double b0, double b1, double b2, double a1, double a2, int samples)
    {
        if (section < 0 || section >= maxSections) return;
        if (samples <= 0)
        {
            setCoeffs(section, b0, b1, b2, a1, a2);
            return;
        }
        target[section].set(b0, b1, b2, a1, a2);
        rampLeft[section] = samples;
        final double inverse = 1.0 / (double) samples;
        final Coefs cur = current[section];
        step[section].set((b0 - cur.b0) * inverse, (b1 - cur.b1) * inverse, (b2 - cur.b2) * inverse,
                          (a1 - cur.a1) * inverse, (a2 - cur.a2) * inverse);
        ramping = true;
    }

    /**
     * Takes a section out of the path without a click: ramps its numerator
     * to its denominator. The poles and the memory keep running and what the
     * section adds to its input dies away; once the ramp is over the section
     * passes its input (to rounding) and can be disabled.
     *
     * @param section section index
     * @param samples length of the ramp in samples
     */
    public void rampToBypass(int section, int samples)
    {
        if (section < 0 || section >= maxSections) return;
        final Coefs t = target[section];
        rampCoeffs(section, 1.0, t.a1, t.a2, t.a1, t.a2, samples);
    }

    /** The coefficients a section is using now (mid ramp, where the ramp is). */
    public BiquadCoeffs getCoeffs(int section)
    {
        final Coefs c = current[Math.max(0, Math.min(section, maxSections - 1))];
        return new BiquadCoeffs(c.b0, c.b1, c.b2, c.a1, c.a2);
    }

    /** The coefficients a section is heading to (its own when it is not ramping). */
    public BiquadCoeffs getTargetCoeffs(int section)
    {
        final Coefs c = target[Math.max(0, Math.min(section, maxSections - 1))];
        return new BiquadCoeffs(c.b0, c.b1, c.b2, c.a1, c.a2);
    }

    /** Whether a section's coefficients are moving. */
    public boolean isRamping(int section)
    {
        return section >= 0 && section < maxSections && rampLeft[section] > 0;
    }

    /** Whether any section's coefficients are moving. */
    public boolean isRamping()
    {
        return ramping;
    }

    /**
     * Switches a section in or out of the path: a switch, not a fade. Disable
     * a section once it is transparent and it leaves without a trace; a
     * disabled section is not computed. Enabled again it starts from the
     * history of its input.
     *
     * @param section section index in [0, maxSections); others are ignored
     * @param on      true to compute the section
     */
    public void setSectionEnabled(int section, boolean on)
    {
        if (section < 0 || section >= maxSections) return;
        if (enabled[section] == on) return;
        enabled[section] = on;
        rebuildRoute();
        if (on && section < numSections) seedFromInput(section);
    }

    /** Whether a section is computed. */
    public boolean isSectionEnabled(int section)
    {
        return section >= 0 && section < maxSections && enabled[section];
    }

    /** Clears the memory of every node. Coefficients and ramps are kept. */
    public void reset()
    {
        for (int ch = 0; ch < maxChannels; ++ch)
        {
            Arrays.fill(nodeH1[ch], 0.0);
            Arrays.fill(nodeH2[ch], 0.0);
        }
    }

    /**
     * Makes this cascade a copy of another of the same size: its sections in
     * the path, coefficients, ramps and memory. Allocates nothing.
     *
     * @param other a cascade with the same maximum sections and channels
     * @throws IllegalArgumentException if the sizes differ
     */
    public void copyFrom(BiquadCascade other)
    {
        if (other == this) return;
        if (other.maxSections != maxSections || other.maxChannels != maxChannels)
            throw new IllegalArgumentException("copyFrom needs a cascade of the same size");
        for (int s = 0; s < maxSections; ++s)
        {
            current[s].set(other.current[s]);
            target[s].set(other.target[s]);
            step[s].set(other.step[s]);
            next[s].set(other.next[s]);
        }
        System.arraycopy(other.moved, 0, moved, 0, maxSections);
        System.arraycopy(other.rampLeft, 0, rampLeft, 0, maxSections);
        System.arraycopy(other.enabled, 0, enabled, 0, maxSections);
        System.arraycopy(other.active, 0, active, 0, maxSections);
        System.arraycopy(other.input, 0, input, 0, maxSections);
        numSections = other.numSections;
        numActive = other.numActive;
        ramping = other.ramping;
        for (int ch = 0; ch < maxChannels; ++ch)
        {
            System.arraycopy(other.nodeH1[ch], 0, nodeH1[ch], 0, maxSections + 1);
            System.arraycopy(other.nodeH2[ch], 0, nodeH2[ch], 0, maxSections + 1);
        }
    }

    /** Runs every channel of a planar buffer through the cascade in place, from index 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, channels.length, 0, numSamples);
    }

    /** Runs every channel of a planar buffer through the cascade in place, from {@code offset}. */
    public void process(double[][] channels, int offset, int numSamples)
    {
        process(channels, channels.length, offset, numSamples);
    }

    /**
     * Runs a block through the cascade, in place. Channels beyond the
     * cascade's maximum are left untouched. A memory that a non-finite sample
     * has reached is cleared at the end of the block, so the channel recovers
     * on the next one. Ramps move on by the block's samples.
     *
     * @param channels    the channels
     * @param numChannels channels to process (the first ones of {@code channels})
     * @param offset      index of the first sample in each channel
     * @param numSamples  samples to process
     */
    public void process(double[][] channels, int numChannels, int offset, int numSamples)
    {
        final int nCh = Math.min(numChannels, maxChannels);
        if (nCh <= 0 || numSamples <= 0) return;
        if (numActive == 0)
        {
            // Nothing in the path: the input node still has to remember what went by.
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] data = channels[ch];
                if (numSamples >= 2) nodeH2[ch][0] = data[offset + numSamples - 2];
                else nodeH2[ch][0] = nodeH1[ch][0];
                nodeH1[ch][0] = data[offset + numSamples - 1];
            }
            advanceRamps(numSamples);
            return;
        }

        int pos = 0;
        while (pos < numSamples)
        {
            int length = Math.min(CHUNK, numSamples - pos);
            if (ramping)
                for (int k = 0; k < numActive; ++k)
                {
                    final int left = rampLeft[active[k]];
                    if (left > 0) length = Math.min(length, left);
                }

            for (int ch = 0; ch < nCh; ++ch)
                runChunk(channels[ch], offset + pos, length, ch);

            advanceRamps(length);
            pos += length;
        }

        for (int ch = 0; ch < nCh; ++ch)
            recover(ch);
    }

    /**
     * One sample of one channel through the cascade, in double, with the
     * coefficients as they are: ramps advance in {@link #process}, or by the
     * caller's {@link #advanceRamps} once per frame.
     *
     * @param input   input sample
     * @param channel channel index in [0, maxChannels)
     * @return output of the last enabled section
     */
    public double processSample(double input, int channel)
    {
        final double[] h1 = nodeH1[channel], h2 = nodeH2[channel];
        double u = input;
        double u1 = h1[0], u2 = h2[0];
        h2[0] = h1[0];
        h1[0] = input;
        for (int k = 0; k < numActive; ++k)
        {
            final int s = active[k];
            final Coefs c = current[s];
            final int out = s + 1;
            final double p = (c.b1 * u1 + c.b2 * u2) - c.a2 * h2[out];
            final double y = (p + c.b0 * u) - c.a1 * h1[out];
            u1 = h1[out];
            u2 = h2[out];
            h2[out] = h1[out];
            h1[out] = y;
            u = y;
        }
        return u;
    }

    /**
     * As {@link #processSample(double, int)}, leaving some sections out for
     * this call. A section whose bit is set in {@code skip} (bit s for section
     * s) is not computed for this sample: what reaches it goes on to the next
     * section and its own memory for the channel stays as it was.
     *
     * @param input   input sample
     * @param channel channel index in [0, maxChannels)
     * @param skip    sections to leave out, one bit each
     * @return output of the last enabled section that was not left out
     */
    public double processSample(double input, int channel, long skip)
    {
        final double[] h1 = nodeH1[channel], h2 = nodeH2[channel];
        double u = input;
        double u1 = h1[0], u2 = h2[0];
        h2[0] = h1[0];
        h1[0] = input;
        for (int k = 0; k < numActive; ++k)
        {
            final int s = active[k];
            if ((skip & (1L << s)) != 0) continue;
            final Coefs c = current[s];
            final int out = s + 1;
            final double p = (c.b1 * u1 + c.b2 * u2) - c.a2 * h2[out];
            final double y = (p + c.b0 * u) - c.a1 * h1[out];
            u1 = h1[out];
            u2 = h2[out];
            h2[out] = h1[out];
            h1[out] = y;
            u = y;
        }
        return u;
    }

    /**
     * Rewrites the memory of a pair of channels as its middle and its sides,
     * or back. Every node of the first channel becomes the half sum of the two
     * and every node of the second their half difference, and the sections
     * carry on as if they had been fed that way all along. The way back is the
     * sum and the difference.
     *
     * @param first  channel that holds the left, or the middle
     * @param second channel that holds the right, or the sides
     * @param back   false: left and right become middle and sides; true: the other way
     */
    public void turnPairMemory(int first, int second, boolean back)
    {
        if (first < 0 || second < 0 || first >= maxChannels || second >= maxChannels || first == second) return;
        final double[] a1 = nodeH1[first], a2 = nodeH2[first];
        final double[] b1 = nodeH1[second], b2 = nodeH2[second];
        final double scale = back ? 1.0 : 0.5;
        for (int n = 0; n < a1.length; ++n)
        {
            final double sum1 = a1[n] + b1[n], difference1 = a1[n] - b1[n];
            final double sum2 = a2[n] + b2[n], difference2 = a2[n] - b2[n];
            a1[n] = scale * sum1;
            b1[n] = scale * difference1;
            a2[n] = scale * sum2;
            b2[n] = scale * difference2;
        }
    }

    /**
     * Moves every ramp on by a number of samples (for
     * {@link #processSample} callers).
     *
     * @param samples samples gone by; a ramp with fewer left lands on its target
     */
    public void advanceRamps(int samples)
    {
        if (!ramping || samples <= 0) return;
        for (int s = 0; s < maxSections; ++s)
        {
            if (rampLeft[s] <= 0) continue;
            if (samples >= rampLeft[s])
            {
                current[s].set(target[s]);
                rampLeft[s] = 0;
                continue;
            }
            rampLeft[s] -= samples;
            if (moved[s])
            {
                // The loop that played the section moved it, sample by sample.
                current[s].set(next[s]);
            }
            else
            {
                // Out of the path, or played one sample at a time: it moves here.
                final double count = (double) samples;
                final Coefs c = current[s], d = step[s];
                c.b0 += d.b0 * count;
                c.b1 += d.b1 * count;
                c.b2 += d.b2 * count;
                c.a1 += d.a1 * count;
                c.a2 += d.a2 * count;
            }
        }
        Arrays.fill(moved, false);
        updateRamping();
    }

    /** A chunk of one channel through every group of sections. */
    private void runChunk(double[] data, int offset, int length, int ch)
    {
        for (int i = 0; i < length; ++i) io[i] = data[offset + i];

        final double[] h1 = nodeH1[ch], h2 = nodeH2[ch];

        // Each group reads the history its input node had BEFORE this chunk;
        // the group ahead of it leaves that node at the end of the chunk.
        for (int first = 0, group = 0; first < numActive; first += GROUP, ++group)
        {
            final int node = input[active[first]];
            startU1[group] = h1[node];
            startU2[group] = h2[node];
        }

        for (int first = 0, group = 0; first < numActive; first += GROUP, ++group)
        {
            final int count = Math.min(GROUP, numActive - first);
            boolean moving = false;
            for (int k = 0; k < count; ++k)
                moving = moving || rampLeft[active[first + k]] > 0;
            runGroup(count, length, first, moving, startU1[group], startU2[group], h1, h2);
        }

        for (int i = 0; i < length; ++i) data[offset + i] = io[i];
    }

    /**
     * M sections of the path over a chunk, all inside one loop. Each section
     * computes the part of its output that only needs past samples first,
     * then adds its input and takes away a1 times its last output.
     */
    private void runGroup(int m, int length, int first, boolean moving, double u1, double u2,
                          double[] h1, double[] h2)
    {
        for (int k = 0; k < m; ++k)
        {
            final int s = active[first + k];
            groupSection[k] = s;
            final Coefs c = current[s];
            gb0[k] = c.b0;
            gb1[k] = c.b1;
            gb2[k] = c.b2;
            ga1[k] = c.a1;
            ga2[k] = c.a2;
            gy1[k] = h1[s + 1];
            gy2[k] = h2[s + 1];
        }

        if (!moving && m == 1)
        {
            // The same arithmetic as the general loop below, with the
            // section's coefficients and memory held in locals.
            final double b0 = gb0[0], b1 = gb1[0], b2 = gb2[0], a1 = ga1[0], a2 = ga2[0];
            double y1 = gy1[0], y2 = gy2[0];
            for (int i = 0; i < length; ++i)
            {
                final double u = io[i];
                final double p1 = u1, p2 = u2;
                u2 = u1;
                u1 = u;
                final double past = (b1 * p1 + b2 * p2) - a2 * y2;
                final double y = (past + b0 * u) - a1 * y1;
                y2 = y1;
                y1 = y;
                io[i] = y;
            }
            gy1[0] = y1;
            gy2[0] = y2;
        }
        else if (!moving && m == 2)
        {
            final double b00 = gb0[0], b10 = gb1[0], b20 = gb2[0], a10 = ga1[0], a20 = ga2[0];
            final double b01 = gb0[1], b11 = gb1[1], b21 = gb2[1], a11 = ga1[1], a21 = ga2[1];
            double y10 = gy1[0], y20 = gy2[0], y11 = gy1[1], y21 = gy2[1];
            for (int i = 0; i < length; ++i)
            {
                final double u = io[i];
                final double p1 = u1, p2 = u2;
                u2 = u1;
                u1 = u;
                final double past0 = (b10 * p1 + b20 * p2) - a20 * y20;
                final double y0 = (past0 + b00 * u) - a10 * y10;
                final double past1 = (b11 * y10 + b21 * y20) - a21 * y21;
                final double y = (past1 + b01 * y0) - a11 * y11;
                y20 = y10;
                y10 = y0;
                y21 = y11;
                y11 = y;
                io[i] = y;
            }
            gy1[0] = y10;
            gy2[0] = y20;
            gy1[1] = y11;
            gy2[1] = y21;
        }
        else if (!moving && m == 4)
        {
            final double b00 = gb0[0], b10 = gb1[0], b20 = gb2[0], a10 = ga1[0], a20 = ga2[0];
            final double b01 = gb0[1], b11 = gb1[1], b21 = gb2[1], a11 = ga1[1], a21 = ga2[1];
            final double b02 = gb0[2], b12 = gb1[2], b22 = gb2[2], a12 = ga1[2], a22 = ga2[2];
            final double b03 = gb0[3], b13 = gb1[3], b23 = gb2[3], a13 = ga1[3], a23 = ga2[3];
            double y10 = gy1[0], y20 = gy2[0], y11 = gy1[1], y21 = gy2[1];
            double y12 = gy1[2], y22 = gy2[2], y13 = gy1[3], y23 = gy2[3];
            for (int i = 0; i < length; ++i)
            {
                final double u = io[i];
                final double p1 = u1, p2 = u2;
                u2 = u1;
                u1 = u;
                final double past0 = (b10 * p1 + b20 * p2) - a20 * y20;
                final double y0 = (past0 + b00 * u) - a10 * y10;
                final double past1 = (b11 * y10 + b21 * y20) - a21 * y21;
                final double y1 = (past1 + b01 * y0) - a11 * y11;
                final double past2 = (b12 * y11 + b22 * y21) - a22 * y22;
                final double y2 = (past2 + b02 * y1) - a12 * y12;
                final double past3 = (b13 * y12 + b23 * y22) - a23 * y23;
                final double y = (past3 + b03 * y2) - a13 * y13;
                y20 = y10;
                y10 = y0;
                y21 = y11;
                y11 = y1;
                y22 = y12;
                y12 = y2;
                y23 = y13;
                y13 = y;
                io[i] = y;
            }
            gy1[0] = y10;
            gy2[0] = y20;
            gy1[1] = y11;
            gy2[1] = y21;
            gy1[2] = y12;
            gy2[2] = y22;
            gy1[3] = y13;
            gy2[3] = y23;
        }
        else if (!moving)
        {
            for (int i = 0; i < length; ++i)
            {
                double u = io[i];
                double p1 = u1, p2 = u2;
                u2 = u1;
                u1 = u;
                for (int k = 0; k < m; ++k)
                {
                    final double past = (gb1[k] * p1 + gb2[k] * p2) - ga2[k] * gy2[k];
                    final double y = (past + gb0[k] * u) - ga1[k] * gy1[k];
                    p1 = gy1[k];
                    p2 = gy2[k];
                    gy2[k] = gy1[k];
                    gy1[k] = y;
                    u = y;
                }
                io[i] = u;
            }
        }
        else
        {
            for (int k = 0; k < m; ++k)
            {
                final int s = groupSection[k];
                final boolean ramps = rampLeft[s] > 0;
                final Coefs d = step[s];
                gs0[k] = ramps ? d.b0 : 0.0;
                gs1[k] = ramps ? d.b1 : 0.0;
                gs2[k] = ramps ? d.b2 : 0.0;
                gt1[k] = ramps ? d.a1 : 0.0;
                gt2[k] = ramps ? d.a2 : 0.0;
            }
            for (int i = 0; i < length; ++i)
            {
                double u = io[i];
                double p1 = u1, p2 = u2;
                u2 = u1;
                u1 = u;
                for (int k = 0; k < m; ++k)
                {
                    gb0[k] += gs0[k];
                    gb1[k] += gs1[k];
                    gb2[k] += gs2[k];
                    ga1[k] += gt1[k];
                    ga2[k] += gt2[k];
                    final double past = (gb1[k] * p1 + gb2[k] * p2) - ga2[k] * gy2[k];
                    final double y = (past + gb0[k] * u) - ga1[k] * gy1[k];
                    p1 = gy1[k];
                    p2 = gy2[k];
                    gy2[k] = gy1[k];
                    gy1[k] = y;
                    u = y;
                }
                io[i] = u;
            }
            // Where the ramp has brought the coefficients: every channel walks
            // the same steps, so the last one to run leaves the same values.
            for (int k = 0; k < m; ++k)
            {
                final int s = groupSection[k];
                if (rampLeft[s] <= 0) continue;
                next[s].set(gb0[k], gb1[k], gb2[k], ga1[k], ga2[k]);
                moved[s] = true;
            }
        }

        // The input node of the cascade is this loop's to keep; the input of
        // a later group is the output node the group ahead has just written.
        if (first == 0)
        {
            h1[0] = u1;
            h2[0] = u2;
        }
        for (int k = 0; k < m; ++k)
        {
            h1[groupSection[k] + 1] = gy1[k];
            h2[groupSection[k] + 1] = gy2[k];
        }
    }

    /** The sections in the path, in order, and the node each one reads. */
    private void rebuildRoute()
    {
        numActive = 0;
        int previous = 0;
        for (int s = 0; s < numSections; ++s)
        {
            if (!enabled[s]) continue;
            active[numActive++] = s;
            input[s] = previous;
            previous = s + 1;
        }
    }

    /** Gives a section that enters the path the history of its input. */
    private void seedFromInput(int section)
    {
        final int from = input[section];
        for (int ch = 0; ch < maxChannels; ++ch)
        {
            nodeH1[ch][section + 1] = nodeH1[ch][from];
            nodeH2[ch][section + 1] = nodeH2[ch][from];
        }
    }

    private void updateRamping()
    {
        ramping = false;
        for (int s = 0; s < maxSections; ++s) ramping = ramping || rampLeft[s] > 0;
    }

    /** Clears a channel whose memory a non-finite sample has reached. */
    private void recover(int channel)
    {
        final double[] h1 = nodeH1[channel], h2 = nodeH2[channel];
        double sum = h1[0] + h2[0];
        for (int k = 0; k < numActive; ++k)
        {
            final int node = active[k] + 1;
            sum += h1[node] + h2[node];
        }
        if (!Double.isFinite(sum))
        {
            Arrays.fill(h1, 0.0);
            Arrays.fill(h2, 0.0);
        }
    }
}
