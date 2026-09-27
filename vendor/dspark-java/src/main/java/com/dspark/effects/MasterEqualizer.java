package com.dspark.effects;

import com.dspark.core.Biquad;
import com.dspark.core.BiquadCoeffs;
import com.dspark.core.FFTReal;
import com.dspark.core.WindowFunctions;

import java.util.Arrays;

/**
 * Unified mastering equalizer: a single multi-band processor that subsumes
 * per-channel volume, Mid/Side gain and stereo width, full parametric and
 * tilt EQ, and per-band dynamic EQ — with per-band channel routing and a
 * per-band choice of linear or minimum phase.
 * <p>
 * It merges the two DSPark equalizers ({@code Equalizer}, {@code DynamicEQ})
 * and generalizes them along three new axes:
 * <ul>
 *   <li><b>Channel routing.</b> Each band acts on one domain:
 *       {@link Channel#STEREO}, {@link Channel#LEFT}, {@link Channel#RIGHT},
 *       {@link Channel#MID} or {@link Channel#SIDE}. Mid/Side is the standard
 *       rotation {@code M=(L+R)/2, S=(L-R)/2}.</li>
 *   <li><b>The {@link BandType#GAIN} band.</b> A flat, frequency-independent
 *       gain on its routed domain. This one primitive replaces the old
 *       standalone modules: a GAIN band on {@code LEFT}/{@code RIGHT} is an
 *       L/R volume; on {@code MID}/{@code SIDE} it is Mid/Side gain; a
 *       {@code SIDE} GAIN is a stereo-width control; a {@code STEREO} GAIN is
 *       an overall trim.</li>
 *   <li><b>Dynamics.</b> Any band can be dynamic, with a threshold and
 *       independent <i>above</i> and <i>below</i> behaviours (ratio, attack,
 *       release, range, boost/cut), modelled on DSPark's {@code DynamicEQ}
 *       (VCA ballistics applied to the gain). A dynamic GAIN band is a
 *       broadband compressor/expander on its domain (e.g. dynamic SIDE =
 *       width dynamics).</li>
 * </ul>
 *
 * <h2>Phase</h2>
 * Static bands run in {@link BandPhase#LINEAR} phase by default (zero phase
 * distortion, the mastering-grade choice) and can be switched to
 * {@link BandPhase#MINIMUM} per band for zero latency, lower CPU and more
 * bands. Because a dynamic band is time-varying it cannot be a static FIR, so
 * dynamic bands always run in minimum phase (as do GAIN bands, which are
 * phase-neutral and therefore zero-latency).
 *
 * <h2>Processing model</h2>
 * Each block is processed in two sections that share a single latency:
 * <ol>
 *   <li><b>Minimum-phase section</b> (zero latency): a per-sample loop applies
 *       every GAIN band, every minimum-phase static band and every dynamic
 *       band, each routed to its domain.</li>
 *   <li><b>Linear-phase section</b> (latency {@code = kernel/2}): all
 *       linear-phase static bands are combined into a single 2×2 MIMO FIR via
 *       per-frequency-bin matrix products of their routing matrices, then
 *       applied by overlap-save FFT convolution. This yields exact series
 *       semantics across routings with a constant latency regardless of how
 *       many domains are involved. The kernel length scales with the sample
 *       rate (a constant {@value #KERNEL_SECONDS}-second span), so the
 *       realized curve is the same in Hz at any processing rate.</li>
 * </ol>
 * The per-bin routing matrices are
 * {@code STEREO: B·I}, {@code LEFT: diag(B,1)}, {@code RIGHT: diag(1,B)},
 * {@code MID: ½[[B+1,B−1],[B−1,B+1]]}, {@code SIDE: ½[[1+B,1−B],[1−B,1+B]]},
 * where {@code B(f)} is the band's (zero-phase) magnitude.
 *
 * <h2>Latency</h2>
 * {@link #getLatencyFrames()} is half the linear-phase kernel length when at
 * least one linear-phase static band is active, otherwise 0.
 *
 * <h2>Threading</h2>
 * Single writer (control thread) via {@link #setBand}/{@link #setNumBands};
 * single reader (audio thread) via {@link #process}. Parameter changes set a
 * {@code volatile} dirty flag and are applied at the start of the next block.
 */
public final class MasterEqualizer
{
    /** Default maximum number of bands. */
    public static final int DEFAULT_MAX_BANDS = 16;

    /** Band filter shape. */
    public enum BandType
    {
        /** Flat, frequency-independent gain on the routed domain. */
        GAIN,
        /** Parametric bell (boost/cut around a frequency). */
        BELL,
        LOW_SHELF, HIGH_SHELF,
        /** Low-cut (high-pass). */
        LOW_CUT,
        /** High-cut (low-pass). */
        HIGH_CUT,
        NOTCH,
        /** Tilt: rocks the spectrum around a pivot frequency. */
        TILT
    }

    /** Routing domain a band acts on. */
    public enum Channel { STEREO, LEFT, RIGHT, MID, SIDE }

    /** Per-band phase mode. */
    public enum BandPhase { LINEAR, MINIMUM }

    /**
     * Mutable per-band configuration. Copy in / copy out of the equalizer; the
     * processor stores defensive copies so a live edit never tears a read.
     */
    public static final class Band
    {
        public BandType type   = BandType.BELL;
        public Channel channel = Channel.STEREO;
        public BandPhase phase = BandPhase.LINEAR;

        public double frequency = 1000.0;
        public double gainDb    = 0.0;
        public double q         = BiquadCoeffs.BUTTERWORTH_Q;
        public int slope        = 12;   // dB/oct for LOW_CUT / HIGH_CUT (12 / 24 / 36 / 48)
        public boolean enabled  = true;

        // Dynamics (optional). Above/below are evaluated relative to threshold.
        public boolean dynamic   = false;
        public double threshold  = -20.0;   // dBFS
        public double aboveRatio = 2.0;
        public double aboveAttackMs  = 5.0;
        public double aboveReleaseMs = 80.0;
        public double aboveRangeDb   = 6.0;
        public boolean aboveBoost    = false;   // false = cut above
        public double belowRatio = 1.0;
        public double belowAttackMs  = 10.0;
        public double belowReleaseMs = 120.0;
        public double belowRangeDb   = 6.0;
        public boolean belowBoost    = false;

        public Band() { }

        public Band copy()
        {
            Band b = new Band();
            b.type = type; b.channel = channel; b.phase = phase;
            b.frequency = frequency; b.gainDb = gainDb; b.q = q; b.slope = slope; b.enabled = enabled;
            b.dynamic = dynamic; b.threshold = threshold;
            b.aboveRatio = aboveRatio; b.aboveAttackMs = aboveAttackMs;
            b.aboveReleaseMs = aboveReleaseMs; b.aboveRangeDb = aboveRangeDb; b.aboveBoost = aboveBoost;
            b.belowRatio = belowRatio; b.belowAttackMs = belowAttackMs;
            b.belowReleaseMs = belowReleaseMs; b.belowRangeDb = belowRangeDb; b.belowBoost = belowBoost;
            return b;
        }
    }

    private static final double MIN_ENVELOPE = 1e-12;
    private static final int DYN_COEFF_REFRESH = 16;   // refresh dynamic coeffs every N samples
    private static final double GAIN_SMOOTH_MS = 12.0;

    /** Linear-phase kernel span in seconds (2048 taps at 44.1 kHz, the design anchor). */
    public static final double KERNEL_SECONDS = 2048.0 / 44100.0;
    private static final int MIN_KERNEL = 256;
    private static final int MAX_KERNEL = 32768;

    private final int maxBands;
    private final Band[] configs;
    private final BandState[] states;

    private double sampleRate = 48000.0;
    private int maxBlockSize = 0;
    private int channels = 2;
    private int numBands = 0;

    private volatile boolean dirty = true;

    // Active-band partitions (rebuilt on dirty).
    private int[] minOrder = new int[0];   // GAIN + minimum-phase static + dynamic
    private int[] linOrder = new int[0];   // linear-phase static (non-GAIN, non-dynamic)
    private boolean linActive = false;

    // Reusable per-frame scratch (avoids per-sample allocation).
    private final float[] frame2 = new float[2];

    // ---- Linear-phase (MIMO overlap-save) state ----
    private FFTReal lpFft;
    private int lpFftSize = 0;
    private int kernelLength = 0;          // M = 2 * maxBlockSize
    private float[] kLL, kLR, kRL, kRR;    // frequency-domain kernels (N+2)
    private float[] fftInL, fftInR, freqL, freqR, freqOutL, freqOutR;
    private float[] prevL, prevR;          // overlap history (M-1)
    private double[] window;
    private float[] tmpFreq, tmpImpulse, tmpKernel;   // kernel-build scratch
    private double[] magLL, magLR, magRL, magRR;      // per-bin matrix entries
    private volatile boolean lpDirty = true;

    public MasterEqualizer() { this(DEFAULT_MAX_BANDS); }

    public MasterEqualizer(int maxBands)
    {
        this.maxBands = Math.max(1, maxBands);
        this.configs = new Band[this.maxBands];
        this.states  = new BandState[this.maxBands];
        for (int i = 0; i < this.maxBands; i++)
        {
            configs[i] = new Band();
            states[i]  = new BandState();
        }
    }

    /**
     * Prepares the equalizer.
     *
     * @param sampleRate    sample rate in Hz
     * @param maxBlockSize  maximum frames per {@link #process} call (sizes the
     *                      linear-phase FFT and sets its latency); 0 disables
     *                      linear-phase processing (bands fall back to minimum
     *                      phase)
     * @param channels      channel count (1 or 2)
     */
    public void prepare(double sampleRate, int maxBlockSize, int channels)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        this.maxBlockSize = maxBlockSize;
        this.channels = Math.max(1, channels);

        for (BandState s : states) s.prepare();

        if (maxBlockSize > 0)
        {
            kernelLength = kernelLengthForRate(sampleRate);
            int target = Math.max(kernelLength * 2, maxBlockSize + kernelLength);
            int n = 1;
            while (n < target) n <<= 1;
            lpFftSize = n;
            lpFft = new FFTReal(lpFftSize);
            kLL = new float[lpFftSize + 2];
            kLR = new float[lpFftSize + 2];
            kRL = new float[lpFftSize + 2];
            kRR = new float[lpFftSize + 2];
            fftInL = new float[lpFftSize];
            fftInR = new float[lpFftSize];
            freqL  = new float[lpFftSize + 2];
            freqR  = new float[lpFftSize + 2];
            freqOutL = new float[lpFftSize + 2];
            freqOutR = new float[lpFftSize + 2];
            prevL = new float[kernelLength];
            prevR = new float[kernelLength];
            window = new double[kernelLength];
            WindowFunctions.blackmanHarris(window, kernelLength);
            int bins = lpFftSize / 2 + 1;
            tmpFreq    = new float[lpFftSize + 2];
            tmpImpulse = new float[lpFftSize];
            tmpKernel  = new float[lpFftSize];
            magLL = new double[bins]; magLR = new double[bins];
            magRL = new double[bins]; magRR = new double[bins];
        }
        else
        {
            lpFft = null;
            lpFftSize = 0;
        }

        dirty = true;
        lpDirty = true;
    }

    /**
     * Configures band {@code index} (thread-safe; stores a defensive copy).
     * Non-finite numeric fields are replaced with the band's previous value
     * (a NaN band would zero the linear-phase kernel to silence).
     */
    public void setBand(int index, Band band)
    {
        if (index < 0 || index >= maxBands || band == null) return;
        Band fresh = band.copy();
        sanitizeBand(fresh, configs[index]);
        configs[index] = fresh;
        if (index >= numBands) numBands = index + 1;
        dirty = true;
    }

    /** Replaces every non-finite numeric field with the previous band's value. */
    private static void sanitizeBand(Band b, Band prev)
    {
        if (!Double.isFinite(b.frequency)) b.frequency = prev.frequency;
        if (!Double.isFinite(b.gainDb)) b.gainDb = prev.gainDb;
        if (!Double.isFinite(b.q)) b.q = prev.q;
        if (!Double.isFinite(b.threshold)) b.threshold = prev.threshold;
        if (!Double.isFinite(b.aboveRatio)) b.aboveRatio = prev.aboveRatio;
        if (!Double.isFinite(b.aboveAttackMs)) b.aboveAttackMs = prev.aboveAttackMs;
        if (!Double.isFinite(b.aboveReleaseMs)) b.aboveReleaseMs = prev.aboveReleaseMs;
        if (!Double.isFinite(b.aboveRangeDb)) b.aboveRangeDb = prev.aboveRangeDb;
        if (!Double.isFinite(b.belowRatio)) b.belowRatio = prev.belowRatio;
        if (!Double.isFinite(b.belowAttackMs)) b.belowAttackMs = prev.belowAttackMs;
        if (!Double.isFinite(b.belowReleaseMs)) b.belowReleaseMs = prev.belowReleaseMs;
        if (!Double.isFinite(b.belowRangeDb)) b.belowRangeDb = prev.belowRangeDb;
    }

    /** Returns a copy of band {@code index}'s configuration. */
    public Band getBand(int index)
    {
        return (index >= 0 && index < maxBands) ? configs[index].copy() : null;
    }

    public void setNumBands(int n)
    {
        numBands = Math.max(0, Math.min(n, maxBands));
        dirty = true;
    }

    public int getNumBands() { return numBands; }

    public int getMaxBands() { return maxBands; }

    /** Current dynamic gain (dB) being applied by a band, for metering. */
    public double getBandGainReductionDb(int index)
    {
        if (index < 0 || index >= maxBands) return 0.0;
        return states[index].currentGainDb;
    }

    /** Last detector level (dBFS) a band saw, for the threshold reference. */
    public double getBandDetectorDb(int index)
    {
        if (index < 0 || index >= maxBands) return -100.0;
        return states[index].detectorDb;
    }

    /** Latency in frames: half the linear-phase kernel if any linear band is active, else 0. */
    public int getLatencyFrames()
    {
        if (dirty) rebuildRuntime();
        return (linActive && lpFft != null) ? kernelLength / 2 : 0;
    }

    /**
     * Linear-phase FIR length for a rate: a constant {@value #KERNEL_SECONDS}-second
     * span (2048 taps at 44.1 kHz), even, bounded. Scaling the kernel with the
     * processing rate keeps the realized EQ curve identical in Hz at any rate,
     * including oversampled rates, instead of losing low-frequency resolution
     * as the rate grows.
     */
    private static int kernelLengthForRate(double sampleRate)
    {
        int m = (int) Math.round(KERNEL_SECONDS * Math.max(sampleRate, 1.0));
        if ((m & 1) == 1) m++;
        if (m < MIN_KERNEL) m = MIN_KERNEL;
        if (m > MAX_KERNEL) m = MAX_KERNEL;
        return m;
    }

    /** Clears all filter, detector and overlap state. */
    public void reset()
    {
        for (BandState s : states) s.reset();
        if (prevL != null) { Arrays.fill(prevL, 0.0f); Arrays.fill(prevR, 0.0f); }
        lpDirty = true;
    }

    /**
     * Processes an interleaved buffer in place.
     *
     * @param buffer    interleaved samples ({@code channels} per frame)
     * @param channels  channel count (1 or 2)
     */
    public void process(float[] buffer, int channels)
    {
        if (dirty) rebuildRuntime();

        // 1) Minimum-phase / dynamic / GAIN section (per sample).
        if (channels == 2)
        {
            for (int i = 0; i + 1 < buffer.length; i += 2)
            {
                frame2[0] = buffer[i];
                frame2[1] = buffer[i + 1];
                for (int bi : minOrder) states[bi].processStereoFrame(frame2);
                buffer[i]     = frame2[0];
                buffer[i + 1] = frame2[1];
            }
        }
        else
        {
            for (int i = 0; i < buffer.length; i++)
            {
                float x = buffer[i];
                for (int bi : minOrder) x = states[bi].processMonoSample(x);
                buffer[i] = x;
            }
        }

        // 2) Linear-phase section (block FFT convolution).
        if (linActive && lpFft != null)
        {
            if (lpDirty) { rebuildLinearKernels(); lpDirty = false; }
            if (channels == 2) processLinearStereo(buffer);
            else               processLinearMono(buffer);
        }
    }

    /**
     * Fills {@code magnitudes} with the combined static linear magnitude
     * response of all enabled bands routed to {@code domain}.
     */
    public void getMagnitudeResponse(Channel domain, double[] frequencies, double[] magnitudes)
    {
        getMagnitudeResponse(domain, frequencies, magnitudes, false);
    }

    /**
     * Fills {@code magnitudes} with the combined linear magnitude response of
     * all enabled bands routed to {@code domain}.
     *
     * @param dynamicLive  when {@code true}, each dynamic band's current (live)
     *                     gain modulation is added to its static gain, so the
     *                     curve reflects what the dynamics are doing right now —
     *                     used to draw the moving dynamic curve over the static
     *                     one
     */
    public void getMagnitudeResponse(Channel domain, double[] frequencies, double[] magnitudes,
                                     boolean dynamicLive)
    {
        Arrays.fill(magnitudes, 0, frequencies.length, 1.0);
        for (int b = 0; b < numBands; b++)
        {
            Band cfg = configs[b];
            if (!cfg.enabled || cfg.channel != domain) continue;
            double gain = cfg.gainDb
                    + ((dynamicLive && cfg.dynamic) ? states[b].currentGainDb : 0.0);
            if (cfg.type == BandType.GAIN)
            {
                double g = dbToLin(gain);
                for (int k = 0; k < frequencies.length; k++) magnitudes[k] *= g;
            }
            else if (isCut(cfg.type))
            {
                for (int k = 0; k < frequencies.length; k++)
                    magnitudes[k] *= magnitudeOf(cfg, gain, frequencies[k]);
            }
            else
            {
                BiquadCoeffs c = coeffsFor(cfg, gain);
                for (int k = 0; k < frequencies.length; k++)
                    magnitudes[k] *= c.magnitude(frequencies[k], sampleRate);
            }
        }
    }

    /**
     * Fills {@code magnitudes} with the static magnitude response of a SINGLE band,
     * ignoring its enabled flag — for drawing a muted band's (dimmed) curve.
     */
    public void getBandMagnitudeResponse(int index, double[] frequencies, double[] magnitudes)
    {
        Arrays.fill(magnitudes, 0, frequencies.length, 1.0);
        if (index < 0 || index >= numBands) return;
        Band cfg = configs[index];
        double gain = cfg.gainDb;
        if (cfg.type == BandType.GAIN)
        {
            double g = dbToLin(gain);
            for (int k = 0; k < frequencies.length; k++) magnitudes[k] *= g;
        }
        else if (isCut(cfg.type))
        {
            for (int k = 0; k < frequencies.length; k++)
                magnitudes[k] *= magnitudeOf(cfg, gain, frequencies[k]);
        }
        else
        {
            BiquadCoeffs c = coeffsFor(cfg, gain);
            for (int k = 0; k < frequencies.length; k++)
                magnitudes[k] *= c.magnitude(frequencies[k], sampleRate);
        }
    }

    /** True if any enabled band is dynamic (so the UI knows to draw the live curve). */
    public boolean hasActiveDynamicBand()
    {
        for (int b = 0; b < numBands; b++)
        {
            if (configs[b].enabled && configs[b].dynamic) return true;
        }
        return false;
    }

    /* ====================================================================
     *  Runtime build
     * ==================================================================== */

    private synchronized void rebuildRuntime()
    {
        int[] minTmp = new int[numBands];
        int[] linTmp = new int[numBands];
        int nMin = 0, nLin = 0;

        for (int i = 0; i < numBands; i++)
        {
            Band cfg = configs[i];
            states[i].configure(cfg, sampleRate);
            if (!cfg.enabled) continue;

            boolean linear = cfg.type != BandType.GAIN
                    && !cfg.dynamic
                    && cfg.phase == BandPhase.LINEAR
                    && lpFft != null;
            if (linear) linTmp[nLin++] = i;
            else        minTmp[nMin++] = i;
        }
        minOrder = Arrays.copyOf(minTmp, nMin);
        linOrder = Arrays.copyOf(linTmp, nLin);
        linActive = nLin > 0;
        dirty = false;
        lpDirty = true;
    }

    /** Builds biquad coefficients for a band at a given (possibly dynamic) gain. */
    private BiquadCoeffs coeffsFor(Band b, double gainDb)
    {
        switch (b.type)
        {
            case BELL:       return BiquadCoeffs.peak(sampleRate, b.frequency, b.q, gainDb);
            case LOW_SHELF:  return BiquadCoeffs.lowShelf(sampleRate, b.frequency, gainDb, 1.0);
            case HIGH_SHELF: return BiquadCoeffs.highShelf(sampleRate, b.frequency, gainDb, 1.0);
            case LOW_CUT:    return BiquadCoeffs.highPass(sampleRate, b.frequency, b.q);
            case HIGH_CUT:   return BiquadCoeffs.lowPass(sampleRate, b.frequency, b.q);
            // Notch = a narrow, depth-adjustable cut (high-Q bell with negative gain),
            // so the user can dial how deep it goes instead of a fixed full null.
            case NOTCH:      return BiquadCoeffs.peak(sampleRate, b.frequency, b.q, gainDb);
            case TILT:       return BiquadCoeffs.tilt(sampleRate, b.frequency, gainDb);
            default:         return BiquadCoeffs.IDENTITY;
        }
    }

    private static boolean gainBearing(BandType t)
    {
        return t == BandType.GAIN || t == BandType.BELL || t == BandType.LOW_SHELF
                || t == BandType.HIGH_SHELF || t == BandType.TILT || t == BandType.NOTCH;
    }

    private static boolean isCut(BandType t)
    {
        return t == BandType.LOW_CUT || t == BandType.HIGH_CUT;
    }

    /** Number of biquad sections for a cut slope (12/24/36/48 dB/oct → 1..4). */
    private static int cutSectionCount(int slopeDbOct)
    {
        return Math.max(1, Math.min(4, slopeDbOct / 12));
    }

    /**
     * Q of section {@code k} of {@code n} for a Butterworth cut cascade. The
     * user's Q sets the corner resonance (highest-Q section); a single section
     * (12 dB/oct) uses the user Q directly, preserving the original behaviour.
     */
    private static double cutSectionQ(Band b, int n, int k)
    {
        if (n == 1) return b.q;
        double butter = 1.0 / (2.0 * Math.cos(Math.PI * (2 * k + 1) / (4.0 * n)));
        return (k == n - 1) ? Math.max(butter, b.q) : butter;
    }

    /** First-order (6 dB/oct) cut section for the band's type. */
    private BiquadCoeffs firstOrderCut(Band b)
    {
        return (b.type == BandType.LOW_CUT)
                ? BiquadCoeffs.firstOrderHighPass(sampleRate, b.frequency)
                : BiquadCoeffs.firstOrderLowPass(sampleRate, b.frequency);
    }

    /** Builds the cut cascade coefficients for the band. */
    private BiquadCoeffs[] cutSections(Band b)
    {
        if (b.slope <= 6) return new BiquadCoeffs[] { firstOrderCut(b) };   // 6 dB/oct = single 1-pole
        int n = cutSectionCount(b.slope);
        boolean low = b.type == BandType.LOW_CUT;
        BiquadCoeffs[] cs = new BiquadCoeffs[n];
        for (int k = 0; k < n; k++)
        {
            double q = cutSectionQ(b, n, k);
            cs[k] = low ? BiquadCoeffs.highPass(sampleRate, b.frequency, q)
                        : BiquadCoeffs.lowPass(sampleRate, b.frequency, q);
        }
        return cs;
    }

    /** Linear magnitude of a band at {@code freq}, honouring cut slopes (cascade). */
    private double magnitudeOf(Band b, double gainDb, double freq)
    {
        if (isCut(b.type))
        {
            if (b.slope <= 6) return firstOrderCut(b).magnitude(freq, sampleRate);
            int n = cutSectionCount(b.slope);
            boolean low = b.type == BandType.LOW_CUT;
            double m = 1.0;
            for (int k = 0; k < n; k++)
            {
                double q = cutSectionQ(b, n, k);
                BiquadCoeffs c = low ? BiquadCoeffs.highPass(sampleRate, b.frequency, q)
                                     : BiquadCoeffs.lowPass(sampleRate, b.frequency, q);
                m *= c.magnitude(freq, sampleRate);
            }
            return m;
        }
        return coeffsFor(b, gainDb).magnitude(freq, sampleRate);
    }

    /* ====================================================================
     *  Per-band runtime state
     * ==================================================================== */

    private final class BandState
    {
        private Band cfg = new Band();
        private Biquad[] stages = { new Biquad(2) };   // filter cascade: 1 section, or N for cut slopes
        private final Biquad detector = new Biquad(2);

        // GAIN smoothing (linear domain).
        private double gainCurrent = 1.0;
        private double gainTarget = 1.0;
        private double gainCoeff = 1.0;

        // Dynamics.
        private double currentGainDb = 0.0;
        private double aboveGainDb = 0.0, belowGainDb = 0.0;   // independent above/below envelopes
        private double detectorDb = -100.0;     // last detector level (for the threshold reference)
        private double aboveAtk, aboveRel, belowAtk, belowRel;
        private double detEnv = 0.0, prevDetEnv = 0.0, detAtk, detRel;   // detector envelope follower
        private int dynCounter = 0;

        void prepare()
        {
            for (Biquad s : stages) s.reset();
            detector.reset();
            gainCurrent = gainTarget;
            currentGainDb = 0.0;
            aboveGainDb = 0.0;
            belowGainDb = 0.0;
            detEnv = 0.0;
            prevDetEnv = 0.0;
            dynCounter = 0;
        }

        void reset()
        {
            for (Biquad s : stages) s.reset();
            detector.reset();
            currentGainDb = 0.0;
            aboveGainDb = 0.0;
            belowGainDb = 0.0;
            detEnv = 0.0;
            prevDetEnv = 0.0;
            gainCurrent = gainTarget;
            dynCounter = 0;
        }

        /** Replaces the filter cascade, allocating sections only when the count changes. */
        private void setStages(BiquadCoeffs[] cs)
        {
            if (stages.length != cs.length)
            {
                stages = new Biquad[cs.length];
                for (int i = 0; i < cs.length; i++) stages[i] = new Biquad(2);
            }
            for (int i = 0; i < cs.length; i++) stages[i].setCoeffs(cs[i]);
        }

        /** Runs a sample through the whole cascade on channel {@code ch}. */
        private double runStages(double x, int ch)
        {
            for (Biquad s : stages) x = s.processSample((float) x, ch);
            return x;
        }

        /** Applies a config and recomputes static coefficients / ballistics. */
        void configure(Band c, double sr)
        {
            this.cfg = c;

            if (c.type == BandType.GAIN)
            {
                gainTarget = dbToLin(c.gainDb);
                // Snap on (re)configure so a freshly set static gain is exact
                // (no ramp), which keeps GAIN bands numerically equivalent to a
                // plain multiply; live ramping still applies on later edits.
                gainCurrent = gainTarget;
                gainCoeff = 1.0 - Math.exp(-1.0 / (Math.max(sr, 1.0) * GAIN_SMOOTH_MS / 1000.0));
            }
            else
            {
                // Cut bands are a Butterworth cascade for the chosen slope and
                // stay static (dynamics don't apply to a cut). Other non-GAIN
                // bands are a single section; for a dynamic gain-bearing band,
                // section 0 is re-set per block by updateDynamics.
                if (isCut(c.type))
                    setStages(cutSections(c));
                else
                    setStages(new BiquadCoeffs[] { coeffsFor(c, c.gainDb) });
            }

            if (c.dynamic)
            {
                // Detector: band-pass at the band frequency, except a GAIN band
                // detects broadband (its level is the raw domain signal).
                if (c.type != BandType.GAIN)
                    detector.setCoeffs(BiquadCoeffs.bandPass(sr, c.frequency, c.q));
                aboveAtk = ballistic(c.aboveAttackMs, sr);
                aboveRel = ballistic(c.aboveReleaseMs, sr);
                belowAtk = ballistic(c.belowAttackMs, sr);
                belowRel = ballistic(c.belowReleaseMs, sr);
                detAtk = ballistic(1.0, sr);    // fast detector envelope (de-chatter)
                detRel = ballistic(25.0, sr);
            }
        }

        /** Processes one stereo frame ({@code lr[0]=L, lr[1]=R}) in place. */
        void processStereoFrame(float[] lr)
        {
            double L = lr[0], R = lr[1];

            if (cfg.dynamic) updateDynamics(L, R);

            switch (cfg.type)
            {
                case GAIN:
                {
                    double g = gainCurrent;
                    if (gainCurrent != gainTarget) g = (gainCurrent += gainCoeff * (gainTarget - gainCurrent));
                    if (cfg.dynamic) g *= dbToLin(currentGainDb);
                    switch (cfg.channel)
                    {
                        case STEREO: L *= g; R *= g; break;
                        case LEFT:   L *= g; break;
                        case RIGHT:  R *= g; break;
                        case MID:  { double m = (L + R) * 0.5 * g, s = (L - R) * 0.5; L = m + s; R = m - s; break; }
                        case SIDE: { double m = (L + R) * 0.5, s = (L - R) * 0.5 * g; L = m + s; R = m - s; break; }
                    }
                    break;
                }
                default:
                {
                    switch (cfg.channel)
                    {
                        case STEREO: L = runStages(L, 0); R = runStages(R, 1); break;
                        case LEFT:   L = runStages(L, 0); break;
                        case RIGHT:  R = runStages(R, 0); break;
                        case MID:  { double m = (L + R) * 0.5, s = (L - R) * 0.5; m = runStages(m, 0); L = m + s; R = m - s; break; }
                        case SIDE: { double m = (L + R) * 0.5, s = (L - R) * 0.5; s = runStages(s, 0); L = m + s; R = m - s; break; }
                    }
                }
            }
            lr[0] = (float) L;
            lr[1] = (float) R;
        }

        /** Processes one mono sample. RIGHT/SIDE routings are inert in mono. */
        float processMonoSample(float x)
        {
            if (cfg.channel == Channel.RIGHT || cfg.channel == Channel.SIDE) return x;
            if (cfg.dynamic) updateDynamics(x, x);

            if (cfg.type == BandType.GAIN)
            {
                double g = gainCurrent;
                if (gainCurrent != gainTarget) g = (gainCurrent += gainCoeff * (gainTarget - gainCurrent));
                if (cfg.dynamic) g *= dbToLin(currentGainDb);
                return (float) (x * g);
            }
            return (float) runStages(x, 0);
        }

        /** Detects level on the routed domain and advances the VCA gain. */
        private void updateDynamics(double L, double R)
        {
            double rect;
            if (cfg.type == BandType.GAIN)
            {
                rect = Math.abs(domainValue(L, R));
            }
            else if (cfg.channel == Channel.STEREO)
            {
                double dl = Math.abs(detector.processSample((float) L, 0));
                double dr = Math.abs(detector.processSample((float) R, 1));
                rect = Math.max(dl, dr);
            }
            else
            {
                rect = Math.abs(detector.processSample((float) domainValue(L, R), 0));
            }
            // Envelope-follow the rectified detector so the threshold comparison
            // is stable — the raw band-pass output is a per-cycle oscillation that
            // would otherwise cross the threshold twice every cycle.
            detEnv += ((rect > detEnv) ? detAtk : detRel) * (rect - detEnv);
            double levelDb = gainDbOf(detEnv);
            detectorDb = levelDb;

            // Two independent envelopes (above / below the threshold), summed.
            // Attack when the detector level is RISING (a transient), release when
            // FALLING — so a transient quickly drops a BELOW boost (attack) just as
            // it quickly engages an ABOVE cut (attack), and both recover on release.
            boolean rising = detEnv >= prevDetEnv;
            prevDetEnv = detEnv;

            double aboveTarget = 0.0;
            if (levelDb > cfg.threshold && cfg.aboveRatio > 1.001 && cfg.aboveRangeDb > 0.0)
            {
                double over = levelDb - cfg.threshold;
                double amount = Math.min(over * (1.0 - 1.0 / cfg.aboveRatio), cfg.aboveRangeDb);
                aboveTarget = cfg.aboveBoost ? amount : -amount;
            }
            aboveGainDb += (rising ? aboveAtk : aboveRel) * (aboveTarget - aboveGainDb);

            double belowTarget = 0.0;
            if (levelDb <= cfg.threshold && cfg.belowRatio > 1.001 && cfg.belowRangeDb > 0.0)
            {
                double under = cfg.threshold - levelDb;
                double amount = Math.min(under * (1.0 - 1.0 / cfg.belowRatio), cfg.belowRangeDb);
                belowTarget = cfg.belowBoost ? amount : -amount;
            }
            belowGainDb += (rising ? belowAtk : belowRel) * (belowTarget - belowGainDb);

            currentGainDb = aboveGainDb + belowGainDb;

            if (cfg.type != BandType.GAIN && gainBearing(cfg.type))
            {
                if ((dynCounter++ & (DYN_COEFF_REFRESH - 1)) == 0)
                    stages[0].setCoeffs(coeffsFor(cfg, cfg.gainDb + currentGainDb));
            }
        }

        private double domainValue(double L, double R)
        {
            switch (cfg.channel)
            {
                case LEFT:  return L;
                case RIGHT: return R;
                case MID:   return (L + R) * 0.5;
                case SIDE:  return (L - R) * 0.5;
                default:    return Math.max(Math.abs(L), Math.abs(R));   // STEREO: broadband level
            }
        }
    }

    private static double ballistic(double ms, double sr)
    {
        double tau = Math.max(ms, 0.01) / 1000.0;
        return 1.0 - Math.exp(-1.0 / (sr * tau));
    }

    private static double gainDbOf(double linear)
    {
        return 20.0 * Math.log10(Math.max(linear, MIN_ENVELOPE));
    }

    private static double dbToLin(double db) { return Math.pow(10.0, db / 20.0); }

    /* ====================================================================
     *  Linear-phase MIMO section
     * ==================================================================== */

    private void rebuildLinearKernels()
    {
        int bins = lpFftSize / 2 + 1;

        if (channels == 2)
        {
            for (int k = 0; k < bins; k++)
            {
                double freq = sampleRate * k / lpFftSize;
                // 2x2 matrix, start at identity, left-multiply each band in order.
                double mLL = 1, mLR = 0, mRL = 0, mRR = 1;
                for (int bi : linOrder)
                {
                    double b = magnitudeOf(configs[bi], configs[bi].gainDb, freq);
                    double rLL, rLR, rRL, rRR;
                    switch (configs[bi].channel)
                    {
                        case LEFT:   rLL = b; rLR = 0;       rRL = 0;       rRR = 1;       break;
                        case RIGHT:  rLL = 1; rLR = 0;       rRL = 0;       rRR = b;       break;
                        case MID:    rLL = 0.5*(b+1); rLR = 0.5*(b-1); rRL = 0.5*(b-1); rRR = 0.5*(b+1); break;
                        case SIDE:   rLL = 0.5*(1+b); rLR = 0.5*(1-b); rRL = 0.5*(1-b); rRR = 0.5*(1+b); break;
                        default:     rLL = b; rLR = 0;       rRL = 0;       rRR = b;       break; // STEREO
                    }
                    // new = R * current
                    double nLL = rLL*mLL + rLR*mRL;
                    double nLR = rLL*mLR + rLR*mRR;
                    double nRL = rRL*mLL + rRR*mRL;
                    double nRR = rRL*mLR + rRR*mRR;
                    mLL = nLL; mLR = nLR; mRL = nRL; mRR = nRR;
                }
                magLL[k] = mLL; magLR[k] = mLR; magRL[k] = mRL; magRR[k] = mRR;
            }
            buildKernelFromMagnitudes(magLL, kLL);
            buildKernelFromMagnitudes(magLR, kLR);
            buildKernelFromMagnitudes(magRL, kRL);
            buildKernelFromMagnitudes(magRR, kRR);
        }
        else
        {
            for (int k = 0; k < bins; k++)
            {
                double freq = sampleRate * k / lpFftSize;
                double m = 1.0;
                for (int bi : linOrder)
                {
                    Channel ch = configs[bi].channel;
                    if (ch == Channel.RIGHT || ch == Channel.SIDE) continue; // inert in mono
                    m *= magnitudeOf(configs[bi], configs[bi].gainDb, freq);
                }
                magLL[k] = m;
            }
            buildKernelFromMagnitudes(magLL, kLL);
        }
    }

    /** magnitude spectrum (real, zero-phase) → causal windowed FIR → freq-domain kernel. */
    private void buildKernelFromMagnitudes(double[] mag, float[] kernelOut)
    {
        int bins = lpFftSize / 2 + 1;
        Arrays.fill(tmpFreq, 0.0f);
        for (int k = 0; k < bins; k++) tmpFreq[2 * k] = (float) mag[k];   // imag = 0

        lpFft.inverse(tmpFreq, tmpImpulse);

        int m = kernelLength;
        int halfM = m / 2;
        Arrays.fill(tmpKernel, 0.0f);
        for (int i = 0; i < m; i++)
        {
            int readIdx = ((i - halfM) % lpFftSize + lpFftSize) % lpFftSize;
            tmpKernel[i] = (float) (tmpImpulse[readIdx] * window[i]);
        }
        lpFft.forward(tmpKernel, kernelOut);
    }

    private void processLinearStereo(float[] buffer)
    {
        int frames = buffer.length / 2;
        if (frames > maxBlockSize) return;
        int n = lpFftSize, m = kernelLength, overlap = m - 1, offset = m - 1;
        int bins = n / 2 + 1;

        // Build [history | current | zeros] for both channels; save next history.
        for (int i = 0; i < overlap; i++) { fftInL[i] = prevL[i]; fftInR[i] = prevR[i]; }
        for (int i = 0; i < frames; i++) { fftInL[overlap + i] = buffer[2 * i]; fftInR[overlap + i] = buffer[2 * i + 1]; }
        for (int i = overlap + frames; i < n; i++) { fftInL[i] = 0.0f; fftInR[i] = 0.0f; }
        for (int i = 0; i < overlap; i++) { prevL[i] = fftInL[frames + i]; prevR[i] = fftInR[frames + i]; }

        lpFft.forward(fftInL, freqL);
        lpFft.forward(fftInR, freqR);

        // Y_L = H_LL·X_L + H_LR·X_R ; Y_R = H_RL·X_L + H_RR·X_R (complex per bin).
        for (int k = 0; k < bins; k++)
        {
            int re = 2 * k, im = 2 * k + 1;
            float xlr = freqL[re], xli = freqL[im], xrr = freqR[re], xri = freqR[im];

            freqOutL[re] = cmulRe(kLL[re], kLL[im], xlr, xli) + cmulRe(kLR[re], kLR[im], xrr, xri);
            freqOutL[im] = cmulIm(kLL[re], kLL[im], xlr, xli) + cmulIm(kLR[re], kLR[im], xrr, xri);
            freqOutR[re] = cmulRe(kRL[re], kRL[im], xlr, xli) + cmulRe(kRR[re], kRR[im], xrr, xri);
            freqOutR[im] = cmulIm(kRL[re], kRL[im], xlr, xli) + cmulIm(kRR[re], kRR[im], xrr, xri);
        }

        lpFft.inverse(freqOutL, fftInL);
        lpFft.inverse(freqOutR, fftInR);
        for (int i = 0; i < frames; i++)
        {
            buffer[2 * i]     = fftInL[offset + i];
            buffer[2 * i + 1] = fftInR[offset + i];
        }
    }

    private void processLinearMono(float[] buffer)
    {
        int frames = buffer.length;
        if (frames > maxBlockSize) return;
        int n = lpFftSize, m = kernelLength, overlap = m - 1, offset = m - 1;
        int bins = n / 2 + 1;

        for (int i = 0; i < overlap; i++) fftInL[i] = prevL[i];
        for (int i = 0; i < frames; i++) fftInL[overlap + i] = buffer[i];
        for (int i = overlap + frames; i < n; i++) fftInL[i] = 0.0f;
        for (int i = 0; i < overlap; i++) prevL[i] = fftInL[frames + i];

        lpFft.forward(fftInL, freqL);
        for (int k = 0; k < bins; k++)
        {
            int re = 2 * k, im = 2 * k + 1;
            float xr = freqL[re], xi = freqL[im];
            freqOutL[re] = cmulRe(kLL[re], kLL[im], xr, xi);
            freqOutL[im] = cmulIm(kLL[re], kLL[im], xr, xi);
        }
        lpFft.inverse(freqOutL, fftInL);
        for (int i = 0; i < frames; i++) buffer[i] = fftInL[offset + i];
    }

    private static float cmulRe(float ar, float ai, float br, float bi) { return ar * br - ai * bi; }
    private static float cmulIm(float ar, float ai, float br, float bi) { return ar * bi + ai * br; }
}
