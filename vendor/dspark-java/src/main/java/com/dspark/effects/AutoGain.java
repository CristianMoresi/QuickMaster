package com.dspark.effects;

import com.dspark.core.DspMath;

/**
 * Automatic gain compensation for honest A/B comparison.
 * <p>
 * Louder always sounds "better", which biases A/B judgements. AutoGain
 * removes that bias by measuring the input level before processing and
 * trimming the output to match afterwards. Usage is a sandwich:
 * <pre>
 *   autoGain.pushReference(buffer, ch);  // measure input level
 *   chain.process(buffer, ch);           // your processing
 *   autoGain.compensate(buffer, ch);     // match output to input level
 * </pre>
 * The compensation is smoothed block to block to avoid zipper noise.
 */
public final class AutoGain
{
    private static final double SILENCE_THRESH_DB = -90.0;

    private double sampleRate = 44100.0;
    private int channels = 2;
    private double smoothTimeSecs = 0.100;
    private double refLevelDb = SILENCE_THRESH_DB;
    private double compensationDb = 0.0;
    private volatile double maxCompensationDb = 12.0;

    public void prepare(double sampleRate, int channels)
    {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0.0) return;
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, channels);
        reset();
    }

    public void reset()
    {
        refLevelDb = SILENCE_THRESH_DB;
        compensationDb = 0.0;
    }

    public double getCompensationDb() { return compensationDb; }

    /**
     * Caps the compensation range, in dB. Non-finite values are ignored
     * (a NaN would silently DISABLE the clamp).
     */
    public void setMaxCompensationDb(double dB)
    {
        if (Double.isFinite(dB)) maxCompensationDb = Math.abs(dB);
    }

    public double getMaxCompensationDb() { return maxCompensationDb; }

    /** Smoothing time in ms. Non-finite values are ignored. */
    public void setSmoothingTimeMs(double ms)
    {
        if (Double.isFinite(ms)) smoothTimeSecs = Math.max(ms * 0.001, 0.001);
    }

    public double getSmoothingTimeMs() { return smoothTimeSecs * 1000.0; }

    /**
     * Snapshots the input level. Call BEFORE processing. An empty buffer
     * keeps the previous reference (flooring it to silence would pin max
     * attenuation on live audio).
     */
    public void pushReference(float[] buffer, int channels)
    {
        if (buffer == null || buffer.length == 0 || channels < 1) return;
        refLevelDb = measureRmsDb(buffer, channels);
    }

    /** Matches the output level to the last reference. Call AFTER processing. */
    public void compensate(float[] buffer, int channels)
    {
        int nCh = Math.min(channels, this.channels);
        int frames = buffer.length / channels;
        if (frames == 0 || nCh == 0) return;

        double outLevelDb = measureRmsDb(buffer, channels);
        double targetDb = refLevelDb - outLevelDb;
        if (Double.isNaN(targetDb)) targetDb = 0.0;

        double maxComp = maxCompensationDb;
        targetDb = DspMath.clamp(targetDb, -maxComp, maxComp);

        if (refLevelDb < SILENCE_THRESH_DB && outLevelDb < SILENCE_THRESH_DB) targetDb = 0.0;

        double alpha = Math.exp(-(double) frames / (sampleRate * smoothTimeSecs));
        double endCompensationDb = targetDb + (compensationDb - targetDb) * alpha;

        double startGain = DspMath.decibelsToGain(compensationDb);
        double endGain = DspMath.decibelsToGain(endCompensationDb);
        double gainStep = (endGain - startGain) / frames;

        for (int f = 0; f < frames; f++)
        {
            double g = startGain + f * gainStep;
            int base = f * channels;
            for (int c = 0; c < nCh; c++) buffer[base + c] *= (float) g;
        }

        compensationDb = endCompensationDb;
    }

    private double measureRmsDb(float[] buffer, int channels)
    {
        int nCh = Math.min(channels, this.channels);
        int frames = buffer.length / channels;
        int total = frames * nCh;
        if (total == 0) return SILENCE_THRESH_DB;

        double sumSq = 0.0;
        for (int f = 0; f < frames; f++)
        {
            int base = f * channels;
            for (int c = 0; c < nCh; c++)
            {
                double x = buffer[base + c];
                sumSq += x * x;
            }
        }
        double meanSq = Math.max(sumSq / total, 1e-15);
        return DspMath.gainToDecibels(Math.sqrt(meanSq));
    }
}
