package com.quickmaster.processing.stereo;

import com.quickmaster.processing.dynamics.leveler.CancellationToken;

/** One bounded immutable raw delta plus one filtered variant. Snapshot forks
 * share this owner, never synthesis state. Expensive work never holds its lock.
 * Input arrays are immutable pipeline publications; identity is the cache key.
 */
final class StereoGenerationCache {
    private static final long BUDGET = 256L * 1024 * 1024;
    private record Raw(float[] input, int rate, int latency, double[] samples) {}
    private record Filtered(Raw raw, double cutoff, double[] samples) {}
    private volatile Raw raw;
    private volatile Filtered filtered;
    private long revision;
    private int syntheses, filters;

    static boolean fits(int samples, int rate) {
        return ((long)samples + rate / 4 + 1024) * Double.BYTES <= BUDGET;
    }
    synchronized void clear() { revision++; raw = null; filtered = null; }
    synchronized int synthesisCount() { return syntheses; }
    synchronized int filterCount() { return filters; }
    long bytes() {
        Raw r = raw; Filtered f = filtered;
        return (r == null ? 0 : 8L * r.samples.length) + (f == null ? 0 : 8L * f.samples.length);
    }
    double[] delta(float[] input, int rate, double cutoff, CancellationToken token) {
        StereoImageAnalyzer.check(token);
        Raw r = raw;
        if (r == null || r.input != input || r.rate != rate) r = generate(input, rate, token);
        Filtered f = filtered;
        if (f != null && f.raw == r && f.cutoff == cutoff) return f.samples;
        int frames = input.length / 2;
        double[] result = new double[frames];
        if (cutoff == 0) {
            System.arraycopy(r.samples, r.latency, result, 0, frames);
        } else {
            double[] taps = PartitionedFir.lowCut(rate, cutoff);
            int block = Integer.highestOneBit(Math.max(64, (int)Math.round(rate / 93.75)));
            var fir = new PartitionedFir(taps, block);
            int latency = r.latency + (taps.length - 1) / 2 + block;
            for (int i = 0; i < frames + latency; i++) {
                if ((i & 4095) == 0) StereoImageAnalyzer.check(token);
                double value = fir.process(r.samples[i]);
                if (i >= latency) result[i - latency] = value;
            }
        }
        StereoImageAnalyzer.check(token);
        synchronized (this) {
            if (raw == r) { filtered = new Filtered(r, cutoff, result); filters++; }
        }
        return result;
    }
    private Raw generate(float[] input, int rate, CancellationToken token) {
        final long ticket;
        synchronized (this) {
            if (raw != null && raw.input == input && raw.rate == rate) return raw;
            ticket = ++revision; raw = null; filtered = null;
        }
        var settings = new StereoImageSettings(true, 1, 0.0, false, 0, false, 3, StereoProfile.AUTO, .12, 0);
        var generator = new StereoDeltaGenerator(settings, rate);
        int frames = input.length / 2;
        // Preserve the causal harmonic stream, including negative-time FIR
        // ringing and the flush tail. Filtering it then compensating latency
        // matches the streaming processor, without boundary approximations.
        double[] samples = new double[Math.addExact(frames, rate / 4 + 1024)];
        for (int i = 0; i < samples.length; i++) {
            if ((i & 4095) == 0) StereoImageAnalyzer.check(token);
            double left = i < frames ? input[2*i] : 0, right = i < frames ? input[2*i+1] : 0;
            if (!Double.isFinite(left) || !Double.isFinite(right)) throw new IllegalArgumentException("Non-finite stereo PCM");
            generator.process(left, right);
            samples[i] = generator.rawDelta;
        }
        StereoImageAnalyzer.check(token);
        Raw made = new Raw(input, rate, generator.harmonicLatency(), samples);
        synchronized (this) { if (revision == ticket) { raw = made; syntheses++; } }
        return made;
    }
}
