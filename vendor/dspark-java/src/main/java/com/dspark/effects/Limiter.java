package com.dspark.effects;

import com.dspark.analysis.TruePeak;
import com.dspark.core.DspMath;
import com.dspark.core.RingBuffer;
import com.dspark.core.SmoothedValue;

/**
 * True-peak brickwall limiter for mastering.
 * <p>
 * Prevents the signal from exceeding a configurable ceiling using a
 * look-ahead delay line and a smoothed attack envelope, so transients are
 * caught without high-frequency clicks. Optional features: 4× oversampled
 * inter-sample (true) peak detection for broadcast compliance, a
 * program-dependent adaptive release, and a post-limiter safety clipper.
 * <p>
 * Introduces a latency of {@link #getLatency()} samples (the look-ahead).
 */
public final class Limiter
{
    private static final int MAX_CHANNELS = 8;
    private static final double MAX_LOOKAHEAD_MS = 10.0;

    private static final double SAFETY_CLIP_CEILING = 0.96605; // -0.3 dBFS

    private boolean prepared = false;
    private double sampleRate = 48000.0;
    private double invSampleRate = 1.0 / 48000.0;
    private int channels = 2;
    private int lookaheadSamples = 96;
    private double lookaheadMs = 2.0;

    // Parameters (set from any thread).
    private volatile double ceilingDb = -0.3;
    private volatile double releaseMs = 100.0;
    private volatile boolean truePeakEnabled = false;
    private volatile boolean adaptiveRelease = false;
    private volatile boolean safetyClipEnabled = false;

    private final SmoothedValue ceilingSmooth = new SmoothedValue();
    private double releaseCoeff = 0.0;
    private double attackCoeff = 1.0;
    private double lastReleaseMs = -1.0;

    private double currentGain = 1.0;
    private int limitingDuration = 0;

    private double heldPeak = 0.0;
    private int peakHoldCounter = 0;

    private RingBuffer[] delayLines = new RingBuffer[0];

    // Shared official BS.1770-5 Annex 2 true-peak detector, one per channel.
    private final TruePeak[] truePeakDet = new TruePeak[MAX_CHANNELS];

    /**
     * Prepares the limiter. Allocates the look-ahead delay lines.
     * A non-finite or non-positive sample rate is ignored (conservative
     * no-op keeping the previous state).
     */
    public void prepare(double sampleRate, int channels, double initialLookaheadMs)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        this.invSampleRate = 1.0 / sampleRate;
        this.channels = Math.max(1, Math.min(channels, MAX_CHANNELS));

        int maxLookaheadSamples = (int) (sampleRate * MAX_LOOKAHEAD_MS / 1000.0) + 1;
        delayLines = new RingBuffer[this.channels];
        for (int i = 0; i < this.channels; i++)
        {
            delayLines[i] = new RingBuffer();
            delayLines[i].prepare(maxLookaheadSamples * 2);
        }

        setLookahead(initialLookaheadMs);

        ceilingSmooth.prepare(sampleRate, 30.0);
        ceilingSmooth.reset(DspMath.decibelsToGain(ceilingDb));

        updateReleaseCoefficient();
        for (int i = 0; i < MAX_CHANNELS; i++)
            if (truePeakDet[i] == null) truePeakDet[i] = new TruePeak();
        reset();
        prepared = true;
    }

    /**
     * Prepares keeping the configured look-ahead (2 ms until
     * {@link #setLookahead} is called).
     */
    public void prepare(double sampleRate, int channels)
    {
        prepare(sampleRate, channels, lookaheadMs);
    }

    /** Resets gain reduction and delay state (RT-safe). */
    public void reset()
    {
        for (RingBuffer dl : delayLines) dl.reset();
        clearTruePeakState();
        currentGain = 1.0;
        limitingDuration = 0;
        heldPeak = 0.0;
        peakHoldCounter = 0;
        ceilingSmooth.skip();
    }

    /** Sets the output ceiling in dBFS (e.g. −1.0 for streaming). Non-finite values are ignored. */
    public void setCeilingDb(double dB) { if (Double.isFinite(dB)) ceilingDb = dB; }

    public double getCeilingDb() { return ceilingDb; }

    /** Sets the base release time in milliseconds. Non-finite values are ignored. */
    public void setReleaseMs(double ms) { if (Double.isFinite(ms)) releaseMs = Math.max(ms, 1.0); }

    /** Enables 4× oversampled inter-sample (true) peak detection. */
    public void setTruePeak(boolean enabled) { truePeakEnabled = enabled; }

    public boolean isTruePeakEnabled() { return truePeakEnabled; }

    /** Enables program-dependent adaptive release. */
    public void setAdaptiveRelease(boolean enabled) { adaptiveRelease = enabled; }

    /** Enables the post-limiter safety clipper. */
    public void setSafetyClip(boolean enabled) { safetyClipEnabled = enabled; }

    /** Sets the look-ahead time in milliseconds (0.5–10). Non-finite values are ignored. */
    public void setLookahead(double ms)
    {
        if (!Double.isFinite(ms)) return;
        lookaheadMs = DspMath.clamp(ms, 0.5, MAX_LOOKAHEAD_MS);
        if (sampleRate > 0)
        {
            lookaheadSamples = Math.max(1, (int) (sampleRate * lookaheadMs / 1000.0));
            // Attack coefficient reaching ~99% of target gain within the
            // look-ahead window: alpha = 1 - exp(-ln(100) / samples).
            attackCoeff = 1.0 - Math.exp(-4.60517 / lookaheadSamples);
        }
    }

    /** Look-ahead latency in samples. */
    public int getLatency() { return lookaheadSamples; }

    /** Current gain reduction in dB (≤ 0). */
    public double getGainReductionDb() { return DspMath.gainToDecibels(currentGain); }

    /**
     * Limits an interleaved buffer in place.
     *
     * @param buffer    interleaved samples
     * @param channels  number of channels
     */
    public void process(float[] buffer, int channels)
    {
        if (!prepared) return;
        int nCh = Math.min(channels, this.channels);
        int frames = buffer.length / channels;

        syncParameters();
        boolean isp = truePeakEnabled;
        boolean adaptive = adaptiveRelease;
        boolean safety = safetyClipEnabled;
        double relMs = Math.max(releaseMs, 1.0);

        for (int f = 0; f < frames; f++)
        {
            int base = f * channels;
            double ceiling = ceilingSmooth.getNextValue();
            double peak = 0.0;

            // Phase 1: push to delay lines and detect the peak across channels.
            for (int ch = 0; ch < nCh; ch++)
            {
                float sample = buffer[base + ch];
                delayLines[ch].push(sample);
                double chPeak = isp ? detectTruePeak(sample, ch) : Math.abs(sample);
                if (chPeak > peak) peak = chPeak;
            }

            // Phase 2: hold the peak across the look-ahead window, then envelope.
            if (peak >= heldPeak) { heldPeak = peak; peakHoldCounter = lookaheadSamples; }
            else if (peakHoldCounter > 0) { peakHoldCounter--; }
            else { heldPeak = peak; }

            double targetGain = (heldPeak > ceiling) ? ceiling / heldPeak : 1.0;
            smoothGain(targetGain, adaptive, relMs);

            // Phase 3: apply the (delayed) gain and optional safety clip.
            for (int ch = 0; ch < nCh; ch++)
            {
                double out = delayLines[ch].read(lookaheadSamples) * currentGain;
                if (safety)
                {
                    double clipCeil = Math.min(SAFETY_CLIP_CEILING, ceiling);
                    if (Math.abs(out) > clipCeil) out = applySafetyClipper(out, clipCeil);
                }
                buffer[base + ch] = (float) out;
            }
        }
    }

    /* ====================================================================
     *  Internals
     * ==================================================================== */

    private void syncParameters()
    {
        ceilingSmooth.setTargetValue(DspMath.decibelsToGain(ceilingDb));
        double relMs = Math.max(releaseMs, 1.0);
        if (relMs != lastReleaseMs)
        {
            lastReleaseMs = relMs;
            updateReleaseCoefficient();
        }
    }

    private void updateReleaseCoefficient()
    {
        double rel = (lastReleaseMs > 0) ? lastReleaseMs : releaseMs;
        if (sampleRate > 0) releaseCoeff = 1.0 - Math.exp(-1.0 / (sampleRate * rel / 1000.0));
    }

    private void smoothGain(double targetGain, boolean adaptive, double relMs)
    {
        if (targetGain < currentGain)
        {
            // Smoothed attack to avoid discontinuous clicks on transients.
            currentGain += attackCoeff * (targetGain - currentGain);
            int maxDuration = (int) (sampleRate * 2.0);
            if (limitingDuration < maxDuration) limitingDuration++;
        }
        else
        {
            double coeff;
            if (adaptive)
            {
                double baseFactor = 1.0;
                if (limitingDuration > 0)
                {
                    double durationMs = limitingDuration * 1000.0 * invSampleRate;
                    baseFactor = 1.0 + Math.min(durationMs / 100.0, 2.0);
                }
                double adaptedRelease = relMs * baseFactor;
                coeff = 1.0 / (1.0 + (sampleRate * adaptedRelease / 1000.0));
            }
            else
            {
                coeff = releaseCoeff;
            }
            currentGain += coeff * (targetGain - currentGain);
            if (currentGain > 1.0) currentGain = 1.0;
            if (currentGain > 0.999) limitingDuration = 0;
        }
    }

    private double applySafetyClipper(double out, double clipCeil)
    {
        double sign = (out >= 0.0) ? 1.0 : -1.0;
        double excess = Math.abs(out) - clipCeil;
        double blend = 1.0 / (1.0 + excess * 10.0);
        out = sign * (clipCeil * blend + Math.abs(out) * (1.0 - blend));
        double hardCeil = clipCeil * 1.05;
        if (Math.abs(out) > hardCeil) out = Math.max(-hardCeil, Math.min(hardCeil, out));
        return out;
    }

    /** Official Annex 2 inter-sample peak estimate (shared TruePeak detector). */
    private double detectTruePeak(double sample, int ch)
    {
        return truePeakDet[ch].process(sample);
    }

    private void clearTruePeakState()
    {
        for (TruePeak tp : truePeakDet) if (tp != null) tp.reset();
    }
}
