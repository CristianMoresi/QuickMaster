package com.dspark.effects;

import com.dspark.core.BiquadCoeffs;
import com.dspark.core.BiquadDesign;
import com.dspark.core.BiquadFilter;
import com.dspark.core.CabinetModel;
import com.dspark.core.FIRFilter;
import com.dspark.core.Oversampling;
import com.dspark.core.OversamplingDryPath;
import com.dspark.core.SmoothedParameter;
import com.dspark.core.wdf.ToneStackFMV;
import com.dspark.effects.TubePreampDetail.CoreDesign;
import com.dspark.effects.TubePreampDetail.CurrentTable;
import com.dspark.effects.TubePreampDetail.GridClamp;
import com.dspark.effects.TubePreampDetail.GridConduction;
import com.dspark.effects.TubePreampDetail.GridTable;
import com.dspark.effects.TubePreampDetail.ToneModes;

import java.util.Arrays;

import static com.dspark.effects.TubePreampDetail.clamp;
import static com.dspark.effects.TubePreampDetail.max;
import static com.dspark.effects.TubePreampDetail.min;
import static com.dspark.effects.TubePreampDetail.phi;

/**
 * Tube preamp: Koren 12AX7 stages, Bassman FMV tone circuit, supply sag.
 * <p>
 * Circuit-level preamp modelling, not a waveshaper:
 * <ul>
 * <li><b>Triode stages</b> (1 or 2) use a tabulated implicit plate-current
 *   solution with bicubic interpolation; an analytic Newton solve handles
 *   voltages outside the table. The tube follows Koren's SPICE model (Koren
 *   1996; published 12AX7 parameters MU=100, EX=1.4, KG1=1060, KP=600,
 *   KVB=300) in a classic common-cathode stage: 300 V supply, 100 k ohm
 *   plate load, 1.5 k ohm cathode resistor with its 22 uF bypass capacitor.
 *   Grid conduction is approximated by a soft clamp toward +0.7 V by
 *   default; {@link GridModel#CONDUCTION} instead feeds each grid through a
 *   22 nF coupling capacitor with a 1 M ohm grid leak and a 68 k ohm
 *   source, against a grid current referenced to the cathode: grid current
 *   charges the coupling capacitor and biases the stage colder (blocking
 *   distortion).</li>
 * <li><b>Supply sag</b>: the effective B+ droops with smoothed plate current
 *   (one-pole, about 70 ms) times a sag resistance; drive into the stage and
 *   the headroom breathes back.</li>
 * <li><b>Tone stack</b>: the full Fender '59 Bassman FMV treble/bass/middle
 *   network ({@link ToneStackFMV}). It sits between the stages with a fixed
 *   38 k ohm source-impedance approximation; its load is not fed back into
 *   the triode solve. Controls interact non-orthogonally, as the circuit
 *   does.</li>
 * <li><b>Output level</b>: the circuit's program response at the reference
 *   tone setting is measured once in {@link #prepare} (settled channel,
 *   pink-weighted multitone). A 3-section EQ designed from that measurement
 *   flattens the FMV stack's fixed mid-scoop envelope, so neutral knobs
 *   sound neutral and the tone controls act relative to flat. Loudness
 *   divides out the program gain measured at each drive (a prepare-time
 *   sweep) plus a +0.25 dB/dB residual slope: backing off is audible,
 *   pushing raises density at a gently rising level. Use
 *   {@link #setOutput} for a static trim; gain and sag changes are ramped
 *   (30 ms in logarithmic gain, at the internal sample clock). Blocks longer
 *   than the prepared maximum are processed in pieces.</li>
 * </ul>
 * The nonlinear core runs at 2x oversampling by default; the factor is
 * configurable with {@link #setOversampling} (1 = off: no internal
 * resampling, zero added latency, the original point-sampled circuit, which
 * aliases unless the surrounding chain is oversampled; 4/8/16 = lower
 * residual error at higher cost). At factors of 2 and more the circuit is
 * solved in continuous time inside each internal sample interval and the
 * analog plate voltage is band-limited once, at the output:
 * <ul>
 * <li>The stage-1 grid input between internal samples is a least-squares
 *   Farrow polynomial fitted to the oversampled band.</li>
 * <li>Supply and cathode states are held at their predicted interval
 *   midpoint and advanced exactly with the interval-average current.</li>
 * <li>Each interval is split where the grid crosses the triode's cutoff,
 *   conduction and grid-clamp knees; saturated parts are integrated in
 *   closed form and the rest with Gauss-Legendre nodes.</li>
 * <li>The tone circuit is propagated exactly in its analog modal form,
 *   including the stiff mode near bass 0 / middle 1, so stage 2 sees the
 *   continuous interstage trajectory. Stage 2 is split at its own knees.</li>
 * <li>The output projection is an order-6 B-spline kernel applied through
 *   per-interval moments, followed by a fixed linear-phase FIR that
 *   compensates its passband droop.</li>
 * <li>From 4x every decimation stage is at least 100 dB deep
 *   ({@link Oversampling.Depth#DEEP}); at 2x the High preset is kept.</li>
 * </ul>
 * Total latency for 1/2/4/8/16x is 0/71/93/94/96 base samples, independent
 * of {@link #setStages}; the dry path includes the same delay.
 * <p>
 * Stage-count changes keep the current path while a prepared second path
 * warms for 5 ms, followed by a 20 ms smooth crossfade. The incoming circuit
 * keeps the signal-dependent cathode, supply and tone-stack state relative
 * to its new DC operating point. Outside a transition only the selected
 * circuit is evaluated. The initial selection and {@link #reset} select the
 * requested topology without a fade.
 * <p>
 * Parameter setters take effect at the next {@link #process} call (non-finite
 * values are ignored). {@link #setOversampling}, {@link #setAutomaticOversampling},
 * {@link #setOversamplingMode} and {@link #setGridModel} re-run
 * {@link #prepare} on a prepared instance. The dry/wet mix ramps at a rate of
 * at most full scale per 20 ms. Channels beyond the prepared count pass
 * through untouched. An unprepared instance passes audio through.
 * <p>
 * An optional cabinet stage ({@link #setCabinet}, off by default) runs the
 * wet signal through a {@link CabinetModel} with no latency; it fades in and
 * out over 20 ms.
 * <p>
 * The plate-current table is 156672 bytes shared by all channels of an
 * instance. {@link #prepare} builds the table, designs the modal tone circuit
 * and calibrates the circuit; processing, tone changes and stage changes
 * allocate nothing (a cabinet design, done when a cabinet setting changed,
 * allocates the analog prototypes).
 */
public final class TubePreamp
{
    /** How the oversampler filters (see {@link #setOversamplingMode}). */
    public enum OversamplingMode
    {
        /** Linear-phase half-bands: the latency of the factor, the dry path a delay (default). */
        LINEAR_PHASE,
        /** Elliptic allpass half-bands: a few samples of latency, the dry path through the same filters. */
        MINIMUM_PHASE
    }

    /**
     * How the stages model grid conduction (see {@link #setGridModel}).
     * <ul>
     * <li>{@link #CLAMP} (default): a soft clamp of the grid voltage toward
     *   +0.7 V, referenced to ground. It starts at 0 V, while the cathode sits
     *   near +1.2 V, so it compresses from about -1.2 V grid-to-cathode and
     *   the grid never reaches the cathode.</li>
     * <li>{@link #CONDUCTION}: grid current of the grid-to-cathode voltage
     *   (12AX7 fit of Dempwolf and Zoelzer, DAFx-11), added to the cathode
     *   current: 0.25 mA at +0.5 V, 0.62 mA at +1 V. Each grid is fed from
     *   its source through a 22 nF coupling capacitor, a 1 M ohm grid leak to
     *   ground and a 68 k ohm series resistance. Grid current charges the
     *   coupling capacitor, so a loud passage leaves the stage biased colder
     *   until the capacitor discharges through the grid leak (22 ms):
     *   blocking distortion. The coupling network is also a 7.2 Hz high-pass
     *   on each grid.</li>
     * </ul>
     */
    public enum GridModel
    {
        /** Soft clamp of the grid toward +0.7 V (default). */
        CLAMP,
        /** Cathode-referenced grid current behind a coupling network. */
        CONDUCTION
    }

    // -- Circuit constants (classic 12AX7 common-cathode stage) -------------------
    private static final double BPLUS = 300.0;
    private static final double RL = CurrentTable.RL;
    private static final double RK = CurrentTable.RK;
    private static final double CK = CurrentTable.CK;
    /** Divider into stage 2. */
    private static final double INTERSTAGE = 0.12;
    /** Rate the automatic factor reaches. */
    private static final double AUTOMATIC_INTERNAL_RATE = 88200.0;

    /** -12..+36 dB in 4 dB steps. */
    private static final int DRIVE_LUT_N = 13;
    private static final double DRIVE_LUT_MIN_DB = -12.0;
    private static final double DRIVE_LUT_STEP_DB = 4.0;

    private static final double[] REF_TONES = { 100.0, 200.0, 400.0, 800.0, 1600.0, 3200.0, 6400.0 };

    // -- Members --------------------------------------------------------------------
    private boolean hasSpec = false;
    private double specRate = 48000.0;
    private int specChannels = 0;
    private int specBlock = 0;
    private double sampleRate = 48000.0;
    private double fs2 = 96000.0;
    private int numChannels = 0;
    private int maxBlock = 0;
    private boolean prepared = false;
    private int latency = 0;
    private int drySize = 1;
    private int osFactor = 2;
    private boolean osAutomatic = false;
    private OversamplingMode osMode = OversamplingMode.LINEAR_PHASE;
    private final OversamplingDryPath dryPath = new OversamplingDryPath();
    private double[][] dryScratch = new double[0][];
    private boolean dryThroughFilters = false;
    private int dryDelay = 0;

    private CurrentTable loadTable = null;
    private GridTable gridTable = null;
    private GridModel gridModel = GridModel.CLAMP;
    private CoreDesign design = null;
    private Oversampling oversampler = null;
    private final ChannelState[][] channels = { new ChannelState[0], new ChannelState[0] };

    private double[][] dryRing = new double[0][];
    private int dryPos = 0;

    private double hScale = 1.0;
    private final double[] mScale = { 1.0, 1.0 };
    private double sagR = 0.0;
    private int numStagesActive = 1;
    private final double[][] gProgLut = new double[2][DRIVE_LUT_N];
    private final SmoothedParameter driveLogSmoother = new SmoothedParameter();
    private final SmoothedParameter[] outputLogSmoothers = { new SmoothedParameter(), new SmoothedParameter() };
    private final double[][] gainRamps = { new double[0], new double[0], new double[0] };
    private final double[][] compensationScratch = { new double[0], new double[0] };
    private final SmoothedParameter stageBlend = new SmoothedParameter();
    private double[] stageRamp = new double[0];
    private final SmoothedParameter sagSmoother = new SmoothedParameter();
    private double[] sagRamp = new double[0];
    private int stageWarmupRemaining = 0;
    private boolean bothStagesActive = false;
    private boolean gainsInitialized = false;
    private final double[] outGain = new double[2];
    private final boolean[] outputRamping = new boolean[2];

    private final CabinetModel cabinet = new CabinetModel();
    private boolean cabinetOn = false;
    private double[][] cabScratch = new double[0][];
    private double cabNow = 0.0;
    private double cabStep = 1.0 / 960.0;

    private double currentMix = 1.0;
    private double mixMaxStep = 1.0 / 960.0;

    private double driveDb = 0.0;
    private double treble = 0.5;
    private double bass = 0.5;
    private double middle = 0.5;
    private double sag = 0.3;
    private int stages = 1;
    private double outputDb = 0.0;
    private double mix = 1.0;
    private double supplyNow = 300.0;
    private boolean dirty = true;

    /** A preamp with the default settings; pass-through until {@link #prepare}. */
    public TubePreamp()
    {
        for (double[] lut : gProgLut) Arrays.fill(lut, 1.0);
    }

    // -- Lifecycle ---------------------------------------------------------------

    /**
     * Allocates both stage configurations per channel and runs the reference
     * calibration. Invalid values (non-positive or non-finite rate, block size
     * or channel count) are ignored: the previous state is kept and an
     * unprepared instance stays pass-through. Parameters set before this
     * call apply from the first processed sample.
     *
     * @param sampleRate    sample rate in Hz
     * @param numChannels   number of channels
     * @param maxBlockSize  largest block passed to {@link #process}
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize)
    {
        if (!(sampleRate > 0.0 && sampleRate <= Double.MAX_VALUE) || maxBlockSize <= 0 || numChannels <= 0)
            return;
        prepared = false;
        hasSpec = true;
        specRate = sampleRate;
        specChannels = numChannels;
        specBlock = maxBlockSize;
        this.sampleRate = sampleRate;
        mixMaxStep = 1.0 / max(1.0, this.sampleRate * 0.02);
        if (osAutomatic)
            osFactor = Oversampling.factorForRate(sampleRate, AUTOMATIC_INTERNAL_RATE);
        // Internal processing rate = active oversampling factor x base rate.
        fs2 = (double) osFactor * this.sampleRate;
        this.numChannels = numChannels;
        maxBlock = Math.max(maxBlockSize, 1);
        driveLogSmoother.prepare(fs2, 30.0);
        for (SmoothedParameter smoother : outputLogSmoothers)
            smoother.prepare(fs2, 30.0);
        stageBlend.setSmoothingType(SmoothedParameter.SmoothingType.LINEAR);
        stageBlend.prepare(fs2, 20.0);
        final int highLength = maxBlock * osFactor;
        for (int k = 0; k < gainRamps.length; ++k)
            gainRamps[k] = new double[highLength];
        for (int k = 0; k < compensationScratch.length; ++k)
            compensationScratch[k] = new double[highLength];
        stageRamp = new double[highLength];
        sagRamp = new double[highLength];
        sagSmoother.prepare(fs2, 30.0);

        if (osFactor > 1)
        {
            // From 4x the core leaves harmonics up to the internal Nyquist at
            // close to their full level, so the preset's 80 dB is no longer
            // enough to decimate them: every stage is made 100 dB deep.
            oversampler = new Oversampling(osFactor, Oversampling.Quality.HIGH,
                osMode == OversamplingMode.MINIMUM_PHASE ? Oversampling.Mode.POLYPHASE_IIR
                                                         : Oversampling.Mode.LINEAR_PHASE_FIR,
                osFactor >= 4 ? Oversampling.Depth.DEEP : Oversampling.Depth.PRESET);
            oversampler.prepare(sampleRate, numChannels, maxBlockSize);
            design = new CoreDesign(osFactor);
        }
        else
        {
            oversampler = null;
            design = null;
        }

        // The continuous core freezes the cathode per interval (B = 0); the
        // 1x point circuit keeps the trapezoidal cathode coupling.
        loadTable = new CurrentTable(design != null ? Double.POSITIVE_INFINITY : fs2);
        if (gridModel == GridModel.CONDUCTION)
        {
            // The 1x point circuit solves the coupling capacitor implicitly:
            // its trapezoid adds a series resistance to the grid source.
            final double rs = design != null ? GridConduction.RS : GridConduction.RS + couplingSeries(fs2);
            if (gridTable == null || gridTable.rs != rs)
                gridTable = new GridTable(rs);
        }
        else
            gridTable = null;
        for (int lane = 0; lane < 2; ++lane)
        {
            channels[lane] = new ChannelState[numChannels];
            for (int ch = 0; ch < numChannels; ++ch)
                channels[lane][ch] = new ChannelState(fs2, loadTable, design, gridTable);
        }

        latency = (oversampler != null ? oversampler.getLatency() : 0) + (design != null ? design.latency : 0);
        // Behind minimum-phase filters the dry copy crosses the same filters
        // (in phase at every frequency) and the ring adds only the core's delay.
        dryThroughFilters = oversampler != null && osMode == OversamplingMode.MINIMUM_PHASE;
        if (dryThroughFilters) dryPath.prepare(sampleRate, numChannels, maxBlockSize, oversampler);
        dryScratch = new double[numChannels][maxBlock];
        dryDelay = latency - (dryThroughFilters ? oversampler.getLatency() : 0);
        drySize = 1;
        while (drySize < latency + maxBlock + 1) drySize <<= 1;
        dryRing = new double[numChannels][drySize];
        dryPos = 0;
        cabinet.prepare(this.sampleRate, numChannels);
        cabScratch = new double[numChannels][maxBlock];
        cabStep = 1.0 / max(1.0, this.sampleRate * 0.02);

        calibrateReference();

        prepared = true;
        dirty = true;
        reset();
    }

    /** Re-settles every stage at its DC operating point. */
    public void reset()
    {
        if (!prepared) return;
        final double sagRNow = sag * 40e3;
        final int requested = stages;
        for (int st = 1; st <= 2; ++st)
            for (ChannelState ch : channels[st - 1])
                ch.reset(sagRNow, st);
        numStagesActive = requested;
        stageBlend.reset((double) (requested - 1));
        stageWarmupRemaining = 0;
        bothStagesActive = false;
        for (double[] d : dryRing)
            Arrays.fill(d, 0.0);
        dryPos = 0;
        dryPath.reset();
        if (oversampler != null) oversampler.reset();
        // Seed the anti-zipper ramps at their targets: no fade-in on start.
        gainsInitialized = false;
        currentMix = mix;
        cabinet.reset();
        cabNow = cabinetOn ? 1.0 : 0.0;
    }

    /**
     * Runs the wet signal through a loudspeaker cabinet (off by default). The
     * cabinet comes after the circuit and before the dry/wet mix, adds no
     * latency and fades in and out over 20 ms. Its settings are those of
     * {@link #getCabinet}.
     *
     * @param on  true for the cabinet stage
     */
    public void setCabinet(boolean on) { cabinetOn = on; }

    /** @return whether the cabinet stage is on */
    public boolean isCabinet() { return cabinetOn; }

    /** @return the cabinet model, whose settings apply at the next block */
    public CabinetModel getCabinet() { return cabinet; }

    // -- Parameters -------------------------------------------------------------------

    /**
     * Input drive in dB, [-12, +36]; level-compensated. Non-finite values are ignored.
     *
     * @param db  the drive
     */
    public void setDrive(double db)
    {
        if (!Double.isFinite(db)) return;
        driveDb = clamp(db, -12.0, 36.0);
        dirty = true;
    }

    /**
     * Treble control of the FMV stack, [0, 1]. Non-finite values are ignored.
     *
     * @param treble  the treble pot position
     */
    public void setTreble(double treble)
    {
        if (!Double.isFinite(treble)) return;
        this.treble = clamp(treble, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Bass control of the FMV stack, [0, 1] (log taper, like the original).
     * Non-finite values are ignored.
     *
     * @param bass  the bass pot position
     */
    public void setBass(double bass)
    {
        if (!Double.isFinite(bass)) return;
        this.bass = clamp(bass, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Middle control of the FMV stack, [0, 1]. Non-finite values are ignored.
     *
     * @param middle  the middle pot position
     */
    public void setMiddle(double middle)
    {
        if (!Double.isFinite(middle)) return;
        this.middle = clamp(middle, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Supply sag depth, [0, 1] (0 = stiff supply). Non-finite values are ignored.
     *
     * @param sag  the sag depth
     */
    public void setSag(double sag)
    {
        if (!Double.isFinite(sag)) return;
        this.sag = clamp(sag, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Number of triode stages (1 = clean/edge, 2 = high gain), clamped to
     * [1, 2]. The prepared latency does not change. A running stream keeps
     * its circuit while the new path warms for 5 ms, then crossfades over
     * 20 ms. Reversing a running fade preserves both circuit states.
     *
     * @param stages  the stage count
     */
    public void setStages(int stages)
    {
        this.stages = Math.max(1, Math.min(stages, 2));
        dirty = true;
    }

    /**
     * Configures internal oversampling of the nonlinear circuit. On a
     * prepared instance it reallocates the filters and re-runs the reference
     * calibration exactly like {@link #prepare}.
     *
     * @param factor  power of two in {1, 2, 4, 8, 16}; 1 = off (no internal
     *                resampling, zero added latency; the nonlinearity then
     *                aliases in-band unless the surrounding chain is
     *                oversampled). 2 is the default. Other values are ignored.
     *                It turns automatic oversampling off.
     */
    public void setOversampling(int factor)
    {
        if (factor < 1 || factor > 16 || (factor & (factor - 1)) != 0) return;
        if (factor == osFactor && !osAutomatic) return;
        osAutomatic = false;
        osFactor = factor;
        if (prepared && hasSpec)
            prepare(specRate, specChannels, specBlock);
    }

    /**
     * Lets the host rate choose the factor. With it on, every
     * {@link #prepare} takes the smallest factor that brings the circuit to
     * an internal rate of at least 88.2 kHz: 2x at 44.1 and 48 kHz (the
     * default factor) and none from 88.2 kHz up. Off by default; an explicit
     * {@link #setOversampling} turns it off. Turned on for a prepared
     * instance, it prepares it again.
     *
     * @param on  true to choose the factor from the rate
     */
    public void setAutomaticOversampling(boolean on)
    {
        if (on == osAutomatic) return;
        osAutomatic = on;
        if (on && prepared && hasSpec)
            prepare(specRate, specChannels, specBlock);
    }

    /** @return whether the factor follows the host rate */
    public boolean isOversamplingAutomatic() { return osAutomatic; }

    /**
     * Chooses the oversampler's filters (an active instance is prepared again).
     * MINIMUM_PHASE runs polyphase allpass half-bands: at 2x the latency
     * falls from the factor's linear-phase delay to a few samples, and the
     * dry copy of the mix crosses the same filters, so a partial mix adds in
     * phase. Null is ignored.
     *
     * @param mode  LINEAR_PHASE (default) or MINIMUM_PHASE
     */
    public void setOversamplingMode(OversamplingMode mode)
    {
        if (mode == null || mode == osMode) return;
        osMode = mode;
        if (prepared && hasSpec)
            prepare(specRate, specChannels, specBlock);
    }

    /** @return the oversampler's filters */
    public OversamplingMode getOversamplingMode() { return osMode; }

    /** @return the active oversampling factor (1 = off, 2 = default) */
    public int getOversamplingFactor() { return osFactor; }

    /**
     * Selects the grid model. On a prepared instance it rebuilds the circuits
     * and re-runs the reference calibration. Null is ignored.
     * <p>
     * Conduction is a change of character, so it is opt-in. Against Clamp at
     * 2x, one stage, 1 kHz at -6 dBFS: the output differs by -21.9 dB at
     * 0 dB drive and -7.7 dB at +12 dB. From +24 dB the charged coupling
     * capacitor holds the stage near cutoff for most of the cycle. After a
     * 300 ms burst at +24 dB a 0.01 tone comes out 15.6 dB below its settled
     * level 2 ms later, recovered by 40 ms.
     *
     * @param model  the grid model
     */
    public void setGridModel(GridModel model)
    {
        if (model == null || model == gridModel) return;
        gridModel = model;
        if (prepared && hasSpec)
            prepare(specRate, specChannels, specBlock);
    }

    /** @return the selected grid model */
    public GridModel getGridModel() { return gridModel; }

    /**
     * Static output trim in dB, [-24, +12]. Non-finite values are ignored.
     *
     * @param db  the trim
     */
    public void setOutput(double db)
    {
        if (!Double.isFinite(db)) return;
        outputDb = clamp(db, -24.0, 12.0);
    }

    /**
     * Dry/wet mix, [0, 1]; the dry signal is latency-compensated and the mix
     * is ramped over at least 20 ms. Non-finite values are ignored.
     *
     * @param mix  the wet share
     */
    public void setMix(double mix)
    {
        if (!Double.isFinite(mix)) return;
        this.mix = clamp(mix, 0.0, 1.0);
    }

    /** @return the drive in dB */
    public double getDrive() { return driveDb; }

    /** @return the treble control */
    public double getTreble() { return treble; }

    /** @return the bass control */
    public double getBass() { return bass; }

    /** @return the middle control */
    public double getMiddle() { return middle; }

    /** @return the sag depth */
    public double getSag() { return sag; }

    /** @return the requested stage count */
    public int getStages() { return stages; }

    /** @return the output trim in dB */
    public double getOutput() { return outputDb; }

    /** @return the dry/wet mix */
    public double getMix() { return mix; }

    /**
     * Latency in prepared-rate samples added by the oversampler and the
     * continuous core (0 at 1x); reflects the active factor.
     *
     * @return the latency
     */
    public int getLatency() { return latency; }

    /** @return the same as {@link #getLatency} */
    public int getLatencySamples() { return getLatency(); }

    /**
     * Effective B+ supply voltage of channel 0 (sag meter readout), as of the
     * last processed block. During a stage transition it uses the same blend
     * weights as the wet paths.
     *
     * @return the supply voltage
     */
    public double getSupplyVoltage() { return supplyNow; }

    // -- Processing -------------------------------------------------------------------

    /** {@link #process(double[][], int, int)} from sample 0. */
    public void process(double[][] buffer, int numSamples)
    {
        process(buffer, 0, numSamples);
    }

    /**
     * Processes a planar block in place. Pass-through until {@link #prepare}
     * succeeds. Non-finite input samples are replaced by silence first, so a
     * transient glitch cannot poison the recursive circuit state.
     *
     * @param buffer      the channels
     * @param offset      first sample of the block in every channel
     * @param numSamples  samples per channel
     */
    public void process(double[][] buffer, int offset, int numSamples)
    {
        if (!prepared) return;
        final int nCh = Math.min(buffer.length, numChannels);
        final int nS = numSamples;
        if (nCh == 0 || nS <= 0) return;

        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] d = buffer[ch];
            for (int i = offset; i < offset + nS; ++i)
                if (!Double.isFinite(d[i])) d[i] = 0.0;
        }

        if (dirty)
        {
            dirty = false;
            recompute();
        }

        for (int off = 0; off < nS; off += maxBlock)
            processChunk(buffer, offset + off, Math.min(maxBlock, nS - off), nCh);
    }

    private void processChunk(double[][] buffer, int off, int nS, int nCh)
    {
        // Rate-limited mix ramp (exact landing; settled it reduces to the constant).
        final double mixTarget = mix;
        final double mixStart = currentMix;
        final double trim = Math.pow(10.0, outputDb / 20.0);
        outGain[0] = mScale[0] * trim;
        outGain[1] = mScale[1] * trim;

        // Dry snapshot (through the oversampler's filters when they are
        // minimum phase).
        if (dryThroughFilters)
        {
            for (int ch = 0; ch < nCh; ++ch)
                System.arraycopy(buffer[ch], off, dryScratch[ch], 0, nS);
            dryPath.process(dryScratch, 0, nS);
        }
        final int dryMask = drySize - 1;
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] in = dryThroughFilters ? dryScratch[ch] : buffer[ch];
            final int inOff = dryThroughFilters ? 0 : off;
            final double[] dry = dryRing[ch];
            int dp = dryPos;
            for (int i = 0; i < nS; ++i)
            {
                dry[dp] = in[inOff + i];
                dp = (dp + 1) & dryMask;
            }
        }

        // Nonlinear circuit at the active oversampling factor (1x = process
        // the base-rate buffer in place, no resampling).
        {
            final boolean osOn = oversampler != null;
            final double[][] osBuf;
            final int osOff;
            final int osN;
            if (osOn)
            {
                osBuf = oversampler.upsample(buffer, off, nS);
                osOff = 0;
                osN = oversampler.getHighRateLength();
            }
            else
            {
                osBuf = buffer;
                osOff = off;
                osN = nS;
            }

            // Smooth the compensation pair in logarithmic gain at the
            // internal sample clock, so a host's block size does not change
            // the trajectory.
            final double driveLog = Math.log(hScale);
            if (!gainsInitialized)
            {
                driveLogSmoother.reset(driveLog);
                for (int st = 0; st < 2; ++st)
                    outputLogSmoothers[st].reset(Math.log(outGain[st]));
                sagSmoother.reset(sagR);
                gainsInitialized = true;
            }
            driveLogSmoother.setTargetValue(driveLog);
            final boolean driveRamping = driveLogSmoother.isSmoothing();
            final double[] driveRamp = gainRamps[0];
            if (driveRamping)
            {
                driveLogSmoother.processBlock(driveRamp, 0, osN);
                for (int i = 0; i < osN; ++i)
                {
                    final double value = driveRamp[i];
                    driveRamp[i] = value == driveLog ? hScale : Math.exp(value);
                }
            }
            for (int st = 0; st < 2; ++st)
            {
                final double outputLog = Math.log(outGain[st]);
                final SmoothedParameter smoother = outputLogSmoothers[st];
                smoother.setTargetValue(outputLog);
                outputRamping[st] = smoother.isSmoothing();
                if (!outputRamping[st]) continue;
                final double[] ramp = gainRamps[st + 1];
                smoother.processBlock(ramp, 0, osN);
                for (int i = 0; i < osN; ++i)
                {
                    final double value = ramp[i];
                    ramp[i] = value == outputLog ? outGain[st] : Math.exp(value);
                }
            }

            // Supply sag resistance, ramped like the gains: a step would move
            // the supply (and so the plate DC) at once and thump.
            sagSmoother.setTargetValue(sagR);
            final boolean sagRamping = sagSmoother.isSmoothing();
            if (sagRamping)
                sagSmoother.processBlock(sagRamp, 0, osN);

            // Advance the transition once per internal frame, shared by all
            // channels. The inactive circuit stops at the exact ramp endpoint,
            // even if the host's block extends beyond it.
            int transitionSamples = 0;
            while (bothStagesActive && transitionSamples < osN)
            {
                double w = stageBlend.getCurrentValue();
                if (stageWarmupRemaining > 0) --stageWarmupRemaining;
                else w = stageBlend.getNextValue();
                stageRamp[transitionSamples++] = w * w * (3.0 - 2.0 * w);
                if (stageWarmupRemaining == 0 && !stageBlend.isSmoothing())
                    bothStagesActive = false;
            }
            final int target = numStagesActive - 1;
            final int other = 1 - target;
            final double[] targetScratch = compensationScratch[target];
            final double[] otherScratch = compensationScratch[other];
            final int nOs = Math.min(nCh, osBuf.length);
            for (int ch = 0; ch < nOs; ++ch)
            {
                final double[] d = osBuf[ch];
                for (int i = 0; i < osN; ++i)
                {
                    final double h = driveRamping ? driveRamp[i] : hScale;
                    final double x = h * d[osOff + i];
                    for (int st = 0; st < 2; ++st)
                    {
                        if (st != target && i >= transitionSamples) continue;
                        compensationScratch[st][i] = channels[st][ch].processSample(
                            x, st + 1, sagRamping ? sagRamp[i] : sagR, false);
                    }
                }
                if (osOn)
                {
                    for (int st = 0; st < 2; ++st)
                    {
                        final int count = st == target ? osN : transitionSamples;
                        if (count > 0)
                            channels[st][ch].compensateBlock(compensationScratch[st], count);
                    }
                }
                final boolean targetRamping = outputRamping[target], otherRamping = outputRamping[other];
                final double[] targetGains = gainRamps[target + 1], otherGains = gainRamps[other + 1];
                final double targetGain = outGain[target], otherGain = outGain[other];
                for (int i = 0; i < osN; ++i)
                {
                    final double g = targetRamping ? targetGains[i] : targetGain;
                    double value = g * targetScratch[i];
                    if (i < transitionSamples)
                    {
                        final double gOther = otherRamping ? otherGains[i] : otherGain;
                        final double alternate = gOther * otherScratch[i];
                        final double w = target == 1 ? stageRamp[i] : 1.0 - stageRamp[i];
                        value = alternate + w * (value - alternate);
                    }
                    d[osOff + i] = value;
                }
            }
            if (osOn) oversampler.downsample(buffer, off, nS);
            final double position = stageBlend.getCurrentValue();
            final double w = position * position * (3.0 - 2.0 * position);
            final double current = (1.0 - w) * channels[0][0].supplyCurrent()
                                 + w * channels[1][0].supplyCurrent();
            supplyNow = BPLUS - sagSmoother.getCurrentValue() * current;
        }

        runCabinet(buffer, off, nCh, nS);

        // Latency-compensated mix, ramped.
        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] d = buffer[ch];
            final double[] dry = dryRing[ch];
            for (int i = 0; i < nS; ++i)
            {
                final int idx = (dryPos + i - dryDelay) & dryMask;
                final double drySample = dry[idx];
                final double mixVal = moveTowards(mixStart, mixTarget, mixMaxStep * (double) (i + 1));
                d[off + i] = drySample + (d[off + i] - drySample) * mixVal;
            }
        }
        currentMix = moveTowards(mixStart, mixTarget, mixMaxStep * (double) nS);
        dryPos = (dryPos + nS) & dryMask;
    }

    /** The cabinet stage on the wet signal, faded in and out. */
    private void runCabinet(double[][] buffer, int off, int nCh, int nS)
    {
        final double target = cabinetOn ? 1.0 : 0.0;
        if (cabNow == 0.0 && target == 0.0) return;
        if (cabNow == 1.0 && target == 1.0)
        {
            cabinet.process(buffer, off, nS);
            return;
        }
        if (cabNow == 0.0) cabinet.reset();     // its memory is of another time
        final int n = Math.min(nS, maxBlock);
        final int count = Math.min(nCh, cabScratch.length);
        for (int ch = 0; ch < count; ++ch)
            System.arraycopy(buffer[ch], off, cabScratch[ch], 0, n);
        cabinet.process(cabScratch, 0, n);
        double now = cabNow;
        for (int i = 0; i < n; ++i)
        {
            now += clamp(target - now, -cabStep, cabStep);
            for (int ch = 0; ch < count; ++ch)
            {
                final double x = buffer[ch][off + i];
                buffer[ch][off + i] = x + now * (cabScratch[ch][i] - x);
            }
        }
        cabNow = (n < nS) ? target : now;
    }

    /** The rate-limiter step: the target once within reach, else a move of maxDelta toward it. */
    private static double moveTowards(double from, double to, double maxDelta)
    {
        final double delta = to - from;
        return Math.abs(delta) <= maxDelta ? to : from + clamp(delta, -maxDelta, maxDelta);
    }

    /** Series resistance the trapezoidal coupling capacitor adds to the grid
     *  source at an internal rate (its h / (2 Cc) against the grid leak). */
    private static double couplingSeries(double rate)
    {
        final double h = 0.5 / (rate * GridConduction.CC);
        return h / (1.0 + h / GridConduction.RG);
    }

    /** Static operating point: Vk = Ip*Rk (capacitor fully charged). */
    private static double settleCurrent(double bplus, double grid, double[] kd)
    {
        double i = 8e-4;
        for (int it = 0; it < 60; ++it)
        {
            final double vkS = i * RK;
            final double vpk = bplus - i * RL - vkS;
            final double ipK = CurrentTable.koren(vpk, grid - vkS, kd);
            final double dVpk = kd[0], dVgk = kd[1];
            final double f = i - ipK;
            final double fp = 1.0 - (dVpk * (-(RL + RK)) + dVgk * (-RK));
            final double di = f / fp;
            i -= di;
            i = clamp(i, 0.0, bplus / (RL + RK));
            if (Math.abs(di) < 1e-15) break;
        }
        return i;
    }

    /** Point-sampled common-cathode stage (1x path) with trapezoidal cathode bypass. */
    private static final class TriodeStage
    {
        CurrentTable table = null;
        /** Conduction model when set. */
        GridTable grid = null;
        /** Grid current state (conduction model). */
        double ig = 0.0;
        /** Coupling capacitor voltage (conduction model). */
        double vc = 0.0;
        /** Previous coupling capacitor current (A). */
        double icPrev = 0.0;
        double fs2 = 96000.0;
        /** Plate current state / Newton seed. */
        double ip = 8e-4;
        /** Cathode voltage (bypass capacitor state). */
        double vk = 1.2;
        /** Previous net capacitor current (A). */
        double fPrev = 0.0;
        /** Plate voltage at the operating point. */
        double vpDC = 200.0;
        private final double[] kd = new double[2];

        void copyFrom(TriodeStage o)
        {
            table = o.table;
            grid = o.grid;
            ig = o.ig;
            vc = o.vc;
            icPrev = o.icPrev;
            fs2 = o.fs2;
            ip = o.ip;
            vk = o.vk;
            fPrev = o.fPrev;
            vpDC = o.vpDC;
        }

        void settleDC(double bplus)
        {
            ip = settleCurrent(bplus, 0.0, kd);
            vk = ip * RK;
            fPrev = 0.0;
            vpDC = bplus - ip * RL;
            ig = 0.0;
            vc = 0.0;
            icPrev = 0.0;
        }

        /** Processes one grid-volt sample, returns the AC plate voltage. */
        double processSample(double vg, double bplusEff)
        {
            if (grid != null) return processConduction(vg, bplusEff);
            vg = GridClamp.value(vg);
            // Trapezoidal cathode bypass: Ck dVk/dt = Ip - Vk/Rk, with fPrev
            // holding the previous net current (Ip - Vk/Rk), so the update is
            //   Vk_n = Vk_{n-1} + (T/2Ck)(I_n + I_{n-1}) = kA + kB * Ip_n.
            final double h2c = 0.5 / (fs2 * CK);
            final double denom = 1.0 + h2c / RK;
            final double kA = (vk + h2c * fPrev) / denom;
            final double kB = h2c / denom;

            final double iMax = bplusEff / RL + 1e-3;
            double i = clamp(ip, 0.0, iMax);
            // Outside the prepared supply/grid range, keep the analytic
            // circuit solve. The table includes a bounded deep-cutoff limit.
            if (table.covers(bplusEff - kA, vg - kA))
                i = table.eval(bplusEff - kA, vg - kA);
            else for (int it = 0; it < 8; ++it)
            {
                final double vkN = kA + kB * i;
                final double vpk = bplusEff - i * RL - vkN;
                final double vgk = vg - vkN;
                final double ipK = CurrentTable.koren(vpk, vgk, kd);
                final double dVpk = kd[0], dVgk = kd[1];

                final double f = i - ipK;
                final double fp = 1.0 - (dVpk * (-RL - kB) + dVgk * (-kB));
                final double di = f / max(fp, 1e-6);
                i -= di;
                i = clamp(i, 0.0, iMax);
                if (Math.abs(di) < 1e-12) break;
            }

            final double vkN = kA + kB * i;
            fPrev = i - vkN / RK;      // net capacitor current for the trapezoid
            vk = vkN;
            ip = i;
            return (bplusEff - i * RL) - vpDC;     // AC component
        }

        /** Conduction model: the grid node is solved against the cathode
         *  predicted from the previous currents (the cathode moves by
         *  kB * di, well under a millivolt per sample), and the grid current
         *  joins the plate current in the cathode. */
        double processConduction(double vs, double bplusEff)
        {
            final double h2c = 0.5 / (fs2 * CK);
            final double denom = 1.0 + h2c / RK;
            final double kA = (vk + h2c * fPrev) / denom;
            final double kB = h2c / denom;
            final double vkEst = kA + kB * (ip + ig);
            // Trapezoidal coupling capacitor: vc = cA + cB * Ig with the grid
            // leak current (vs - vc) / Rg; cB joins the grid source resistance
            // (the table is built for Rs + cB at this rate).
            final double hc = 0.5 / (fs2 * GridConduction.CC);
            final double cDen = 1.0 + hc / GridConduction.RG;
            final double cA = (vc + hc * (vs / GridConduction.RG + icPrev)) / cDen;
            final double drive = vs - cA - vkEst;
            final double w = grid.eval(drive);
            final double vg = vkEst + w;
            final double igN = (drive - w) / grid.rs;
            vc = cA + (hc / cDen) * igN;
            icPrev = (vs - vc) / GridConduction.RG + igN;

            final double iMax = bplusEff / RL + 1e-3;
            double i = clamp(ip, 0.0, iMax);
            // The table spans R = g / S up to 0.0125.
            final double sC = bplusEff - kA, gC = vg - kA;
            if (sC >= 80.0 && sC < 304.0 && Double.isFinite(gC) && gC < 0.0125 * sC)
                i = table.eval(sC, gC);
            else for (int it = 0; it < 8; ++it)
            {
                final double vkN = kA + kB * i;
                final double vpk = bplusEff - i * RL - vkN;
                final double vgk = vg - vkN;
                final double ipK = CurrentTable.koren(vpk, vgk, kd);
                final double dVpk = kd[0], dVgk = kd[1];
                final double f = i - ipK;
                final double fp = 1.0 - (dVpk * (-RL - kB) + dVgk * (-kB));
                final double di = f / max(fp, 1e-6);
                i -= di;
                i = clamp(i, 0.0, iMax);
                if (Math.abs(di) < 1e-12) break;
            }

            final double vkN = kA + kB * (i + igN);
            fPrev = i + igN - vkN / RK;
            vk = vkN;
            ip = i;
            ig = igN;
            return (bplusEff - i * RL) - vpDC;
        }
    }

    /** Interstage voltage on one piece: a degree-5 polynomial in the piece
     *  coordinate plus exact terms for modes of intermediate stiffness. */
    private static final class Trajectory
    {
        private static final double[] FACT = { 1.0, 1.0, 2.0, 6.0 };

        final double[] c = new double[6];
        int exactCount = 0;
        final double[] q0 = new double[3], z = new double[3], bt = new double[3], g = new double[3];
        final double[] u = new double[4];
        int uTerms = 1;
        /** Derivative of the last exactTerm(). */
        double termDerivative = 0.0;
        /** Derivative of the last eval(). */
        double derivative = 0.0;
        private final double[] p = new double[5];

        double exactTerm(int k, double xi)
        {
            phi(z[k] * xi, p);
            double forced = 0.0, xp = xi, ui = 0.0, xe = 1.0;
            for (int e = 0; e < uTerms; ++e)
            {
                forced += u[e] * FACT[e] * xp * p[e + 1];
                xp *= xi;
                ui += u[e] * xe;
                xe *= xi;
            }
            final double qv = p[0] * q0[k] + bt[k] * forced;
            termDerivative = g[k] * (z[k] * qv + bt[k] * ui);
            return g[k] * qv;
        }

        double value(double xi)
        {
            double v = ((((c[5] * xi + c[4]) * xi + c[3]) * xi + c[2]) * xi + c[1]) * xi + c[0];
            for (int k = 0; k < exactCount; ++k)
                v += exactTerm(k, xi);
            return v;
        }

        double eval(double xi)
        {
            double v = c[5], d = 0.0;
            for (int e = 4; e >= 0; --e)
            {
                d = d * xi + v;
                v = v * xi + c[e];
            }
            for (int k = 0; k < exactCount; ++k)
            {
                v += exactTerm(k, xi);
                d += termDerivative;
            }
            derivative = d;
            return v;
        }
    }

    /**
     * Continuous-time two-stage core (factors of 2 and more). One call per
     * internal sample; returns the kernel-projected plate voltage of the
     * output stage, delayed by taps/2 + 2 internal samples.
     */
    private static final class ContinuousCore
    {
        /** Clamp model: cutoff, A-3, A-1, +1 V, +6 V. Conduction model:
         *  cutoff, A-3, A-1 and four knees around the conduction onset, and
         *  in stage 1 a level halfway between cutoff and A-3. */
        static final int MAX_LEVELS = 8;
        static final int MAX_PIECES = 48;
        static final int ORDER = CoreDesign.KERNEL_ORDER;
        /** Volts of x needed to split at knees. */
        static final double KNEE_RANGE = 1.0;
        /** Same, conduction knees. */
        static final double CONDUCTION_KNEE_RANGE = 0.25;
        static final double SMALL_Z = 0.3, STIFF_Z = 40.0;
        /** Samples; longer stage-2 pieces use 4 nodes. */
        static final double LONG_PIECE = 0.5;
        /** Samples; shorter pieces use 2 nodes (stage 1: conduction model). */
        static final double SHORT_PIECE = 0.03;
        /** Samples; shorter stage-1 pieces use 3 nodes (conduction model). */
        static final double MEDIUM_PIECE = 0.1;
        /** Grid-to-cathode source below which the grid current (under 1e-9 A)
         *  moves the coupling capacitor by less than 1 uV per sample: the
         *  charge is then held across the piece. */
        static final double CHARGE_FLOOR = -0.8;
        static final double[] CONDUCTION_KNEES = { -0.6, -0.3, 0.1, 1.0 };
        // Gauss-Legendre rules on [0, 1]
        static final double[] G4X = { 0.069431844202973713, 0.33000947820757187,
                                      0.66999052179242813, 0.93056815579702629 };
        static final double[] G4W = { 0.17392742256872692, 0.32607257743127308,
                                      0.32607257743127308, 0.17392742256872692 };
        static final double[] G3X = { 0.11270166537925831, 0.5, 0.88729833462074169 };
        static final double[] G3W = { 5.0 / 18.0, 4.0 / 9.0, 5.0 / 18.0 };
        static final double[] G2X = { 0.21132486540518713, 0.78867513459481287 };
        private static final double[] FACT = { 1.0, 1.0, 2.0, 6.0 };
        private static final double[] INV = { 1.0, 1.0 / 2, 1.0 / 3, 1.0 / 4, 1.0 / 5, 1.0 / 6,
            1.0 / 7, 1.0 / 8, 1.0 / 9, 1.0 / 10, 1.0 / 11, 1.0 / 12, 1.0 / 13, 1.0 / 14,
            1.0 / 15, 1.0 / 16 };
        private static final double[][] BINOM = { {1, 0, 0, 0, 0, 0}, {1, 1, 0, 0, 0, 0}, {1, 2, 1, 0, 0, 0},
            {1, 3, 3, 1, 0, 0}, {1, 4, 6, 4, 1, 0}, {1, 5, 10, 10, 5, 1} };

        CurrentTable table = null;
        CoreDesign design = null;
        /** Conduction model when set. */
        GridTable grid = null;
        double period = 1.0 / 96000.0, cathodeDecay = 0.0, sagDecay = 0.0;
        /** Interval-average grid currents. */
        double g1Prev = 0.0, g2Prev = 0.0;
        double gridIntegral1 = 0.0, gridIntegral2 = 0.0;
        /** Coupling capacitor voltages and interval-average grid sources (conduction). */
        double vc1 = 0.0, vc2 = 0.0, x2Prev = 0.0, x2Integral = 0.0;
        /** Predicted change of the stage-2 grid reference over the interval. */
        double vcSlope2 = 0.0;
        /** Stage-2 grid reference at the interval start. */
        double vcRamp2 = 0.0;
        /** Conduction model: coupling-capacitor charge by grid current since
         *  the interval start (V), the predicted charge slopes that place the
         *  level crossings, the stage-2 leak ramp and the latest grid currents. */
        double charge1 = 0.0, charge2 = 0.0, chargeSlope1 = 0.0, chargeSlope2 = 0.0;
        double leakSlope2 = 0.0, leakEnd2 = 0.0, chargeRise = 0.0, lastIg1 = 0.0, lastIg2 = 0.0;
        double couplingDecay = 1.0;
        final ToneModes modes = new ToneModes();
        final double[] ring = new double[16];
        int ringPos = 0;
        /** Modal tone-circuit state. */
        final double[] q = new double[3];
        double vk1 = 0.0, vk2 = 0.0, ipLP = 0.0, i1Prev = 0.0, i2Prev = 0.0;
        double vp1 = 0.0, vp2 = 0.0;
        /** Pending outputs z[n-2] .. z[n+3]. */
        final double[] out = new double[ORDER];
        final double[] cutSupply = { -1.0, -1.0 }, cutVgk = new double[2];
        // Interval scratch
        final CurrentTable.Slice slice1 = new CurrentTable.Slice(), slice2 = new CurrentTable.Slice();
        double supply = 0.0, a1 = 0.0, a2 = 0.0, s1 = 0.0, s2 = 0.0, ref1 = 0.0, ref2 = 0.0;
        final double[] lev1 = new double[MAX_LEVELS], lev2 = new double[MAX_LEVELS];
        /** Levels in use (5 Clamp; 8 and 7 Conduction). */
        int levels1 = 5, levels2 = 5;
        double integral1 = 0.0, integral2 = 0.0;
        final double[] moments = new double[ORDER];
        double hi1 = 0.0, hi2 = 0.0;
        boolean haveHi1 = false, haveHi2 = false;
        int stages = 2;
        /** Monomial coefficients of the node basis. */
        final double[][] lagrange4 = new double[4][4];
        final double[][] lagrange3 = new double[3][3];
        /** Integrals from 0 to each node of the node basis, and its values at 1. */
        final double[][] nodeIntegral4 = new double[4][4], nodeIntegral3 = new double[3][3], nodeIntegral2 = new double[2][2];
        final double[] endWeight4 = new double[4], endWeight3 = new double[3], endWeight2 = new double[2];

        // Work arrays of the interval.
        private final double[] kd = new double[2];
        private final double[] bias = new double[3];
        private final double[] physical = new double[3];
        private final double[] currentsG = new double[4];
        private final double[] chargeIg = new double[4];
        private final double[] oneX = new double[1], oneR = new double[1], oneG = new double[1];
        private final Trajectory tr = new Trajectory();
        private final double[] pz = new double[5];
        private final double[] der = new double[4];
        private final double[] momentSums = new double[ORDER], momentPowers = new double[ORDER];
        private final double[] plateY = new double[3], platePoly = new double[3];
        // interval()
        private final double[] cPoly = new double[16];
        private final double[] qv = new double[5];
        private final double[] cuts1 = new double[MAX_PIECES];
        private final double[] u1 = new double[1];
        private final double[] src2 = new double[2], offset2 = new double[2], x2n = new double[2];
        private final double[] i2n = new double[2], q2n = new double[2], u2n = new double[2];
        private final double[] x3 = new double[3], src3 = new double[3], offset3 = new double[3];
        private final double[] i3 = new double[3], q3 = new double[3], y3 = new double[3], u3 = new double[3];
        private final double[] xs4 = new double[4], is4 = new double[4], src4 = new double[4];
        private final double[] offset4 = new double[4], gs4 = new double[4], y4 = new double[4], u4 = new double[4];
        // stage2()
        private final double[] cuts2 = new double[16];
        private final double[] sv = new double[5];
        private final double[] bxs4 = new double[4], bis4 = new double[4], bgs4 = new double[4];
        private final double[] by4 = new double[4], bpoly4 = new double[4];
        private final double[] bxs2 = new double[2], bis2 = new double[2], bgs2 = new double[2], bpoly2 = new double[2];
        private final double[] bxs3 = new double[3], bis3 = new double[3], bgs3 = new double[3];
        private final double[] by3 = new double[3], bpoly3 = new double[3];
        // chargeNodes2()
        private final double[] nodeSrc = new double[4], nodeOffset = new double[4];

        /** Copies every state and setting of another core (not its work arrays). */
        void copyFrom(ContinuousCore o)
        {
            table = o.table;
            design = o.design;
            grid = o.grid;
            period = o.period;
            cathodeDecay = o.cathodeDecay;
            sagDecay = o.sagDecay;
            g1Prev = o.g1Prev;
            g2Prev = o.g2Prev;
            gridIntegral1 = o.gridIntegral1;
            gridIntegral2 = o.gridIntegral2;
            vc1 = o.vc1;
            vc2 = o.vc2;
            x2Prev = o.x2Prev;
            x2Integral = o.x2Integral;
            vcSlope2 = o.vcSlope2;
            vcRamp2 = o.vcRamp2;
            charge1 = o.charge1;
            charge2 = o.charge2;
            chargeSlope1 = o.chargeSlope1;
            chargeSlope2 = o.chargeSlope2;
            leakSlope2 = o.leakSlope2;
            leakEnd2 = o.leakEnd2;
            chargeRise = o.chargeRise;
            lastIg1 = o.lastIg1;
            lastIg2 = o.lastIg2;
            couplingDecay = o.couplingDecay;
            modes.copyFrom(o.modes);
            System.arraycopy(o.ring, 0, ring, 0, ring.length);
            ringPos = o.ringPos;
            System.arraycopy(o.q, 0, q, 0, 3);
            vk1 = o.vk1;
            vk2 = o.vk2;
            ipLP = o.ipLP;
            i1Prev = o.i1Prev;
            i2Prev = o.i2Prev;
            vp1 = o.vp1;
            vp2 = o.vp2;
            System.arraycopy(o.out, 0, out, 0, ORDER);
            cutSupply[0] = o.cutSupply[0];
            cutSupply[1] = o.cutSupply[1];
            cutVgk[0] = o.cutVgk[0];
            cutVgk[1] = o.cutVgk[1];
            slice1.copyFrom(o.slice1);
            slice2.copyFrom(o.slice2);
            supply = o.supply;
            a1 = o.a1;
            a2 = o.a2;
            s1 = o.s1;
            s2 = o.s2;
            ref1 = o.ref1;
            ref2 = o.ref2;
            System.arraycopy(o.lev1, 0, lev1, 0, MAX_LEVELS);
            System.arraycopy(o.lev2, 0, lev2, 0, MAX_LEVELS);
            levels1 = o.levels1;
            levels2 = o.levels2;
            integral1 = o.integral1;
            integral2 = o.integral2;
            System.arraycopy(o.moments, 0, moments, 0, ORDER);
            hi1 = o.hi1;
            hi2 = o.hi2;
            haveHi1 = o.haveHi1;
            haveHi2 = o.haveHi2;
            stages = o.stages;
            for (int k = 0; k < 4; ++k)
            {
                System.arraycopy(o.lagrange4[k], 0, lagrange4[k], 0, 4);
                System.arraycopy(o.nodeIntegral4[k], 0, nodeIntegral4[k], 0, 4);
            }
            for (int k = 0; k < 3; ++k)
            {
                System.arraycopy(o.lagrange3[k], 0, lagrange3[k], 0, 3);
                System.arraycopy(o.nodeIntegral3[k], 0, nodeIntegral3[k], 0, 3);
            }
            for (int k = 0; k < 2; ++k)
                System.arraycopy(o.nodeIntegral2[k], 0, nodeIntegral2[k], 0, 2);
            System.arraycopy(o.endWeight4, 0, endWeight4, 0, 4);
            System.arraycopy(o.endWeight3, 0, endWeight3, 0, 3);
            System.arraycopy(o.endWeight2, 0, endWeight2, 0, 2);
        }

        void init(double sampleRate, CurrentTable tableIn, CoreDesign designIn)
        {
            table = tableIn;
            design = designIn;
            period = 1.0 / sampleRate;
            cathodeDecay = Math.exp(-period / (RK * CK));
            sagDecay = Math.exp(-period / 0.07);
            couplingDecay = Math.exp(-period / (GridConduction.RG * GridConduction.CC));
            for (int k = 0; k < 4; ++k)
            {
                double[] poly = { 1.0, 0.0, 0.0, 0.0 };
                double den = 1.0;
                int deg = 0;
                for (int o = 0; o < 4; ++o)
                {
                    if (o == k) continue;
                    final double[] next = { 0.0, 0.0, 0.0, 0.0 };
                    for (int e = 0; e <= deg; ++e)
                    {
                        next[e + 1] += poly[e];
                        next[e] -= G4X[o] * poly[e];
                    }
                    ++deg;
                    poly = next;
                    den *= G4X[k] - G4X[o];
                }
                for (int e = 0; e < 4; ++e) lagrange4[k][e] = poly[e] / den;
            }
            for (int k = 0; k < 3; ++k)
            {
                final int o1 = (k + 1) % 3, o2 = (k + 2) % 3;
                final double den = (G3X[k] - G3X[o1]) * (G3X[k] - G3X[o2]);
                lagrange3[k][0] = G3X[o1] * G3X[o2] / den;
                lagrange3[k][1] = -(G3X[o1] + G3X[o2]) / den;
                lagrange3[k][2] = 1.0 / den;
            }
            chargeRise = (1.0 - couplingDecay) * GridConduction.RG;
            for (int j = 0; j < 4; ++j)
                for (int k = 0; k < 4; ++k)
                {
                    double acc = 0.0, xp = G4X[j];
                    for (int e = 0; e < 4; ++e) { acc += lagrange4[k][e] * xp / (e + 1); xp *= G4X[j]; }
                    nodeIntegral4[j][k] = acc;
                }
            for (int j = 0; j < 3; ++j)
                for (int k = 0; k < 3; ++k)
                {
                    double acc = 0.0, xp = G3X[j];
                    for (int e = 0; e < 3; ++e) { acc += lagrange3[k][e] * xp / (e + 1); xp *= G3X[j]; }
                    nodeIntegral3[j][k] = acc;
                }
            for (int k = 0; k < 4; ++k)
                endWeight4[k] = lagrange4[k][0] + lagrange4[k][1] + lagrange4[k][2] + lagrange4[k][3];
            for (int k = 0; k < 3; ++k) endWeight3[k] = lagrange3[k][0] + lagrange3[k][1] + lagrange3[k][2];
            final double g0 = G2X[0], g1 = G2X[1];
            for (int j = 0; j < 2; ++j)
            {
                final double x = G2X[j];
                nodeIntegral2[j][0] = (0.5 * x * x - g1 * x) / (g0 - g1);
                nodeIntegral2[j][1] = (0.5 * x * x - g0 * x) / (g1 - g0);
            }
            endWeight2[0] = (1.0 - g1) / (g0 - g1);
            endWeight2[1] = (1.0 - g0) / (g1 - g0);
        }

        /**
         * Coupling-capacitor charge at the N nodes of a piece (conduction
         * model): the charge at its start plus the integral of the grid
         * current over the piece, Rg (1 - e^(-T / (Rg Cc))) per internal
         * sample and ampere, from the node basis of the currents. The
         * currents depend on the charge, so a prediction from the last grid
         * current is corrected once with the currents it gives.
         *
         * @param n        node count
         * @param src      grid sources at the nodes without the charge
         * @param cathode  grid reference (cathode and capacitor start)
         * @param charge   charge at the piece start
         * @param len      piece length in internal samples
         * @param lastIg   grid current at the end of the previous piece
         * @param weights  node-basis integrals (nodeIntegral4/3/2)
         * @param nodes    node positions in the piece
         * @param offset   receives the charge at each node
         */
        void chargeAtNodes(int n, double[] src, double cathode, double charge, double len, double lastIg,
                           double[][] weights, double[] nodes, double[] offset)
        {
            // Below CHARGE_FLOOR at every node the grid current is negligible
            // (and smaller still at a larger charge): the nodes keep the charge.
            double top = src[0];
            for (int j = 1; j < n; ++j) top = max(top, src[j]);
            if (top - charge - cathode <= CHARGE_FLOOR)
            {
                for (int j = 0; j < n; ++j) offset[j] = charge;
                return;
            }
            final double k = chargeRise * len;
            for (int j = 0; j < n; ++j) offset[j] = charge + k * lastIg * nodes[j];
            final double[] ig = chargeIg;
            for (int j = 0; j < n; ++j)
            {
                final double d = src[j] - offset[j] - cathode;
                ig[j] = (d - grid.eval(d)) * (1.0 / GridConduction.RS);
            }
            for (int j = 0; j < n; ++j)
            {
                double acc = 0.0;
                for (int m = 0; m < n; ++m) acc += weights[j][m] * ig[m];
                offset[j] = charge + k * acc;
            }
        }

        /** Re-designs the modal tone circuit, keeping capacitor voltages. */
        void setTone(ToneStackFMV.AnalogStateSpace ss)
        {
            physical[0] = physical[1] = physical[2] = 0.0;
            for (int i = 0; i < 3; ++i)
                for (int m = 0; m < 3; ++m)
                    physical[i] += modes.toPhysical[i][m] * q[m];
            modes.design(ss, 1.0 / period);
            for (int m = 0; m < 3; ++m)
            {
                q[m] = 0.0;
                for (int i = 0; i < 3; ++i) q[m] += modes.toModal[m][i] * physical[i];
            }
        }

        /** The sagged DC point: supply, stage-1 current and stage-2 current in out. */
        static void dcBias(double sagR, int numStages, double grid, double[] kd, double[] out)
        {
            double bp = BPLUS, i1 = 0.0, i2 = 0.0, iTotal;
            for (int it = 0; it < 40; ++it)
            {
                i1 = settleCurrent(bp, grid, kd);
                i2 = numStages > 1 && grid != 0.0 ? settleCurrent(bp, 0.0, kd) : i1;
                iTotal = i1 + (numStages > 1 ? i2 : 0.0);
                final double next = BPLUS - sagR * iTotal;
                if (Math.abs(next - bp) < 1e-12) { bp = next; break; }
                bp = next;
            }
            out[0] = bp;
            out[1] = i1;
            out[2] = i2;
        }

        void reset(double sagR, int numStages)
        {
            dcBias(sagR, numStages, 0.0, kd, bias);
            final double bp = bias[0], i1 = bias[1];
            vk1 = vk2 = i1 * RK;
            vp1 = vp2 = bp - i1 * RL;
            ipLP = i1 * numStages;
            i1Prev = i2Prev = i1;
            g1Prev = g2Prev = 0.0;
            vc1 = vc2 = x2Prev = x2Integral = 0.0;
            vcRamp2 = vcSlope2 = 0.0;
            charge1 = charge2 = chargeSlope1 = chargeSlope2 = leakSlope2 = leakEnd2 = 0.0;
            lastIg1 = lastIg2 = 0.0;
            q[0] = q[1] = q[2] = 0.0;
            Arrays.fill(ring, 0.0);
            ringPos = 0;
            Arrays.fill(out, 0.0);
            cutSupply[0] = cutSupply[1] = -1.0;
        }

        static double cutoffVgk(double s)
        {
            // Grid voltage relative to the cathode below which the plate
            // current is < 1e-10 A (plate effect < 10 uV) at vpk ~= s.
            return CurrentTable.gridForCurrent(s, 1e-10);
        }

        double currentSlow(double s, double g)
        {
            // Frozen-cathode load line outside the table: Ip = Koren(s - RL Ip, g).
            double i = 0.5 * max(s, 0.0) / RL;
            for (int it = 0; it < 60; ++it)
            {
                final double p = CurrentTable.koren(s - RL * i, g, kd);
                final double dp = kd[0];
                final double next = clamp(i - (i - p) / (1.0 + RL * dp), 0.0, max(s, 0.0) / RL);
                if (Math.abs(next - i) < 1e-16) { i = next; break; }
                i = next;
            }
            return i;
        }

        /** Plate currents at grid sources x; with the conduction model also
         *  the grid currents (left untouched otherwise). */
        void currents(int n, CurrentTable.Slice sl, double cathode, double[] x, double[] result, double[] gridCurrent)
        {
            final double[] g = currentsG;
            if (grid != null)
            {
                for (int j = 0; j < n; ++j)
                {
                    final double d = x[j] - cathode;
                    g[j] = grid.eval(d);
                    gridCurrent[j] = (d - g[j]) * (1.0 / GridConduction.RS);
                }
                // The table spans R = g / S up to 0.0125.
                final double gMax = 0.0125 * sl.s;
                for (int j = 0; j < n; ++j)
                    result[j] = (sl.inside && g[j] < gMax) ? sl.eval(g[j]) : currentSlow(sl.s, g[j]);
                return;
            }
            for (int j = 0; j < n; ++j) g[j] = GridClamp.value(x[j]) - cathode;
            for (int j = 0; j < n; ++j)
                result[j] = (sl.inside && g[j] < 1.0) ? sl.eval(g[j]) : currentSlow(sl.s, g[j]);
        }

        double current(CurrentTable.Slice sl, double cathode, double x)
        {
            oneX[0] = x;
            currents(1, sl, cathode, oneX, oneR, oneG);
            return oneR[0];
        }

        static int classify(double[] lev, int levels, double v)
        {
            int k = 0;
            while (k < levels && v > lev[k]) ++k;
            return k;
        }

        static int classifyEdges(double[] lev, int levels, double v)
        {
            return v <= lev[0] ? 0 : (v > lev[levels - 1] ? levels : 1);
        }

        void addMoments(double tau, double weight, double y)
        {
            final double d = tau - 0.5;
            double t = weight * y;
            for (int p = 0; p < ORDER; ++p) { moments[p] += t; t *= d; }
        }

        void addConstantMoments(double a, double b, double y)
        {
            final double pa = a - 0.5, pb = b - 0.5;
            double ea = pa, eb = pb;
            for (int p = 0; p < ORDER; ++p)
            {
                moments[p] += y * (eb - ea) / (p + 1);
                ea *= pa;
                eb *= pb;
            }
        }

        /** Moments of sum_e c[e] xi^e over the piece [a, a + h]. */
        void addPolynomialMoments(double a, double h, double[] c, int degree)
        {
            final double[] sums = momentSums, dk = momentPowers;
            double hk = 1.0;
            dk[0] = 1.0;
            for (int k = 1; k < ORDER; ++k) dk[k] = dk[k - 1] * (a - 0.5);
            for (int k = 0; k < ORDER; ++k)
            {
                double acc = 0.0;
                for (int e = 0; e <= degree; ++e) acc += c[e] * INV[e + k];
                sums[k] = acc * hk;
                hk *= h;
            }
            for (int p = 0; p < ORDER; ++p)
            {
                final double[] row = BINOM[p];
                double acc = 0.0;
                for (int k = 0; k <= p; ++k) acc += row[k] * dk[p - k] * sums[k];
                moments[p] += h * acc;
            }
        }

        /** Propagates the modal state over a piece of length h (internal
         *  samples) whose stage-1 plate voltage is sum_e u[e] xi^e, and fills
         *  the tone-circuit output on the piece. */
        void propagate(double h, double[] u, int terms, Trajectory t)
        {
            t.exactCount = 0;
            t.uTerms = terms;
            for (int e = 0; e < 4; ++e) t.u[e] = e < terms ? u[e] : 0.0;
            final double[] tc = t.c;
            for (int e = 0; e < 6; ++e) tc[e] = 0.0;
            final double u0 = u[0], u0p = terms > 1 ? u[1] : 0.0;
            double uEnd = 0.0, uEndP = 0.0;
            for (int e = 0; e < terms; ++e)
            {
                uEnd += u[e];
                uEndP += e * u[e];
            }
            // Non-stiff modal sum: exact values and first/second derivatives
            // at both ends (from dq/dxi = z q + bt u), then a quintic Hermite.
            double h0 = 0.0, h0p = 0.0, h0pp = 0.0, h1 = 0.0, h1p = 0.0, h1pp = 0.0;
            for (int m = 0; m < 3; ++m)
            {
                final double z = modes.lambda[m] * h, bt = modes.beta[m] * h, g = modes.gamma[m];
                final double az = Math.abs(z);
                if (az >= STIFF_Z)
                {
                    // Slow manifold of dq/dxi = z q + bt u:
                    // q = -(bt/z) sum_k u^(k)(xi) / z^k (boundary layer < e^-40).
                    final double iz = 1.0 / z;
                    der[0] = der[1] = der[2] = der[3] = 0.0;
                    for (int e = 0; e < terms; ++e) der[e] = u[e];
                    double izp = 1.0, endValue = 0.0;
                    for (int k = 0; k < terms; ++k)
                    {
                        double sumAtEnd = 0.0;
                        for (int e = 0; e < 4; ++e)
                        {
                            tc[e] += -g * bt * iz * izp * der[e];
                            sumAtEnd += der[e];
                        }
                        endValue += izp * sumAtEnd;
                        for (int e = 0; e < 3; ++e) der[e] = der[e + 1] * (e + 1);
                        der[3] = 0.0;
                        izp *= iz;
                    }
                    q[m] = -bt * iz * endValue;
                    continue;
                }
                // A whole interval (h == 1) reads the values kept per mode.
                final double[] p;
                if (h == 1.0) p = modes.unitPhi[m];
                else
                {
                    phi(z, pz);
                    p = pz;
                }
                double forced = 0.0;
                for (int e = 0; e < terms; ++e) forced += u[e] * FACT[e] * p[e + 1];
                final double qEnd = p[0] * q[m] + bt * forced;
                if (az > SMALL_Z)
                {
                    final int k = t.exactCount++;
                    t.q0[k] = q[m];
                    t.z[k] = z;
                    t.bt[k] = bt;
                    t.g[k] = g;
                    q[m] = qEnd;
                    continue;
                }
                final double d0 = z * q[m] + bt * u0, d1 = z * qEnd + bt * uEnd;
                h0 += g * q[m];
                h0p += g * d0;
                h0pp += g * (z * d0 + bt * u0p);
                h1 += g * qEnd;
                h1p += g * d1;
                h1pp += g * (z * d1 + bt * uEndP);
                q[m] = qEnd;
            }
            final double quad = 0.5 * h0pp;
            final double r0 = h1 - (h0 + h0p + quad), r1 = h1p - (h0p + 2.0 * quad), r2 = h1pp - 2.0 * quad;
            tc[0] += h0;
            tc[1] += h0p;
            tc[2] += quad;
            tc[3] += 10.0 * r0 - 4.0 * r1 + 0.5 * r2;
            tc[4] += -15.0 * r0 + 7.0 * r1 - r2;
            tc[5] += 6.0 * r0 - 3.0 * r1 + 0.5 * r2;
            for (int e = 0; e < terms; ++e) tc[e] += modes.direct * u[e];
        }

        /** The stage-2 grid source on the trajectory at a piece position. */
        private static double source2(Trajectory t, double ramp, double a, double h, double xi)
        {
            return INTERSTAGE * t.value(xi) - ramp * (a + h * xi);
        }

        /** Stage 2 over the piece [a, a + h] (interval coordinates). */
        void stage2(double a, double h, Trajectory t)
        {
            final double[] cuts = cuts2;
            int count = 0;
            cuts[count++] = 0.0;
            final int samples = h > 0.5 ? 4 : 2;
            double lo = Double.MAX_VALUE, hi = -lo;
            for (int k = 0; k <= samples; ++k)
            {
                sv[k] = INTERSTAGE * t.value((double) k / samples);
                lo = min(lo, sv[k]);
                hi = max(hi, sv[k]);
            }
            // Conduction model: the coupling capacitor's predicted change
            // across the interval (linear in time), removed from the source
            // that the grid sees; the capacitor update uses the source itself.
            final boolean conduction = grid != null;
            final double ramp = conduction ? vcSlope2 : 0.0;
            if (conduction)   // Simpson average of the stage-2 source on this piece
            {
                x2Integral += h * (samples == 4 ? (sv[0] + 4.0 * sv[1] + 2.0 * sv[2] + 4.0 * sv[3] + sv[4]) / 12.0
                                                : (sv[0] + 4.0 * sv[1] + sv[2]) / 6.0);
                lo = Double.MAX_VALUE;
                hi = -lo;
                for (int k = 0; k <= samples; ++k)
                {
                    sv[k] -= ramp * (a + h * (double) k / samples);
                    lo = min(lo, sv[k]);
                    hi = max(hi, sv[k]);
                }
            }
            final boolean edges = hi - lo < (conduction ? CONDUCTION_KNEE_RANGE : KNEE_RANGE);
            final int levels = levels2;
            double pt = 0.0, pv = sv[0];
            int pc = edges ? classifyEdges(lev2, levels, pv) : classify(lev2, levels, pv);
            final int first = pc;
            boolean changed = false;
            for (int k = 1; k <= samples; ++k)
            {
                final double tk = (double) k / samples, v = sv[k];
                final int cc = edges ? classifyEdges(lev2, levels, v) : classify(lev2, levels, v);
                if (cc != pc)
                {
                    changed = true;
                    final int low = Math.min(cc, pc), high = Math.max(cc, pc);
                    for (int r = 0; r < high - low; ++r)
                    {
                        int li = cc > pc ? low + r : high - 1 - r;
                        if (edges) li = li == 0 ? 0 : levels - 1;
                        final double level = lev2[li];
                        double tt = pt + (tk - pt) * (pv - level) / (pv - v);
                        for (int it = 0; it < 2; ++it)
                        {
                            final double value = t.eval(tt);
                            double dv = t.derivative;
                            final double f = INTERSTAGE * value - ramp * (a + h * tt) - level;
                            dv = dv * INTERSTAGE - ramp * h;
                            if (dv == 0.0) break;
                            final double next = tt - f / dv;
                            if (!(next > pt && next < tk)) break;
                            tt = next;
                        }
                        if (tt > cuts[count - 1] + 1e-12 && count < 14) cuts[count++] = tt;
                    }
                }
                pt = tk;
                pv = v;
                pc = cc;
            }
            cuts[count++] = 1.0;
            for (int k = 0; k + 1 < count; ++k)
            {
                final double xa = cuts[k], xb = cuts[k + 1], len = xb - xa;
                if (len <= 0.0) continue;
                final int cm;
                if (changed)
                {
                    final double mid = source2(t, ramp, a, h, 0.5 * (xa + xb));
                    cm = edges ? classifyEdges(lev2, levels, mid) : classify(lev2, levels, mid);
                }
                else cm = first;
                if (cm == 0 || (cm == levels && !conduction))
                {
                    double i2 = 0.0;
                    if (cm == levels)
                    {
                        if (!haveHi2) { hi2 = current(slice2, a2, 1e3); haveHi2 = true; }
                        i2 = hi2;
                    }
                    integral2 += h * len * i2;
                    if (conduction) lastIg2 = 0.0;
                    addConstantMoments(a + h * xa, a + h * xb, supply - RL * i2 - vp2);
                    continue;
                }
                if (h * len > LONG_PIECE)
                {
                    // Long smooth piece: a cubic interpolant keeps harmonics
                    // near 0.8 cycles/sample from leaking past the kernel.
                    final double[] xs = bxs4, is = bis4, gs = bgs4, y = by4, poly = bpoly4;
                    for (int j = 0; j < 4; ++j) xs[j] = source2(t, ramp, a, h, xa + len * G4X[j]);
                    if (conduction) chargeNodes2(4, xs, a, h, xa, len, nodeIntegral4, G4X);
                    currents(4, slice2, ref2, xs, is, gs);
                    for (int j = 0; j < 4; ++j)
                    {
                        integral2 += h * len * G4W[j] * is[j];
                        y[j] = supply - RL * is[j] - vp2;
                    }
                    if (conduction)
                    {
                        double sum = 0.0, end = 0.0;
                        for (int j = 0; j < 4; ++j) { sum += G4W[j] * gs[j]; end += endWeight4[j] * gs[j]; }
                        gridIntegral2 += h * len * sum;
                        charge2 += chargeRise * h * len * sum;
                        lastIg2 = max(end, 0.0);
                    }
                    for (int e = 0; e < 4; ++e)
                        poly[e] = lagrange4[0][e] * y[0] + lagrange4[1][e] * y[1]
                                + lagrange4[2][e] * y[2] + lagrange4[3][e] * y[3];
                    addPolynomialMoments(a + h * xa, h * len, poly, 3);
                    continue;
                }
                if (h * len < SHORT_PIECE)
                {
                    // Short piece: two nodes and the exact moments of their
                    // linear interpolant.
                    final double g0 = G2X[0], g1 = G2X[1];
                    final double[] xs = bxs2, is = bis2, gs = bgs2, poly = bpoly2;
                    xs[0] = source2(t, ramp, a, h, xa + len * g0);
                    xs[1] = source2(t, ramp, a, h, xa + len * g1);
                    if (conduction) chargeNodes2(2, xs, a, h, xa, len, nodeIntegral2, G2X);
                    currents(2, slice2, ref2, xs, is, gs);
                    integral2 += h * len * 0.5 * (is[0] + is[1]);
                    if (conduction)
                    {
                        gridIntegral2 += h * len * 0.5 * (gs[0] + gs[1]);
                        charge2 += chargeRise * h * len * 0.5 * (gs[0] + gs[1]);
                        lastIg2 = max(endWeight2[0] * gs[0] + endWeight2[1] * gs[1], 0.0);
                    }
                    final double y0 = supply - RL * is[0] - vp2, y1 = supply - RL * is[1] - vp2;
                    poly[1] = (y1 - y0) / (g1 - g0);
                    poly[0] = y0 - poly[1] * g0;
                    addPolynomialMoments(a + h * xa, h * len, poly, 1);
                    continue;
                }
                final double[] xs = bxs3, is = bis3, gs = bgs3, y = by3, poly = bpoly3;
                for (int j = 0; j < 3; ++j) xs[j] = source2(t, ramp, a, h, xa + len * G3X[j]);
                if (conduction) chargeNodes2(3, xs, a, h, xa, len, nodeIntegral3, G3X);
                currents(3, slice2, ref2, xs, is, gs);
                if (conduction)
                {
                    double sum = 0.0, end = 0.0;
                    for (int j = 0; j < 3; ++j) { sum += G3W[j] * gs[j]; end += endWeight3[j] * gs[j]; }
                    gridIntegral2 += h * len * sum;
                    charge2 += chargeRise * h * len * sum;
                    lastIg2 = max(end, 0.0);
                }
                // Exact moments of the plate interpolant through the three
                // nodes: Gauss weights alone are not exact for the quintic
                // kernel weights times a curved plate trajectory.
                for (int j = 0; j < 3; ++j)
                {
                    integral2 += h * len * G3W[j] * is[j];
                    y[j] = supply - RL * is[j] - vp2;
                }
                for (int e = 0; e < 3; ++e)
                    poly[e] = lagrange3[0][e] * y[0] + lagrange3[1][e] * y[1] + lagrange3[2][e] * y[2];
                addPolynomialMoments(a + h * xa, h * len, poly, 2);
            }
        }

        /** Stage-2 grid sources at the nodes of a piece with the capacitor
         *  charge in place of its predicted ramp (conduction model). */
        void chargeNodes2(int n, double[] xs, double a, double h, double xa, double len,
                          double[][] weights, double[] nodes)
        {
            final double[] src = nodeSrc, offset = nodeOffset;
            for (int j = 0; j < n; ++j) src[j] = xs[j] + chargeSlope2 * (a + h * (xa + len * nodes[j]));
            chargeAtNodes(n, src, ref2, charge2, h * len, lastIg2, weights, nodes, offset);
            for (int j = 0; j < n; ++j) xs[j] = src[j] - offset[j];
        }

        void plate(double a, double h, double[] u, int terms)
        {
            final Trajectory t = tr;
            propagate(h, u, terms, t);
            if (stages > 1)
                stage2(a, h, t);
            else if (t.exactCount == 0)
                addPolynomialMoments(a, h, t.c, 5);
            else
            {
                // Intermediate-stiffness mode present: interpolate the
                // tone-circuit output at the nodes, then take exact moments.
                final double[] y = plateY, poly = platePoly;
                y[0] = t.value(G3X[0]);
                y[1] = t.value(G3X[1]);
                y[2] = t.value(G3X[2]);
                for (int e = 0; e < 3; ++e)
                    poly[e] = lagrange3[0][e] * y[0] + lagrange3[1][e] * y[1] + lagrange3[2][e] * y[2];
                addPolynomialMoments(a, h, poly, 2);
            }
        }

        double process(double x, int numStages, double sagR)
        {
            stages = numStages;
            final int taps = design.taps;
            ring[ringPos] = x;
            ringPos = ringPos + 1 == taps ? 0 : ringPos + 1;
            interval(sagR);
            final double result = out[0];
            for (int k = 0; k + 1 < ORDER; ++k) out[k] = out[k + 1];
            out[ORDER - 1] = 0.0;
            return result;
        }

        /** The stage-1 grid polynomial of the interval at tau. */
        private double xAt(double tau, int deg)
        {
            final double[] c = cPoly;
            double v = c[deg];
            for (int p = deg - 1; p >= 0; --p) v = v * tau + c[p];
            return v;
        }

        void interval(double sagR)
        {
            // Stage-1 grid polynomial on the interval [n, n+1].
            final int taps = design.taps;
            int deg = design.degree;
            final double[] c = cPoly;
            Arrays.fill(c, 0.0);
            final double[] farrow = design.farrow;
            int idx = ringPos;
            for (int j = 0; j < taps; ++j)
            {
                final double v = ring[idx];
                idx = idx + 1 == taps ? 0 : idx + 1;
                final int row = j * (deg + 1);
                for (int p = 0; p <= deg; ++p) c[p] += farrow[row + p] * v;
            }
            // Slow states frozen at the predicted interval midpoint.
            final double iPrev = i1Prev + (stages > 1 ? i2Prev : 0.0);
            supply = BPLUS - sagR * (ipLP + 0.5 * (1.0 - sagDecay) * (iPrev - ipLP));
            a1 = vk1 + 0.5 * (1.0 - cathodeDecay) * ((i1Prev + g1Prev) * RK - vk1);
            a2 = vk2 + 0.5 * (1.0 - cathodeDecay) * ((i2Prev + g2Prev) * RK - vk2);
            s1 = supply - a1;
            s2 = supply - a2;
            slice1.set(table, s1);
            slice2.set(table, s2);
            if (Math.abs(s1 - cutSupply[0]) > 0.05) { cutSupply[0] = s1; cutVgk[0] = cutoffVgk(s1); }
            if (Math.abs(s2 - cutSupply[1]) > 0.05) { cutSupply[1] = s2; cutVgk[1] = cutoffVgk(s2); }
            // Grid references: the cathode, plus the coupling capacitor voltage
            // (conduction model), advanced exactly below with the interval-
            // average source and grid current.
            ref1 = a1;
            ref2 = a2;
            double x1Avg = 0.0;
            final boolean conduction = grid != null;
            if (conduction)
            {
                // The coupling capacitor follows Cc dvc/dt = (x - vc) / Rg + Ig
                // inside the interval: vc(t) = vc + rise (X(t) - vc t) + Q(t)
                // to first order in rise = 1 - e^(-T / (Rg Cc)), with X the
                // integral of the source and Q the grid-current charge. Stage
                // 1: the leak is exact in the source polynomial (one degree
                // more), the charge is integrated piece by piece
                // (chargeAtNodes()); the predicted charge slope only places
                // the level crossings. Stage 2: the leak ramps from where the
                // previous reference ended to its predicted end (its source is
                // known only piece by piece), the charge as in stage 1.
                for (int p = 0; p <= deg; ++p) x1Avg += c[p] / (p + 1);
                final double rise = 1.0 - couplingDecay;
                chargeSlope1 = chargeRise * g1Prev;
                ref1 += vc1;
                for (int p = deg + 1; p >= 1; --p) c[p] -= rise * c[p - 1] / p;
                c[1] += rise * vc1 - chargeSlope1;
                ++deg;
                leakEnd2 = vc2 + rise * (x2Prev - vc2);
                leakSlope2 = leakEnd2 - vcRamp2;
                chargeSlope2 = chargeRise * g2Prev;
                vcSlope2 = leakSlope2 + chargeSlope2;
                ref2 += vcRamp2;
                charge1 = charge2 = 0.0;
            }
            lev1[0] = ref1 + cutVgk[0]; lev1[1] = ref1 - 3.0; lev1[2] = ref1 - 1.0; lev1[3] = 1.0; lev1[4] = 6.0;
            lev2[0] = ref2 + cutVgk[1]; lev2[1] = ref2 - 3.0; lev2[2] = ref2 - 1.0; lev2[3] = 1.0; lev2[4] = 6.0;
            if (conduction)
            {
                // Conduction knee relative to the cathode, where the grid
                // slope falls from 1 to 0.2; no saturated top region, the
                // grid keeps rising slowly with the source. Stage 1 is also
                // split halfway between cutoff and A-3, where the plate
                // current grows about six decades.
                levels1 = 8;
                levels2 = 7;
                for (int k = 7; k >= 2; --k) lev1[k] = lev1[k - 1];
                lev1[1] = 0.5 * (lev1[0] + lev1[2]);
                for (int k = 0; k < 4; ++k)
                {
                    lev1[4 + k] = ref1 + CONDUCTION_KNEES[k];
                    lev2[3 + k] = ref2 + CONDUCTION_KNEES[k];
                }
            }
            for (int j = 1; j < levels1; ++j) lev1[j] = max(lev1[j], lev1[j - 1] + 1e-6);
            for (int j = 1; j < levels2; ++j) lev2[j] = max(lev2[j], lev2[j - 1] + 1e-6);
            // Split the interval where the grid crosses the stage-1 levels;
            // interior knees only when the interval spans at least 1 V.
            double lo = Double.MAX_VALUE, hi = -lo;
            for (int k = 0; k <= 4; ++k)
            {
                qv[k] = xAt(0.25 * k, deg);
                lo = min(lo, qv[k]);
                hi = max(hi, qv[k]);
            }
            final boolean edges = hi - lo < (conduction ? CONDUCTION_KNEE_RANGE : KNEE_RANGE);
            final int levels = levels1;
            final double[] cuts = cuts1;
            int count = 0;
            cuts[count++] = 0.0;
            double pt = 0.0, pv = qv[0];
            int pc = edges ? classifyEdges(lev1, levels, pv) : classify(lev1, levels, pv);
            final int first = pc;
            boolean changed = false;
            for (int k = 1; k <= 4; ++k)
            {
                final double t = 0.25 * k, v = qv[k];
                final int cc = edges ? classifyEdges(lev1, levels, v) : classify(lev1, levels, v);
                if (cc != pc)
                {
                    changed = true;
                    final int low = Math.min(cc, pc), high = Math.max(cc, pc);
                    for (int r = 0; r < high - low; ++r)
                    {
                        int li = cc > pc ? low + r : high - 1 - r;
                        if (edges) li = li == 0 ? 0 : levels - 1;
                        final double level = lev1[li];
                        double tt = pt + (t - pt) * (pv - level) / (pv - v);
                        for (int it = 0; it < 2; ++it)
                        {
                            double f = c[deg], df = 0.0;
                            for (int p = deg - 1; p >= 0; --p) { df = df * tt + f; f = f * tt + c[p]; }
                            f -= level;
                            if (df == 0.0) break;
                            final double next = tt - f / df;
                            if (!(next > pt && next < t)) break;
                            tt = next;
                        }
                        if (tt > cuts[count - 1] + 1e-12 && count < MAX_PIECES - 2) cuts[count++] = tt;
                    }
                }
                pt = t;
                pv = v;
                pc = cc;
            }
            cuts[count++] = 1.0;
            integral1 = integral2 = 0.0;
            gridIntegral1 = gridIntegral2 = 0.0;
            x2Integral = 0.0;
            Arrays.fill(moments, 0.0);
            haveHi1 = haveHi2 = false;
            for (int k = 0; k + 1 < count; ++k)
            {
                final double a = cuts[k], b = cuts[k + 1], h = b - a;
                if (h <= 0.0) continue;
                final int cm;
                if (changed)
                {
                    final double mid = xAt(0.5 * (a + b), deg);
                    cm = edges ? classifyEdges(lev1, levels, mid) : classify(lev1, levels, mid);
                }
                else cm = first;
                if (cm == 0 || (cm == levels && !conduction))
                {
                    // Stage 1 saturated: constant plate voltage on the piece.
                    double i1 = 0.0;
                    if (cm == levels)
                    {
                        if (!haveHi1) { hi1 = current(slice1, a1, 1e3); haveHi1 = true; }
                        i1 = hi1;
                    }
                    integral1 += h * i1;
                    if (conduction) lastIg1 = 0.0;
                    u1[0] = supply - RL * i1 - vp1;
                    plate(a, h, u1, 1);
                    continue;
                }
                if (conduction && h < SHORT_PIECE)
                {
                    // Short piece (conduction model): two nodes and the exact
                    // moments of the plate's linear interpolant, as in stage 2.
                    final double g0 = G2X[0], g1 = G2X[1];
                    final double ta = a + h * g0, tb = a + h * g1;
                    double va = c[deg], vb = c[deg];
                    for (int p = deg - 1; p >= 0; --p) { va = va * ta + c[p]; vb = vb * tb + c[p]; }
                    src2[0] = va + chargeSlope1 * ta;
                    src2[1] = vb + chargeSlope1 * tb;
                    chargeAtNodes(2, src2, ref1, charge1, h, lastIg1, nodeIntegral2, G2X, offset2);
                    x2n[0] = src2[0] - offset2[0];
                    x2n[1] = src2[1] - offset2[1];
                    currents(2, slice1, ref1, x2n, i2n, q2n);
                    integral1 += h * 0.5 * (i2n[0] + i2n[1]);
                    gridIntegral1 += h * 0.5 * (q2n[0] + q2n[1]);
                    charge1 += chargeRise * h * 0.5 * (q2n[0] + q2n[1]);
                    lastIg1 = max(endWeight2[0] * q2n[0] + endWeight2[1] * q2n[1], 0.0);
                    final double y0 = supply - RL * i2n[0] - vp1, y1 = supply - RL * i2n[1] - vp1;
                    u2n[1] = (y1 - y0) / (g1 - g0);
                    u2n[0] = y0 - u2n[1] * g0;
                    plate(a, h, u2n, 2);
                    continue;
                }
                if (conduction && h < MEDIUM_PIECE)
                {
                    // Medium piece (conduction model): three nodes and the
                    // exact moments of their quadratic interpolant.
                    for (int j = 0; j < 3; ++j)
                    {
                        final double tj = a + h * G3X[j];
                        double v = c[deg];
                        for (int p = deg - 1; p >= 0; --p) v = v * tj + c[p];
                        src3[j] = v + chargeSlope1 * tj;
                    }
                    chargeAtNodes(3, src3, ref1, charge1, h, lastIg1, nodeIntegral3, G3X, offset3);
                    for (int j = 0; j < 3; ++j) x3[j] = src3[j] - offset3[j];
                    currents(3, slice1, ref1, x3, i3, q3);
                    double sum = 0.0, end = 0.0;
                    for (int j = 0; j < 3; ++j)
                    {
                        integral1 += h * G3W[j] * i3[j];
                        y3[j] = supply - RL * i3[j] - vp1;
                        sum += G3W[j] * q3[j];
                        end += endWeight3[j] * q3[j];
                    }
                    gridIntegral1 += h * sum;
                    charge1 += chargeRise * h * sum;
                    lastIg1 = max(end, 0.0);
                    for (int e = 0; e < 3; ++e)
                        u3[e] = lagrange3[0][e] * y3[0] + lagrange3[1][e] * y3[1] + lagrange3[2][e] * y3[2];
                    plate(a, h, u3, 3);
                    continue;
                }
                final double[] xs = xs4, is = is4;
                {
                    final double t0 = a + h * G4X[0], t1 = a + h * G4X[1];
                    final double t2 = a + h * G4X[2], t3 = a + h * G4X[3];
                    double v0 = c[deg], v1 = c[deg], v2 = c[deg], v3 = c[deg];
                    for (int p = deg - 1; p >= 0; --p)
                    {
                        v0 = v0 * t0 + c[p];
                        v1 = v1 * t1 + c[p];
                        v2 = v2 * t2 + c[p];
                        v3 = v3 * t3 + c[p];
                    }
                    xs[0] = v0; xs[1] = v1; xs[2] = v2; xs[3] = v3;
                }
                if (conduction)
                {
                    for (int j = 0; j < 4; ++j) src4[j] = xs[j] + chargeSlope1 * (a + h * G4X[j]);
                    chargeAtNodes(4, src4, ref1, charge1, h, lastIg1, nodeIntegral4, G4X, offset4);
                    for (int j = 0; j < 4; ++j) xs[j] = src4[j] - offset4[j];
                }
                final double[] gs = gs4;
                currents(4, slice1, ref1, xs, is, gs);
                final double[] y = y4;
                for (int j = 0; j < 4; ++j)
                {
                    y[j] = supply - RL * is[j] - vp1;
                    integral1 += h * G4W[j] * is[j];
                }
                if (conduction)
                {
                    double sum = 0.0, end = 0.0;
                    for (int j = 0; j < 4; ++j) { sum += G4W[j] * gs[j]; end += endWeight4[j] * gs[j]; }
                    gridIntegral1 += h * sum;
                    charge1 += chargeRise * h * sum;
                    lastIg1 = max(end, 0.0);
                }
                final double[] u = u4;
                for (int e = 0; e < 4; ++e)
                    u[e] = lagrange4[0][e] * y[0] + lagrange4[1][e] * y[1]
                         + lagrange4[2][e] * y[2] + lagrange4[3][e] * y[3];
                plate(a, h, u, 4);
            }
            // Exact exponential updates with the interval-average currents.
            // The cathode carries plate and grid current; the supply only
            // the plate current.
            vk1 = vk1 * cathodeDecay + (1.0 - cathodeDecay) * RK * (integral1 + gridIntegral1);
            if (stages > 1)
            {
                vk2 = vk2 * cathodeDecay + (1.0 - cathodeDecay) * RK * (integral2 + gridIntegral2);
                i2Prev = integral2;
                g2Prev = gridIntegral2;
            }
            if (conduction)
            {
                // Coupling capacitors: Cc dvc/dt = (x - vc) / Rg + Ig, exact for
                // the interval-average source x and grid current Ig.
                vc1 = vc1 * couplingDecay + (1.0 - couplingDecay) * (x1Avg + GridConduction.RG * gridIntegral1);
                if (stages > 1)
                {
                    vc2 = vc2 * couplingDecay + (1.0 - couplingDecay) * (x2Integral + GridConduction.RG * gridIntegral2);
                    x2Prev = x2Integral;
                    vcRamp2 = leakEnd2 + charge2;   // the reference where this interval ended
                }
            }
            ipLP = ipLP * sagDecay + (1.0 - sagDecay) * (integral1 + (stages > 1 ? integral2 : 0.0));
            i1Prev = integral1;
            g1Prev = gridIntegral1;
            final double[][] kernel = design.kernel;
            for (int k = 0; k < ORDER; ++k)
            {
                final double[] piece = kernel[k];
                double acc = 0.0;
                for (int p = 0; p < ORDER; ++p) acc += piece[p] * moments[p];
                out[k] += acc;
            }
        }
    }

    /** Full per-channel circuit: two stages, FMV tone stack and sag. */
    private static final class ChannelState
    {
        final double fs2;
        final TriodeStage stage1 = new TriodeStage(), stage2 = new TriodeStage();
        double ipLP = 1.6e-3;
        double outHpX = 0.0, outHpY = 0.0;
        /** Per stage count. */
        final BiquadFilter[][] flatten = new BiquadFilter[2][3];
        final CoreDesign design;
        final ContinuousCore core = new ContinuousCore();
        final FIRFilter compensation = new FIRFilter();
        /** Bassman stack (R-type WDF / analog form). */
        final ToneStackFMV fmv = new ToneStackFMV(38e3, 1e6);   // fixed source impedance
        private final ToneStackFMV.AnalogStateSpace stateSpace = new ToneStackFMV.AnalogStateSpace();
        /** Supply sag one-pole of the 1x path. */
        private final double sagAlpha;
        /** Output coupling high-pass coefficient. */
        private final double hpA;
        private final double[] kd = new double[2];
        private final double[] after = new double[3];

        ChannelState(double fs2In, CurrentTable table, CoreDesign designIn, GridTable gridIn)
        {
            design = designIn;
            stage1.table = table;
            stage2.table = table;
            stage1.grid = gridIn;
            stage2.grid = gridIn;
            core.grid = gridIn;
            stage1.fs2 = fs2In;
            stage2.fs2 = fs2In;
            fs2 = fs2In;
            sagAlpha = 1.0 - Math.exp(-1.0 / (0.07 * fs2));
            hpA = 1.0 - 2.0 * Math.PI * 8.0 / fs2;
            for (int set = 0; set < 2; ++set)
                for (int k = 0; k < 3; ++k)
                    flatten[set][k] = new BiquadFilter(1);
            fmv.prepare(fs2In);
            if (design != null)
            {
                core.init(fs2In, table, design);
                fmv.analogStateSpace(stateSpace);
                core.setTone(stateSpace);
                compensation.prepare(design.compensation.length, 1);
                compensation.setCoefficients(design.compensation);
            }
        }

        /** Settles the DC operating point consistently with the sagged
         *  supply: fixed point on B+ = BPLUS - sagR*(Ip1+Ip2) over the
         *  active stage count. */
        void reset(double sagR, int numStages)
        {
            double bp = BPLUS;
            double iTotal = 0.0;
            for (int it = 0; it < 8; ++it)
            {
                stage1.settleDC(bp);
                stage2.settleDC(bp);
                iTotal = stage1.ip + (numStages > 1 ? stage2.ip : 0.0);
                final double bpNew = BPLUS - sagR * iTotal;
                if (Math.abs(bpNew - bp) < 1e-9) break;
                bp = bpNew;
            }
            ipLP = iTotal;
            outHpX = outHpY = 0.0;
            fmv.reset();
            for (BiquadFilter[] set : flatten)
                for (BiquadFilter f : set)
                    f.reset();
            if (design != null)
            {
                core.reset(sagR, numStages);
                compensation.reset();
            }
        }

        /** Forks the signal-dependent state when a dormant path is requested:
         *  bias-relative state is translated to the new topology's DC
         *  solution, and the output history (of another plate, polarity and
         *  EQ) is primed while the previous path remains fully audible. */
        void resumeFrom(ChannelState source, int numStages, double sagR)
        {
            // The bypass capacitor already measures mean plate current.
            // Invert Koren at that operating point, then solve the requested
            // load with the same effective grid bias.
            final double current1 = (design != null ? source.core.vk1 : source.stage1.vk) / RK;
            final double current2 = numStages == 1
                ? (design != null ? source.core.vk2 : source.stage2.vk) / RK : 0.0;
            final double supply = BPLUS - sagR * (current1 + current2);
            final double plate = max(1.0, supply - (RL + RK) * current1);
            final double grid = RK * current1
                + CurrentTable.gridForCurrent(plate, max(current1, 1e-18));
            ContinuousCore.dcBias(sagR, numStages, grid, kd, after);
            final double di = after[1] - current1;
            final double dTotal = after[1] + (numStages == 2 ? after[2] : 0.0) - current1 - current2;
            final double dPlate = after[0] - supply - RL * di;
            final double reference = after[0] - RL * after[1];
            final double previousReference = design != null ? source.core.vp1 : source.stage1.vpDC;
            final double inputOffset = previousReference + dPlate - reference;
            stage1.copyFrom(source.stage1);
            stage1.vk += RK * di;
            stage1.ip += di;
            stage1.vpDC = reference;
            stage2.ip = after[2];
            stage2.vk = RK * after[2];
            stage2.vpDC = after[0] - RL * after[2];
            stage2.fPrev = 0.0;
            stage2.ig = stage2.vc = stage2.icPrev = 0.0;
            ipLP = source.ipLP + dTotal;
            if (design == null) fmv.copyStateFrom(source.fmv, inputOffset);
            core.copyFrom(source.core);
            core.vk1 += RK * di;
            core.i1Prev += di;
            core.vp1 = reference;
            core.vc2 = core.x2Prev = core.g2Prev = core.vcRamp2 = 0.0;
            if (design != null)
                for (int mode = 0; mode < 3; ++mode)
                    for (int capacitor = 0; capacitor < 3; ++capacitor)
                        core.q[mode] += core.modes.toModal[mode][capacitor] * inputOffset;
            // A common DC shift of the three coupling-capacitor voltages
            // exactly cancels the changed stage-1 voltage reference.
            core.ipLP += dTotal;
            core.vk2 = stage2.vk;
            core.vp2 = stage2.vpDC;
            core.i2Prev = stage2.ip;
            Arrays.fill(core.out, 0.0);
            outHpX = outHpY = 0.0;
            for (BiquadFilter filter : flatten[numStages - 1])
                filter.reset();
            if (design != null) compensation.reset();
        }

        /** Installs the reference-flattening EQ for one stage count. */
        void setFlattenCoeffs(int stageCount, BiquadCoeffs[] c)
        {
            final BiquadFilter[] set = flatten[stageCount - 1];
            for (int k = 0; k < 3; ++k)
                set[k].setCoeffs(c[k]);
        }

        void setToneControls(double t, double b, double m)
        {
            fmv.setControls(t, b, m);   // rebuilds the R-type scattering
            if (design != null)
            {
                fmv.analogStateSpace(stateSpace);
                core.setTone(stateSpace);
            }
        }

        /** Total plate current behind the sag meter. */
        double supplyCurrent() { return design != null ? core.ipLP : ipLP; }

        double processSample(double vgIn, int numStages, double sagR, boolean compensate)
        {
            double v;
            if (design != null)
                v = core.process(vgIn, numStages, sagR);
            else
            {
                // Supply sag: B+ droops with smoothed total plate current.
                final double iTotal = stage1.ip + (numStages > 1 ? stage2.ip : 0.0);
                ipLP += sagAlpha * (iTotal - ipLP);
                final double bplusEff = BPLUS - sagR * ipLP;
                // Stage 1 -> FMV tone stack -> (stage 2).
                v = stage1.processSample(vgIn, bplusEff);
                v = fmv.processSample(v);
                if (numStages > 1)
                    v = stage2.processSample(v * INTERSTAGE, bplusEff);
            }

            // Output coupling high-pass (about 8 Hz, removes residual sag drift).
            final double y = hpA * (outHpY + v - outHpX);
            outHpX = v;
            outHpY = y;
            // Restore absolute polarity for single-stage use.
            double out = (numStages > 1) ? y : -y;

            // Reference-flattening EQ: undoes the FMV stack's fixed envelope
            // at the neutral tone setting, so neutral knobs sound neutral and
            // the tone controls act relative to flat. This linear stage adds
            // no harmonics.
            final BiquadFilter[] fl = flatten[numStages > 1 ? 1 : 0];
            out = fl[0].processSample(out, 0);
            out = fl[1].processSample(out, 0);
            out = fl[2].processSample(out, 0);
            if (!compensate) return out;
            if (design == null) return out;
            return compensation.processSample(out, 0);
        }

        void compensateBlock(double[] data, int count)
        {
            compensation.processBlock(data, 0, count);
        }
    }

    /** Applies parameter changes: stack controls, sag resistance, calibration. */
    private void recompute()
    {
        final double drive = Math.pow(10.0, driveDb / 20.0);
        final double t = treble;
        final double b = bass;
        final double m = middle;
        final double sagNow = sag;
        final int requestedStages = stages;

        hScale = drive;                       // 0 dBFS -> 1 V grid at drive 0
        // A class-A preamp draws near-constant average current, so supply sag
        // shifts the operating point rather than pumping: the sag control
        // changes voicing, not loudness.
        sagR = sagNow * 40e3;
        for (ChannelState[] lane : channels)
            for (ChannelState ch : lane)
                ch.setToneControls(t, b, m);

        if (!gainsInitialized)
        {
            // A pre-stream edit selects the initial circuit, without a fade
            // or a supply transient from the other topology's DC point.
            for (ChannelState ch : channels[requestedStages - 1])
                ch.reset(sagR, requestedStages);
            stageBlend.reset((double) (requestedStages - 1));
            bothStagesActive = false;
            stageWarmupRemaining = 0;
        }
        else if (requestedStages != numStagesActive)
        {
            if (!bothStagesActive)
            {
                final ChannelState[] incoming = channels[requestedStages - 1];
                final ChannelState[] outgoing = channels[numStagesActive - 1];
                for (int ch = 0; ch < incoming.length; ++ch)
                    incoming[ch].resumeFrom(outgoing[ch], requestedStages, sagR);
                stageWarmupRemaining = Math.max(1, (int) Math.ceil(0.005 * fs2));
                bothStagesActive = true;
            }
            stageBlend.setTargetValue((double) (requestedStages - 1));
            if (!stageBlend.isSmoothing())
            {
                bothStagesActive = false;
                stageWarmupRemaining = 0;
            }
        }
        numStagesActive = requestedStages;

        // Loudness: divide out the circuit's program gain measured at this
        // drive (prepare-time sweep, log-interpolated) and keep a residual
        // +0.25 dB/dB slope (drive^0.25), so the knob stays alive below 0 dB
        // and high drive holds level while the density grows. The tone knobs
        // stay fully audible: their deviation from the 0.5/0.5/0.5 reference
        // is part of the tone, not the level.
        final double driveDbNow = driveDb;
        for (int st = 1; st <= 2; ++st)
            mScale[st - 1] = Math.pow(drive, 0.25)
                / max(programGainAt(driveDbNow, st), 1e-9);
    }

    /** Program gain at a drive setting (log-interpolated prepare-time table). */
    private double programGainAt(double driveDbNow, int stageCount)
    {
        final double[] lut = gProgLut[stageCount - 1];
        // Ordered min/max instead of clamp: a NaN input resolves to 0 here.
        final double pos = min(max(0.0, (driveDbNow - DRIVE_LUT_MIN_DB) / DRIVE_LUT_STEP_DB),
                               (double) (DRIVE_LUT_N - 1));
        final int idx = Math.min((int) pos, DRIVE_LUT_N - 2);
        final double frac = pos - (double) idx;
        final double a = max(lut[idx], 1e-9);
        final double b = max(lut[idx + 1], 1e-9);
        return a * Math.pow(b / a, frac);   // gains are smooth in dB
    }

    /**
     * Measures the per-stage-count reference response of a settled channel
     * at the reference tone (0.5/0.5/0.5), reference sag voicing and drive
     * 0 dB, using a pink-weighted multitone (one tone per octave,
     * 100..6400 Hz) at program level (about -15 dBFS RMS). From the per-tone
     * gains it designs a 3-section flattening EQ (low shelf / mid peak / high
     * shelf) that removes the FMV stack's fixed envelope, then sweeps the
     * drive grid through a chained scratch channel (flattener installed) to
     * fill the program gain table that recompute() interpolates.
     */
    private void calibrateReference()
    {
        final double sagRef = 0.3 * 40e3;
        final int settle = (int) (0.060 * fs2);
        final int meas = (int) (0.050 * fs2);
        final double ampPerTone = 0.095 / 2.6457513;

        final double[] gs1 = new double[7], gs2 = new double[7], gc = new double[7];
        final double[] gainDb = new double[7];
        for (int st = 1; st <= 2; ++st)
        {
            final ChannelState cal = new ChannelState(fs2, loadTable, design, gridTable);
            cal.setToneControls(0.5, 0.5, 0.5);
            cal.reset(sagRef, st);

            // Per-tone Goertzel over the measured tail.
            Arrays.fill(gs1, 0.0);
            Arrays.fill(gs2, 0.0);
            for (int k = 0; k < 7; ++k)
                gc[k] = 2.0 * Math.cos(2.0 * Math.PI * REF_TONES[k] / fs2);
            for (int i = 0; i < settle + meas; ++i)
            {
                double x = 0.0;
                for (int k = 0; k < 7; ++k)
                    x += Math.sin(2.0 * Math.PI * REF_TONES[k] * i / fs2 + k * 1.7);
                x *= ampPerTone;
                final double y = cal.processSample(x, st, sagRef, true);
                if (i >= settle)
                    for (int k = 0; k < 7; ++k)
                    {
                        final double s0 = y + gc[k] * gs1[k] - gs2[k];
                        gs2[k] = gs1[k];
                        gs1[k] = s0;
                    }
            }
            for (int k = 0; k < 7; ++k)
            {
                final double c = gc[k] * 0.5;
                final double s1 = gs1[k], s2 = gs2[k];
                final double mag = Math.sqrt(max(s1 * s1 + s2 * s2 - 2.0 * c * s1 * s2, 0.0))
                                 * 2.0 / meas;
                gainDb[k] = 20.0 * Math.log10(max(mag / ampPerTone, 1e-9));
            }

            // Flattening EQ from the band means, relative to the overall mean.
            double mean = 0.0;
            for (double g : gainDb) mean += g / 7.0;
            final double lo = 0.5 * (gainDb[0] + gainDb[1]) - mean;
            final double mid = (gainDb[2] + gainDb[3] + gainDb[4]) / 3.0 - mean;
            final double hi = 0.5 * (gainDb[5] + gainDb[6]) - mean;

            final BiquadCoeffs[] fc = {
                BiquadDesign.makeLowShelf(fs2, 180.0, -lo),
                BiquadDesign.makePeak(fs2, 800.0, 0.55, -mid),
                BiquadDesign.makeHighShelf(fs2, 4500.0, -hi)
            };
            for (ChannelState[] lane : channels)
                for (ChannelState ch : lane)
                    ch.setFlattenCoeffs(st, fc);

            // Program gain against drive, on a chained scratch channel with
            // the flattener installed (the chain as it really sounds). Each
            // grid point gets a re-settle before its measurement window.
            final ChannelState sweep = new ChannelState(fs2, loadTable, design, gridTable);
            sweep.setToneControls(0.5, 0.5, 0.5);
            sweep.setFlattenCoeffs(st, fc);
            sweep.reset(sagRef, st);
            final int settle2 = (int) (0.030 * fs2);
            final int meas2 = (int) (0.030 * fs2);
            int t = 0;   // continuous tone phase across the whole sweep
            for (int kd = 0; kd < DRIVE_LUT_N; ++kd)
            {
                final double d = Math.pow(10.0, (DRIVE_LUT_MIN_DB + kd * DRIVE_LUT_STEP_DB) / 20.0);
                double inSq = 0.0, outSq = 0.0;
                for (int i = 0; i < settle2 + meas2; ++i, ++t)
                {
                    double x = 0.0;
                    for (int k = 0; k < 7; ++k)
                        x += Math.sin(2.0 * Math.PI * REF_TONES[k] * t / fs2 + k * 1.7);
                    x *= ampPerTone;
                    final double y = sweep.processSample(d * x, st, sagRef, true);
                    if (i >= settle2)
                    {
                        inSq += x * x;
                        outSq += y * y;
                    }
                }
                gProgLut[st - 1][kd] = (outSq > 0.0 && inSq > 0.0) ? Math.sqrt(outSq / inSq) : 1.0;
            }
        }
    }
}
