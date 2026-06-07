package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.analysis.TrackAnalysis;

import com.dspark.core.DspMath;

/**
 * <b>Beat Comp</b> - tempo-synchronised transient leveller.
 * <p>
 * Evens the level of a track's beat transients. Onsets are located by
 * spectral-flux detection ({@link TrackAnalysis}); each onset louder than the
 * median onset level is turned down toward it, by up to the
 * <b>Gain Reduction Target</b>. The attack is instantaneous (look-ahead) and the
 * release is locked to one musical note value ({@link NoteValue}) of the track's
 * tempo, so the gain recovers by the next beat.
 */
public final class BeatCompProcessor extends AnalysisDynamicsProcessor
{
    /** Musical note values for the tempo-synced release. */
    public enum NoteValue
    {
        EIGHTH("1/8", 0.5),
        QUARTER("1/4", 1.0),
        HALF("1/2", 2.0),
        WHOLE("1/1", 4.0);

        public final String label;
        /** Length in quarter-note beats. */
        public final double beats;
        NoteValue(String label, double beats) { this.label = label; this.beats = beats; }
        @Override public String toString() { return label; }
    }

    public static final double LOOKAHEAD_MS = 2.0;
    public static final double ATTACK_MS = 0.5;
    /** Release used when no tempo is available (ms). */
    public static final double FALLBACK_RELEASE_MS = 250.0;

    public static final double DEFAULT_TARGET_DB = -2.0;
    public static final double MIN_TARGET_DB = -18.0;
    public static final double MAX_TARGET_DB = 0.0;

    private static final double PEAK_WINDOW_MS = 30.0;     // per-onset peak measurement
    private static final double PRE_ROLL_MS = 4.0;

    private volatile double targetDb = DEFAULT_TARGET_DB;
    private volatile NoteValue note = NoteValue.QUARTER;
    private TrackAnalysis trackAnalysis;

    private double[] onsetTimes = new double[0];
    private double[] onsetDurations = new double[0];
    private float[] onsetExcessDb = new float[0];   // each onset's level above the median onset
    private double maxExcessDb = 0.0;

    /** Injects the shared per-track analysis (onsets + tempo). */
    public void setTrackAnalysis(TrackAnalysis ta) { this.trackAnalysis = ta; }

    public double getTargetDb() { return targetDb; }

    public void setTargetDb(double db)
    {
        this.targetDb = DspMath.clamp(db, MIN_TARGET_DB, MAX_TARGET_DB);
    }

    public NoteValue getNote() { return note; }

    public void setNote(NoteValue n) { this.note = (n == null) ? NoteValue.QUARTER : n; }

    /** Current tempo in BPM, or 0 if unknown. */
    public double getBpm() { return (trackAnalysis != null) ? trackAnalysis.getBpm() : 0.0; }

    /** Largest amount any beat sits above the median beat level, in dB. */
    public double getMaxExcessDb() { return maxExcessDb; }

    /** The tempo-synced release time in ms for the current tempo and note value. */
    public double getReleaseMs()
    {
        double bpm = getBpm();
        return (bpm <= 0.0) ? FALLBACK_RELEASE_MS : note.beats * 60000.0 / bpm;
    }

    @Override
    protected void computeFeatures(float[] samples, int channels, int sampleRate, int frames)
    {
        TrackAnalysis ta = trackAnalysis;
        if (ta == null || ta.getOnsetCount() == 0 || sampleRate <= 0)
        {
            onsetTimes = new double[0];
            onsetDurations = new double[0];
            onsetExcessDb = new float[0];
            maxExcessDb = 0.0;
            return;
        }
        double[] times = ta.getOnsetTimesSec();
        double[] durs = ta.getOnsetDurationsSec();
        int count = times.length;
        int win = Math.max(1, (int) (PEAK_WINDOW_MS / 1000.0 * sampleRate));

        double[] peakDb = new double[count];
        for (int k = 0; k < count; k++)
        {
            int start = (int) (times[k] * sampleRate);
            float pk = 0.0f;
            for (int i = start; i < start + win && i < frames; i++)
            {
                int base = i * channels;
                for (int c = 0; c < channels; c++)
                {
                    float a = Math.abs(samples[base + c]);
                    if (a > pk) pk = a;
                }
            }
            peakDb[k] = DspMath.gainToDecibels(Math.max(pk, 1e-6f));
        }

        double refDb = median(peakDb);
        float[] excess = new float[count];
        double maxE = 0.0;
        for (int k = 0; k < count; k++)
        {
            double e = Math.max(0.0, peakDb[k] - refDb);
            excess[k] = (float) e;
            if (e > maxE) maxE = e;
        }
        this.onsetTimes = times;
        this.onsetDurations = durs;
        this.onsetExcessDb = excess;
        this.maxExcessDb = maxE;
    }

    @Override
    protected void mapFeaturesToGain()
    {
        int n = envFrames;
        float[] rawTarget = new float[n];
        java.util.Arrays.fill(rawTarget, 1.0f);
        if (onsetExcessDb.length == 0)
        {
            gainEnv = rawTarget;
            return;
        }
        double preRoll = PRE_ROLL_MS / 1000.0;
        for (int k = 0; k < onsetExcessDb.length; k++)
        {
            double reductionDb = -Math.min(onsetExcessDb[k], -targetDb);   // <= 0
            float g = (float) DspMath.decibelsToGain(reductionDb);
            double dur = (k < onsetDurations.length) ? onsetDurations[k] : 0.03;
            int i0 = (int) ((onsetTimes[k] - preRoll) * envRate);
            int i1 = (int) ((onsetTimes[k] + dur) * envRate);
            if (i0 < 0) i0 = 0;
            if (i1 > n - 1) i1 = n - 1;
            for (int i = i0; i <= i1; i++) if (g < rawTarget[i]) rawTarget[i] = g;
        }
        gainEnv = LookaheadGainSmoother.smooth(rawTarget, envRate, LOOKAHEAD_MS, ATTACK_MS, getReleaseMs());
    }

    private static double median(double[] v)
    {
        if (v.length == 0) return 0.0;
        double[] c = v.clone();
        java.util.Arrays.sort(c);
        return c[c.length / 2];
    }
}
