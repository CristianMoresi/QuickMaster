package com.quickmaster.processing.analysis;

import com.dspark.analysis.OnsetDetector;
import com.dspark.analysis.TempoEstimator;

/**
 * Per-track musical analysis shared by the tempo- and transient-aware
 * dynamics (Beat Comp and Punch).
 * <p>
 * Wraps DSPark's {@link OnsetDetector} and {@link TempoEstimator} to extract,
 * <b>once per loaded track</b>, the global tempo (BPM) and a map of onsets
 * (transient positions, durations and strengths). It is recomputed only when
 * the source timeline changes - a new file, or a crop/trim/delete - because
 * those are the only edits that move transients or change the tempo grid.
 * <p>
 * The result is read-only and cheap to share: a single instance is referenced
 * by every compressor that needs it, so the expensive FFT-based detection runs
 * just once rather than per compressor or per playback.
 * <p>
 * When {@link #isTempoReliable()} is {@code false}, the UI should let the user
 * type a BPM; {@link #setManualBpm(double)} overrides the detected value.
 */
public final class TrackAnalysis
{
    /** Below this confidence the detected tempo is considered unreliable. */
    public static final double RELIABLE_CONFIDENCE = 0.25;

    private double bpm = 0.0;
    private double detectedBpm = 0.0;
    private double confidence = 0.0;
    private boolean manualBpm = false;

    private double[] onsetTimesSec = new double[0];
    private double[] onsetDurationsSec = new double[0];
    private float[] onsetStrengths = new float[0];

    /**
     * Runs onset detection and tempo estimation over the whole signal.
     *
     * @param interleaved  interleaved float samples (the raw track)
     * @param channels     channel count
     * @param sampleRate   sample rate in Hz
     */
    public void analyze(float[] interleaved, int channels, double sampleRate)
    {
        OnsetDetector onsets = new OnsetDetector();
        onsets.analyze(interleaved, channels, sampleRate);

        TempoEstimator tempo = new TempoEstimator();
        tempo.estimate(onsets);

        this.onsetTimesSec = onsets.getOnsetTimesSec();
        this.onsetDurationsSec = onsets.getOnsetDurationsSec();
        this.onsetStrengths = onsets.getOnsetStrengths();

        this.detectedBpm = tempo.getBpm();
        if (!manualBpm)
        {
            this.bpm = detectedBpm;
        }
        this.confidence = tempo.getConfidence();
    }

    /** Detected (or manually set) tempo in BPM; {@code 0} if unknown. */
    public double getBpm() { return bpm; }

    /** The detected tempo in BPM, regardless of any manual override; {@code 0} if unknown. */
    public double getDetectedBpm() { return detectedBpm; }

    /** Tempo estimation confidence in {@code [0,1]}. */
    public double getConfidence() { return confidence; }

    /** True when the detected tempo is trustworthy enough to use automatically. */
    public boolean isTempoReliable() { return manualBpm || (bpm > 0 && confidence >= RELIABLE_CONFIDENCE); }

    /** Overrides the tempo with a user-supplied BPM (sticks across re-analysis). */
    public void setManualBpm(double bpm)
    {
        if (bpm > 0)
        {
            this.bpm = bpm;
            this.manualBpm = true;
        }
    }

    /** Clears a manual override and restores the detected tempo immediately. */
    public void clearManualBpm()
    {
        this.manualBpm = false;
        if (detectedBpm > 0) this.bpm = detectedBpm;
    }

    /** Whether the current BPM came from the user rather than detection. */
    public boolean isManualBpm() { return manualBpm; }

    /** Onset positions in seconds. */
    public double[] getOnsetTimesSec() { return onsetTimesSec; }

    /** Estimated transient durations in seconds, parallel to the onsets. */
    public double[] getOnsetDurationsSec() { return onsetDurationsSec; }

    /** Onset strengths in {@code [0,1]}, parallel to the onsets. */
    public float[] getOnsetStrengths() { return onsetStrengths; }

    /** Number of detected onsets. */
    public int getOnsetCount() { return onsetTimesSec.length; }
}
