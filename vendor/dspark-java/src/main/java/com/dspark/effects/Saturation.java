package com.dspark.effects;

import com.dspark.core.AnalogRandom;
import com.dspark.core.BiquadCascade;
import com.dspark.core.BiquadCoeffs;
import com.dspark.core.BiquadDesign;
import com.dspark.core.BiquadFilter;
import com.dspark.core.DryPhase;
import com.dspark.core.DryWetMixer;
import com.dspark.core.Interpolation;
import com.dspark.core.Oversampling;
import com.dspark.core.OversamplingDryPath;
import com.dspark.core.Smoothers;
import com.dspark.effects.SaturationDetail.BitcrusherAlgorithm;
import com.dspark.effects.SaturationDetail.CurveAlgorithm;
import com.dspark.effects.SaturationDetail.DownsampleAlgorithm;
import com.dspark.effects.SaturationDetail.ExciterAlgorithm;
import com.dspark.effects.SaturationDetail.HardClipAlgorithm;
import com.dspark.effects.SaturationDetail.MultiStageAlgorithm;
import com.dspark.effects.SaturationDetail.SaturationAlgorithm;
import com.dspark.effects.SaturationDetail.TanhAlgorithm;
import com.dspark.effects.SaturationDetail.TapeAlgorithm;
import com.dspark.effects.SaturationDetail.TransformerAlgorithm;
import com.dspark.effects.SaturationDetail.TubeAlgorithm;
import com.dspark.effects.SaturationDetail.WavefolderAlgorithm;

import java.util.Arrays;

import static com.dspark.effects.SaturationDetail.clamp;
import static com.dspark.effects.SaturationDetail.decibelsToGain;
import static com.dspark.effects.SaturationDetail.gainToDecibels;
import static com.dspark.effects.SaturationDetail.lround;
import static com.dspark.effects.SaturationDetail.max;
import static com.dspark.effects.SaturationDetail.min;

/**
 * Multi-algorithm saturation with analog simulation.
 * <p>
 * Eleven algorithms, from soft clipping to magnetic tape and transformer
 * models, with drive, character, asymmetry, slew sensitivity, a drive
 * panorama, analog drift, level tracking, programme noise, a tuned comb loop,
 * adaptive blend, drive compensation, a pre high-pass, a four-section tone
 * and a post tilt, DC blocking, mid-side modes, three output modes and a
 * dry/wet mix in the phase of the wet side.
 * <p>
 * It oversamples 2x by default, so its latency is the oversampler's group
 * delay ({@link #getLatency()}); at 1x it is zero. Second-order antialiasing
 * ({@link Antialiasing#SECOND_ORDER}) adds two samples at the default 2x and
 * one at the other factors, which the dry path matches.
 * <p>
 * The saturation stage processes up to 16 channels and no more than the
 * prepared channel count; further channels pass through the linear stages
 * (filters, gain, mix) only. Setters take effect at the next {@link #process}
 * call; parameters set before {@link #prepare} are in force, fully settled,
 * from the first sample.
 */
public final class Saturation
{
    // -- Enums ----------------------------------------------------------------

    /** The harmonic generation topology. */
    public enum Algorithm
    {
        /** Asymmetric triode emulation. Dominant 2nd harmonic, compresses negative excursions. */
        TUBE,
        /** Anhysteretic Langevin magnetisation (tape under AC bias) with dynamic head-bump and HF roll-off. */
        TAPE,
        /** Core saturation model. LF saturates before HF. */
        TRANSFORMER,
        /** Symmetric tanh curve. Odd harmonics only. */
        SOFT_CLIP,
        /** Digital hard clipping. Extreme odd harmonics, highly aliasing without oversampling. */
        HARD_CLIP,
        /** Polynomial waveshaper that synthesises high-frequency harmonic content. */
        EXCITER,
        /** First-order antialiased sine wavefolder. */
        WAVEFOLDER,
        /** Quantisation noise generator with TPDF dithering. */
        BITCRUSHER,
        /** Sample-and-hold rate reduction with anti-aliasing pre-filtering. */
        DOWNSAMPLE,
        /** Serial cascade: Tube, Tape, Transformer. Gain-staged internally. */
        MULTI_STAGE,
        /** A soft clipper shaped by three controls (setCurveShape()). */
        CURVE
    }

    /** The base curve of {@link Algorithm#CURVE} (see setCurveBase()). */
    public enum CurveBase
    {
        TANH, ALGEBRAIC, ARCTANGENT, ERF, CUBIC, VALVE, RECTIFIER
    }

    /** How the oversampler filters (see setOversamplingMode()). */
    public enum OversamplingMode
    {
        /** Linear-phase half-bands: the latency of the factor, the dry path a delay. */
        LINEAR_PHASE,
        /** Elliptic allpass half-bands: a few samples of latency, the dry path through the same filters. */
        MINIMUM_PHASE
    }

    /** How the stereo field is processed. */
    public enum ProcessingMode
    {
        STEREO, MID_ONLY, SIDE_ONLY, MID_SIDE
    }

    /** The final output routing. */
    public enum OutputMode
    {
        NORMAL, WET_ONLY, DELTA
    }

    /**
     * Antiderivative antialiasing order.
     * <ul>
     * <li>OFF: plain curves (the Wavefolder keeps its built-in first-order form).</li>
     * <li>FIRST_ORDER: SoftClip, Tube and HardClip, half a processing sample of
     *   uncompensated delay.</li>
     * <li>SECOND_ORDER: a kernel on every curve except the Bitcrusher, the
     *   Downsample and the Curve, and on all three MultiStage stages from 2x
     *   (the tube stage only at 1x). Every algorithm is delayed by the same
     *   number of processing samples: four at 2x (two base samples),
     *   otherwise min(factor, 3) with the wet path padded to one base sample.
     *   getLatency() reports it at once and the dry, Delta and unprocessed
     *   M/S paths match.</li>
     * </ul>
     */
    public enum Antialiasing
    {
        OFF, FIRST_ORDER, SECOND_ORDER
    }

    private static final Algorithm[] ALGORITHMS = Algorithm.values();
    private static final CurveBase[] CURVE_BASES = CurveBase.values();
    private static final Antialiasing[] ANTIALIASING = Antialiasing.values();

    // -- Constants -------------------------------------------------------------

    private static final int NUM_ALGORITHMS = 11;
    private static final int MAX_CH = 16;
    private static final int STAGE_CHANNELS = 16;
    private static final int COEF_REFRESH = 16;
    private static final double NOISE_OFF_DB = -120.0;
    private static final double MIN_FEEDBACK_HZ = 20.0;
    private static final int MIN_FEEDBACK_DELAY = 16;
    private static final double LOOP_OCTAVE_SECONDS = 0.1;
    private static final double LOOP_CLOSE_SECONDS = 0.02;
    private static final double LOOP_CLOSE_LEVEL = 3.1622776601683794e-4;   // -70 dBFS
    private static final double DYNAMICS_REFERENCE_DB = -18.0;
    private static final double DYNAMICS_REACH_DB = 24.0;
    private static final double EVEN_SHARE = 0.25;
    private static final double COMPENSATION_SHARE = 0.75;
    private static final double CALIBRATION_LEVEL_DB = -18.0;
    private static final int CALIBRATION_SAMPLES = 4096;
    private static final int CALIBRATION_SKIP = 1024;
    private static final double CALIBRATION_QUIET = 0.01;
    private static final int CALIBRATION_BLOCK = 256;
    private static final double CALIBRATION_EVERY = 0.05;
    private static final double DC_CORNER_HZ = 5.0;
    private static final double DC_FADE_SECONDS = 0.05;
    private static final double MODE_DIP_SECONDS = 0.005;
    private static final double MODE_RISE_SHARE = 0.25;
    private static final double DRY_HOLD_SECONDS = 0.05;
    private static final double DRIVE_PAN_DB = 6.0;
    private static final int TONE_SECTIONS = 4;
    private static final double TONE_RAMP_SECONDS = 0.02;
    private static final double AUTOMATIC_INTERNAL_RATE = 88200.0;
    private static final int ALIGN_RING = 32;
    private static final int QUEUE_SLOTS = 32;   // one slot tells full from empty: 31 usable

    // -- Parameters --------------------------------------------------------------

    /** The parameters that travel as one snapshot. */
    private static final class Params
    {
        Algorithm algorithm = Algorithm.SOFT_CLIP;
        ProcessingMode processingMode = ProcessingMode.STEREO;
        OutputMode outputMode = OutputMode.NORMAL;
        double driveDb = 0.0;
        double mix = 1.0;
        double character = 0.0;
        double analogDrift = 0.0;
        double preFilterHpFreq = 20.0;
        double postFilterTiltFreq = 1000.0;
        double postFilterTiltGain = 0.0;
        double outputGain = 0.0;
        boolean dcBlocking = true;

        Params copyFrom(Params o)
        {
            algorithm = o.algorithm;
            processingMode = o.processingMode;
            outputMode = o.outputMode;
            driveDb = o.driveDb;
            mix = o.mix;
            character = o.character;
            analogDrift = o.analogDrift;
            preFilterHpFreq = o.preFilterHpFreq;
            postFilterTiltFreq = o.postFilterTiltFreq;
            postFilterTiltGain = o.postFilterTiltGain;
            outputGain = o.outputGain;
            dcBlocking = o.dcBlocking;
            return this;
        }
    }

    private final Params lastParams = new Params();
    private final Params editPrev = new Params();
    /** The snapshot applied last. */
    private final Params applied = new Params();
    private final Params scratchParams = new Params();
    private final Params[] queue = new Params[QUEUE_SLOTS];
    private int queueHead = 0, queueTail = 0;
    /** A full queue dropped a snapshot: the next block applies lastParams. */
    private boolean paramsPending = false;
    private int antialiasOrder = 0;

    // -- Stages around the curve -------------------------------------------------

    private double asymmetry = 0.0;
    private double programNoiseDb = NOISE_OFF_DB;
    private double levelTracking = 0.0;
    private double trackingCornerHz = 0.0;
    private double combLoop = 0.0;
    private double combLoopHz = 200.0;

    private boolean stagesOn = false;
    private boolean feedingBefore = false;
    private double asymmetryNow = 0.0, asymmetryTarget = 0.0;
    private double dynamicsNow = 0.0, dynamicsTarget = 0.0;
    private double feedbackNow = 0.0, feedbackTarget = 0.0;
    private double loopDelayNow = 0.0, loopGlide = 1.0;
    private double programPeak = 0.0, programFall = 0.999, loopOpen = 1.0;
    private boolean combLoopClosing = false;
    private boolean closingOn = false;
    private double stageRate = 0.0, evenRate = 0.0, followUp = 0.0, followDown = 0.0, focusRate = 0.0;
    private double followPower = 0.0, noiseGain = 0.0, noisePeak = 0.0, noiseFall = 0.0;
    private double dynamicsGainDb = 0.0, dynamicsSmoothRate = 0.0;
    private int noiseHoldSamples = 0, noiseHoldLeft = 0;
    private int stageLatency = 0, feedbackDelay = MIN_FEEDBACK_DELAY, gainTail = 0;
    private double feedbackScale = 1.0;
    private final double[] evenMean = new double[STAGE_CHANNELS];
    private final double[] focusState = new double[STAGE_CHANNELS];
    private final int[] noiseSeed = { 0x1234567, 0x89ABCDE, 0x3C6EF37, 0x5851F42,
                                      0x14057B7, 0x2B992DD, 0x7F4A7C1, 0x61C8864,
                                      0x0BADF00, 0x1F123BB, 0x5DEECE6, 0x41C64E6,
                                      0x6C07896, 0x27D4EB2, 0x165667B, 0x4F6CDD1 };
    private double[] gainRing = new double[0];
    private double[] feedbackRing = new double[0];
    private int gainSize = 64, feedbackSize = 64, gainPos = 0, feedbackPos = 0;

    // -- Drive compensation --------------------------------------------------------

    private boolean driveCompensation = false;
    private int calibrationWait = 0;
    private boolean calibrationDirty = false;
    private boolean calibrationRequested = false;
    private double compensationDb = 0.0;
    private float userOutputGainDb = 0.0f;
    private Saturation calibrator = null;
    private double[] calibrationNoise = new double[0];
    private double[] calibrationWork = new double[0];
    private final double[][] calibrationView = new double[1][];

    // -- Oversampling and the dry path ---------------------------------------------

    private OversamplingMode oversamplingMode = OversamplingMode.LINEAR_PHASE;
    private Oversampling.Band oversamplingBand = Oversampling.Band.PRESET;
    private final OversamplingDryPath dryPath = new OversamplingDryPath();
    private boolean dryThroughFilters = false;

    private double sampleRate = 0.0;
    private int numChannels = 0;
    private int maxBlockSize = 0;
    private boolean prepared = false;

    private final SaturationAlgorithm[] pool = new SaturationAlgorithm[NUM_ALGORITHMS];
    private SaturationAlgorithm active;
    private SaturationAlgorithm next;

    private ProcessingMode procMode = ProcessingMode.STEREO;
    private OutputMode outputMode = OutputMode.NORMAL;
    private boolean dcBlockingEnabled = true;
    private Algorithm currentAlgoType = Algorithm.SOFT_CLIP;

    private final Smoothers.StateVariableSmoother driveSmoother = new Smoothers.StateVariableSmoother();
    private final Smoothers.StateVariableSmoother preHpSmoother = new Smoothers.StateVariableSmoother();
    private final Smoothers.StateVariableSmoother postTiltFreqSmoother = new Smoothers.StateVariableSmoother();
    private final Smoothers.LinearSmoother mixSmoother = new Smoothers.LinearSmoother();
    private final Smoothers.LinearSmoother characterSmoother = new Smoothers.LinearSmoother();
    private final Smoothers.LinearSmoother driftSmoother = new Smoothers.LinearSmoother();
    private final Smoothers.LinearSmoother outputGainSmoother = new Smoothers.LinearSmoother();
    private final Smoothers.LinearSmoother postTiltGainSmoother = new Smoothers.LinearSmoother();
    private final Smoothers.LinearSmoother crossfader = new Smoothers.LinearSmoother();

    private final BiquadFilter preFilter = new BiquadFilter();
    private final BiquadFilter postFilter = new BiquadFilter();
    private final DcBlockFilter dcBlocker = new DcBlockFilter();
    private final DryWetMixer dryWetMixer = new DryWetMixer();

    private final DryPhase dryPreLead = new DryPhase();
    private final DryPhase dryDcLead = new DryPhase();
    private final BiquadFilter dryPre = new BiquadFilter();
    private final DcBlockFilter dryDc = new DcBlockFilter();
    private double dcShare = 1.0, dcStep = 1.0 / 2400.0;
    private double drivePan = 0.0;
    private double panNow = 0.0;
    private final double[] panFrom = { 1.0, 1.0 }, panTo = { 1.0, 1.0 };
    private boolean panning = false;
    private final double[] toneHz = { 100.0, 500.0, 2500.0, 8000.0 };
    private final double[] toneDb = { 0.0, 0.0, 0.0, 0.0 };
    private boolean toneDirty = false;
    private BiquadCascade tone = null;
    private final int[] toneLeft = { -1, -1, -1, -1 };
    private boolean toneOn = false;
    private boolean dcWanted = true;
    private boolean dcSnap = true;
    private double[][] dcScratch = new double[0][0];
    private int dcScratchSamples = 0;
    private ProcessingMode pendingMode = ProcessingMode.STEREO;
    private double modeDip = 0.0, modeDipStep = 1.0 / 240.0;
    private boolean modeDipping = false, modeDipDown = false;
    private boolean dryRunning = false, dryExact = false;
    private int dryHoldSamples = 0;

    private final AnalogRandom.Generator leftDrift = new AnalogRandom.Generator();
    private final AnalogRandom.Generator rightDrift = new AnalogRandom.Generator();

    private double[][] tempBuffer = new double[0][0];
    private double[][] driftBuffer = new double[0][0];
    private double[] msKeepBuffer = new double[0];

    private Oversampling oversampler = null;
    private int oversamplingFactor = 2;
    private boolean oversamplingAutomatic = false;

    private double gainReductionDb = 0.0;

    private boolean adaptiveBlend = false;
    private final double[] prevBlendSample = new double[MAX_CH];

    private double slewSensitivity = 0.0;
    private double slewRateScale = 1.0;
    private double[] driveRamp = new double[0];
    private double[] charRamp = new double[0];
    private final double[] prevSlewSample = new double[MAX_CH];

    private float lastPreHpFreq = -1.0f;
    private float lastPostTiltFreq = -1.0f;
    private float lastPostTiltGain = Float.NaN;

    private boolean secondActive = false;
    private boolean alignFresh = true;
    private final double[][] alignRing = new double[MAX_CH][ALIGN_RING];
    private final double[] keepRing = new double[ALIGN_RING];
    private int alignPos = 0;
    private final double[] dryHold = new double[MAX_CH];
    private final double[] dryHold2 = new double[MAX_CH];
    private double[][] dryAligned = new double[0][0];
    private int dryAlignedSamples = 0;

    // -- Lifecycle -----------------------------------------------------------------

    /** A SoftClip saturation at 2x, every setting at its default. */
    public Saturation()
    {
        pool[0] = new TubeAlgorithm();
        pool[1] = new TapeAlgorithm();
        pool[2] = new TransformerAlgorithm();
        pool[3] = new TanhAlgorithm();
        pool[4] = new HardClipAlgorithm();
        pool[5] = new ExciterAlgorithm();
        pool[6] = new WavefolderAlgorithm();
        pool[7] = new BitcrusherAlgorithm();
        pool[8] = new DownsampleAlgorithm();
        pool[9] = new MultiStageAlgorithm();
        pool[10] = new CurveAlgorithm();
        for (int i = 0; i < QUEUE_SLOTS; ++i) queue[i] = new Params();

        active = pool[Algorithm.SOFT_CLIP.ordinal()];
        next = null;
    }

    /**
     * Prepares every filter, buffer and smoother (allocates). An invalid
     * specification (a rate that is not a positive finite number, or no
     * channels or samples) is ignored and the previous state is kept. The
     * parameters already set are in force, settled, from the first sample,
     * and the drive compensation is measured here.
     *
     * @param sampleRate    host rate in Hz
     * @param numChannels   channels
     * @param maxBlockSize  largest block
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize)
    {
        if (!(sampleRate > 0.0 && sampleRate <= Double.MAX_VALUE && maxBlockSize > 0 && numChannels > 0)) return;
        this.sampleRate = sampleRate;
        this.numChannels = numChannels;
        this.maxBlockSize = maxBlockSize;
        if (oversamplingAutomatic)
        {
            final int automatic = Oversampling.factorForRate(sampleRate, AUTOMATIC_INTERNAL_RATE);
            if (automatic != oversamplingFactor)
            {
                oversamplingFactor = automatic;
                oversampler = null;   // rebuilt below for the new factor
            }
        }
        for (SaturationAlgorithm algo : pool)
            algo.prepare(sampleRate, numChannels, maxBlockSize);

        preFilter.reset();
        postFilter.reset();
        // Order 2 at 5 Hz, for every channel the pipeline touches.
        dcBlocker.setOrder(2);
        dcBlocker.prepare(sampleRate, DcBlockFilter.MAX_CHANNELS, DC_CORNER_HZ);
        dryDc.setOrder(2);
        dryDc.prepare(sampleRate, DcBlockFilter.MAX_CHANNELS, DC_CORNER_HZ);
        dcStep = 1.0 / max(1.0, DC_FADE_SECONDS * sampleRate);
        modeDipStep = 1.0 / max(1.0, MODE_DIP_SECONDS * sampleRate);
        dcScratch = new double[numChannels][maxBlockSize];
        dcScratchSamples = maxBlockSize;
        if (tone == null) tone = new BiquadCascade(4, 16);
        tone.setNumSections(TONE_SECTIONS);
        for (int s = 0; s < TONE_SECTIONS; ++s) tone.setSectionEnabled(s, false);
        Arrays.fill(toneLeft, -1);
        toneOn = false;
        toneDirty = true;
        designTone(0);
        dcWanted = dcBlockingEnabled && curveMakesDc();
        dcShare = dcWanted ? 1.0 : 0.0;
        dcSnap = true;
        modeDipping = false;
        modeDip = 0.0;
        dryPre.reset();
        dryPreLead.prepare(sampleRate, Math.min(numChannels, 8));   // as many as the pre-filter reaches
        dryDcLead.prepare(sampleRate, numChannels);

        lastPreHpFreq = -1.0f;
        lastPostTiltFreq = -1.0f;
        lastPostTiltGain = Float.NaN;
        dryWetMixer.prepare(sampleRate, numChannels, maxBlockSize);

        if (oversamplingFactor > 1 && oversampler == null)
            oversampler = makeOversampler(oversamplingFactor, oversamplingMode, oversamplingBand);
        if (oversampler != null) oversampler.prepare(sampleRate, numChannels, maxBlockSize);
        prepareStages();

        final int up = maxBlockSize * Math.max(1, oversamplingFactor);
        tempBuffer = new double[numChannels][up];
        driftBuffer = new double[numChannels][up];
        msKeepBuffer = new double[up];
        dryAligned = new double[numChannels][maxBlockSize];
        dryAlignedSamples = maxBlockSize;
        driveRamp = new double[up];
        Arrays.fill(driveRamp, 1.0);
        charRamp = new double[up];

        // Keep the dry path aligned with the (latent) oversampled wet path.
        alignDryPath();

        // Base-rate smoothers (consumed once per base sample).
        final double sr = sampleRate;
        mixSmoother.reset(sr, 20.0f, 1.0f);
        preHpSmoother.reset(sr, 30.0f, 0.707f, 20.0f);
        postTiltFreqSmoother.reset(sr, 30.0f, 0.707f, 1000.0f);
        postTiltGainSmoother.reset(sr, 30.0f, 0.0f);
        outputGainSmoother.reset(sr, 20.0f, 0.0f);
        // Processing-rate state (drive, character, drift, crossfade).
        configureProcessingRate(false);

        // Re-apply the parameters: the smoother resets above restored the defaults.
        queueHead = queueTail = 0;
        paramsPending = false;
        applyParamSnapshot(scratchParams.copyFrom(lastParams));

        reset();
        if (driveCompensation) prepareCalibrator();
        calibrate();
        outputGainSmoother.skip();
        calibrationWait = 0;
        prepared = true;
    }

    /** Clears every state, phase memory and history buffer; a pending algorithm switch completes at once. */
    public void reset()
    {
        if (next != null)
        {
            active = next;
            next = null;
        }

        for (SaturationAlgorithm algo : pool)
            algo.reset();

        preFilter.reset();
        postFilter.reset();
        dcBlocker.reset();
        dryWetMixer.reset();
        dcSnap = true;
        if (tone != null) tone.reset();
        modeDipping = false;
        modeDip = 0.0;
        procMode = pendingMode = applied.processingMode;
        dryRunning = false;
        dryHoldSamples = 0;
        if (oversampler != null) oversampler.reset();

        driveSmoother.skip();
        mixSmoother.skip();
        characterSmoother.skip();
        driftSmoother.skip();
        preHpSmoother.skip();
        postTiltFreqSmoother.skip();
        postTiltGainSmoother.skip();
        outputGainSmoother.skip();
        crossfader.setCurrentAndTargetValue(1.0f);

        Arrays.fill(prevBlendSample, 0.0);
        Arrays.fill(prevSlewSample, 0.0);
        alignFresh = true;
        dryPath.reset();
        resetStages();
    }

    // -- Audio processing ------------------------------------------------------------

    /**
     * Processes a planar block in place: parameter updates, mid-side
     * encoding, pre-filter, slew, drift, the curve, mid-side decoding, tone,
     * adaptive blend, post-filter, DC blocking, output gain and the mix.
     * Pass-through until prepare() succeeds. Blocks longer than the prepared
     * maximum are processed in pieces.
     *
     * @param channels    the channels, in place
     * @param numSamples  samples per channel
     */
    public void process(double[][] channels, int numSamples)
    {
        processPieces(channels, 0, numSamples, null, false);
    }

    /**
     * {@link #process}, also giving the dry side of the mix when asked: it is
     * as late as the wet side, behind its filters and in its phase.
     *
     * @param buffer      the audio, in place
     * @param offset      first sample in every channel
     * @param numSamples  samples per channel
     * @param dryOut      takes the dry side at the same indices when not null
     * @param dryTurn     with dryOut: whether the caller hears it mixed, and so in the phase of the wet side
     */
    void processPieces(double[][] buffer, int offset, int numSamples, double[][] dryOut, boolean dryTurn)
    {
        if (!prepared) return;
        handleParameterChanges();

        // Blocks longer than the prepared maximum are processed in pieces;
        // while the tuned feedback runs, a piece is never longer than its delay.
        final int total = numSamples;
        maybeCalibrate(total);
        final int maxBlock = Math.min(Math.max(1, maxBlockSize), syncStages());
        for (int off = 0; off < total; off += maxBlock)
        {
            final int n = Math.min(maxBlock, total - off);
            processChunk(buffer, offset + off, n, dryOut, offset + off, dryTurn);
        }
    }

    /**
     * The oversampler of a factor: from 8x every stage at least 100 dB deep,
     * at 2x and 4x the preset's depth.
     */
    private static Oversampling makeOversampler(int factor, OversamplingMode mode, Oversampling.Band band)
    {
        return new Oversampling(factor, Oversampling.Quality.HIGH,
                                mode == OversamplingMode.MINIMUM_PHASE ? Oversampling.Mode.POLYPHASE_IIR
                                                                       : Oversampling.Mode.LINEAR_PHASE_FIR,
                                factor >= 8 ? Oversampling.Depth.DEEP : Oversampling.Depth.PRESET, band);
    }

    /**
     * Aligns the dry path with the wet one: a delay behind linear-phase
     * filters, the same filters behind minimum-phase ones.
     */
    private void alignDryPath()
    {
        final boolean oversampled = oversampler != null && oversamplingFactor > 1;
        dryThroughFilters = oversampled && oversamplingMode == OversamplingMode.MINIMUM_PHASE;
        if (dryThroughFilters) dryPath.prepare(sampleRate, numChannels, maxBlockSize, oversampler);
        dryWetMixer.setLatencyCompensation((oversampled && !dryThroughFilters) ? oversampler.getLatency() : 0);
    }

    /**
     * Whether the dry side is worked on in this chunk, and how. Full wet and
     * settled it is heard by nobody and is left alone; a mix of zero is the
     * input, so there its phase is left alone too.
     */
    private boolean aimDryPhase(int samples, boolean asked, boolean askedTurn)
    {
        final float mix = mixSmoother.getTargetValue();
        final boolean normal = outputMode == OutputMode.NORMAL;
        final boolean delta = outputMode == OutputMode.DELTA;
        final boolean heard = asked || delta || adaptiveBlend || (normal && mix < 1.0f);
        if (heard) dryHoldSamples = (int) (sampleRate * DRY_HOLD_SECONDS);
        final boolean run = heard || dryHoldSamples > 0;
        if (!heard) dryHoldSamples = Math.max(0, dryHoldSamples - samples);

        final boolean turn = (!normal || mix > 0.0f) && (!asked || askedTurn);
        dryPreLead.aim(turn);
        dryDcLead.aim(turn && dcWanted);
        dryDcLead.setCorner(DryPhase.BUTTERWORTH_SHARE * DC_CORNER_HZ);
        if (run && (!dryRunning || delta != dryExact))
        {
            dryPreLead.reset();
            dryDcLead.reset();
            dryPreLead.land();
            dryDcLead.land();
            dryPre.reset();
            dryDc.reset();
        }
        dryRunning = run;
        dryExact = delta;
        return run;
    }

    private void processChunk(double[][] buffer, int off, int n, double[][] dryOut, int dryOffset, boolean dryTurn)
    {
        final int bufferChannels = buffer.length;
        dcWanted = dcBlockingEnabled && curveMakesDc();
        if (dcSnap)
        {
            // The first block after prepare() or reset(): the filter as the
            // curve in play wants it, with nothing to fade from.
            dcSnap = false;
            dcShare = dcWanted ? 1.0 : 0.0;
            dcBlocker.reset();
            dryDc.reset();
        }
        {
            final double pan = drivePan;
            panning = pan != 0.0 || panNow != 0.0;
            if (panning)
            {
                panGains(panNow, panFrom);
                panGains(pan, panTo);
                panNow = pan;
            }
        }
        final boolean dryWork = aimDryPhase(n, dryOut != null, dryTurn);
        // Second-order antialiasing delays the wet path by whole base
        // samples; the dry capture and the untouched M/S channel follow.
        final boolean second = antialiasOrder == 2;
        if (second != secondActive)
        {
            secondActive = second;
            alignFresh = true;   // hold the first sample in the delay lines
        }
        final boolean seed = second && alignFresh;
        if (second) alignFresh = false;
        if (second)
        {
            final int nCh = Math.min(Math.min(bufferChannels, dryAligned.length), MAX_CH);
            final int nS = Math.min(n, dryAlignedSamples);
            final boolean twoSamples = secondOrderBaseDelay() == 2;
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] in = buffer[ch];
                final double[] out = dryAligned[ch];
                double hold = dryHold[ch];
                if (twoSamples)
                {
                    double older = dryHold2[ch];
                    if (seed && nS > 0) hold = older = in[off];
                    for (int i = 0; i < nS; ++i)
                    {
                        out[i] = older;
                        older = hold;
                        hold = in[off + i];
                    }
                    dryHold[ch] = hold;
                    dryHold2[ch] = older;
                    continue;
                }
                if (seed && nS > 0) hold = in[off];
                for (int i = 0; i < nS; ++i)
                {
                    out[i] = hold;
                    hold = in[off + i];
                }
                dryHold[ch] = hold;
            }
            if (dryThroughFilters) dryPath.process(dryAligned, 0, nS);
        }
        else if (dryThroughFilters || dryWork)
        {
            final int nCh = Math.min(bufferChannels, dryAligned.length);
            final int nS = Math.min(n, dryAlignedSamples);
            for (int ch = 0; ch < nCh; ++ch)
                System.arraycopy(buffer[ch], off, dryAligned[ch], 0, nS);
            if (dryThroughFilters) dryPath.process(dryAligned, 0, nS);
        }
        else
        {
            dryWetMixer.pushDry(buffer, off, n);
        }

        // Pre-filter
        for (int i = 0; i < n; i += COEF_REFRESH)
        {
            final int chunk = Math.min(COEF_REFRESH, n - i);
            float curFreq = lastPreHpFreq;
            for (int k = 0; k < chunk; ++k)
                curFreq = preHpSmoother.getNextValue();

            if (curFreq != lastPreHpFreq)
            {
                final BiquadCoeffs c = BiquadDesign.makeHighPass(sampleRate, curFreq);
                preFilter.setCoeffs(c);
                dryPre.setCoeffs(c);
                lastPreHpFreq = curFreq;
            }
            preFilter.process(buffer, bufferChannels, off + i, chunk);
            if (dryWork)
            {
                if (dryExact)
                {
                    // Delta is what the curve adds: the dry copy crosses the
                    // very filters the wet side does.
                    dryPre.process(dryAligned, dryAligned.length, i, chunk);
                    continue;
                }
                dryPreLead.setCorner(DryPhase.BUTTERWORTH_SHARE * (double) curFreq);
                dryPreLead.process(dryAligned, i, chunk);
                dryDcLead.process(dryAligned, i, chunk);
            }
        }
        if (second || dryThroughFilters || dryWork)
        {
            final int nS = Math.min(n, dryAlignedSamples);
            if (dryWork && dryExact && (dcShare > 0.0 || dcWanted)) applyDcShare(dryDc, dryAligned, 0, nS, dcShare);
            dryWetMixer.pushDry(dryAligned, 0, nS);
        }
        if (dryOut != null)
        {
            final int channels = Math.min(Math.min(bufferChannels, dryOut.length), dryWetMixer.getDryNumChannels());
            final int samples = Math.min(n, dryWetMixer.getDryCapturedSamples());
            for (int ch = 0; ch < channels; ++ch)
                System.arraycopy(dryWetMixer.getDryChannel(ch), 0, dryOut[ch], dryOffset, samples);
        }

        final boolean isMidSide = (procMode == ProcessingMode.MID_ONLY || procMode == ProcessingMode.SIDE_ONLY
                                   || procMode == ProcessingMode.MID_SIDE) && bufferChannels == 2;
        if (isMidSide)
            for (int i = 0; i < n; ++i) MidSide.encodeSample(buffer[0], buffer[1], off + i);

        // MidOnly / SideOnly: the channel that stays unprocessed is kept in
        // the domain the curve works in and restored after it, so it shares
        // the wet path's round trip and delay.
        final int keepChannel =
            (isMidSide && procMode == ProcessingMode.MID_ONLY) ? 1 :
            (isMidSide && procMode == ProcessingMode.SIDE_ONLY) ? 0 : -1;

        if (stagesOn) stagesBefore(buffer, off, n, keepChannel);

        if (oversamplingFactor > 1 && oversampler != null)
        {
            final double[][] up = oversampler.upsample(buffer, off, n);
            final int upLength = oversampler.getHighRateLength();

            final int upSamples = Math.min(upLength, msKeepBuffer.length);
            if (keepChannel >= 0 && keepChannel < up.length && upSamples > 0)
                System.arraycopy(up[keepChannel], 0, msKeepBuffer, 0, upSamples);

            processSaturationPipeline(up, 0, upLength);
            if (second)
                alignSecondOrder(up, 0, upLength, keepChannel, upSamples, oversamplingFactor, seed);

            if (keepChannel >= 0 && keepChannel < up.length && upSamples > 0)
                System.arraycopy(msKeepBuffer, 0, up[keepChannel], 0, upSamples);

            oversampler.downsample(buffer, off, n);
        }
        else
        {
            final int baseSamples = Math.min(n, msKeepBuffer.length);
            if (keepChannel >= 0 && keepChannel < bufferChannels && baseSamples > 0)
                System.arraycopy(buffer[keepChannel], off, msKeepBuffer, 0, baseSamples);

            processSaturationPipeline(buffer, off, n);
            if (second)
                alignSecondOrder(buffer, off, n, keepChannel, baseSamples, 1, seed);

            if (keepChannel >= 0 && keepChannel < bufferChannels && baseSamples > 0)
                System.arraycopy(msKeepBuffer, 0, buffer[keepChannel], off, baseSamples);
        }

        if (stagesOn) stagesAfter(buffer, off, n, keepChannel);

        if (isMidSide)
            for (int i = 0; i < n; ++i) MidSide.decodeSample(buffer[0], buffer[1], off + i);

        // The tone after the curve. With every section out the cascade only
        // remembers the last two samples, so a section that comes in starts
        // from what has just gone by.
        designTone((int) (TONE_RAMP_SECONDS * sampleRate));
        tone.process(buffer, bufferChannels, off, n);
        if (toneOn)
        {
            for (int s = 0; s < TONE_SECTIONS; ++s)
            {
                if (toneLeft[s] < 0) continue;
                toneLeft[s] -= n;
                if (toneLeft[s] <= 0 && !tone.isRamping(s))
                {
                    tone.setSectionEnabled(s, false);
                    toneLeft[s] = -1;
                }
            }
            toneOn = false;
            for (int s = 0; s < TONE_SECTIONS; ++s) toneOn = toneOn || tone.isSectionEnabled(s);
        }

        // Programme-dependent adaptive blend, at the base rate in the L/R
        // domain where the latency-compensated dry capture is valid.
        if (adaptiveBlend)
            applyAdaptiveBlend(buffer, off, n);

        // Post-filter
        for (int i = 0; i < n; i += COEF_REFRESH)
        {
            final int chunk = Math.min(COEF_REFRESH, n - i);
            float curFreq = lastPostTiltFreq;
            float curGain = lastPostTiltGain;
            for (int k = 0; k < chunk; ++k)
            {
                curFreq = postTiltFreqSmoother.getNextValue();
                curGain = postTiltGainSmoother.getNextValue();
            }

            if (curFreq != lastPostTiltFreq || curGain != lastPostTiltGain)
            {
                final BiquadCoeffs c = BiquadDesign.makeTilt(sampleRate, curFreq, curGain);
                postFilter.setCoeffs(c);
                lastPostTiltFreq = curFreq;
                lastPostTiltGain = curGain;
            }
            postFilter.process(buffer, bufferChannels, off + i, chunk);
        }

        if (dcShare > 0.0 || dcWanted) dcShare = applyDcShare(dcBlocker, buffer, off, n, dcShare);
        applyOutputGain(buffer, off, n);
        if (modeDipping) applyModeDip(buffer, off, n);

        // Mix output
        if (outputMode == OutputMode.WET_ONLY)
        {
            // The wet side alone.
        }
        else if (outputMode == OutputMode.DELTA)
        {
            final int nCh = Math.min(bufferChannels, dryWetMixer.getDryNumChannels());
            final int nS = Math.min(n, dryWetMixer.getDryCapturedSamples());
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] wet = buffer[ch];
                final double[] dry = dryWetMixer.getDryChannel(ch);
                for (int i = 0; i < nS; ++i) wet[off + i] -= dry[i];
            }
        }
        else
        {
            dryWetMixer.mixWet(buffer, off, n, (double) mixSmoother.getTargetValue());
        }
    }

    private static void panGains(double p, double[] out)
    {
        final double g = Math.pow(10.0, DRIVE_PAN_DB * p / 20.0);
        out[0] = 1.0 / g;
        out[1] = g;
    }

    // -- Setters --------------------------------------------------------------------

    private void beginEdit()
    {
        editPrev.copyFrom(lastParams);
    }

    private void endEdit()
    {
        sanitizeParams(lastParams, editPrev);
        final int nextTail = (queueTail + 1) & (QUEUE_SLOTS - 1);
        if (nextTail == queueHead)
        {
            // Queue full: lastParams already holds the complete state, so the
            // next block fetches it directly.
            paramsPending = true;
            return;
        }
        queue[queueTail].copyFrom(lastParams);
        queueTail = nextTail;
    }

    /**
     * Sets the saturation algorithm; a change is crossfaded over 10 ms.
     *
     * @param algo  the algorithm (null is ignored)
     */
    public void setAlgorithm(Algorithm algo)
    {
        if (algo == null) return;
        beginEdit();
        lastParams.algorithm = algo;
        endEdit();
    }

    /**
     * Sets the input drive, smoothed over 20 ms.
     *
     * @param dB  drive in decibels, -24 to +48 (0 is unity; default 0)
     */
    public void setDrive(double dB)
    {
        beginEdit();
        lastParams.driveDb = dB;
        endEdit();
    }

    /**
     * Sets the dry/wet blend, smoothed over 20 ms. Between the two ends the
     * dry side takes the phase of the wet side's high-passes and keeps its
     * own level. At zero the output is the input, late by the latency.
     *
     * @param mix01  0 (fully dry) to 1 (fully wet; default)
     */
    public void setMix(double mix01)
    {
        beginEdit();
        lastParams.mix = mix01;
        endEdit();
    }

    /**
     * Sets the character of the algorithm, smoothed over 20 ms: the
     * asymmetry of the Tube, the magnetic coupling of the Tape, the bias of
     * the Transformer, the fold point of the Wavefolder.
     *
     * @param c  -1 to 1 (0, the default, is neutral)
     */
    public void setCharacter(double c)
    {
        beginEdit();
        lastParams.character = c;
        endEdit();
    }

    /**
     * Sets the routing. A change dips the wet side to the aligned dry in
     * 5 ms, switches there (the curves start again from rest) and comes back
     * in 20 ms.
     *
     * @param m  the processing mode (null is ignored)
     */
    public void setProcessingMode(ProcessingMode m)
    {
        if (m == null) return;
        beginEdit();
        lastParams.processingMode = m;
        endEdit();
    }

    /**
     * Sets the output path. Delta is the wet side less the dry one, both
     * through the same high-passes: what the curve changes.
     *
     * @param m  the output mode (null is ignored)
     */
    public void setOutputMode(OutputMode m)
    {
        if (m == null) return;
        beginEdit();
        lastParams.outputMode = m;
        endEdit();
    }

    /**
     * Sets the true-stereo pseudo-random low-frequency drift of the drive,
     * smoothed over 500 ms.
     *
     * @param i  intensity, 0 (default) to 1
     */
    public void setAnalogDrift(double i)
    {
        beginEdit();
        lastParams.analogDrift = i;
        endEdit();
    }

    /**
     * Sets the corner of the pre-saturation high-pass, smoothed over 30 ms.
     *
     * @param hz  10 Hz to Nyquist (default 20)
     */
    public void setPreFilterHpFrequency(double hz)
    {
        beginEdit();
        lastParams.preFilterHpFreq = hz;
        endEdit();
    }

    /**
     * Sets the post-saturation make-up or trim gain, smoothed over 20 ms.
     *
     * @param dB  gain in decibels (default 0)
     */
    public void setOutputGain(double dB)
    {
        beginEdit();
        lastParams.outputGain = dB;
        endEdit();
    }

    /**
     * Enables or disables the fixed 5 Hz DC blocker. It is in the path only
     * while a curve in play makes DC (Tube and MultiStage; Transformer,
     * SoftClip, HardClip, Exciter and Wavefolder with a character; Curve with
     * its asymmetry; any with setAsymmetry()). It comes in and goes out over
     * 50 ms.
     *
     * @param on  true to enable (default)
     */
    public void setDcBlocking(boolean on)
    {
        beginEdit();
        lastParams.dcBlocking = on;
        endEdit();
    }

    /**
     * Enables programme-dependent saturation density: the louder the dry
     * signal, the more of the wet one.
     *
     * @param on  true to enable (default off)
     */
    public void setAdaptiveBlend(boolean on)
    {
        adaptiveBlend = on;
    }

    /**
     * First-order antialiasing on (true) or off (false).
     *
     * @param on  true selects {@link Antialiasing#FIRST_ORDER}, false {@link Antialiasing#OFF}
     */
    public void setAntialiasing(boolean on)
    {
        setAntialiasing(on ? Antialiasing.FIRST_ORDER : Antialiasing.OFF);
    }

    /**
     * Selects the antialiasing order. SECOND_ORDER changes getLatency() at
     * once and the audio from the next block.
     *
     * @param mode  the order (null is ignored)
     */
    public void setAntialiasing(Antialiasing mode)
    {
        if (mode == null) return;
        final int order = clamp(mode.ordinal(), 0, 2);
        antialiasOrder = order;
        for (SaturationAlgorithm a : pool) a.setAntialias(order);
        calibrationRequested = true;
    }

    /** @return the selected antialiasing order */
    public Antialiasing getAntialiasing()
    {
        return ANTIALIASING[antialiasOrder];
    }

    /**
     * A section of the tone after the curve: 0 a low shelf (100 Hz by
     * default), 1 and 2 bells of Q 0.7 (500 Hz and 2.5 kHz), 3 a high shelf
     * (8 kHz). At 0 dB a section is out of the path; it comes in, moves and
     * goes out over 20 ms. Values that are not finite are ignored.
     *
     * @param section  0 to 3 (others are ignored)
     * @param hz       its frequency, 20 Hz to 20 kHz
     * @param gainDb   its gain, -18 to 18 dB
     */
    public void setTone(int section, double hz, double gainDb)
    {
        if (section < 0 || section >= TONE_SECTIONS || !Double.isFinite(hz) || !Double.isFinite(gainDb)) return;
        toneHz[section] = clamp(hz, 20.0, 20000.0);
        toneDb[section] = clamp(gainDb, -18.0, 18.0);
        toneDirty = true;
    }

    /** @return a section's frequency in hertz */
    public double getToneFrequency(int section)
    {
        return toneHz[clamp(section, 0, TONE_SECTIONS - 1)];
    }

    /** @return a section's gain in dB */
    public double getToneGain(int section)
    {
        return toneDb[clamp(section, 0, TONE_SECTIONS - 1)];
    }

    /**
     * The panorama of the drive: the first channel the curve sees gets up to
     * 6 dB more drive and the second up to 6 dB less (at -1), or the other way
     * (at +1); each comes out by the inverse, so only how much it saturates
     * moves. It moves across a block. Values that are not finite are ignored.
     *
     * @param pan  -1 to 1 (0, the default, is centred)
     */
    public void setDrivePan(double pan)
    {
        if (Double.isFinite(pan)) drivePan = clamp(pan, -1.0, 1.0);
    }

    /** @return the panorama of the drive, -1 to 1 */
    public double getDrivePan()
    {
        return drivePan;
    }

    /**
     * Sets a slew-rate saturation multiplier. Non-finite values are ignored.
     *
     * @param amount  0 (off, default) to 1
     */
    public void setSlewSensitivity(double amount)
    {
        if (!Double.isFinite(amount)) return;
        slewSensitivity = clamp(amount, 0.0, 1.0);
    }

    /**
     * The base curve of the Curve algorithm (tanh by default), taken at the
     * next block.
     *
     * @param base  the base curve (null is ignored)
     */
    public void setCurveBase(CurveBase base)
    {
        if (base == null) return;
        curve().setBase(base.ordinal());
    }

    /** @return the base curve of {@link Algorithm#CURVE} */
    public CurveBase getCurveBase()
    {
        return CURVE_BASES[curve().getBase()];
    }

    /**
     * Shapes the Curve algorithm, taken at the next block; values that are
     * not finite keep what they had.
     *
     * @param hardness   how far it stays a straight line, 0 to 0.95 (default 0)
     * @param curvature  how fast it turns, 0.5 to 4 (1, the default, is neutral)
     * @param asym       how unlike the two halves are, -1 to 1 (default 0)
     */
    public void setCurveShape(double hardness, double curvature, double asym)
    {
        curve().setShape(hardness, curvature, asym);
        calibrationRequested = true;
    }

    /** @return how far the Curve algorithm stays straight */
    public double getCurveHardness()
    {
        return curve().getHardness();
    }

    /** @return how fast the Curve algorithm turns */
    public double getCurveCurvature()
    {
        return curve().getCurvature();
    }

    /** @return how unlike the two halves of the Curve algorithm are */
    public double getCurveAsymmetry()
    {
        return curve().getAsymmetry();
    }

    /**
     * Adds a second-order term before the curve of any algorithm: a share of
     * the signal's own square (less its mean), so even harmonics appear.
     * Smoothed over 20 ms; a value that is not finite is ignored.
     *
     * @param amount  -1 to 1 (0, the default, is off)
     */
    public void setAsymmetry(double amount)
    {
        if (!Double.isFinite(amount)) return;
        asymmetry = clamp(amount, -1.0, 1.0);
    }

    /** @return the second-order term before the curve */
    public double getAsymmetry()
    {
        return asymmetry;
    }

    /**
     * Adds white noise before the curve at a level counted from the recent
     * peak of the signal (held for 50 ms, released over 150 ms): silence
     * stays silent. A value that is not finite is ignored.
     *
     * @param levelDb  -90 to -20 dB under the peak; -120 or less (default) is off
     */
    public void setProgramNoise(double levelDb)
    {
        if (!Double.isFinite(levelDb)) return;
        programNoiseDb = clamp(levelDb, NOISE_OFF_DB, -20.0);
    }

    /** @return the level of the programme noise under the peak, in dB */
    public double getProgramNoise()
    {
        return programNoiseDb;
    }

    /**
     * Dynamics around the curve: a level follower (10 ms up, 150 ms down,
     * its gain smoothed over 5 ms) moves the gain before the curve and its
     * inverse comes after it. At +1 the curve always sees the programme at
     * -18 dBFS; at -1 the level into it moves twice as far as the programme.
     * Held to 24 dB either way. A value that is not finite is ignored.
     *
     * @param amount  -1 to 1 (0, the default, is off)
     */
    public void setLevelTracking(double amount)
    {
        if (!Double.isFinite(amount)) return;
        levelTracking = clamp(amount, -1.0, 1.0);
    }

    /** @return the dynamics around the curve */
    public double getLevelTracking()
    {
        return levelTracking;
    }

    /**
     * The band the follower of setLevelTracking() listens to: over 0 a
     * low-pass at this frequency. A value that is not finite is ignored.
     *
     * @param hz  20 to 2000 Hz; 0 or less (default) is the whole band
     */
    public void setTrackingCorner(double hz)
    {
        if (!Double.isFinite(hz)) return;
        trackingCornerHz = hz <= 0.0 ? 0.0 : clamp(hz, 20.0, 2000.0);
    }

    /** @return the band the dynamics follower listens to, in hertz (0: all of it) */
    public double getTrackingCorner()
    {
        return trackingCornerHz;
    }

    /**
     * Feeds the output of the curve back into it, inverted, one period of a
     * frequency late (with the latency): a comb the curve sits inside. The
     * delay cannot be shorter than the latency plus 16 samples; a new
     * frequency is reached by a glide of the delay. While it runs the block
     * is processed in pieces no longer than the delay. Values that are not
     * finite are ignored.
     *
     * @param amount  -0.95 to 0.95 (0, the default, is off)
     * @param hz      frequency of the comb, from 20 Hz (default 200)
     */
    public void setCombLoop(double amount, double hz)
    {
        if (Double.isFinite(amount))
            combLoop = clamp(amount, -0.95, 0.95);
        if (Double.isFinite(hz) && hz > 0.0)
            combLoopHz = clamp(hz, MIN_FEEDBACK_HZ, 20000.0);
    }

    /**
     * Closes the tuned feedback when the programme stops: the loop closes in
     * a few tens of milliseconds once the input has stayed under -70 dBFS.
     *
     * @param on  true to close it (default off)
     */
    public void setCombLoopClosing(boolean on)
    {
        combLoopClosing = on;
    }

    /** @return whether the tuned feedback closes when the programme stops */
    public boolean getCombLoopClosing()
    {
        return combLoopClosing;
    }

    /** @return the amount of the tuned feedback */
    public double getCombLoop()
    {
        return combLoop;
    }

    /** @return the frequency of the tuned feedback as it runs: what the latency allows of the one asked for */
    public double getCombLoopFrequency()
    {
        if (!(sampleRate > 0)) return combLoopHz;
        return sampleRate / (double) (feedbackDelayFor(getLatency()) + getLatency());
    }

    /**
     * Takes back, at the output, three quarters (in decibels) of the level
     * the drive adds: the gain of the curve on pink noise at -18 dBFS RMS
     * through a twin of this processor, against the same noise 40 dB down at
     * no drive, measured whenever the algorithm, drive, character, shape,
     * antialiasing or oversampling changes (at most every 50 ms while they
     * move, at the start of a process() call). Allocates the twin.
     *
     * @param on  true to compensate (default off)
     */
    public void setDriveCompensation(boolean on)
    {
        if (on == driveCompensation) return;
        if (on && prepared) prepareCalibrator();
        driveCompensation = on;
        calibrationRequested = true;
    }

    /** @return whether the level the drive adds is taken back */
    public boolean isDriveCompensation()
    {
        return driveCompensation;
    }

    /** @return the gain the drive compensation is applying, in dB (0 while it is off) */
    public double getDriveCompensationDb()
    {
        return compensationDb;
    }

    /**
     * A post-saturation first-order tilt: -amountDb/2 at DC, unity at the
     * pivot, +amountDb/2 at Nyquist. Both smoothed over 30 ms.
     *
     * @param centerHz  pivot, 100 Hz to Nyquist (default 1000)
     * @param amountDb  total span, -12 to 12 dB (default 0)
     */
    public void setPostFilterTilt(double centerHz, double amountDb)
    {
        beginEdit();
        lastParams.postFilterTiltFreq = centerHz;
        lastParams.postFilterTiltGain = amountDb;
        endEdit();
    }

    /**
     * Sets the oversampling factor (allocates; call before prepare() or
     * prepare() again after it). Turns the automatic factor off.
     *
     * @param factor  1, 2, 4, 8 or 16 (default 2; others are ignored)
     */
    public void setOversampling(int factor)
    {
        if (factor < 1 || factor > 16 || (factor & (factor - 1)) != 0) return;
        oversamplingAutomatic = false;
        applyOversampling(factor);
    }

    /**
     * Lets the host rate choose the factor (allocates): every prepare() takes
     * the smallest factor that brings the curves to at least 88.2 kHz, 2x at
     * 44.1 and 48 kHz and none from 88.2 kHz up.
     *
     * @param on  true to choose the factor from the rate (default off)
     */
    public void setAutomaticOversampling(boolean on)
    {
        if (on == oversamplingAutomatic) return;
        oversamplingAutomatic = on;
        if (on && sampleRate > 0)
        {
            final int automatic = Oversampling.factorForRate(sampleRate, AUTOMATIC_INTERNAL_RATE);
            if (automatic != oversamplingFactor) applyOversampling(automatic);
        }
    }

    /** @return whether the factor follows the host rate */
    public boolean isOversamplingAutomatic()
    {
        return oversamplingAutomatic;
    }

    /**
     * Chooses the filters of the oversampler (allocates). MINIMUM_PHASE runs
     * elliptic allpass half-bands, and the dry path then goes through the
     * same filters.
     *
     * @param mode  the mode (null is ignored; default LINEAR_PHASE)
     */
    public void setOversamplingMode(OversamplingMode mode)
    {
        if (mode == null || mode == oversamplingMode) return;
        oversamplingMode = mode;
        applyOversampling(oversamplingFactor);
    }

    /** @return the filters of the oversampler */
    public OversamplingMode getOversamplingMode()
    {
        return oversamplingMode;
    }

    /**
     * Chooses the band the oversampler's first stage keeps (allocates):
     * PRESET up to 0.46 of the host rate, AUDIBLE up to 20 kHz with a shorter
     * stage the higher the rate.
     *
     * @param band  the band (null is ignored; default PRESET)
     */
    public void setOversamplingBand(Oversampling.Band band)
    {
        if (band == null || band == oversamplingBand) return;
        oversamplingBand = band;
        applyOversampling(oversamplingFactor);
    }

    /** @return the band of the oversampler's first stage */
    public Oversampling.Band getOversamplingBand()
    {
        return oversamplingBand;
    }

    private void applyOversampling(int factor)
    {
        oversamplingFactor = factor;
        if (factor > 1)
        {
            oversampler = makeOversampler(factor, oversamplingMode, oversamplingBand);
            if (sampleRate > 0) oversampler.prepare(sampleRate, numChannels, maxBlockSize);
        }
        else
            oversampler = null;

        // After prepare(): grow the scratch buffers and realign the dry path.
        if (prepared)
        {
            final int upBlock = maxBlockSize * Math.max(1, oversamplingFactor);
            if (tempBuffer.length == 0 || tempBuffer[0].length < upBlock)
                tempBuffer = new double[numChannels][upBlock];
            if (driftBuffer.length == 0 || driftBuffer[0].length < upBlock)
                driftBuffer = new double[numChannels][upBlock];
            if (msKeepBuffer.length < upBlock)
                msKeepBuffer = new double[upBlock];

            alignDryPath();

            // The drive/character/drift/crossfade clocks follow the factor.
            configureProcessingRate(true);
            reset();
            if (driveCompensation) prepareCalibrator();
            calibrate();
        }
    }

    // -- Getters ----------------------------------------------------------------------

    /** @return the oversampling factor (1, 2, 4, 8, 16) */
    public int getOversamplingFactor()
    {
        return oversamplingFactor;
    }

    /**
     * The latency in samples: the oversampler's group delay (0 without
     * oversampling), plus with second-order antialiasing two samples at 2x
     * and one at the other factors.
     *
     * @return the latency
     */
    public int getLatency()
    {
        return ((oversampler != null && oversamplingFactor > 1) ? oversampler.getLatency() : 0)
             + (antialiasOrder == 2 ? secondOrderBaseDelay() : 0);
    }

    /** @return the latency, as {@link #getLatency()} */
    public int getLatencySamples()
    {
        return getLatency();
    }

    /** @return the algorithm last asked for */
    public Algorithm getCurrentAlgorithm()
    {
        return currentAlgoType;
    }

    /** @return the peak gain reduction of the last block, in dB (at most 0) */
    public double getGainReductionDb()
    {
        return gainReductionDb;
    }

    // -- Internals ----------------------------------------------------------------------

    /**
     * Prepares the state that runs at the processing (oversampled) rate: the
     * drive, character and drift smoothers, the algorithm crossfade and the
     * drift generators. keepTargets snaps each smoother to its current target.
     */
    private void configureProcessingRate(boolean keepTargets)
    {
        final double pr = sampleRate * (double) Math.max(1, oversamplingFactor);
        driveSmoother.reset(pr, 20.0f, 0.707f, keepTargets ? driveSmoother.getTargetValue() : 0.0f);
        characterSmoother.reset(pr, 20.0f, keepTargets ? characterSmoother.getTargetValue() : 0.0f);
        driftSmoother.reset(pr, 500.0f, keepTargets ? driftSmoother.getTargetValue() : 0.0f);
        crossfader.reset(pr, 10.0f, 1.0f);

        leftDrift.prepare(pr);
        rightDrift.prepare(pr);
        leftDrift.reseed(0x9E3779B97F4A7C15L);
        rightDrift.reseed(0xBF58476D1CE4E5B9L);
        // Smooth the random drift targets (about 100 ms): a slow wander
        // instead of steps once per second.
        leftDrift.setSmoothing(true, 100.0);
        rightDrift.setSmoothing(true, 100.0);

        // Slew sensitivity is defined per sample at 96 kHz.
        slewRateScale = pr / 96000.0;

        final int up = Math.max(1, maxBlockSize) * Math.max(1, oversamplingFactor);
        if (driveRamp.length < up)
        {
            driveRamp = new double[up];
            Arrays.fill(driveRamp, 1.0);
        }
        if (charRamp.length < up) charRamp = new double[up];
    }

    /**
     * Keeps every snapshot well-formed: a value that is not finite reverts to
     * the previous one (enums cannot leave their range in Java).
     */
    private static void sanitizeParams(Params p, Params prev)
    {
        if (!Double.isFinite(p.driveDb)) p.driveDb = prev.driveDb;
        if (!Double.isFinite(p.mix)) p.mix = prev.mix;
        if (!Double.isFinite(p.character)) p.character = prev.character;
        if (!Double.isFinite(p.analogDrift)) p.analogDrift = prev.analogDrift;
        if (!Double.isFinite(p.preFilterHpFreq)) p.preFilterHpFreq = prev.preFilterHpFreq;
        if (!Double.isFinite(p.postFilterTiltFreq)) p.postFilterTiltFreq = prev.postFilterTiltFreq;
        if (!Double.isFinite(p.postFilterTiltGain)) p.postFilterTiltGain = prev.postFilterTiltGain;
        if (!Double.isFinite(p.outputGain)) p.outputGain = prev.outputGain;
    }

    private void handleParameterChanges()
    {
        while (queueHead != queueTail)
        {
            applyParamSnapshot(scratchParams.copyFrom(queue[queueHead]));
            queueHead = (queueHead + 1) & (QUEUE_SLOTS - 1);
        }

        // An edit that could not be queued: lastParams is the newest complete state.
        if (paramsPending)
        {
            paramsPending = false;
            applyParamSnapshot(scratchParams.copyFrom(lastParams));
        }
    }

    private static boolean makesDc(Algorithm a, double character, CurveAlgorithm curve)
    {
        switch (a)
        {
            case TUBE:
            case MULTI_STAGE: return true;
            case TRANSFORMER:
            case SOFT_CLIP:
            case HARD_CLIP:
            case EXCITER:
            case WAVEFOLDER: return character != 0.0;
            case CURVE: return curve.getAsymmetry() != 0.0 || curve.getBase() >= CurveBase.VALVE.ordinal();
            case TAPE:
            case BITCRUSHER:
            case DOWNSAMPLE: return false;
            default: return true;
        }
    }

    /**
     * Whether a curve in play makes DC from a signal without it: Tube and
     * MultiStage always; Transformer, SoftClip, HardClip, Exciter and
     * Wavefolder with a character; Curve with its asymmetry; any with
     * setAsymmetry(). Tape, Bitcrusher and Downsample never.
     */
    private boolean curveMakesDc()
    {
        if (asymmetry != 0.0) return true;
        final CurveAlgorithm c = curve();
        if (makesDc(applied.algorithm, applied.character, c)) return true;
        for (int a = 0; a < pool.length; ++a)
            if (pool[a] == active && makesDc(ALGORITHMS[a], applied.character, c)) return true;
        return false;
    }

    /**
     * The DC filter on a block by its share, which moves toward 1 while
     * wanted and toward 0 otherwise. At 1 it is the filter itself.
     *
     * @return the share at the end of the block
     */
    private double applyDcShare(DcBlockFilter filter, double[][] view, int off, int n, double share)
    {
        final boolean wanted = dcWanted;
        if (wanted && share >= 1.0)
        {
            filter.process(view, view.length, off, n);
            return 1.0;
        }
        if (wanted && share <= 0.0) filter.reset();
        final int nS = Math.min(n, dcScratchSamples);
        final int nCh = Math.min(view.length, dcScratch.length);
        for (int ch = 0; ch < nCh; ++ch)
            System.arraycopy(view[ch], off, dcScratch[ch], 0, nS);
        filter.process(dcScratch, dcScratch.length, 0, nS);
        double s = share;
        for (int i = 0; i < nS; ++i)
        {
            s = wanted ? min(1.0, s + dcStep) : max(0.0, s - dcStep);
            final double g = s;
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] x = view[ch];
                x[off + i] += g * (dcScratch[ch][i] - x[off + i]);
            }
        }
        return s;
    }

    /**
     * A change of processing mode: the wet side goes to the aligned dry over
     * a ramp, the mode changes at the bottom (the curves start again from
     * rest there), and it comes back.
     */
    private void applyModeDip(double[][] buffer, int off, int n)
    {
        final int nCh = Math.min(buffer.length, dryWetMixer.getDryNumChannels());
        final int nS = Math.min(n, dryWetMixer.getDryCapturedSamples());
        for (int i = 0; i < nS; ++i)
        {
            if (modeDipDown) modeDip = min(1.0, modeDip + modeDipStep);
            else modeDip = max(0.0, modeDip - modeDipStep * MODE_RISE_SHARE);
            final double g = modeDip;
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] wet = buffer[ch];
                wet[off + i] += g * (dryWetMixer.getDryChannel(ch)[i] - wet[off + i]);
            }
        }
        if (modeDipDown && modeDip >= 1.0)
        {
            // At the bottom: the new mode from the next block on.
            procMode = pendingMode;
            for (SaturationAlgorithm algo : pool)
                algo.reset();
            modeDipDown = false;
        }
        else if (!modeDipDown && modeDip <= 0.0)
        {
            modeDipping = false;
            if (pendingMode != procMode)
            {
                modeDipping = true;
                modeDipDown = true;
            }
        }
    }

    private void applyParamSnapshot(Params p)
    {
        driveSmoother.setTargetValue(clampf((float) p.driveDb, -24.0f, 48.0f));
        mixSmoother.setTargetValue(clampf((float) p.mix, 0.0f, 1.0f));
        characterSmoother.setTargetValue(clampf((float) p.character, -1.0f, 1.0f));
        driftSmoother.setTargetValue(clampf((float) p.analogDrift, 0.0f, 1.0f));
        userOutputGainDb = (float) p.outputGain;
        outputGainSmoother.setTargetValue(userOutputGainDb + (float) compensationDb);
        if (p.algorithm != applied.algorithm || p.driveDb != applied.driveDb || p.character != applied.character)
            calibrationDirty = true;
        applied.copyFrom(p);

        final float nyquist = (float) sampleRate / 2.0f;
        preHpSmoother.setTargetValue(clampf((float) p.preFilterHpFreq, 10.0f, nyquist));
        postTiltFreqSmoother.setTargetValue(clampf((float) p.postFilterTiltFreq, 100.0f, nyquist));
        postTiltGainSmoother.setTargetValue(clampf((float) p.postFilterTiltGain, -12.0f, 12.0f));

        dcBlockingEnabled = p.dcBlocking;
        if (p.processingMode != procMode || modeDipping)
        {
            pendingMode = p.processingMode;
            if (!modeDipping && p.processingMode != procMode)
            {
                modeDipping = true;
                modeDipDown = true;
            }
        }
        outputMode = p.outputMode;
        currentAlgoType = p.algorithm;

        // Algorithm switching: the crossfader fades active out towards next
        // (1 to 0); processSaturationPipeline resolves the end of the fade by
        // its final value (0 promotes next, 1 discards it).
        final SaturationAlgorithm requested = pool[p.algorithm.ordinal()];
        final SaturationAlgorithm act = active;
        final SaturationAlgorithm nxt = next;
        if (requested == act)
        {
            // Back to the algorithm still playing: steer a running fade back
            // to 1 so the pending one is discarded when it lands.
            if (nxt != null)
            {
                crossfader.setTargetValue(1.0f);
                if (!crossfader.isSmoothing())
                {
                    nxt.reset();
                    next = null;
                }
            }
        }
        else if (requested == nxt)
        {
            crossfader.setTargetValue(0.0f);
        }
        else
        {
            // A new target: clear its state, it has not been heard for a while.
            requested.reset();
            next = requested;
            crossfader.setTargetValue(0.0f);
        }
    }

    private static float clampf(float v, float lo, float hi)
    {
        return v < lo ? lo : (hi < v ? hi : v);
    }

    /** Processing samples of second-order delay inside the algorithms: 4 at 2x, otherwise min(factor, 3). */
    private int secondOrderDelay()
    {
        return oversamplingFactor == 2 ? 4 : clamp(oversamplingFactor, 1, 3);
    }

    /** Base samples that second-order antialiasing adds: 2 at 2x, else 1. */
    private int secondOrderBaseDelay()
    {
        return (oversampler != null && oversamplingFactor == 2) ? 2 : 1;
    }

    private void processSaturationPipeline(double[][] buffer, int off, int nS)
    {
        final SaturationAlgorithm primary = active;
        final SaturationAlgorithm secondary = next;
        final boolean xfading = secondary != null && crossfader.isSmoothing();

        // Channels beyond the prepared count (or 16) bypass the saturation stage.
        final int nCh = Math.min(Math.min(buffer.length, MAX_CH), numChannels);

        final double driveDbTarget = driveSmoother.getTargetValue();
        final double driveGainTarget = decibelsToGain(driveDbTarget);
        final double characterTarget = characterSmoother.getTargetValue();

        double updateRate = sampleRate;
        if (oversamplingFactor > 1) updateRate *= oversamplingFactor;

        final int commonDelay = secondOrderDelay();
        if (primary != null)
        {
            primary.latchOrder(commonDelay);
            primary.update(driveGainTarget, characterTarget, updateRate);
        }
        if (secondary != null)
        {
            secondary.latchOrder(commonDelay);
            secondary.update(driveGainTarget, characterTarget, updateRate);
        }

        double peakInOriginal = 0.0;
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] d = buffer[ch];
            for (int i = 0; i < nS; ++i) peakInOriginal = max(peakInOriginal, Math.abs(d[off + i]));
        }

        // 1. Slew (in place)
        final double slewAmt = slewSensitivity;
        if (slewAmt > 0.0)
        {
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] data = buffer[ch];
                for (int i = 0; i < nS; ++i)
                {
                    final double dry = data[off + i];
                    final double delta = (dry - prevSlewSample[ch]) * slewRateScale;
                    data[off + i] += Math.tanh(Math.abs(delta)) * slewAmt * dry;
                    prevSlewSample[ch] = dry;
                }
            }
        }

        // 2. Drift: one draw per generator per sample; channels beyond the
        //    second share the right generator's value.
        final float driftIntensity = driftSmoother.getTargetValue();
        final boolean useDrift = driftIntensity > 0.01f;
        if (useDrift)
        {
            for (int i = 0; i < nS; ++i)
            {
                final float driftS = driftSmoother.getNextValue();
                final double noiseL = leftDrift.getNextSample();
                final double noiseR = (nCh > 1) ? rightDrift.getNextSample() : 0.0;
                for (int ch = 0; ch < nCh; ++ch)
                    driftBuffer[ch][i] = 1.0 + (double) driftS * (ch == 0 ? noiseL : noiseR);
            }
        }

        // 3. Drive and character trajectories, advanced once per sample and
        //    shared by both algorithms of a crossfade. With the knob still the
        //    smoother gives its target at every sample: one gain for the block.
        if (!driveSmoother.isSmoothing())
        {
            final double still = decibelsToGain(driveSmoother.getNextValue());
            for (int i = 0; i < nS; ++i)
            {
                driveRamp[i] = still;
                charRamp[i] = characterSmoother.getNextValue();
            }
        }
        else
            for (int i = 0; i < nS; ++i)
            {
                driveRamp[i] = decibelsToGain(driveSmoother.getNextValue());
                charRamp[i] = characterSmoother.getNextValue();
            }

        // 4. Primary saturation (and the secondary during a crossfade)
        if (xfading)
        {
            for (int ch = 0; ch < nCh; ++ch)
                System.arraycopy(buffer[ch], off, tempBuffer[ch], 0, nS);

            dispatchSaturator(primary, buffer, off, nS, useDrift);
            dispatchSaturator(secondary, tempBuffer, 0, nS, useDrift);

            for (int i = 0; i < nS; ++i)
            {
                final double fade = crossfader.getNextValue();
                for (int ch = 0; ch < nCh; ++ch)
                {
                    final double[] out = buffer[ch];
                    out[off + i] = out[off + i] * fade + tempBuffer[ch][i] * (1.0 - fade);
                }
            }

            if (!crossfader.isSmoothing())
            {
                if (crossfader.getCurrentValue() < 0.5f)
                {
                    // The fade ran to 0: the pending algorithm takes over.
                    active = secondary;
                    next = null;
                    if (primary != null) primary.reset();
                }
                else
                {
                    // A reverted fade landed back on the active algorithm.
                    secondary.reset();
                    next = null;
                }
                crossfader.setCurrentAndTargetValue(1.0f);
            }
        }
        else
        {
            dispatchSaturator(primary, buffer, off, nS, useDrift);
        }

        // Gain reduction
        double peakOut = 0.0;
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] d = buffer[ch];
            for (int i = 0; i < nS; ++i) peakOut = max(peakOut, Math.abs(d[off + i]));
        }

        final double peakInDriven = peakInOriginal * driveGainTarget;
        if (peakInDriven > 1e-6)
        {
            final double ratio = min(peakOut / peakInDriven, 1.0);
            gainReductionDb = gainToDecibels(ratio);
        }
        else
        {
            gainReductionDb = 0.0;
        }
    }

    /**
     * Completes the second-order delay to whole base samples: a factor-N
     * stage adds the rest of N (of 2N = 4 at 2x) to every saturated channel;
     * the M/S snapshot that is restored unprocessed is delayed by all of it.
     * seed fills the rings with each channel's first sample.
     */
    private void alignSecondOrder(double[][] view, int off, int nS, int keepChannel, int keepSamples,
                                  int factor, boolean seed)
    {
        final int nCh = Math.min(Math.min(view.length, MAX_CH), numChannels);
        final int target = factor == 2 ? 4 : clamp(factor, 1, ALIGN_RING - 1);
        final int pad = target - (factor == 2 ? 4 : clamp(factor, 1, 3));
        int pos;
        if (pad > 0)
            for (int ch = 0; ch < nCh; ++ch)
            {
                if (ch == keepChannel) continue;
                final double[] d = view[ch];
                final double[] ring = alignRing[ch];
                if (seed && nS > 0) Arrays.fill(ring, d[off]);
                pos = alignPos;
                for (int i = 0; i < nS; ++i)
                {
                    ring[pos] = d[off + i];
                    d[off + i] = ring[(pos - pad) & (ALIGN_RING - 1)];
                    pos = (pos + 1) & (ALIGN_RING - 1);
                }
            }
        if (keepChannel >= 0 && keepSamples > 0)
        {
            final double[] d = msKeepBuffer;
            final double[] ring = keepRing;
            if (seed) Arrays.fill(ring, d[0]);
            pos = alignPos;
            final int delay = target;
            for (int i = 0; i < keepSamples; ++i)
            {
                ring[pos] = d[i];
                d[i] = ring[(pos - delay) & (ALIGN_RING - 1)];
                pos = (pos + 1) & (ALIGN_RING - 1);
            }
        }
        alignPos = (alignPos + nS) & (ALIGN_RING - 1);
    }

    /**
     * Programme-dependent dry/wet density blend (base rate, L/R domain),
     * against the latency-compensated dry capture.
     */
    private void applyAdaptiveBlend(double[][] buffer, int off, int n)
    {
        final int nCh = Math.min(Math.min(buffer.length, dryWetMixer.getDryNumChannels()), MAX_CH);
        final int nS = Math.min(n, dryWetMixer.getDryCapturedSamples());

        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] wetData = buffer[ch];
            final double[] dryData = dryWetMixer.getDryChannel(ch);
            for (int i = 0; i < nS; ++i)
            {
                final double dry = dryData[i];
                final double wet = wetData[off + i];
                final double avg = (Math.abs(prevBlendSample[ch]) + Math.abs(dry)) * 0.5;
                final double apply = clamp(avg, 0.0, 1.0);
                wetData[off + i] = dry * (1.0 - apply) + wet * apply;
                prevBlendSample[ch] = dry;
            }
        }
    }

    private void dispatchSaturator(SaturationAlgorithm algo, double[][] buffer, int off, int nS, boolean useDrift)
    {
        if (algo == null) return;
        processCore(algo, algo.getType(), buffer, off, nS, useDrift);
    }

    /** One sample of an algorithm's plain (or first-order) path. */
    private static double plainSample(SaturationAlgorithm algo, Algorithm type, double x, double drive,
                                      double character, int ch)
    {
        switch (type)
        {
            case TUBE: return ((TubeAlgorithm) algo).processSample(x, drive, character, ch);
            case TAPE: return ((TapeAlgorithm) algo).processSample(x, drive, character, ch);
            case TRANSFORMER: return ((TransformerAlgorithm) algo).processSample(x, drive, character, ch);
            case SOFT_CLIP: return ((TanhAlgorithm) algo).processSample(x, drive, character, ch);
            case HARD_CLIP: return ((HardClipAlgorithm) algo).processSample(x, drive, character, ch);
            case EXCITER: return ((ExciterAlgorithm) algo).processSample(x, drive, character, ch);
            case WAVEFOLDER: return ((WavefolderAlgorithm) algo).processSample(x, drive, character, ch);
            case BITCRUSHER: return ((BitcrusherAlgorithm) algo).processSample(x, drive, character, ch);
            case DOWNSAMPLE: return ((DownsampleAlgorithm) algo).processSample(x, drive, character, ch);
            case MULTI_STAGE: return ((MultiStageAlgorithm) algo).processSample(x, drive, character, ch);
            default: return ((CurveAlgorithm) algo).processSample(x, drive, character, ch);
        }
    }

    /**
     * One sample of an algorithm with second-order antialiasing: the
     * MultiStage's own aligned form, a kernel padded to the common delay, or
     * the plain curve delayed by all of it.
     */
    private static double secondSample(SaturationAlgorithm algo, Algorithm type, double x, double drive,
                                       double character, int ch)
    {
        switch (type)
        {
            case MULTI_STAGE: return ((MultiStageAlgorithm) algo).processSecond(x, drive, character, ch);
            case TUBE: return algo.padKernel(((TubeAlgorithm) algo).processSecond(x, drive, character, ch), ch);
            case TAPE: return algo.padKernel(((TapeAlgorithm) algo).processSecond(x, drive, character, ch), ch);
            case TRANSFORMER: return algo.padKernel(((TransformerAlgorithm) algo).processSecond(x, drive, character, ch), ch);
            case SOFT_CLIP: return algo.padKernel(((TanhAlgorithm) algo).processSecond(x, drive, character, ch), ch);
            case HARD_CLIP: return algo.padKernel(((HardClipAlgorithm) algo).processSecond(x, drive, character, ch), ch);
            case EXCITER: return algo.padKernel(((ExciterAlgorithm) algo).processSecond(x, drive, character, ch), ch);
            case WAVEFOLDER: return algo.padKernel(((WavefolderAlgorithm) algo).processSecond(x, drive, character, ch), ch);
            case BITCRUSHER: return algo.align(((BitcrusherAlgorithm) algo).processSample(x, drive, character, ch), ch);
            case DOWNSAMPLE: return algo.align(((DownsampleAlgorithm) algo).processSample(x, drive, character, ch), ch);
            default: return algo.align(((CurveAlgorithm) algo).processSample(x, drive, character, ch), ch);
        }
    }

    private void processCore(SaturationAlgorithm algo, Algorithm type, double[][] buffer, int off, int nS,
                             boolean useDrift)
    {
        final int nCh = Math.min(Math.min(buffer.length, MAX_CH), numChannels);

        if (algo.order() == 2)
        {
            // Second order: the curve's kernel where one exists, otherwise the
            // plain curve delayed to the same alignment.
            for (int i = 0; i < nS; ++i)
            {
                final double driveGainS = driveRamp[i];
                final double charS = charRamp[i];
                for (int ch = 0; ch < nCh; ++ch)
                {
                    final double drift = useDrift ? driftBuffer[ch][i] : 1.0;
                    final double[] data = buffer[ch];
                    final double pg = panning && ch < 2 ? panGain(ch, i, nS) : 1.0;
                    data[off + i] = secondSample(algo, type, data[off + i], driveGainS * drift * pg, charS * drift, ch);
                    if (pg != 1.0) data[off + i] /= pg;
                }
            }
            return;
        }

        for (int i = 0; i < nS; ++i)
        {
            final double driveGainS = driveRamp[i];
            final double charS = charRamp[i];

            for (int ch = 0; ch < nCh; ++ch)
            {
                final double drift = useDrift ? driftBuffer[ch][i] : 1.0;
                final double[] data = buffer[ch];
                if (panning && ch < 2)
                {
                    final double pg = panGain(ch, i, nS);
                    data[off + i] = plainSample(algo, type, data[off + i], driveGainS * drift * pg, charS * drift, ch) / pg;
                    continue;
                }
                data[off + i] = plainSample(algo, type, data[off + i], driveGainS * drift, charS * drift, ch);
            }
        }
    }

    /**
     * The tone's sections from what was set: a section at 0 dB ramps to the
     * identity and leaves; one coming in starts as the identity of its own
     * design and ramps to it.
     */
    private void designTone(int ramp)
    {
        if (tone == null || !toneDirty) return;
        toneDirty = false;
        final double fs = sampleRate;
        for (int s = 0; s < TONE_SECTIONS; ++s)
        {
            final double hz = min(toneHz[s], 0.45 * fs);
            final double db = toneDb[s];
            if (db == 0.0)
            {
                if (tone.isSectionEnabled(s) && toneLeft[s] < 0)
                {
                    // Out of the path once the ramp is over and what the
                    // section still adds has died away with its poles.
                    tone.rampToBypass(s, ramp);
                    toneLeft[s] = ramp + poleDecaySamples(tone.getTargetCoeffs(s));
                }
                continue;
            }
            final BiquadCoeffs c = s == 0 ? BiquadDesign.makeLowShelf(fs, hz, db)
                                 : s == 3 ? BiquadDesign.makeHighShelf(fs, hz, db)
                                          : BiquadDesign.makePeak(fs, hz, 0.7, db);
            if (!tone.isSectionEnabled(s))
            {
                tone.setCoeffs(s, BiquadDesign.bypassed(c));
                tone.setSectionEnabled(s, true);
            }
            toneLeft[s] = -1;
            tone.rampCoeffs(s, c, ramp);
            toneOn = true;
        }
    }

    /** Samples for the poles of a section to fall 140 dB (what it adds once transparent). */
    private int poleDecaySamples(BiquadCoeffs c)
    {
        final double disc = c.a1 * c.a1 - 4.0 * c.a2;
        final double radius = disc < 0.0 ? Math.sqrt(max(0.0, c.a2))
                                         : 0.5 * (Math.abs(c.a1) + Math.sqrt(disc));
        if (!(radius > 0.0)) return 0;
        if (!(radius < 1.0)) return (int) sampleRate;
        return (int) min(sampleRate, Math.ceil(Math.log(1e-7) / Math.log(radius)));
    }

    /** The drive's gain of a channel at a sample of the block (a straight line between its ends). */
    private double panGain(int ch, int i, int samples)
    {
        return panFrom[ch] + (panTo[ch] - panFrom[ch]) * (double) (i + 1) / (double) Math.max(1, samples);
    }

    private void applyOutputGain(double[][] buffer, int off, int n)
    {
        final float targetGainDb = outputGainSmoother.getTargetValue();
        if (Math.abs(targetGainDb - outputGainSmoother.getCurrentValue()) < 0.001f)
        {
            final double g = decibelsToGain(targetGainDb);
            for (double[] ch : buffer)
                for (int i = 0; i < n; ++i) ch[off + i] *= g;
        }
        else
        {
            final int nCh = buffer.length;
            for (int i = 0; i < n; ++i)
            {
                final double gain = decibelsToGain(outputGainSmoother.getNextValue());
                for (int ch = 0; ch < nCh; ++ch) buffer[ch][off + i] *= gain;
            }
        }
    }

    // -- Stages around the curve -------------------------------------------------

    private CurveAlgorithm curve()
    {
        return (CurveAlgorithm) pool[Algorithm.CURVE.ordinal()];
    }

    /** Samples the feedback line delays so that, with a latency, the loop is one period long. */
    private int feedbackDelayFor(int latency)
    {
        final double hz = combLoopHz;
        final int period = (int) lround(sampleRate / max(hz, 1.0));
        return clamp(period - latency, MIN_FEEDBACK_DELAY, Math.max(MIN_FEEDBACK_DELAY, feedbackSize / 2));
    }

    private static int pow2(int n)
    {
        int p = 64;
        while (p < n) p <<= 1;
        return p;
    }

    /** Sizes the memory of the stages. */
    private void prepareStages()
    {
        final int block = Math.max(1, maxBlockSize);
        gainSize = pow2(block + 1024);
        gainRing = new double[gainSize];
        Arrays.fill(gainRing, 1.0);
        feedbackSize = pow2(2 * (int) Math.ceil(sampleRate / MIN_FEEDBACK_HZ) + 16);
        feedbackRing = new double[feedbackSize * clamp(numChannels, 1, MAX_CH)];
        final double fs = sampleRate;
        stageRate = 1.0 - Math.exp(-1.0 / (0.02 * fs));
        evenRate = 1.0 - Math.exp(-2.0 * Math.PI * 10.0 / fs);
        followUp = 1.0 - Math.exp(-1.0 / (0.010 * fs));
        followDown = 1.0 - Math.exp(-1.0 / (0.150 * fs));
        dynamicsSmoothRate = 1.0 - Math.exp(-1.0 / (0.005 * fs));
        noiseHoldSamples = (int) (0.050 * fs);
        loopGlide = Math.pow(2.0, 1.0 / (LOOP_OCTAVE_SECONDS * fs));
        programFall = Math.exp(-1.0 / (LOOP_CLOSE_SECONDS * fs));
        noiseFall = 1.0 - Math.exp(-1.0 / (0.150 * fs));
    }

    /** Clears the memory of the stages. */
    private void resetStages()
    {
        Arrays.fill(gainRing, 1.0);
        Arrays.fill(feedbackRing, 0.0);
        gainPos = 0;
        feedbackPos = 0;
        Arrays.fill(evenMean, 0.0);
        Arrays.fill(focusState, 0.0);
        followPower = 0.0;
        dynamicsGainDb = 0.0;
        noisePeak = 0.0;
        noiseHoldLeft = 0;
        asymmetryNow = asymmetry;
        dynamicsNow = levelTracking;
        feedbackNow = combLoop;
        gainTail = 0;
        stagesOn = false;
        feedingBefore = false;
        loopDelayNow = 0.0;
        programPeak = 0.0;
        loopOpen = 1.0;
    }

    /**
     * Takes the settings of the stages for a block and says how long a piece
     * of it may be (the delay of the feedback line while the feedback runs).
     */
    private int syncStages()
    {
        asymmetryTarget = asymmetry;
        dynamicsTarget = levelTracking;
        feedbackTarget = combLoop;
        final double noiseDb = programNoiseDb;
        noiseGain = (noiseDb > NOISE_OFF_DB) ? decibelsToGain(noiseDb) : 0.0;
        final double focusHz = trackingCornerHz;
        focusRate = (focusHz > 0.0) ? 1.0 - Math.exp(-2.0 * Math.PI * focusHz / sampleRate) : 0.0;

        stageLatency = getLatency();
        final boolean feeding = feedbackTarget != 0.0 || feedbackNow != 0.0;
        if (feeding && !feedingBefore) Arrays.fill(feedbackRing, 0.0);
        feedbackDelay = feedbackDelayFor(stageLatency);
        // A loop that starts is at its delay at once; one that runs glides to a new one.
        if (!feedingBefore || !(loopDelayNow > 0.0)) loopDelayNow = feedbackDelay;
        feedingBefore = feeding;
        closingOn = combLoopClosing;
        if (!closingOn) loopOpen = 1.0;
        feedbackScale = 1.0 / decibelsToGain(driveSmoother.getTargetValue());
        final boolean moving = dynamicsTarget != 0.0 || dynamicsNow != 0.0 || gainTail > 0;
        stagesOn = feeding || moving || noiseGain > 0.0
                || asymmetryTarget != 0.0 || asymmetryNow != 0.0;
        if (!feeding) return Integer.MAX_VALUE;
        // At rest a piece is as long as the delay; while the delay glides the
        // read between samples needs its neighbours.
        if (loopDelayNow == (double) feedbackDelay) return feedbackDelay;
        return Math.max(1, (int) min(loopDelayNow, (double) feedbackDelay) - 2);
    }

    /** A value on its way to a target; it lands on a target of zero. */
    private static double approach(double now, double target, double rate)
    {
        now += rate * (target - now);
        if (target == 0.0 && Math.abs(now) < 1e-5) now = 0.0;
        return now;
    }

    /** White noise in [-1, 1) for a channel. */
    private double stageNoise(int ch)
    {
        final int s = noiseSeed[ch] * 1664525 + 1013904223;
        noiseSeed[ch] = s;
        return (double) s * (1.0 / 2147483648.0);
    }

    /**
     * Before the curve, at the base rate: the feedback comes back, the
     * dynamics set the level, the second-order term and the noise of the
     * programme are added. The channel that stays unprocessed in the MidOnly
     * and SideOnly modes is left alone.
     */
    private void stagesBefore(double[][] buffer, int off, int nS, int keepChannel)
    {
        final int nCh = Math.min(Math.min(buffer.length, MAX_CH), numChannels);
        final int gainMask = gainSize - 1, feedbackMask = feedbackSize - 1;
        for (int i = 0; i < nS; ++i)
        {
            asymmetryNow = approach(asymmetryNow, asymmetryTarget, stageRate);
            dynamicsNow = approach(dynamicsNow, dynamicsTarget, stageRate);
            feedbackNow = approach(feedbackNow, feedbackTarget, stageRate);

            if (feedbackNow != 0.0)
            {
                // The loop that closes without programme: the level of what
                // comes in, falling over LOOP_CLOSE_SECONDS, opens it.
                double amount = feedbackNow;
                if (closingOn)
                {
                    double top = 0.0;
                    for (int ch = 0; ch < nCh; ++ch)
                        if (ch != keepChannel)
                        {
                            final double level = Math.abs(buffer[ch][off + i]);
                            if (Double.isFinite(level)) top = max(top, level);
                        }
                    programPeak = max(top, programPeak * programFall);
                    loopOpen = approach(loopOpen, programPeak > LOOP_CLOSE_LEVEL ? 1.0 : 0.0, stageRate);
                    amount = feedbackNow * loopOpen;
                }
                final double goal = feedbackDelay;
                if (loopDelayNow == goal)
                {
                    final int at = (feedbackPos + i - feedbackDelay) & feedbackMask;
                    for (int ch = 0; ch < nCh; ++ch)
                    {
                        if (ch == keepChannel) continue;
                        final double back = feedbackRing[ch * feedbackSize + at];
                        buffer[ch][off + i] -= amount * back;
                    }
                }
                else
                {
                    // The delay glides to its new length by a constant ratio
                    // per sample and is read between samples (four points).
                    loopDelayNow = loopDelayNow < goal ? min(goal, loopDelayNow * loopGlide)
                                                       : max(goal, loopDelayNow / loopGlide);
                    final int whole = (int) loopDelayNow;
                    final double part = loopDelayNow - (double) whole;
                    final int at = feedbackPos + i - whole;
                    for (int ch = 0; ch < nCh; ++ch)
                    {
                        if (ch == keepChannel) continue;
                        final int line = ch * feedbackSize;
                        final double back = Interpolation.interpolateHermite(feedbackRing[line + ((at + 1) & feedbackMask)],
                                                                             feedbackRing[line + (at & feedbackMask)],
                                                                             feedbackRing[line + ((at - 1) & feedbackMask)],
                                                                             feedbackRing[line + ((at - 2) & feedbackMask)], part);
                        buffer[ch][off + i] -= amount * back;
                    }
                }
            }

            // The dynamics: one gain for every channel, kept for its inverse.
            double gain = 1.0;
            if (dynamicsNow != 0.0)
            {
                double top = 0.0;
                for (int ch = 0; ch < nCh; ++ch)
                {
                    if (ch == keepChannel) continue;
                    double heard = buffer[ch][off + i];
                    if (!Double.isFinite(heard)) heard = 0.0;
                    if (focusRate > 0.0)
                    {
                        focusState[ch] += focusRate * (heard - focusState[ch]);
                        heard = focusState[ch];
                    }
                    top = max(top, Math.abs(heard));
                }
                final double power = top * top;
                followPower += (power > followPower ? followUp : followDown) * (power - followPower);
                final double levelDb = 10.0 * Math.log10(followPower + 1e-12);
                final double gainDb = clamp(-dynamicsNow * (levelDb - DYNAMICS_REFERENCE_DB), -DYNAMICS_REACH_DB, DYNAMICS_REACH_DB);
                // The follower ripples at twice the frequency of a tone and its
                // inverse arrives a latency later, so the gain is smoothed.
                dynamicsGainDb += dynamicsSmoothRate * (gainDb - dynamicsGainDb);
                gain = decibelsToGain(dynamicsGainDb);
                gainTail = gainSize;
            }
            else
            {
                dynamicsGainDb = 0.0;
                if (gainTail > 0) --gainTail;   // the inverse of the last gains is still on its way out
            }
            gainRing[(gainPos + i) & gainMask] = gain;

            for (int ch = 0; ch < nCh; ++ch)
            {
                if (ch == keepChannel) continue;
                final double[] data = buffer[ch];
                data[off + i] *= gain;
                if (asymmetryNow != 0.0)
                {
                    final double x = data[off + i];
                    final double square = x * x;
                    if (Double.isFinite(square)) evenMean[ch] += evenRate * (square - evenMean[ch]);
                    data[off + i] = x + asymmetryNow * EVEN_SHARE * (square - evenMean[ch]);
                }
            }

            if (noiseGain > 0.0)
            {
                double top = 0.0;
                for (int ch = 0; ch < nCh; ++ch)
                    if (ch != keepChannel)
                    {
                        final double level = Math.abs(buffer[ch][off + i]);
                        if (Double.isFinite(level)) top = max(top, level);
                    }
                if (top >= noisePeak) { noisePeak = top; noiseHoldLeft = noiseHoldSamples; }
                else if (noiseHoldLeft > 0) --noiseHoldLeft;
                else noisePeak += noiseFall * (top - noisePeak);
                if (noisePeak < 1e-9) noisePeak = 0.0;
                final double level = noisePeak * noiseGain;
                for (int ch = 0; ch < nCh; ++ch)
                    if (ch != keepChannel) buffer[ch][off + i] += level * stageNoise(ch);
            }
        }
    }

    /**
     * After the curve: the inverse of the gain of the dynamics, as late as
     * the wet path is, and the output written to the feedback line at the
     * level it came in at.
     */
    private void stagesAfter(double[][] buffer, int off, int nS, int keepChannel)
    {
        final int nCh = Math.min(Math.min(buffer.length, MAX_CH), numChannels);
        final int gainMask = gainSize - 1, feedbackMask = feedbackSize - 1;
        final boolean feeding = feedbackTarget != 0.0 || feedbackNow != 0.0;
        for (int i = 0; i < nS; ++i)
        {
            final double gain = gainRing[(gainPos + i - stageLatency) & gainMask];
            final double back = (gain != 1.0) ? 1.0 / gain : 1.0;
            final int at = (feedbackPos + i) & feedbackMask;
            for (int ch = 0; ch < nCh; ++ch)
            {
                if (ch == keepChannel) continue;
                final double[] data = buffer[ch];
                data[off + i] *= back;
                if (feeding)
                {
                    final double fed = data[off + i] * feedbackScale;
                    feedbackRing[ch * feedbackSize + at] = Double.isFinite(fed) ? clamp(fed, -2.0, 2.0) : 0.0;
                }
            }
        }
        gainPos = (gainPos + nS) & gainMask;
        feedbackPos = (feedbackPos + nS) & feedbackMask;
    }

    /** Measures the curve again when it has moved and the last measurement is old enough. */
    private void maybeCalibrate(int samples)
    {
        final boolean asked = calibrationRequested;
        calibrationRequested = false;
        calibrationWait -= samples;
        if (!asked && (!calibrationDirty || calibrationWait > 0)) return;
        calibrate();
        calibrationWait = (int) (CALIBRATION_EVERY * sampleRate);
    }

    /** Builds the twin that measures the gain of the curve, and its noise (allocates). */
    private void prepareCalibrator()
    {
        if (calibrator == null) calibrator = new Saturation();
        calibrator.oversamplingAutomatic = false;
        calibrator.oversamplingMode = oversamplingMode;
        calibrator.oversamplingBand = oversamplingBand;
        calibrator.applyOversampling(oversamplingFactor);
        calibrator.prepare(sampleRate, 1, CALIBRATION_BLOCK);

        // Pink noise at -18 dBFS RMS over the stretch that is measured, the
        // same every time.
        calibrationNoise = new double[CALIBRATION_SAMPLES];
        calibrationWork = new double[CALIBRATION_SAMPLES];
        calibrationView[0] = calibrationWork;
        int seed = 0x2545F491;
        double b0 = 0.0, b1 = 0.0, b2 = 0.0, sum = 0.0;
        for (int i = 0; i < CALIBRATION_SAMPLES; ++i)
        {
            seed = seed * 1664525 + 1013904223;
            final double white = (double) seed * (1.0 / 2147483648.0);
            b0 = 0.99765 * b0 + white * 0.0990460;
            b1 = 0.96300 * b1 + white * 0.2965164;
            b2 = 0.57000 * b2 + white * 1.0526913;
            calibrationNoise[i] = b0 + b1 + b2 + white * 0.1848;
        }
        for (int i = CALIBRATION_SKIP; i < CALIBRATION_SAMPLES; ++i)
        {
            final double pink = calibrationNoise[i];
            sum += pink * pink;
        }
        final double scale = Math.pow(10.0, CALIBRATION_LEVEL_DB / 20.0)
                           / Math.sqrt(sum / (double) (CALIBRATION_SAMPLES - CALIBRATION_SKIP));
        for (int i = 0; i < CALIBRATION_SAMPLES; ++i) calibrationNoise[i] = calibrationNoise[i] * scale;
    }

    /** The energy of the measuring noise, scaled, through the twin with a snapshot (after its first 1024 samples). */
    private double calibrationEnergy(Params run, double scale)
    {
        final Saturation twin = calibrator;
        twin.applyParamSnapshot(run);
        twin.reset();
        for (int i = 0; i < CALIBRATION_SAMPLES; ++i)
            calibrationWork[i] = calibrationNoise[i] * scale;
        for (int off = 0; off < CALIBRATION_SAMPLES; off += CALIBRATION_BLOCK)
            twin.processChunk(calibrationView, off, Math.min(CALIBRATION_BLOCK, CALIBRATION_SAMPLES - off), null, 0, false);
        double sum = 0.0;
        for (int i = CALIBRATION_SKIP; i < CALIBRATION_SAMPLES; ++i)
        {
            final double y = calibrationWork[i];
            sum += y * y;
        }
        return sum;
    }

    private final Params calibrationRun = new Params();
    private final Params calibrationLinear = new Params();

    /**
     * Measures the gain of the curve on programme with the settings as they
     * stand and sets the compensation: the energy of the noise through the
     * twin over the energy of the same noise, 40 dB down, through the twin at
     * no drive (everything but the curve is linear and cancels).
     */
    private void calibrate()
    {
        calibrationDirty = false;
        double compensation = 0.0;
        if (driveCompensation && calibrator != null && calibrationNoise.length != 0)
        {
            final Saturation twin = calibrator;
            final Params p = calibrationRun.copyFrom(applied);
            p.mix = 1.0;
            p.outputGain = 0.0;
            p.analogDrift = 0.0;
            p.processingMode = ProcessingMode.STEREO;
            p.outputMode = OutputMode.WET_ONLY;
            twin.setAntialiasing(ANTIALIASING[antialiasOrder]);
            twin.curve().setShape(getCurveHardness(), getCurveCurvature(), getCurveAsymmetry());

            final Params linear = calibrationLinear.copyFrom(p);
            linear.driveDb = 0.0;
            final double reference = calibrationEnergy(linear, CALIBRATION_QUIET) / (CALIBRATION_QUIET * CALIBRATION_QUIET);
            final double driven = calibrationEnergy(p, 1.0);
            if (reference > 0.0 && driven > 0.0 && Double.isFinite(driven) && Double.isFinite(reference))
                compensation = clamp(-COMPENSATION_SHARE * 10.0 * Math.log10(driven / reference), -48.0, 24.0);
        }
        compensationDb = compensation;
        outputGainSmoother.setTargetValue(userOutputGainDb + (float) compensation);
    }
}
