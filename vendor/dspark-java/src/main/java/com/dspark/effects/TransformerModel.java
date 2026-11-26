package com.dspark.effects;

import com.dspark.core.BiquadCoeffs;
import com.dspark.core.BiquadDesign;
import com.dspark.core.DryPhase;
import com.dspark.core.Hysteresis;
import com.dspark.core.Oversampling;

/**
 * Audio transformer: core hysteresis on the flux, LF bloom, HF bell.
 * <p>
 * What makes a transformer sound like a transformer is <em>where</em> its
 * nonlinearity lives: the core saturates on magnetic FLUX, which is the
 * time-integral of the winding voltage. At equal level, low frequencies
 * integrate to much larger flux than highs (Phi ~ V/f), so distortion and
 * compression concentrate in the low end - the classic "iron" low-frequency
 * bloom - while the top stays clean.
 * <p>
 * The model implements that physics directly:
 * <pre>
 *   in -&gt; leaky trapezoidal integrator (flux) -&gt; Jiles-Atherton hysteresis
 *      -&gt; exact algebraic inverse differentiator -&gt; magnetizing-corner
 *        high-pass (finite Lm) -&gt; leakage/capacitance HF bell -&gt; out
 * </pre>
 * The integrator/differentiator pair is an exact algebraic inverse, so the
 * linear part of the JA loop passes transparently; everything you hear is the
 * core's hysteresis acting on flux, with the loop's odd-dominant saturation,
 * remanence memory and rate-dependent loss. The JA core is the one shared with
 * tape modelling ({@link Hysteresis}), parameterised for an unbiased iron core
 * (wider loop than biased tape).
 * <ul>
 *   <li><b>coreSize</b> moves the magnetizing-inductance corner (40 Hz small
 *   core to 5 Hz big core) and scales the flux headroom the same way real iron
 *   does: big cores ring lower and take more level before saturating.</li>
 *   <li><b>resonance</b> raises the leakage-inductance/capacitance bell
 *   (Jensen-style), mapped into the audible band near the top octave.</li>
 *   <li>Loudness is calibrated empirically at PROGRAM level (100 Hz, -12 dBFS,
 *   the flux regime music drives the core into): drive changes iron, not
 *   volume. Gain changes are ramped (~50 ms), which matters doubly here
 *   because the model differentiates its output.</li>
 * </ul>
 * Calibration: {@link #prepare} calibrates the parameters set so far before
 * it returns, so the first sample already runs at the calibrated (drive,
 * makeup) pair. A parameter change made after {@code prepare()} takes effect
 * at the next {@link #process} call: the linear parts (corner, leak, bell)
 * move at once, and the (drive, makeup) pair is re-calibrated on a scratch
 * core spread over the following blocks (one internal sample per internal
 * sample of audio, about 40 ms of audio), adopted when it completes and
 * reached through the geometric ramps.
 * <p>
 * The core runs at 2x oversampling by default, configurable with
 * {@link #setOversampling} (1 = off, zero latency; 2, 4, 8, 16 = the
 * oversampler's group delay, 64 samples at 2x and 70 at 4x), reported by
 * {@link #getLatency}, with the dry path of the mix delayed to match. The
 * loop's harmonics fall with frequency as flux does, but at the top of the
 * band they are not small: over tones from 1 to 15 kHz at -6 and -18 dBFS and
 * every core size, the worst harmonic folded back below 20 kHz at 48 kHz
 * measures -61.3 dB at the default drive and -37.5 dB at +24 dB at 1x (a
 * 10.1 kHz tone's third, at 17.7 kHz), -83.4 and -59.4 dB at 2x, -71 dB at
 * +24 dB at 4x. Unlike the biased tape core, the leaky flux integrator
 * continuously re-centres the loop, so the response is history-independent.
 * <p>
 * Not thread-safe: setters take effect at the next {@link #process} call.
 * {@link #prepare} allocates; invalid specs are ignored and an unprepared
 * instance passes audio through. The dry/wet mix is ramped at no more than
 * full scale per 20 ms, whatever the block size. Channels beyond the prepared
 * count pass through untouched, and so do those beyond
 * {@link Oversampling#MAX_CHANNELS} while the core is oversampled.
 */
public final class TransformerModel
{
    /** Differentiator pole damping. */
    private static final double DIFF_RHO = 0.974;

    /** Rate the automatic factor reaches. */
    private static final double AUTOMATIC_INTERNAL_RATE = 88200.0;

    /** Unbiased iron: Jiles-Atherton Ms, a, alpha, k, c (wider loop than biased tape). */
    private static final double CORE_MS = 3.5e5;
    private static final double CORE_A = 2.2e4;
    private static final double CORE_ALPHA = 1.6e-3;
    private static final double CORE_K = 3.2e4;
    private static final double CORE_C = 0.25;

    /** One second-order section of the leakage/capacitance bell (transposed direct form II). */
    private static final class BellSection
    {
        double b0 = 1.0;
        double b1 = 0.0;
        double b2 = 0.0;
        double a1 = 0.0;
        double a2 = 0.0;
        double z1 = 0.0;
        double z2 = 0.0;
    }

    /** Per-channel circuit state. */
    private static final class ChannelState
    {
        final Hysteresis hyst = new Hysteresis();
        double flux = 0.0;
        double vPrev = 0.0;      // previous input (integrator)
        double vPrev2 = 0.0;     // previous output (differentiator)
        double mPrev = 0.0;
        double hpX = 0.0;
        double hpY = 0.0;
        final BellSection bell = new BellSection();
    }

    // Last valid spec, for setOversampling().
    private double specSampleRate = 0.0;
    private int specNumChannels = 0;
    private int specMaxBlockSize = 0;

    private double sampleRate = 48000.0;   // INTERNAL rate: factor x base rate
    private int numChannels = 0;
    private int maxBlock = 1;
    private int osFactor = 2;              // oversampling factor (1 = off)
    private boolean osAutomatic = false;   // the factor follows the host rate
    private int latency = 0;
    private Oversampling oversampler = null;
    private double[][] dryRing = new double[0][];   // per-channel dry history
    private int drySize = 1;
    private int dryPos = 0;
    private final DryPhase dryLead = new DryPhase();   // the lead of the magnetizing corner on the dry side
    private boolean prepared = false;

    private ChannelState[] channels = new ChannelState[0];

    private double leak = 0.999;
    private double hpA = 0.999;
    private double halfT = 0.5 / 48000.0;
    private double invHalfT = 96000.0;
    private double hScale = 1.0;
    private double mScale = 1.0;
    private double hScaleSm = -1.0;   // anti-zipper ramp states (-1 = seed on
    private double mScaleSm = -1.0;   //  first block after prepare/reset)
    private double currentMix = 1.0;  // mix ramp state
    private double mixMaxStep = 1.0 / 960.0;   // mix ramp rate: full scale per 20 ms

    private double driveDb = 0.0;
    private double coreSize = 0.5;
    private double resonance = 0.3;
    private double mix = 1.0;
    private boolean dirty = true;

    // Incremental calibration (after prepare()).
    private final Hysteresis cal = new Hysteresis();
    private double calReqH = 1.0;
    private double calReqLeak = 0.999;
    private double calH = 1.0;
    private double calLeak = 0.999;
    private double calFlux = 0.0;
    private double calVPrev = 0.0;
    private double calMPrev = 0.0;
    private double calVPrev2 = 0.0;
    private double calInSq = 0.0;
    private double calOutSq = 0.0;
    private int calN = 0;
    private int calI = 0;
    private boolean calActive = false;
    private boolean calRestart = false;

    // -- Lifecycle ---------------------------------------------------------------

    /**
     * Allocates the per-channel circuit state, builds the oversampler and
     * calibrates the current parameters. Invalid specs (non-positive or
     * non-finite rate, non-positive block size or channel count) are ignored:
     * the previous state is kept and an unprepared instance stays
     * pass-through.
     *
     * @param sampleRate    host sample rate in Hz
     * @param numChannels   channels to process
     * @param maxBlockSize  largest block per call (longer blocks are processed in pieces)
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize)
    {
        if (!(sampleRate > 0.0 && sampleRate <= Double.MAX_VALUE && maxBlockSize > 0 && numChannels > 0))
            return;
        prepared = false;
        specSampleRate = sampleRate;
        specNumChannels = numChannels;
        specMaxBlockSize = maxBlockSize;
        if (osAutomatic)
            osFactor = Oversampling.factorForRate(sampleRate, AUTOMATIC_INTERNAL_RATE);
        // Everything but the mix runs at the internal rate: the active
        // oversampling factor times the base rate.
        this.sampleRate = (double) osFactor * sampleRate;
        mixMaxStep = 1.0 / stdMax(1.0, sampleRate * 0.02);
        this.numChannels = numChannels;
        maxBlock = Math.max(maxBlockSize, 1);

        if (osFactor > 1)
        {
            oversampler = new Oversampling(osFactor, Oversampling.Quality.HIGH);
            oversampler.prepare(sampleRate, numChannels, maxBlockSize);
        }
        else
        {
            oversampler = null;
        }
        latency = oversampler != null ? oversampler.getLatency() : 0;
        drySize = 1;
        while (drySize < latency + maxBlock + 1) drySize <<= 1;
        dryRing = new double[numChannels][drySize];
        dryPos = 0;
        dryLead.prepare(sampleRate, numChannels);

        channels = new ChannelState[numChannels];
        for (int ch = 0; ch < numChannels; ch++)
        {
            final ChannelState st = new ChannelState();
            st.hyst.prepare(this.sampleRate);
            // Constant material: set once here, not on every recompute.
            st.hyst.setParameters(CORE_MS, CORE_A, CORE_ALPHA, CORE_K, CORE_C);
            channels[ch] = st;
        }

        // The first calibration runs here, to completion; later parameter
        // changes calibrate incrementally inside process().
        dirty = false;
        recomputeLinear();
        calActive = false;
        calRestart = false;
        startCalibration();
        stepCalibration(calN);
        prepared = true;
        reset();
    }

    /** Clears all signal state. The parameters and their calibration stay. */
    public void reset()
    {
        if (!prepared) return;
        for (ChannelState ch : channels)
        {
            ch.hyst.reset();
            ch.flux = 0.0;
            ch.vPrev = 0.0;
            ch.vPrev2 = 0.0;
            ch.mPrev = 0.0;
            ch.hpX = 0.0;
            ch.hpY = 0.0;
            // State only: the bell keeps its coefficients.
            ch.bell.z1 = 0.0;
            ch.bell.z2 = 0.0;
        }
        for (double[] d : dryRing)
            java.util.Arrays.fill(d, 0.0);
        dryPos = 0;
        if (oversampler != null) oversampler.reset();
        // Seed the anti-zipper ramps at their targets: no fade-in on start.
        hScaleSm = -1.0;
        mScaleSm = -1.0;
        currentMix = mix;
        dryLead.aim(currentMix > 0.0);
        dryLead.reset();
        dryLead.land();
    }

    // -- Parameters --------------------------------------------------------------

    /**
     * Core drive in dB; loudness-compensated. Non-finite values are ignored.
     *
     * @param db drive in dB, clamped to [-12, +24] (default 0)
     */
    public void setDrive(double db)
    {
        if (!Double.isFinite(db)) return;
        driveDb = clamp(db, -12.0, 24.0);
        dirty = true;
    }

    /**
     * Core size: small chokes at 40 Hz and saturates early, big rings to 5 Hz
     * with more low-end headroom. Non-finite values are ignored.
     *
     * @param size core size, clamped to [0, 1] (default 0.5)
     */
    public void setCoreSize(double size)
    {
        if (!Double.isFinite(size)) return;
        coreSize = clamp(size, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Leakage/capacitance HF bell amount (Jensen-style). Non-finite values are
     * ignored.
     *
     * @param amount bell amount, clamped to [0, 1] (default 0.3)
     */
    public void setResonance(double amount)
    {
        if (!Double.isFinite(amount)) return;
        resonance = clamp(amount, 0.0, 1.0);
        dirty = true;
    }

    /**
     * Dry/wet mix; the dry path is delayed to {@link #getLatency} and the mix
     * is ramped over at least 20 ms. Between the two ends the dry side leads
     * as the magnetizing corner does, by phase alone, so a partial mix loses
     * no more than 0.1 dB to phase from 20 Hz up; at 0 it is the delayed input
     * to the bit. Non-finite values are ignored.
     *
     * @param mix wet share, clamped to [0, 1] (default 1)
     */
    public void setMix(double mix)
    {
        if (!Double.isFinite(mix)) return;
        this.mix = clamp(mix, 0.0, 1.0);
    }

    /** @return the drive in dB */
    public double getDrive() { return driveDb; }

    /** @return the core size, 0 to 1 */
    public double getCoreSize() { return coreSize; }

    /** @return the resonance, 0 to 1 */
    public double getResonance() { return resonance; }

    /** @return the dry/wet mix, 0 to 1 */
    public double getMix() { return mix; }

    /**
     * Configures internal oversampling of the core. It reallocates the
     * filters and re-runs the calibration exactly like {@link #prepare} when
     * the instance is prepared, and turns automatic oversampling off.
     *
     * @param factor power of two in {1, 2, 4, 8, 16}. 1 = off (zero latency;
     *               the loop's harmonics above the base Nyquist then fold into
     *               the band). 2 is the default. Other values are ignored.
     *               {@link #getLatency} reflects the new factor after this call.
     */
    public void setOversampling(int factor)
    {
        if (factor < 1 || factor > 16 || (factor & (factor - 1)) != 0) return;
        if (factor == osFactor && !osAutomatic) return;
        osAutomatic = false;
        osFactor = factor;
        if (prepared)
            prepare(specSampleRate, specNumChannels, specMaxBlockSize);
    }

    /**
     * Lets the host rate choose the factor. With it on, every
     * {@link #prepare} takes the smallest factor that brings the core to an
     * internal rate of at least 88.2 kHz: 2x at 44.1 and 48 kHz (the default
     * factor) and none from 88.2 kHz up. The nonlinearity then runs at about
     * the same rate whatever the session rate. Off by default; an explicit
     * {@link #setOversampling} turns it off. Turned on while prepared, it
     * re-prepares at once.
     *
     * @param on true to choose the factor from the rate
     */
    public void setAutomaticOversampling(boolean on)
    {
        if (on == osAutomatic) return;
        osAutomatic = on;
        if (on && prepared)
            prepare(specSampleRate, specNumChannels, specMaxBlockSize);
    }

    /** @return whether the factor follows the host rate ({@link #setAutomaticOversampling}) */
    public boolean isOversamplingAutomatic() { return osAutomatic; }

    /** @return the active oversampling factor (1 = off, 2 = default) */
    public int getOversamplingFactor() { return osFactor; }

    /**
     * Latency in samples: the active oversampler's group delay (0 at 1x). The
     * model itself is minimum-phase IIR and memoryless Newton-Raphson.
     *
     * @return the latency in host-rate samples
     */
    public int getLatency() { return latency; }

    /** @return {@link #getLatency}, in prepared-rate samples (compatibility alias) */
    public int getLatencySamples() { return getLatency(); }

    // -- Processing --------------------------------------------------------------

    /** {@link #process(double[][], int, int)} from sample 0. */
    public void process(double[][] channels, int numSamples)
    {
        process(channels, 0, numSamples);
    }

    /**
     * Processes a block in place. Pass-through until {@link #prepare}
     * succeeds. Blocks longer than the prepared maximum are processed in
     * pieces.
     *
     * @param channels    planar channels (those beyond the prepared count, and
     *                    with oversampling those beyond
     *                    {@link Oversampling#MAX_CHANNELS}, pass through untouched)
     * @param offset      first sample in every channel
     * @param numSamples  number of samples
     */
    public void process(double[][] channels, int offset, int numSamples)
    {
        if (!prepared) return;

        int nCh = Math.min(channels.length, numChannels);
        if (oversampler != null) nCh = Math.min(nCh, Oversampling.MAX_CHANNELS);
        final int nS = numSamples;
        if (nCh == 0 || nS <= 0) return;

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

    private void processChunk(double[][] buffer, int offset, int nS, int nCh)
    {
        // Non-finite guard: a NaN/Inf input would poison the leaky
        // integrator, the algebraic inverse, the high-pass, the bell and the
        // oversampler permanently. The bad sample is replaced with silence
        // (dry too).
        for (int ch = 0; ch < nCh; ch++)
        {
            final double[] d = buffer[ch];
            for (int i = 0; i < nS; i++)
                if (!Double.isFinite(d[offset + i])) d[offset + i] = 0.0;
        }

        // Rate-limited mix ramp (exact landing; settled it reduces to the
        // constant, bit-identically).
        final double mixTarget = mix;
        final double mixStart = currentMix;

        // Dry snapshot, read back delayed to the oversampler's latency.
        final int dryMask = drySize - 1;
        for (int ch = 0; ch < nCh; ch++)
        {
            final double[] in = buffer[ch];
            final double[] dry = dryRing[ch];
            int dp = dryPos;
            for (int i = 0; i < nS; i++)
            {
                dry[dp] = in[offset + i];
                dp = (dp + 1) & dryMask;
            }
        }

        final boolean osOn = oversampler != null;
        final double[][] osView;
        final int osBase;
        final int osN;
        if (osOn)
        {
            osView = oversampler.upsample(buffer, offset, nS);
            osBase = 0;
            osN = oversampler.getHighRateLength();
        }
        else
        {
            osView = buffer;
            osBase = offset;
            osN = nS;
        }

        // Anti-zipper: GEOMETRIC in-block ramps toward the calibrated targets
        // (~50 ms across blocks), shared by all channels. The model
        // differentiates its output (x 2*fs), so a flat per-block gain step
        // clicks; and (hScale, mScale) is a calibrated compensation pair
        // spanning orders of magnitude, which power-law interpolation keeps on
        // the calibration curve m ~ C/h^g throughout the transition.
        if (hScaleSm <= 0.0)
        {
            hScaleSm = hScale;
            mScaleSm = mScale;
        }
        final double kSm = 1.0 - Math.exp(-(double) osN / (0.050 * sampleRate));
        final double hEnd = hScaleSm * Math.pow(hScale / hScaleSm, kSm);
        final double mEnd = mScaleSm * Math.pow(mScale / mScaleSm, kSm);
        final double hRat = Math.pow(hEnd / hScaleSm, 1.0 / (double) osN);
        final double mRat = Math.pow(mEnd / mScaleSm, 1.0 / (double) osN);

        final double lk = leak;
        final double ht = halfT;
        final double iht = invHalfT;
        final double a = hpA;
        for (int ch = 0; ch < nCh; ch++)
        {
            final double[] d = osView[ch];
            final ChannelState st = channels[ch];
            final Hysteresis hyst = st.hyst;
            final BellSection bell = st.bell;
            final double b0 = bell.b0;
            final double b1 = bell.b1;
            final double b2 = bell.b2;
            final double a1 = bell.a1;
            final double a2 = bell.a2;
            double z1 = bell.z1;
            double z2 = bell.z2;
            double flux = st.flux;
            double vPrev = st.vPrev;
            double vPrev2 = st.vPrev2;
            double mPrev = st.mPrev;
            double hpX = st.hpX;
            double hpY = st.hpY;
            double hSm = hScaleSm;
            double mSm = mScaleSm;

            for (int i = 0; i < osN; i++)
            {
                hSm *= hRat;
                mSm *= mRat;
                final double x = d[osBase + i];

                // Leaky trapezoidal integrator: winding voltage -> flux.
                final double fluxNew = lk * flux + ht * (x + vPrev);
                vPrev = x;

                // Core hysteresis on the flux (JA, unbiased-iron parameters).
                final double m = mSm * hyst.processSample(hSm * fluxNew);

                // Algebraic inverse of the integrator (flux -> voltage). The
                // damped feedback pole (rho < 1) tames z = -1: hysteresis
                // distortion is new content the inverse never saw, and an
                // undamped differentiator would ring at Nyquist forever. The
                // leak still cancels exactly; the only linear residue is
                // (1+z^-1)/(1+rho z^-1), transparent to within 0.15 dB.
                final double v = (m - lk * mPrev) * iht - DIFF_RHO * vPrev2;
                vPrev2 = v;
                mPrev = m;
                flux = fluxNew;

                // Magnetizing-inductance corner (one-pole high-pass).
                final double hp = a * (hpY + v - hpX);
                hpX = v;
                hpY = hp;

                // Leakage/capacitance bell.
                final double y = b0 * hp + z1;
                z1 = b1 * hp - a1 * y + z2;
                z2 = b2 * hp - a2 * y;
                d[osBase + i] = y;
            }

            st.flux = flux;
            st.vPrev = vPrev;
            st.vPrev2 = vPrev2;
            st.mPrev = mPrev;
            st.hpX = hpX;
            st.hpY = hpY;
            bell.z1 = z1;
            bell.z2 = z2;
        }
        hScaleSm = hEnd;
        mScaleSm = mEnd;
        // Advance a pending calibration by as many internal samples as this
        // chunk has: bounded work per call.
        if (calActive) stepCalibration(osN);
        if (osOn) oversampler.downsample(buffer, offset, nS);

        dryLead.aim(mixTarget > 0.0);
        for (int i = 0; i < nS; i++)
        {
            final int idx = (dryPos + i - latency) & dryMask;
            final double mixVal = moveTowards(mixStart, mixTarget, mixMaxStep * (double) (i + 1));
            final double turn = dryLead.advance();
            for (int ch = 0; ch < nCh; ch++)
            {
                final double[] d = buffer[ch];
                final double rawDry = dryRing[ch][idx];
                final double turned = dryLead.processSample(ch, rawDry, turn);
                // All wet, the sum keeps the rounding it has with the raw copy.
                final double drySample = mixVal == 1.0 ? rawDry : turned;
                d[offset + i] = drySample + (d[offset + i] - drySample) * mixVal;
            }
        }
        currentMix = moveTowards(mixStart, mixTarget, mixMaxStep * (double) nS);
        dryPos = (dryPos + nS) & dryMask;
    }

    /** Applies the linear parameters and records the calibration request for the (drive, makeup) pair. */
    private void recomputeLinear()
    {
        final double drive = Math.pow(10.0, driveDb / 20.0);
        final double size = coreSize;
        final double res = resonance;

        // The integrator leak keeps the flux bounded; its exact algebraic
        // inverse cancels it, so the audible LF rolloff comes only from the
        // explicit magnetizing-corner high-pass below.
        final double cornerHz = 40.0 * Math.pow(0.125, size);   // 40 -> 5 Hz
        leak = Math.exp(-2.0 * Math.PI * cornerHz / sampleRate);
        hpA = 1.0 - 2.0 * Math.PI * cornerHz / sampleRate;
        // A first-order all-pass at tan(pi/8) of the corner leads by as much
        // as the one-pole high-pass at that corner, and within a few degrees over it.
        dryLead.setCorner((Math.sqrt(2.0) - 1.0) * cornerHz);
        halfT = 0.5 / sampleRate;
        invHalfT = 2.0 * sampleRate;

        // Flux scale: 0 dBFS at 30 Hz reaches H = 1.1a at nominal drive and
        // nominal core; bigger cores take proportionally more flux.
        final double fluxRef = 1.0 / (2.0 * Math.PI * 30.0);
        final double headroom = 0.6 + 0.8 * size;
        calReqH = drive * 1.1 * 2.2e4 / (fluxRef * headroom);
        calReqLeak = leak;

        // HF bell: Jensen-style leakage resonance mapped into the top octave.
        final double bellHz = stdMin(12000.0 + 6000.0 * res, 0.42 * sampleRate);
        final double bellDb = 2.5 * res;
        final BiquadCoeffs bc = BiquadDesign.makePeak(sampleRate, bellHz, 0.8, bellDb);
        for (ChannelState ch : channels)
        {
            ch.bell.b0 = bc.b0;
            ch.bell.b1 = bc.b1;
            ch.bell.b2 = bc.b2;
            ch.bell.a1 = bc.a1;
            ch.bell.a2 = bc.a2;
        }
    }

    /**
     * Loudness calibration at PROGRAM level: 100 Hz at -12 dBFS, the flux
     * regime music actually drives the core into (flux ~ V/f, so lows
     * dominate). A small-signal reference would probe the JA virgin curve,
     * whose susceptibility is an order of magnitude below the major loop's
     * mean slope.
     */
    private void startCalibration()
    {
        calH = calReqH;
        calLeak = calReqLeak;
        cal.prepare(sampleRate);
        cal.setParameters(CORE_MS, CORE_A, CORE_ALPHA, CORE_K, CORE_C);
        calFlux = 0.0;
        calVPrev = 0.0;
        calMPrev = 0.0;
        calVPrev2 = 0.0;
        calInSq = 0.0;
        calOutSq = 0.0;
        calN = (int) (0.04 * sampleRate);   // 2+2 cycles of 100 Hz
        calI = 0;
        calActive = true;
    }

    /** Runs up to {@code budget} calibration samples; adopts the (drive, makeup) pair when the calibration completes. */
    private void stepCalibration(int budget)
    {
        final int stop = Math.min(calN, calI + Math.max(budget, 0));
        for (; calI < stop; ++calI)
        {
            final int i = calI;
            final double x = 0.25 * Math.sin(2.0 * Math.PI * 100.0 * i / sampleRate);
            final double fluxNew = calLeak * calFlux + halfT * (x + calVPrev);
            calVPrev = x;
            final double m = cal.processSample(calH * fluxNew);
            final double v = (m - calLeak * calMPrev) * invHalfT - DIFF_RHO * calVPrev2;
            calVPrev2 = v;
            calMPrev = m;
            calFlux = fluxNew;
            if (i >= calN / 2)
            {
                calInSq += x * x;
                calOutSq += v * v;
            }
        }
        if (calI < calN) return;
        mScale = (calOutSq > 0.0) ? Math.sqrt(calInSq / calOutSq) : 1.0;
        hScale = calH;
        calActive = false;
        if (calRestart)
        {
            calRestart = false;
            startCalibration();
        }
    }

    /** Moves {@code from} towards {@code to} by at most {@code maxDelta}, landing on the target exactly. */
    private static double moveTowards(double from, double to, double maxDelta)
    {
        final double delta = to - from;
        return Math.abs(delta) <= maxDelta ? to : from + clamp(delta, -maxDelta, maxDelta);
    }

    /** The value clamped to [lo, hi], as std::clamp. */
    private static double clamp(double v, double lo, double hi)
    {
        return (v < lo) ? lo : (hi < v) ? hi : v;
    }

    /** The larger of two values, the first one when they compare equal (std::max). */
    private static double stdMax(double x, double y)
    {
        return (x < y) ? y : x;
    }

    /** The smaller of two values, the first one when they compare equal (std::min). */
    private static double stdMin(double x, double y)
    {
        return (y < x) ? y : x;
    }
}
