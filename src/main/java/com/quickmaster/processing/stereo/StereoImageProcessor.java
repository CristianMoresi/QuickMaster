package com.quickmaster.processing.stereo;

import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;

/** Worker-owned render processor; only immutable settings/plans cross ownership boundaries. */
public final class StereoImageProcessor implements AudioProcessor, com.quickmaster.processing.OfflineMetering,
        com.quickmaster.processing.OfflineRenderProcessor {
    private final StereoGenerationCache generationCache;
    public StereoImageProcessor() { this(new StereoGenerationCache()); }
    private StereoImageProcessor(StereoGenerationCache cache) { generationCache = cache; }
    public void clearGenerationCache() { generationCache.clear(); }
    int synthesisCount() { return generationCache.synthesisCount(); }
    int filterCount() { return generationCache.filterCount(); }
    long cachedBytes() { return generationCache.bytes(); }
    private boolean enabled;
    private StereoImageSettings settings = StereoImageSettings.DEFAULT;
    private StereoImagePlan plan;
    private StereoDeltaGenerator generator;
    private int rate = 48000;
    private long frame;
    private boolean mono;
    private long totalSamples;
    private double[] meterMid, meterSide, measuredSide = new double[0];
    private double meterStep = .02;
    private boolean metering;
    public StereoImageSettings settings() { return settings; }
    public void setSettings(StereoImageSettings settings) {
        if (settings == null) throw new IllegalArgumentException("Missing settings");
        this.settings = settings;
    }
    public StereoImagePlan plan() { return plan; }
    public StereoImageProcessor fork() {
        var copy = new StereoImageProcessor(generationCache); copy.enabled = enabled; copy.settings = settings;
        return copy;
    }
    public void adoptPlan(StereoImageProcessor source) {
        plan = source.plan; measuredSide = source.measuredSide; meterStep = source.meterStep;
    }
    @Override public void prepare(int rate, long totalSamples) {
        if (rate <= 0) throw new IllegalArgumentException("Invalid sample rate");
        if (enabled && settings.active() && (rate < 8000 || rate > 3072000))
            throw new IllegalArgumentException("Stereo Image supports 8–192 kHz sources with up to 16x oversampling");
        this.rate = rate; this.totalSamples = totalSamples; frame = 0;
        generator = enabled && settings.active() ? new StereoDeltaGenerator(settings, rate) : null;
    }
    @Override public boolean usesAnalysis() { return true; }
    @Override public void analyze(float[] audio, int channels) { analyze(audio, channels, null); }
    @Override public void analyze(float[] audio, int channels, CancellationToken token) {
        mono = channels != 2;
        plan = StereoImageAnalyzer.analyze(audio, rate, channels, settings, 0, Double.NaN, token);
        prepare(rate, audio.length);
    }
    /** Local provisional plan on the preview worker; preserve approved Auto target. */
    public void analyzePreview(float[] audio, int channels, int startFrame, CancellationToken token) {
        mono = channels != 2;
        double target = plan == null ? Double.NaN : plan.targetSideShare();
        plan = StereoImageAnalyzer.analyze(audio, rate, channels, settings, startFrame / (double)rate, target, token);
        prepare(rate, audio.length);
    }
    /** Exact local plan/render in one synthesis pass; never stored in the whole-track cache. */
    public float[] renderPreview(float[] input, int channels, int startFrame, CancellationToken token) {
        if (channels != 2) { analyzePreview(input, channels, startFrame, token); return input; }
        if (input.length % 2 != 0) throw new IllegalArgumentException("Incomplete stereo frame");
        double target = plan == null ? Double.NaN : plan.targetSideShare();
        var local = new StereoDeltaGenerator(settings, rate); local.position(startFrame);
        int frames = input.length / 2, latency = local.latency();
        double[] mid = new double[frames], side = new double[frames];
        for (int f = 0; f < frames + latency; f++) {
            if ((f & 4095) == 0) StereoImageAnalyzer.check(token);
            double l = f < frames ? input[2*f] : 0, r = f < frames ? input[2*f+1] : 0;
            if (!Double.isFinite(l) || !Double.isFinite(r)) throw new IllegalArgumentException("Non-finite stereo PCM");
            local.process(l, r);
            if (f >= latency) {
                mid[f-latency] = .5 * (local.left + local.right);
                side[f-latency] = .5 * (local.left - local.right);
            }
        }
        plan = StereoImageAnalyzer.analyzeMidSide(mid, side, rate, settings, startFrame / (double)rate, target, token);
        mono = false;
        float[] output = new float[input.length];
        double trim = Math.pow(10, settings.sideGainDb() / 20);
        for (int f = 0; f < frames; f++) {
            if ((f & 4095) == 0) StereoImageAnalyzer.check(token);
            double gain = plan.gain(((long)startFrame + f) / (double)rate) * trim;
            output[2*f] = (float)(mid[f] + gain * side[f]);
            output[2*f+1] = (float)(mid[f] - gain * side[f]);
        }
        return output;
    }
    @Override public int getLatencyFrames() {
        return enabled && !mono && generator != null ? generator.latency() : 0;
    }
    @Override public void setPlaybackPosition(long frame) {
        this.frame = frame;
        if (generator != null) generator.position(frame);
    }
    @Override public float[] process(float[] audio, int channels) {
        if (!enabled || channels != 2) return audio;
        if (audio.length % 2 != 0) throw new IllegalArgumentException("Incomplete stereo frame");
        if (!settings.active()) {
            // Enabled module with no selected sections is transparent, but its
            // output meter still describes the real passing audio.
            for (int i = 0; i < audio.length; i += 2) {
                double m = .5 * (audio[i] + (double)audio[i + 1]);
                double s = .5 * (audio[i] - (double)audio[i + 1]);
                recordMeter(m, s, frame++);
            }
            return audio;
        }
        if (generator == null) prepare(rate, audio.length);
        int latency = generator.latency();
        double trim = Math.pow(10, settings.sideGainDb() / 20);
        for (int i = 0; i < audio.length; i += 2) {
            if (!Float.isFinite(audio[i]) || !Float.isFinite(audio[i + 1])) throw new IllegalArgumentException("Non-finite stereo PCM");
            generator.process(audio[i], audio[i + 1]);
            double m = .5 * (generator.left + generator.right), s = .5 * (generator.left - generator.right);
            double gain = (plan == null ? 1 : plan.gain((frame - latency) / (double)rate)) * trim;
            audio[i] = (float)(m + gain * s); audio[i + 1] = (float)(m - gain * s);
            recordMeter(m, gain * s, frame - latency);
            frame++;
        }
        return audio;
    }
    @Override public boolean supportsOfflineRender(int samples, int channels) {
        return channels == 2 && (!settings.generates() || StereoGenerationCache.fits(samples, rate));
    }
    @Override public float[] renderOffline(float[] input, int channels,
            java.util.function.DoubleConsumer progress, CancellationToken token) {
        if (!enabled) return input;
        if (channels != 2 || input.length % 2 != 0) throw new IllegalArgumentException("Require stereo PCM");
        double[] delta = settings.generates()
                ? generationCache.delta(input, rate, settings.generatedLowCutHz(), token) : null;
        plan = StereoImageAnalyzer.analyzeDelta(input, delta, rate, settings, token);
        mono = false;
        float[] output = settings.active() ? new float[input.length] : input;
        double amount = 8 * settings.generationAmount(), trim = Math.pow(10, settings.sideGainDb() / 20);
        boolean regulate = settings.regulates() || settings.guard();
        beginOfflineMetering(channels);
        try {
            for (int f = 0; f < input.length / 2; f++) {
                if ((f & 4095) == 0) {
                    StereoImageAnalyzer.check(token);
                    if (progress != null) progress.accept(f * 2.0 / input.length);
                }
                double d = delta == null ? 0 : delta[f] * amount;
                double l = input[2*f] + d, r = input[2*f+1] - d;
                double m = .5 * (l + r), s = .5 * (l - r);
                double gain = trim * (regulate ? plan.gain(f / (double)rate) : 1);
                if (output != input) { output[2*f] = (float)(m + s*gain); output[2*f+1] = (float)(m - s*gain); }
                recordMeter(m, s*gain, f);
            }
        } finally { endOfflineMetering(); }
        StereoImageAnalyzer.check(token);
        if (progress != null) progress.accept(1);
        return output;
    }
    private void recordMeter(double mid, double side, long alignedFrame) {
        if (!metering || alignedFrame < 0) return;
        int cell = (int)(alignedFrame / Math.max(1, rate / 50));
        if (cell < meterMid.length) { meterMid[cell] += mid*mid; meterSide[cell] += side*side; }
    }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }
    @Override public void beginOfflineMetering(int channels) {
        int hop = Math.max(1, rate / 50);
        int cells = Math.toIntExact((totalSamples / channels + hop - 1) / hop);
        meterMid = new double[cells]; meterSide = new double[cells];
        meterStep = hop / (double)rate; metering = channels == 2;
    }
    @Override public void endOfflineMetering() {
        double[] result = new double[meterMid == null ? 0 : meterMid.length];
        for (int i = 0; i < result.length; i++) result[i] = StereoImageAnalyzer.share(meterMid[i], meterSide[i]);
        measuredSide = result; metering = false; meterMid = meterSide = null;
    }
    public double measuredSideShare(double seconds) {
        int cell = (int)Math.floor(seconds / meterStep + 1e-9);
        return cell < 0 || cell >= measuredSide.length ? Double.NaN : measuredSide[cell];
    }
}
