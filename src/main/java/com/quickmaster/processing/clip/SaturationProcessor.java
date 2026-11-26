package com.quickmaster.processing.clip;

import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.OfflineMetering;
import com.quickmaster.processing.OfflineRenderProcessor;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * The saturation stage: one control, the decibels of peak the saturation
 * takes off, no more and no less.
 * <p>
 * The three curves are memoryless and solved in closed form against the
 * song's peak ({@link SoftClipProcessor}): the peak loses exactly the amount
 * and every quieter moment less. The analog models are DSPark's analog
 * saturation driven by {@link AnalogSaturation}: the song enters each at its
 * alignment, and the drive is solved so that the most saturated moment of the
 * song loses exactly the amount against the model's own linear response (its
 * equalization and filters are not counted as saturation), with the loudness
 * kept. The meter reads, hop by hop, what the saturation takes off, so its
 * deepest reading is the knob's. At the analysis rate the whole song is
 * rendered at once; at an oversampled rate, and for a provisional preview,
 * the model streams with the solved drive and gain. Zero is a bit-exact
 * bypass for every type.
 */
public final class SaturationProcessor implements AudioProcessor, OfflineMetering, OfflineRenderProcessor
{
    public static final double DEFAULT_SAT_DB = 1, MIN_SAT_DB = 0, MAX_SAT_DB = 12;
    private static final int BLOCK = 2048;

    private final SoftClipProcessor curve = new SoftClipProcessor();
    private volatile SaturationType type = SaturationType.SINE;
    private volatile TapePreset tapePreset = TapePreset.DEFAULT;
    private volatile double satDb = DEFAULT_SAT_DB;
    private volatile boolean enabled;

    private volatile AnalogSaturation.Analysis analysis;
    private volatile List<AnalogSaturation.Level> levels = List.of();
    private volatile String levelsKey = "";
    private volatile AnalogSaturation.Solution solution = AnalogSaturation.Solution.BYPASS;
    private volatile String solutionKey = "";
    private volatile AnalogSaturation.Linear linear;
    private volatile String linearKey = "";
    private volatile float[] grEnv = new float[0];

    private int preparedRate = 48000;
    private long position;
    private Stream stream;
    private float[] meterOut;

    public SaturationProcessor()
    {
        curve.setCurve(SaturationType.SINE);
        curve.setSatDb(DEFAULT_SAT_DB);
    }

    public double getSatDb() { return satDb; }

    public void setSatDb(double db)
    {
        if (!Double.isFinite(db)) return;
        satDb = Math.max(MIN_SAT_DB, Math.min(MAX_SAT_DB, db));
        curve.setSatDb(satDb);
    }

    public SaturationType getType() { return type; }

    public void setType(SaturationType t)
    {
        if (t == null) return;
        type = t;
        if (t.isCurve()) curve.setCurve(t);
    }

    public TapePreset getTapePreset() { return tapePreset; }

    public void setTapePreset(TapePreset p) { if (p != null) tapePreset = p; }

    /** The drive the analog model was solved at, in decibels (0 for a curve or before a render). */
    public double getSolvedDriveDb() { return analog() ? solution.driveDb() : 0.0; }

    /**
     * The peak the analog model's linear response gives the song at the
     * input's loudness, in dBFS (NaN for a curve or before a render).
     */
    public double getLinearPeakDb()
    {
        AnalogSaturation.Linear l = linear;
        AnalogSaturation.Analysis a = analysis;
        return analog() && l != null && a != null && linearKey.equals(modelKey() + "#" + a.fingerprint())
                ? l.peakDb() : Double.NaN;
    }

    /** The loudness the analog model left against its input, in decibels (0 when kept). */
    public double getLoudnessChangeDb() { return analog() ? solution.loudnessDb() : 0.0; }

    /** True when the circuit could not reach the reduction alone and the sine knee after it took the rest. */
    public boolean isCompletedByKnee() { return analog() && solution.clips(); }

    /** How many whole renders the last solve took (0 for a curve or a provisional solution). */
    public int getSolveRenders() { return analog() ? solution.renders() : 0; }

    private boolean analog() { return !type.isCurve(); }

    private boolean active() { return enabled && satDb > 0.0; }

    /** What the analog model is: its type and, for tape, its alignment. */
    private String modelKey() { return type == SaturationType.TAPE ? type + "/" + tapePreset : type.name(); }

    /** What the analog solution answers: the model and the reduction. */
    private String settingsKey() { return modelKey() + "@" + satDb; }

    private Supplier<AnalogModel> factory() { return AnalogModels.factory(type, tapePreset); }

    private static int workers()
    {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean e)
    {
        enabled = e;
        curve.setEnabled(e);
    }

    @Override public boolean usesAnalysis() { return true; }

    @Override
    public void prepare(int sampleRate, long totalSamples)
    {
        if (sampleRate <= 0 || totalSamples < 0) throw new IllegalArgumentException("Invalid saturation preparation.");
        curve.prepare(sampleRate, totalSamples);
        preparedRate = sampleRate;
        position = 0;
        stream = null;
    }

    @Override
    public void setPlaybackPosition(long frame)
    {
        position = frame;
        curve.setPlaybackPosition(frame);
    }

    @Override
    public void analyze(float[] input, int channels)
    {
        analyze(input, channels, null);
    }

    @Override
    public void analyze(float[] input, int channels, CancellationToken cancellation)
    {
        if (!analog())
        {
            curve.analyze(input, channels, cancellation);
            return;
        }
        solveWhole(input, channels, cancellation);
    }

    @Override
    public boolean supportsOfflineRender(int samples, int channels)
    {
        return analog() && active() && preparedRate > 0;
    }

    @Override
    public float[] renderOffline(float[] input, int channels, DoubleConsumer progress, CancellationToken token)
    {
        float[] out = solveWhole(input, channels, token);
        if (progress != null) progress.accept(1.0);
        return out;
    }

    /**
     * Measures the input, solves the drive on whole renders and keeps the
     * meter; returns the output. Nothing measured before enters the solve, so
     * the result depends on the input and the settings alone.
     */
    private float[] solveWhole(float[] input, int channels, CancellationToken cancellation)
    {
        validate(input, channels);
        AnalogSaturation.Analysis a = AnalogSaturation.analyze(input, channels, preparedRate, cancellation);
        String key = modelKey() + "#" + a.fingerprint();
        stream = null;
        if (!active())
        {
            analysis = a;
            levelsKey = key;
            levels = List.of();
            solution = AnalogSaturation.Solution.BYPASS;
            solutionKey = settingsKey();
            grEnv = new float[a.hopPeak().length];
            return input;
        }
        // The linear response depends on the input and the model alone, so the one of the same input is kept.
        AnalogSaturation.Linear lin = key.equals(linearKey) && linear != null ? linear
                : AnalogSaturation.linear(input, a, factory(), workers(), cancellation);
        linear = lin;
        linearKey = key;
        AnalogSaturation.Result r = AnalogSaturation.render(input, a, lin, satDb, factory(), workers(), cancellation);
        analysis = r.analysis();
        levelsKey = key;
        levels = r.levels();
        solution = r.solution();
        solutionKey = settingsKey();
        grEnv = AnalogSaturation.meter(lin, r.hopPeakOut());
        return r.output();
    }

    /** The solution for the current settings from the loud moments alone, for a provisional preview. */
    private void solveProvisional()
    {
        AnalogSaturation.Analysis a = analysis;
        if (a == null || settingsKey().equals(solutionKey)) return;
        if (!active() || a.silent())
        {
            solution = AnalogSaturation.Solution.BYPASS;
            solutionKey = settingsKey();
            return;
        }
        String key = modelKey() + "#" + a.fingerprint();
        List<AnalogSaturation.Level> known = key.equals(levelsKey) ? levels : List.of();
        solution = AnalogSaturation.provisional(a, key.equals(linearKey) ? linear : null, satDb, factory(), known,
                workers(), null);
        solutionKey = settingsKey();
    }

    /** True when the type is an analog circuit rather than a curve. */
    public boolean isAnalogModel() { return analog(); }

    /** Takes the analysis of the approved stage; solves this one's settings provisionally if they differ. */
    public void adoptAnalysis(SaturationProcessor other)
    {
        curve.adoptAnalysis(other.curve);
        analysis = other.analysis;
        levelsKey = other.levelsKey;
        levels = other.levels;
        solution = other.solution;
        solutionKey = other.solutionKey;
        linear = other.linear;
        linearKey = other.linearKey;
        grEnv = other.grEnv;
        if (analog() && !settingsKey().equals(solutionKey)) solveProvisional();
    }

    /** Peak reduction of this stage at a frame of the analysis rate, in dB (at most 0). */
    public double getGrAtPosition(long baseFrame)
    {
        if (!analog()) return curve.getGrAtPosition(baseFrame);
        float[] env = grEnv;
        if (!active() || baseFrame < 0) return 0;
        long h = baseFrame / AnalogSaturation.HOP;
        return h < env.length ? env[(int) h] : 0;
    }

    @Override
    public int getLatencyFrames()
    {
        if (!analog() || !active() || solution == AnalogSaturation.Solution.BYPASS) return 0;
        return stream().model.latency();
    }

    private Stream stream()
    {
        Stream s = stream;
        AnalogSaturation.Solution sol = solution;
        if (s == null || s.solution != sol || s.rate != preparedRate)
        {
            AnalogSaturation.Analysis a = analysis;
            s = new Stream(factory(), preparedRate, a == null ? 2 : a.channels(), sol, a);
            stream = s;
        }
        return s;
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        if (!analog()) return curve.process(buffer, channels);
        validate(buffer, channels);
        int frames = buffer.length / channels;
        if (!active() || solution == AnalogSaturation.Solution.BYPASS || analysis == null)
        {
            position += frames;
            return buffer;
        }
        Stream s = stream();
        if (s.channels != channels) throw new IllegalArgumentException("Saturation channel count changed.");
        AnalogSaturation.Analysis a = analysis;
        double ratio = a.rate() / (double) preparedRate;
        int lat = s.model.latency();
        float[] out = meterOut;
        for (int start = 0; start < frames; start += BLOCK)
        {
            int n = Math.min(BLOCK, frames - start);
            for (int i = 0; i < n; i++)
                for (int c = 0; c < channels; c++)
                    s.buffer[c][i] = buffer[(start + i) * channels + c] * s.norm;
            s.model.process(s.buffer, n);
            for (int i = 0; i < n; i++)
                for (int c = 0; c < channels; c++)
                    buffer[(start + i) * channels + c] = s.out(s.buffer[c][i]);
            if (out != null)
                for (int i = 0; i < n; i++)
                {
                    int hop = hop(position + start + i - lat, ratio, out.length);
                    if (hop < 0) continue;
                    for (int c = 0; c < channels; c++)
                        out[hop] = Math.max(out[hop], Math.abs(buffer[(start + i) * channels + c]));
                }
        }
        position += frames;
        return buffer;
    }

    private static int hop(long frame, double ratio, int hops)
    {
        double source = frame * ratio;
        if (source < 0) return -1;
        long h = (long) (source / AnalogSaturation.HOP);
        return h < hops ? (int) h : -1;
    }

    @Override
    public void beginOfflineMetering(int channels)
    {
        if (!analog())
        {
            curve.beginOfflineMetering(channels);
            return;
        }
        AnalogSaturation.Analysis a = analysis;
        meterOut = new float[a == null ? 0 : a.hopPeak().length];
    }

    @Override
    public void endOfflineMetering()
    {
        if (!analog())
        {
            curve.endOfflineMetering();
            return;
        }
        if (meterOut == null) return;
        AnalogSaturation.Analysis a = analysis;
        AnalogSaturation.Linear l = linear;
        boolean known = a != null && l != null && linearKey.equals(modelKey() + "#" + a.fingerprint());
        grEnv = known ? AnalogSaturation.meter(l, meterOut) : new float[meterOut.length];
        meterOut = null;
    }

    /** The circuit streaming at the prepared rate with a solution. */
    private static final class Stream
    {
        final AnalogModel model;
        final int rate, channels;
        final AnalogSaturation.Solution solution;
        final double norm, back;
        final double[][] buffer;
        final com.dspark.effects.Clipper knee = new com.dspark.effects.Clipper();
        final double clipGain;

        /** A sample of the circuit's output, the gain and the sine knee applied as the whole render did. */
        float out(double y)
        {
            float v = (float) (y * back);
            if (!solution.clips()) return v;
            double scale = solution.clipScale();
            return (float) (scale * knee.shapeSample(v / scale, solution.clipCeiling())) * (float) clipGain;
        }

        Stream(Supplier<AnalogModel> factory, int rate, int channels, AnalogSaturation.Solution solution,
               AnalogSaturation.Analysis a)
        {
            this.model = factory.get();
            this.rate = rate;
            this.channels = channels;
            this.solution = solution;
            model.setDriveDb(solution.driveDb());
            model.prepare(rate, channels, BLOCK);
            this.norm = a == null ? 1.0 : a.alignment(model.alignmentLufs());
            knee.setMode(com.dspark.effects.Clipper.Mode.ANALOG);
            this.clipGain = Math.pow(10.0, solution.clipGainDb() / 20.0);
            this.back = Math.pow(10.0, solution.gainDb() / 20.0) / norm;
            this.buffer = new double[channels][BLOCK];
        }
    }

    private static void validate(float[] samples, int channels)
    {
        if (samples == null || (channels != 1 && channels != 2) || samples.length % channels != 0)
            throw new IllegalArgumentException("Require complete mono or stereo saturation frames.");
    }
}
