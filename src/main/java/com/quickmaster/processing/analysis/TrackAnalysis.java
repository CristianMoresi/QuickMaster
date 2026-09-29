package com.quickmaster.processing.analysis;

import com.dspark.analysis.OnsetDetector;
import com.dspark.analysis.TempoEstimator;
import com.dspark.analysis.SuperFluxOnsetDetector;

/**
 * Per-track musical analysis shared by the tempo- and transient-aware
 * dynamics (Beat Comp and Punch).
 * <p>
 * Uses DSPark's FFT/onset and {@link TempoEstimator} primitives with pooled
 * stereo spectral energy and a repeated onset-interval check to extract, <b>once per loaded track</b>, a reliable
 * global tempo (BPM) and a map of onsets
 * (transient positions, durations and strengths). It is recomputed only when
 * the source timeline changes - a new file, or a crop/trim/delete - because
 * those are the only edits that move transients or change the tempo grid.
 * <p>
 * The result is read-only and cheap to share: a single instance is referenced
 * by every compressor that needs it. The strong-pulse tempo and SuperFlux
 * transient analyses each run once per source, never per knob or playback.
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
    private SuperFluxOnsetDetector.Result transientOnsets =
            new SuperFluxOnsetDetector.Result(new double[0],new double[0],new double[0],0,0);

    /**
     * Runs onset detection and tempo estimation over the whole signal.
     *
     * @param interleaved  interleaved float samples (the raw track)
     * @param channels     channel count
     * @param sampleRate   sample rate in Hz
     */
    public void analyze(float[] interleaved, int channels, double sampleRate)
    {
        float[] novelty;
        double frameRate;
        if (!validInput(interleaved, channels, sampleRate))
        {
            onsetTimesSec = new double[0];
            onsetDurationsSec = new double[0];
            onsetStrengths = new float[0];
            transientOnsets = new SuperFluxOnsetDetector.Result(new double[0],new double[0],new double[0],0,0);
            detectedBpm = confidence = 0.0;
            if (!manualBpm) bpm = 0.0;
            return;
        }
        if (channels == 2)
        {
            StereoOnsetDetector.Result onsets = StereoOnsetDetector.analyze(interleaved, sampleRate);
            onsetTimesSec = onsets.times();
            onsetDurationsSec = onsets.durations();
            onsetStrengths = onsets.strengths();
            novelty = onsets.novelty();
            frameRate = onsets.frameRate();
        }
        else
        {
            OnsetDetector onsets = new OnsetDetector();
            onsets.analyze(interleaved, channels, sampleRate);
            onsetTimesSec = onsets.getOnsetTimesSec();
            onsetDurationsSec = onsets.getOnsetDurationsSec();
            onsetStrengths = onsets.getOnsetStrengths();
            novelty = onsets.getOdf();
            frameRate = onsets.getFrameRate();
        }
        // Separate transient shaping from the audited strong-pulse tempo map.
        // SuperFlux suppresses stationary-bin/vibrato fluctuations; merely
        // lowering a local spectral-flux threshold amplifies them as attacks.
        // Delta .01 (C++ default .03) retains the -31 dBFS transient corpus;
        // stationary carriers and FM/AM are independently guarded at this value.
        transientOnsets = new SuperFluxOnsetDetector().analyze(interleaved,channels,sampleRate,.01);
        TempoEstimator tempo = new TempoEstimator();
        tempo.estimate(novelty, frameRate);

        OnsetTempoEstimator.Estimate onsetTempo =
                OnsetTempoEstimator.estimate(onsetTimesSec, onsetStrengths);
        if (onsetTempo.confidence() >= RELIABLE_CONFIDENCE)
        {
            this.detectedBpm = onsetTempo.bpm();
            this.confidence = onsetTempo.confidence();
        }
        else if (!onsetTempo.conflictingSections()
                && tempo.getConfidence() >= 0.5 && Double.isFinite(tempo.getBpm()))
        {
            this.detectedBpm = tempo.getBpm();
            this.confidence = tempo.getConfidence();
        }
        else
        {
            this.detectedBpm = 0.0;
            this.confidence = 0.0;
        }
        if (!manualBpm)
        {
            this.bpm = detectedBpm;
        }
    }

    private static boolean validInput(float[] samples, int channels, double rate)
    {
        if (samples == null || (channels != 1 && channels != 2) || samples.length % channels != 0
                || !Double.isFinite(rate) || rate <= 0.0) return false;
        for (float sample : samples) if (!Float.isFinite(sample)) return false;
        return true;
    }

    /** SuperFlux transient clock for Punch, independent of the tempo's strong-pulse gate. */
    public double[] getTransientTimesSec() { return transientOnsets.times(); }
    public double[] getTransientDurationsSec() { return transientOnsets.durations(); }

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
        if (Double.isFinite(bpm) && bpm > 0)
        {
            this.bpm = bpm;
            this.manualBpm = true;
        }
    }

    /** Clears a manual override and restores the detected tempo immediately. */
    public void clearManualBpm()
    {
        this.manualBpm = false;
        this.bpm = detectedBpm;
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
