package com.dspark.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * Dry/wet mixer with parameter smoothing and mix laws.
 * <p>
 * Captures a copy of the dry (unprocessed) signal before an effect runs, then
 * blends it with the wet (processed) signal. A change of the mix ramps across
 * the block, never faster than a full-scale move in 20 ms, so automation does
 * not zipper. Linear, equal-power and full-dry-to-half laws.
 * <p>
 * If the effect introduces latency, call {@link #setLatencyCompensation}
 * during setup and pass the original, undelayed input to {@link #pushDry}:
 * the mixer delays its own copy to align both paths and prevent comb
 * filtering.
 * <pre>
 * mixer.prepare(sampleRate, channels, maxBlock);
 * mixer.pushDry(buffer, n);
 * applyEffect(buffer, n);          // in place: the buffer is now wet
 * mixer.mixWet(buffer, n, 0.5);    // moves smoothly to 50 % wet
 * </pre>
 * {@link #prepare} and {@link #setLatencyCompensation} allocate; the
 * processing calls do not.
 */
public final class DryWetMixer
{
    /** The curve of the mix. */
    public enum MixRule
    {
        /** Linear crossfade. Best for highly correlated signals (EQ, saturation). */
        LINEAR,
        /** Constant power crossfade. Best for uncorrelated signals (reverb, delay). */
        EQUAL_POWER,
        /**
         * The dry stays whole up to half and then falls; the wet rises to whole
         * at half: at 0.5 the output is dry plus wet, as in hardware that adds
         * the two.
         */
        FULL_DRY_TO_HALF
    }

    /** Most channels the mixer keeps. */
    public static final int MAX_CHANNELS = 16;

    private static final double MIN_RAMP_SECONDS = 0.02;

    private double[][] dryBuffer = new double[0][];
    private int dryNumSamples = 0;
    private int capturedSamples = 0;

    // Optional dry-path latency compensation (circular delay); 0 disables it.
    private double[][] delayHist = new double[0][];
    private int delayHistSamples = 0;
    private int latencySamples = 0;
    private int histPos = 0;

    private MixRule mixRule = MixRule.LINEAR;
    private double currentMix = -1.0;   // smoothing state; negative jumps on the next mix
    private double maxMixStep = 1.0;    // per-sample mix slew limit (set in prepare())

    /**
     * Allocates the dry buffer.
     *
     * @param sampleRate    sample rate in Hz
     * @param numChannels   channels (up to {@link #MAX_CHANNELS} are kept)
     * @param maxBlockSize  largest block
     */
    public void prepare(double sampleRate, int numChannels, int maxBlockSize)
    {
        final int channels = Math.max(0, Math.min(numChannels, MAX_CHANNELS));
        dryNumSamples = Math.max(0, maxBlockSize);
        dryBuffer = new double[channels][dryNumSamples];

        // Mix changes ramp over at least MIN_RAMP_SECONDS for a full-scale
        // move, whatever the host block size (an invalid spec keeps the
        // one-block ramp).
        final boolean valid = sampleRate > 0.0 && sampleRate <= Double.MAX_VALUE
            && maxBlockSize > 0 && numChannels > 0;
        if (valid)
        {
            final double samples = sampleRate * MIN_RAMP_SECONDS;
            maxMixStep = 1.0 / ((1.0 < samples) ? samples : 1.0);
        }
        else
        {
            maxMixStep = 1.0;
        }

        // A latency set before prepare() sized the history with no channels:
        // size it again now that the channel count is known.
        if (latencySamples > 0)
        {
            resizeDelayHist(channels, latencySamples);
            histPos = 0;
        }

        reset();
    }

    /** Clears the dry buffer, the delay and the smoothing state. */
    public void reset()
    {
        for (double[] ch : dryBuffer) Arrays.fill(ch, 0.0);
        if (delayHistSamples > 0)
            for (double[] ch : delayHist) Arrays.fill(ch, 0.0);
        histPos = 0;
        capturedSamples = 0;   // no valid dry snapshot until the next pushDry()
        currentMix = -1.0;     // the next mix jumps to its target
    }

    /**
     * Sets the curve of the mix, from the next {@link #mixWet}.
     *
     * @param rule  the rule
     */
    public void setMixRule(MixRule rule)
    {
        mixRule = Objects.requireNonNull(rule, "rule");
    }

    /** @return the rule of the mix */
    public MixRule getMixRule() { return mixRule; }

    /**
     * Delays the captured dry signal to compensate for an effect's latency
     * (an oversampler's FIR group delay, for example). Pass 0 to disable
     * (default). Allocates: call it at setup, typically right after
     * {@link #prepare}.
     *
     * @param samples  latency in samples (negative counts as 0)
     */
    public void setLatencyCompensation(int samples)
    {
        samples = Math.max(0, samples);
        if (samples == latencySamples) return;
        latencySamples = samples;
        resizeDelayHist(dryBuffer.length, samples);
        histPos = 0;
    }

    /** @return the dry-path latency compensation in samples */
    public int getLatencyCompensation() { return latencySamples; }

    private void resizeDelayHist(int channels, int samples)
    {
        delayHist = new double[channels][samples];
        delayHistSamples = samples;
    }

    /** {@link #pushDry(double[][], int, int)} from sample 0. */
    public void pushDry(double[][] input, int numSamples)
    {
        pushDry(input, 0, numSamples);
    }

    /**
     * Captures a snapshot of the dry (unprocessed) signal. Channels beyond the
     * prepared count are ignored; a block shorter than the prepared size
     * shortens the snapshot (and the stretch the next {@link #mixWet} blends).
     *
     * @param input       the unprocessed channels (read only)
     * @param offset      first sample in every channel
     * @param numSamples  number of samples
     */
    public void pushDry(double[][] input, int offset, int numSamples)
    {
        if (dryNumSamples == 0) return;   // not prepared

        final int chCount = Math.min(input.length, dryBuffer.length);
        final int nSamples = Math.max(0, Math.min(numSamples, dryNumSamples));

        if (latencySamples <= 0)
        {
            for (int ch = 0; ch < chCount; ch++)
                System.arraycopy(input[ch], offset, dryBuffer[ch], 0, nSamples);
        }
        else
        {
            // A per-channel circular delay of exactly latencySamples keeps the
            // dry time aligned with a wet path that incurs the same delay.
            final int d = latencySamples;
            for (int ch = 0; ch < chCount; ch++)
            {
                final double[] src = input[ch];
                final double[] dst = dryBuffer[ch];
                final double[] hist = delayHist[ch];
                int pos = histPos;
                for (int i = 0; i < nSamples; i++)
                {
                    dst[i] = hist[pos];   // the sample written d steps ago
                    hist[pos] = src[offset + i];
                    if (++pos == d) pos = 0;
                }
            }
            histPos = (histPos + nSamples) % d;
        }

        capturedSamples = nSamples;
    }

    /** {@link #mixWet(double[][], int, int, double)} from sample 0. */
    public void mixWet(double[][] wetBuffer, int numSamples, double targetMix)
    {
        mixWet(wetBuffer, 0, numSamples, targetMix);
    }

    /**
     * Blends the stored dry signal into the wet buffer, in place. A change of
     * the mix ramps linearly across the block, but never faster than a
     * full-scale move in 20 ms; a ramp the limit cuts short goes on in the
     * following blocks. Only the stretch the last {@link #pushDry} captured is
     * blended: without a snapshot (right after {@link #reset}) the buffer
     * stays fully wet. Channels beyond the prepared count pass untouched.
     *
     * @param wetBuffer   the processed channels (modified in place)
     * @param offset      first sample in every channel
     * @param numSamples  number of samples
     * @param targetMix   0 is fully dry, 1 fully wet (NaN counts as 0)
     */
    public void mixWet(double[][] wetBuffer, int offset, int numSamples, double targetMix)
    {
        if (Double.isNaN(targetMix)) targetMix = 0.0;
        targetMix = DspMath.clamp(targetMix, 0.0, 1.0);

        final int chCount = Math.min(wetBuffer.length, dryBuffer.length);
        final int nSamples = Math.min(numSamples, capturedSamples);

        if (nSamples <= 0 || chCount == 0) return;

        // The first mix after a reset jumps to its target.
        if (currentMix < 0.0) currentMix = targetMix;

        final boolean needsSmoothing = Math.abs(currentMix - targetMix) > 1e-5;
        double mixStep = 0.0;
        boolean rateLimited = false;
        if (needsSmoothing)
        {
            mixStep = (targetMix - currentMix) / (double) nSamples;
            if (Math.abs(mixStep) > maxMixStep)
            {
                mixStep = mixStep > 0.0 ? maxMixStep : -maxMixStep;
                rateLimited = true;
            }
        }
        final MixRule rule = mixRule;

        // Every channel ramps from the same smoothed origin, indexed rather
        // than accumulated (one rounding per sample). A settled mix within the
        // 1e-5 dead band renders at the target itself.
        final double mix0 = needsSmoothing ? currentMix : targetMix;

        for (int ch = 0; ch < chCount; ch++)
        {
            final double[] wetData = wetBuffer[ch];
            final double[] dryData = dryBuffer[ch];

            if (rule == MixRule.EQUAL_POWER)
            {
                if (!needsSmoothing)
                {
                    final double w = Math.sqrt(mix0);
                    final double d = Math.sqrt(1.0 - mix0);
                    for (int i = 0; i < nSamples; i++)
                        wetData[offset + i] = dryData[i] * d + wetData[offset + i] * w;
                }
                else
                {
                    for (int i = 0; i < nSamples; i++)
                    {
                        // Exact constant-power law, w^2 + d^2 = 1; the guards
                        // keep a radicand rounded past an end from going negative.
                        final double m = mix0 + mixStep * (double) i;
                        final double rest = 1.0 - m;
                        final double w = Math.sqrt(0.0 < m ? m : 0.0);
                        final double d = Math.sqrt(0.0 < rest ? rest : 0.0);
                        wetData[offset + i] = dryData[i] * d + wetData[offset + i] * w;
                    }
                }
            }
            else if (rule == MixRule.FULL_DRY_TO_HALF)
            {
                // dry min(1, 2 (1 - m)), wet min(1, 2 m).
                for (int i = 0; i < nSamples; i++)
                {
                    final double m = needsSmoothing ? mix0 + mixStep * (double) i : mix0;
                    final double twiceWet = 2.0 * m;
                    final double twiceDry = 2.0 * (1.0 - m);
                    final double w = (twiceWet < 1.0) ? twiceWet : 1.0;
                    final double d = (twiceDry < 1.0) ? twiceDry : 1.0;
                    wetData[offset + i] = dryData[i] * d + wetData[offset + i] * w;
                }
            }
            else
            {
                if (!needsSmoothing)
                {
                    final double w = mix0;
                    final double d = 1.0 - w;
                    for (int i = 0; i < nSamples; i++)
                        wetData[offset + i] = dryData[i] * d + wetData[offset + i] * w;
                }
                else
                {
                    for (int i = 0; i < nSamples; i++)
                    {
                        final double w = mix0 + mixStep * (double) i;
                        final double d = 1.0 - w;
                        wetData[offset + i] = dryData[i] * d + wetData[offset + i] * w;
                    }
                }
            }
        }

        // A rate-limited ramp resumes from where this block left it.
        currentMix = rateLimited
            ? DspMath.clamp(mix0 + mixStep * (double) nSamples, 0.0, 1.0)
            : targetMix;
    }

    /**
     * The captured dry samples of a channel (valid over
     * {@link #getDryCapturedSamples} samples from index 0).
     *
     * @param ch  channel index
     * @return the channel's dry buffer, read only, or null for a channel it does not keep
     */
    public double[] getDryChannel(int ch)
    {
        if (ch < 0 || ch >= dryBuffer.length) return null;
        return dryBuffer[ch];
    }

    /** @return the channels of the dry buffer */
    public int getDryNumChannels() { return dryBuffer.length; }

    /** @return the samples the last {@link #pushDry} captured */
    public int getDryCapturedSamples() { return capturedSamples; }
}
