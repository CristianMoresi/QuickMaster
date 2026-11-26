package com.dspark.effects;

import com.dspark.core.Oversampling;

import java.util.Arrays;

/**
 * Multi-band saturation: a crossover split, a {@link Saturation} per band,
 * and the bands summed back in time with each other.
 * <p>
 * Splits the signal into 2 to maxBands bands (6 by default) with a
 * Linkwitz-Riley crossover ({@link CrossoverFilter}, IIR or linear phase),
 * runs each band through its own Saturation (any algorithm, drive, character,
 * curve shape, the stages around the curve, its own mix), then applies a
 * level, a pan, a mute and a solo per band and sums them. The bands are
 * summed in time whatever each one's latency: a band that runs with less
 * latency than the slowest one is delayed by the difference, so a band with
 * another oversampling factor or antialiasing order does not comb against its
 * neighbours at the crossover.
 * <p>
 * {@link #setMix} blends the sum with the programme as the crossover leaves
 * it (the bands summed without their curves, as late as the slowest band):
 * the dry side carries the crossover's phase, so any mix is as flat as the
 * crossover is, at the splits too. Each band of the dry side is the dry side
 * of that band's Saturation: through the same oversampling filters when they
 * are minimum phase, and in the phase of its high-passes while the mix is
 * between its ends. {@link #setOutputClip} puts an oversampled clipper after
 * the sum, with a ceiling ({@link #setOutputCeiling}): many driven bands can
 * add up to far more than any one of them.
 * <p>
 * Level and pan glide over 20 ms; mute and solo fade over 20 ms on a ramp of
 * their own. The level and pan glide lands on its target whatever the block
 * size: within a block it moves a share of the way that grows with the
 * block's length, and a block longer than 20 ms stops on the target instead
 * of passing it. A band that is not heard (muted, or left out by a solo, once
 * its fade is over) is not computed unless the mix needs its dry side: its
 * Saturation is skipped and starts from silence when the band is heard
 * again, under its fade in.
 * <p>
 * Up to 16 channels are processed; further channels pass through untouched.
 * Setters take effect at the next {@link #process} call; the oversampling
 * settings rebuild the bands' oversamplers (they allocate). Not thread-safe.
 */
public final class MultibandSaturation
{
    /** Maximum number of bands of a default-constructed processor. */
    public static final int DEFAULT_MAX_BANDS = 6;

    /** Glide of level and pan, fade of mute and solo. */
    private static final double FADE_SECONDS = 0.02;
    /** Longest latency a band can be delayed by, plus room. */
    private static final int PAD_SIZE = 512;
    /** Channels the bands, the dry side and the alignment lines hold. */
    private static final int MAX_CHANNELS = 16;

    private final int maxBands;
    private boolean prepared = false;
    private double sampleRate = 0.0;
    /** The channels processed: the prepared count, at most MAX_CHANNELS. */
    private int bufferChannels = 0;
    private int maxBlockSize = 0;
    private final CrossoverFilter crossover;
    private final Saturation[] saturation;
    /** [band][channel][maxBlockSize] the crossover's bands. */
    private double[][][] bandBuffers = new double[0][][];

    // Per band: level, pan, mute and solo as set.
    private final double[] levelDb;
    private final double[] pan;
    private final boolean[] mute;
    private final boolean[] solo;

    // Where each of a band's two ramps is.
    /** Mute and solo: 0 silent, 1 heard. */
    private final double[] rampHeard;
    /** Level and pan, per side. */
    private final double[] rampLeft;
    private final double[] rampRight;
    private final boolean[] rampComputed;

    /** Per band (then per band's dry side) and channel, PAD_SIZE samples each. */
    private double[] padLines = new double[0];
    private int padPos = 0;
    /** The dry side of the mix, and that of the band at hand. */
    private double[][] dryBuffer = new double[0][];
    private double[][] bandDry = new double[0][];
    /** The clipper after the sum. */
    private final OversampledClipper clip = new OversampledClipper();
    private double mix = 1.0;
    private double outputCeilingDb = 0.0;
    private boolean outputClip = false;
    /** The mix as it glides. */
    private double mixNow = 1.0;
    /** The dry line and the clipper ran in the last chunk. */
    private boolean dryRunning = false, clipping = false;
    /** Samples of the 20 ms glides and fades, and the share of the way a sample moves. */
    private double fadeSamples = 960.0;
    private double fadeStep = 1.0 / 960.0;
    /** Test access: false lets a level or pan glide pass its target in a block longer than 20 ms. */
    boolean glideLanding = true;

    // Where a band's ramps are going (targets()).
    private double targetHeard, targetLeft, targetRight;

    /** A processor of up to {@link #DEFAULT_MAX_BANDS} bands. */
    public MultibandSaturation()
    {
        this(DEFAULT_MAX_BANDS);
    }

    /**
     * A processor of up to {@code maxBands} bands.
     *
     * @param maxBands maximum number of bands, at least 2 (one split)
     * @throws IllegalArgumentException if maxBands is below 2
     */
    public MultibandSaturation(int maxBands)
    {
        if (maxBands < 2)
            throw new IllegalArgumentException("MultibandSaturation needs at least 2 bands (one split); got " + maxBands);
        this.maxBands = maxBands;
        crossover = new CrossoverFilter(maxBands);
        saturation = new Saturation[maxBands];
        for (int b = 0; b < maxBands; ++b) saturation[b] = new Saturation();
        levelDb = new double[maxBands];
        pan = new double[maxBands];
        mute = new boolean[maxBands];
        solo = new boolean[maxBands];
        rampHeard = new double[maxBands];
        rampLeft = new double[maxBands];
        rampRight = new double[maxBands];
        rampComputed = new boolean[maxBands];
        Arrays.fill(rampHeard, 1.0);
        Arrays.fill(rampLeft, 1.0);
        Arrays.fill(rampRight, 1.0);
        Arrays.fill(rampComputed, true);
    }

    // -- Lifecycle -----------------------------------------------------------

    /**
     * Prepares the crossover, every band and the buffers (allocates). An
     * invalid set of values (a rate that is not a positive finite number, or
     * no channels or samples) is ignored.
     *
     * @param sampleRate    host rate in Hz
     * @param numChannels   channels
     * @param maxBlockSize  largest block
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize)
    {
        if (!(sampleRate > 0.0 && sampleRate <= Double.MAX_VALUE && maxBlockSize > 0 && numChannels > 0)) return;
        prepared = false;
        this.sampleRate = sampleRate;
        this.bufferChannels = Math.min(numChannels, MAX_CHANNELS);
        this.maxBlockSize = maxBlockSize;
        crossover.prepare(sampleRate, numChannels, maxBlockSize);
        bandBuffers = new double[maxBands][bufferChannels][maxBlockSize];
        for (int b = 0; b < maxBands; ++b) saturation[b].prepare(sampleRate, numChannels, maxBlockSize);
        // Two lines a band: its dry side waits for the slowest band too.
        padLines = new double[2 * maxBands * Math.max(bufferChannels, 1) * PAD_SIZE];
        dryBuffer = new double[bufferChannels][maxBlockSize];
        bandDry = new double[bufferChannels][maxBlockSize];
        clip.setMode(OversampledClipper.Mode.HARD);
        clip.setOversampling(4);
        clip.setCeiling(outputCeilingDb);
        clip.prepare(sampleRate, numChannels, maxBlockSize);
        fadeSamples = SaturationDetail.max(1.0, FADE_SECONDS * sampleRate);
        fadeStep = 1.0 / fadeSamples;
        reset();
        prepared = true;
    }

    /** Clears the crossover, the bands and the alignment delays. */
    public void reset()
    {
        crossover.reset();
        for (final Saturation band : saturation) band.reset();
        Arrays.fill(padLines, 0.0);
        padPos = 0;
        clip.reset();
        clipping = outputClip;
        mixNow = mix;
        dryRunning = mixNow < 1.0;
        final boolean soloing = anySolo();
        for (int b = 0; b < maxBands; ++b)
        {
            targets(b, soloing);
            rampHeard[b] = targetHeard;
            rampLeft[b] = targetLeft;
            rampRight[b] = targetRight;
            rampComputed[b] = targetHeard > 0.0;
        }
    }

    /** {@link #process(double[][], int, int)} from sample 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, 0, numSamples);
    }

    /**
     * Processes audio through the bands, in place. Pass-through until
     * prepare() succeeds. A block longer than the prepared maxBlockSize is
     * processed in pieces of that size; the span is clamped to the shortest
     * channel array; channels beyond the prepared count, or beyond 16, pass
     * through.
     *
     * @param channels    the channels (non-null arrays), in place
     * @param offset      first sample in every channel
     * @param numSamples  samples per channel
     */
    public void process(double[][] channels, int offset, int numSamples)
    {
        if (!prepared || channels == null || offset < 0) return;
        final int chunk = maxBlockSize;
        if (chunk <= 0) return;
        int total = numSamples;
        for (final double[] ch : channels) total = Math.min(total, ch.length - offset);
        for (int off = 0; off < total; off += chunk)
            processChunk(channels, offset + off, Math.min(chunk, total - off));
    }

    // -- Crossover -------------------------------------------------------------

    /**
     * Number of bands, 2 to maxBands; re-spaces the split frequencies, and a
     * live change fades (see {@link CrossoverFilter#setNumBands}).
     */
    public void setNumBands(int n)
    {
        crossover.setNumBands(clampInt(n, 2, maxBands));
    }

    /** Number of bands keeping the split frequencies (see {@link CrossoverFilter#setNumBandsKeepingSplits}). */
    public void setNumBandsKeepingSplits(int n)
    {
        crossover.setNumBandsKeepingSplits(clampInt(n, 2, maxBands));
    }

    /** Sets a split frequency (see {@link CrossoverFilter#setCrossoverFrequency}). */
    public void setCrossoverFrequency(int index, double hz)
    {
        crossover.setCrossoverFrequency(index, hz);
    }

    /** Slope of the crossover in dB per octave (see {@link CrossoverFilter#setOrder}). */
    public void setOrder(int order)
    {
        crossover.setOrder(order);
    }

    /** Minimum-phase (IIR) or linear-phase crossover. */
    public void setCrossoverMode(CrossoverFilter.FilterMode mode)
    {
        crossover.setFilterMode(mode);
    }

    /** Direct-form or ladder sections in the minimum-phase crossover (see {@link CrossoverFilter#setStructure}). */
    public void setCrossoverStructure(CrossoverFilter.Structure structure)
    {
        crossover.setStructure(structure);
    }

    /** Cascade or balanced arrangement of the minimum-phase splits (see {@link CrossoverFilter#setTopology}). */
    public void setCrossoverTopology(CrossoverFilter.Topology topology)
    {
        crossover.setTopology(topology);
    }

    public int getNumBands()
    {
        return crossover.getNumBands();
    }

    public double getCrossoverFrequency(int index)
    {
        return crossover.getCrossoverFrequency(index);
    }

    public int getOrder()
    {
        return crossover.getOrder();
    }

    public CrossoverFilter.FilterMode getCrossoverMode()
    {
        return crossover.getFilterMode();
    }

    public CrossoverFilter.Structure getCrossoverStructure()
    {
        return crossover.getStructure();
    }

    public CrossoverFilter.Topology getCrossoverTopology()
    {
        return crossover.getTopology();
    }

    // -- Bands ---------------------------------------------------------------

    /** The Saturation of a band (the index is clamped). */
    public Saturation getBandSaturation(int band)
    {
        return saturation[clampInt(band, 0, maxBands - 1)];
    }

    /** The algorithm of a band. */
    public void setBandAlgorithm(int band, Saturation.Algorithm algo)
    {
        getBandSaturation(band).setAlgorithm(algo);
    }

    /** The drive of a band in dB. */
    public void setBandDrive(int band, double dB)
    {
        getBandSaturation(band).setDrive(dB);
    }

    /** Level of a band in dB, -60 to +24 (glides over 20 ms); a band outside the range or a non-finite value is ignored. */
    public void setBandLevel(int band, double dB)
    {
        if (band < 0 || band >= maxBands || !Double.isFinite(dB)) return;
        levelDb[band] = SaturationDetail.clamp(dB, -60.0, 24.0);
    }

    /** Pan of a band, -1 (left) to +1 (right): a balance that never lifts a side. */
    public void setBandPan(int band, double p)
    {
        if (band < 0 || band >= maxBands || !Double.isFinite(p)) return;
        pan[band] = SaturationDetail.clamp(p, -1.0, 1.0);
    }

    /** Mutes a band (fades over 20 ms). */
    public void setBandMute(int band, boolean on)
    {
        if (band < 0 || band >= maxBands) return;
        mute[band] = on;
    }

    /** Solos a band: while any band is soloed only the soloed ones are heard (fades over 20 ms). */
    public void setBandSolo(int band, boolean on)
    {
        if (band < 0 || band >= maxBands) return;
        solo[band] = on;
    }

    public double getBandLevel(int band)
    {
        return levelDb[clampInt(band, 0, maxBands - 1)];
    }

    public double getBandPan(int band)
    {
        return pan[clampInt(band, 0, maxBands - 1)];
    }

    public boolean isBandMuted(int band)
    {
        return mute[clampInt(band, 0, maxBands - 1)];
    }

    public boolean isBandSoloed(int band)
    {
        return solo[clampInt(band, 0, maxBands - 1)];
    }

    /** Whether a band is computed now (false while it is not heard). */
    public boolean isBandComputed(int band)
    {
        return band >= 0 && band < maxBands && rampComputed[band];
    }

    // -- The sum ---------------------------------------------------------------

    /**
     * Blends the sum of the bands with the programme, 0 (dry) to 1 (the
     * default: the bands alone; glides over 20 ms).
     * <p>
     * The dry side is the programme as the crossover leaves it (the bands
     * summed without their curves) and as late as the slowest band, so it
     * meets the processed bands in the crossover's phase: with no drive a mix
     * of one half is as flat as the crossover is, at the splits too. Each
     * band's dry side crosses what that band's does: the filters of
     * minimum-phase oversampling, and between the two ends the phase (not the
     * magnitude) of its Saturation's high-passes, so a mix of one half is the
     * mean of the two levels at the bottom and at the top as well. Each band's
     * own Saturation mix still acts inside its band. Non-finite values are
     * ignored.
     */
    public void setMix(double amount)
    {
        if (Double.isFinite(amount)) mix = SaturationDetail.clamp(amount, 0.0, 1.0);
    }

    public double getMix()
    {
        return mix;
    }

    /**
     * An oversampled clipper after the sum (off by default). Driven bands add
     * up to more than any one of them; with this on, the sum is clipped at
     * {@link #setOutputCeiling} four times oversampled, and no sample leaves
     * over the ceiling; under it nothing changes but the latency, which grows
     * by the clipper's ({@link #getLatency()} reports it).
     */
    public void setOutputClip(boolean on)
    {
        outputClip = on;
    }

    public boolean getOutputClip()
    {
        return outputClip;
    }

    /** Ceiling of the output clipper, -24 to 0 dBFS (default 0); non-finite values are ignored. */
    public void setOutputCeiling(double dB)
    {
        if (!Double.isFinite(dB)) return;
        outputCeilingDb = SaturationDetail.clamp(dB, -24.0, 0.0);
        clip.setCeiling(outputCeilingDb);
    }

    public double getOutputCeiling()
    {
        return outputCeilingDb;
    }

    // -- Settings shared by every band -------------------------------------------

    /** The oversampling factor of every band (allocates; see {@link Saturation#setOversampling}). */
    public void setOversampling(int factor)
    {
        for (final Saturation band : saturation) band.setOversampling(factor);
    }

    /** The oversampler filters of every band (allocates). */
    public void setOversamplingMode(Saturation.OversamplingMode mode)
    {
        for (final Saturation band : saturation) band.setOversamplingMode(mode);
    }

    /** The band the first stage of every band's oversampler keeps (allocates; see {@link Saturation#setOversamplingBand}). */
    public void setOversamplingBand(Oversampling.Band band)
    {
        for (final Saturation each : saturation) each.setOversamplingBand(band);
    }

    /** The antialiasing order of every band. */
    public void setAntialiasing(Saturation.Antialiasing mode)
    {
        for (final Saturation band : saturation) band.setAntialiasing(mode);
    }

    /**
     * Latency in samples: the crossover's and the slowest band's. Every band
     * is delayed to the slowest band's latency, so this is the latency of the
     * sum whatever each band runs with; the output clipper adds its own while
     * it is on.
     */
    public int getLatency()
    {
        return crossover.getLatency() + slowestBand(crossover.getNumBands()) + (outputClip ? clip.getLatency() : 0);
    }

    // -- Processing --------------------------------------------------------------

    private boolean anySolo()
    {
        final int n = crossover.getNumBands();
        for (int b = 0; b < n; ++b)
            if (solo[b]) return true;
        return false;
    }

    /** Where a band's ramps are going, into targetHeard, targetLeft and targetRight. */
    private void targets(int band, boolean soloing)
    {
        final boolean heard = !mute[band] && (!soloing || solo[band]);
        final double level = Math.pow(10.0, levelDb[band] / 20.0);
        final double p = pan[band];
        final double left = (p > 0.0) ? Math.cos(0.5 * Math.PI * p) : 1.0;
        final double right = (p < 0.0) ? Math.cos(-0.5 * Math.PI * p) : 1.0;
        targetHeard = heard ? 1.0 : 0.0;
        targetLeft = level * left;
        targetRight = level * right;
    }

    /** The latency of the slowest of the first n bands. */
    private int slowestBand(int n)
    {
        int slowest = 0;
        final int count = clampInt(n, 0, maxBands);
        for (int b = 0; b < count; ++b) slowest = Math.max(slowest, saturation[b].getLatency());
        return Math.min(slowest, PAD_SIZE - 1);
    }

    private void processChunk(double[][] buffer, int offset, int n)
    {
        final int nCh = Math.min(buffer.length, bufferChannels);
        final int nS = Math.min(n, maxBlockSize);
        if (nCh <= 0 || nS <= 0) return;

        final int nb = crossover.process(buffer, offset, bandBuffers, 0, maxBands, nS);
        if (nb < 2) return;

        final boolean soloing = anySolo();
        final int slowest = slowestBand(nb);

        // The dry side of the mix: the dry side of each band, waiting for the
        // slowest band as the band itself does, summed without level, pan or
        // mute. It runs while the mix is, or is on its way, under one.
        final double mixTarget = mix;
        final boolean dry = mixTarget < 1.0 || mixNow < 1.0;
        if (dry && !dryRunning)
            for (int b = 0; b < maxBands; ++b) clearPad(maxBands + b);
        dryRunning = dry;
        if (dry)
            for (int ch = 0; ch < nCh; ++ch) Arrays.fill(dryBuffer[ch], 0, nS, 0.0);
        for (int ch = 0; ch < nCh; ++ch) Arrays.fill(buffer[ch], offset, offset + nS, 0.0);

        for (int b = 0; b < nb; ++b)
        {
            targets(b, soloing);
            final double tHeard = targetHeard, tLeft = targetLeft, tRight = targetRight;

            // A band that is not heard, and has faded out, is not computed,
            // unless the mix wants its dry side.
            final boolean silent = tHeard == 0.0 && rampHeard[b] == 0.0;
            if (silent && !dry)
            {
                rampComputed[b] = false;
                rampLeft[b] = tLeft;
                rampRight[b] = tRight;
                continue;
            }
            final Saturation sat = saturation[b];
            if (!rampComputed[b])
            {
                // Back from silence: its history is of another time.
                sat.reset();
                clearPad(b);
                rampComputed[b] = true;
            }
            final double[][] view = bandBuffers[b];
            if (dry) sat.processPieces(view, 0, nS, bandDry, mixTarget > 0.0);
            else sat.process(view, nS);
            final int pad = slowest - sat.getLatency();
            if (dry) addDryBand(b, nCh, nS, pad);

            final double heardStep = SaturationDetail.clamp(tHeard - rampHeard[b], -fadeStep, fadeStep);
            final boolean fading = rampHeard[b] != tHeard;
            final double leftStep = (tLeft - rampLeft[b]) * fadeStep;
            final double rightStep = (tRight - rampRight[b]) * fadeStep;
            // Over a block longer than the glide the step would carry level
            // and pan past their targets: there they stop on them.
            final boolean landing = glideLanding && (double) nS > fadeSamples;
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] in = view[ch];
                final double[] out = buffer[ch];
                final int line = (b * bufferChannels + ch) * PAD_SIZE;
                double heard = rampHeard[b], left = rampLeft[b], right = rampRight[b];
                final boolean isRight = (ch == 1);
                int pos = padPos;
                for (int i = 0; i < nS; ++i)
                {
                    // Every band is delayed to the slowest one's latency.
                    padLines[line + pos] = in[i];
                    final double aligned = padLines[line + ((pos - pad) & (PAD_SIZE - 1))];
                    pos = (pos + 1) & (PAD_SIZE - 1);
                    if (fading)
                    {
                        heard += heardStep;
                        if ((heardStep > 0.0 && heard > tHeard) || (heardStep < 0.0 && heard < tHeard)) heard = tHeard;
                    }
                    left += leftStep;
                    right += rightStep;
                    if (landing)
                    {
                        if ((leftStep > 0.0 && left > tLeft) || (leftStep < 0.0 && left < tLeft)) left = tLeft;
                        if ((rightStep > 0.0 && right > tRight) || (rightStep < 0.0 && right < tRight)) right = tRight;
                    }
                    final double side = (nCh == 2) ? (isRight ? right : left) : 0.5 * (left + right);
                    out[offset + i] += aligned * heard * side;
                }
                if (ch == nCh - 1)
                {
                    rampHeard[b] = heard;
                    rampLeft[b] = left;
                    rampRight[b] = right;
                }
            }
        }
        // Bands past the count (after a change of it) are left as they were.
        padPos = (padPos + nS) & (PAD_SIZE - 1);
        for (int b = nb; b < maxBands; ++b) rampComputed[b] = false;

        // The mix, gliding like the levels do.
        if (dry)
        {
            final double step = SaturationDetail.clamp(mixTarget - mixNow, -fadeStep, fadeStep);
            double m = mixNow;
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] d = dryBuffer[ch];
                final double[] out = buffer[ch];
                m = mixNow;
                for (int i = 0; i < nS; ++i)
                {
                    if (m != mixTarget)
                    {
                        m += step;
                        if ((step > 0.0 && m > mixTarget) || (step < 0.0 && m < mixTarget)) m = mixTarget;
                    }
                    out[offset + i] = d[i] + m * (out[offset + i] - d[i]);
                }
            }
            mixNow = m;
        }

        // The clipper after the sum, and no sample over its ceiling (its
        // filters ring a little past it).
        final boolean clipNow = outputClip;
        if (clipNow && !clipping) clip.reset();
        clipping = clipNow;
        if (clipNow)
        {
            clip.process(buffer, offset, nS);
            final double top = SaturationDetail.decibelsToGain(outputCeilingDb);
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] out = buffer[ch];
                for (int i = offset; i < offset + nS; ++i) out[i] = SaturationDetail.clamp(out[i], -top, top);
            }
        }
    }

    /** Adds the dry side of a band, as late as the slowest band. */
    private void addDryBand(int band, int channels, int samples, int pad)
    {
        for (int ch = 0; ch < channels; ++ch)
        {
            final double[] in = bandDry[ch];
            final double[] sum = dryBuffer[ch];
            final int line = ((maxBands + band) * bufferChannels + ch) * PAD_SIZE;
            int pos = padPos;
            for (int i = 0; i < samples; ++i)
            {
                padLines[line + pos] = in[i];
                sum[i] += padLines[line + ((pos - pad) & (PAD_SIZE - 1))];
                pos = (pos + 1) & (PAD_SIZE - 1);
            }
        }
    }

    private void clearPad(int band)
    {
        final int per = bufferChannels * PAD_SIZE;
        Arrays.fill(padLines, band * per, band * per + per, 0.0);
    }

    private static int clampInt(int v, int lo, int hi)
    {
        return v < lo ? lo : (hi < v ? hi : v);
    }
}
