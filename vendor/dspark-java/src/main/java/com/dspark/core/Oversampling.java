package com.dspark.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * Power-of-two oversampling with polyphase half-band filters: cascaded 2x
 * stages in one of two filter modes.
 * <ul>
 * <li><b>Linear-phase FIR (default).</b> Kaiser-windowed symmetric half-band
 *   filters, both directions as polyphase pairs. Upsampling: the even outputs
 *   are the FIR phase over the non-zero half-band taps, the odd outputs a pure
 *   delay through the centre tap, so zero-stuffed buffers are never
 *   materialised. Decimation: the high-rate stream is split into its even and
 *   odd samples; the even ones meet the non-zero taps and the odd ones the
 *   centre tap, at exactly the positions of the full high-rate convolution.
 *   Each stage after the first is shorter: its transition band only has to
 *   protect what the first stage keeps, so it is designed to be no worse than
 *   the first stage over that band. The round trip is an exact integer delay
 *   at every frequency. {@link Depth#DEEP} keeps a preset's band and makes
 *   every stage at least 100 dB deep, for nonlinear processing whose harmonics
 *   fill the oversampled band. {@link Band#AUDIBLE} fits the first stage to
 *   the host rate: it keeps 20 kHz whole and stops the image of 20 kHz, so the
 *   higher the rate the shorter the stage.</li>
 * <li><b>Polyphase IIR ({@link Mode#POLYPHASE_IIR}).</b> Elliptic half-band
 *   filters realised as two first-order allpass cascades in z^-2 (Valenzuela
 *   and Constantinides, IEE Proc. 1983), the coefficients designed here from
 *   the elliptic filter's theta series. The same or a deeper stopband than the
 *   FIR preset for 2 to 6 samples of round-trip delay instead of 16 to 141.
 *   The phase is NOT linear: the round-trip delay is a fractional,
 *   frequency-dependent number of samples ({@link #getRoundTripDelay}), so a
 *   parallel dry path cannot be aligned by an integer delay above the low
 *   audio range (see {@link OversamplingDryPath}). {@link #getLatency} reports
 *   it rounded at low frequencies.</li>
 * </ul>
 * Factors 1, 2, 4, 8 and 16 with four quality presets, two stopband depths
 * and two bands. Block-based streaming: variable block sizes give identical
 * output for any partition of the stream. No allocation after
 * {@link #prepare}.
 * <p>
 * Usage is a paired streaming operation per block: {@link #upsample} (base
 * rate to high rate, into the internal buffer), processing of the returned
 * high-rate buffer in place over {@link #getHighRateLength} samples, then
 * {@link #downsample} (high rate to base rate) with the same block length.
 * A source that generates at the high rate fills {@link #getHighRateBlock}
 * and calls {@link #downsample}; its delay is {@link #getDecimationDelay}
 * high-rate samples. Factor, quality, mode, depth and band are fixed at
 * construction.
 */
public final class Oversampling
{
    /**
     * Quality presets: the steepness and the rejection of the anti-aliasing
     * filter. The figures are the worst image or alias level of the whole
     * cascade, which the first stage sets (its stop edge); later stages are
     * designed to be no worse over the band that folds back into the first
     * stage's passband and transition. Linear-phase FIR taps of the first
     * stage are 31/63/127/255. In {@link Mode#POLYPHASE_IIR} the same
     * transition band gets at least the same depth (worst image or alias over
     * every factor, measured: Low -55, Medium -83, High -91, Maximum -107 dB).
     */
    public enum Quality
    {
        /** About -40 dB stopband. For fast previewing. */
        LOW,
        /** About -60 dB stopband. General purpose. */
        MEDIUM,
        /** About -80 dB stopband. Professional standard. */
        HIGH,
        /** About -100 dB stopband. Mastering grade. */
        MAXIMUM
    }

    /** Half-band filter family. */
    public enum Mode
    {
        /** Exact integer delay at every frequency (default). */
        LINEAR_PHASE_FIR,
        /** Elliptic allpass pairs: a few samples of nonlinear-phase delay. */
        POLYPHASE_IIR
    }

    /**
     * Stopband depth of the cascade.
     * <ul>
     * <li>{@link #PRESET} (default): the depth of the quality preset.</li>
     * <li>{@link #DEEP}: every stage at least 100 dB deep over the band it
     *   protects, with the preset's transition band (the same passband edge
     *   and stop edge). A nonlinear process at the high rate leaves harmonics
     *   across the whole band above the base Nyquist at close to their full
     *   level, and the preset's depth is then the floor of what folds back.
     *   Linear-phase FIR taps per stage (first stage first): Low 91/35/23/31,
     *   Medium 115/31/23/31, High 163/31/23/31 (with Preset 31/11/7/15,
     *   63/19/15/15 and 127/23/23/15). Round-trip latency at High: 82/90/93/95
     *   base samples at 2x/4x/8x/16x (Preset 64/70/73/74). Maximum already has
     *   that depth and is unchanged. In {@link Mode#POLYPHASE_IIR} every stage
     *   gets the coefficient count that reaches 100 dB over its band.</li>
     * </ul>
     */
    public enum Depth
    {
        /** The quality preset's depth (default). */
        PRESET,
        /** At least 100 dB in every stage, with the preset's band. */
        DEEP
    }

    /**
     * The band the first stage keeps.
     * <ul>
     * <li>{@link #PRESET} (default): the quality preset's own transition band,
     *   a fixed share of the host rate around its Nyquist frequency (High:
     *   from 0.460 to 0.540 of the rate), whatever the rate.</li>
     * <li>{@link #AUDIBLE}: the first stage keeps everything up to 20 kHz and
     *   stops everything from the image of 20 kHz (the host rate less 20 kHz)
     *   up, to the depth of the preset. The transition is then
     *   1/2 - 20 kHz / rate of the 2x rate: 0.046 at 44.1 kHz, 0.083 at
     *   48 kHz, 0.292 at 96 kHz and 0.396 at 192 kHz, against a fixed 0.040
     *   for High. {@link #prepare} takes the shortest kernel that measures
     *   the preset's depth over that stopband, so the stage gets shorter as
     *   the rate rises (where the preset's own band is the wider one, as Low
     *   and Medium are at 44.1 kHz, it stays). Later stages keep their
     *   designs. The latency is known after prepare() and stays a whole
     *   number of samples. In {@link Mode#POLYPHASE_IIR} the first stage gets
     *   the same band and the coefficient count that reaches the depth over
     *   it.</li>
     * </ul>
     * First stage of High, in taps, at 44.1, 48, 88.2, 96, 176.4 and 192 kHz:
     * 111, 63, 23, 19, 15 and 15 against 127, which at 2x is a latency of 56,
     * 32, 12, 10, 8 and 8 samples against 64. At 96 kHz Low, Medium and
     * Maximum take 7, 15 and 27 taps against 31, 63 and 255, and Depth DEEP 27
     * against 163.
     * <p>
     * What AUDIBLE gives up: content of the input between 20 kHz and its
     * Nyquist frequency is not carried whole to the high rate, and its images
     * are not removed whole; on the way down, what a process leaves between
     * the Nyquist frequency and the image of 20 kHz folds back above 20 kHz.
     * Nothing folds below 20 kHz that the preset would have stopped.
     */
    public enum Band
    {
        /** The preset's transition band (default). */
        PRESET,
        /** The first stage keeps 20 kHz and stops its image, by the host rate. */
        AUDIBLE
    }

    /** Most channels the engine processes. */
    public static final int MAX_CHANNELS = 16;

    private static final int MAX_STAGES = 4;

    /** Top of the band Band.AUDIBLE keeps whole, in Hz. */
    private static final double AUDIBLE_EDGE_HZ = 20000.0;
    /** Shortest kernel the fit gives (the shortest of the tables). */
    private static final int SHORTEST_FIT = 7;

    private final int factor;
    private final int numStages;
    private final Quality quality;
    private final Mode mode;
    private final Depth depth;
    private final Band band;
    private StageDesign firstStage;      // the first stage's design in use (Band.AUDIBLE: fitted at prepare())
    private double firstTransition;      // its transition, as a fraction of the 2x rate
    private int latency = 0;
    private int decimationDelay = 0;

    private double baseSampleRate = 0.0;
    private int baseMaxBlockSize = 0;
    private double[][] upBuffer = new double[0][];
    private int highRateLength = 0;

    private final PolyphaseHalfBand[] filters = new PolyphaseHalfBand[MAX_STAGES];
    private final PolyphaseIirHalfBand[] iir = new PolyphaseIirHalfBand[MAX_STAGES];

    /** A 2x engine, High quality, linear phase, preset depth and band. */
    public Oversampling()
    {
        this(2, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.PRESET);
    }

    /** @param factor  oversampling factor (see {@link #Oversampling(int, Quality, Mode, Depth, Band)}) */
    public Oversampling(int factor)
    {
        this(factor, Quality.HIGH, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.PRESET);
    }

    /**
     * @param factor   oversampling factor
     * @param quality  filter quality preset
     */
    public Oversampling(int factor, Quality quality)
    {
        this(factor, quality, Mode.LINEAR_PHASE_FIR, Depth.PRESET, Band.PRESET);
    }

    /**
     * @param factor   oversampling factor
     * @param quality  filter quality preset
     * @param mode     filter family
     */
    public Oversampling(int factor, Quality quality, Mode mode)
    {
        this(factor, quality, mode, Depth.PRESET, Band.PRESET);
    }

    /**
     * @param factor   oversampling factor
     * @param quality  filter quality preset
     * @param mode     filter family
     * @param depth    stopband depth
     */
    public Oversampling(int factor, Quality quality, Mode mode, Depth depth)
    {
        this(factor, quality, mode, depth, Band.PRESET);
    }

    /**
     * Constructs the oversampling engine.
     *
     * @param factor   oversampling factor, a power of two from 1 to 16; another
     *                 value is clamped into that range and rounded down to a
     *                 power of two
     * @param quality  filter quality preset (High is -80 dB)
     * @param mode     filter family (linear-phase FIR by default)
     * @param depth    stopband depth (the preset's by default)
     * @param band     band of the first stage (the preset's by default;
     *                 {@link Band#AUDIBLE} fits it to the host rate at
     *                 {@link #prepare})
     */
    public Oversampling(int factor, Quality quality, Mode mode, Depth depth, Band band)
    {
        this.quality = Objects.requireNonNull(quality, "quality");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.depth = Objects.requireNonNull(depth, "depth");
        this.band = Objects.requireNonNull(band, "band");
        this.firstStage = firStageDesign(quality, depth, 0);
        this.firstTransition = firTransition(quality);

        // Clamp into [1, 2^MAX_STAGES], derive the stage count and re-derive
        // the factor as 2^numStages, so the pair stays coherent.
        int f = Math.max(1, Math.min(factor, 1 << MAX_STAGES));
        int stages = 0;
        while ((1 << (stages + 1)) <= f)
            ++stages;
        this.numStages = stages;
        this.factor = 1 << stages;

        for (int s = 0; s < MAX_STAGES; s++)
        {
            filters[s] = new PolyphaseHalfBand();
            iir[s] = new PolyphaseIirHalfBand();
        }
        if (this.mode == Mode.POLYPHASE_IIR)
        {
            for (int s = 0; s < numStages; s++)
                iir[s].design(iirCoefficientCount(quality, depth, s), iirTransition(quality, s));
        }
        computeDelays();
    }

    /**
     * Allocates the buffers and computes the filter coefficients for a base
     * (1x) rate. With {@link Band#AUDIBLE} the first stage is fitted to the
     * rate here, so {@link #getLatency} and {@link #getDecimationDelay} are
     * those of this rate from now on (before the first prepare() they are the
     * preset's). The fit tries a few lengths and windows and measures each.
     *
     * @param sampleRate    base sample rate in Hz
     * @param numChannels   channels (up to {@link #MAX_CHANNELS} are processed)
     * @param maxBlockSize  largest base-rate block (a negative value counts as 0)
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize)
    {
        baseSampleRate = sampleRate;
        baseMaxBlockSize = Math.max(0, maxBlockSize);
        final int channels = Math.max(0, numChannels);
        upBuffer = new double[Math.min(channels, MAX_CHANNELS)][baseMaxBlockSize * factor];
        highRateLength = 0;
        fitFirstStage(sampleRate);

        for (int stage = 0; stage < numStages; stage++)
        {
            // The max block size entering a stage is baseSize * 2^stage.
            final int stageMaxSamples = baseMaxBlockSize * (1 << stage);
            if (mode == Mode.POLYPHASE_IIR)
            {
                iir[stage].allocate(channels, stageMaxSamples);
            }
            else
            {
                final StageDesign d = stageDesign(stage);
                filters[stage].design(d.taps, d.beta, channels, stageMaxSamples);
            }
        }

        reset();
    }

    /** Clears all delay lines and histories. */
    public void reset()
    {
        for (double[] ch : upBuffer) Arrays.fill(ch, 0.0);
        for (int i = 0; i < numStages; i++)
        {
            filters[i].reset();
            iir[i].reset();
        }
    }

    /**
     * The smallest factor that brings a host rate up to at least 176.4 kHz:
     * 4 at 44.1 and 48 kHz, 2 at 88.2 and 96 kHz and 1 from 176.4 kHz up.
     *
     * @param sampleRate  host rate in Hz
     * @return a power of two between 1 and 16
     */
    public static int factorForRate(double sampleRate)
    {
        return factorForRate(sampleRate, 176400.0, 1 << MAX_STAGES);
    }

    /**
     * The smallest factor that brings a host rate up to an internal rate.
     *
     * @param sampleRate           host rate in Hz
     * @param minimumInternalRate  internal rate to reach, in Hz
     * @return a power of two between 1 and 16
     */
    public static int factorForRate(double sampleRate, double minimumInternalRate)
    {
        return factorForRate(sampleRate, minimumInternalRate, 1 << MAX_STAGES);
    }

    /**
     * The smallest factor that brings a host rate up to an internal rate. A
     * nonlinear stage that always runs at about the same internal rate sounds
     * the same at every host rate and stops paying for oversampling the host
     * already provides.
     *
     * @param sampleRate           host rate in Hz; a rate that is not a
     *                             positive finite number gives 1
     * @param minimumInternalRate  internal rate to reach, in Hz
     * @param maxFactor            largest factor returned (at most 16)
     * @return a power of two between 1 and maxFactor
     */
    public static int factorForRate(double sampleRate, double minimumInternalRate, int maxFactor)
    {
        if (!(sampleRate > 0.0) || !(sampleRate < 1e12)) return 1;
        final int limit = maxFactor < 1 ? 1 : (maxFactor > (1 << MAX_STAGES) ? (1 << MAX_STAGES) : maxFactor);
        int f = 1;
        // A rate a rounding error short of an exact multiple still counts.
        while (f * 2 <= limit && sampleRate * f < minimumInternalRate * (1.0 - 1e-9))
            f *= 2;
        return f;
    }

    /** @return the oversampling factor */
    public int getFactor() { return factor; }

    /** @return the quality preset */
    public Quality getQuality() { return quality; }

    /** @return the half-band filter family */
    public Mode getMode() { return mode; }

    /** @return the stopband depth */
    public Depth getDepth() { return depth; }

    /** @return the band of the first stage */
    public Band getBand() { return band; }

    /**
     * Length of a linear-phase stage's kernel, in taps at its high rate. Half
     * of them (the odd ones from the centre) are the half-band's zeros and
     * cost nothing. With {@link Band#AUDIBLE} the first stage's length is that
     * of the rate of the last prepare().
     *
     * @param stage  stage, 0 the first (the one at the lowest rate)
     * @return the length, or 0 for a stage the engine does not have or in
     *         {@link Mode#POLYPHASE_IIR}
     */
    public int getStageLength(int stage)
    {
        if (mode != Mode.LINEAR_PHASE_FIR || stage < 0 || stage >= numStages) return 0;
        return stageDesign(stage).taps;
    }

    /**
     * Transition band of the first stage, as a fraction of the 2x rate: the
     * stage keeps everything under (1/4 - half of this) of the 2x rate and
     * stops everything over (1/4 + half of it). The preset's own figure, or
     * 1/2 - 20 kHz / rate with {@link Band#AUDIBLE} where that is wider.
     *
     * @return the transition width
     */
    public double getFirstStageTransition() { return firstTransition; }

    /**
     * Round-trip (upsample + downsample) delay at the base rate, in samples.
     * Linear-phase FIR: exact at every frequency; each stage s contributes its
     * centre-tap delay on the way up and down, (halfOrder_s + 1) / 2^s base
     * samples, and the stage lengths make every term an integer. Polyphase
     * IIR: the low-frequency round-trip delay rounded to the nearest sample
     * (see {@link #getRoundTripDelay}).
     *
     * @return the latency in base-rate samples
     */
    public int getLatency() { return latency; }

    /**
     * Exact round-trip delay at a frequency, in base-rate samples. Constant
     * ({@link #getLatency}) in the linear-phase mode. In
     * {@link Mode#POLYPHASE_IIR} it is the phase delay of the elliptic allpass
     * pairs, evaluated from the designed coefficients: for example 3.07
     * samples at 1 kHz rising to 4.23 samples at 18 kHz for 2x High at 48 kHz.
     * It evaluates the transfer functions; call it outside the audio path.
     *
     * @param frequencyHz  frequency in Hz at the base rate of prepare() (48 kHz
     *                     is assumed before prepare())
     * @return the delay in base-rate samples
     */
    public double getRoundTripDelay(double frequencyHz)
    {
        if (mode != Mode.POLYPHASE_IIR || numStages == 0)
            return (double) latency;
        final double fs = baseSampleRate > 0.0 ? baseSampleRate : 48000.0;
        final double w = 2.0 * Math.PI * DspMath.clamp(frequencyHz / fs, 1e-6, 0.4999);
        return iirDelay(w, true);
    }

    /**
     * Group delay of the decimation path alone, in HIGH-RATE samples: the
     * delay of a source rendered directly at the high rate through
     * {@link #getHighRateBlock} and {@link #downsample}. Linear-phase FIR:
     * every half-band stage delays by its centre tap, halfOrder_s samples at
     * its input rate, which is exact and generally fractional at the base
     * rate (31.5 samples at 2x High); a source that must report an integer
     * base-rate latency L starts its content L * getFactor() minus this value
     * high-rate samples later. Polyphase IIR: the low-frequency delay, rounded.
     *
     * @return the decimation delay in high-rate samples
     */
    public int getDecimationDelay() { return decimationDelay; }

    /**
     * Writable high-rate block for a source that renders directly at the
     * oversampled rate: the decimation-only counterpart of {@link #upsample}.
     * Fill {@link #getHighRateLength} samples of every channel of the returned
     * buffer, then call {@link #downsample} with the same base block length.
     *
     * @param numSamples  base-rate block length, clamped to prepare()'s maxBlockSize
     * @return the internal high-rate buffer, one array per prepared channel
     */
    public double[][] getHighRateBlock(int numSamples)
    {
        final int nS = Math.max(0, Math.min(numSamples, baseMaxBlockSize));
        highRateLength = nS * factor;
        return upBuffer;
    }

    /**
     * Number of valid high-rate samples per channel in the buffer the last
     * {@link #upsample} or {@link #getHighRateBlock} returned: the base block
     * length (clamped to prepare()'s maxBlockSize) times the factor.
     *
     * @return the high-rate length
     */
    public int getHighRateLength() { return highRateLength; }

    /** {@link #upsample(double[][], int, int)} from sample 0. */
    public double[][] upsample(double[][] input, int numSamples)
    {
        return upsample(input, 0, numSamples);
    }

    /**
     * Upsamples a base-rate block into the internal high-rate buffer. Blocks
     * longer than prepare()'s maxBlockSize are truncated; channels beyond the
     * prepared count are ignored. Pair every call with one
     * {@link #downsample} of the same block length before the next one.
     *
     * @param input       base-rate channels (read only)
     * @param offset      first sample of the block in every channel
     * @param numSamples  base-rate block length
     * @return the internal high-rate buffer, valid over {@link #getHighRateLength} samples
     */
    public double[][] upsample(double[][] input, int offset, int numSamples)
    {
        final int nCh = Math.min(input.length, upBuffer.length);
        final int nS = Math.max(0, Math.min(numSamples, baseMaxBlockSize));
        highRateLength = nS * factor;

        if (numStages == 0)
        {
            for (int ch = 0; ch < nCh; ch++)
                System.arraycopy(input[ch], offset, upBuffer[ch], 0, nS);
            return upBuffer;
        }

        if (mode == Mode.POLYPHASE_IIR)
        {
            int len = nS;
            for (int stage = 0; stage < numStages; stage++)
            {
                for (int ch = 0; ch < nCh; ch++)
                {
                    if (stage == 0)
                        iir[0].upsampleChannel(input[ch], offset, upBuffer[ch], ch, len);
                    else
                        iir[stage].upsampleChannel(upBuffer[ch], 0, upBuffer[ch], ch, len);
                }
                len *= 2;
            }
            return upBuffer;
        }

        // Stage 0 reads the input directly.
        filters[0].processUpsample(input, offset, upBuffer, nCh, nS);

        // Later stages upsample in place inside the high-rate buffer.
        int currentLen = nS * 2;
        for (int stage = 1; stage < numStages; stage++)
        {
            filters[stage].processUpsampleInPlace(upBuffer, nCh, currentLen);
            currentLen *= 2;
        }
        return upBuffer;
    }

    /** {@link #downsample(double[][], int, int)} from sample 0. */
    public void downsample(double[][] output, int numSamples)
    {
        downsample(output, 0, numSamples);
    }

    /**
     * Downsamples the internal high-rate buffer back to the base rate: it
     * consumes the high-rate samples the most recent {@link #upsample} wrote
     * (and the caller modified in place in between), or the ones the caller
     * wrote into {@link #getHighRateBlock}. No gain compensation is needed:
     * the in-band sine gain is already unity to better than 0.01 dB.
     *
     * @param output      base-rate channels written
     * @param offset      first sample of the block in every channel
     * @param numSamples  base-rate block length, the same as the paired upsample()
     */
    public void downsample(double[][] output, int offset, int numSamples)
    {
        final int nCh = Math.min(output.length, upBuffer.length);
        final int nS = Math.max(0, Math.min(numSamples, baseMaxBlockSize));

        if (numStages == 0)
        {
            for (int ch = 0; ch < nCh; ch++)
                System.arraycopy(upBuffer[ch], 0, output[ch], offset, nS);
            return;
        }

        if (mode == Mode.POLYPHASE_IIR)
        {
            int len = nS * factor;
            for (int stage = numStages - 1; stage >= 0; stage--)
            {
                for (int ch = 0; ch < nCh; ch++)
                {
                    if (stage == 0)
                        iir[0].downsampleChannel(upBuffer[ch], output[ch], offset, ch, len);
                    else
                        iir[stage].downsampleChannel(upBuffer[ch], upBuffer[ch], 0, ch, len);
                }
                len /= 2;
            }
            return;
        }

        int currentLen = nS * factor;
        for (int stage = numStages - 1; stage > 0; stage--)
        {
            filters[stage].processDownsampleInPlace(upBuffer, nCh, currentLen);
            currentLen /= 2;
        }

        // Stage 0 writes the output directly.
        filters[0].processDownsample(upBuffer, output, offset, nCh, currentLen);
    }

    // ------------------------------------------------------------------------
    // Polyphase half-band FIR
    // ------------------------------------------------------------------------

    /** One 2x stage as a polyphase half-band FIR pair. */
    private static final class PolyphaseHalfBand
    {
        double[] evenTaps = new double[0];   // the non-zero half-band taps (the even array indices)
        double centerTap = 0.0;
        int halfOrder = 0;
        int delaySamples = 0;

        double[][] history = new double[0][];    // upsampler: base-rate input with its FIR prefix
        double[][] evenHist = new double[0][];   // decimator: even high-rate samples with prefix
        double[][] oddHist = new double[0][];    // decimator: odd high-rate samples with prefix
        double[] scratch = new double[0];        // FIR-phase outputs of one call

        /**
         * Designs the half-band filter and allocates the histories.
         *
         * @param taps             total filter length ((taps - 1) / 2 odd)
         * @param beta             Kaiser window beta
         * @param numChannels      channels
         * @param maxBlockSamples  largest number of input samples of the upsampler in one call
         */
        void design(int taps, double beta, int numChannels, int maxBlockSamples)
        {
            halfOrder = (taps - 1) / 2;
            // The array-even taps are the non-zero (odd-from-centre) half-band
            // taps only when halfOrder is odd, which every design keeps. The
            // centre sample sits (halfOrder + 1) / 2 samples into the window
            // to stay phase aligned with the even phase.
            delaySamples = (halfOrder + 1) / 2;

            final double[] fullCoeffs = FIRFilter.FIRDesign.lowPass(1.0, 0.25, taps, beta);
            centerTap = fullCoeffs[halfOrder];

            // The non-zero taps: every even array index (the centre sits at the
            // odd index halfOrder, the other odd indices are the zeros).
            evenTaps = new double[(fullCoeffs.length + 1) / 2];
            for (int i = 0, j = 0; i < fullCoeffs.length; i += 2, j++)
                evenTaps[j] = fullCoeffs[i];

            // Up history: base-rate (numTaps + block). Down histories:
            // halfOrder samples of prefix plus up to maxBlockSamples new
            // samples per phase.
            final int upHistorySize = evenTaps.length + maxBlockSamples;
            final int downHistorySize = halfOrder + maxBlockSamples;
            history = new double[numChannels][upHistorySize];
            evenHist = new double[numChannels][downHistorySize];
            oddHist = new double[numChannels][downHistorySize];
            scratch = new double[maxBlockSamples];
        }

        void reset()
        {
            for (double[] h : history) Arrays.fill(h, 0.0);
            for (double[] h : evenHist) Arrays.fill(h, 0.0);
            for (double[] h : oddHist) Arrays.fill(h, 0.0);
        }

        /**
         * One channel of the 2x upsampler: dst[2i] is the FIR phase, dst[2i + 1]
         * the centre-tap delay. src may be dst (the history copy is what the
         * phases read).
         */
        void upsampleChannel(double[] src, int srcOff, double[] dst, double[] hist, int n)
        {
            final int numTaps = evenTaps.length;

            // Append the new block after the fixed numTaps-sample history prefix.
            System.arraycopy(src, srcOff, hist, numTaps, n);

            FIRFilter.firCorrelate(hist, 0, evenTaps, 0, numTaps, scratch, 0, n, 2.0);

            final int centre = numTaps - delaySamples;
            final double centre2 = centerTap * 2.0;
            for (int i = 0; i < n; i++)
            {
                dst[2 * i] = scratch[i];
                dst[2 * i + 1] = hist[centre + i] * centre2;
            }

            // Keep the trailing numTaps input samples for the next block.
            System.arraycopy(hist, n, hist, 0, numTaps);
        }

        /**
         * One channel of the 2x decimator over len high-rate samples:
         * dst[k] = sum_i h[i] x[2k + i] of the full kernel, as the non-zero taps
         * over the even samples plus the centre tap on the odd ones. dst may be
         * src.
         */
        void downsampleChannel(double[] src, double[] dst, int dstOff, int ch, int len)
        {
            final int outLen = len / 2;
            final int numTaps = evenTaps.length;
            final double[] even = evenHist[ch];
            final double[] odd = oddHist[ch];

            // Split the new samples after the halfOrder-sample prefixes.
            for (int m = 0; m < outLen; m++)
            {
                even[halfOrder + m] = src[2 * m];
                odd[halfOrder + m] = src[2 * m + 1];
            }

            FIRFilter.firCorrelate(even, 0, evenTaps, 0, numTaps, dst, dstOff, outLen, 1.0);

            // The centre tap meets x[2k + halfOrder] = odd[k + (halfOrder - 1) / 2].
            final int centre = (halfOrder - 1) / 2;
            final double c = centerTap;
            for (int k = 0; k < outLen; k++)
                dst[dstOff + k] = c * odd[centre + k] + dst[dstOff + k];

            System.arraycopy(even, outLen, even, 0, halfOrder);
            System.arraycopy(odd, outLen, odd, 0, halfOrder);
        }

        void processUpsample(double[][] input, int offset, double[][] output, int nCh, int nS)
        {
            for (int ch = 0; ch < nCh; ch++)
                upsampleChannel(input[ch], offset, output[ch], history[ch], nS);
        }

        void processUpsampleInPlace(double[][] buffer, int nCh, int currentLen)
        {
            for (int ch = 0; ch < nCh; ch++)
                upsampleChannel(buffer[ch], 0, buffer[ch], history[ch], currentLen);
        }

        void processDownsample(double[][] input, double[][] output, int offset, int nCh, int currentLen)
        {
            for (int ch = 0; ch < nCh; ch++)
                downsampleChannel(input[ch], output[ch], offset, ch, currentLen);
        }

        void processDownsampleInPlace(double[][] buffer, int nCh, int currentLen)
        {
            for (int ch = 0; ch < nCh; ch++)
                downsampleChannel(buffer[ch], buffer[ch], 0, ch, currentLen);
        }
    }

    // ------------------------------------------------------------------------
    // Polyphase IIR half-band (elliptic allpass pair)
    // ------------------------------------------------------------------------

    /**
     * 2x elliptic half-band as H(z) = (A0(z^2) + z^-1 A1(z^2)) / 2. Each branch
     * is a cascade of first-order sections (a + z^-1)/(1 + a z^-1) at the low
     * rate. Upsampling feeds both branches the same input and interleaves
     * their outputs (A0 to the even sample); decimation feeds the odd input
     * sample to A0 and the even one to A1 and averages, which keeps the
     * full-filter output at odd high-rate instants: one high-rate sample
     * earlier than the even instants, with the same alias rejection.
     */
    private static final class PolyphaseIirHalfBand
    {
        static final int MAX_PAIRS = 8;   // up to 16 coefficients per stage

        int numPairs = 0;
        final double[] coef = new double[2 * MAX_PAIRS];   // [A0 k, A1 k] interleaved

        // Per channel: the previous input pair of the cascade followed by each
        // section's previous output pair. A section's previous input is the
        // previous output of the section before it.
        double[][] up = new double[0][];
        double[][] down = new double[0][];
        double[] scratch = new double[0];

        /**
         * Designs numCoefs (even) allpass coefficients of an elliptic half-band
         * with the given transition width: the full width of the transition
         * band as a fraction of this stage's OUTPUT rate (centred on a quarter
         * of it). From the passband edge fp = 1/4 - transition/2 the bilinear
         * selectivity is k = tan^2(pi fp) and the nome q follows from the
         * modulus k' = sqrt(1 - k^2) by its standard series. The half-band
         * elliptic filter of odd order n = 2N + 1 has a real pole at s = -1 and
         * N pole pairs on the unit circle of the analog plane; with the pole
         * frequencies Omega_i from the theta-function series (Antoniou,
         * "Digital Filters", sec. 5.5) the real part of pole i is
         * sigma_i = sqrt((1 - k Omega_i^2)(1 - Omega_i^2 / k)) / (1 + Omega_i^2),
         * and the bilinear transform maps it to the z^-2 allpass coefficient
         * a_i = (1 - sigma_i) / (1 + sigma_i). Sorted ascending, the
         * coefficients alternate between the two branches. Stopband depth:
         * 10 log10(1 + q^(-n/2) / 4) dB.
         */
        void design(int numCoefs, double transition)
        {
            numCoefs = Math.max(2, Math.min(numCoefs & ~1, 2 * MAX_PAIRS));
            numPairs = numCoefs / 2;
            final double fp = 0.25 - 0.5 * DspMath.clamp(transition, 1e-4, 0.4999);
            final double k = Math.tan(Math.PI * fp) * Math.tan(Math.PI * fp);
            final double q = nome(k);
            final int order = 2 * numCoefs + 1;
            final double[] a = new double[2 * MAX_PAIRS];
            for (int i = 1; i <= numCoefs; i++)
            {
                double num = 0.0, den = 0.0;
                for (int m = 0; m < 20; m++)
                {
                    final double sign = ((m & 1) != 0) ? -1.0 : 1.0;
                    num += sign * Math.pow(q, m * (m + 1.0)) * Math.sin((2 * m + 1) * Math.PI * i / order);
                    if (m > 0) den += sign * Math.pow(q, (double) m * m) * Math.cos(2 * m * Math.PI * i / order);
                }
                final double w = 2.0 * Math.pow(q, 0.25) * num / (1.0 + 2.0 * den);
                final double w2 = w * w;
                final double r = (1.0 - k * w2) * (1.0 - w2 / k);
                final double sigma = Math.sqrt(0.0 < r ? r : 0.0) / (1.0 + w2);
                a[i - 1] = (1.0 - sigma) / (1.0 + sigma);
            }
            Arrays.sort(a, 0, numCoefs);
            for (int i = 0; i < numCoefs; i++)
                coef[i] = a[i];   // even -> A0, odd -> A1
        }

        /** Nome of the elliptic modulus k (k < 1). */
        static double nome(double k)
        {
            final double kp = Math.sqrt(1.0 - k * k);
            final double e = 0.5 * (1.0 - Math.sqrt(kp)) / (1.0 + Math.sqrt(kp));
            final double e4 = e * e * e * e;
            return e * (1.0 + e4 * (2.0 + e4 * (15.0 + 150.0 * e4)));
        }

        /** Stopband depth in dB of numCoefs coefficients at a transition width. */
        static double attenuationDb(int numCoefs, double transition)
        {
            final double fp = 0.25 - 0.5 * transition;
            final double q = nome(Math.tan(Math.PI * fp) * Math.tan(Math.PI * fp));
            return 10.0 * Math.log10(1.0 + Math.pow(q, -(2.0 * numCoefs + 1.0) / 2.0) / 4.0);
        }

        /**
         * Phase (argument) of the frequency response H at w radians per sample
         * of the stage output rate.
         */
        double responsePhase(double w)
        {
            final double zr = Math.cos(-2.0 * w), zi = Math.sin(-2.0 * w);
            double a0r = 1.0, a0i = 0.0, a1r = 1.0, a1i = 0.0;
            for (int k = 0; k < numPairs; k++)
            {
                final double c0 = coef[2 * k];
                final double c1 = coef[2 * k + 1];
                // a0 *= (c0 + z2) / (1 + c0 z2), a1 likewise with c1.
                double qr = complexDivRe(c0 + zr, zi, 1.0 + c0 * zr, c0 * zi);
                double qi = complexDivIm(c0 + zr, zi, 1.0 + c0 * zr, c0 * zi);
                double nr = a0r * qr - a0i * qi;
                double ni = a0r * qi + a0i * qr;
                a0r = nr;
                a0i = ni;
                qr = complexDivRe(c1 + zr, zi, 1.0 + c1 * zr, c1 * zi);
                qi = complexDivIm(c1 + zr, zi, 1.0 + c1 * zr, c1 * zi);
                nr = a1r * qr - a1i * qi;
                ni = a1r * qi + a1i * qr;
                a1r = nr;
                a1i = ni;
            }
            final double pr = Math.cos(-w), pi = Math.sin(-w);
            final double br = pr * a1r - pi * a1i;
            final double bi = pr * a1i + pi * a1r;
            final double hr = 0.5 * (a0r + br);
            final double hi = 0.5 * (a0i + bi);
            return Math.atan2(hi, hr);
        }

        /** Real part of (a + ib) / (c + id), by Smith's scaled division as the C++ complex library does. */
        private static double complexDivRe(double a, double b, double c, double d)
        {
            if (Math.abs(d) < Math.abs(c))
            {
                final double r = d / c, den = c + r * d;
                return (a + b * r) / den;
            }
            final double r = c / d, den = d + r * c;
            return (a * r + b) / den;
        }

        /** Imaginary part of (a + ib) / (c + id), by Smith's scaled division. */
        private static double complexDivIm(double a, double b, double c, double d)
        {
            if (Math.abs(d) < Math.abs(c))
            {
                final double r = d / c, den = c + r * d;
                return (b - a * r) / den;
            }
            final double r = c / d, den = d + r * c;
            return (b * r - a) / den;
        }

        void allocate(int numChannels, int maxInputSamples)
        {
            final int n = Math.max(0, numChannels);
            up = new double[n][2 * (MAX_PAIRS + 1)];
            down = new double[n][2 * (MAX_PAIRS + 1)];
            scratch = new double[Math.max(0, maxInputSamples)];
        }

        void reset()
        {
            for (double[] s : up) Arrays.fill(s, 0.0);
            for (double[] s : down) Arrays.fill(s, 0.0);
        }

        /**
         * A block through both branches. Up: both branches take x[i], dst[2i]
         * and dst[2i + 1] receive A0 and A1. Down: A0 takes x[2k + 1], A1 x[2k],
         * dst[k] their mean. A NaN or Inf that reached the recursion is cleared
         * at the end of the block, so a single bad sample cannot silence the
         * stream.
         */
        private void runBlock(boolean isUp, double[] st, double[] src, int srcOff, double[] dst, int dstOff, int n)
        {
            final int pairs = numPairs;
            final double[] c = coef;
            for (int i = 0; i < n; i++)
            {
                double inA, inB;
                if (isUp)
                {
                    final double x = src[srcOff + i];
                    inA = x;
                    inB = x;
                }
                else
                {
                    inA = src[2 * i + 1];
                    inB = src[2 * i];
                }
                double xPrevA = st[0], xPrevB = st[1];
                st[0] = inA;
                st[1] = inB;
                for (int k = 0; k < pairs; k++)
                {
                    final int s = 2 * (k + 1);
                    final double yOldA = st[s], yOldB = st[s + 1];
                    final double yA = c[2 * k] * (inA - yOldA) + xPrevA;
                    final double yB = c[2 * k + 1] * (inB - yOldB) + xPrevB;
                    xPrevA = yOldA;
                    xPrevB = yOldB;
                    st[s] = yA;
                    st[s + 1] = yB;
                    inA = yA;
                    inB = yB;
                }
                if (isUp)
                {
                    dst[dstOff + 2 * i] = inA;
                    dst[dstOff + 2 * i + 1] = inB;
                }
                else
                {
                    dst[dstOff + i] = 0.5 * (inA + inB);
                }
            }
            double sum = st[0] + st[1];
            for (int k = 0; k < pairs; k++)
                sum += st[2 * (k + 1)] + st[2 * (k + 1) + 1];
            if (!Double.isFinite(sum)) Arrays.fill(st, 0.0);
        }

        /** dst[2i] = A0(x)[i], dst[2i + 1] = A1(x)[i]. src may be dst. */
        void upsampleChannel(double[] src, int srcOff, double[] dst, int ch, int n)
        {
            if (ch >= up.length) return;
            if (src == dst && srcOff == 0)
            {
                System.arraycopy(src, 0, scratch, 0, n);
                src = scratch;
            }
            runBlock(true, up[ch], src, srcOff, dst, 0, n);
        }

        /** dst[k] = (A0(x[2k + 1]) + A1(x[2k])) / 2. dst may be src. */
        void downsampleChannel(double[] src, double[] dst, int dstOff, int ch, int len)
        {
            if (ch >= down.length) return;
            runBlock(false, down[ch], src, 0, dst, dstOff, len / 2);
        }
    }

    // ------------------------------------------------------------------------
    // Stage designs
    // ------------------------------------------------------------------------

    static final class StageDesign
    {
        final int taps;
        final double beta;

        StageDesign(int taps, double beta)
        {
            this.taps = taps;
            this.beta = beta;
        }
    }

    private static StageDesign[] table(int t0, double b0, int t1, double b1, int t2, double b2, int t3, double b3)
    {
        return new StageDesign[] { new StageDesign(t0, b0), new StageDesign(t1, b1),
                                   new StageDesign(t2, b2), new StageDesign(t3, b3) };
    }

    // Linear-phase stage designs. Stage 0 sets the preset's stop edge and
    // depth. A later stage s only has to stop what folds back into the band
    // stage 0 keeps (up to its stop edge), so it gets the shortest kernel and
    // Kaiser beta whose stopband over that band is no worse than stage 0's
    // own, with (halfOrder + 1) a multiple of 2^s so the round-trip delay
    // stays an exact integer. Stopband peaks (stage 0 / 1 / 2 / 3), dB: Low
    // -38.9/-43.7/-44.8/-91.9, Medium -59.5/-64.0/-78.0/-92.5, High
    // -79.2/-80.3/-122.9/-93.1, Maximum -100.1/-109.4/-124.1/-159.3.
    // Depth.DEEP keeps each preset's stop edge and takes, per stage, the
    // shortest kernel (same delay rule) reaching -100.1 dB over the same band:
    // Low -104.0/-111.3/-117.7/-159.0, Medium -103.6/-102.2/-119.8/-159.4,
    // High -100.7/-106.3/-122.3/-160.1.
    private static final StageDesign[] LOW_TABLE = table(31, 3.395, 11, 2.90, 7, 2.35, 15, 9.45);
    private static final StageDesign[] MEDIUM_TABLE = table(63, 5.653, 19, 6.25, 15, 7.90, 15, 9.50);
    private static final StageDesign[] HIGH_TABLE = table(127, 7.857, 23, 7.95, 23, 12.55, 15, 9.50);
    private static final StageDesign[] MAXIMUM_TABLE = table(255, 10.056, 31, 11.20, 23, 12.70, 31, 15.65);
    private static final StageDesign[] LOW_DEEP_TABLE = table(91, 10.45, 35, 11.35, 23, 12.30, 31, 15.65);
    private static final StageDesign[] MEDIUM_DEEP_TABLE = table(115, 10.45, 31, 10.40, 23, 12.40, 31, 15.65);
    private static final StageDesign[] HIGH_DEEP_TABLE = table(163, 10.10, 31, 10.85, 23, 12.55, 31, 15.65);

    static StageDesign firStageDesign(Quality q, Depth depth, int stage)
    {
        final int s = Math.max(0, Math.min(stage, MAX_STAGES - 1));
        final boolean deep = depth == Depth.DEEP;
        switch (q)
        {
            case LOW:     return deep ? LOW_DEEP_TABLE[s] : LOW_TABLE[s];
            case MEDIUM:  return deep ? MEDIUM_DEEP_TABLE[s] : MEDIUM_TABLE[s];
            case HIGH:    return deep ? HIGH_DEEP_TABLE[s] : HIGH_TABLE[s];
            case MAXIMUM: return MAXIMUM_TABLE[s];
            default:      return HIGH_TABLE[s];
        }
    }

    /** The design a linear-phase stage runs: the table's, or for the first stage the fitted one. */
    private StageDesign stageDesign(int stage)
    {
        return stage == 0 ? firstStage : firStageDesign(quality, depth, stage);
    }

    /** Depth of a preset's first stage, measured, in dB (Depth.DEEP: Maximum's). */
    static double firstStageDepthDb(Quality q, Depth depth)
    {
        if (depth == Depth.DEEP) return 100.1;
        switch (q)
        {
            case LOW:     return 38.9;
            case MEDIUM:  return 59.5;
            case HIGH:    return 79.2;
            case MAXIMUM: return 100.1;
            default:      return 79.2;
        }
    }

    /** Kaiser's window parameter for a stopband depth in dB (his estimate). */
    private static double kaiserBeta(double depthDb)
    {
        if (depthDb > 50.0) return 0.1102 * (depthDb - 8.7);
        if (depthDb > 21.0) return 0.5842 * Math.pow(depthDb - 21.0, 0.4) + 0.07886 * (depthDb - 21.0);
        return 0.0;
    }

    /**
     * Largest response of a half-band kernel over its stopband, in dB: from
     * {@code edge} (a fraction of the 2x rate) to its Nyquist frequency, on a
     * grid of sixteen points to the lobe. It reads the kernel the stage would
     * run: the centre tap and the taps at odd distances from it, which pair up
     * as cosines; the cosines of the odd multiples come from one another by
     * the Chebyshev recurrence.
     */
    static double halfBandStopPeakDb(int taps, double beta, double edge)
    {
        final double[] full = FIRFilter.FIRDesign.lowPass(1.0, 0.25, taps, beta);
        final int half = (taps - 1) / 2;
        final int points = Math.max(64, 4 * taps);
        double peak = 0.0;
        for (int p = 0; p <= points; p++)
        {
            final double f = edge + (0.5 - edge) * (double) p / (double) points;
            final double theta = 2.0 * Math.PI * f;
            final double twice = 2.0 * Math.cos(2.0 * theta);
            double before = Math.cos(theta), now = before;   // cos(-theta), cos(theta)
            double sum = full[half];
            for (int k = 1; k <= half; k += 2)
            {
                sum += (full[half + k] + full[half - k]) * now;
                final double next = twice * now - before;       // cos((k + 2) theta)
                before = now;
                now = next;
            }
            final double a = Math.abs(sum);
            if (peak < a) peak = a;
        }
        return 20.0 * Math.log10(peak < 1e-12 ? 1e-12 : peak);
    }

    /**
     * The shortest half-band kernel (half order odd, so the stage's delay is a
     * whole number of base samples) whose stopband over a transition measures
     * {@code depthDb} or deeper, with the Kaiser window that goes deepest at
     * that length: lengths from two steps under Kaiser's estimate, and for
     * each the window parameter in steps of 0.25 around his and then of 0.05
     * around the best of those. {@code otherwise} if none shorter than it does.
     */
    private static StageDesign fitHalfBand(double transition, double depthDb, StageDesign otherwise)
    {
        final double edge = 0.25 + 0.5 * transition;
        final double estimate = (depthDb - 7.95) / (14.36 * transition) + 1.0;
        int taps = 4 * (int) (estimate / 4.0) + 3 - 8;
        if (taps < SHORTEST_FIT) taps = SHORTEST_FIT;
        final double centre = kaiserBeta(depthDb);
        for (; taps < otherwise.taps; taps += 4)
        {
            double bestBeta = centre, bestPeak = halfBandStopPeakDb(taps, centre, edge);
            for (int i = -8; i <= 16; i++)
            {
                final double beta = centre + 0.25 * (double) i;
                if (i == 0 || beta < 0.5) continue;
                final double peak = halfBandStopPeakDb(taps, beta, edge);
                if (peak < bestPeak) { bestPeak = peak; bestBeta = beta; }
            }
            final double around = bestBeta;
            for (int i = -4; i <= 4; i++)
            {
                final double beta = around + 0.05 * (double) i;
                if (i == 0 || beta < 0.5) continue;
                final double peak = halfBandStopPeakDb(taps, beta, edge);
                if (peak < bestPeak) { bestPeak = peak; bestBeta = beta; }
            }
            if (bestPeak <= -depthDb) return new StageDesign(taps, bestBeta);
        }
        return otherwise;
    }

    /**
     * The first stage for a host rate. Band.PRESET: the table's. Band.AUDIBLE:
     * the transition from 20 kHz to its image, rate - 20 kHz, is
     * 1/2 - 20 kHz / rate of the 2x rate; where that is wider than the
     * preset's the stage is fitted to it (a rate that is not a positive
     * finite number keeps the preset). Sets the delays the engine reports.
     */
    private void fitFirstStage(double sampleRate)
    {
        firstStage = firStageDesign(quality, depth, 0);
        firstTransition = firTransition(quality);
        if (band != Band.AUDIBLE || numStages == 0) return;

        if (sampleRate > 2.0 * AUDIBLE_EDGE_HZ && sampleRate < 1e12)
        {
            final double wide = 0.5 - AUDIBLE_EDGE_HZ / sampleRate;
            if (wide > firstTransition)
            {
                firstTransition = wide;
                if (mode == Mode.LINEAR_PHASE_FIR)
                    firstStage = fitHalfBand(wide, firstStageDepthDb(quality, depth), firstStage);
            }
        }
        if (mode == Mode.POLYPHASE_IIR)
            iir[0].design(iirCoefficientCountOver(firstStageDepthDb(quality, depth), firstTransition),
                          firstTransition);
        computeDelays();
    }

    /**
     * Stage-0 transition of the FIR preset (Kaiser's estimate), as a fraction
     * of the 2x rate; the IIR stages reuse it so both modes keep the same band.
     */
    static double firTransition(Quality q)
    {
        switch (q)
        {
            case LOW:     return (40.0 - 7.95) / (14.36 * 30.0);
            case MEDIUM:  return (60.0 - 7.95) / (14.36 * 62.0);
            case HIGH:    return (80.0 - 7.95) / (14.36 * 126.0);
            case MAXIMUM: return (100.0 - 7.95) / (14.36 * 254.0);
            default:      return (80.0 - 7.95) / (14.36 * 126.0);
        }
    }

    /**
     * IIR stage s transition: stage 0 as the FIR preset; later stages pass up
     * to stage 0's stop edge, (1/2 + t0) of the base rate, and stop its image.
     */
    static double iirTransition(Quality q, int stage)
    {
        final double t0 = firTransition(q);
        return stage == 0 ? t0 : 0.5 - (1.0 + 2.0 * t0) / (double) (1 << (stage + 1));
    }

    /**
     * Smallest even coefficient count at least as deep as the FIR preset's
     * first stage (stage-0 depths: Low 62, Medium 84, High 99, Maximum 109 dB),
     * or as Maximum's with Depth.DEEP.
     */
    private static int iirCoefficientCount(Quality q, Depth depth, int stage)
    {
        return iirCoefficientCountOver(firstStageDepthDb(q, depth), iirTransition(q, stage));
    }

    /** Smallest even coefficient count that reaches a depth over a transition. */
    static int iirCoefficientCountOver(double depthDb, double transition)
    {
        int n = 2;
        while (n < 2 * PolyphaseIirHalfBand.MAX_PAIRS
               && PolyphaseIirHalfBand.attenuationDb(n, transition) < depthDb)
            n += 2;
        return n;
    }

    /** Stopband depth in dB of an IIR stage of numCoefs coefficients over a transition. */
    static double iirAttenuationDb(int numCoefs, double transition)
    {
        return PolyphaseIirHalfBand.attenuationDb(numCoefs, transition);
    }

    /**
     * Low-frequency or round-trip phase delay of the IIR cascade, in base
     * samples. The phase is unwrapped along a frequency sweep from near DC.
     */
    private double iirDelay(double w, boolean roundTrip)
    {
        final int steps = 512;
        double total = 0.0;
        for (int s = 0; s < numStages; s++)
        {
            final double scale = 1.0 / (double) (1 << (s + 1));
            double prev = 0.0, unwrapped = 0.0;
            for (int i = 1; i <= steps; i++)
            {
                final double wi = w * scale * (double) i / steps;
                final double ph = iir[s].responsePhase(wi);
                double d = ph - prev;
                while (d > Math.PI) d -= 2.0 * Math.PI;
                while (d < -Math.PI) d += 2.0 * Math.PI;
                unwrapped += d;
                prev = ph;
            }
            // The down path keeps the odd instant: one high-rate sample of advance.
            final double downPath = unwrapped + w * scale;
            total += roundTrip ? (unwrapped + downPath) : downPath;
        }
        return -total / w;
    }

    private void computeDelays()
    {
        if (numStages == 0) { latency = 0; decimationDelay = 0; return; }
        if (mode == Mode.POLYPHASE_IIR)
        {
            final double w = 1e-4;
            latency = (int) lround(iirDelay(w, true));
            decimationDelay = (int) lround(iirDelay(w, false) * factor);
            return;
        }
        int lat = 0, decimation = 0;
        for (int s = 0; s < numStages; s++)
        {
            final int halfOrder = (stageDesign(s).taps - 1) / 2;
            lat += (halfOrder + 1) / (1 << s);                  // exact by design
            decimation += halfOrder * (factor >> (s + 1));
        }
        latency = lat;
        decimationDelay = decimation;
    }

    /** Rounds half away from zero. */
    private static long lround(double x)
    {
        final double a = Math.abs(x);
        double r = Math.floor(a);
        if (a - r >= 0.5) r += 1.0;
        return (long) (x < 0.0 ? -r : r);
    }
}
