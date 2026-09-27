package com.quickmaster.processing.dynamics;

import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import com.quickmaster.processing.dynamics.macro.MacroLevelerEngine;
import java.util.concurrent.CancellationException;

/** Product Leveler: continuous offline macro RMS automation, not cohort matching. */
public final class MacroLevelerProcessor extends AnalysisDynamicsProcessor {
    public static final double DEFAULT_LEVELING = .5, MIN_LEVELING = 0, MAX_LEVELING = 1;
    public static final double DEFAULT_SPEED = .5, MIN_SPEED = 0, MAX_SPEED = 1;
    private volatile double leveling = DEFAULT_LEVELING, speed = DEFAULT_SPEED;
    private record Publication(MacroLevelerEngine.Result result, String status) { }
    private volatile Publication publication = new Publication(null, "UNIT");
    private int rate;
    private long cursor;
    private volatile double meter;

    public double getLeveling() { return leveling; }
    public double getSpeed() { return speed; }

    public synchronized void setLeveling(double value) {
        double next = control(value);
        if (next != leveling) { leveling = next; clearAnalysis(); }
    }

    public synchronized void setSpeed(double value) {
        double next = control(value);
        if (next != speed) { speed = next; clearAnalysis(); }
    }

    private static double control(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Control must be finite.");
        return Math.max(0, Math.min(1, value));
    }

    public String getAnalysisDiagnostic() { return publication.status(); }

    public MacroLevelerEngine.Report getAnalysisReport() {
        var snapshot = publication;
        return snapshot.result() == null ? null : snapshot.result().report();
    }

    /** The worker owns its analysis. No source PCM, mutable cache or old gain is shared. */
    public synchronized MacroLevelerProcessor forkForAnalysis(double amount, double speed) {
        var fork = new MacroLevelerProcessor();
        fork.setLeveling(amount);
        fork.setSpeed(speed);
        fork.setEnabled(isEnabled());
        return fork;
    }

    @Override public void prepare(int sampleRate, long totalSamples) {
        rate = sampleRate > 0 && totalSamples >= 0 ? sampleRate : 0;
        cursor = 0;
        meter = 0;
        // Preserve the immutable source-clock plan across playback/export prepares.
    }

    @Override public void setPlaybackPosition(long frame) { cursor = frame; meter = 0; }

    @Override public synchronized void analyze(float[] samples, int channels) {
        analyze(samples, channels, null);
    }

    @Override public synchronized void analyze(float[] samples, int channels, CancellationToken token) {
        publication = new Publication(null, "UNIT");
        try {
            var result = new MacroLevelerEngine().analyze(samples, channels, rate, leveling, speed, token);
            publication = new Publication(result, result.report().status());
        } catch (CancellationException ex) {
            publication = new Publication(null, "CANCELLED");
            throw ex;
        } catch (IllegalArgumentException ex) {
            publication = new Publication(null, "INVALID_INPUT");
        } catch (IllegalStateException ex) {
            publication = new Publication(null, "PEAK_UNSAFE");
            throw ex;
        }
    }

    @Override public boolean isAnalyzed() { return publication.result() != null; }
    @Override public double getGainReductionDb() { return meter; }

    @Override public double getGainDbAtPosition(long sourceFrame) {
        var snapshot = publication;
        return !enabled || snapshot.result() == null ? 0 : snapshot.result().curve().dbAt(sourceFrame);
    }

    @Override public float[] process(float[] buffer, int channels) {
        var snapshot = publication; // exactly one atomic publication per block
        long start = cursor;
        if (buffer == null || channels < 1 || buffer.length % channels != 0) {
            meter = 0;
            return buffer;
        }
        int frames = buffer.length / channels;
        if (start < 0 || start > Long.MAX_VALUE - frames) { meter = 0; return buffer; }
        cursor = start + frames;
        if (!enabled || rate <= 0 || snapshot.result() == null || snapshot.result().channels() != channels) {
            meter = 0;
            return buffer;
        }
        for (float sample : buffer) if (!Float.isFinite(sample)) { meter = 0; return buffer; }
        var curve = snapshot.result().curve();
        double ratio = curve.sourceRate() / (double)rate;
        double minimum = 1, maximum = 1;
        for (int f = 0; f < frames; f++) {
            double gain = curve.linearAt((start + f) * ratio);
            if (gain != 1) for (int c = 0; c < channels; c++)
                buffer[f * channels + c] = (float)(buffer[f * channels + c] * gain);
            minimum = Math.min(minimum, gain);
            maximum = Math.max(maximum, gain);
        }
        double lo = 20 * Math.log10(minimum), hi = 20 * Math.log10(maximum);
        meter = Math.abs(lo) > hi ? lo : hi;
        return buffer;
    }

    @Override public synchronized void adoptEnvelope(AnalysisDynamicsProcessor src) {
        if (!(src instanceof MacroLevelerProcessor macro))
            throw new IllegalArgumentException("Macro publication required.");
        publication = macro.publication;
    }

    @Override synchronized void clearAnalysis() {
        publication = new Publication(null, "CLEARED");
        meter = 0;
    }

    // This subclass participates in the common dynamics UI/lifecycle, but must
    // never enter the historical dense/sparse mapping paths in the base class.
    @Override protected void computeFeatures(float[] samples, int channels, int sampleRate, int frames) {
        throw new UnsupportedOperationException("Macro engine owns analysis.");
    }

    @Override protected void mapFeaturesToGain() {
        throw new UnsupportedOperationException("Macro engine owns gain publication.");
    }
}
