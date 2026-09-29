package com.quickmaster.processing.stereo;

import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Whole-file decisions run on a worker. No PCM retained in published plans. */
public final class StereoImageAnalyzer {
    private StereoImageAnalyzer() { }
    static StereoImagePlan analyze(float[] audio, int rate, int channels,
            StereoImageSettings settings, double start, double autoTarget, CancellationToken token) {
        if (channels < 1 || channels > 2 || audio.length % channels != 0)
            throw new IllegalArgumentException("Invalid Stereo Image format");
        int frames = audio.length / channels, hop = Math.max(1, rate / 100);
        int count = (frames + hop - 1) / hop;
        double[] m = new double[count], s = new double[count];
        if (channels == 1 || frames == 0) return new StereoImagePlan(new double[count], new double[count],
                hop / (double)rate, start, .12, 0, 0, channels == 1 ? "Mono file: stereo processing unavailable" : "No audio");
        // Generation alone has no gain plan to solve: avoid synthesizing the
        // entire harmonic track twice just to produce an unused target.
        var generator = settings.regulates() || settings.guard() ? new StereoDeltaGenerator(settings, rate) : null;
        if (generator != null) generator.position(Math.round(start * rate));
        int latency = generator == null ? 0 : generator.latency();
        for (int f = 0; f < frames + latency; f++) {
            if ((f & 4095) == 0) check(token);
            double l = f < frames ? audio[2 * f] : 0, r = f < frames ? audio[2 * f + 1] : 0;
            if (!Double.isFinite(l) || !Double.isFinite(r)) throw new IllegalArgumentException("Non-finite stereo PCM");
            if (generator != null) generator.process(l, r);
            int aligned = f - latency;
            if (aligned >= 0 && aligned < frames) {
                double left = generator == null ? l : generator.left, right = generator == null ? r : generator.right;
                double mid = .5 * (left + right), side = .5 * (left - right);
                m[aligned / hop] += mid * mid; s[aligned / hop] += side * side;
            }
        }
        for (int i = 0; i < count; i++) {
            int n = Math.min(hop, frames - i * hop); m[i] /= n; s[i] /= n;
        }
        return plan(m, s, hop / (double)rate, start, settings, autoTarget, token);
    }

    /** Analyze an already synthesized delta; controls do not repeat synthesis. */
    static StereoImagePlan analyzeDelta(float[] audio, double[] delta, int rate,
            StereoImageSettings settings, CancellationToken token) {
        int frames = audio.length / 2, hop = Math.max(1, rate / 100);
        double[] m = new double[(frames + hop - 1) / hop], s = new double[m.length];
        double amount = 8 * settings.generationAmount();
        for (int f = 0; f < frames; f++) {
            if ((f & 4095) == 0) check(token);
            double d = delta == null ? 0 : delta[f] * amount;
            double l = audio[2*f] + d, r = audio[2*f+1] - d;
            if (!Double.isFinite(l) || !Double.isFinite(r)) throw new IllegalArgumentException("Non-finite stereo PCM");
            double mid = .5 * (l + r), side = .5 * (l - r);
            m[f / hop] += mid*mid; s[f / hop] += side*side;
        }
        for (int i = 0; i < m.length; i++) {
            int n = Math.min(hop, frames - i*hop); m[i] /= n; s[i] /= n;
        }
        return plan(m, s, hop / (double)rate, 0, settings, Double.NaN, token);
    }

    /** Energy analysis of a synthesized preview, without repeating its generator. */
    static StereoImagePlan analyzeMidSide(double[] mid, double[] side, int rate,
            StereoImageSettings settings, double start, double autoTarget, CancellationToken token) {
        int hop = Math.max(1, rate / 100), frames = mid.length;
        double[] m = new double[(frames + hop - 1) / hop], s = new double[m.length];
        for (int f = 0; f < frames; f++) {
            if ((f & 4095) == 0) check(token);
            m[f / hop] += mid[f] * mid[f]; s[f / hop] += side[f] * side[f];
        }
        for (int i = 0; i < m.length; i++) {
            int n = Math.min(hop, frames - i * hop); m[i] /= n; s[i] /= n;
        }
        return plan(m, s, hop / (double)rate, start, settings, autoTarget, token);
    }

    static StereoImagePlan plan(double[] m, double[] s, double step, double start,
            StereoImageSettings settings, double autoTarget, CancellationToken token) {
        int n = m.length;
        double pm = Arrays.stream(m).sum(), ps = Arrays.stream(s).sum();
        double observed = share(pm, ps);
        // Auto follows this track's measured post-generation balance, not a genre guess.
        double target = settings.profile() == StereoProfile.REFERENCE ? settings.referenceSideShare()
                : settings.profile() != StereoProfile.AUTO ? settings.profile().sideShare()
                : Double.isFinite(autoTarget) ? autoTarget : Double.isFinite(observed) ? observed : .12;
        target = Math.max(.001, Math.min(.49, target));
        double[] level = new double[n], guard = new double[n];
        double[] mm = average(m, Math.max(1, (int)Math.round(.2 / step)));
        double[] ss = average(s, Math.max(1, (int)Math.round(.2 / step)));
        double gate = Math.max(1e-14, (pm + ps) / Math.max(1, n) * 1e-6);
        double ratio = target / (1 - target);
        boolean limited = false;
        if (settings.regulates()) {
            for (int i = 0; i < n; i++) {
                if ((i & 4095) == 0) check(token);
                if (mm[i] <= gate || ss[i] <= gate) continue;
                double ideal = .5 * (Math.log(ratio) + Math.log(mm[i]) - Math.log(ss[i]));
                double bounded = Math.max(-24 * Math.log(10) / 20, Math.min(12 * Math.log(10) / 20, ideal));
                if (Math.abs(ideal - bounded) > .001) limited = true;
                level[i] = settings.levelingAmount() * bounded;
            }
            // Symmetric triangular smoothing: anticipation without waveform-rate gain riding.
            int radius = Math.max(1, (int)Math.round(.5 / step));
            level = average(average(level, radius), radius);
        }
        if (settings.guard()) {
            double ceilingRatio = ratio * Math.pow(10, settings.guardMarginDb() / 10);
            double[] required = new double[n];
            int window = Math.max(1, (int)Math.round(.04 / step));
            for (int i = 0; i < n; i++) {
                // Forty-millisecond energy windows avoid following individual bass
                // cycles. Bound every Leveler gain within this whole window.
                double wm = 0, ws = 0, maxLevel = Double.NEGATIVE_INFINITY;
                int end = Math.min(n, i + window);
                for (int j = i; j < end; j++) {
                    wm += m[j]; ws += s[j];
                    maxLevel = Math.max(maxLevel, Math.max(level[j], level[Math.min(n - 1, j + 1)]));
                }
                if (ws > gate * (end - i)) {
                    double safe = .5 * Math.log(Math.max(1e-30, ceilingRatio * wm) / ws) - maxLevel;
                    required[i] = Math.min(0, Math.max(-60 * Math.log(10) / 20, safe));
                }
            }
            // Every endpoint respects ALL energy windows that contain either
            // adjacent cell, then smooth by only
            // adding attenuation. Never smooth a ceiling upward into an overshoot.
            for (int i = 0; i < n; i++)
                for (int j = Math.max(0, i - window); j <= i; j++) guard[i] = Math.min(guard[i], required[j]);
            double rise = Math.log(10) / 20 * 20 * step; // <=20 dB/s release
            for (int i = 1; i < n; i++) guard[i] = Math.min(guard[i], guard[i - 1] + rise);
            double attack = Math.log(10) / 20 * 60 * step; // anticipatory <=60 dB/s
            for (int i = n - 2; i >= 0; i--) guard[i] = Math.min(guard[i], guard[i + 1] + attack);
        }
        double post = 0, sidePowerGain = Math.pow(10, settings.sideGainDb() / 10);
        for (int i = 0; i < n; i++) post += s[i] * Math.exp(2 * (level[i] + guard[i])) * sidePowerGain;
        String notice = pm + ps == 0 ? "Silence: no stereo target" : pm <= gate * n
                ? "Mid is absent: Side attenuation cannot restore mono content"
                : ps <= gate * n && !settings.generates() ? "No usable Side: enable Generation to create stereo"
                : limited ? "Leveling constrained by the +12 / -24 dB range" : "";
        return new StereoImagePlan(level, guard, step, start, target, observed, share(pm, post), notice);
    }

    /** Energy fraction, not perceived volume. NaN deliberately represents silence. */
    public static double share(double mid, double side) {
        if (!Double.isFinite(mid) || !Double.isFinite(side) || mid < 0 || side < 0)
            throw new IllegalArgumentException("Invalid stereo power");
        double scale = Math.max(mid, side);
        return scale == 0 ? Double.NaN : (side / scale) / (mid / scale + side / scale);
    }
    private static double[] average(double[] x, int radius) {
        double[] prefix = new double[x.length + 1], out = new double[x.length];
        for (int i = 0; i < x.length; i++) prefix[i + 1] = prefix[i] + x[i];
        for (int i = 0; i < x.length; i++) {
            int a = Math.max(0, i - radius), b = Math.min(x.length, i + radius + 1);
            out[i] = (prefix[b] - prefix[a]) / (b - a);
        }
        return out;
    }
    static void check(CancellationToken token) {
        if (Thread.currentThread().isInterrupted() || token != null && token.isCancelled()) throw new CancellationException();
    }
}
