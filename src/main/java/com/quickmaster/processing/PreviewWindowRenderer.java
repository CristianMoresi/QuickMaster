package com.quickmaster.processing;

import com.dspark.analysis.TruePeak;
import com.dspark.core.OversamplingEngine;
import com.dspark.effects.MasterEqualizer;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import com.quickmaster.processing.eq.EqualizerProcessor;
import com.quickmaster.processing.stereo.StereoImageProcessor;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Worker-owned provisional audition. Never analyzes downstream stages or a whole song. */
public final class PreviewWindowRenderer {
    private final ProcessingPipeline pipeline;
    private final float[] source;
    private final int rate, channels, factor, history;
    private boolean calibrated;
    private double safetyGain = 1;

    public PreviewWindowRenderer(ProcessingPipeline pipeline, float[] source, int rate, int channels, int factor) {
        if (rate <= 0 || channels < 1 || channels > 2 || source.length % channels != 0
                || factor < 1 || factor > 16 || Integer.bitCount(factor) != 1)
            throw new IllegalArgumentException("Invalid preview format");
        this.pipeline = pipeline; this.source = source; this.rate = rate;
        this.channels = channels; this.factor = factor;
        double seconds = .12;
        for (AudioProcessor p : pipeline.getProcessors()) if (p instanceof StereoImageProcessor si && p.isEnabled())
            seconds = Math.max(seconds, si.settings().regulates() ? 1.3 : .3);
        for (AudioProcessor p : pipeline.getProcessors()) if (p instanceof EqualizerProcessor eq && p.isEnabled()) {
            for (int i = 0; i < eq.getNumBands(); i++) {
                MasterEqualizer.Band b = eq.getBand(i);
                if (!b.enabled) continue;
                if (b.dynamic) seconds = Math.max(seconds, 1.5);
                else if (b.phase == MasterEqualizer.BandPhase.MINIMUM)
                    seconds = Math.max(seconds, Math.min(2, 4.5 * Math.max(1, b.q) / Math.max(10, b.frequency)));
            }
        }
        // Finite FIR history is exact; very long IIR/detector histories are a
        // bounded preview approximation, never an export replacement.
        history = (int)Math.ceil(seconds * rate);
    }

    public float[] render(int start, int frames, CancellationToken cancellation) {
        check(cancellation);
        if (start < 0 || frames < 0 || (long)start + frames > source.length / channels)
            throw new IllegalArgumentException("Window outside source");
        int from = Math.max(0, start - history);
        int to = Math.min(source.length / channels, start + frames + rate / 8);
        float[] context = Arrays.copyOfRange(source, from * channels, to * channels);
        pipeline.prepare(rate, source.length);
        float[] base = null;
        boolean localStereoPlan = pipeline.getProcessors().stream().anyMatch(p -> p instanceof StereoImageProcessor && p.isEnabled());
        if (!calibrated || factor == 1 || localStereoPlan) {
            base = context;
            for (AudioProcessor p : pipeline.getProcessors()) {
                check(cancellation);
                if (!p.isEnabled() && !(p instanceof PeakNormalizer n && n.isSafetyEnabled())) continue;
                float[] dry = base;
                if (!calibrated && p instanceof EqualizerProcessor eq) eq.beginAutoGainAnalysis();
                if (p instanceof StereoImageProcessor si) base = si.renderPreview(base, channels, from, cancellation);
                else base = renderStage(p, base, from, cancellation);
                if (!calibrated && p instanceof EqualizerProcessor eq)
                    eq.finishAutoGainAnalysis(dry, base, channels, cancellation);
            }
            calibrated = true;
        }
        float[] processed = factor == 1 ? base : renderOversampled(context, from, cancellation);
        float[] output = Arrays.copyOfRange(processed, (start - from) * channels, (start - from + frames) * channels);
        boolean safety = pipeline.usesEqOutputSafety();
        double ceilingDb = -1; // Monitoring margin for overlapping provisional windows.
        for (AudioProcessor p : pipeline.getProcessors()) if (p instanceof PeakNormalizer n && n.isEnabled()) {
            safety = true; ceilingDb = Math.min(ceilingDb, n.getTargetDbfs());
        }
        for (float v : output) if (!Float.isFinite(v)) throw new IllegalStateException("Non-finite preview PCM");
        if (safety) {
            double peak = TruePeak.measureMax(output, channels);
            if (peak > 0) safetyGain = Math.min(safetyGain, Math.pow(10, ceilingDb / 20) / peak);
            // One linked scalar per window, never clip samples. Hold attenuation
            // until the next edit instead of riding each quiet/loud passage.
            if (safetyGain < 1) for (int i = 0; i < output.length; i++) output[i] *= (float)safetyGain;
        }
        check(cancellation);
        return output;
    }

    public double getEqGainDb() {
        for (AudioProcessor p : pipeline.getProcessors()) if (p instanceof EqualizerProcessor eq) return eq.getAutoGainDb();
        return 0;
    }
    public double getOutputGainDb() {
        double db = 20 * Math.log10(safetyGain);
        for (AudioProcessor p : pipeline.getProcessors()) if (p instanceof PeakNormalizer n) db += n.getGainDb();
        return db;
    }

    private float[] renderStage(AudioProcessor p, float[] input, int from, CancellationToken cancellation) {
        int latency = p.getLatencyFrames(), total = input.length / channels;
        float[] output = new float[input.length];
        p.setPlaybackPosition(from);
        for (int cursor = 0; cursor < total + latency; ) {
            check(cancellation);
            int frames = Math.min(1024, total + latency - cursor);
            float[] block = new float[frames * channels];
            int copy = Math.max(0, Math.min(frames, total - cursor));
            if (copy > 0) System.arraycopy(input, cursor * channels, block, 0, copy * channels);
            float[] out = p.process(block, channels);
            int first = Math.max(cursor, latency), last = Math.min(cursor + frames, total + latency);
            if (last > first) System.arraycopy(out, (first - cursor) * channels,
                    output, (first - latency) * channels, (last - first) * channels);
            cursor += frames;
        }
        return output;
    }

    private float[] renderOversampled(float[] input, int from, CancellationToken cancellation) {
        pipeline.prepare(Math.multiplyExact(rate, factor), (long)source.length * factor);
        // Do not split one base block into many tiny high-rate FFTs. Prepared
        // preview processors own capacity for the complete oversampled block.
        for (AudioProcessor p : pipeline.getProcessors()) if (p instanceof EqualizerProcessor eq)
            eq.preparePreviewBlocks(channels, 1024 * factor);
        var os = new OversamplingEngine();
        os.prepare(factor, channels, 1024, OversamplingEngine.Quality.HIGH);
        int latency = os.getLatencyBaseFrames() + (int)Math.round(pipeline.getLatencyFrames() / (double)factor);
        pipeline.setPlaybackPosition((long)from * factor - os.getUpsampleLatencyHiFrames());
        int frames = input.length / channels;
        float[] result = new float[input.length], down = new float[1024 * channels];
        for (int cursor = 0; cursor < frames + latency; cursor += 1024) {
            check(cancellation);
            float[] block = new float[1024 * channels];
            int copy = Math.max(0, Math.min(1024, frames - cursor));
            if (copy > 0) System.arraycopy(input, cursor * channels, block, 0, copy * channels);
            float[] high = pipeline.execute(os.upsample(block, 1024), channels);
            os.downsample(high, 1024, down);
            int first = Math.max(0, latency - cursor), last = Math.min(1024, frames + latency - cursor);
            if (last > first) System.arraycopy(down, first * channels, result,
                    (cursor + first - latency) * channels, (last - first) * channels);
        }
        return result;
    }

    private static void check(CancellationToken token) {
        if (Thread.currentThread().isInterrupted() || token.isCancelled()) throw new CancellationException();
    }
}
