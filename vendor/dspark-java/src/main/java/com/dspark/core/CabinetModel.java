package com.dspark.core;

import java.util.Arrays;

/**
 * A loudspeaker cabinet without convolution and without latency: the colour
 * of the speaker as filters, the box as a few reflections and two diffusers.
 * <p>
 * What a guitar or bass amplifier sounds like through its speaker is mostly
 * three things: the low resonance of the cone in its box (a bump, and
 * nothing under it), the cone breaking up and then rolling off steeply in
 * the upper midrange, and the short reflections inside and around the box
 * that a microphone picks up with the direct sound. A measured impulse
 * response captures one cabinet and one microphone; this is the cheap and
 * adjustable alternative:
 * <ul>
 * <li><b>Colour</b>: a 12 dB per octave low cut with a resonance at the
 *   cone's frequency, a shallow dip in the low midrange, the breakup peak,
 *   and a 24 dB per octave roll-off, each designed from its analog prototype
 *   with {@link FilterDesign} and run in one {@link BiquadCascade}.</li>
 * <li><b>Box</b>: six sparse reflections between 0.3 and 3.4 ms with
 *   alternating signs, through two allpass diffusers, added to the direct
 *   sound by the distance of the microphone; the level is kept as the
 *   reflections come in.</li>
 * </ul>
 * There is no lookahead and no block: the first output sample is already the
 * direct sound.
 * <p>
 * Settings move while audio runs: a changed size or brightness is designed at
 * the next {@link #process} call and glides over 20 ms; the distance glides
 * across that block. {@link #prepare} allocates; {@link #process} designs the
 * filters only after a setting changed.
 */
public final class CabinetModel
{
    /** Reflections of the box. */
    public static final int TAPS = 6;
    /** Sections of the colour filters. */
    public static final int SECTIONS = 5;
    /** Channels of a model built with the no-argument constructor. */
    public static final int DEFAULT_MAX_CHANNELS = 16;

    // Reflections of the box: times (ms) and gains, alternating in sign.
    private static final double[] TAP_MS = { 0.29, 0.71, 1.13, 1.87, 2.63, 3.41 };
    private static final double[] TAP_GAIN = { 0.45, -0.32, 0.25, -0.18, 0.12, -0.08 };
    private static final double TAP_ENERGY = 0.45 * 0.45 + 0.32 * 0.32 + 0.25 * 0.25 + 0.18 * 0.18 + 0.12 * 0.12 + 0.08 * 0.08;
    private static final double[] DIFFUSER_MS = { 1.31, 2.27 };
    private static final double[] DIFFUSER_GAIN = { 0.5, 0.4 };

    private final int maxChannels;

    private double sizeParam = 0.5;
    private double brightness = 0.5;
    private double distance = 0.3;
    private boolean dirty = true;

    private BiquadCascade cascade = null;
    private final FilterDesign.Band fit = new FilterDesign.Band();
    private final int[] tapDelay = new int[TAPS];
    private final int[] diffuserDelay = new int[2];
    /** Per channel: the direct sound, and each diffuser's line. */
    private double[] lines = new double[0];
    private final BiquadCoeffs[] sections = new BiquadCoeffs[SECTIONS];
    private double fs = 48000.0;
    private double distanceNow = 0.3;
    private int channels = 0;
    private int size = 1;
    private int pos = 0;
    private int rampSamples = 960;
    private boolean settled = false;

    /** A model for up to {@value #DEFAULT_MAX_CHANNELS} channels. */
    public CabinetModel()
    {
        this(DEFAULT_MAX_CHANNELS);
    }

    /**
     * A model for up to {@code maxChannels} channels.
     *
     * @param maxChannels largest number of channels (at least 1)
     */
    public CabinetModel(int maxChannels)
    {
        if (maxChannels < 1)
            throw new IllegalArgumentException("maxChannels must be >= 1; got " + maxChannels);
        this.maxChannels = maxChannels;
    }

    /**
     * Prepares the model for a sample rate and a channel count, and clears
     * it. Invalid values are ignored. Allocates.
     *
     * @param sampleRate   sample rate in Hz
     * @param numChannels  channels (at most the model's maximum are processed)
     */
    public void prepare(double sampleRate, int numChannels)
    {
        if (!Double.isFinite(sampleRate) || !(sampleRate > 0.0) || numChannels <= 0) return;
        fs = sampleRate;
        channels = Math.min(numChannels, maxChannels);
        if (cascade == null) cascade = new BiquadCascade(SECTIONS, maxChannels);
        cascade.setNumSections(SECTIONS);
        fit.prepare(sampleRate);

        for (int k = 0; k < TAPS; ++k)
            tapDelay[k] = Math.max(1, (int) lround(TAP_MS[k] * 0.001 * fs));
        diffuserDelay[0] = Math.max(1, (int) lround(DIFFUSER_MS[0] * 0.001 * fs));
        diffuserDelay[1] = Math.max(1, (int) lround(DIFFUSER_MS[1] * 0.001 * fs));
        size = 1;
        while (size <= tapDelay[TAPS - 1] + 1 || size <= diffuserDelay[1] + 1) size <<= 1;
        lines = new double[channels * 3 * size];
        rampSamples = Math.max(1, (int) (0.02 * fs));
        settled = false;
        distanceNow = distance;
        dirty = true;
        reset();
    }

    /** Clears the filters and the reflections (settings are kept). */
    public void reset()
    {
        if (cascade != null) cascade.reset();
        Arrays.fill(lines, 0.0);
        pos = 0;
        distanceNow = distance;
    }

    /**
     * Size of the box, 0 to 1: the cone's resonance from 140 Hz (0) down to
     * 70 Hz (1). Default 0.5 (99 Hz). Non-finite values are ignored.
     *
     * @param size  the size
     */
    public void setSize(double size)
    {
        if (!Double.isFinite(size)) return;
        sizeParam = clamp(size, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Brightness of the speaker, 0 to 1: the roll-off from 3 kHz (0) up to
     * 7 kHz (1), with the breakup peak at 0.62 of it. Default 0.5.
     * Non-finite values are ignored.
     *
     * @param brightness  the brightness
     */
    public void setBrightness(double brightness)
    {
        if (!Double.isFinite(brightness)) return;
        this.brightness = clamp(brightness, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Distance of the microphone, 0 to 1: how much of the box's reflections
     * comes with the direct sound (0: none). Default 0.3. Non-finite values
     * are ignored.
     *
     * @param distance  the distance
     */
    public void setDistance(double distance)
    {
        if (!Double.isFinite(distance)) return;
        this.distance = clamp(distance, 0.0, 1.0);
    }

    /** @return the size of the box */
    public double getSize() { return sizeParam; }

    /** @return the brightness of the speaker */
    public double getBrightness() { return brightness; }

    /** @return the distance of the microphone */
    public double getDistance() { return distance; }

    /** @return the cone's resonance for the size set, in hertz */
    public double getResonanceHz()
    {
        return 140.0 * Math.pow(2.0, -getSize());
    }

    /** @return the roll-off corner for the brightness set, in hertz */
    public double getRollOffHz()
    {
        return 3000.0 * Math.pow(7.0 / 3.0, getBrightness());
    }

    /** {@link #process(double[][], int, int)} from sample 0. */
    public void process(double[][] buffer, int numSamples)
    {
        process(buffer, 0, numSamples);
    }

    /**
     * Runs a block in place. Channels beyond the prepared count pass through.
     *
     * @param buffer      the channels
     * @param offset      first sample of the block in every channel
     * @param numSamples  samples per channel
     */
    public void process(double[][] buffer, int offset, int numSamples)
    {
        process(buffer, buffer.length, offset, numSamples);
    }

    /**
     * Runs a block of the first channels of a buffer in place.
     *
     * @param buffer       the channels
     * @param numChannels  channels to process (at most the prepared count)
     * @param offset       first sample of the block in every channel
     * @param numSamples   samples per channel
     */
    public void process(double[][] buffer, int numChannels, int offset, int numSamples)
    {
        if (cascade == null || lines.length == 0) return;
        if (dirty)
        {
            dirty = false;
            design();
        }
        final int nCh = Math.min(numChannels, channels);
        final int nS = numSamples;
        final double target = distance;
        final double distanceStep = (target - distanceNow) / (double) Math.max(nS, 1);
        final int mask = size - 1;
        final double[] line = lines;
        for (int i = 0; i < nS; ++i)
        {
            final double d = distanceNow + distanceStep * (double) (i + 1);
            // Energy of the reflections, so the level stays as they come in.
            final double norm = 1.0 / Math.sqrt(1.0 + d * d * TAP_ENERGY);
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] data = buffer[ch];
                final int direct = ch * 3 * size;
                final int first = direct + size;
                final int second = first + size;
                final double x = data[offset + i];
                final double y = cascade.processSample(Double.isFinite(x) ? x : 0.0, ch);
                line[direct + pos] = y;

                double reflections = 0.0;
                for (int k = 0; k < TAPS; ++k)
                    reflections += TAP_GAIN[k] * line[direct + ((pos - tapDelay[k]) & mask)];

                // Two Schroeder allpasses: v = x + g w(n - D); out = w(n - D) - g v.
                final double late1 = line[first + ((pos - diffuserDelay[0]) & mask)];
                final double v1 = reflections + DIFFUSER_GAIN[0] * late1;
                line[first + pos] = v1;
                final double a1 = late1 - DIFFUSER_GAIN[0] * v1;
                final double late2 = line[second + ((pos - diffuserDelay[1]) & mask)];
                final double v2 = a1 + DIFFUSER_GAIN[1] * late2;
                line[second + pos] = v2;
                final double a2 = late2 - DIFFUSER_GAIN[1] * v2;

                data[offset + i] = (y + d * a2) * norm;
            }
            cascade.advanceRamps(1);
            pos = (pos + 1) & mask;
        }
        distanceNow = target;
    }

    /** Designs the colour and sends it to the cascade. */
    private void design()
    {
        final double resonance = getResonanceHz();
        final double rollOff = min(getRollOffHz(), 0.45 * fs);
        final AnalogCascade[] parts = {
            FilterDesign.lowCut(resonance, 12.0, 1.4),
            FilterDesign.bell(450.0, 1.0, -2.0, 1),
            FilterDesign.bell(0.62 * rollOff, 1.5, 4.0, 1),
            FilterDesign.highCut(rollOff, 24.0),
        };
        int count = 0;
        for (final AnalogCascade part : parts)
            count += FilterDesign.digital(part, fit, sections, count, SECTIONS - count);
        final int ramp = settled ? rampSamples : 0;
        for (int s = 0; s < SECTIONS; ++s)
            cascade.rampCoeffs(s, s < count ? sections[s] : BiquadCoeffs.IDENTITY, ramp);
        settled = true;
    }

    /** std::lround: the nearest integer, halves away from zero. */
    private static long lround(double x)
    {
        final double a = Math.abs(x);
        final double f = Math.floor(a);
        final double r = (a - f >= 0.5) ? f + 1.0 : f;
        return (long) Math.copySign(r, x);
    }

    private static double clamp(double v, double lo, double hi)
    {
        return (v < lo) ? lo : ((hi < v) ? hi : v);
    }

    private static double min(double a, double b)
    {
        return (b < a) ? b : a;
    }
}
