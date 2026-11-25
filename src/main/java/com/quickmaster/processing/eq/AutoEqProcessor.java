package com.quickmaster.processing.eq;

import com.dspark.core.DspMath;
import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;

import java.util.function.BooleanSupplier;

/**
 * <b>Auto EQ</b> - automatic tonal balance.
 * <p>
 * The first stage of the EQ. It hears the programme through a model of the
 * ear (loudness in bands of the Bark scale, with the threshold in quiet and
 * the stereo image taken into account) and moves it toward an even tone:
 * the broad tone toward flat loudness across the ear's scale, and the fine
 * detail by lowering what stands over both of its sides (a resonance) and,
 * more gently, raising what lies under both (a hole). The correction follows
 * the programme in time. Two controls:
 * <ul>
 *   <li><b>Amount</b> is how much it does, from nothing to all of it. At 0
 *       the programme is returned to the bit. The share of the way the tone
 *       moves, how far a resonance is lowered or a hole raised, and the
 *       largest correction of a band (12 dB at full) all grow with it, so the
 *       correction at an amount is about that share of the correction at
 *       full.</li>
 *   <li><b>Cut/Boost</b> is what the correction is made of. In the middle it
 *       lowers what is in excess and raises what is lacking alike, and the
 *       loudness the model hears is kept. Toward Cut what it raises fades
 *       out, until it only lowers; toward Boost what it lowers fades out,
 *       until it only raises.</li>
 * </ul>
 * Dips are raised by six tenths of what peaks are lowered by, and the detail
 * is read a quarter of an octave fine.
 * <p>
 * Because the file is known in advance, the whole balanced output is rendered
 * offline (a minimum-phase filter, its latency taken off) and then played
 * back by position, so there is no latency and seeking is exact. Disabled by
 * default, so it costs nothing until switched on.
 */
public final class AutoEqProcessor implements AudioProcessor
{
    public static final double DEFAULT_AMOUNT = 0.25, MIN_AMOUNT = 0.0, MAX_AMOUNT = 1.0;
    /** Cut/Boost: -1 lowers only, 0 lowers and raises alike, 1 raises only. */
    public static final double DEFAULT_CUT_BOOST = 0.0, MIN_CUT_BOOST = -1.0, MAX_CUT_BOOST = 1.0;

    /** The largest correction of a band with Amount at full, in dB. */
    static final double FULL_RANGE_DB = 12.0;
    /** Dips are raised by this share of what peaks are lowered by. */
    static final double DIPS_PER_PEAK = 0.6;
    /** Peaks and dips are read and played this fine, in octaves. */
    static final double DETAIL_OCTAVES = 1.0 / 4.0;

    private volatile boolean enabled = false;
    private volatile double amount = DEFAULT_AMOUNT;
    private volatile double cutBoost = DEFAULT_CUT_BOOST;

    private int sampleRate = 0;
    private long framesProcessed = 0L;

    // Pre-rendered output (interleaved), played back by position, with the
    // settings it was rendered for and the curves in play along it. A
    // provisional one stands for a window of the programme from `offset`,
    // heard until the whole render replaces it.
    private record Render(float[] samples, int offset, int frames, int channels, int rate, long signature,
                          TonalBalanceRender.Settings settings, boolean provisional,
                          short[] curves, int curveCount, int curveSpacing) { }
    // Publish all format, curve and PCM fields together during UI adoption.
    private volatile Render render;

    public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean e) { this.enabled = e; }

    /** How much the Auto EQ does, 0 (nothing) to 1 (all of it). */
    public double getAmount() { return amount; }
    public void setAmount(double a) { this.amount = finiteClamp(a, MIN_AMOUNT, MAX_AMOUNT); }

    /** What the correction is made of: -1 lowers only, 0 lowers and raises alike, 1 raises only. */
    public double getCutBoost() { return cutBoost; }
    public void setCutBoost(double lean) { this.cutBoost = finiteClamp(lean, MIN_CUT_BOOST, MAX_CUT_BOOST); }

    /** The share of what would be lowered that is lowered at a Cut/Boost position: all of it up to the middle, none at Boost. */
    public static double cutShare(double cutBoost)
    {
        return cutBoost <= 0.0 ? 1.0 : 1.0 - Math.min(cutBoost, 1.0);
    }

    /** The share of what would be raised that is raised at a Cut/Boost position: none at Cut, all of it from the middle on. */
    public static double boostShare(double cutBoost)
    {
        return cutBoost >= 0.0 ? 1.0 : 1.0 + Math.max(cutBoost, -1.0);
    }

    /** The largest correction of a band at the current amount, in dB. */
    public double getRangeDb() { return settings().rangeDb(); }

    /**
     * What the two controls ask of the tonal balance. Amount is the share of
     * every correction: of the way the tone moves, of the trimming of peaks,
     * of the filling of dips (six tenths of it) and of the 12 dB a band may
     * move at most. Cut/Boost is the share of all that it raises and the
     * share it lowers. At half the amount and in the middle these are the
     * tonal balance's own defaults.
     */
    TonalBalanceRender.Settings settings()
    {
        final double a = amount, lean = cutBoost;
        return new TonalBalanceRender.Settings(a, a, DIPS_PER_PEAK * a, boostShare(lean), cutShare(lean),
                DETAIL_OCTAVES, FULL_RANGE_DB * a);
    }

    @Override public boolean usesAnalysis() { return true; }
    @Override public int getLatencyFrames() { return 0; }

    @Override
    public void prepare(int sampleRate, long totalSamples)
    {
        if (sampleRate <= 0 || totalSamples < 0) throw new IllegalArgumentException("Invalid Auto EQ format.");
        this.sampleRate = sampleRate;
        this.framesProcessed = 0L;
    }

    @Override
    public void setPlaybackPosition(long frame) { this.framesProcessed = frame; }

    @Override
    public void analyze(float[] samples, int channels)
    {
        analyze(samples, channels, null);
    }

    @Override
    public void analyze(float[] samples, int channels, CancellationToken cancellation)
    {
        if (cancellation != null && cancellation.isCancelled())
            throw new java.util.concurrent.CancellationException("Analysis superseded.");
        if (!enabled) return;
        final Thread caller = Thread.currentThread();
        final BooleanSupplier cancelled = () -> caller.isInterrupted() || (cancellation != null && cancellation.isCancelled());
        if (samples == null || channels < 1 || channels > 2 || sampleRate <= 0 || samples.length % channels != 0)
            throw new IllegalArgumentException("Auto EQ needs complete mono/stereo frames at a positive sample rate.");
        for (int i = 0; i < samples.length; i++) {
            if ((i & 16383) == 0) checkCancelled(cancelled);
            if (!Float.isFinite(samples[i])) throw new IllegalArgumentException("Non-finite Auto EQ input.");
        }
        int frames = samples.length / channels;
        if (frames == 0) { render = null; return; }

        var settings = settings();
        long sig = signature(samples, channels, frames, settings, cancelled);
        Render previous = render;
        if (previous != null && !previous.provisional && sig == previous.signature) return;
        if (settings.asksNothing()) {
            render = new Render(samples.clone(), 0, frames, channels, sampleRate, sig, settings, false, null, 0, 1);
            return;
        }

        var result = TonalBalanceRender.render(samples, channels, sampleRate, settings, renderWorkers(), cancelled);
        checkCancelled(cancelled);
        this.render = new Render(result.samples(), 0, frames, channels, sampleRate, sig, settings, false,
                result.curves(), result.curveCount(), result.curveSpacing());
    }

    /**
     * Whether what this processor would play is not the render of its current
     * settings: it is on, and holds no render, or one of other settings, or a
     * provisional window.
     */
    public boolean awaitsRender()
    {
        Render snapshot = render;
        return enabled && (snapshot == null || snapshot.provisional || !snapshot.settings.equals(settings()));
    }

    /** Drops the render held, so nothing of another programme or of other settings can be played. */
    public void forgetRender() { render = null; }

    /**
     * Renders a provisional window with the current settings, to be heard at
     * once while the whole render is on its way. The window starts cold, so
     * its first second or more is warm-up for what follows; it is an
     * audition, never an export.
     *
     * @param context      this stage's input from frame {@code from} of the programme on, interleaved
     * @param channels     1 or 2
     * @param from         the frame of the programme the window begins at
     * @param cancellation polled while rendering, or null
     */
    public void renderPreview(float[] context, int channels, int from, CancellationToken cancellation)
    {
        if (!enabled) return;
        if (context == null || channels < 1 || channels > 2 || sampleRate <= 0 || context.length % channels != 0 || from < 0)
            throw new IllegalArgumentException("Auto EQ needs complete mono/stereo frames at a positive sample rate.");
        final Thread caller = Thread.currentThread();
        final BooleanSupplier cancelled = () -> caller.isInterrupted() || (cancellation != null && cancellation.isCancelled());
        int frames = context.length / channels;
        if (frames == 0) return;
        var settings = settings();
        float[] window = settings.asksNothing() ? context.clone()
                : TonalBalanceRender.renderWindow(context, channels, sampleRate, settings, cancelled);
        checkCancelled(cancelled);
        render = new Render(window, from, frames, channels, sampleRate, 0L, settings, true, null, 0, 1);
    }

    /** Threads a render may use: every processor but two, left to playback and the interface. */
    static int renderWorkers()
    {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    }

    /** Fills {@code outDb} with the correction (dB) in play at each frequency for the given position. */
    public void fillCorrection(double[] freqs, long samplePos, double[] outDb)
    {
        Render snapshot = render;
        short[] curves = snapshot == null ? null : snapshot.curves;
        int count = snapshot == null ? 0 : snapshot.curveCount;
        if (curves == null || count == 0) { java.util.Arrays.fill(outDb, 0.0); return; }
        final int points = TonalBalanceRender.CURVE_POINTS;
        double slot = DspMath.clamp((double) samplePos / snapshot.curveSpacing, 0.0, count - 1.0);
        int s0 = (int) slot, s1 = Math.min(s0 + 1, count - 1);
        double between = slot - s0;
        int row0 = s0 * points, row1 = s1 * points;
        for (int i = 0; i < freqs.length; i++)
        {
            double pos = TonalBalanceRender.curvePosition(freqs[i]);
            int p0 = (int) pos, p1 = Math.min(p0 + 1, points - 1);
            double part = pos - p0;
            double early = curves[row0 + p0] + part * (curves[row0 + p1] - curves[row0 + p0]);
            double late = curves[row1 + p0] + part * (curves[row1 + p1] - curves[row1 + p0]);
            outDb[i] = 0.01 * (early + between * (late - early));
        }
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        Render snapshot = render;
        if (!enabled || snapshot == null || channels != snapshot.channels) return buffer;
        float[] r = snapshot.samples;
        int renderedFrames = snapshot.frames, renderedRate = snapshot.rate, offset = snapshot.offset;
        int frames = buffer.length / channels;
        long pos = framesProcessed;

        // The render is stored at its analysis (base) rate; position it by time,
        // so the same render plays correctly at any oversampled processing rate.
        // Outside what it holds (a provisional window holds a stretch only)
        // the buffer passes as it is.
        double ratio = (sampleRate > 0 && renderedRate > 0)
                ? (double) renderedRate / sampleRate : 1.0;
        if (ratio == 1.0)
        {
            for (int f = 0; f < frames; f++)
            {
                long s = pos + f - offset;
                if (s < 0 || s >= renderedFrames) continue;
                int bi = f * channels, ri = (int) (s * channels);
                for (int c = 0; c < channels; c++) buffer[bi + c] = r[ri + c];
            }
        }
        else
        {
            for (int f = 0; f < frames; f++)
            {
                double s = (pos + f) * ratio - offset;
                if (s < 0.0 || s >= renderedFrames) continue;
                int bi = f * channels;
                for (int c = 0; c < channels; c++)
                {
                    buffer[bi + c] = sampleRendered(r, renderedFrames, channels, c, s);
                }
            }
        }
        framesProcessed += frames;
        return buffer;
    }

    /** Catmull-Rom read of the base-rate render at a fractional frame position. */
    private float sampleRendered(float[] r, int renderedFrames, int channels, int c, double pos)
    {
        int i1 = (int) pos;
        double t = pos - i1;
        float p0 = renderedAt(r, renderedFrames, channels, c, i1 - 1);
        float p1 = renderedAt(r, renderedFrames, channels, c, i1);
        float p2 = renderedAt(r, renderedFrames, channels, c, i1 + 1);
        float p3 = renderedAt(r, renderedFrames, channels, c, i1 + 2);
        double a0 = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3;
        double a1 = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;
        double a2 = -0.5 * p0 + 0.5 * p2;
        return (float) (((a0 * t + a1) * t + a2) * t + p1);
    }

    private float renderedAt(float[] r, int renderedFrames, int channels, int c, int frame)
    {
        if (frame < 0) frame = 0;
        if (frame >= renderedFrames) frame = renderedFrames - 1;
        return r[frame * channels + c];
    }

    /** Adopts another instance's rendered output (after a background re-analysis). */
    public void adopt(AutoEqProcessor src)
    {
        this.render = src.render;
    }

    private long signature(float[] samples, int channels, int frames, TonalBalanceRender.Settings s,
                           BooleanSupplier cancelled)
    {
        long h = 1125899906842597L;
        h = h * 31 + frames;
        h = h * 31 + channels;
        h = h * 31 + sampleRate;
        h = h * 31 + Double.hashCode(s.balance());
        h = h * 31 + Double.hashCode(s.trimPeaks());
        h = h * 31 + Double.hashCode(s.fillDips());
        h = h * 31 + Double.hashCode(s.raising());
        h = h * 31 + Double.hashCode(s.lowering());
        h = h * 31 + Double.hashCode(s.detail());
        h = h * 31 + Double.hashCode(s.rangeDb());
        // Every sample of the input is part of the signature.
        for (int i = 0; i < samples.length; i++)
        {
            if ((i & 16383) == 0) checkCancelled(cancelled);
            h = h * 1099511628211L + Float.floatToIntBits(samples[i]);
        }
        return h;
    }

    private static double finiteClamp(double value, double min, double max) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite Auto EQ parameter.");
        return DspMath.clamp(value, min, max);
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Auto EQ cancelled.");
    }
}
