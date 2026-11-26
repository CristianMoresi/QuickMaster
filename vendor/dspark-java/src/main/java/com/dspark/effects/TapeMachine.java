package com.dspark.effects;

import com.dspark.core.AnalogSection;
import com.dspark.core.BiquadCoeffs;
import com.dspark.core.BiquadDesign;
import com.dspark.core.DryPhase;
import com.dspark.core.DspMath;
import com.dspark.core.FilterDesign;
import com.dspark.core.Hysteresis;
import com.dspark.core.NormalizedLadder;
import com.dspark.core.Oversampling;
import com.dspark.core.OversamplingDryPath;
import com.dspark.core.TapeTransport;

import java.util.Arrays;

/**
 * Physical tape machine: Jiles-Atherton hysteresis, EQ standards, losses and
 * transport.
 * <p>
 * A complete record/playback chain built around the Jiles-Atherton
 * hysteresis core ({@link Hysteresis}), modelling the things that make tape
 * sound like tape:
 * <ol>
 * <li><b>Record/playback equalization</b> (NAB or CCIR, speed-dependent time
 *     constants): the signal is HF-emphasized (and LF-boosted for NAB) before
 *     hitting the "tape", and de-emphasized on playback with the exact
 *     digital inverse. The round trip is flat by construction, but the
 *     hysteresis sees the emphasized signal, so highs saturate first at slow
 *     speeds, exactly as on hardware.</li>
 * <li><b>Magnetic hysteresis with real AC bias</b> at 4x oversampling by
 *     default ({@link #setOversampling}): an ultrasonic carrier (a quarter of
 *     the internal rate, exact table) is summed with the signal into a
 *     push-pull pair of JA instances per channel (+carrier / -carrier, output
 *     averaged, like a centre-tapped record head). The carrier erases the
 *     loop's branch memory as hardware bias does, the push-pull cancels the
 *     carrier, its odd harmonics and the remanence DC, and the even folds die
 *     in the downsampler. The bias maps to the carrier amplitude: under-bias
 *     drops below the erase threshold (grit and level instability) and the
 *     top end rises, over-bias adds the self-erasure roll-off of short
 *     wavelengths from the optimum on. Gain is calibrated per setting through
 *     the same biased chain, so drive changes saturation, not level.</li>
 * <li><b>Playback loss effects</b> with the physical formulas (Kadis):
 *     spacing loss 54.6*d/lambda dB, gap loss sinc(pi*g/lambda), thickness
 *     loss (1-e^(-4*pi*delta/lambda))/(4*pi*delta/lambda), with lambda =
 *     speed/frequency, rendered into a linear-phase FIR per speed (63 taps at
 *     48 kHz, scaled with the rate so the response is the same at every
 *     rate), plus the head-bump resonance, at the speed over the length of
 *     the head's contact with the tape (22.5, 45, 90 and 180 Hz at 3.75, 7.5,
 *     15 and 30 ips with the half-track head).</li>
 * <li><b>Wow and flutter</b>: one shared transport modulation (slow drift
 *     random walk + 0.55 Hz wow + 8.3/23 Hz flutter + scrape band) driving a
 *     fractional delay, identical on all channels, like a real capstan. The
 *     hiss generator uses its own random stream, so enabling noise never
 *     changes the transport's realisation.</li>
 * <li><b>Tape hiss</b> (optional, default off).</li>
 * <li><b>Physical band edges</b>: the line input is AC coupled (two one-pole
 *     high-passes at 4 Hz), so DC and the slowest drift never lean the tape's
 *     loop to one side; a 2nd-order 24 Hz high-pass models the AC-coupled
 *     playback amplifier, and the loss FIR always applies the hard
 *     reproduce-gap cutoff above about 21.5 kHz.</li>
 * </ol>
 * The machine around the tape, all off or at the classic setting by default
 * (each one is described at its setter): the speed transport
 * ({@link Transport#SPEED}), hiss on the tape, mains hum after it, channel
 * bleed, even harmonics before the tape, an output transformer, the Contour
 * head model, the Circuit electronics, the alignment trims, the record
 * emphasis trim, the formulation, the track format, the calibration level
 * and the monitored head.
 * <p>
 * The dry path of the mix control is delay-compensated to
 * {@link #getLatency} and, between the two ends of the mix, takes the phase
 * (not the magnitude) of the machine's high-passes, so a partial mix keeps
 * its bottom. The mix ramps at no more than full scale per 20 ms. Channels
 * beyond the prepared count pass through untouched.
 * <p>
 * Parameter setters take effect at the next {@link #process} call (non-finite
 * values are ignored). A parameter change set after {@link #prepare}
 * re-calibrates the drive makeup on a scratch push-pull pair, spread over the
 * following blocks (one step of calibration per step of the core, about 24 ms
 * of audio); the calibrated (drive, makeup, bias) set is adopted when its
 * calibration completes and reached through a geometric ramp of about 30 ms.
 * Parameters set before {@link #prepare} are calibrated there: the first
 * sample already runs at the calibrated set. Blocks longer than the prepared
 * maximum are processed in pieces. Not thread-safe: the owner calls every
 * method from its own processing thread.
 * <p>
 * <b>Oversampling.</b> {@link #setOversampling} selects the internal factor
 * in {1, 2, 4, 8, 16}; the default is 4. The AC-bias carrier is always a
 * quarter of the internal rate. Factor 1 means no oversampler. With four
 * carrier levels the core solves each sample in two steps from 44.1 to
 * under 352.8 kHz of internal rate. {@link #getLatency} always reflects the
 * active factor (oversampler group delay + loss FIR + transport delay).
 */
public final class TapeMachine
{
    /** Playback equalization standard. */
    public enum Standard
    {
        NAB,
        CCIR
    }

    /** Tape speed (3.75 ips comes last, so stored states keep their meaning). */
    public enum Speed
    {
        IPS_7_5,
        IPS_15,
        IPS_30,
        IPS_3_75
    }

    /**
     * How the transport moves the tape ({@link #setTransport}).
     * <p>
     * MODULATED, the default, reads the tape through a delay moved by a sum of
     * drift, wow, flutter and scrape. SPEED keeps the speed of the tape as a
     * state: the reproduce pitch is the speed of the moment, whose deviation
     * is a smoothed noise, the capstan's rotation (with a third of its second
     * harmonic) and the reel's rotation, at rates that follow the speed (5 Hz
     * and 0.6 Hz at 15 ips) and deeper at slower speeds (by the square root of
     * the ratio); the delay is held at the head distance over the speed, and a
     * change of {@link #setSpeed} glides the speed with inertia (0.45 s to
     * speed up, 0.7 s to slow down) instead of jumping.
     */
    public enum Transport
    {
        MODULATED,
        SPEED
    }

    /** Mains frequency of the hum ({@link #setHum}). */
    public enum Mains
    {
        HZ_50,
        HZ_60
    }

    /**
     * Tape formulation ({@link #setFormulation}).
     * <p>
     * CLASSIC is the default material. HIGH_OUTPUT saturates 3 dB later, takes
     * 20 % more coercive force from the bias and hisses 1.5 dB less; VINTAGE
     * saturates 2 dB earlier with a more reversible loop, takes 20 % less
     * coercive force and hisses 3 dB more. The bias control keeps its meaning:
     * 0.5 is the optimum of the material in use.
     */
    public enum Formulation
    {
        CLASSIC,
        HIGH_OUTPUT,
        VINTAGE
    }

    /**
     * Track format ({@link #setTrackFormat}): the signal grows with the track
     * width and the hiss with its square root, so HALF (2.0 mm, the default)
     * keeps the hiss as set, FULL (6.3 mm) lowers it by 5.0 dB and QUARTER
     * (1.0 mm) raises it by 3.0 dB. Each format has its head, and the head
     * bump follows the length of its contact with the tape: at 15 ips it sits
     * at 90 Hz with HALF, 72 Hz with FULL and 112 Hz with QUARTER.
     */
    public enum TrackFormat
    {
        HALF,
        FULL,
        QUARTER
    }

    /**
     * What the output monitors ({@link #setMonitor}). PLAY_HEAD, the default,
     * is the reproduce head; RECORD_HEAD reads through the record head, whose
     * wider gap (6 um against 3) and spacing lose more of the top end; SOURCE
     * is the input itself, delayed like the tape path. Changes fade over 20 ms.
     */
    public enum Monitor
    {
        PLAY_HEAD,
        RECORD_HEAD,
        SOURCE
    }

    /**
     * The reproduce head's low end ({@link #setHeadModel}). BUMP, the default,
     * is the single head-bump resonance; CONTOUR is a chain of four
     * resonances, the bump and three ripples above it at 1.45, 1.95 and 2.5
     * times its frequency with alternating signs and falling size, each
     * designed on its own analog prototype.
     */
    public enum HeadModel
    {
        BUMP,
        CONTOUR
    }

    /**
     * Record and reproduce electronics ({@link #setElectronics}).
     * <p>
     * IDEAL, the default, runs the record emphasis at the session rate and
     * its inverse after the tape. CIRCUIT runs both networks from their
     * analog transfer functions at the internal rate, around the tape, as
     * normalized ladders: the record network (the emphasis and a second-order
     * record amplifier limit at 45 kHz) and the reproduce network (the
     * de-emphasis with the trims of {@link #setAlignment} and a first-order
     * amplifier pole at 50 kHz). The round trip then carries the amplifiers'
     * gentle top-end roll-off. The networks it brings in start from rest.
     */
    public enum Electronics
    {
        IDEAL,
        CIRCUIT
    }

    /** The levels of the bias carrier ({@link #setBiasShape}). */
    public enum BiasShape
    {
        /** Every sample at plus or minus the amplitude: the gain rises with the level before it saturates. */
        TWO_LEVEL,
        /** An inner and an outer level of each sign: the gain only falls with the level (default). */
        FOUR_LEVEL
    }

    /** How the oversampler filters ({@link #setOversamplingMode}). */
    public enum OversamplingMode
    {
        /** Linear-phase half-bands: the latency of the factor, the dry path a delay (default). */
        LINEAR_PHASE,
        /** Elliptic allpass half-bands: a few samples of latency, the dry path through the same filters. */
        MINIMUM_PHASE
    }

    // -- Constants -----------------------------------------------------------------

    private static final int FIR_LEN_AT_48K = 63;
    private static final double GAIN_RAMP_SECONDS = 0.03;
    private static final double UNIFORM_TO_UNIT_RMS = 1.7320508075688772;    // sqrt(3)
    private static final int CONTOUR_SECTIONS = 4;
    private static final double CONTOUR_DB = 3.0;                // peak of the contour's bump at full head bump
    private static final double ASYMMETRY_SHARE = 0.15;          // even term at full asymmetry
    private static final double TRANSFORMER_CORNER_HZ = 60.0;
    private static final double INPUT_COUPLING_HZ = 4.0;         // corner of the AC-coupled input
    private static final double OUTPUT_COUPLING_HZ = 24.0;       // corner of the AC-coupled playback amplifier
    private static final double IRON_CORNER_HZ = 20.0;           // corner of the iron's low end at the full amount
    private static final double UNDER_BIAS_LIFT_DB = 4.5;        // top-end lift with no bias at all
    private static final double UNDER_BIAS_CENTRE_HZ = 6000.0;   // its centre at 15 ips
    private static final double EMPHASIS_TRIM_HZ = 4000.0;       // centre of the record emphasis trim
    private static final double BUMP_HZ_PER_IPS = 6.0;           // head bump of the half-track head: 90 Hz at 15 ips
    private static final double TRANSFORMER_EVEN = 0.03;         // terms of the transformer's curve
    private static final double TRANSFORMER_ODD = 0.2;
    private static final double HUM_NORM = 1.2649110640673518;   // 1 / RMS of sin + sin(2x) / 2
    private static final double DRIVE_LINK = 0.25;               // level left to the drive knob, in dB a dB
    private static final double FOUR_LEVEL_DRIVE_LINK = 0.23;
    private static final int STEP_DELAY = 4;                     // of the two half-bands, in internal samples
    private static final double EXACT_TOLERANCE = 1e-9;          // where the solver stops
    private static final double FOUR_LEVEL_TOLERANCE = 1e-6;
    private static final double FOUR_LEVEL_INTERNAL_RATE = 352800.0;
    private static final double INNER_LEVEL = 0.2;               // of the bias amplitude, inner level up to the optimum
    private static final double OUTER_LEVEL = 0.6;
    private static final double AUTOMATIC_INTERNAL_RATE = 176400.0;   // rate the automatic factor reaches
    private static final int CARRIER_PERIOD = 4;                 // core samples the carrier repeats over
    private static final int TRANSPORT_CONTROL = 16;             // frames between the modulated transport's control points
    private static final double HI_CAP = 4.0;                    // +12 dB emphasis cap (practical alignment)
    private static final double LO_BOOST = 2.0;                  // +6 dB NAB LF record boost
    private static final double[] TRACK_WIDTH_MM = { 2.0, 6.3, 1.0 };
    private static final double[] CONTACT_RATIO = { 1.0, 0.8, 1.25 };    // half, full and quarter track
    private static final double[] CONTOUR_RATIO = { 1.0, 1.45, 1.95, 2.5 };
    private static final double[] CONTOUR_SHARE = { 1.0, -0.5, 0.28, -0.15 };
    private static final double[] CONTOUR_Q = { 0.9, 2.0, 2.5, 3.0 };

    /**
     * AC bias carrier at a quarter of the internal rate, sampled 45 degrees off
     * its zero crossings: sqrt(2) * sin(pi * k / 2 + pi / 4), so every sample
     * sits at the carrier amplitude; two periods per 8-phase cycle, shared by
     * all channels like a machine's single bias oscillator.
     */
    private static final double[] BIAS_TABLE = { 1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, -1.0 };

    // The channels' alignment (setAlignmentTolerance()).
    private static final int MAX_ALIGNED = 16;
    private static final double ALIGN_BIAS = 0.08;
    private static final double ALIGN_SHELF_DB = 0.75;
    private static final double ALIGN_SHELF_HZ = 6000.0;
    private static final double ALIGN_LEVEL_DB = 0.25;

    // The record level's peak (getRecordPeak()).
    private static final int MAX_RECORD_PEAKS = 16;
    private static final double RECORD_PEAK_SECONDS = 0.3;
    private static final double RECORD_FULL_SCALE = 1.2 * 2.2e4;   // the field of a full-scale signal at nominal drive

    /** JA parameters and hiss offset of a formulation. */
    private static final class FormulationParams
    {
        final double ms, a, alpha, k, c, hissDb;

        FormulationParams(double ms, double a, double alpha, double k, double c, double hissDb)
        {
            this.ms = ms;
            this.a = a;
            this.alpha = alpha;
            this.k = k;
            this.c = c;
            this.hissDb = hissDb;
        }
    }

    private static final FormulationParams FORM_CLASSIC =
        new FormulationParams(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17, 0.0);
    private static final FormulationParams FORM_HIGH_OUTPUT =
        new FormulationParams(3.5e5 * 1.4125375446227544, 2.2e4 * 1.4125375446227544, 1.6e-3, 2.7e4 * 1.2, 0.17, -1.5);
    private static final FormulationParams FORM_VINTAGE =
        new FormulationParams(3.5e5 * 0.7943282347242815, 2.2e4 * 0.7943282347242815, 1.6e-3, 2.7e4 * 0.8, 0.25, 3.0);

    // -- Building blocks -----------------------------------------------------------

    /** One-pole/one-zero section with its exact inverse (double state). */
    private static final class Shelf
    {
        double b0 = 1.0, b1 = 0.0, a1 = 0.0;
        double x1 = 0.0, y1 = 0.0;

        void clear()
        {
            x1 = 0.0;
            y1 = 0.0;
        }

        /** Takes the coefficients of another section, keeping this one's state. */
        void setCoeffs(Shelf o)
        {
            b0 = o.b0;
            b1 = o.b1;
            a1 = o.a1;
        }

        /** The identity, keeping the state. */
        void setIdentity()
        {
            b0 = 1.0;
            b1 = 0.0;
            a1 = 0.0;
        }

        double process(double x)
        {
            final double y = b0 * x + b1 * x1 - a1 * y1;
            x1 = x;
            y1 = y;
            return y;
        }
    }

    /** Minimal biquad in double (head bump, output high-pass), transposed direct form II. */
    private static final class Peak
    {
        double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
        double z1 = 0.0, z2 = 0.0;

        void clear()
        {
            z1 = 0.0;
            z2 = 0.0;
        }

        /** Takes a biquad's coefficients, keeping the state. */
        void setCoeffs(BiquadCoeffs c)
        {
            b0 = c.b0;
            b1 = c.b1;
            b2 = c.b2;
            a1 = c.a1;
            a2 = c.a2;
        }

        double process(double x)
        {
            final double in = x;
            final double y = b0 * in + z1;
            z1 = b1 * in - a1 * y + z2;
            z2 = b2 * in - a2 * y;
            return y;
        }
    }

    /** What the two half-bands of the steps remember. */
    private static final class Stepped
    {
        double in0, in1, in2;            // the last internal samples in
        double e0, e1, e2, e3, e4;       // the first step's output of the last samples
        double o0, o1, o2;               // the second step's

        void clear()
        {
            in0 = in1 = in2 = 0.0;
            e0 = e1 = e2 = e3 = e4 = 0.0;
            o0 = o1 = o2 = 0.0;
        }
    }

    /** A linear ramp towards a target, at a step fixed when it is aimed. */
    private static final class LinearRamp
    {
        double now, target, step;

        LinearRamp(double now, double target, double step)
        {
            this.now = now;
            this.target = target;
            this.step = step;
        }

        void aim(double to, double samples)
        {
            if (to == target) return;
            target = to;
            step = Math.abs(to - now) / stdMax(1.0, samples);
        }

        double next()
        {
            now = advance(now, target, step);
            return now;
        }

        boolean active()
        {
            return now != 0.0 || target != 0.0;
        }

        /** The value a ramp at {@code now} takes on its next step. */
        static double advance(double now, double target, double step)
        {
            if (now != target)
                return (now < target) ? stdMin(target, now + step) : stdMax(target, now - step);
            return now;
        }
    }

    // -- Configuration and parameters ------------------------------------------------

    private double specRate = 0.0;
    private int specChannels = 0;
    private int specBlock = 0;
    private double sampleRate = 48000.0;
    private int numChannels = 0;
    private int maxBlock = 0;
    private boolean prepared = false;
    private int latency = 0;
    private int drySize = 1;
    private int osRequested = 4;
    private boolean fourLevel = true;
    private int steps = 1;
    private double biasNominal = 3.0 * 2.2e4;
    private int osFactor = 4;
    private boolean osAutomatic = false;
    private OversamplingMode osMode = OversamplingMode.LINEAR_PHASE;

    private double driveDb = 0.0;
    private double bias = 0.5;
    private int speed = Speed.IPS_15.ordinal();
    private int standard = Standard.NAB.ordinal();
    private double loss = 0.5;
    private double headBumpAmt = 0.5;
    private double wowFlutter = 0.15;
    private double noiseDb = -200.0;
    private double mix = 1.0;
    private boolean dirty = true;
    private int transport = 0;
    private int mains = 0;
    private int headModel = 0;
    private int electronics = 0;
    private int formulation = 0;
    private int trackFormat = 0;
    private int monitor = 0;
    private double humDb = -200.0;
    private double channelBleedDb = -200.0;
    private double evenHarmonics = 0.0;
    private double outputIron = 0.0;
    private double alignHf = 0.0;
    private double alignLf = 0.0;
    private double calLevel = 0.0;
    private double recordEmphasis = 0.0;
    private double alignTolerance = 0.0;

    // -- Processing state --------------------------------------------------------------

    private Oversampling oversampler = null;
    private final OversamplingDryPath dryPath = new OversamplingDryPath();
    private double[][] dryScratch = new double[0][];
    private final DryPhase dryInputLead = new DryPhase();
    private final DryPhase dryOutputLead = new DryPhase();
    private final DryPhase dryIronLead = new DryPhase();
    private boolean dryThroughFilters = false;
    private int dryDelay = 0;

    private Hysteresis[] hysteresis = new Hysteresis[0];    // +carrier instances (push-pull pair A)
    private Hysteresis[] hysteresisN = new Hysteresis[0];   // -carrier instances (push-pull pair B)
    private final Hysteresis calib = new Hysteresis();      // scratch +carrier instance for makeup calibration
    private final Hysteresis calib2 = new Hysteresis();     // scratch -carrier instance for makeup calibration
    private Stepped[] stepped = new Stepped[0];

    private Shelf[] recordHF = new Shelf[0], recordLF = new Shelf[0], playHF = new Shelf[0], playLF = new Shelf[0];
    private Peak[] headBump = new Peak[0];
    private Peak[] outHp = new Peak[0];
    private double[] overBiasLp = new double[0];
    private double[] overBiasX1 = new double[0];
    private double[][] inputAc = new double[0][];
    private double inputAcPole = 0.0;
    private Shelf[] recTrim = new Shelf[0], playTrim = new Shelf[0];
    private double[] ironLp = new double[0];
    private double ironA = 0.0;
    private boolean resting = false;
    private int wakeHold = 0;

    private double[] firTaps = new double[0];
    private double[][] firState = new double[0][];
    private double[] cosTable = new double[0], designMags = new double[0], designTaps = new double[0];
    private int firLen = FIR_LEN_AT_48K;
    private int firCenter = (FIR_LEN_AT_48K - 1) / 2;
    private int firHalf = 64;
    private int firPos = 0;

    private double[][] delayRing = new double[0][];
    private int delayPos = 0;
    private int delayCenter = 96;
    private int delayMask = 255;
    private double driftA = 0.0, scrapeA1 = 0.0, scrapeA2 = 0.0;
    private double modFrom = 0.0, modTo = 0.0;
    private int modAt = 0;
    private double humCos = 1.0, humSin = 0.0, humRotCos = 1.0, humRotSin = 0.0, humStepFor = -1.0;

    private double[][] dryRing = new double[0][];
    private int dryPos = 0;

    private double hScale = 1.0, mScale = 1.0;   // calibrated targets
    private double alignFor = 0.0, alignHpA = 0.0;
    private boolean alignActive = false;
    private final double[] alignBias = new double[MAX_ALIGNED];
    private final double[] alignShelf = new double[MAX_ALIGNED];
    private final double[] alignLevel = new double[MAX_ALIGNED];
    private final double[] alignHpX = new double[MAX_ALIGNED];
    private final double[] alignHpY = new double[MAX_ALIGNED];

    private double[] recordHeld = new double[0];
    private double recordFall = 0.9999;
    private final double[] recordPeak = new double[MAX_RECORD_PEAKS];
    private double biasAmp = 3.0 * 2.2e4;        // carrier amplitude target in H units
    private double hSm = 1.0, mSm = 1.0, bSm = 3.0 * 2.2e4;   // ramped values
    private boolean overBiasOn = false;
    private boolean overBiasActive = false;      // overBiasOn as of the previous block
    private double obB0 = 1.0, obB1 = 0.0, obP = 0.0;

    // Calibration requests (the latest one and the one running).
    private double reqHScale = 1.0, reqBiasAmp = 3.0 * 2.2e4, reqBiasNominal = 3.0 * 2.2e4, reqCalAmp = 0.25, reqDrive = 1.0;
    private FormulationParams reqForm = FORM_CLASSIC;
    private double runHScale = 1.0, runBiasAmp = 3.0 * 2.2e4, runBiasNominal = 3.0 * 2.2e4, runCalAmp = 0.25, runDrive = 1.0;
    private FormulationParams runForm = FORM_CLASSIC;
    private boolean calActive = false, calRestart = false;
    private int calN = 0, calFrom = 0, calI = 0, calMeas = 0;
    private double calW = 0.0, calRe = 0.0, calIm = 0.0;
    private int biasPhase = 0;                   // shared carrier phase

    private double modPhaseWow = 0.0, modPhaseFlut = 0.0, modPhaseFlut2 = 0.0;
    private double driftState = 0.0, scrapeLp1 = 0.0, scrapeLp2 = 0.0;
    private int rng = 0x1357feed;                // transport modulation stream
    private int rngNoise = 0x2468beef;           // hiss stream (independent of transport)

    private double currentMix = 1.0;             // mix ramp state (exact landing)
    private double mixMaxStep = 1.0 / 960.0;     // mix ramp rate: full scale per 20 ms

    private boolean speedTransportActive = false, circuitActive = false, contourActive = false, hissNormDirty = true;
    private int formApplied = -1;
    private double speedIps = 0.0, lastMod = 0.0;
    private final TapeTransport tapeTransport = new TapeTransport();
    private double hissNorm = 1.0, firNorm = 1.0, xtNorm = 1.0;
    private double asymMeanA = 0.0, trMeanA = 0.0;
    private final LinearRamp hiss = new LinearRamp(0.0, 0.0, 0.0);
    private final LinearRamp hum = new LinearRamp(0.0, 0.0, 0.0);
    private final LinearRamp xt = new LinearRamp(0.0, 0.0, 0.0);
    private final LinearRamp tr = new LinearRamp(0.0, 0.0, 0.0);
    private final LinearRamp asym = new LinearRamp(0.0, 0.0, 0.0);
    private final LinearRamp monitorRamp = new LinearRamp(1.0, 1.0, 0.0);
    private double[] wet = new double[0], asymMean = new double[0], trMean = new double[0];
    private Shelf[] hissHF = new Shelf[0], hissLF = new Shelf[0], xtHp = new Shelf[0], trPre = new Shelf[0], trPost = new Shelf[0];
    private Peak[][] headContour = new Peak[0][];
    private NormalizedLadder[][] recNet = new NormalizedLadder[0][], playNet = new NormalizedLadder[0][];

    // Design scratch (no allocation when the linear stages are rebuilt).
    private final Shelf scratchUp = new Shelf();
    private final Shelf scratchDown = new Shelf();
    private final Shelf scratchShelf = new Shelf();
    private FilterDesign.Band band = new FilterDesign.Band();

    // -- Lifecycle ---------------------------------------------------------------------

    /**
     * Allocates the whole chain. Invalid specs (non-positive or non-finite
     * rate, block size or channel count) are ignored: the previous state is
     * kept and an unprepared instance stays pass-through.
     * <p>
     * A prepared machine asked for another block size alone (same rate and
     * channels) keeps its memory: the tape, the filters and what is still
     * inside its delays go on as if nothing had been asked, since
     * {@link #process} splits any block to the size it was built for. Any
     * other call starts from rest. Parameters set before this call are
     * calibrated here, so the first sample processed runs at the calibrated
     * drive, makeup and bias.
     *
     * @param sampleRate   sample rate in Hz
     * @param numChannels  channel count
     * @param maxBlockSize largest block size
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize)
    {
        if (!(sampleRate > 0.0 && sampleRate <= Double.MAX_VALUE && maxBlockSize > 0 && numChannels > 0)) return;
        if (prepared && sampleRate == specRate && numChannels == specChannels && maxBlockSize != specBlock)
        {
            specBlock = maxBlockSize;
            return;
        }
        prepared = false;
        specRate = sampleRate;
        specChannels = numChannels;
        specBlock = maxBlockSize;
        this.sampleRate = sampleRate;
        mixMaxStep = 1.0 / stdMax(1.0, this.sampleRate * 0.02);
        this.numChannels = numChannels;
        maxBlock = Math.max(maxBlockSize, 1);
        if (osAutomatic)
            osRequested = Oversampling.factorForRate(sampleRate, AUTOMATIC_INTERNAL_RATE);
        osFactor = osRequested;
        steps = stepsInUse(sampleRate);

        // The hysteresis core runs at the active oversampling factor so the
        // quarter-internal-rate AC bias carrier and its sidebands stay clear of
        // the audio band. With four levels it takes two steps a sample from
        // 44.1 to under 352.8 kHz.
        final double osRate = this.sampleRate * (double) osFactor;
        final double coreRate = osRate * (double) steps;
        final double tolerance = fourLevel ? FOUR_LEVEL_TOLERANCE : EXACT_TOLERANCE;
        final int channels = numChannels;
        stepped = new Stepped[channels];
        for (int ch = 0; ch < channels; ++ch) stepped[ch] = new Stepped();
        calib.setTolerance(tolerance);
        calib2.setTolerance(tolerance);
        // With four levels the solver starts from the carrier's own period;
        // two levels keep the exact solver.
        final int period = fourLevel ? CARRIER_PERIOD : 0;
        calib.setPredictorPeriod(period);
        calib2.setPredictorPeriod(period);
        if (osFactor > 1)
        {
            oversampler = new Oversampling(osFactor, Oversampling.Quality.HIGH,
                osMode == OversamplingMode.MINIMUM_PHASE ? Oversampling.Mode.POLYPHASE_IIR
                                                         : Oversampling.Mode.LINEAR_PHASE_FIR);
            oversampler.prepare(sampleRate, numChannels, maxBlockSize);
        }
        else
        {
            oversampler = null;
        }

        // Push-pull pair per channel: +carrier and -carrier instances, output
        // averaged. Odd-in-bias terms (the carrier, its 3rd harmonic fold and
        // the loop's remanence DC) cancel exactly.
        hysteresis = new Hysteresis[channels];
        hysteresisN = new Hysteresis[channels];
        for (int ch = 0; ch < channels; ++ch)
        {
            final Hysteresis h = new Hysteresis();
            h.prepare(coreRate);
            h.setTolerance(tolerance);
            h.setPredictorPeriod(period);
            h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
            hysteresis[ch] = h;
        }
        for (int ch = 0; ch < channels; ++ch)
        {
            final Hysteresis h = new Hysteresis();
            h.prepare(coreRate);
            h.setTolerance(tolerance);
            h.setPredictorPeriod(period);
            h.setParameters(3.5e5, 2.2e4, 1.6e-3, 2.7e4, 0.17);
            hysteresisN[ch] = h;
        }

        recordHF = shelves(channels);
        recordLF = shelves(channels);
        playHF = shelves(channels);
        playLF = shelves(channels);
        headBump = peaks(channels);
        overBiasLp = new double[channels];
        inputAc = new double[channels][4];
        inputAcPole = Math.exp(-2.0 * Math.PI * INPUT_COUPLING_HZ / this.sampleRate);
        recTrim = shelves(channels);
        playTrim = shelves(channels);
        ironLp = new double[channels];
        ironA = 1.0 - Math.exp(-2.0 * Math.PI * IRON_CORNER_HZ / this.sampleRate);
        resting = false;
        wakeHold = 0;
        hissHF = shelves(channels);
        hissLF = shelves(channels);
        xtHp = shelves(channels);
        trPre = shelves(channels);
        trPost = shelves(channels);
        trMean = new double[channels];
        asymMean = new double[channels];
        recordHeld = new double[channels];
        recordFall = Math.exp(-1.0 / (RECORD_PEAK_SECONDS * this.sampleRate * (double) Math.max(1, osFactor)));
        wet = new double[channels];
        headContour = new Peak[channels][];
        for (int ch = 0; ch < channels; ++ch) headContour[ch] = peaks(CONTOUR_SECTIONS);
        recNet = new NormalizedLadder[channels][2];
        playNet = new NormalizedLadder[channels][2];
        for (int ch = 0; ch < channels; ++ch)
            for (int s = 0; s < 2; ++s)
            {
                recNet[ch][s] = new NormalizedLadder(1);
                playNet[ch][s] = new NormalizedLadder(1);
            }
        {
            // Channel bleed: a first-order high-pass at 10 kHz (its corner kept
            // in place), scaled to unity at 1 kHz. Transformer branch: a
            // one-pole low-pass at 60 Hz before the curve (flux follows
            // voltage over frequency) and its inverse, capped at 4 kHz, after.
            final double wc = 2.0 * this.sampleRate * Math.tan(Math.PI * stdMin(10000.0, 0.45 * this.sampleRate) / this.sampleRate);
            final Shelf hp = firstOrder(1.0, 0.0, 1.0, wc, this.sampleRate, new Shelf());
            for (final Shelf s : xtHp) s.setCoeffs(hp);
            xtNorm = 1.0 / stdMax(1e-9, shelfMagnitude(hp, 2.0 * Math.PI * 1000.0 / this.sampleRate));
            final double w1 = 2.0 * Math.PI * TRANSFORMER_CORNER_HZ;
            final double w2 = 2.0 * Math.PI * stdMin(4000.0, 0.45 * this.sampleRate);
            final Shelf pre = firstOrder(0.0, w1, 1.0, w1, this.sampleRate, new Shelf());
            final Shelf post = firstOrder(1.0 / w1, 1.0, 1.0 / w2, 1.0, this.sampleRate, new Shelf());
            for (final Shelf s : trPre) s.setCoeffs(pre);
            for (final Shelf s : trPost) s.setCoeffs(post);
        }

        // AC-coupled playback amplifier: fixed 2nd-order 24 Hz high-pass.
        outHp = peaks(channels);
        {
            final BiquadCoeffs hc = BiquadDesign.makeHighPass(this.sampleRate, OUTPUT_COUPLING_HZ, 0.707);
            for (final Peak h : outHp) h.setCoeffs(hc);
        }

        // Loss FIR: 63 taps at 48 kHz, scaled with the rate so the design grid
        // keeps the same frequency spacing.
        firLen = Math.max(FIR_LEN_AT_48K, 64 * (int) lround(this.sampleRate / 48000.0) - 1);
        firCenter = (firLen - 1) / 2;
        firHalf = 1;
        while (firHalf < firLen + 1) firHalf <<= 1;
        firState = new double[channels][2 * firHalf];
        firPos = 0;
        firTaps = new double[firLen];
        {
            final int grid = firLen + 1;
            cosTable = new double[grid];
            for (int m = 0; m < grid; ++m)
                cosTable[m] = Math.cos(2.0 * Math.PI * m / (double) grid);
            designMags = new double[grid / 2 + 1];
            designTaps = new double[firLen];
        }
        overBiasX1 = new double[channels];

        // Transport delay scales with the rate so the wow excursion always fits
        // under the centre. What the two steps of the core delay is taken off
        // it: the latency stays that of one step.
        delayCenter = Math.max(96, (int) (96.0 * this.sampleRate / 48000.0)) - (steps > 1 ? STEP_DELAY / osFactor : 0);
        int dsz = 1;
        // Room for the speed transport too: its delay is the head distance
        // over the speed, from a quarter to four times the centre.
        while (dsz < 5 * delayCenter + 16) dsz <<= 1;
        delayMask = dsz - 1;
        delayRing = new double[channels][dsz];
        delayPos = 0;

        final double dt = 1.0 / this.sampleRate;
        // The transport's noises run at the control rate (nextTransportMod()).
        final double dControl = (double) TRANSPORT_CONTROL * dt;
        driftA = Math.exp(-2.0 * Math.PI * 0.10 * dControl);
        scrapeA1 = Math.exp(-2.0 * Math.PI * 90.0 * dControl);
        scrapeA2 = Math.exp(-2.0 * Math.PI * 40.0 * dControl);
        // The speed transport: the head distance is the delay centre at the
        // nominal speed.
        tapeTransport.prepare(this.sampleRate, (double) delayCenter, speedIps > 0.0 ? speedIps : 15.0);
        tapeTransport.setLimits(3.0, (double) (delayMask - 4));
        asymMeanA = Math.exp(-2.0 * Math.PI * 10.0 * dt / (double) osFactor);
        trMeanA = Math.exp(-2.0 * Math.PI * 5.0 * dt);

        latency = (oversampler != null ? oversampler.getLatency() : 0) + firCenter + delayCenter
                + (steps > 1 ? STEP_DELAY / osFactor : 0);   // the steps' delay, taken off the transport's
        // Behind minimum-phase filters the dry copy crosses the same filters
        // and the ring adds the rest of the delay.
        dryThroughFilters = oversampler != null && osMode == OversamplingMode.MINIMUM_PHASE;
        if (dryThroughFilters) dryPath.prepare(sampleRate, numChannels, maxBlockSize, oversampler);
        dryScratch = new double[channels][maxBlock];
        dryDelay = latency - (dryThroughFilters ? oversampler.getLatency() : 0);
        drySize = 1;
        while (drySize < latency + maxBlock + 1) drySize <<= 1;
        dryRing = new double[channels][drySize];
        dryPos = 0;
        dryInputLead.prepare(this.sampleRate, channels);
        dryOutputLead.prepare(this.sampleRate, channels);
        dryIronLead.prepare(this.sampleRate, channels);
        dryInputLead.setCorner(INPUT_COUPLING_HZ);
        dryOutputLead.setCorner(DryPhase.BUTTERWORTH_SHARE * OUTPUT_COUPLING_HZ);
        band = new FilterDesign.Band(this.sampleRate);

        // The first calibration runs here; later parameter changes calibrate
        // incrementally inside process().
        dirty = false;
        formApplied = -1;
        circuitActive = false;
        speedTransportActive = false;
        speedIps = 0.0;
        recomputeLinear();
        calActive = false;
        calRestart = false;
        startCalibration();
        stepCalibration(calN);
        hSm = hScale;
        mSm = mScale;
        bSm = biasAmp;
        prepared = true;
        reset();
    }

    /** Clears all signal state (keeps parameters). */
    public void reset()
    {
        if (!prepared) return;
        clearMachine();
        for (final double[] d : dryRing) Arrays.fill(d, 0.0);
        dryPath.reset();
        resting = false;
        wakeHold = 0;
        tapeTransport.reset();
        lastMod = 0.0;
        aimRamps(true);
        firPos = 0;
        delayPos = 0;
        dryPos = 0;
        currentMix = mix;   // no fade-in on start
        aimDryPhase(currentMix);
        dryInputLead.reset();
        dryInputLead.land();
        dryOutputLead.reset();
        dryOutputLead.land();
        dryIronLead.reset();
        dryIronLead.land();
        biasPhase = 0;
        modPhaseWow = 0.0;
        modPhaseFlut = 0.0;
        modPhaseFlut2 = 0.0;
        driftState = 0.0;
        scrapeLp1 = scrapeLp2 = 0.0;
        modFrom = modTo = 0.0;
        modAt = 0;
        humCos = 1.0;
        humSin = 0.0;
        humStepFor = -1.0;
        rng = 0x1357feed;
        rngNoise = 0x2468beef;
        hSm = hScale;   // no fade-in on start; a running calibration continues
        mSm = mScale;
        bSm = biasAmp;
        Arrays.fill(recordHeld, 0.0);
        Arrays.fill(alignHpX, 0.0);
        Arrays.fill(alignHpY, 0.0);
        Arrays.fill(recordPeak, 0.0);
        if (oversampler != null) oversampler.reset();
    }

    // -- Parameters ------------------------------------------------------------------

    /**
     * Input drive in dB [-12, +24], default 0. Level-compensated at -12 dBFS,
     * with a quarter of a dB a dB left: more drive means more saturation at
     * roughly constant loudness there. Pushed hard the tape compresses as a
     * tape does, so what is quieter comes out louder than what is loud.
     * Non-finite values are ignored.
     */
    public void setDrive(double driveDb)
    {
        if (!Double.isFinite(driveDb)) return;
        this.driveDb = DspMath.clamp(driveDb, -12.0, 24.0);
        dirty = true;
    }

    /**
     * Bias setting [0, 1], default 0.5, the nominal calibration (carrier at 3x
     * the JA field constant: full branch-memory erasure, clean odd
     * saturation). Below nominal the carrier drops under the erase threshold:
     * growing distortion, level instability, and a top end that rises as the
     * bias falls (a shelf of up to 4.5 dB, around 6 kHz at 15 ips and in
     * proportion to the speed). Above nominal, self-erasure progressively
     * rolls off the top octave, from the optimum on and without a step.
     * Non-finite values are ignored.
     */
    public void setBias(double bias)
    {
        if (!Double.isFinite(bias)) return;
        this.bias = DspMath.clamp(bias, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Configures the internal oversampling of the biased hysteresis core. A
     * prepared instance is prepared again (it reallocates and re-calibrates
     * like {@link #prepare}); {@link #getLatency} reflects the new factor
     * after this call.
     *
     * @param factor power of two in {1, 2, 4, 8, 16}, default 4; 1 is off (no
     *               internal resampling and no oversampler latency; the
     *               carrier then lands at half the base rate). Other values
     *               are ignored. Turns the automatic factor off.
     */
    public void setOversampling(int factor)
    {
        if (factor < 1 || factor > 16 || (factor & (factor - 1)) != 0) return;
        if (factor == osRequested && !osAutomatic) return;
        osAutomatic = false;
        osRequested = factor;
        osFactor = factor;
        if (prepared)
            prepare(specRate, specChannels, specBlock);
    }

    /**
     * Lets the host rate choose the factor. With it on, every
     * {@link #prepare} takes the smallest factor that brings the core to an
     * internal rate of at least 176.4 kHz: 4x at 44.1 and 48 kHz, 2x at 88.2
     * and 96 kHz, none from 176.4 kHz up. Off by default; an explicit
     * {@link #setOversampling} turns it off. Turned on while prepared, the
     * instance is prepared again.
     *
     * @param on true to choose the factor from the rate
     */
    public void setAutomaticOversampling(boolean on)
    {
        if (on == osAutomatic) return;
        osAutomatic = on;
        if (on && prepared)
            prepare(specRate, specChannels, specBlock);
    }

    /** @return whether the factor follows the host rate */
    public boolean isOversamplingAutomatic() { return osAutomatic; }

    /**
     * Chooses the levels of the bias carrier (a prepared instance is prepared
     * again). FOUR_LEVEL, the default, holds the gain and then lets it fall,
     * at any bias, as a tape compresses; at 1x, 2x or 4x, from 44.1 to under
     * 352.8 kHz of internal rate, the core takes two steps a sample, at the
     * same latency. TWO_LEVEL is the earlier carrier, kept to compare: its
     * gain rises with the level before it saturates. Null is ignored.
     */
    public void setBiasShape(BiasShape shape)
    {
        if (shape == null) return;
        final boolean four = shape == BiasShape.FOUR_LEVEL;
        if (four == fourLevel) return;
        fourLevel = four;
        if (prepared)
            prepare(specRate, specChannels, specBlock);
    }

    /** @return the levels of the bias carrier */
    public BiasShape getBiasShape() { return fourLevel ? BiasShape.FOUR_LEVEL : BiasShape.TWO_LEVEL; }

    /**
     * Chooses the oversampler's filters (a prepared instance is prepared
     * again). MINIMUM_PHASE runs polyphase allpass half-bands: the
     * oversampler's share of the latency falls to a few samples, and the dry
     * copy of the mix crosses the same filters. Null is ignored.
     */
    public void setOversamplingMode(OversamplingMode mode)
    {
        if (mode == null || mode == osMode) return;
        osMode = mode;
        if (prepared)
            prepare(specRate, specChannels, specBlock);
    }

    /** @return the oversampler's filters */
    public OversamplingMode getOversamplingMode() { return osMode; }

    /** @return the active oversampling factor (1 = off) */
    public int getOversamplingFactor() { return osFactor; }

    /** Tape speed (changes EQ time constants, losses and head bump), default IPS_15. Null is ignored. */
    public void setSpeed(Speed s)
    {
        if (s == null) return;
        speed = s.ordinal();
        dirty = true;
    }

    /** Equalization standard (NAB adds the LF time constant), default NAB. Null is ignored. */
    public void setStandard(Standard s)
    {
        if (s == null) return;
        standard = s.ordinal();
        dirty = true;
    }

    /** Playback loss intensity [0, 1], default 0.5 (0 leaves only the gap cutoff). Non-finite values are ignored. */
    public void setLossEffects(double amount)
    {
        if (!Double.isFinite(amount)) return;
        loss = DspMath.clamp(amount, 0.0, 1.0);
        dirty = true;
    }

    /** Head-bump resonance intensity [0, 1], default 0.5 (about 2.5 dB at full). Non-finite values are ignored. */
    public void setHeadBump(double amount)
    {
        if (!Double.isFinite(amount)) return;
        headBumpAmt = DspMath.clamp(amount, 0.0, 1.0);
        dirty = true;
    }

    /** Wow and flutter depth [0, 1], default 0.15 (about 0.25 % peak pitch deviation at 1). Non-finite values are ignored. */
    public void setWowFlutter(double amount)
    {
        if (!Double.isFinite(amount)) return;
        wowFlutter = DspMath.clamp(amount, 0.0, 1.0);
    }

    /**
     * Tape hiss RMS level in dBFS at the output, clamped to [-200, -20];
     * values of -120 and below disable it (default -200). The hiss is laid on
     * the tape, before the reproduce head and the de-emphasis, so its colour
     * follows the speed and the standard; the level is that of the output for
     * the half-track format, the classic formulation and no calibration
     * offset, and glides over 50 ms. Non-finite values are ignored.
     */
    public void setNoise(double dbfs)
    {
        if (!Double.isFinite(dbfs)) return;
        noiseDb = DspMath.clamp(dbfs, -200.0, -20.0);
    }

    /** Dry/wet mix [0, 1], default 1; dry is latency-compensated. Non-finite values are ignored. */
    public void setMix(double mix)
    {
        if (!Double.isFinite(mix)) return;
        this.mix = DspMath.clamp(mix, 0.0, 1.0);
    }

    /** Transport model, default MODULATED. Null is ignored. */
    public void setTransport(Transport t)
    {
        if (t == null) return;
        transport = t.ordinal();
    }

    /** Mains hum at 50 Hz: {@link #setHum(double, Mains)}. */
    public void setHum(double dbfs)
    {
        setHum(dbfs, Mains.HZ_50);
    }

    /**
     * Mains hum after the tape: the mains frequency and its second harmonic
     * 6 dB down, in phase on every channel, its level gliding over 50 ms.
     * Non-finite levels are ignored.
     *
     * @param dbfs  RMS level in dBFS, clamped to [-200, -20]; -120 and below
     *              turn it off (default -200)
     * @param mains mains frequency (null keeps the one set)
     */
    public void setHum(double dbfs, Mains mains)
    {
        if (!Double.isFinite(dbfs)) return;
        humDb = DspMath.clamp(dbfs, -200.0, -20.0);
        if (mains != null) this.mains = mains.ordinal();
    }

    /**
     * Bleed between the channels of each pair (0 and 1, 2 and 3, and so on):
     * each takes the other through a first-order high-pass at 10 kHz (rising
     * 6 dB per octave below it), its level gliding over 50 ms. Non-finite
     * values are ignored.
     *
     * @param dB level at 1 kHz, clamped to [-200, -20]; -120 and below turn it
     *           off (default -200)
     */
    public void setChannelBleed(double dB)
    {
        if (Double.isFinite(dB)) channelBleedDb = DspMath.clamp(dB, -200.0, -20.0);
    }

    /**
     * Even harmonics of the record path, 0 to 1 (0, the default, is off): a
     * share of the square of the recorded signal (less its mean) is added
     * before the tape, so the second harmonic grows with the level; it glides
     * over 20 ms. Non-finite values are ignored.
     */
    public void setEvenHarmonics(double amount)
    {
        if (Double.isFinite(amount)) evenHarmonics = DspMath.clamp(amount, 0.0, 1.0);
    }

    /**
     * Output iron, 0 to 1 (0, the default, is off): a parallel branch that
     * takes the reproduced signal through a one-pole low-pass at 60 Hz, a
     * curve with an even and an odd term, and the inverse filter capped at
     * 4 kHz, and adds what the curve changed: the low end saturates first. The
     * iron also loses its low end linearly: by the same share the output
     * crosses a first-order high-pass at 20 Hz. Its share glides over 20 ms.
     * Non-finite values are ignored.
     */
    public void setOutputIron(double amount)
    {
        if (Double.isFinite(amount)) outputIron = DspMath.clamp(amount, 0.0, 1.0);
    }

    /** Reproduce head model, default BUMP. Null is ignored. */
    public void setHeadModel(HeadModel m)
    {
        if (m == null) return;
        headModel = m.ordinal();
        dirty = true;
    }

    /** Record and reproduce electronics, default IDEAL. Null is ignored. */
    public void setElectronics(Electronics e)
    {
        if (e == null) return;
        electronics = e.ordinal();
        dirty = true;
    }

    /**
     * Reproduce alignment trims, as the HF and LF reproduce adjustments of a
     * machine (both 0 by default). A call with a non-finite value is ignored.
     *
     * @param hfDb trim of the de-emphasis at high frequencies, -6 to +6 dB
     * @param lfDb trim of the low end (the NAB LF network, or a shelf at 50 Hz
     *             with CCIR), -6 to +6 dB
     */
    public void setAlignment(double hfDb, double lfDb)
    {
        if (!Double.isFinite(hfDb) || !Double.isFinite(lfDb)) return;
        alignHf = DspMath.clamp(hfDb, -6.0, 6.0);
        alignLf = DspMath.clamp(lfDb, -6.0, 6.0);
        dirty = true;
    }

    /**
     * Trim of the record emphasis, -6 to +6 dB (0 by default): a high shelf
     * around 4 kHz on the record side and its exact inverse on the reproduce
     * side. The round trip stays flat at low level; with more emphasis a loud
     * high tone distorts more and the tape's hiss up there falls by as much.
     * Non-finite values are ignored.
     */
    public void setRecordEmphasis(double dB)
    {
        if (!Double.isFinite(dB)) return;
        recordEmphasis = DspMath.clamp(dB, -6.0, 6.0);
        dirty = true;
    }

    /** @return the trim of the record emphasis in dB */
    public double getRecordEmphasis() { return recordEmphasis; }

    /**
     * The record level's peak, the overload lamp before the tape: what drives
     * the curve, read at the internal rate (so it sees the peaks between
     * samples), 1 for a full-scale signal at nominal drive and operating level,
     * falling with a 300 ms time constant. Updated once per block.
     *
     * @param channel channel index (out of range returns 0)
     */
    public double getRecordPeak(int channel)
    {
        if (channel < 0 || channel >= MAX_RECORD_PEAKS) return 0.0;
        return recordPeak[channel];
    }

    /**
     * How far the channels' alignment strays, 0 to 1 (0, the default: every
     * channel the same, to the bit). Each channel takes its own fixed
     * deviation, the same at every run: up to 8 % of bias, up to 0.75 dB of
     * playback treble (a shelf from 6 kHz) and 0.25 dB of level at 1.
     * Magnitudes only. Non-finite values are ignored.
     */
    public void setAlignmentTolerance(double amount)
    {
        if (Double.isFinite(amount)) alignTolerance = DspMath.clamp(amount, 0.0, 1.0);
    }

    /** @return how far the channels' alignment strays */
    public double getAlignmentTolerance() { return alignTolerance; }

    /** @return the record level's peak in decibels (floored at -100) */
    public double getRecordPeakDb(int channel) { return gainToDecibels(getRecordPeak(channel)); }

    /** Tape formulation, default CLASSIC. Null is ignored. */
    public void setFormulation(Formulation f)
    {
        if (f == null) return;
        formulation = f.ordinal();
        dirty = true;
    }

    /** Track format, default HALF. Null is ignored. */
    public void setTrackFormat(TrackFormat f)
    {
        if (f == null) return;
        trackFormat = f.ordinal();
        dirty = true;
    }

    /**
     * Operating level above the reference flux, 0 to 9 dB (0 by default): the
     * tape is driven that much harder for the same input while the output
     * level is kept, and the hiss falls by as much against the signal. A
     * 1 kHz tone at -12 dBFS carries 0.16 % THD at 0 dB, 0.31 % at 3 dB and
     * 0.60 % at 6 dB. Non-finite values are ignored.
     */
    public void setCalibrationLevel(double dB)
    {
        if (!Double.isFinite(dB)) return;
        calLevel = DspMath.clamp(dB, 0.0, 9.0);
        dirty = true;
    }

    /** Monitored head or input, default PLAY_HEAD. Null is ignored. */
    public void setMonitor(Monitor m)
    {
        if (m == null) return;
        monitor = m.ordinal();
        dirty = true;
    }

    public Transport getTransport() { return Transport.values()[transport]; }
    public double getHum() { return humDb; }
    public Mains getMains() { return Mains.values()[mains]; }
    public double getChannelBleed() { return channelBleedDb; }
    public double getEvenHarmonics() { return evenHarmonics; }
    public double getOutputIron() { return outputIron; }
    public HeadModel getHeadModel() { return HeadModel.values()[headModel]; }
    public Electronics getElectronics() { return Electronics.values()[electronics]; }
    public double getAlignmentHf() { return alignHf; }
    public double getAlignmentLf() { return alignLf; }
    public Formulation getFormulation() { return Formulation.values()[formulation]; }
    public TrackFormat getTrackFormat() { return TrackFormat.values()[trackFormat]; }
    public double getCalibrationLevel() { return calLevel; }
    public Monitor getMonitor() { return Monitor.values()[monitor]; }
    public double getDrive() { return driveDb; }
    public double getBias() { return bias; }
    public Speed getSpeed() { return Speed.values()[speed]; }
    public Standard getStandard() { return Standard.values()[standard]; }
    public double getLossEffects() { return loss; }
    public double getHeadBump() { return headBumpAmt; }
    public double getWowFlutter() { return wowFlutter; }
    public double getNoise() { return noiseDb; }
    public double getMix() { return mix; }

    /** @return total latency in samples (active oversampler + loss FIR + transport delay) */
    public int getLatency() { return latency; }

    /** @return {@link #getLatency}, in prepared-rate samples */
    public int getLatencySamples() { return getLatency(); }

    // -- Processing ------------------------------------------------------------------

    /** {@link #process(double[][], int, int)} from sample 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, 0, numSamples);
    }

    /**
     * Processes a block in place. Pass-through until {@link #prepare}
     * succeeds. Non-finite input samples are replaced by silence before they
     * reach any state. Channels beyond the prepared count pass through.
     *
     * @param channels   planar channels
     * @param offset     first sample in every channel
     * @param numSamples block length
     */
    public void process(double[][] channels, int offset, int numSamples)
    {
        if (!prepared) return;
        final int nCh = Math.min(channels.length, numChannels);
        final int nS = numSamples;
        if (nCh == 0 || nS <= 0) return;

        for (int ch = 0; ch < nCh; ++ch)
        {
            final double[] d = channels[ch];
            for (int i = 0; i < nS; ++i)
                if (!Double.isFinite(d[offset + i])) d[offset + i] = 0.0;
        }

        // Linear stages change at once; the drive makeup calibrates over the
        // next blocks.
        if (dirty)
        {
            dirty = false;
            recomputeLinear();
            if (calActive) calRestart = true;
            else startCalibration();
        }

        for (int off = 0; off < nS; off += maxBlock)
            processChunk(channels, offset + off, Math.min(maxBlock, nS - off), nCh);
    }

    private void processChunk(double[][] buffer, int off, int nS, int nCh)
    {
        final double mixTarget = mix;
        final double mixStart = currentMix;
        final double wfDepth = wowFlutter;
        final boolean speedTransport = transport == 1;
        if (speedTransport != speedTransportActive)
        {
            // Into the speed model from where the modulated delay stands; back
            // to the modulated model at its own position.
            if (speedTransport)
            {
                tapeTransport.setSpeedNow(speedIps);
                tapeTransport.syncDelay((double) delayCenter + lastMod);
            }
            speedTransportActive = speedTransport;
        }
        final boolean circuit = circuitActive;
        aimRamps(false);
        if (hissOn() && hissNormDirty) computeHissNorm();
        final boolean hissActive = hiss.active();
        final boolean humActive = hum.active();
        final boolean xtActive = xt.active();
        final boolean trActive = tr.active();
        final boolean monitorMoving = monitorRamp.now != 1.0 || monitorRamp.target != 1.0;
        aimDryPhase(mixTarget);
        final double humStep = (mains == 1 ? 60.0 : 50.0) / sampleRate;
        if (humStep != humStepFor)
        {
            humStepFor = humStep;
            humRotCos = Math.cos(2.0 * Math.PI * humStep);
            humRotSin = Math.sin(2.0 * Math.PI * humStep);
        }
        {
            final double r = 1.0 / Math.sqrt(humCos * humCos + humSin * humSin);
            humCos *= r;
            humSin *= r;
        }
        final int dryMask = drySize - 1;

        // 1. Dry snapshot for the latency-compensated mix (through the
        //    oversampler's filters when they are minimum phase).
        if (dryThroughFilters)
        {
            for (int ch = 0; ch < nCh; ++ch)
                System.arraycopy(buffer[ch], off, dryScratch[ch], 0, nS);
            dryPath.process(dryScratch, 0, nS);
        }
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

        // At rest: with the mix settled on the dry side, or the source being
        // monitored, nothing of the machine is heard, so it does not run. The
        // dry ring keeps its place and a pending calibration goes on.
        if ((mixTarget == 0.0 && mixStart == 0.0) || (monitorRamp.now == 0.0 && monitorRamp.target == 0.0))
        {
            for (int i = 0; i < nS; ++i)
            {
                final double inTurn = dryInputLead.advance();
                final double outTurn = dryOutputLead.advance();
                final double ironTurn = dryIronLead.advance();
                final int at = (dryPos + i - dryDelay) & dryMask;
                for (int ch = 0; ch < nCh; ++ch)
                    buffer[ch][off + i] = turnDry(ch, dryRing[ch][at], inTurn, outTurn, ironTurn);
            }
            dryPos = (dryPos + nS) & dryMask;
            if (calActive) stepCalibration(nS * osFactor * steps);
            resting = true;
            return;
        }
        if (resting)
        {
            // Back from rest: the machine's memory is of another time. It
            // starts from rest and runs unheard until its own latency has
            // passed; then the mix (or the monitor) fades it in.
            clearMachine();
            hSm = hScale;
            mSm = mScale;
            bSm = biasAmp;
            wakeHold = latency + 1;
            resting = false;
        }
        final int warm = Math.min(wakeHold, nS);
        wakeHold -= warm;

        // 1b. The line input is AC coupled: two one-pole high-passes in series
        // (critically damped, the difference taken first so that a constant
        // cancels exactly). Then the record side of the emphasis trim.
        {
            final double p = inputAcPole, g = 0.5 * (1.0 + p);
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] d = buffer[ch];
                final double[] s = inputAc[ch];
                final Shelf trim = recTrim[ch];
                for (int i = 0; i < nS; ++i)
                {
                    final double x = d[off + i];
                    final double y1 = g * (x - s[0]) + p * s[1];
                    s[0] = x;
                    s[1] = y1;
                    final double y2 = g * (y1 - s[2]) + p * s[3];
                    s[2] = y1;
                    s[3] = y2;
                    d[off + i] = trim.process(y2);
                }
            }
        }

        // 2. Record EQ (emphasis) at base rate (the circuit runs its record
        // network at the internal rate instead).
        if (!circuit)
        {
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] d = buffer[ch];
                final Shelf hf = recordHF[ch];
                final Shelf lf = recordLF[ch];
                for (int i = 0; i < nS; ++i)
                    d[off + i] = hf.process(lf.process(d[off + i]));
            }
        }

        // 3. Push-pull AC-bias hysteresis. One shared carrier phase for all
        // channels; each channel runs a +carrier and a -carrier JA instance and
        // averages them.
        {
            final boolean osOn = oversampler != null;
            final double[][] osData;
            final int osOff;
            final int osN;
            if (osOn)
            {
                osData = oversampler.upsample(buffer, off, nS);
                osOff = 0;
                osN = oversampler.getHighRateLength();
            }
            else
            {
                osData = buffer;
                osOff = off;
                osN = nS;
            }
            final int phaseStart = biasPhase;
            // Geometric ramps of the calibrated set (drive, makeup, bias): the
            // pair spans orders of magnitude, so power-law interpolation keeps
            // it on its calibration curve.
            double hRat = 1.0, mRat = 1.0, bRat = 1.0;
            double hEnd = hSm, mEnd = mSm, bEnd = bSm;
            if (osN > 0 && (hSm != hScale || mSm != mScale || bSm != biasAmp))
            {
                final double kSm = 1.0 - Math.exp(-(double) osN
                    / (GAIN_RAMP_SECONDS * sampleRate * (double) osFactor));
                hEnd = glide(hSm, hScale, kSm);
                mEnd = glide(mSm, mScale, kSm);
                bEnd = glide(bSm, biasAmp, kSm);
                final double inv = 1.0 / (double) osN;
                hRat = Math.pow(hEnd / hSm, inv);
                mRat = Math.pow(mEnd / mSm, inv);
                bRat = Math.pow(bEnd / bSm, inv);
            }
            final boolean ramping = hRat != 1.0 || mRat != 1.0 || bRat != 1.0;
            final boolean alignOn = updateAlignment(nCh);
            final boolean asymActive = asym.active();
            final double asymTarget = asym.target, asymStep = asym.step;
            double asymAfter = asym.now;
            final boolean twoStep = steps > 1;
            // The oversampler carries at most Oversampling.MAX_CHANNELS channels.
            final int coreChannels = osOn ? Math.min(nCh, osData.length) : nCh;
            for (int ch = 0; ch < coreChannels; ++ch)
            {
                final double[] d = osData[ch];
                final Hysteresis hp = hysteresis[ch];
                final Hysteresis hn = hysteresisN[ch];
                final Stepped st = stepped[ch];
                final NormalizedLadder rec0 = recNet[ch][0], rec1 = recNet[ch][1];
                final NormalizedLadder play0 = playNet[ch][0], play1 = playNet[ch][1];
                double mean = asymMean[ch];
                double asymNow = asym.now;
                double inScale = hSm, mS = mSm, b = bSm;
                int phase = phaseStart;
                double held = recordHeld[ch];
                final double biasTrim = alignOn && ch < MAX_ALIGNED ? alignBias[ch] : 1.0;
                for (int i = 0; i < osN; ++i)
                {
                    if (ramping)
                    {
                        inScale *= hRat;
                        mS *= mRat;
                        b *= bRat;
                    }
                    double xs = d[osOff + i];
                    if (circuit) xs = rec1.processSample(rec0.processSample(xs, 0), 0);
                    if (asymActive)
                    {
                        // Even term less its running mean: no DC reaches the tape.
                        final double square = xs * xs;
                        mean = asymMeanA * mean + (1.0 - asymMeanA) * square;
                        asymNow = LinearRamp.advance(asymNow, asymTarget, asymStep);
                        xs += asymNow * ASYMMETRY_SHARE * (square - mean);
                    }
                    final double x = inScale * xs;
                    held = stdMax(Math.abs(x), held * recordFall);
                    final double pair;
                    if (twoStep)
                    {
                        pair = twoSteps(st, hp, hn, x, alignOn ? b * biasTrim : b, phase);
                        phase = (phase + 2) & 7;
                    }
                    else
                    {
                        final double c = carrier(alignOn ? b * biasTrim : b, biasNominal, phase);
                        phase = (phase + 1) & 7;
                        final double mp = hp.processSample(x + c);
                        final double mn = hn.processSample(x - c);
                        pair = mp + mn;
                    }
                    double out = (mS * 0.5) * pair;
                    if (circuit) out = play1.processSample(play0.processSample(out, 0), 0);
                    d[osOff + i] = out;
                }
                asymAfter = asymNow;
                asymMean[ch] = mean;
                if (!Double.isFinite(held)) held = 0.0;
                recordHeld[ch] = held;
                if (ch < MAX_RECORD_PEAKS)
                    recordPeak[ch] = held / RECORD_FULL_SCALE;
            }
            if (nCh > 0) asym.now = asymAfter;
            if (circuit)
                for (int ch = 0; ch < nCh; ++ch)
                    for (int s = 0; s < 2; ++s)
                    {
                        recNet[ch][s].advanceRamp(osN);
                        playNet[ch][s].advanceRamp(osN);
                    }
            hSm = hEnd;
            mSm = mEnd;
            bSm = bEnd;
            biasPhase = (phaseStart + osN * steps) & 7;
            // Advance a pending makeup calibration by as many steps of the
            // core as this block has: bounded work per block.
            if (calActive) stepCalibration(osN * steps);
            if (osOn) oversampler.downsample(buffer, off, nS);
        }

        // The over-bias low-pass starts from the signal it receives, not from
        // rest: switching it on with a zero state stepped the output.
        final boolean overBiasStart = overBiasOn && !overBiasActive;
        overBiasActive = overBiasOn;

        // 4-6. Per frame: hiss onto the tape, loss FIR and head, transport,
        // playback EQ, over-bias, output coupling and transformer per channel;
        // then the channel bleed, hum, monitor and mix across the channels.
        final int dm = delayMask;
        final int firTapCount = firLen;
        final int firStart = firHalf - (firLen - 1);
        for (int i = 0; i < nS; ++i)
        {
            // One shared transport per frame (all channels move together).
            final double readPos;
            if (speedTransport)
            {
                readPos = tapeTransport.next(wfDepth);
            }
            else
            {
                lastMod = nextTransportMod(wfDepth);
                readPos = (double) delayCenter + lastMod;
            }
            final int readInt = (int) Math.floor(readPos);
            final double frac = readPos - readInt;
            final double hissAmp = hissActive ? hiss.next() * hissNorm * UNIFORM_TO_UNIT_RMS : 0.0;
            final double trShare = trActive ? tr.next() : 0.0;

            for (int ch = 0; ch < nCh; ++ch)
            {
                final double[] d = buffer[ch];
                double in = d[off + i];

                // Hiss is magnetic noise on the tape: it is read through the
                // head losses and the reproduce de-emphasis (own random stream).
                if (hissActive)
                {
                    rngNoise = rngNoise * 1664525 + 1013904223;
                    double n1 = ((double) (rngNoise >>> 8) / 8388608.0 - 1.0) * hissAmp;
                    if (circuit)
                        n1 = hissLF[ch].process(hissHF[ch].process(n1));
                    in += n1;
                }

                // Loss FIR (linear phase) on a per-channel mirrored ring.
                final double[] fir = firState[ch];
                fir[firPos] = in;
                fir[firPos + firHalf] = in;
                double y = dotProduct(firTaps, fir, firPos + firStart, firTapCount);

                // Head bump resonance, or the contour chain.
                if (contourActive)
                {
                    final Peak[] chain = headContour[ch];
                    for (int k = 0; k < CONTOUR_SECTIONS; ++k) y = chain[k].process(y);
                }
                else
                {
                    y = headBump[ch].process(y);
                }

                // Transport fractional delay: interpolate at t - readInt -
                // frac, between p1 = x(t-readInt) and p2 = x(t-readInt-1).
                final double[] dl = delayRing[ch];
                dl[delayPos] = y;
                final int base = delayPos - readInt;
                final double p0 = dl[(base + 1) & dm];
                final double p1 = dl[base & dm];
                final double p2 = dl[(base - 1) & dm];
                final double p3 = dl[(base - 2) & dm];
                double w;
                if (speedTransport)
                {
                    // Four-point Lagrange (nodes -1, 0, 1, 2).
                    final double f = frac;
                    final double fm1 = f - 1.0, fm2 = f - 2.0, fp1 = f + 1.0;
                    w = -f * fm1 * fm2 * (1.0 / 6.0) * p0 + fp1 * fm1 * fm2 * 0.5 * p1
                        - fp1 * f * fm2 * 0.5 * p2 + fp1 * f * fm1 * (1.0 / 6.0) * p3;
                }
                else
                {
                    // Catmull-Rom.
                    w = p1 + 0.5 * frac * (p2 - p0
                        + frac * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3
                        + frac * (3.0 * (p1 - p2) + p3 - p0)));
                }

                // Playback EQ (exact inverse de-emphasis; the circuit ran its
                // reproduce network before the downsampler).
                if (!circuit)
                    w = playLF[ch].process(playHF[ch].process(w));
                // The reproduce side of the emphasis trim (the tape's hiss crosses it too).
                w = playTrim[ch].process(w);
                if (alignActive && ch < MAX_ALIGNED)
                {
                    // The channel's own treble (a first-order shelf) and level.
                    final double xin = w;
                    final double hpOut = alignHpA * (alignHpY[ch] + xin - alignHpX[ch]);
                    alignHpX[ch] = xin;
                    alignHpY[ch] = hpOut;
                    w = alignLevel[ch] * (xin + alignShelf[ch] * hpOut);
                }

                // Over-bias self-erasure (or the under-bias lift): a first-order
                // section engaged away from the nominal bias only. Its zero
                // matches the analog magnitude at Nyquist, so the roll-off is
                // the same at every rate.
                if (overBiasOn)
                {
                    double lp = overBiasLp[ch];
                    double x1 = overBiasX1[ch];
                    final double xin = w;
                    if (overBiasStart && i == 0)
                    {
                        lp = xin;
                        x1 = xin;
                    }
                    lp = obB0 * xin + obB1 * x1 + obP * lp;
                    x1 = xin;
                    overBiasLp[ch] = lp;
                    overBiasX1[ch] = x1;
                    w = lp;
                }

                // AC-coupled playback amplifier (2nd-order 24 Hz high-pass).
                w = outHp[ch].process(w);

                // Output transformer branch: always running, added by its share.
                {
                    final double wd = w;
                    final double u = trPre[ch].process(wd);
                    double m = trMean[ch];
                    m = trMeanA * m + (1.0 - trMeanA) * u * u;
                    trMean[ch] = m;
                    final double bent = trPost[ch].process(
                        TRANSFORMER_EVEN * (u * u - m) - TRANSFORMER_ODD * u * u * u);
                    // The iron's low end: a low-pass that always runs, taken
                    // off by the same share. What DC the even term leaves goes
                    // with it.
                    double low = ironLp[ch];
                    final double sum = trShare > 0.0 ? wd + trShare * bent : wd;
                    low += ironA * (sum - low);
                    ironLp[ch] = low;
                    if (trShare > 0.0) w = sum - trShare * low;
                }
                wet[ch] = w;
            }

            if (xtActive)
            {
                final double g = xt.next();
                for (int ch = 0; ch + 1 < nCh; ch += 2)
                {
                    final double a = wet[ch], b = wet[ch + 1];
                    final double fromA = xtHp[ch].process(a);
                    final double fromB = xtHp[ch + 1].process(b);
                    wet[ch] = a + g * fromB;
                    wet[ch + 1] = b + g * fromA;
                }
            }
            if (humActive)
            {
                final double amp = hum.next();
                // A rotation per sample (its second harmonic 2 sin cos), the
                // radius held on the circle every block.
                final double s = humSin * humRotCos + humCos * humRotSin;
                humCos = humCos * humRotCos - humSin * humRotSin;
                humSin = s;
                final double h = amp * HUM_NORM * (humSin + humSin * humCos);
                for (int ch = 0; ch < nCh; ++ch) wet[ch] += h;
            }

            // Latency-compensated monitor and mix. While the machine warms up
            // after a rest the dry side alone is heard, and the mix and the
            // monitor wait for it.
            final double monitorNow = i < warm ? 0.0 : (monitorMoving ? monitorRamp.next() : 1.0);
            final double mixVal = moveTowards(mixStart, mixTarget, mixMaxStep * (double) Math.max(0, i + 1 - warm));
            final double inTurn = dryInputLead.advance();
            final double outTurn = dryOutputLead.advance();
            final double ironTurn = dryIronLead.advance();
            final boolean wetAlone = mixVal == 1.0 && monitorNow == 1.0;
            final int dryIdx = (dryPos + i - dryDelay) & dryMask;
            for (int ch = 0; ch < nCh; ++ch)
            {
                final double rawDry = dryRing[ch][dryIdx];
                final double turned = turnDry(ch, rawDry, inTurn, outTurn, ironTurn);
                final double drySample = wetAlone ? rawDry : turned;
                double w = wet[ch];
                if (monitorNow != 1.0)
                    w = drySample + (w - drySample) * monitorNow;
                buffer[ch][off + i] = drySample + (w - drySample) * mixVal;
            }

            firPos = (firPos + 1) & (firHalf - 1);
            delayPos = (delayPos + 1) & delayMask;
        }
        dryPos = (dryPos + nS) & dryMask;
        currentMix = moveTowards(mixStart, mixTarget, mixMaxStep * (double) (nS - warm));
    }

    /**
     * Where the phase of the dry side goes: that of the high-passes while the
     * machine is mixed in, none where the dry side is the output.
     */
    private void aimDryPhase(double mixTarget)
    {
        final boolean turn = mixTarget > 0.0 && monitor != 2;
        final double iron = outputIron;
        dryInputLead.aim(turn);
        dryOutputLead.aim(turn);
        // The iron takes off a share of a one-pole low-pass: an all-pass at
        // half that share of its corner leads by as much.
        dryIronLead.setCorner(0.5 * IRON_CORNER_HZ * stdMax(iron, 0.05));
        dryIronLead.aim(turn && iron >= 0.05);
    }

    /** A sample of the dry side through the phase of the high-passes. */
    private double turnDry(int channel, double x, double inTurn, double outTurn, double ironTurn)
    {
        return dryIronLead.processSample(channel,
            dryOutputLead.processSample(channel, dryInputLead.processSample(channel, x, inTurn), outTurn), ironTurn);
    }

    /**
     * The steps of the core in an internal sample: two with four levels from
     * 44.1 to under 352.8 kHz, at a factor whose own samples hold the
     * half-bands' delay (1x, 2x and 4x).
     */
    private int stepsInUse(double rate)
    {
        final boolean whole = osFactor == 1 || osFactor == 2 || osFactor == 4;
        final double internal = rate * (double) osFactor;
        return fourLevel && whole && 4.0 * internal > 0.999999 * AUTOMATIC_INTERNAL_RATE
            && internal < 0.999999 * FOUR_LEVEL_INTERNAL_RATE ? 2 : 1;
    }

    /**
     * One internal sample solved in two: a maximally flat half-band of 7 taps
     * up and one of 11 down, four internal samples in all. Both are linear
     * phase; the band around the new Nyquist frequency, where the carrier's
     * own square lies, meets a zero of sixth order on the way down.
     */
    private double twoSteps(Stepped s, Hysteresis hp, Hysteresis hn, double x, double amplitude, int phase)
    {
        final double first = (9.0 * (s.in0 + s.in1) - (x + s.in2)) * 0.0625;
        final double second = s.in0;
        s.in2 = s.in1;
        s.in1 = s.in0;
        s.in0 = x;

        final double ca = carrier(amplitude, biasNominal, phase);
        final double a = hp.processSample(first + ca) + hn.processSample(first - ca);
        final double out = (3.0 * (a + s.e4) - 25.0 * (s.e0 + s.e3) + 150.0 * (s.e1 + s.e2)) * (1.0 / 512.0)
                         + 0.5 * s.o2;
        final double cb = carrier(amplitude, biasNominal, (phase + 1) & 7);
        final double b = hp.processSample(second + cb) + hn.processSample(second - cb);
        s.e4 = s.e3;
        s.e3 = s.e2;
        s.e2 = s.e1;
        s.e1 = s.e0;
        s.e0 = a;
        s.o2 = s.o1;
        s.o1 = s.o0;
        s.o0 = b;
        return out;
    }

    /**
     * The carrier at one of its eight phases.
     *
     * @param amplitude the amplitude of the bias in use
     * @param nominal   the optimum of the material: over it the inner level stays
     * @param phase     the phase, 0 to 7
     */
    private double carrier(double amplitude, double nominal, int phase)
    {
        final double sign = BIAS_TABLE[phase];
        if (!fourLevel) return amplitude * sign;
        return sign * (((phase & 1) != 0) ? OUTER_LEVEL * amplitude : INNER_LEVEL * stdMin(amplitude, nominal));
    }

    private boolean hissOn() { return noiseDb > -120.0; }

    /** Aims the level ramps at the settings (and lands them with {@code land}). */
    private void aimRamps(boolean land)
    {
        final double fs = sampleRate;
        // Hiss: the setting, less what the track width, the formulation and
        // the operating level take off it against the signal.
        double hissLevel = 0.0;
        if (hissOn())
        {
            final int track = clampInt(trackFormat, 0, 2);
            final double offset = 10.0 * Math.log10(TRACK_WIDTH_MM[0] / TRACK_WIDTH_MM[track])
                + formulationOf(formulation).hissDb
                - calLevel;
            hissLevel = Math.pow(10.0, (noiseDb + offset) / 20.0);
        }
        final double humLevel = humDb > -120.0 ? Math.pow(10.0, humDb / 20.0) : 0.0;
        final double xtLevel = channelBleedDb > -120.0 ? Math.pow(10.0, channelBleedDb / 20.0) * xtNorm : 0.0;
        if (xtLevel > 0.0 && !xt.active())
            for (final Shelf s : xtHp) s.clear();
        final double monitorLevel = monitor == 2 ? 0.0 : 1.0;
        hiss.aim(hissLevel, 0.05 * fs);
        hum.aim(humLevel, 0.05 * fs);
        xt.aim(xtLevel, 0.05 * fs);
        tr.aim(outputIron, 0.02 * fs);
        asym.aim(evenHarmonics, 0.02 * fs * (double) osFactor);
        monitorRamp.aim(monitorLevel, 0.02 * fs);
        if (land)
        {
            hiss.now = hiss.target;
            hum.now = hum.target;
            xt.now = xt.target;
            tr.now = tr.target;
            asym.now = asym.target;
            monitorRamp.now = monitorRamp.target;
        }
    }

    /**
     * The hiss scale that makes white noise on the tape come out at unit RMS:
     * one over the RMS gain of the reproduce chain (loss FIR, head,
     * de-emphasis, over-bias and output coupling) over a grid of frequencies.
     */
    private void computeHissNorm()
    {
        hissNormDirty = false;
        if (numChannels <= 0) return;
        final int grid = 256;
        double power = 0.0;
        for (int k = 0; k < grid; ++k)
        {
            final double w = Math.PI * ((double) k + 0.5) / grid;
            final double cw = Math.cos(w), sw = Math.sin(w);
            double re = 0.0, im = 0.0, rr = 1.0, ri = 0.0;
            for (int n = 0; n < firLen; ++n)
            {
                re += designTaps[n] * rr;
                im += designTaps[n] * ri;
                final double nr = rr * cw + ri * sw;
                ri = ri * cw - rr * sw;
                rr = nr;
            }
            double g2 = (re * re + im * im) * firNorm * firNorm;
            if (contourActive)
            {
                for (final Peak s : headContour[0]) g2 *= peakMagnitude(s, w) * peakMagnitude(s, w);
            }
            else
            {
                g2 *= peakMagnitude(headBump[0], w) * peakMagnitude(headBump[0], w);
            }
            final double play = shelfMagnitude(playHF[0], w) * shelfMagnitude(playLF[0], w);
            g2 *= play * play;
            if (overBiasOn)
            {
                final double nr = obB0 + obB1 * cw, ni = -obB1 * sw;
                final double dr = 1.0 - obP * cw, di = obP * sw;
                g2 *= (nr * nr + ni * ni) / (dr * dr + di * di);
            }
            g2 *= peakMagnitude(outHp[0], w) * peakMagnitude(outHp[0], w);
            power += g2;
        }
        power /= grid;
        hissNorm = power > 1e-20 ? 1.0 / Math.sqrt(power) : 1.0;
    }

    /** HF emphasis (kHi*s*t + 1)/(s*t + 1) via the bilinear transform; {@code inverse} swaps numerator and denominator. */
    private static Shelf makeHFShelf(double t, double kHi, double fs, boolean inverse, Shelf out)
    {
        final double kt = 2.0 * fs * t;
        double n0 = kHi * kt + 1.0, n1 = 1.0 - kHi * kt;
        double d0 = kt + 1.0, d1 = 1.0 - kt;
        if (inverse)
        {
            double tmp = n0;
            n0 = d0;
            d0 = tmp;
            tmp = n1;
            n1 = d1;
            d1 = tmp;
        }
        out.b0 = n0 / d0;
        out.b1 = n1 / d0;
        out.a1 = d1 / d0;
        return out;
    }

    /** LF shelf (s*t + kLo)/(s*t + 1) via the bilinear transform; {@code inverse} swaps numerator and denominator. */
    private static Shelf makeLFShelf(double t, double kLo, double fs, boolean inverse, Shelf out)
    {
        final double kt = 2.0 * fs * t;
        double n0 = kt + kLo, n1 = kLo - kt;
        double d0 = kt + 1.0, d1 = 1.0 - kt;
        if (inverse)
        {
            double tmp = n0;
            n0 = d0;
            d0 = tmp;
            tmp = n1;
            n1 = d1;
            d1 = tmp;
        }
        out.b0 = n0 / d0;
        out.b1 = n1 / d0;
        out.a1 = d1 / d0;
        return out;
    }

    /** Bilinear transform of (n1 s + n0) / (d1 s + d0). */
    private static Shelf firstOrder(double n1, double n0, double d1, double d0, double fs, Shelf out)
    {
        final double k = 2.0 * fs;
        final double a0 = d1 * k + d0;
        out.b0 = (n1 * k + n0) / a0;
        out.b1 = (n0 - n1 * k) / a0;
        out.a1 = (d0 - d1 * k) / a0;
        return out;
    }

    /** Magnitude of a first-order section at w (radians per sample). */
    private static double shelfMagnitude(Shelf s, double w)
    {
        final double c = Math.cos(w), sn = Math.sin(w);
        final double nr = s.b0 + s.b1 * c, ni = -s.b1 * sn;
        final double dr = 1.0 + s.a1 * c, di = -s.a1 * sn;
        return Math.sqrt((nr * nr + ni * ni) / stdMax(1e-300, dr * dr + di * di));
    }

    /** Magnitude of a biquad section at w (radians per sample). */
    private static double peakMagnitude(Peak s, double w)
    {
        final double c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2.0 * w), s2 = Math.sin(2.0 * w);
        final double nr = s.b0 + s.b1 * c1 + s.b2 * c2, ni = -(s.b1 * s1 + s.b2 * s2);
        final double dr = 1.0 + s.a1 * c1 + s.a2 * c2, di = -(s.a1 * s1 + s.a2 * s2);
        return Math.sqrt((nr * nr + ni * ni) / stdMax(1e-300, dr * dr + di * di));
    }

    private static FormulationParams formulationOf(int f)
    {
        switch (clampInt(f, 0, 2))
        {
        case 1:
            return FORM_HIGH_OUTPUT;
        case 2:
            return FORM_VINTAGE;
        default:
            return FORM_CLASSIC;
        }
    }

    /** Clears the memory of the machine's side (all but the dry ring). */
    private void clearMachine()
    {
        for (final Hysteresis h : hysteresis) h.reset();
        for (final Hysteresis h : hysteresisN) h.reset();
        for (final Stepped s : stepped) s.clear();
        for (final double[] f : firState) Arrays.fill(f, 0.0);
        for (final double[] d : delayRing) Arrays.fill(d, 0.0);
        for (final double[] s : inputAc) Arrays.fill(s, 0.0);
        for (final Shelf s : recTrim) s.clear();
        for (final Shelf s : playTrim) s.clear();
        Arrays.fill(ironLp, 0.0);
        for (final Shelf s : recordHF) s.clear();
        for (final Shelf s : recordLF) s.clear();
        for (final Shelf s : playHF) s.clear();
        for (final Shelf s : playLF) s.clear();
        Arrays.fill(overBiasLp, 0.0);
        Arrays.fill(overBiasX1, 0.0);
        // Clear state only: the coefficients stay.
        for (final Peak b : headBump) b.clear();
        for (final Peak h : outHp) h.clear();
        for (final Shelf s : hissHF) s.clear();
        for (final Shelf s : hissLF) s.clear();
        for (final Shelf s : xtHp) s.clear();
        for (final Shelf s : trPre) s.clear();
        for (final Shelf s : trPost) s.clear();
        Arrays.fill(trMean, 0.0);
        Arrays.fill(asymMean, 0.0);
        Arrays.fill(wet, 0.0);
        for (final Peak[] chain : headContour) for (final Peak s : chain) s.clear();
        for (final NormalizedLadder[] n : recNet) for (final NormalizedLadder s : n) s.reset();
        for (final NormalizedLadder[] n : playNet) for (final NormalizedLadder s : n) s.reset();
        if (oversampler != null) oversampler.reset();
    }

    /** The speed in inches per second. */
    private static double speedInIps(int speed)
    {
        switch (speed)
        {
        case 3:
            return 3.75;
        case 0:
            return 7.5;
        case 2:
            return 30.0;
        default:
            return 15.0;
        }
    }

    /**
     * Rebuilds the linear stages (EQ, losses, head bump, over-bias, circuit)
     * and records the calibration request for drive, bias and emphasis.
     */
    private void recomputeLinear()
    {
        final double drive = Math.pow(10.0, driveDb / 20.0);
        final double ips = speedInIps(speed);
        final double lossAmt = loss;
        final double bumpAmt = headBumpAmt;

        // Bias -> carrier amplitude and over-bias erasure. Nominal (0.5) sits
        // at B = 3a. The formulation sets the JA parameters of the running
        // pairs when it changes; the bias scales with its coercive force so
        // that 0.5 stays the optimum of the material.
        final int form = formulation;
        final FormulationParams fp = formulationOf(form);
        if (form != formApplied)
        {
            for (final Hysteresis h : hysteresis) h.setParameters(fp.ms, fp.a, fp.alpha, fp.k, fp.c);
            for (final Hysteresis h : hysteresisN) h.setParameters(fp.ms, fp.a, fp.alpha, fp.k, fp.c);
            formApplied = form;
            hissNormDirty = true;
        }
        reqForm = fp;
        final double biasB = stdMin(6.0, 3.0 * Math.pow(9.0, bias - 0.5));
        reqBiasAmp = biasB * 2.2e4 * (fp.k / 2.7e4);
        biasNominal = 3.0 * 2.2e4 * (fp.k / 2.7e4);
        reqBiasNominal = biasNominal;
        if (biasB > 3.0)
        {
            // Gentle self-erasure (corner gliding 22 kHz -> about 12.8 kHz at
            // full over-bias): first-order low-pass with its pole at the analog
            // corner and a zero that matches the analog gain at Nyquist.
            // Between the optimum (B = 3) and B = 3.5 the low-pass at its
            // widest comes in by degrees.
            final double fc = 22000.0 * (3.5 / stdMax(biasB, 3.5));
            final double pole = Math.exp(-2.0 * Math.PI * fc / sampleRate);
            final double r = 0.5 * sampleRate / fc;
            final double nyq = 1.0 / Math.sqrt(1.0 + r * r);
            final double b0 = 0.5 * ((1.0 - pole) + nyq * (1.0 + pole));
            final double b1 = 0.5 * ((1.0 - pole) - nyq * (1.0 + pole));
            final double share = stdMin(1.0, (biasB - 3.0) / 0.5);
            obP = pole;
            obB0 = (1.0 - share) + share * b0;
            obB1 = share * b1 - (1.0 - share) * pole;
            overBiasOn = true;
        }
        else if (bias < 0.5)
        {
            // Under the optimum the short wavelengths record more strongly than
            // the long ones: a high shelf that grows as the bias falls, around
            // a frequency that follows the speed (the wavelength).
            final double lift = Math.pow(10.0, UNDER_BIAS_LIFT_DB * (1.0 - 2.0 * bias) / 20.0);
            final double centre = DspMath.clamp(UNDER_BIAS_CENTRE_HZ * ips / 15.0, 1000.0, 0.3 * sampleRate);
            final Shelf s = makeHFShelf(1.0 / (2.0 * Math.PI * centre * Math.sqrt(lift)), lift, sampleRate, false, scratchShelf);
            obP = -s.a1;
            obB0 = s.b0;
            obB1 = s.b1;
            overBiasOn = true;
        }
        else
        {
            overBiasOn = false;
        }

        // EQ time constants per standard and speed.
        double t2;                 // HF time constant
        boolean useLF;             // NAB LF constant 3180 us
        if (standard == Standard.NAB.ordinal())
        {
            t2 = (speed == 2) ? 17.5e-6 : (speed == 3) ? 90e-6 : 50e-6;
            useLF = speed != 2;
        }
        else
        {
            // 3.75 ips carries 90 us and the low-frequency constant in both standards.
            t2 = (speed == 3) ? 90e-6
               : (speed == 0) ? 70e-6
               : (speed == 1) ? 35e-6 : 17.5e-6;
            useLF = speed == 3;
        }
        final double tLo = 3180e-6;
        final double hfTrim = alignHf;
        final double lfTrim = alignLf;
        final double gHf = Math.pow(10.0, hfTrim / 20.0), gLf = Math.pow(10.0, lfTrim / 20.0);

        // The emphasis trim: a shelf on the record side and its exact inverse
        // on the reproduce side (both the identity, to the bit, at 0 dB).
        double trimAt1k;
        {
            final double gRec = Math.pow(10.0, recordEmphasis / 20.0);
            final double tTrim = 1.0 / (2.0 * Math.PI * EMPHASIS_TRIM_HZ * Math.sqrt(gRec));
            final Shelf up = makeHFShelf(tTrim, gRec, sampleRate, false, scratchUp);
            final Shelf down = makeHFShelf(tTrim, gRec, sampleRate, true, scratchDown);
            for (final Shelf s : recTrim) s.setCoeffs(up);
            for (final Shelf s : playTrim) s.setCoeffs(down);
            trimAt1k = shelfMagnitude(up, 2.0 * Math.PI * 1000.0 / sampleRate);
        }

        // Drive scaling with operating-level makeup: 0 dBFS at nominal drive
        // maps to H = 1.2a. Tape gain is level-dependent, so unity is defined
        // at one operating point: the makeup is calibrated at -12 dBFS
        // programme level through the same push-pull biased chain. The
        // operating level drives the tape harder; the makeup measures the
        // whole gain, so the output level is kept.
        final double calGain = Math.pow(10.0, calLevel / 20.0);
        reqHScale = drive * 1.2 * 2.2e4 * calGain;
        {
            // -12 dBFS reference, seen through the record emphasis at 1 kHz,
            // as a real machine's record-level alignment.
            final double w1 = 2.0 * Math.PI * 1000.0 * t2;
            final double emph1k = Math.sqrt((1.0 + HI_CAP * HI_CAP * w1 * w1)
                                            / (1.0 + w1 * w1));
            reqCalAmp = 0.25 * emph1k * trimAt1k;
            reqDrive = drive;
        }

        for (int ch = 0; ch < numChannels; ++ch)
        {
            makeHFShelf(t2, HI_CAP, sampleRate, false, scratchShelf);
            recordHF[ch].setCoeffs(scratchShelf);

            if (useLF) recordLF[ch].setCoeffs(makeLFShelf(tLo, LO_BOOST, sampleRate, false, scratchShelf));
            else recordLF[ch].setIdentity();

            final Shelf phf = playHF[ch];
            phf.setCoeffs(makeHFShelf(t2, HI_CAP / gHf, sampleRate, true, scratchShelf));

            final Shelf plf = playLF[ch];
            if (useLF) plf.setCoeffs(makeLFShelf(tLo, LO_BOOST / gLf, sampleRate, true, scratchShelf));
            else if (lfTrim != 0.0) plf.setCoeffs(makeLFShelf(tLo, 1.0 / gLf, sampleRate, true, scratchShelf));
            else plf.setIdentity();

            // The hiss's own de-emphasis, for the circuit (whose reproduce
            // network runs before the tape's noise is laid on).
            hissHF[ch].setCoeffs(phf);
            hissLF[ch].setCoeffs(plf);
        }

        // Circuit electronics: the record and reproduce networks from their
        // analog transfer functions, at the internal rate, as ladders.
        final boolean circuitWanted = electronics == 1;
        if (circuitWanted)
        {
            final double osRate = sampleRate * (double) osFactor;
            final double kLoR = useLF ? LO_BOOST : 1.0;
            final double kHiP = HI_CAP / gHf, kLoP = kLoR / gLf;
            final AnalogSection rec1 = AnalogSection.make(HI_CAP * t2 * tLo, HI_CAP * t2 * kLoR + tLo, kLoR,
                                                          t2 * tLo, t2 + tLo, 1.0);
            final AnalogSection play1 = AnalogSection.make(t2 * tLo, t2 + tLo, 1.0,
                                                           kHiP * t2 * tLo, kHiP * t2 * kLoP + tLo, kLoP);
            final double recLimit = stdMin(45000.0, 0.45 * osRate), playPole = stdMin(50000.0, 0.45 * osRate);
            final double corner = 1.0 / (2.0 * Math.PI * t2);
            final BiquadCoeffs r1 = FilterDesign.bilinear(rec1, osRate, corner);
            final BiquadCoeffs r2 = FilterDesign.bilinear(AnalogSection.lowPass(recLimit), osRate, recLimit);
            final BiquadCoeffs q1 = FilterDesign.bilinear(play1, osRate, corner);
            final BiquadCoeffs q2 = FilterDesign.bilinear(AnalogSection.firstOrderLowPass(playPole), osRate, playPole);
            final int ramp = (int) (0.05 * osRate);
            for (int ch = 0; ch < numChannels; ++ch)
            {
                final NormalizedLadder[] rec = recNet[ch];
                final NormalizedLadder[] play = playNet[ch];
                if (!circuitActive)
                {
                    rec[0].setCoeffs(r1);
                    rec[1].setCoeffs(r2);
                    play[0].setCoeffs(q1);
                    play[1].setCoeffs(q2);
                    for (final NormalizedLadder s : rec) s.reset();
                    for (final NormalizedLadder s : play) s.reset();
                }
                else
                {
                    rec[0].rampCoeffs(r1, ramp);
                    rec[1].rampCoeffs(r2, ramp);
                    play[0].rampCoeffs(q1, ramp);
                    play[1].rampCoeffs(q2, ramp);
                }
            }
            if (!circuitActive)
                for (final Shelf s : hissHF) s.clear();
        }
        else if (circuitActive)
        {
            // Back to the ideal shelves: they start from rest.
            for (final Shelf s : recordHF) s.clear();
            for (final Shelf s : recordLF) s.clear();
            for (final Shelf s : playHF) s.clear();
            for (final Shelf s : playLF) s.clear();
        }
        circuitActive = circuitWanted;

        // A new speed under the speed transport: the tape keeps moving at the
        // old one and glides to the new one with inertia.
        if (speedTransportActive && speedIps > 0.0) tapeTransport.setSpeed(ips);
        else tapeTransport.setSpeedNow(ips);
        speedIps = ips;

        // Loss-effect FIR (linear phase).
        final double v = ips * 0.0254;            // m/s
        // The record head (record-head monitoring) has the wider gap and spacing.
        final boolean sync = monitor == 1;
        final double gap = sync ? 6.0e-6 : 3.0e-6;           // playback head gap (m)
        final double spacing = sync ? 1.0e-6 : 0.5e-6;       // head-tape spacing (m)
        final double thickness = 1.0e-6;                     // effective coating depth (m)

        final int grid = firLen + 1;              // design grid (64 points at 48 kHz)
        final double[] mags = designMags;
        for (int kBin = 0; kBin <= grid / 2; ++kBin)
        {
            final double f = kBin * sampleRate / grid;
            double mag = 1.0;
            if (f > 1.0)
            {
                final double lambda = v / f;
                final double spacingLoss = Math.pow(10.0, -54.6 * (spacing / lambda) / 20.0);
                final double gx = Math.PI * gap / lambda;
                final double gapLoss = (gx < 1e-9) ? 1.0 : Math.abs(Math.sin(gx) / gx);
                final double tx = 4.0 * Math.PI * thickness / lambda;
                final double thickLoss = (tx < 1e-9) ? 1.0 : (1.0 - Math.exp(-tx)) / tx;
                mag = spacingLoss * gapLoss * thickLoss;
            }
            mags[kBin] = (1.0 - lossAmt) + lossAmt * mag;
            // Physical reproduce-gap cutoff: no real head reads anything above
            // about 21.5 kHz, whatever the loss amount. Raised-cosine
            // transition 19.5k -> 21.5k.
            if (f >= 21500.0)
                mags[kBin] = 0.0;
            else if (f > 19500.0)
                mags[kBin] *= 0.5 + 0.5 * Math.cos(Math.PI * (f - 19500.0) / 2000.0);
        }
        final double[] taps = designTaps;
        for (int n = 0; n < firLen; ++n)
        {
            // Frequency sampling with the cosines taken from the table.
            final int offset = n - firCenter;
            double acc = mags[0];
            for (int kBin = 1; kBin < grid / 2; ++kBin)
            {
                final int idx = ((kBin * offset) % grid + grid) % grid;
                acc += 2.0 * mags[kBin] * cosTable[idx];
            }
            acc += mags[grid / 2] * (((offset & 1) != 0) ? -1.0 : 1.0);
            final double hann = 0.5 - 0.5 * cosTable[(n + 1) % grid];
            taps[n] = acc * hann / grid;
        }
        // Normalize to exact unity at the 1 kHz calibration frequency.
        {
            final double w1k = 2.0 * Math.PI * 1000.0 / sampleRate;
            double re = 0.0, im = 0.0;
            for (int n = 0; n < firLen; ++n)
            {
                re += taps[n] * Math.cos(w1k * n);
                im -= taps[n] * Math.sin(w1k * n);
            }
            final double g = Math.sqrt(re * re + im * im);
            final double norm = (g > 1e-9) ? 1.0 / g : 1.0;
            for (int n = 0; n < firLen; ++n) taps[n] *= norm;
            firNorm = 1.0;
        }
        for (int n = 0; n < firLen; ++n)
            firTaps[firLen - 1 - n] = taps[n];   // reversed for the dot product

        // Head bump: its frequency is the speed over the length of the head's
        // contact with the tape, and each track format has its head.
        final double bumpHz = BUMP_HZ_PER_IPS * ips * CONTACT_RATIO[clampInt(trackFormat, 0, 2)];
        final double bumpDb = 2.5 * bumpAmt;
        final BiquadCoeffs bc = BiquadDesign.makePeak(sampleRate, bumpHz, 0.9, bumpDb);
        for (final Peak b : headBump) b.setCoeffs(bc);

        // Head contour: the bump and three ripples above it, each a resonance
        // designed on its analog prototype.
        contourActive = headModel == 1;
        if (contourActive)
        {
            for (int k = 0; k < CONTOUR_SECTIONS; ++k)
            {
                final BiquadCoeffs c = FilterDesign.digital(
                    AnalogSection.peak(bumpHz * CONTOUR_RATIO[k], CONTOUR_Q[k], CONTOUR_DB * bumpAmt * CONTOUR_SHARE[k]), band);
                for (final Peak[] chain : headContour) chain[k].setCoeffs(c);
            }
        }
        hissNormDirty = true;
    }

    /**
     * Starts the drive-makeup calibration for the latest request: a 1 kHz
     * burst at -12 dBFS programme level through the same push-pull biased
     * chain on two scratch instances.
     */
    private void startCalibration()
    {
        runHScale = reqHScale;
        runBiasAmp = reqBiasAmp;
        runBiasNominal = reqBiasNominal;
        runCalAmp = reqCalAmp;
        runDrive = reqDrive;
        runForm = reqForm;
        final double fs4 = sampleRate * (double) (osFactor * steps);
        // 16 ms settle + 8 ms measured: the biased loop's mean state needs a
        // few hundred carrier cycles to reach its steady branch.
        calN = (int) (0.024 * fs4);
        calFrom = (calN * 2) / 3;
        calW = 2.0 * Math.PI * 1000.0 / fs4;
        final FormulationParams fp = runForm;
        calib.prepare(fs4);
        calib.setParameters(fp.ms, fp.a, fp.alpha, fp.k, fp.c);
        calib2.prepare(fs4);
        calib2.setParameters(fp.ms, fp.a, fp.alpha, fp.k, fp.c);
        calI = 0;
        calRe = calIm = 0.0;
        calMeas = 0;
        calActive = true;
    }

    /**
     * Runs up to {@code budget} calibration samples; adopts the result (drive,
     * makeup and bias together) when the calibration completes.
     */
    private void stepCalibration(int budget)
    {
        final int stop = Math.min(calN, calI + Math.max(budget, 0));
        for (; calI < stop; ++calI)
        {
            final int i = calI;
            final double s = Math.sin(calW * i);
            final double x = runHScale * runCalAmp * s;
            final double c = carrier(runBiasAmp, runBiasNominal, i & 7);
            final double m = 0.5
                * (calib.processSample(x + c)
                 + calib2.processSample(x - c));
            // Measure the fundamental of the averaged pair (a correlation), not
            // the raw RMS: the raw output still carries the even-order carrier
            // residue that the downsampler removes in the real path.
            if (i >= calFrom)
            {
                calRe += m * Math.cos(calW * i);
                calIm += m * s;
                ++calMeas;
            }
        }
        if (calI < calN) return;
        final double fund = 2.0 * Math.sqrt(calRe * calRe + calIm * calIm)
                          / Math.max(1, calMeas);
        // Partial loudness link: a +0.25 dB/dB residual slope keeps the drive
        // knob alive (0.23 with four levels pushed).
        final double link = fourLevel && runDrive > 1.0 ? FOUR_LEVEL_DRIVE_LINK : DRIVE_LINK;
        mScale = ((fund > 0.0) ? runCalAmp / fund : 1.0) * Math.pow(runDrive, link);
        hScale = runHScale;
        biasAmp = runBiasAmp;
        calActive = false;
        if (calRestart)
        {
            calRestart = false;
            startCalibration();
        }
    }

    /**
     * The modulated transport's delay offset for the next frame, in samples:
     * worked out every 16 frames (the sines exact there, the noises at that
     * rate with their inputs scaled to keep their spectra) and followed by a
     * straight line between.
     */
    private double nextTransportMod(double depth)
    {
        if (modAt == 0)
        {
            modFrom = modTo;
            modTo = transportModAhead(depth);
        }
        final double share = (double) (++modAt) / (double) TRANSPORT_CONTROL;
        if (modAt == TRANSPORT_CONTROL) modAt = 0;
        return modFrom + (modTo - modFrom) * share;
    }

    /** Drift + wow + flutter + scrape at the next control point. */
    private double transportModAhead(double depth)
    {
        // Always advance the transport state so engaging the control later
        // does not jump phases; only the output is scaled by depth.
        final double dt = (double) TRANSPORT_CONTROL / sampleRate;
        modPhaseWow += 0.55 * dt;
        modPhaseWow -= Math.floor(modPhaseWow);
        modPhaseFlut += 8.3 * dt;
        modPhaseFlut -= Math.floor(modPhaseFlut);
        modPhaseFlut2 += 23.0 * dt;
        modPhaseFlut2 -= Math.floor(modPhaseFlut2);

        rng = rng * 1664525 + 1013904223;
        // One draw a control step, scaled by 1 / sqrt(16).
        final double n = ((double) (rng >>> 8) / 8388608.0 - 1.0) * 0.25;

        // Slow drift: heavily low-passed random walk, clamped to +/-18 samples.
        driftState = driftA * driftState + (1.0 - driftA) * n * 600.0;
        final double drift = DspMath.clamp(driftState, -18.0, 18.0);

        // Scrape band (about 40-90 Hz): difference of two one-poles on noise.
        scrapeLp1 = scrapeA1 * scrapeLp1 + (1.0 - scrapeA1) * n;
        scrapeLp2 = scrapeA2 * scrapeLp2 + (1.0 - scrapeA2) * n;
        final double scrape = (scrapeLp1 - scrapeLp2) * 0.6;

        if (depth <= 0.0)
            return 0.0;

        final double twoPi = 2.0 * Math.PI;
        // Component amplitudes in delay samples at 48 kHz, scaled to the actual
        // rate. The 0.4 factor calibrates full depth to about 0.6 %
        // peak-to-peak measured pitch deviation.
        final double rateScale = sampleRate / 48000.0;
        final double wow = 27.8 * Math.sin(twoPi * modPhaseWow);
        final double flut = 0.55 * Math.sin(twoPi * modPhaseFlut);
        final double flut2 = 0.066 * Math.sin(twoPi * modPhaseFlut2);

        return 0.4 * depth * rateScale * (drift + wow + flut + flut2 + scrape);
    }

    /** A fixed value in [-1, 1) for a channel and a quantity. */
    private static double alignDraw(int ch, int quantity)
    {
        long x = 0x9E3779B97F4A7C15L * (long) (ch * 3 + quantity + 1);
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return (double) (x >>> 11) * (2.0 / 9007199254740992.0) - 1.0;
    }

    /** Publishes the trims of the tolerance (once per block); whether any applies. */
    private boolean updateAlignment(int channels)
    {
        final double tol = alignTolerance;
        if (tol != alignFor)
        {
            alignFor = tol;
            alignHpA = Math.exp(-2.0 * 3.141592653589793 * ALIGN_SHELF_HZ / sampleRate);
            for (int ch = 0; ch < MAX_ALIGNED; ++ch)
            {
                alignBias[ch] = 1.0 + ALIGN_BIAS * tol * alignDraw(ch, 0);
                alignShelf[ch] = Math.pow(10.0, ALIGN_SHELF_DB * tol * alignDraw(ch, 1) / 20.0) - 1.0;
                alignLevel[ch] = Math.pow(10.0, ALIGN_LEVEL_DB * tol * alignDraw(ch, 2) / 20.0);
            }
        }
        alignActive = tol > 0.0;
        return alignActive;
    }

    // -- Helpers -----------------------------------------------------------------------

    /** The geometric glide of a calibrated value towards its target. */
    private static double glide(double from, double to, double kSm)
    {
        final double e = from * Math.pow(to / from, kSm);
        return Math.abs(Math.log(e / to)) < 1e-9 ? to : e;
    }

    /**
     * Dot product in two-lane pairs: lanes 0 and 1 of two accumulators take
     * the products four at a time, a remaining pair goes to the first
     * accumulator, the accumulators are added lane by lane and then across,
     * and an odd last product is added at the end.
     */
    private static double dotProduct(double[] a, double[] b, int bOff, int count)
    {
        double s00 = 0.0, s01 = 0.0, s10 = 0.0, s11 = 0.0;
        int i = 0;
        for (; i + 3 < count; i += 4)
        {
            s00 = s00 + a[i] * b[bOff + i];
            s01 = s01 + a[i + 1] * b[bOff + i + 1];
            s10 = s10 + a[i + 2] * b[bOff + i + 2];
            s11 = s11 + a[i + 3] * b[bOff + i + 3];
        }
        for (; i + 1 < count; i += 2)
        {
            s00 = s00 + a[i] * b[bOff + i];
            s01 = s01 + a[i + 1] * b[bOff + i + 1];
        }
        double sum = (s00 + s10) + (s01 + s11);
        for (; i < count; i++) sum += a[i] * b[bOff + i];
        return sum;
    }

    /** Moves towards a target by at most a step; the target itself once within reach. */
    private static double moveTowards(double from, double to, double maxDelta)
    {
        final double delta = to - from;
        return Math.abs(delta) <= maxDelta ? to : from + DspMath.clamp(delta, -maxDelta, maxDelta);
    }

    /** Decibels of a gain, floored at -100 (and -100 for a gain of 0 or less). */
    private static double gainToDecibels(double gain)
    {
        return gain > 0.0 ? stdMax(-100.0, 8.6858896380650365530 * Math.log(gain)) : -100.0;
    }

    /** Rounds half away from zero. */
    private static long lround(double x)
    {
        if (x < 0.0) return -lround(-x);
        final double r = Math.floor(x);
        return (long) ((x - r >= 0.5) ? r + 1.0 : r);
    }

    /** The larger of two values, the first one when they compare equal or either is NaN. */
    private static double stdMax(double a, double b)
    {
        return (a < b) ? b : a;
    }

    /** The smaller of two values, the first one when they compare equal or either is NaN. */
    private static double stdMin(double a, double b)
    {
        return (b < a) ? b : a;
    }

    private static int clampInt(int v, int lo, int hi)
    {
        return v < lo ? lo : (hi < v ? hi : v);
    }

    private static Shelf[] shelves(int n)
    {
        final Shelf[] s = new Shelf[n];
        for (int i = 0; i < n; ++i) s[i] = new Shelf();
        return s;
    }

    private static Peak[] peaks(int n)
    {
        final Peak[] p = new Peak[n];
        for (int i = 0; i < n; ++i) p[i] = new Peak();
        return p;
    }
}
