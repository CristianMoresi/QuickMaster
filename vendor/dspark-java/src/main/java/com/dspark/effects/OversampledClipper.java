package com.dspark.effects;

import com.dspark.core.Adaa;
import com.dspark.core.DryWetMixer;
import com.dspark.core.DspMath;
import com.dspark.core.Oversampling;
import com.dspark.core.OversamplingDryPath;
import com.dspark.core.SmoothedParameter;

import java.util.Arrays;

/**
 * Multi-mode clipping processor with oversampling, a multi-stage cascade and
 * time-aware slew limiting.
 * <p>
 * Four clipping modes:
 * <ul>
 * <li>{@link Mode#HARD}: a digital clamp, transparent until the ceiling is
 *     hit, then a brickwall limit.</li>
 * <li>{@link Mode#SOFT}: hyperbolic tangent, smooth saturation with no hard
 *     discontinuity.</li>
 * <li>{@link Mode#ANALOG}: sine-based waveshaping, the soft clipping of
 *     magnetic or transformer saturation.</li>
 * <li>{@link Mode#GOLDEN_RATIO}: a soft knee that is linear up to
 *     ceiling/phi, then a smooth rational asymptote to the ceiling.</li>
 * </ul>
 * Input gain and ceiling glide linearly over 20 ms at the processing rate;
 * values set before {@link #prepare} apply from the first sample.
 * <p>
 * Antialiasing ({@link #setAntialiasing}, off by default): every mode then
 * runs second-order antiderivative antialiasing (Bilbao, Esqueda, Parker and
 * Valimaki 2017), per stage. Each stage delays by one processing-rate
 * sample; the wet path is padded to a whole number of base samples,
 * independent of the stage count, so {@link #getLatency()} grows by
 * ceil(4 / factor) base samples (4 at 1x, 2 at 2x, 1 from 4x) and the dry path
 * is delayed to match. The kernel attenuates a linear signal by
 * (1 + 2 cos w) / 3 at the processing rate, so use it with oversampling.
 * <p>
 * Without oversampling up to 16 channels are clipped; the dry/wet blend and
 * the oversampled path cover only the prepared channel count. Blocks longer
 * than the prepared maximum are processed in pieces. The ceiling is enforced
 * sample-exactly at the processing rate; with oversampling the downsampling
 * filter can reconstruct small inter-sample overshoots above it.
 * <p>
 * Setters take effect at the next {@link #process} call; the oversampling
 * settings at the next {@link #prepare}. Not thread-safe.
 */
public final class OversampledClipper
{
    /** The waveshaping curve. */
    public enum Mode
    {
        /** Brickwall digital clipping. High odd harmonics. */
        HARD,
        /** Tanh soft clipping. */
        SOFT,
        /** Sine-based soft clipping. Transformer-like saturation. */
        ANALOG,
        /** Soft knee using phi. Transparent until heavy drive. */
        GOLDEN_RATIO
    }

    /** How the oversampler filters (see {@link #setOversamplingMode}). */
    public enum OversamplingMode
    {
        /** Linear-phase half-bands: the latency of the factor, the dry path a delay (default). */
        LINEAR_PHASE,
        /** Elliptic allpass half-bands: a few samples of latency, the dry path through the same filters. */
        MINIMUM_PHASE
    }

    private static final int MAX_STAGES = 4;
    private static final int MAX_CHANNELS = 16;
    private static final double PHI = 1.6180339887498948482;
    /** Rate the automatic factor reaches. */
    private static final double AUTOMATIC_INTERNAL_RATE = 88200.0;
    private static final double PARAM_RAMP_MS = 20.0;
    private static final int CHUNK = 256;
    /** Longer than the largest pad (16 * 1 - 1). */
    private static final int PAD_RING = 32;

    /** One antialiased stage: its kernel and its last two outputs (in ceiling units). */
    private static final class AdaaStage
    {
        final Adaa.Adaa2 state = new Adaa.Adaa2();
        double out1 = 0.0, out2 = 0.0;

        void clear()
        {
            state.reset();
            out1 = 0.0;
            out2 = 0.0;
        }
    }

    private double sampleRate = 0.0;
    private int numChannels = 0;
    private int maxBlockSize = 0;
    private boolean prepared = false;
    private final DryWetMixer mixer = new DryWetMixer();
    private Oversampling oversampler = null;

    private Mode mode = Mode.HARD;
    private double ceilingDb = 0.0;
    private double inputGainDb = 0.0;
    private int stages = 1;
    private double mix = 1.0;
    private double slewLimitMs = 0.0;
    private int osFactor = 1;
    private boolean osAutomatic = false;
    private OversamplingMode osMode = OversamplingMode.LINEAR_PHASE;
    private Oversampling.Band osBand = Oversampling.Band.PRESET;
    /** The dry copy through minimum-phase filters. */
    private final OversamplingDryPath dryPath = new OversamplingDryPath();
    /** The dry copy crosses the filters (minimum phase). */
    private boolean dryThroughFilters = false;

    private final double[] slewPrev = new double[MAX_CHANNELS];

    // Parameter glides (processing rate) and their per-chunk ramps.
    private final SmoothedParameter stageGainSm = new SmoothedParameter();
    private final SmoothedParameter ceilingSm = new SmoothedParameter();
    /** Stage count the stage-gain glide refers to. */
    private int lastStages = 1;
    private final double[] gainRamp = new double[CHUNK];
    private final double[] ceilRamp = new double[CHUNK];

    private double gainReductionDb = 0.0;

    // Second-order antialiasing (processing rate, per channel and stage).
    private boolean antialias = false;
    /** The antialiasing as applied. */
    private boolean adaaActive = false;
    /** Seed the stage history on the next block. */
    private boolean adaaFresh = true;
    /** Curve the cached values belong to. */
    private Mode adaaMode = Mode.HARD;
    private int adaaStageCount = 1;
    /** Oversampling factor in use. */
    private int processFactor = 1;
    private final AdaaStage[][] adaaStages = new AdaaStage[MAX_CHANNELS][MAX_STAGES];
    private final double[][] padRing = new double[MAX_CHANNELS][PAD_RING];
    private int padPos = 0;
    /** Dry delay, up to 4 samples. */
    private final double[][] dryRing = new double[MAX_CHANNELS][MAX_STAGES];
    private int dryPos = 0;
    private double[][] dryScratch = new double[0][0];
    private int dryScratchSamples = 0;

    /** A hard clipper at 0 dBFS, no drive, no oversampling. */
    public OversampledClipper()
    {
        for (final AdaaStage[] chStages : adaaStages)
            for (int s = 0; s < MAX_STAGES; ++s) chStages[s] = new AdaaStage();
    }

    // -- Lifecycle --------------------------------------------------------------

    /**
     * Prepares the clipper, allocating the oversampling filters when the
     * factor is above 1. An invalid set of values (a rate that is not a
     * positive finite number, or no channels or samples) is ignored: the
     * previous state is kept and an unprepared instance passes audio through.
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
        this.numChannels = numChannels;
        this.maxBlockSize = maxBlockSize;
        mixer.prepare(sampleRate, numChannels, maxBlockSize);

        int factor = osFactor;
        if (osAutomatic)
        {
            factor = Oversampling.factorForRate(sampleRate, AUTOMATIC_INTERNAL_RATE);
            osFactor = factor;
        }

        if (factor > 1)
        {
            // At 16x the antialiased curves leave less than the High preset's
            // last stage lets through (93 dB): every stage is then made at
            // least 100 dB deep. Below that the folding of the curve itself is
            // what is left, so the preset stays.
            final boolean minimum = osMode == OversamplingMode.MINIMUM_PHASE;
            oversampler = new Oversampling(factor, Oversampling.Quality.HIGH,
                                           minimum ? Oversampling.Mode.POLYPHASE_IIR : Oversampling.Mode.LINEAR_PHASE_FIR,
                                           factor >= 16 ? Oversampling.Depth.DEEP : Oversampling.Depth.PRESET, osBand);
            oversampler.prepare(sampleRate, numChannels, maxBlockSize);
        }
        else
        {
            oversampler = null;
        }

        // Align the dry copy with the latent (oversampled) wet path so that a
        // blend below 100 % does not comb-filter; behind minimum-phase filters
        // the dry copy crosses the same filters instead. The antialiasing
        // delay is added on top by delaying the dry input (pushDryAligned).
        dryThroughFilters = oversampler != null && oversampler.getMode() == Oversampling.Mode.POLYPHASE_IIR;
        if (dryThroughFilters) dryPath.prepare(sampleRate, numChannels, maxBlockSize, oversampler);
        mixer.setLatencyCompensation(oversampler != null && !dryThroughFilters ? oversampler.getLatency() : 0);
        processFactor = oversampler != null ? oversampler.getFactor() : 1;
        dryScratch = new double[Math.min(numChannels, MAX_CHANNELS)][maxBlockSize];
        dryScratchSamples = maxBlockSize;
        resetAntialiasState();

        // Parameter glides run at the processing (possibly oversampled) rate.
        final double procRate = sampleRate * (oversampler != null ? oversampler.getFactor() : 1);
        for (final SmoothedParameter sm : new SmoothedParameter[] { stageGainSm, ceilingSm })
        {
            sm.prepare(procRate, PARAM_RAMP_MS);
            sm.setSmoothingType(SmoothedParameter.SmoothingType.LINEAR);
        }
        snapParameters();

        Arrays.fill(slewPrev, 0.0);

        prepared = true;
    }

    /** Clears the history, the slew limiter, the oversampling memory and the meter. */
    public void reset()
    {
        mixer.reset();
        dryPath.reset();
        if (oversampler != null) oversampler.reset();
        Arrays.fill(slewPrev, 0.0);
        snapParameters();
        resetAntialiasState();
        gainReductionDb = 0.0;
    }

    // -- Processing -------------------------------------------------------------

    /** {@link #process(double[][], int, int)} from sample 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, 0, numSamples);
    }

    /**
     * Processes planar audio in place: the dry copy, the upsampling, the
     * curves, the downsampling and the mix. Pass-through until prepare()
     * succeeds.
     *
     * @param channels    the channels, in place
     * @param offset      first sample in every channel
     * @param numSamples  samples per channel
     */
    public void process(double[][] channels, int offset, int numSamples)
    {
        if (!prepared) return;
        if (numSamples <= 0 || channels.length == 0) return;

        // The oversampler and the dry mixer hold one maximum block.
        final int maxBlock = Math.max(1, maxBlockSize);
        for (int off = 0; off < numSamples; off += maxBlock)
            processChunk(channels, offset + off, Math.min(maxBlock, numSamples - off));
    }

    private void processChunk(double[][] buffer, int off, int n)
    {
        final double mixVal = mix;
        final boolean adaa = antialias;
        if (adaa != adaaActive)
        {
            // Start each stage from its current input rather than from stale
            // history; the latency change itself steps the output.
            adaaActive = adaa;
            adaaFresh = true;
        }
        pushDryAligned(buffer, off, n, adaa ? adaaBaseLatency() : 0);

        if (oversampler != null && oversampler.getFactor() > 1)
        {
            final double[][] up = oversampler.upsample(buffer, off, n);
            processInternal(up, 0, oversampler.getHighRateLength(), sampleRate * oversampler.getFactor());
            oversampler.downsample(buffer, off, n);
        }
        else
        {
            processInternal(buffer, off, n, sampleRate);
        }

        mixer.mixWet(buffer, off, n, mixVal);
    }

    // -- Parameters ---------------------------------------------------------------

    /** Sets the clipping curve (null is ignored). */
    public void setMode(Mode mode)
    {
        if (mode != null) this.mode = mode;
    }

    /**
     * Sets the maximum output level.
     *
     * @param dB  ceiling in dBFS, clamped to [-60, 0]; non-finite values are ignored
     */
    public void setCeiling(double dB)
    {
        if (!Double.isFinite(dB)) return;
        ceilingDb = clamp(dB, -60.0, 0.0);
    }

    /**
     * Sets the drive before clipping.
     *
     * @param dB  gain in dB, clamped to [0, 48]; non-finite values are ignored
     */
    public void setInputGain(double dB)
    {
        if (!Double.isFinite(dB)) return;
        inputGainDb = clamp(dB, 0.0, 48.0);
    }

    /**
     * Sets the number of cascaded clipping stages: the input gain is split
     * logarithmically across them, which makes heavy distortion smoother than
     * one aggressive stage. A change keeps the running total gain.
     *
     * @param count  stages, clamped to [1, 4]
     */
    public void setStages(int count)
    {
        stages = Math.max(1, Math.min(count, MAX_STAGES));
    }

    /**
     * Sets the dry/wet ratio.
     *
     * @param amount  0 entirely dry to 1 fully processed; non-finite values are ignored
     */
    public void setMix(double amount)
    {
        if (!Double.isFinite(amount)) return;
        mix = clamp(amount, 0.0, 1.0);
    }

    /**
     * Limits how fast the output can change, as the rise time from zero to
     * the ceiling, so the effect is the same at any rate or oversampling
     * factor. A slew limiter is itself nonlinear: it darkens transient edges
     * and the top octave rather than removing aliasing.
     *
     * @param ms  rise time in ms; 0 is off; non-finite values are ignored
     */
    public void setSlewLimit(double ms)
    {
        if (!Double.isFinite(ms)) return;
        slewLimitMs = stdMax(ms, 0.0);
    }

    /**
     * Sets the oversampling factor, applied by the next {@link #prepare}.
     * Values are rounded up to the next power of two and capped at 16. At 16x
     * every decimation stage is at least 100 dB deep. Turns the automatic
     * factor off.
     *
     * @param factor  1 (off), 2, 4, 8 or 16
     */
    public void setOversampling(int factor)
    {
        final int f = Math.max(1, factor);
        int ceil = 1;
        while (ceil < f && ceil < (1 << 30)) ceil <<= 1;
        osFactor = Math.min(ceil, 16);
        osAutomatic = false;
    }

    /**
     * Lets the host rate choose the factor (applied by the next prepare()):
     * the smallest factor that brings the clipper to at least 88.2 kHz, 2x
     * at 44.1 and 48 kHz and none from 88.2 kHz up. Off by default; an
     * explicit setOversampling() turns it off.
     */
    public void setAutomaticOversampling(boolean on)
    {
        osAutomatic = on;
    }

    /** Whether the factor follows the host rate. */
    public boolean isOversamplingAutomatic()
    {
        return osAutomatic;
    }

    /** Chooses the oversampler's filters, applied by the next prepare() (null is ignored). */
    public void setOversamplingMode(OversamplingMode mode)
    {
        if (mode != null) osMode = mode;
    }

    /** The oversampler's filters. */
    public OversamplingMode getOversamplingMode()
    {
        return osMode;
    }

    /**
     * Chooses the band the oversampler's first stage keeps, applied by the
     * next prepare() (null is ignored): PRESET up to 0.46 of the host rate,
     * AUDIBLE up to 20 kHz with a stage fitted to the host rate.
     */
    public void setOversamplingBand(Oversampling.Band band)
    {
        if (band != null) osBand = band;
    }

    /** The band of the oversampler's first stage. */
    public Oversampling.Band getOversamplingBand()
    {
        return osBand;
    }

    public Mode getMode()
    {
        return mode;
    }

    public double getCeiling()
    {
        return ceilingDb;
    }

    public double getInputGain()
    {
        return inputGainDb;
    }

    public int getStages()
    {
        return stages;
    }

    public double getMix()
    {
        return mix;
    }

    public double getSlewLimit()
    {
        return slewLimitMs;
    }

    /** The oversampling factor set (applied on the next prepare()). */
    public int getOversampling()
    {
        return osFactor;
    }

    /**
     * Enables second-order antiderivative antialiasing on every mode, from the
     * next block. Adds ceil(4 / factor) base samples of latency, reported by
     * getLatency() at once.
     */
    public void setAntialiasing(boolean on)
    {
        antialias = on;
    }

    /** Whether second-order antialiasing is on. */
    public boolean getAntialiasing()
    {
        return antialias;
    }

    /** Latency in samples: the oversampling filters plus, when enabled, the antialiasing delay. */
    public int getLatency()
    {
        return (oversampler != null ? oversampler.getLatency() : 0) + (antialias ? adaaBaseLatency() : 0);
    }

    /** The largest gain reduction of the last block, in dB. */
    public double getGainReductionDb()
    {
        return gainReductionDb;
    }

    // -- The curves ---------------------------------------------------------------

    private void processInternal(double[][] buffer, int off, int nS, double currentSampleRate)
    {
        final Mode modeVal = mode;
        final double ceilDb = ceilingDb;
        final double gainDb = inputGainDb;
        final int numStages = stages;
        final double slewMs = slewLimitMs;

        if (numStages != lastStages)
        {
            // Keep the running total gain continuous.
            final double total = Math.pow(stageGainSm.getCurrentValue(), (double) lastStages);
            stageGainSm.reset(stageGainFor(total, numStages));
            lastStages = numStages;
        }
        ceilingSm.setTargetValue(dbToLinear(ceilDb));
        stageGainSm.setTargetValue(stageGainFor(dbToLinear(gainDb), numStages));

        // Maximum slew per sample is ceiling * slewScale (time-aware).
        final double slewScale = (slewMs > 0.0) ? 1.0 / (slewMs * 0.001) / currentSampleRate : 0.0;

        if (adaaActive)
        {
            // Cached antiderivative values belong to one curve.
            if (modeVal != adaaMode)
            {
                adaaMode = modeVal;
                for (final AdaaStage[] chStages : adaaStages)
                    for (final AdaaStage st : chStages) st.state.invalidate();
            }
            final Adaa.Curve curve;
            switch (modeVal)
            {
                case SOFT: curve = Adaa.AdaaTanh.INSTANCE; break;
                case ANALOG: curve = Adaa.AdaaSinePolynomial.INSTANCE; break;
                case GOLDEN_RATIO: curve = Adaa.AdaaRationalKnee.INSTANCE; break;
                default: curve = Adaa.AdaaHardClip.INSTANCE; break;
            }
            dispatchAntialiased(curve, buffer, off, nS, numStages, slewScale);
            return;
        }
        dispatchClipping(modeVal, buffer, off, nS, numStages, slewScale);
    }

    /** Antialiasing delay in base samples: MAX_STAGES processing samples, rounded up to whole base samples. */
    private int adaaBaseLatency()
    {
        final int factor = Math.max(1, processFactor);
        return (MAX_STAGES + factor - 1) / factor;
    }

    private void resetAntialiasState()
    {
        for (final AdaaStage[] chStages : adaaStages)
            for (final AdaaStage st : chStages) st.clear();
        for (final double[] ring : padRing) Arrays.fill(ring, 0.0);
        for (final double[] ring : dryRing) Arrays.fill(ring, 0.0);
        padPos = 0;
        dryPos = 0;
        adaaFresh = true;
        adaaStageCount = stages;
    }

    /**
     * Captures the dry signal, delayed by {@code extra} base samples when the
     * antialiasing delay is active. The short per-channel ring is updated
     * either way, so enabling it starts from real history.
     */
    private void pushDryAligned(double[][] buffer, int off, int n, int extra)
    {
        final int nCh = Math.min(Math.min(buffer.length, dryScratch.length), MAX_CHANNELS);
        final int nS = Math.min(n, dryScratchSamples);
        final int len = adaaBaseLatency();
        int pos = dryPos;
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] in = buffer[ch];
            final double[] out = dryScratch[ch];
            final double[] ring = dryRing[ch];
            pos = dryPos;
            for (int i = 0; i < nS; ++i)
            {
                out[i] = ring[pos];   // input len samples ago
                ring[pos] = in[off + i];
                pos = pos + 1 == len ? 0 : pos + 1;
            }
        }
        dryPos = pos;
        // The mixer blends only the prepared channels, so the scratch
        // (prepared channels) is enough whatever the buffer's width.
        if (dryThroughFilters && nS == n)
        {
            if (extra <= 0)
                for (int ch = 0; ch < nCh; ++ch) System.arraycopy(buffer[ch], off, dryScratch[ch], 0, nS);
            dryPath.process(dryScratch, 0, nS);
            mixer.pushDry(dryScratch, 0, nS);
        }
        else if (extra > 0 && nS == n)
            mixer.pushDry(dryScratch, 0, nS);
        else
            mixer.pushDry(buffer, off, n);
    }

    /**
     * Second-order antialiased loop. Works in units of the ceiling: each
     * stage maps u to curve(u) with u = stage gain times the previous stage's
     * output. Every stage delays by one processing sample; a pad brings the
     * total to factor * adaaBaseLatency() samples for any stage count.
     */
    private void dispatchAntialiased(Adaa.Curve curve, double[][] buffer, int off, int nS, int numStages,
                                     double slewScale)
    {
        final int nCh = Math.min(buffer.length, MAX_CHANNELS);
        final int totalDelay = Math.max(1, processFactor) * adaaBaseLatency();
        final int pad = totalDelay - numStages;

        double peakIn = 0.0;
        double peakOut = 0.0;

        if (adaaFresh)
        {
            // Hold the first input as the whole history of every stage.
            adaaFresh = false;
            adaaStageCount = numStages;
            if (nS > 0)
            {
                final double c0 = ceilingSm.getCurrentValue();
                final double g0 = stageGainSm.getCurrentValue();
                for (int ch = 0; ch < nCh; ++ch)
                {
                    double u = buffer[ch][off] / c0;
                    for (int s = 0; s < MAX_STAGES; ++s)
                    {
                        u *= g0;
                        final AdaaStage st = adaaStages[ch][s];
                        st.state.reset(u);
                        u = curve.d2(u);
                        st.out1 = st.out2 = u;
                    }
                }
            }
        }
        else if (numStages > adaaStageCount)
        {
            // Newly active stages continue from the previous stage's recent
            // output, as if they had been running.
            final double g0 = stageGainSm.getCurrentValue();
            for (int ch = 0; ch < nCh; ++ch)
                for (int s = adaaStageCount; s < numStages; ++s)
                {
                    final AdaaStage prev = adaaStages[ch][s - 1];
                    final AdaaStage st = adaaStages[ch][s];
                    st.state.reset(g0 * prev.out2);
                    st.state.track(g0 * prev.out1);
                    st.out1 = st.out2 = prev.out1;
                }
        }
        adaaStageCount = numStages;

        int writePos = padPos;
        for (int start = 0; start < nS; start += CHUNK)
        {
            final int n = Math.min(CHUNK, nS - start);
            final boolean gliding = stageGainSm.isSmoothing() || ceilingSm.isSmoothing();
            if (gliding)
                for (int j = 0; j < n; ++j)
                {
                    gainRamp[j] = stageGainSm.getNextValue();
                    ceilRamp[j] = ceilingSm.getNextValue();
                }
            final double gainConst = stageGainSm.getCurrentValue();
            final double ceilConst = ceilingSm.getCurrentValue();

            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] data = buffer[ch];
                final int base = off + start;
                final AdaaStage[] chStages = adaaStages[ch];
                final double[] ring = padRing[ch];
                int pos = writePos;
                for (int j = 0; j < n; ++j)
                {
                    final double stageGain = gliding ? gainRamp[j] : gainConst;
                    final double ceiling = gliding ? ceilRamp[j] : ceilConst;
                    final double g = stageGain;
                    final double c = ceiling;
                    double u = data[base + j] / c;

                    double totalGain = stageGain;
                    for (int s = 1; s < numStages; ++s) totalGain *= stageGain;
                    final double driven = data[base + j] * totalGain;

                    for (int s = 0; s < numStages; ++s)
                    {
                        final AdaaStage st = chStages[s];
                        u = st.state.process(curve, u * g);
                        st.out2 = st.out1;
                        st.out1 = u;
                    }

                    // Pad to the fixed total delay.
                    ring[pos] = u;
                    final double delayed = ring[(pos - pad) & (PAD_RING - 1)];
                    pos = (pos + 1) & (PAD_RING - 1);

                    double sample = delayed * c;
                    final double maxSlewDelta = ceiling * slewScale;
                    if (maxSlewDelta > 0.0)
                    {
                        final double delta = sample - slewPrev[ch];
                        if (Math.abs(delta) > maxSlewDelta)
                            sample = slewPrev[ch] + Math.copySign(maxSlewDelta, delta);
                    }
                    slewPrev[ch] = sample;

                    peakIn = stdMax(peakIn, Math.abs(driven));
                    peakOut = stdMax(peakOut, Math.abs(sample));
                    data[base + j] = sample;
                }
                if (ch == nCh - 1) writePos = pos;
            }
            if (nCh == 0) break;
        }
        padPos = writePos;
        storeGainReduction(peakIn, peakOut);
    }

    /** Gain per stage: the total split logarithmically across the cascade. */
    private static double stageGainFor(double totalGainLin, int numStages)
    {
        return (numStages > 1) ? Math.pow(totalGainLin, 1.0 / (double) numStages) : totalGainLin;
    }

    /** Jumps both parameter glides to the current targets (prepare/reset). */
    private void snapParameters()
    {
        lastStages = stages;
        ceilingSm.reset(dbToLinear(ceilingDb));
        stageGainSm.reset(stageGainFor(dbToLinear(inputGainDb), stages));
    }

    /** The plain loop: the curve of a mode, its stages, the slew limit and the meter. */
    private void dispatchClipping(Mode m, double[][] buffer, int off, int nS, int numStages, double slewScale)
    {
        final int nCh = Math.min(buffer.length, MAX_CHANNELS);

        double peakIn = 0.0;
        double peakOut = 0.0;

        // Chunks: the parameter glides are computed once per sample and shared
        // by every channel; settled, both are plain constants.
        for (int start = 0; start < nS; start += CHUNK)
        {
            final int n = Math.min(CHUNK, nS - start);
            final boolean gliding = stageGainSm.isSmoothing() || ceilingSm.isSmoothing();
            if (gliding)
                for (int j = 0; j < n; ++j)
                {
                    gainRamp[j] = stageGainSm.getNextValue();
                    ceilRamp[j] = ceilingSm.getNextValue();
                }
            final double gainConst = stageGainSm.getCurrentValue();
            final double ceilConst = ceilingSm.getCurrentValue();

            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] data = buffer[ch];
                final int base = off + start;
                for (int j = 0; j < n; ++j)
                {
                    final double stageGain = gliding ? gainRamp[j] : gainConst;
                    final double ceiling = gliding ? ceilRamp[j] : ceilConst;
                    double sample = data[base + j];
                    double totalGain = stageGain;
                    for (int s = 1; s < numStages; ++s) totalGain *= stageGain;
                    final double driven = sample * totalGain;

                    for (int s = 0; s < numStages; ++s)
                    {
                        sample *= stageGain;
                        sample = shape(m, sample, ceiling);
                    }

                    final double maxSlewDelta = ceiling * slewScale;
                    if (maxSlewDelta > 0.0)
                    {
                        final double delta = sample - slewPrev[ch];
                        if (Math.abs(delta) > maxSlewDelta)
                            sample = slewPrev[ch] + Math.copySign(maxSlewDelta, delta);
                    }
                    slewPrev[ch] = sample;

                    peakIn = stdMax(peakIn, Math.abs(driven));
                    peakOut = stdMax(peakOut, Math.abs(sample));
                    data[base + j] = sample;
                }
            }
        }
        storeGainReduction(peakIn, peakOut);
    }

    private void storeGainReduction(double peakIn, double peakOut)
    {
        if (peakIn > 1e-6)
        {
            final double ratio = stdMin(peakOut / peakIn, 1.0);
            gainReductionDb = ratio > 1e-5 ? 20.0 * Math.log10(ratio) : -100.0;
        }
        else
        {
            gainReductionDb = 0.0;
        }
    }

    /** The stateless transfer curve of a mode. */
    static double shape(Mode m, double sample, double ceiling)
    {
        switch (m)
        {
            case SOFT:
                return ceiling * Math.tanh(sample / ceiling);
            case ANALOG:
            {
                final double halfPi = Math.PI * 0.5;
                return ceiling * DspMath.fastSin(clamp(sample / ceiling, -halfPi, halfPi));
            }
            case GOLDEN_RATIO:
            {
                final double threshold = ceiling / PHI;
                final double absSample = Math.abs(sample);
                if (absSample <= threshold) return sample;
                final double sign = Math.copySign(1.0, sample);
                return sign * rationalKneeMagnitude(absSample, threshold, ceiling);
            }
            default:
                return clamp(sample, -ceiling, ceiling);
        }
    }

    /** Positive magnitude of the rational soft knee. */
    private static double rationalKneeMagnitude(double magnitude, double knee, double ceiling)
    {
        if (magnitude <= knee) return magnitude;
        final double excess = magnitude - knee;
        final double range = ceiling - knee;
        return knee + (range * excess) / (excess + range);
    }

    private static double dbToLinear(double dB)
    {
        return Math.pow(10.0, dB / 20.0);
    }

    /** The C++ std::clamp: lo when v is below it, hi when v is above it, else v. */
    private static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : (hi < v ? hi : v);
    }

    /** The C++ std::max: the first value unless the second is larger. */
    private static double stdMax(double a, double b)
    {
        return (a < b) ? b : a;
    }

    /** The C++ std::min: the first value unless the second is smaller. */
    private static double stdMin(double a, double b)
    {
        return (b < a) ? b : a;
    }
}
