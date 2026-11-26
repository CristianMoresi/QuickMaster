package com.dspark.effects;

import com.dspark.core.BiquadCascade;
import com.dspark.core.BiquadCoeffs;
import com.dspark.core.BiquadDesign;
import com.dspark.core.FFTRealDoubleStepped;
import com.dspark.core.NormalizedLadder;
import com.dspark.core.SmoothedParameter;

import java.util.Arrays;

/**
 * Linkwitz-Riley crossover filter for multi-band audio processing: 2 to
 * maxBands bands (12 by default) at 6, 12, 24, 36 or 48 dB per octave.
 * <p>
 * Supports five slopes (6 dB per octave, LR12, LR24, LR36 and LR48) and two
 * processing modes: minimum-phase IIR with allpass phase correction, and
 * linear-phase FFT-based processing with zero phase distortion. Its
 * {@link #process} writes separate caller-owned band buffers rather than
 * replacing the input with one band. Non-finite input samples enter the
 * filters as silence (they would latch in the IIR state). Sum
 * {@link #getLatency()} once for the split, not once for each parallel band.
 * <p>
 * Linkwitz-Riley crossovers guarantee that all bands sum to an allpass (flat
 * magnitude response) at every frequency. In the IIR tree, band b passes
 * through one allpass section per split above it (LR12/LR24) or two sections
 * (LR48) so the phase of every branch matches at the summing point. LR12
 * additionally bakes a polarity inversion into the high-pass branch of each
 * split (the textbook LR2 convention): odd-numbered bands come out
 * polarity-inverted so the tree sums flat instead of notching at every
 * crossover. LR36 (two third-order Butterworth filters per branch) does the
 * same, with a first-order and a second-order allpass section per split
 * above. The 6 dB per octave split is a first-order low-pass and its
 * complement: the two branches add up to the input itself, so its bands sum
 * to the input (not to an allpass of it) and need no phase correction. The
 * linear-phase path needs neither (zero-phase magnitudes sum to unity by
 * construction).
 * <p>
 * The linear-phase FIR has a fixed length of about 85 ms (4096 taps at 44.1
 * and 48 kHz, scaling with the rate), whatever the block size. Its latency is
 * fixed: three quarters of the FIR, 3072 samples at 48 kHz. The FIR runs as a
 * uniformly partitioned convolution in eight partitions of an eighth of its
 * length (512 taps at 48 kHz), with one shared input transform per channel
 * for all bands. The engine delays its output by two partitions, so the work
 * for an output partition can start one partition before it is heard: it is
 * spread evenly over the samples of that partition (after W. G. Gardner,
 * "Efficient Convolution without Input-Output Delay", JAES 43(3), 1995). The
 * last band is the delayed input minus the other bands, so it costs no
 * convolution. A kernel partition whose taps sum below 1e-6 is not convolved
 * (it moves its band by less than -120 dB at any frequency): away from the
 * centre most partitions hold only the design's rounding noise. The kernels
 * are the ideal Linkwitz-Riley magnitudes with a flat-top window (cosine
 * tapers on the outer quarters), which keeps the passband and slopes exact:
 * band 0 of a 200 Hz LR24 split at 48 kHz measures -0.527 dB at 100 Hz and
 * -24.61 dB at 400 Hz (ideal -0.527 / -24.61). The band magnitudes telescope
 * like the IIR tree (each band carries the high-pass of every split below
 * it), so the bands sum to the delayed input exactly.
 * <p>
 * A frequency, order or band-count change in linear phase is designed over
 * the blocks that follow, from scratch allocated in {@link #prepare}: each
 * band's kernel goes into a second kernel set, and the set is swapped in
 * whole, so the bands always come from one design and keep summing to the
 * delayed input (until a band-count change lands, the playing design's bands
 * are kept, missing ones silent and extra ones folded into the last requested
 * band). The first partition on a new design also runs the previous one, and
 * each band goes from the previous design to the new along it (a linear ramp
 * of one partition, 10.7 ms at 48 kHz; a band the previous design did not
 * have rises from silence): no band steps from one design to the other. The
 * design runs in small steps at a fixed share of the stereo steady work per
 * sample, so the time a change takes to land depends neither on the block
 * size nor on the channel count. A change that arrives during a design lets
 * it finish, then the next design starts from the latest targets.
 * {@link #prepare} runs the first design whole.
 * <p>
 * The minimum-phase engine arranges its splits in a cascade (default) or a
 * balanced tree ({@link #setTopology}), which sums to the same allpass with
 * fewer phase corrections. Frequency changes are smoothed (5 ms) in IIR mode,
 * with the coefficients refreshed on a 32-sample grid that runs across
 * blocks (the glide does not depend on the block size).
 * <p>
 * Setters take effect at the next {@link #process} call. Not thread-safe.
 */
public final class CrossoverFilter
{
    /** Filter processing mode. */
    public enum FilterMode
    {
        /** IIR biquads with allpass phase correction (zero latency). */
        MINIMUM_PHASE,
        /** FFT-based FIR crossover (latency, zero phase distortion, pre-ringing). */
        LINEAR_PHASE
    }

    /** How the minimum-phase sections are realised (see {@link #setStructure}). */
    public enum Structure
    {
        /** Direct form with a double memory, all sections in one pass (default). */
        DIRECT_FORM,
        /** Normalized ladders: passive under any movement of the splits. */
        LADDER
    }

    /** How the splits of the minimum-phase engine are arranged (see {@link #setTopology}). */
    public enum Topology
    {
        /** Each split takes what the one below left above it (default). */
        CASCADE,
        /** The middle split first, each half divided the same way. */
        BALANCED
    }

    /** Maximum number of bands of a default-constructed crossover. */
    public static final int DEFAULT_MAX_BANDS = 12;

    /** Channels the sections hold state for. */
    private static final int MAX_CHANNELS = 16;
    private static final int MAX_STAGES_PER_FILTER = 4;
    /** Linear-phase FIR span (rounded up to a power of two). */
    private static final double LP_FIR_SECONDS = 4096.0 / 48000.0;
    /** FIR length cap (768 kHz). */
    private static final int LP_MAX_FIR_LENGTH = 1 << 16;
    /** Uniform partitions of the FIR (even: the FIR centre falls on a partition boundary). */
    private static final int LP_PARTITIONS = 8;
    /**
     * Output delay of the engine, in partitions. Two leave one partition of
     * slack: the work for an output partition can be spread over the
     * partition before it. Latency: half the FIR plus these partitions.
     */
    private static final int LP_LEAD_BLOCKS = 2;
    /**
     * Input history, in partitions (power of two). The work for input
     * partition i runs while partition i + 1 is staged and reads partitions
     * i - 1 and i (the transform frame) and i - LP_PARTITIONS / 2 (the
     * delayed input of the residual band).
     */
    private static final int LP_RING_BLOCKS = 8;
    /** IIR coefficient refresh grid during a glide (samples). */
    private static final int GLIDE_GRID = 32;
    /** A band-count change in the minimum-phase engine. */
    private static final double FADE_SECONDS = 0.02;
    /** Samples per pass of that fade. */
    private static final int FADE_CHUNK = 64;
    // Linear-phase cost model, in units of one complex multiply-accumulate
    // bin. It weighs the steps of a partition's work (spread over the
    // partition) and of a design (paced at a share of the steady work); only
    // the ratios matter.
    /** Partition-size transform, per real point. */
    private static final double LP_FFT_UNITS_PER_POINT = 2.4;
    /** Long design transform, per real point. */
    private static final double LP_DESIGN_FFT_UNITS_PER_POINT = 2.7;
    /** Ideal magnitude, per design bin. */
    private static final double LP_MAG_UNITS_PER_BIN = 3.4;
    /** Copy or window, per sample. */
    private static final double LP_COPY_UNITS_PER_SAMPLE = 0.1;
    /** Residual band, per sample and band. */
    private static final double LP_RESIDUAL_UNITS_PER_SAMPLE = 0.35;
    /** Design steps per band's magnitudes. */
    private static final int LP_MAG_CHUNKS = 4;
    /** A kernel partition whose taps sum (in magnitude) below this is not convolved. */
    private static final double LP_SKIP_L1 = 1e-6;
    /**
     * Design work per sample, as a share of the steady work of a stereo
     * stream with the design's band count (whatever the channel count, so a
     * change lands in the same time in mono).
     */
    private static final double LP_DESIGN_SHARE = 0.45;

    /** Exact Butterworth Q of the LR24 sections. */
    private static final double Q_BUTTERWORTH = 0.70710678118654752440;
    /** Exact 4th-order Butterworth section Qs, 1/(2 cos(pi/8)) and 1/(2 cos(3 pi/8)). */
    private static final double Q_LR48_1 = 0.54119610014619698440;
    private static final double Q_LR48_2 = 1.30656296487637652786;
    private static final double[] Q_LR48 = { Q_LR48_1, Q_LR48_2, Q_LR48_1, Q_LR48_2 };

    private static final int LADDER_SPLIT_STRIDE = 2 * MAX_STAGES_PER_FILTER;

    /**
     * The sections of the minimum-phase engine: the splits, the phase
     * corrections and the same as ladders. Two banks: a change that the band
     * sum cannot follow in one memory (one involving the balanced topology)
     * runs the old configuration on one and the new on the other.
     */
    private static final class IirBank
    {
        /** The low branch's sections of each split, run in one pass. */
        final BiquadCascade[] splitLp;
        /** The high branch's sections of each split. */
        final BiquadCascade[] splitHp;
        /** The phase corrections, [band (or tree node) * maxSplits + split]. */
        final BiquadCascade[] allpass;
        /** See ladderLow(), ladderHigh(), ladderAllpass(). */
        NormalizedLadder[] ladders = new NormalizedLadder[0];

        IirBank(int maxBands, int maxSplits)
        {
            splitLp = new BiquadCascade[maxSplits];
            splitHp = new BiquadCascade[maxSplits];
            for (int s = 0; s < maxSplits; ++s)
            {
                splitLp[s] = new BiquadCascade(MAX_STAGES_PER_FILTER, MAX_CHANNELS);
                splitHp[s] = new BiquadCascade(MAX_STAGES_PER_FILTER, MAX_CHANNELS);
            }
            allpass = new BiquadCascade[maxBands * maxSplits];
            for (int i = 0; i < allpass.length; ++i)
                allpass[i] = new BiquadCascade(MAX_STAGES_PER_FILTER, MAX_CHANNELS);
        }

        /** Every section and ladder of another bank of the same size: coefficients, ramps and memory. */
        void copyFrom(IirBank from)
        {
            for (int s = 0; s < splitLp.length; ++s)
            {
                splitLp[s].copyFrom(from.splitLp[s]);
                splitHp[s].copyFrom(from.splitHp[s]);
            }
            for (int i = 0; i < allpass.length; ++i) allpass[i].copyFrom(from.allpass[i]);
            for (int i = 0; i < ladders.length; ++i) ladders[i].copyFrom(from.ladders[i]);
        }
    }

    private final int maxBands;
    private final int maxSplits;
    private final int ladderAllpassBase;
    private final int ladderCount;

    private double sampleRate = 0.0;
    private int maxBlockSize = 0;
    private boolean prepared = false;
    private int numBands = 2;
    private int order = 24;
    private int numChannels = 2;
    private FilterMode filterMode = FilterMode.MINIMUM_PHASE;
    private boolean dirty = true;

    private final double[] targetFrequencies;
    private boolean freqUpdatePending = false;

    private final double[] frequencies;
    private final SmoothedParameter[] freqSmoothers;

    private int numStagesPerFilter = 2;
    private int numStagesAllpass = 1;
    private FilterMode lastMode = FilterMode.MINIMUM_PHASE;

    /** The sections (from prepare()). */
    private final IirBank[] banks = new IirBank[2];
    /** The bank that plays. */
    private int bank = 0;
    /** The bank a two-bank fade brings in (-1: none). */
    private int fadeBank = -1;
    private double[][] workBuf = new double[0][];

    private Structure structure = Structure.DIRECT_FORM;
    private Topology topology = Topology.CASCADE;
    /** The processing runs the ladders. */
    private boolean ladderActive = false;

    // Linear-phase state (uniformly partitioned convolution).
    /** Partition transforms (2 x lpPart); the engine gate. */
    private FFTRealDoubleStepped lpFft = null;
    /** Kernel design (2 x firLength). */
    private FFTRealDoubleStepped lpDesignFft = null;
    private int firLength = 0;
    private int lpPart = 0;
    private int lpLatency = 0;
    /** Samples staged in the current partition. */
    private int lpPos = 0;
    /** History-ring block being staged. */
    private int lpStageBlock = 0;
    /** Output buffer that plays (the other is being computed). */
    private int lpPlay = 0;
    /** Bands in the output partition being emitted. */
    private int lpOutBands = 0;
    /** Kernel set that new partition work uses. */
    private int lpActiveSet = 0;
    /** Band count of each kernel set. */
    private final int[] lpSetBands = { 0, 0 };
    /** Convolved partitions of each kernel set (all bands). */
    private final int[] lpSetParts = { 0, 0 };
    // Partition work in flight.
    /** Kernel set it reads (-1: none in flight). */
    private int lpTaskSet = -1;
    private int lpTaskBands = 2;
    /** History-ring block of its input partition. */
    private int lpTaskBlock = 0;
    /** Delay-line slot of its input spectrum. */
    private int lpTaskFdl = 0;
    private int lpTaskUnit = 0;
    private int lpTaskUnits = 0;
    private double lpTaskDone = 0.0;
    private double lpTaskTotal = 0.0;
    /** Kernel set of the last partition work launched. */
    private int lpLaunchedSet = -1;
    /** Previous set the work in flight fades from (-1: none). */
    private int lpTaskFadeSet = -1;
    private int lpTaskFadeBands = 0;
    // Cost model (units), set in prepare().
    private double lpCostFft = 0.0, lpCostCma = 0.0, lpCostIfft = 0.0, lpCostResidualPerBand = 0.0;
    private double lpCostMagChunk = 0.0, lpCostDesignFftStep = 0.0, lpCostPartitionStep = 0.0;
    // Design step machine (prepare() runs it whole).
    private boolean lpDesignActive = false;
    /** A change arrived during the design. */
    private boolean lpDesignRedo = false;
    /** Kernel set the design writes. */
    private int lpDesignSet = 1;
    private int lpDesignBand = 0;
    /** Magnitude chunks, inverse FFT passes, partitions. */
    private int lpDesignStep = 0;
    private int lpDesignBands = 0;
    /** Partitions the design keeps so far. */
    private int lpDesignParts = 0;
    private int lpDesignExpo = 4;
    private int lpDesignFftSteps = 1;
    private final double[] lpDesignFc;
    private double lpDesignCredit = 0.0;
    /** Design units per sample. */
    private double lpDesignRate = 0.0;
    /** Samples left in the current IIR glide segment. */
    private int glideRemain = 0;
    // The minimum-phase engine's band count and the fade of a change.
    /** Bands of the topology heading to (0: none yet since a reset). */
    private int iirBands = 0;
    /** Bands of the topology fading out. */
    private int fadeFrom = 0;
    /** The topology heading to. */
    private Topology iirTopo = Topology.CASCADE;
    /** The topology fading out. */
    private Topology fadeFromTopo = Topology.CASCADE;
    /** Samples left of the fade. */
    private int fadeLeft = 0;
    private int fadeLength = 960;
    /** [band][ch][FADE_CHUNK] scratch of the fade. */
    private double[][][] fadeBands = new double[0][][];
    private double[][][] fadeLower = new double[0][][];
    /** [ch][FADE_CHUNK] scratch of the fade. */
    private double[][] fadeTop = new double[0][];
    private boolean lpMagDirty = true;

    /** [set][(band * partitions + part) * specLen] kernel partition spectra. */
    private double[] lpKernelSpec = null;
    /** [set][band * partitions + part] true = convolved, false = negligible. */
    private boolean[] lpPartOn = null;
    /** [(ch * partitions + slot) * specLen] input spectra. */
    private double[] lpFdl = null;
    /** [ch][ring block][lpPart] input history. */
    private double[] lpRing = null;
    /** [((ch * 2 + buffer) * maxBands + band) * lpPart] output partitions. */
    private double[] lpOut = null;
    private double[] lpAccum = null, lpResult = null, lpScratch = null;
    /** [band][lpPart] one channel's previous-design bands in a fade. */
    private double[] lpFadeOld = null;
    /** [bin] product of the high-passes below the band being designed. */
    private double[] lpCarry = null;
    /** [bin] design grid frequencies. */
    private double[] lpBinFreq = null;
    /** [bin] magnitudes of the band being designed. */
    private double[] lpMag = null;
    /** [tap] flat-top window. */
    private double[] lpWindow = null;
    /** Design IFFT input scratch (2 x firLength + 2). */
    private double[] lpTimeResponse = null;
    /** Design IFFT output scratch (2 x firLength). */
    private double[] lpFirKernel = null;

    /** A crossover of up to {@link #DEFAULT_MAX_BANDS} bands. */
    public CrossoverFilter()
    {
        this(DEFAULT_MAX_BANDS);
    }

    /**
     * A crossover of up to {@code maxBands} bands.
     *
     * @param maxBands maximum number of output bands, at least 2
     * @throws IllegalArgumentException if maxBands is below 2
     */
    public CrossoverFilter(int maxBands)
    {
        if (maxBands < 2)
            throw new IllegalArgumentException("CrossoverFilter needs at least 2 bands; got " + maxBands);
        this.maxBands = maxBands;
        this.maxSplits = maxBands - 1;
        this.ladderAllpassBase = maxSplits * LADDER_SPLIT_STRIDE;
        this.ladderCount = ladderAllpassBase + maxBands * maxSplits * 2;
        targetFrequencies = new double[maxSplits];
        frequencies = new double[maxSplits];
        lpDesignFc = new double[maxSplits];
        freqSmoothers = new SmoothedParameter[maxSplits];
        for (int i = 0; i < maxSplits; ++i) freqSmoothers[i] = new SmoothedParameter();
        initDefaultFrequencies();
    }

    // -- Lifecycle -----------------------------------------------------------

    /**
     * Prepares the crossover for processing.
     * <p>
     * Configured split frequencies, band count, order and mode are preserved
     * across prepare() calls (configure-then-prepare is fully supported). The
     * linear-phase engine is always allocated (so a later mode switch works);
     * its size depends on the sample rate and channel count only. The first
     * linear-phase design is built whole here.
     *
     * @param sampleRate   sample rate in Hz
     * @param numChannels  channels (the sections hold up to 16; more pass through on band 0)
     * @param maxBlockSize largest block {@link #process} is given
     *                     (an invalid set of values is ignored and the previous state is kept)
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize)
    {
        if (!(sampleRate > 0.0 && sampleRate <= Double.MAX_VALUE && maxBlockSize > 0 && numChannels > 0)) return;
        prepared = false;
        this.sampleRate = sampleRate;
        this.maxBlockSize = maxBlockSize;
        // The per-split sections hold 16 channels; more are passed through.
        this.numChannels = Math.min(numChannels, MAX_CHANNELS);

        workBuf = new double[this.numChannels][maxBlockSize];

        // Linear-phase resources, allocated regardless of the current mode so
        // a later setFilterMode(LINEAR_PHASE) engages. The FIR length follows
        // the sample rate (about 85 ms), never the block. lpFft is the gate
        // and is created last.
        lpFft = null;
        lpDesignFft = null;
        firLength = 0;
        lpPart = 0;
        lpLatency = 0;
        {
            int firLen = 256;
            final double wanted = sampleRate * LP_FIR_SECONDS;
            while (firLen < wanted && firLen < LP_MAX_FIR_LENGTH) firLen <<= 1;
            firLength = firLen;
            lpPart = firLen / LP_PARTITIONS;
            // The kernel is centred at firLength/2; the engine delays its
            // output by LP_LEAD_BLOCKS partitions (the scheduling slack).
            lpLatency = firLength / 2 + LP_LEAD_BLOCKS * lpPart;

            final int specLen = 2 * lpPart + 2;
            final int chans = this.numChannels;
            final int designBins = firLength + 1; // 2L-point design grid

            // Two kernel sets: one plays while the next design is built. The
            // last band of a set is the residual and has no kernel.
            lpKernelSpec = new double[2 * maxSplits * LP_PARTITIONS * specLen];
            lpPartOn = new boolean[2 * maxSplits * LP_PARTITIONS];
            lpFdl = new double[chans * LP_PARTITIONS * specLen];
            lpRing = new double[chans * LP_RING_BLOCKS * lpPart];
            lpOut = new double[chans * 2 * maxBands * lpPart];
            lpAccum = new double[specLen];
            lpResult = new double[2 * lpPart];

            // Cost model of the steps (units: one complex multiply-accumulate
            // bin), used to spread a partition's work and a design's steps.
            final double part = (double) lpPart;
            lpCostFft = (LP_FFT_UNITS_PER_POINT + LP_COPY_UNITS_PER_SAMPLE) * 2.0 * part;
            lpCostCma = part + 1.0;
            lpCostIfft = LP_FFT_UNITS_PER_POINT * 2.0 * part + LP_COPY_UNITS_PER_SAMPLE * part;
            lpCostResidualPerBand = LP_RESIDUAL_UNITS_PER_SAMPLE * part;
            lpCostMagChunk = LP_MAG_UNITS_PER_BIN * (double) ((designBins + LP_MAG_CHUNKS - 1) / LP_MAG_CHUNKS);
            lpCostPartitionStep = lpCostIfft + LP_COPY_UNITS_PER_SAMPLE * part;

            // Design scratch, so a live crossover/order change never allocates.
            lpCarry = new double[designBins];
            Arrays.fill(lpCarry, 1.0);
            lpBinFreq = new double[designBins];
            lpMag = new double[designBins];
            for (int k = 0; k < designBins; ++k)
                lpBinFreq[k] = sampleRate * (double) k / (double) (2 * firLength);
            // Flat-top window: a cosine taper over the outer quarter on each side.
            lpWindow = new double[firLength];
            Arrays.fill(lpWindow, 1.0);
            final int taper = firLength / 4;
            for (int i = 0; i < firLength; ++i)
            {
                final int edge = Math.min(i, firLength - 1 - i);
                if (edge < taper)
                    lpWindow[i] = 0.5 - 0.5 * Math.cos(Math.PI * ((double) edge + 0.5) / (double) taper);
            }
            lpTimeResponse = new double[2 * firLength + 2];
            lpFirKernel = new double[2 * firLength];
            lpScratch = new double[2 * lpPart];

            lpDesignFft = new FFTRealDoubleStepped(2 * firLength);
            lpDesignFftSteps = lpDesignFft.getInverseStepCount();
            lpCostDesignFftStep = LP_DESIGN_FFT_UNITS_PER_POINT * 2.0 * (double) firLength
                                  / (double) lpDesignFftSteps;
            lpFft = new FFTRealDoubleStepped(2 * lpPart);
        }

        // The two banks of sections: the correction chains [band (or tree
        // node) * maxSplits + split], and the ladders of setStructure(LADDER),
        // allocated whatever the current structure and topology so a later
        // switch never allocates.
        for (int i = 0; i < 2; ++i)
        {
            if (banks[i] == null) banks[i] = new IirBank(maxBands, maxSplits);
            final NormalizedLadder[] ladders = new NormalizedLadder[ladderCount];
            for (int j = 0; j < ladderCount; ++j) ladders[j] = new NormalizedLadder(MAX_CHANNELS);
            banks[i].ladders = ladders;
        }
        bank = 0;
        fadeBank = -1;
        ladderActive = false;

        // A change of the band count in the minimum-phase engine: both
        // topologies run for FADE_SECONDS and the bands go from one to the
        // other, a chunk at a time through this scratch.
        fadeLength = Math.max(1, (int) lround(FADE_SECONDS * sampleRate));
        fadeBands = new double[maxBands][this.numChannels][FADE_CHUNK];
        fadeLower = new double[maxBands][this.numChannels][FADE_CHUNK];
        fadeTop = new double[this.numChannels][FADE_CHUNK];
        lpFadeOld = new double[maxBands * lpPart];

        // Re-sync the smoothers to the current targets: a configuration made
        // before prepare() is kept.
        for (int i = 0; i < maxSplits; ++i)
        {
            freqSmoothers[i].prepare(sampleRate, 5.0);
            freqSmoothers[i].setSmoothingType(SmoothedParameter.SmoothingType.EXPONENTIAL);
            final double target = targetFrequencies[i];
            freqSmoothers[i].reset(target);
            frequencies[i] = target;
        }

        lastMode = filterMode;
        dirty = true;
        // The first design is built whole here; later changes are spread
        // over blocks by process().
        lpActiveSet = 0;
        lpSetBands[0] = 0;
        lpSetBands[1] = 0;
        lpSetParts[0] = 0;
        lpSetParts[1] = 0;
        lpDesignActive = false;
        lpDesignRedo = false;
        if (lpFft != null) designLinearPhaseNow();
        reset();

        prepared = true;
    }

    /**
     * {@link #process(double[][], int, double[][][], int, int, int)} with
     * every array read and written from index 0.
     */
    public int process(double[][] input, double[][][] bandOutputs, int numOutputBands, int numSamples)
    {
        return process(input, 0, bandOutputs, 0, numOutputBands, numSamples);
    }

    /**
     * Splits the input into separate band outputs.
     * <p>
     * {@code bandOutputs[b]} holds the channels of band b; samples are read
     * from {@code input[ch][inputOffset]} and written to
     * {@code bandOutputs[b][ch][bandOffset]} on. The processed span is
     * clamped to the prepared maxBlockSize and to the shortest array; the
     * channel count to the narrowest band. The linear-phase engine buffers
     * internally, so any block size up to maxBlockSize gives the same output.
     * Input channels beyond the prepared/16-channel limit are passed through
     * on band 0 (remaining bands silent) so the band sum still reconstructs
     * the input. The outputs may be the input arrays.
     *
     * @param input          input channels (non-null arrays)
     * @param inputOffset    index of the first input sample
     * @param bandOutputs    one array of channels per band
     * @param bandOffset     index of the first output sample
     * @param numOutputBands number of output bands (should match
     *                       getNumBands(); fewer bands re-purpose the first
     *                       splits only in IIR mode and is not a supported
     *                       configuration)
     * @param numSamples     samples to process
     * @return the number of bands actually written this call (0 when nothing
     *         was written: unprepared, degenerate span, or null outputs);
     *         arrays beyond the returned count are untouched
     */
    public int process(double[][] input, int inputOffset, double[][][] bandOutputs, int bandOffset,
                       int numOutputBands, int numSamples)
    {
        if (!prepared || bandOutputs == null || input == null) return 0;
        numOutputBands = Math.min(numOutputBands, bandOutputs.length);

        final FilterMode mode = filterMode;
        final boolean lpActive = mode == FilterMode.LINEAR_PHASE && lpFft != null;
        if (mode != lastMode)
        {
            // Mode switch: clear filter/overlap state so the incoming engine
            // does not replay stale history (a transient is the documented
            // behaviour of an engine switch).
            lastMode = mode;
            reset();
            if (lpActive) syncFrequenciesToTargets();
        }

        if (freqUpdatePending)
        {
            freqUpdatePending = false;
            if (lpActive)
            {
                // FIR kernels are rebuilt per change (per-sample smoothing
                // cannot apply to a kernel), from the targets synced here.
                syncFrequenciesToTargets();
                lpMagDirty = true;
            }
            else
            {
                for (int i = 0; i < maxSplits; ++i) freqSmoothers[i].setTargetValue(targetFrequencies[i]);
            }
        }

        // A change of structure restarts the memory of the sections; the
        // ladders take the coefficients in use.
        final boolean ladder = structure == Structure.LADDER && bank(0).ladders.length > 0;
        if (!lpActive && ladder != ladderActive)
        {
            ladderActive = ladder;
            resetBank(bank(0));
            resetBank(bank(1));
            if (ladder) updateCoefficients(0);
        }

        // The IIR coefficients are only rebuilt while the IIR engine runs; in
        // linear-phase mode the flag stays set for a later switch back.
        if (!lpActive && dirty)
        {
            dirty = false;
            updateCoefficients(0);
        }

        final int n = Math.min(numOutputBands, numBands);
        if (n < 2) return 0;

        // Defensive span clamp: never index past the prepared work buffers or
        // past any output array the call writes; the channel count follows
        // the narrowest band. A fade of the minimum-phase engine still running
        // towards more bands writes them all.
        if (inputOffset < 0 || bandOffset < 0) return 0;
        int limit = n;
        if (!lpActive)
        {
            final boolean change = iirBands == 0 || (fadeLeft == 0 && (n != iirBands || topology != iirTopo));
            limit = Math.max(n, Math.min(change ? n : iirBands, numOutputBands));
        }
        int nS = Math.min(numSamples, maxBlockSize);
        int nCh = Math.min(input.length, numChannels);
        for (final double[] ch : input) nS = Math.min(nS, ch.length - inputOffset);
        for (int b = 0; b < limit; ++b)
        {
            final double[][] band = bandOutputs[b];
            if (band == null)
            {
                nCh = 0;
                continue;
            }
            nCh = Math.min(nCh, band.length);
            for (final double[] ch : band) nS = Math.min(nS, ch.length - bandOffset);
        }
        if (nS <= 0) return 0;

        if (lpActive)
        {
            processLinearPhase(input, inputOffset, bandOutputs, bandOffset, n, nS, nCh);
            passExtraChannels(input, inputOffset, bandOutputs, bandOffset, n, nS, nCh);
            return n;
        }

        // The minimum-phase engine changes its band count or its topology
        // over a fade (a change that arrives during one waits for it to end).
        final Topology topo = topology;
        if (iirBands == 0)
        {
            iirBands = n;
            iirTopo = topo;
        }
        else if (fadeLeft == 0 && (n != iirBands || topo != iirTopo)) startBandFade(n, topo);
        final int written = Math.min(iirBands, numOutputBands);
        processIIR(input, inputOffset, bandOutputs, bandOffset, written, nS, nCh);
        passExtraChannels(input, inputOffset, bandOutputs, bandOffset, written, nS, nCh);
        return written;
    }

    // -- Configuration -------------------------------------------------------

    /**
     * Sets the number of output bands (2 to maxBands).
     * <p>
     * Changing the band count re-initialises all split frequencies to
     * log-spaced defaults (100 Hz to 10 kHz); reapply custom frequencies
     * afterwards, or use {@link #setNumBandsKeepingSplits}. A live change
     * goes over without a step: in the minimum-phase engine the old and the
     * new topology run together for 20 ms and the bands move from one to the
     * other (a band that leaves folds into the new top band, one that comes
     * in rises from silence), so the band sum moves from one allpass to the
     * other; in the linear-phase engine the new design comes in over a
     * partition.
     */
    public void setNumBands(int n)
    {
        numBands = clamp(n, 2, maxBands);
        initDefaultFrequencies();
        dirty = true;
        lpMagDirty = true;
    }

    /**
     * Sets the number of output bands (2 to maxBands) and keeps the splits.
     * <p>
     * The split frequencies do not depend on the band count: the count only
     * says how many of the splits are used. A split that stays keeps its
     * frequency (and, in the minimum-phase engine, its memory); a split that
     * comes back takes the frequency it had, or, when that is not above the
     * split below it, the point halfway in octaves between that split and 20
     * kHz. The change itself goes over as {@link #setNumBands} describes.
     */
    public void setNumBandsKeepingSplits(int n)
    {
        final int bands = clamp(n, 2, maxBands);
        for (int s = 1; s < bands - 1; ++s)
        {
            final double below = targetFrequencies[s - 1];
            final double at = targetFrequencies[s];
            if (!(at > below))
                targetFrequencies[s] = Math.sqrt(stdMax(below, 1.0) * 20000.0);
        }
        numBands = bands;
        freqUpdatePending = true;
        dirty = true;
        lpMagDirty = true;
    }

    /**
     * Sets a crossover frequency and keeps the splits sorted.
     * <p>
     * Non-finite frequencies are ignored; values are floored to 1 Hz here and
     * clamped to [20, 0.499 * sampleRate] at design time.
     *
     * @param index  split index, 0 to getNumBands() - 2 (others are ignored)
     * @param freqHz frequency in Hz
     */
    public void setCrossoverFrequency(int index, double freqHz)
    {
        if (!Double.isFinite(freqHz)) return;
        if (index >= 0 && index < numBands - 1)
        {
            freqHz = stdMax(freqHz, 1.0);
            targetFrequencies[index] = freqHz;

            // Insertion sort of the splits in use.
            final int activeSplits = clamp(numBands - 1, 0, maxSplits);
            for (int i = 1; i < activeSplits; ++i)
            {
                final double key = targetFrequencies[i];
                int j = i - 1;
                while (j >= 0 && targetFrequencies[j] > key)
                {
                    targetFrequencies[j + 1] = targetFrequencies[j];
                    --j;
                }
                targetFrequencies[j + 1] = key;
            }

            freqUpdatePending = true;
            dirty = true;
            lpMagDirty = true;
        }
    }

    /**
     * Sets the crossover slope: 6, 12, 24, 36 or 48 (dB/oct). Other values are ignored.
     * <p>
     * 12 to 48 are Linkwitz-Riley splits (the bands sum to an allpass of the
     * input; each branch is 6 dB down at the split). 6 is a first-order
     * low-pass and its complement (the bands sum to the input itself; each
     * branch is 3 dB down at the split in the minimum-phase engine and 6 dB
     * in the linear-phase one).
     */
    public void setOrder(int order)
    {
        if (order == 6 || order == 12 || order == 24 || order == 36 || order == 48)
        {
            this.order = order;
            dirty = true;
            lpMagDirty = true;
        }
    }

    /** Sets the processing mode (null is ignored). A switch resets the filter state (transient). */
    public void setFilterMode(FilterMode mode)
    {
        if (mode == null) return;
        filterMode = mode;
        dirty = true;
        lpMagDirty = true;
    }

    /**
     * Chooses how the sections of the minimum-phase engine are realised.
     * <p>
     * LADDER runs every section of the splits and of the phase correction as
     * a normalized ladder ({@link NormalizedLadder}): the same band responses,
     * and a memory that can only lose energy however its coefficients move.
     * During a glide the ladders' coefficients ramp linearly from one refresh
     * of the 32-sample grid to the next instead of stepping. The 5 ms glide
     * of the splits already keeps the direct form's memory consistent with
     * its coefficients, so the ladder is for those who want the guarantee
     * rather than a lower peak. The switch restarts the sections' memory at
     * the next block, which may click: choose it before playback. The
     * linear-phase engine is unaffected. Null is ignored.
     *
     * @param structure DIRECT_FORM (default) or LADDER
     */
    public void setStructure(Structure structure)
    {
        if (structure == null) return;
        this.structure = structure;
    }

    /** The structure set by {@link #setStructure}. */
    public Structure getStructure()
    {
        return structure;
    }

    /**
     * Arranges the splits of the minimum-phase engine.
     * <p>
     * CASCADE splits the lowest band off first and passes what is left on;
     * each band then takes the phase correction of every split above it,
     * S(S-1)/2 chains for S splits (10 with 6 bands, 55 with 12). BALANCED
     * splits at the middle split first and divides each half the same way;
     * each branch takes the corrections of the splits of the other half once,
     * before it is divided (6 chains with 6 bands, 22 with 12). The bands
     * still sum to the same allpass, but their skirts differ: a band carries
     * the slopes of the splits on its way down the tree rather than every
     * split below it, so some skirts fall faster and others slower than in
     * the cascade. A live change goes over without a step (both arrangements
     * run for 20 ms on separate memories and the bands move from one to the
     * other). The linear-phase engine is unaffected. Null is ignored.
     *
     * @param topology CASCADE (default) or BALANCED
     */
    public void setTopology(Topology topology)
    {
        if (topology == null) return;
        this.topology = topology;
        dirty = true;
    }

    /** The arrangement set by {@link #setTopology}. */
    public Topology getTopology()
    {
        return topology;
    }

    // -- Queries -------------------------------------------------------------

    public int getNumBands()
    {
        return numBands;
    }

    public int getOrder()
    {
        return order;
    }

    public FilterMode getFilterMode()
    {
        return filterMode;
    }

    /** The target frequency of split point {@code index} (0 outside [0, maxBands - 1)). */
    public double getCrossoverFrequency(int index)
    {
        if (index < 0 || index >= maxSplits) return 0.0;
        return targetFrequencies[index];
    }

    /**
     * Latency in samples: 0 for IIR; for linear phase, three quarters of the
     * FIR (3072 at 48 kHz), whatever the block size. Reports the
     * linear-phase latency only when the engine exists (after prepare()).
     */
    public int getLatency()
    {
        return (filterMode == FilterMode.LINEAR_PHASE && lpFft != null) ? lpLatency : 0;
    }

    /** Clears the filter memories and the linear-phase history; the settings and the designs are kept. */
    public void reset()
    {
        for (final IirBank k : banks)
            if (k != null) resetBank(k);
        fadeBank = -1;

        if (lpRing != null) Arrays.fill(lpRing, 0.0);
        if (lpFdl != null) Arrays.fill(lpFdl, 0.0);
        if (lpOut != null) Arrays.fill(lpOut, 0.0);
        lpPos = 0;
        lpStageBlock = 0;
        lpPlay = 0;
        lpOutBands = lpSetBands[lpActiveSet];
        // No partition task is in flight: the first one starts when the
        // first partition of input is in.
        lpTaskSet = -1;
        lpTaskFdl = 0;
        lpTaskUnit = 0;
        lpTaskUnits = 0;
        lpTaskDone = 0.0;
        lpTaskTotal = 0.0;
        lpLaunchedSet = -1;
        lpTaskFadeSet = -1;
        glideRemain = 0;
        iirBands = 0;
        fadeLeft = 0;
    }

    // -- Coefficients ----------------------------------------------------------

    /**
     * First-order allpass, bilinear transform of (omega - s)/(omega + s):
     * H(z) = (a + z^-1)/(1 + a z^-1) with a = (w - 1)/(w + 1), w =
     * tan(pi*f/fs). Its phase is -90 degrees exactly at {@code freq}, and it
     * equals LP1 - HP1 of the matching first-order pair (the LR12 sum
     * identity).
     */
    static BiquadCoeffs makeFirstOrderAllPass(double sampleRate, double freq)
    {
        freq = clampValue(freq, 1.0, stdMax(1.0, sampleRate * 0.499));
        final double w = Math.tan(Math.PI * freq / sampleRate);
        final double a = (w - 1.0) / (w + 1.0);
        return new BiquadCoeffs(a, 1.0, 0.0, a, 0.0);
    }

    /** The section with its numerator negated (a polarity inversion). */
    private static BiquadCoeffs inverted(BiquadCoeffs c)
    {
        return new BiquadCoeffs(-c.b0, -c.b1, -c.b2, c.a1, c.a2);
    }

    private void initDefaultFrequencies()
    {
        final int numSplits = numBands - 1;
        final double logMin = Math.log(100.0);
        final double logMax = Math.log(10000.0);

        for (int s = 0; s < numSplits; ++s)
        {
            final double t = (double) (s + 1) / (double) (numSplits + 1);
            targetFrequencies[s] = Math.exp(logMin + t * (logMax - logMin));
        }
        freqUpdatePending = true;
    }

    /**
     * Applies the targets at once (linear-phase path and mode switches) and
     * parks the smoothers on them, so a later switch back to IIR does not
     * replay a stale ramp.
     */
    private void syncFrequenciesToTargets()
    {
        for (int i = 0; i < maxSplits; ++i)
        {
            final double t = targetFrequencies[i];
            frequencies[i] = t;
            freqSmoothers[i].reset(t);
        }
    }

    private static double clampSplitFreq(double f, double sr)
    {
        return clampValue(f, 20.0, sr * 0.499);
    }

    private void updateCoefficients(int ladderRamp)
    {
        if (sampleRate <= 0 || banks[0] == null) return;

        final int numSplits = numBands - 1;
        final double sr = sampleRate;

        // Splits (and their allpass chains) that a band count increase brings
        // back are reset where they come into the path (startBandFade()): a
        // split still fading out keeps playing until then.

        // The phase-correction allpass per band/split equals the allpass that
        // the split's LP/HP branches sum to: one first-order section for LR12
        // (LP1^2 - HP1^2 = AP1 exactly), one second-order section for LR24
        // (LP2^2 + HP2^2 = AP2(Q = 0.7071)), two sections (q1, q2) for LR48.
        // The frequencies are sorted by the setters.
        switch (order)
        {
            case 6:
                // A first-order low-pass and the high-pass on the same pole:
                // they add up to the input itself (their numerators sum to the
                // denominator), so no band needs a phase correction.
                numStagesPerFilter = 1;
                numStagesAllpass = 0;
                for (int s = 0; s < numSplits; ++s)
                {
                    final double f = clampSplitFreq(frequencies[s], sr);
                    setSplitSection(s, 0, BiquadDesign.makeFirstOrderLowPass(sr, f),
                                    BiquadDesign.makeFirstOrderHighPass(sr, f), ladderRamp);
                }
                break;

            case 36:
                // Two third-order Butterworth filters in series per branch: a
                // first-order section and a second-order one of Q = 1, twice.
                // The two branches meet half a turn apart (three times 180
                // degrees), as in LR12, so the high branch is inverted and the
                // pair sums to the allpass of one third-order filter: a
                // first-order and a second-order section.
                numStagesPerFilter = 4;
                numStagesAllpass = 2;
                for (int s = 0; s < numSplits; ++s)
                {
                    final double f = clampSplitFreq(frequencies[s], sr);
                    final BiquadCoeffs lp1 = BiquadDesign.makeFirstOrderLowPass(sr, f);
                    final BiquadCoeffs hp1 = BiquadDesign.makeFirstOrderHighPass(sr, f);
                    final BiquadCoeffs lp2 = BiquadDesign.makeLowPass(sr, f, 1.0);
                    final BiquadCoeffs hp2 = BiquadDesign.makeHighPass(sr, f, 1.0);
                    setSplitSection(s, 0, lp1, inverted(hp1), ladderRamp);
                    setSplitSection(s, 1, lp2, hp2, ladderRamp);
                    setSplitSection(s, 2, lp1, hp1, ladderRamp);
                    setSplitSection(s, 3, lp2, hp2, ladderRamp);
                    final BiquadCoeffs ap1 = makeFirstOrderAllPass(sr, f);
                    final BiquadCoeffs ap2 = BiquadDesign.makeAllPass(sr, f, 1.0);
                    setCorrection(s, numSplits, 0, ap1, ladderRamp);
                    setCorrection(s, numSplits, 1, ap2, ladderRamp);
                }
                break;

            case 12:
                numStagesPerFilter = 2;
                numStagesAllpass = 1;
                for (int s = 0; s < numSplits; ++s)
                {
                    final double f = clampSplitFreq(frequencies[s], sr);
                    final BiquadCoeffs lpC = BiquadDesign.makeFirstOrderLowPass(sr, f);
                    final BiquadCoeffs hpC = BiquadDesign.makeFirstOrderHighPass(sr, f);
                    final BiquadCoeffs apC = makeFirstOrderAllPass(sr, f);

                    // LR12 sums flat only with the high branch polarity
                    // inverted (LP^2 - HP^2 = allpass; LP^2 + HP^2 notches at
                    // fc). The inversion is baked into the first HP stage: odd
                    // bands come out inverted, the sum is allpass-flat.
                    setSplitSection(s, 0, lpC, inverted(hpC), ladderRamp);
                    setSplitSection(s, 1, lpC, hpC, ladderRamp);
                    setCorrection(s, numSplits, 0, apC, ladderRamp);
                }
                break;

            case 24:
                numStagesPerFilter = 2;
                numStagesAllpass = 1;
                for (int s = 0; s < numSplits; ++s)
                {
                    final double f = clampSplitFreq(frequencies[s], sr);
                    final BiquadCoeffs lpC = BiquadDesign.makeLowPass(sr, f, Q_BUTTERWORTH);
                    final BiquadCoeffs hpC = BiquadDesign.makeHighPass(sr, f, Q_BUTTERWORTH);
                    final BiquadCoeffs apC = BiquadDesign.makeAllPass(sr, f, Q_BUTTERWORTH);

                    for (int st = 0; st < 2; ++st)
                        setSplitSection(s, st, lpC, hpC, ladderRamp);
                    setCorrection(s, numSplits, 0, apC, ladderRamp);
                }
                break;

            case 48:
                numStagesPerFilter = 4;
                numStagesAllpass = 2;
                for (int s = 0; s < numSplits; ++s)
                {
                    final double f = clampSplitFreq(frequencies[s], sr);

                    for (int st = 0; st < 4; ++st)
                        setSplitSection(s, st, BiquadDesign.makeLowPass(sr, f, Q_LR48[st]),
                                        BiquadDesign.makeHighPass(sr, f, Q_LR48[st]), ladderRamp);
                    final BiquadCoeffs ap1 = BiquadDesign.makeAllPass(sr, f, Q_LR48_1);
                    final BiquadCoeffs ap2 = BiquadDesign.makeAllPass(sr, f, Q_LR48_2);
                    setCorrection(s, numSplits, 0, ap1, ladderRamp);
                    setCorrection(s, numSplits, 1, ap2, ladderRamp);
                }
                break;

            default:
                break;
        }

        final boolean tree = treeInUse();
        for (int i = 0; i < 2; ++i)
        {
            if (i != bank && i != fadeBank) continue;
            final IirBank k = bank(i);
            for (int s = 0; s < numSplits; ++s)
            {
                k.splitLp[s].setNumSections(numStagesPerFilter);
                k.splitHp[s].setNumSections(numStagesPerFilter);
                for (int b = 0; b < (tree ? numSplits : s); ++b)
                    if (b != s) k.allpass[b * maxSplits + s].setNumSections(numStagesAllpass);
            }
        }
    }

    /** Ladder index of section {@code st} of split {@code s}, low branch. */
    private static int ladderLow(int s, int st)
    {
        return s * LADDER_SPLIT_STRIDE + st;
    }

    /** Ladder index of section {@code st} of split {@code s}, high branch. */
    private static int ladderHigh(int s, int st)
    {
        return s * LADDER_SPLIT_STRIDE + MAX_STAGES_PER_FILTER + st;
    }

    /** Ladder index of section {@code st} of the correction of band {@code b} for split {@code s}. */
    private int ladderAllpass(int b, int s, int st)
    {
        return ladderAllpassBase + (b * maxSplits + s) * 2 + st;
    }

    private IirBank bank(int index)
    {
        return banks[index];
    }

    /**
     * Whether some configuration in play uses the balanced tree (whose
     * corrections also run for splits below a band).
     */
    private boolean treeInUse()
    {
        return topology == Topology.BALANCED || iirTopo == Topology.BALANCED
               || (fadeBank >= 0 && fadeFromTopo == Topology.BALANCED);
    }

    /**
     * One section of the two branches of a split: to the one-pass cascades
     * and, while the ladders run, to the ladders (ramping over {@code ramp}
     * samples), in the bank that plays and in the one a fade brings in.
     */
    private void setSplitSection(int s, int st, BiquadCoeffs low, BiquadCoeffs high, int ramp)
    {
        for (int i = 0; i < 2; ++i)
        {
            if (i != bank && i != fadeBank) continue;
            final IirBank k = bank(i);
            k.splitLp[s].setCoeffs(st, low);
            k.splitHp[s].setCoeffs(st, high);
            if (ladderActive)
            {
                k.ladders[ladderLow(s, st)].rampCoeffs(low, ramp);
                k.ladders[ladderHigh(s, st)].rampCoeffs(high, ramp);
            }
        }
    }

    /** One section of the phase correction of band {@code b} (or tree node {@code b}) for split {@code s}. */
    private void setAllpassSection(int b, int s, int st, BiquadCoeffs c, int ramp)
    {
        for (int i = 0; i < 2; ++i)
        {
            if (i != bank && i != fadeBank) continue;
            final IirBank k = bank(i);
            k.allpass[b * maxSplits + s].setCoeffs(st, c);
            if (ladderActive)
                k.ladders[ladderAllpass(b, s, st)].rampCoeffs(c, ramp);
        }
    }

    /** The corrections for split {@code s}: of every band below it, and with the tree in play of every node. */
    private void setCorrection(int s, int numSplits, int st, BiquadCoeffs c, int ramp)
    {
        final int end = treeInUse() ? numSplits : s;
        for (int b = 0; b < end; ++b)
            if (b != s) setAllpassSection(b, s, st, c, ramp);
    }

    // -- Minimum-phase engine --------------------------------------------------

    /**
     * {@code count} ladders from {@code first} over one channel, from
     * {@code src} to {@code dst} (may be the same). {@link #advanceLadders}
     * moves the ramps on.
     */
    private static void runLadders(IirBank k, double[] src, int srcOff, double[] dst, int dstOff, int length,
                                   int first, int count, int channel)
    {
        if (src != dst || srcOff != dstOff) System.arraycopy(src, srcOff, dst, dstOff, length);
        for (int j = 0; j < count; ++j)
            k.ladders[first + j].processSegment(dst, dstOff, length, channel);
    }

    /** Moves the ramps of {@code count} ladders from {@code first} on, once every channel has run. */
    private static void advanceLadders(IirBank k, int first, int count, int length)
    {
        for (int j = 0; j < count; ++j)
            k.ladders[first + j].advanceRamp(length);
    }

    private void processIIR(double[][] input, int inOff, double[][][] outputs, int outOff, int numBands, int nS,
                            int nCh)
    {
        final int numSplits = numBands - 1;

        // During a glide the coefficients are refreshed every GLIDE_GRID
        // samples on a grid that runs across blocks: a segment started in one
        // block keeps its coefficients into the next, so the glide is the
        // same whatever the block size.
        int offset = 0;
        while (offset < nS)
        {
            if (glideRemain == 0)
            {
                boolean anySmoothing = false;
                for (int i = 0; i < numSplits; ++i)
                    anySmoothing = anySmoothing || freqSmoothers[i].isSmoothing();
                if (!anySmoothing) break;
                for (int i = 0; i < numSplits; ++i)
                {
                    for (int s = 0; s < GLIDE_GRID; ++s)
                        freqSmoothers[i].getNextValue();
                    frequencies[i] = freqSmoothers[i].getCurrentValue();
                }
                // The ladders ramp to the new coefficients over the segment.
                updateCoefficients(GLIDE_GRID);
                glideRemain = GLIDE_GRID;
            }
            final int len = Math.min(glideRemain, nS - offset);
            processRange(input, inOff, outputs, outOff, numBands, nCh, offset, len);
            offset += len;
            glideRemain -= len;
        }
        if (offset < nS)
            processRange(input, inOff, outputs, outOff, numBands, nCh, offset, nS - offset);
    }

    /** A range of the minimum-phase engine: through the fade while one runs. */
    private void processRange(double[][] input, int inOff, double[][][] outputs, int outOff, int numBands, int nCh,
                              int offset, int length)
    {
        while (fadeLeft > 0 && length > 0)
        {
            final int len = Math.min(Math.min(length, fadeLeft), FADE_CHUNK);
            if (fadeBank >= 0) processBankFadeRange(input, inOff, outputs, outOff, numBands, nCh, offset, len);
            else processFadeRange(input, inOff, outputs, outOff, numBands, nCh, offset, len);
            fadeLeft -= len;
            offset += len;
            length -= len;
            if (fadeLeft == 0 && fadeBank >= 0)
            {
                bank = fadeBank;
                fadeBank = -1;
            }
        }
        if (length > 0)
            runTopology(bank(bank), iirTopo, numBands, input, inOff + offset, outputs, outOff + offset, nCh, length);
    }

    /** Clears the memory of every section of a bank. */
    private static void resetBank(IirBank k)
    {
        for (int s = 0; s < k.splitLp.length; ++s)
        {
            k.splitLp[s].reset();
            k.splitHp[s].reset();
        }
        for (final BiquadCascade apChain : k.allpass)
            apChain.reset();
        for (final NormalizedLadder l : k.ladders)
            l.reset();
    }

    /** Starts the fade from the configuration in play to {@code bands} bands on {@code topo}. */
    private void startBandFade(int bands, Topology topo)
    {
        final boolean shared = iirTopo == Topology.CASCADE && topo == Topology.CASCADE;
        fadeFrom = iirBands;
        fadeFromTopo = iirTopo;
        iirBands = bands;
        iirTopo = topo;
        fadeLeft = fadeLength;
        if (!shared)
        {
            // The new configuration on the other bank: the coefficients of the
            // bank in play, its memory from silence.
            fadeBank = 1 - bank;
            final IirBank to = bank(fadeBank);
            to.copyFrom(bank(bank));
            resetBank(to);
            return;
        }
        // The splits that come in, and the corrections of the bands below
        // them, start from silence (their memory may be arbitrarily old).
        final IirBank k = bank(bank);
        for (int s = fadeFrom - 1; s < bands - 1; ++s)
        {
            k.splitLp[s].reset();
            k.splitHp[s].reset();
            for (int b = 0; b < s; ++b)
                k.allpass[b * maxSplits + s].reset();
            if (k.ladders.length > 0)
            {
                for (int st = 0; st < MAX_STAGES_PER_FILTER; ++st)
                {
                    k.ladders[ladderLow(s, st)].reset();
                    k.ladders[ladderHigh(s, st)].reset();
                }
                for (int b = 0; b < s; ++b)
                    for (int st = 0; st < 2; ++st)
                        k.ladders[ladderAllpass(b, s, st)].reset();
            }
        }
    }

    /**
     * Split {@code s} over {@code len} samples: its low branch from
     * {@code work} into {@code low}, and {@code work} to its high branch.
     */
    private void runSplit(IirBank k, int s, double[][] work, int workOff, double[][] low, int lowOff, int nCh,
                          int len)
    {
        if (ladderActive)
        {
            final int perFilter = numStagesPerFilter;
            for (int ch = 0; ch < nCh; ++ch)
            {
                runLadders(k, work[ch], workOff, low[ch], lowOff, len, ladderLow(s, 0), perFilter, ch);
                runLadders(k, work[ch], workOff, work[ch], workOff, len, ladderHigh(s, 0), perFilter, ch);
            }
            advanceLadders(k, ladderLow(s, 0), perFilter, len);
            advanceLadders(k, ladderHigh(s, 0), perFilter, len);
            return;
        }
        for (int ch = 0; ch < nCh; ++ch) System.arraycopy(work[ch], workOff, low[ch], lowOff, len);
        k.splitLp[s].process(low, nCh, lowOff, len);
        k.splitHp[s].process(work, nCh, workOff, len);
    }

    /** The correction of band {@code b} (or tree node {@code b}) for split {@code s}, in place. */
    private void runAllpass(IirBank k, int b, int s, double[][] band, int off, int nCh, int len)
    {
        if (ladderActive)
        {
            for (int ch = 0; ch < nCh; ++ch)
                runLadders(k, band[ch], off, band[ch], off, len, ladderAllpass(b, s, 0), numStagesAllpass, ch);
            advanceLadders(k, ladderAllpass(b, s, 0), numStagesAllpass, len);
            return;
        }
        k.allpass[b * maxSplits + s].process(band, nCh, off, len);
    }

    /**
     * The balanced tree over bands lo..hi, whose signal is in {@code buf}
     * (the buffer of band hi). The middle split r divides it; the low branch
     * goes to band r's buffer and takes the corrections of the splits above r
     * in this range, the high branch those of the splits below r; each half
     * is then divided the same way. The corrections of node r are chains
     * [r * maxSplits + s].
     */
    private void runTree(IirBank k, int lo, int hi, double[][] buf, int bufOff, double[][][] out, int outOff,
                         int nCh, int len)
    {
        if (lo >= hi) return;
        final int r = lo + (hi - lo - 1) / 2;
        final double[][] low = out[r];
        runSplit(k, r, buf, bufOff, low, outOff, nCh, len);
        if (numStagesAllpass > 0)
        {
            for (int s = r + 1; s < hi; ++s) runAllpass(k, r, s, low, outOff, nCh, len);
            for (int s = lo; s < r; ++s) runAllpass(k, r, s, buf, bufOff, nCh, len);
        }
        runTree(k, lo, r, low, outOff, out, outOff, nCh, len);
        runTree(k, r + 1, hi, buf, bufOff, out, outOff, nCh, len);
    }

    /**
     * One configuration of the minimum-phase engine over a range: the input
     * from {@code inPos} into the bands of {@code out} from {@code outPos}.
     */
    private void runTopology(IirBank k, Topology topo, int numBands, double[][] input, int inPos,
                             double[][][] out, int outPos, int nCh, int len)
    {
        final int numSplits = numBands - 1;

        // Non-finite input samples enter as silence: in the recursive
        // sections they would latch for good.
        final double[][] work = workBuf;
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] src = input[ch];
            final double[] dst = work[ch];
            for (int i = 0; i < len; ++i)
            {
                final double v = src[inPos + i];
                dst[i] = Double.isFinite(v) ? v : 0.0;
            }
        }

        if (topo == Topology.BALANCED)
        {
            runTree(k, 0, numSplits, work, 0, out, outPos, nCh, len);
            for (int ch = 0; ch < nCh; ++ch) System.arraycopy(work[ch], 0, out[numBands - 1][ch], outPos, len);
            return;
        }

        // Each split: its low band from what is left, and what is left above
        // it, every section of a filter and every channel in one pass (the
        // memory in double).
        for (int s = 0; s < numSplits; ++s)
            runSplit(k, s, work, 0, out[s], outPos, nCh, len);

        for (int ch = 0; ch < nCh; ++ch) System.arraycopy(work[ch], 0, out[numBands - 1][ch], outPos, len);

        for (int s = 1; s < numSplits; ++s)
            for (int b = 0; b < s; ++b)
                runAllpass(k, b, s, out[b], outPos, nCh, len);
    }

    /**
     * A chunk of a band-count fade of the cascade. The larger topology runs
     * whole; on the way the smaller one is read off it: its top band is what
     * is left above its last split, its other bands are the larger topology's
     * before the corrections of the splits it does not have. Each band then
     * goes from the old topology's version to the new one's (a band beyond
     * the smaller topology is silent in it; leaving, it folds into the top
     * band).
     */
    private void processFadeRange(double[][] input, int inOff, double[][][] outputs, int outOff, int numBands,
                                  int nCh, int offset, int len)
    {
        final IirBank k = bank(bank);
        final int most = Math.max(fadeFrom, iirBands);
        final int fewest = Math.min(fadeFrom, iirBands);
        final boolean adding = iirBands > fadeFrom;
        final double[][] work = workBuf;
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] src = input[ch];
            final double[] dst = work[ch];
            for (int i = 0; i < len; ++i)
            {
                final double v = src[inOff + offset + i];
                dst[i] = Double.isFinite(v) ? v : 0.0;
            }
        }
        final double[][] top = fadeTop;
        for (int s = 0; s < most - 1; ++s)
        {
            if (s == fewest - 1)
                for (int ch = 0; ch < nCh; ++ch) System.arraycopy(work[ch], 0, top[ch], 0, len);
            runSplit(k, s, work, 0, fadeBands[s], 0, nCh, len);
        }
        for (int ch = 0; ch < nCh; ++ch)
            System.arraycopy(work[ch], 0, fadeBands[most - 1][ch], 0, len);
        // The corrections, the smaller topology's lower bands read off before
        // the first split it does not have (fewest - 1 <= most - 2).
        for (int s = 1; s < most - 1; ++s)
        {
            if (s == fewest - 1)
                for (int b = 0; b < fewest - 1; ++b)
                    for (int ch = 0; ch < nCh; ++ch)
                        System.arraycopy(fadeBands[b][ch], 0, fadeLower[b][ch], 0, len);
            for (int b = 0; b < s; ++b)
                runAllpass(k, b, s, fadeBands[b], 0, nCh, len);
        }

        // Each written band from the old version to the new one.
        final double step = 1.0 / (double) fadeLength;
        final double start = (double) (fadeLength - fadeLeft);
        for (int b = 0; b < numBands; ++b)
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] out = outputs[b][ch];
                final int o = outOff + offset;
                final double[] lg = fadeBands[b][ch];
                final double[] sm = b < fewest - 1 ? fadeLower[b][ch] : b == fewest - 1 ? top[ch] : null;
                for (int i = 0; i < len; ++i)
                {
                    final double w = (start + (double) (i + 1)) * step;   // the new topology's share
                    double inLarge = lg[i];
                    if (!adding && b == fewest - 1)
                        for (int j = b + 1; j < most; ++j) inLarge += fadeBands[j][ch][i];
                    final double inSmall = sm != null ? sm[i] : 0.0;
                    out[o + i] = adding ? inSmall + (inLarge - inSmall) * w : inLarge + (inSmall - inLarge) * w;
                }
            }
    }

    /**
     * A chunk of a two-bank fade: the old configuration on the bank in play
     * into scratch, the new one on the other bank into the outputs, then each
     * band from the old version (read in the new band count: missing bands
     * silent, extra ones folded into the top band) to the new one.
     */
    private void processBankFadeRange(double[][] input, int inOff, double[][][] outputs, int outOff, int numBands,
                                      int nCh, int offset, int len)
    {
        final double[][][] before = fadeBands;
        runTopology(bank(bank), fadeFromTopo, fadeFrom, input, inOff + offset, before, 0, nCh, len);
        runTopology(bank(fadeBank), iirTopo, numBands, input, inOff + offset, outputs, outOff + offset, nCh, len);

        final double step = 1.0 / (double) fadeLength;
        final double start = (double) (fadeLength - fadeLeft);
        for (int b = 0; b < numBands; ++b)
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] out = outputs[b][ch];
                final int o = outOff + offset;
                for (int i = 0; i < len; ++i)
                {
                    double was = 0.0;
                    if (b < numBands - 1) was = b < fadeFrom ? before[b][ch][i] : 0.0;
                    else
                        for (int j = b; j < fadeFrom; ++j) was += before[j][ch][i];
                    final double w = (start + (double) (i + 1)) * step;
                    out[o + i] = was + (out[o + i] - was) * w;
                }
            }
    }

    /**
     * Input channels beyond the processed count get band 0 = input and the
     * remaining bands silence, so the band sum still equals the input.
     */
    private static void passExtraChannels(double[][] input, int inOff, double[][][] outputs, int outOff,
                                          int numBands, int nS, int firstCh)
    {
        final int inCh = input.length;
        for (int ch = firstCh; ch < inCh; ++ch)
        {
            for (int b = 0; b < numBands; ++b)
            {
                final double[][] band = outputs[b];
                if (band == null || ch >= band.length) continue;
                final double[] dst = band[ch];
                if (b == 0) System.arraycopy(input[ch], inOff, dst, outOff, nS);
                else Arrays.fill(dst, outOff, outOff + nS, 0.0);
            }
        }
    }

    // -- Linear-phase kernel design -------------------------------------------
    //
    // A design builds every band's kernel into the kernel set that is not
    // playing, one small step at a time, then swaps sets in one assignment:
    // the bands always come from one consistent design, so they keep summing
    // to the delayed input. Steps per band: the band's ideal magnitudes (in
    // LP_MAG_CHUNKS pieces), the long inverse FFT (one step per pass), then
    // one windowed partition transform per partition. The last band is the
    // residual and needs no kernel.

    private int lpKernelSet(int set)
    {
        final int specLen = 2 * lpPart + 2;
        return set * maxSplits * LP_PARTITIONS * specLen;
    }

    /**
     * Steady cost of one partition (units), for {@code bands} bands with
     * {@code parts} convolved kernel partitions in all and {@code chans}
     * channels.
     */
    private double lpPartitionCost(int bands, int chans, int parts)
    {
        return (double) chans
               * (lpCostFft + (double) parts * lpCostCma
                  + (double) (bands - 1) * lpCostIfft
                  + (double) bands * lpCostResidualPerBand);
    }

    /** Captures the design targets and arms the step machine. */
    private void startLinearPhaseDesign()
    {
        lpDesignBands = clamp(numBands, 2, maxBands);
        final double sr = sampleRate;
        for (int sp = 0; sp < lpDesignBands - 1; ++sp)
            lpDesignFc[sp] = clampSplitFreq(frequencies[sp], sr);
        switch (order)
        {
            case 6: lpDesignExpo = 1; break;
            case 12: lpDesignExpo = 2; break;
            case 36: lpDesignExpo = 6; break;
            case 48: lpDesignExpo = 8; break;
            default: lpDesignExpo = 4; break;
        }
        lpDesignSet = 1 - lpActiveSet;
        lpDesignBand = 0;
        lpDesignStep = 0;
        lpDesignParts = 0;
        lpDesignRedo = false;
        lpDesignActive = true;
        // The pace is a share of the steady work of a stereo stream with the
        // design's band count and every partition convolved, whatever the
        // channel count and however many partitions the design keeps.
        lpDesignRate = LP_DESIGN_SHARE * lpPartitionCost(lpDesignBands, 2, (lpDesignBands - 1) * LP_PARTITIONS)
                       / (double) lpPart;
    }

    /**
     * Band magnitudes for a fixed slope over bins [begin, end):
     * |LP| = 1/(1 + r^expo), r = f/fc, written as the real parts of the
     * design spectrum (through lpMag); the running high-pass product goes to
     * lpCarry.
     */
    private void designBandMagnitudes(int expo, double fc, int begin, int end)
    {
        final double[] freq = lpBinFreq;
        final double[] carry = lpCarry;
        final double[] mag = lpMag;
        final double invFc = 1.0 / fc;
        for (int k = begin; k < end; ++k)
        {
            final double r = freq[k] * invFc;
            double rPow = r;
            if (expo >= 2) rPow = r * r;
            if (expo == 6) rPow *= rPow * rPow;
            else
            {
                if (expo >= 4) rPow *= rPow;
                if (expo >= 8) rPow *= rPow;
            }
            final double lp = 1.0 / (1.0 + rPow);
            final double c = carry[k];
            mag[k] = c * lp;
            carry[k] = c * (rPow * lp); // the high-pass, 1 - lp
        }
        final double[] re = lpTimeResponse;
        for (int k = begin; k < end; ++k) re[2 * k] = mag[k];
    }

    /**
     * Cost of the design step the machine runs next (units; an upper bound:
     * a partition the design drops costs less).
     */
    private double linearPhaseDesignStepCost()
    {
        if (lpDesignStep < LP_MAG_CHUNKS) return lpCostMagChunk;
        if (lpDesignStep < LP_MAG_CHUNKS + lpDesignFftSteps) return lpCostDesignFftStep;
        return lpCostPartitionStep;
    }

    /**
     * Runs one design step and returns its cost (units). The last step of the
     * last convolved band swaps the kernel sets.
     */
    private double linearPhaseDesignStep()
    {
        final int designSize = 2 * firLength;
        final int numBins = firLength + 1;
        final int b = lpDesignBand;

        if (lpDesignStep < LP_MAG_CHUNKS)
        {
            // Ideal zero-phase magnitudes of band b. Each band carries the
            // high-pass of every split below it (lpCarry, the running
            // product), like the IIR tree, so the bands telescope to exactly 1
            // (LP + HP = 1 per split). Ideal Linkwitz-Riley
            // |LP| = 1/(1 + (f/fc)^N) with N = order/6 (1/2/4/6/8 ->
            // 6/12/24/36/48 dB/oct), matching the IIR path's slope; integer
            // powers by repeated squaring. The imaginary parts stay zero (set
            // in prepare(); the inverse FFT does not write its input).
            final int chunk = (numBins + LP_MAG_CHUNKS - 1) / LP_MAG_CHUNKS;
            final int begin = Math.min(numBins, lpDesignStep * chunk);
            final int end = Math.min(numBins, begin + chunk);
            if (b == 0) Arrays.fill(lpCarry, begin, end, 1.0);
            designBandMagnitudes(lpDesignExpo, lpDesignFc[b], begin, end);
            ++lpDesignStep;
            return lpCostMagChunk;
        }

        if (lpDesignStep < LP_MAG_CHUNKS + lpDesignFftSteps)
        {
            // The long inverse FFT, one pass per step (bit-identical to a
            // single inverse()).
            lpDesignFft.inverseStep(lpDesignStep - LP_MAG_CHUNKS, lpTimeResponse, 0, lpFirKernel, 0);
            ++lpDesignStep;
            return lpCostDesignFftStep;
        }

        // One partition of band b: the zero-phase response centred at
        // firLength/2 under the flat-top window (a cosine taper over the
        // outer quarter on each side: it leaves the response's core
        // untouched, so the passband and slopes stay exact where a
        // full-length window would smear them), then its spectrum.
        final int part = lpDesignStep - LP_MAG_CHUNKS - lpDesignFftSteps;
        final int halfLen = firLength / 2;
        final int specLen = 2 * lpPart + 2;
        final int first = part * lpPart; // tap index of the partition start (the centre is a partition boundary)
        // Tap i reads the inverse FFT at i - halfLen, wrapped into [0, 2L).
        final int shift = (first >= halfLen) ? -halfLen : designSize - halfLen;
        final int src = first + shift;
        final double[] scratch = lpScratch;
        for (int j = 0; j < lpPart; ++j) scratch[j] = lpFirKernel[src + j] * lpWindow[first + j];
        // A partition whose taps sum below LP_SKIP_L1 is dropped (not
        // convolved): it changes the band by less than that at any frequency.
        double l1 = 0.0;
        for (int j = 0; j < lpPart; ++j) l1 += Math.abs(scratch[j]);
        final int slot = lpDesignSet * maxSplits * LP_PARTITIONS + b * LP_PARTITIONS + part;
        final boolean keep = l1 >= LP_SKIP_L1;
        lpPartOn[slot] = keep;
        double cost = 2.0 * LP_COPY_UNITS_PER_SAMPLE * (double) lpPart;
        if (keep)
        {
            ++lpDesignParts;
            Arrays.fill(scratch, lpPart, scratch.length, 0.0);
            lpFft.forward(scratch, 0, lpKernelSpec, lpKernelSet(lpDesignSet) + (b * LP_PARTITIONS + part) * specLen);
            cost = lpCostPartitionStep;
        }

        if (++lpDesignStep == LP_MAG_CHUNKS + lpDesignFftSteps + LP_PARTITIONS)
        {
            lpDesignStep = 0;
            if (++lpDesignBand == lpDesignBands - 1)
            {
                // Every convolved band of the new design is ready: swap.
                // Partition work started from here on uses it; the work in
                // flight finishes on the previous design.
                lpActiveSet = lpDesignSet;
                lpSetBands[lpActiveSet] = lpDesignBands;
                lpSetParts[lpActiveSet] = lpDesignParts;
                lpDesignActive = false;
            }
        }
        return cost;
    }

    /** Runs a whole design at once (prepare()). */
    private void designLinearPhaseNow()
    {
        lpMagDirty = false;
        startLinearPhaseDesign();
        while (lpDesignActive) linearPhaseDesignStep();
        lpDesignCredit = 0.0;
    }

    /**
     * Advances a pending design within this block's budget. A change that
     * arrives during a design lets the design finish, then starts the next
     * one from the latest targets: under continuous automation the kernels
     * keep landing.
     */
    private void advanceLinearPhaseDesign(int nS)
    {
        if (lpMagDirty)
        {
            lpMagDirty = false;
            if (lpDesignActive) lpDesignRedo = true;
            else
            {
                startLinearPhaseDesign();
                lpDesignCredit = 0.0;
            }
        }
        if (!lpDesignActive) return;

        // A step runs only when the credit covers it, so a block adds at most
        // its budget plus the credit a short block left (under one step), or
        // one step when a step is larger than a block's budget (the credit
        // then builds up over a few blocks). The cap keeps a wait from
        // turning into a burst.
        final double budget = lpDesignRate * (double) nS;
        final double maxStep = stdMax(lpCostMagChunk, stdMax(lpCostDesignFftStep, lpCostPartitionStep));
        lpDesignCredit = stdMin(lpDesignCredit + budget, budget + maxStep);
        while (lpDesignActive)
        {
            // The partition work in flight may still read the set the design
            // writes (right after a swap); the design then waits for it.
            if (lpDesignSet == lpTaskSet && lpTaskUnit < lpTaskUnits) break;
            // Nor the previous design while a fade from it is in flight or still to start.
            if (lpDesignSet == lpTaskFadeSet && lpTaskUnit < lpTaskUnits) break;
            if (lpLaunchedSet >= 0 && lpLaunchedSet != lpActiveSet && lpDesignSet == lpLaunchedSet) break;
            if (linearPhaseDesignStepCost() > lpDesignCredit) break;
            lpDesignCredit -= linearPhaseDesignStep();
            if (!lpDesignActive && lpDesignRedo) startLinearPhaseDesign();
        }
        if (!lpDesignActive) lpDesignCredit = 0.0;
    }

    // -- Linear-phase engine ---------------------------------------------------
    //
    // Input partition i (lpPart samples) is staged into a history ring. When
    // it is complete, the work for it starts: per channel, the transform of
    // the frame [partition i - 1, partition i] into the frequency-domain delay
    // line; per convolved band, the partition products and the inverse
    // transform, whose last half is the band's output partition y_i; then the
    // residual band, the input delayed by the latency minus the other bands.
    // y_i is heard LP_LEAD_BLOCKS partitions later, so the work has the whole
    // of partition i + 1 to run: it is split into units and run in proportion
    // to the samples of partition i + 1 staged so far.

    private int lpRingOf(int ch)
    {
        return ch * LP_RING_BLOCKS * lpPart;
    }

    private int lpOutOf(int ch, int buf, int band)
    {
        return ((ch * 2 + buf) * maxBands + band) * lpPart;
    }

    /**
     * Units of the partition work for one channel: the forward transform,
     * LP_PARTITIONS products and an inverse transform per convolved band,
     * then the residual band.
     */
    private int lpUnitsPerChannel()
    {
        final int fading = lpTaskFadeSet >= 0 ? lpTaskFadeBands - 1 : 0;
        return 2 + (lpTaskBands - 1 + fading) * (LP_PARTITIONS + 1);
    }

    /** Starts the work for the input partition just staged (ring block {@code block}). */
    private void launchLinearPhaseTask(int block)
    {
        // The first partition on a new design also runs the previous one, and
        // each band goes from that design to the new over the partition.
        lpTaskFadeSet = (lpLaunchedSet >= 0 && lpLaunchedSet != lpActiveSet) ? lpLaunchedSet : -1;
        lpTaskFadeBands = lpTaskFadeSet >= 0 ? Math.max(2, lpSetBands[lpTaskFadeSet]) : 0;
        lpLaunchedSet = lpActiveSet;
        lpTaskSet = lpActiveSet;
        lpTaskBands = Math.max(2, lpSetBands[lpActiveSet]);
        lpTaskBlock = block;
        lpTaskFdl = (lpTaskFdl + 1) % LP_PARTITIONS;
        lpTaskUnit = 0;
        lpTaskUnits = numChannels * lpUnitsPerChannel();
        lpTaskDone = 0.0;
        lpTaskTotal = lpPartitionCost(lpTaskBands, numChannels, lpSetParts[lpActiveSet]);
        if (lpTaskFadeSet >= 0)
            lpTaskTotal += (double) numChannels
                           * ((double) lpSetParts[lpTaskFadeSet] * lpCostCma
                              + (double) (lpTaskFadeBands - 1) * lpCostIfft
                              + (double) (lpTaskFadeBands + lpTaskBands) * lpCostResidualPerBand);
    }

    /** Runs one unit of the partition work and returns its cost (units). */
    private double runLinearPhaseUnit(int unit)
    {
        final int perCh = lpUnitsPerChannel();
        final int ch = unit / perCh;
        final int u = unit - ch * perCh;
        final int n = lpPart;
        final int specLen = 2 * n + 2;
        final int fdl = ch * LP_PARTITIONS * specLen;
        final int next = 1 - lpPlay;

        if (u == 0)
        {
            // Overlap-save frame [partition i - 1, partition i].
            final int ring = lpRingOf(ch);
            final int prev = (lpTaskBlock + LP_RING_BLOCKS - 1) & (LP_RING_BLOCKS - 1);
            System.arraycopy(lpRing, ring + prev * n, lpScratch, 0, n);
            System.arraycopy(lpRing, ring + lpTaskBlock * n, lpScratch, n, n);
            lpFft.forward(lpScratch, 0, lpFdl, fdl + lpTaskFdl * specLen);
            return lpCostFft;
        }

        final int conv = lpTaskBands - 1;
        final int delayed = (lpTaskBlock + LP_RING_BLOCKS - LP_PARTITIONS / 2) & (LP_RING_BLOCKS - 1);
        final int delayedInput = lpRingOf(ch) + delayed * n;
        if (u == perCh - 1)
        {
            if (lpTaskFadeSet >= 0) blendLinearPhaseFade(ch, next, delayedInput);
            // Residual band: the input delayed by the latency, minus the
            // convolved bands, so the bands sum to the delayed input exactly.
            final int dst = lpOutOf(ch, next, conv);
            System.arraycopy(lpRing, delayedInput, lpOut, dst, n);
            for (int b = 0; b < conv; ++b)
            {
                final int src = lpOutOf(ch, next, b);
                for (int i = 0; i < n; ++i) lpOut[dst + i] -= lpOut[src + i];
            }
            return (double) lpTaskBands * lpCostResidualPerBand;
        }

        // The bands of the design (then, in a fade, those of the previous one).
        int band = (u - 1) / (LP_PARTITIONS + 1);
        final int step = (u - 1) - band * (LP_PARTITIONS + 1);
        final boolean previous = band >= conv;
        final int set = previous ? lpTaskFadeSet : lpTaskSet;
        if (previous) band -= conv;
        if (step < LP_PARTITIONS)
        {
            if (step == 0) Arrays.fill(lpAccum, 0.0);
            if (!lpPartOn[set * maxSplits * LP_PARTITIONS + band * LP_PARTITIONS + step])
                return 0.0; // a dropped (negligible) partition
            final int kernel = lpKernelSet(set) + (band * LP_PARTITIONS + step) * specLen;
            final int slot = (lpTaskFdl - step + LP_PARTITIONS) % LP_PARTITIONS;
            complexMulAccum(lpAccum, lpFdl, fdl + slot * specLen, lpKernelSpec, kernel, n + 1);
            return lpCostCma;
        }
        lpFft.inverse(lpAccum, 0, lpResult, 0);
        if (previous) System.arraycopy(lpResult, n, lpFadeOld, band * n, n);
        else System.arraycopy(lpResult, n, lpOut, lpOutOf(ch, next, band), n);
        return lpCostIfft;
    }

    /** accum[k] += a[k] * b[k] over interleaved complex bins. */
    private static void complexMulAccum(double[] accum, double[] a, int aOff, double[] b, int bOff, int bins)
    {
        for (int k = 0; k < bins; ++k)
        {
            final double re1 = a[aOff + 2 * k], im1 = a[aOff + 2 * k + 1];
            final double re2 = b[bOff + 2 * k], im2 = b[bOff + 2 * k + 1];
            accum[2 * k] += re1 * re2 - im1 * im2;
            accum[2 * k + 1] += re1 * im2 + im1 * re2;
        }
    }

    /**
     * The fade of one channel's partition from the previous design to the new
     * one. The previous design's bands (its residual made here) are read in
     * the new band count: missing bands silent, extra ones folded into the
     * last band. Each convolved band of the new design then moves from that
     * version to its own along the partition; the residual made after this
     * keeps the sum on the delayed input, exactly.
     */
    private void blendLinearPhaseFade(int ch, int next, int delayedInput)
    {
        final int n = lpPart;
        final int before = lpTaskFadeBands;
        final int conv = lpTaskBands - 1;
        final double[] old = lpFadeOld;
        final int oldResidual = (before - 1) * n;
        System.arraycopy(lpRing, delayedInput, old, oldResidual, n);
        for (int b = 0; b < before - 1; ++b)
            for (int i = 0; i < n; ++i) old[oldResidual + i] -= old[b * n + i];
        final double invN = 1.0 / (double) n;
        for (int b = 0; b < conv; ++b)
        {
            final int out = lpOutOf(ch, next, b);
            final boolean has = b < before;
            final int from = b * n;
            for (int i = 0; i < n; ++i)
            {
                final double was = has ? old[from + i] : 0.0;
                lpOut[out + i] = was + (lpOut[out + i] - was) * ((double) (i + 1) * invN);
            }
        }
    }

    /** Runs the partition work in flight up to {@code staged} samples of its window (lpPart runs it whole). */
    private void runLinearPhaseTask(int staged)
    {
        if (lpTaskSet < 0) return;
        final double target = (staged >= lpPart)
                              ? lpTaskTotal + 1.0
                              : lpTaskTotal * (double) staged / (double) lpPart;
        while (lpTaskUnit < lpTaskUnits && lpTaskDone < target)
            lpTaskDone += runLinearPhaseUnit(lpTaskUnit++);
    }

    private void processLinearPhase(double[][] input, int inOff, double[][][] outputs, int outOff, int numBands,
                                    int nS, int nCh)
    {
        advanceLinearPhaseDesign(nS);

        final int n = lpPart;
        int done = 0;
        while (done < nS)
        {
            final int run = Math.min(nS - done, n - lpPos);
            // The output partition was computed with lpOutBands bands. Until a
            // band-count change lands, missing bands are silent and extra ones
            // fold into the last requested band, so the sum stays exact.
            final int have = lpOutBands;
            final int stage = lpStageBlock * n + lpPos;
            for (int ch = 0; ch < numChannels; ++ch)
            {
                // Stage the input first (the outputs may alias it). Every
                // prepared channel advances, so the shared delay-line slot
                // stays coherent when a later block has more channels.
                final int dst = lpRingOf(ch) + stage;
                if (ch >= nCh)
                {
                    Arrays.fill(lpRing, dst, dst + run, 0.0);
                    continue;
                }
                final double[] src = input[ch];
                final int s0 = inOff + done;
                for (int i = 0; i < run; ++i)
                {
                    final double v = src[s0 + i];
                    lpRing[dst + i] = Double.isFinite(v) ? v : 0.0; // never latch a NaN
                }
                final int o = outOff + done;
                for (int b = 0; b < numBands; ++b)
                {
                    final double[] out = outputs[b][ch];
                    if (b < have) System.arraycopy(lpOut, lpOutOf(ch, lpPlay, b) + lpPos, out, o, run);
                    else Arrays.fill(out, o, o + run, 0.0);
                }
                for (int b = numBands; b < have; ++b)
                {
                    final double[] out = outputs[numBands - 1][ch];
                    final int from = lpOutOf(ch, lpPlay, b) + lpPos;
                    for (int i = 0; i < run; ++i) out[o + i] += lpOut[from + i];
                }
            }
            lpPos += run;
            done += run;
            runLinearPhaseTask(lpPos);

            if (lpPos == n)
            {
                // The work in flight is complete (its window has ended): its
                // partition plays next, and the work for the partition just
                // staged starts.
                if (lpTaskSet >= 0)
                {
                    lpPlay = 1 - lpPlay;
                    lpOutBands = lpTaskBands;
                }
                launchLinearPhaseTask(lpStageBlock);
                lpStageBlock = (lpStageBlock + 1) & (LP_RING_BLOCKS - 1);
                lpPos = 0;
            }
        }
    }

    // -- Test access -----------------------------------------------------------

    /** Kernel partitions the playing linear-phase design convolves for band {@code band}. */
    int keptLinearPhasePartitions(int band)
    {
        if (lpPartOn == null || band < 0 || band >= maxSplits) return 0;
        int kept = 0;
        for (int p = 0; p < LP_PARTITIONS; ++p)
            if (lpPartOn[lpActiveSet * maxSplits * LP_PARTITIONS + band * LP_PARTITIONS + p]) ++kept;
        return kept;
    }

    // -- Helpers ---------------------------------------------------------------

    private static int clamp(int v, int lo, int hi)
    {
        return v < lo ? lo : (hi < v ? hi : v);
    }

    /** The C++ std::clamp: lo when v is below it, hi when v is above it, else v (NaN passes). */
    private static double clampValue(double v, double lo, double hi)
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

    /** Rounds half away from zero (the C++ std::lround). */
    private static long lround(double x)
    {
        final double a = Math.abs(x);
        double r = Math.floor(a);
        if (a - r >= 0.5) r += 1.0;
        return (long) (x < 0.0 ? -r : r);
    }
}
