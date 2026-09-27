package com.quickmaster.processing.dynamics.macro;

/** Immutable 10 Hz linear-amplitude automation; no frame-sized envelope or PCM retained. */
public final class MacroGainCurve {
    private final double[] gains;
    private final int rate, frames, hop;

    MacroGainCurve(int rate, int frames, int hop, double[] gains) {
        this.rate = rate; this.frames = frames; this.hop = hop;
        this.gains = gains.clone();
        for (double g : this.gains) if (!Double.isFinite(g) || g <= 0)
            throw new IllegalArgumentException("Invalid macro gain.");
    }
    public int sourceRate() { return rate; }
    public int sourceFrames() { return frames; }
    public int controlPoints() { return gains.length; }
    public double linearAt(double frame) {
        if (frame < 0 || frame >= frames || !Double.isFinite(frame)) return 1;
        double position = frame / hop;
        int index = (int)position;
        if (index >= gains.length - 1) return gains[gains.length - 1];
        double a = gains[index], b = gains[index + 1];
        return a + (b - a) * (position - index);
    }
    public double dbAt(double frame) { return 20 * Math.log10(linearAt(frame)); }
    public double minimumDb() {
        double min = Double.POSITIVE_INFINITY;
        for (double g : gains) min = Math.min(min, g);
        return 20 * Math.log10(min);
    }
    public double maximumDb() {
        double max = 0;
        for (double g : gains) max = Math.max(max, g);
        return 20 * Math.log10(max);
    }
    MacroGainCurve scaled(double factor) {
        double[] copy = gains.clone();
        for (int i = 0; i < copy.length; i++) copy[i] *= factor;
        return new MacroGainCurve(rate, frames, hop, copy);
    }
}
