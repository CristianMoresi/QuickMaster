package com.quickmaster.processing.stereo;

/** Immutable low-rate, log-gain automation. Sample-rate independent source clock. */
public final class StereoImagePlan {
    private final double[] level, guard;
    private final double step, start;
    private final double targetSide, inputSide, outputSide;
    private final String notice;
    StereoImagePlan(double[] level, double[] guard, double step, double start,
            double targetSide, double inputSide, double outputSide, String notice) {
        this.level = level.clone(); this.guard = guard.clone(); this.step = step; this.start = start;
        this.targetSide = targetSide; this.inputSide = inputSide; this.outputSide = outputSide;
        this.notice = notice;
    }
    public double targetSideShare() { return targetSide; }
    public double inputSideShare() { return inputSide; }
    public double estimatedOutputSideShare() { return outputSide; }
    public String notice() { return notice; }
    public double levelGainDb(double seconds) { return at(level, seconds) * 20 / Math.log(10); }
    public double guardGainDb(double seconds) { return at(guard, seconds) * 20 / Math.log(10); }
    double gain(double seconds) { return Math.exp(at(level, seconds) + at(guard, seconds)); }
    private double at(double[] values, double seconds) {
        if (values.length == 0) return 0;
        double t = Math.max(0, Math.min(values.length - 1, (seconds - start) / step));
        int index = (int)t;
        return values[index] + (values[Math.min(index + 1, values.length - 1)] - values[index]) * (t - index);
    }
}
