package com.quickmaster.processing.eq;
import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.analysis.SpectralEngine;

import com.dspark.core.DspMath;

/**
 * <b>Auto EQ</b> - automatic spectral equalisation toward a target tonal curve.
 * <p>
 * The first stage of the EQ. It analyses the whole signal once (an STFT
 * time-frequency map) and, knowing every frequency's level at every moment,
 * shapes each moment's spectrum toward a target curve (pink noise by default,
 * or darker / brighter). The correction per band rides over time with
 * <b>Attack</b> and <b>Release</b>; <b>Amount</b> scales it (0 = off, 1 = full).
 * <p>
 * Because the file is known in advance, the entire equalised output is rendered
 * offline (linear phase, magnitude only) and then played back by position, so
 * there is no latency and seeking is exact. Disabled by default, so it costs
 * nothing until switched on.
 */
public final class AutoEqProcessor implements AudioProcessor
{
    /** Target tonal curves, by spectral slope in dB/octave (pink-centred). */
    public enum Target
    {
        DEEP("Deep", -9.0), BROWN("Brown", -6.0), PINK("Pink", -3.0),
        WHITE("White", 0.0), BLUE("Blue", 3.0);
        public final String label;
        public final double slopeDbPerOct;
        Target(String label, double slope) { this.label = label; this.slopeDbPerOct = slope; }
        @Override public String toString() { return label; }
    }

    public static final double DEFAULT_AMOUNT = 0.2, MIN_AMOUNT = 0.0, MAX_AMOUNT = 1.0;
    // Dynamic but gentle: fast enough to follow the music, slow enough not to
    // chase individual notes.
    public static final double DEFAULT_ATTACK_SEC = 0.1, MIN_ATTACK_SEC = 0.02, MAX_ATTACK_SEC = 0.5;
    public static final double DEFAULT_RELEASE_SEC = 0.4, MIN_RELEASE_SEC = 0.1, MAX_RELEASE_SEC = 2.0;

    private static final int FFT_SIZE = 16384;
    private static final int HOP = FFT_SIZE / 4;
    private static final int NUM_BANDS = 120;             // 1/12 octave, 20 Hz..~19 kHz
    private static final double MIN_HZ = 20.0, REF_HZ = 1000.0;
    private static final double MAX_CUT_DB = 9.0, MAX_BOOST_DB = 6.0;
    private static final double LEVEL_FLOOR_DB = -90.0;
    /** Overall gentleness: amount = 1 applies only this fraction of the raw match. */
    private static final double STRENGTH = 0.30;
    /** Correction is tapered out toward the spectral extremes (psychoacoustic). */
    private static final double LOW_ZERO_HZ = 28.0, LOW_FULL_HZ = 55.0;
    private static final double HIGH_FULL_HZ = 14000.0, HIGH_ZERO_HZ = 18000.0;

    private volatile boolean enabled = false;
    private volatile double amount = DEFAULT_AMOUNT;
    private volatile Target target = Target.PINK;
    private volatile double attackSec = DEFAULT_ATTACK_SEC;
    private volatile double releaseSec = DEFAULT_RELEASE_SEC;

    private final SpectralEngine engine = new SpectralEngine(FFT_SIZE, HOP);
    private int sampleRate = 0;
    private long framesProcessed = 0L;

    // Pre-rendered output (interleaved), played back by position.
    private record Render(float[] samples, int frames, int channels, int rate,
                          long signature, float[] gain, int gainFrames) { }
    // Publish all format, gain-map and PCM fields together during UI adoption.
    private volatile Render render;

    public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean e) { this.enabled = e; }

    public double getAmount() { return amount; }
    public void setAmount(double a) { this.amount = finiteClamp(a, MIN_AMOUNT, MAX_AMOUNT); }

    public Target getTarget() { return target; }
    public void setTarget(Target t) { this.target = (t == null) ? Target.PINK : t; }

    public double getAttackSec() { return attackSec; }
    public void setAttackSec(double s) { this.attackSec = finiteClamp(s, MIN_ATTACK_SEC, MAX_ATTACK_SEC); }

    public double getReleaseSec() { return releaseSec; }
    public void setReleaseSec(double s) { this.releaseSec = finiteClamp(s, MIN_RELEASE_SEC, MAX_RELEASE_SEC); }

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
        if (!enabled) return;
        if (samples == null || channels < 1 || channels > 2 || sampleRate <= 0 || samples.length % channels != 0)
            throw new IllegalArgumentException("Auto EQ needs complete mono/stereo frames at a positive sample rate.");
        for (int i = 0; i < samples.length; i++) {
            if ((i & 16383) == 0) checkCancelled();
            if (!Float.isFinite(samples[i])) throw new IllegalArgumentException("Non-finite Auto EQ input.");
        }
        int frames = samples.length / channels;
        if (frames == 0) { render = null; return; }

        long sig = signature(samples, channels, frames);
        Render previous = render;
        if (previous != null && sig == previous.signature) return;
        if (amount == 0) {
            render = new Render(samples.clone(), frames, channels, sampleRate, sig, null, 0);
            return;
        }

        // 2) Band ranges (per bin and per band).
        int nb = engine.getNumBins();
        int[] bandLo = new int[NUM_BANDS], bandHi = new int[NUM_BANDS];
        double[] bandHz = new double[NUM_BANDS];
        boolean[] active = new boolean[NUM_BANDS];
        int activeBands = 0;
        for (int b = 0; b < NUM_BANDS; b++)
        {
            bandHz[b] = MIN_HZ * Math.pow(2.0, b / 12.0);
            double lo = bandHz[b] * Math.pow(2.0, -1.0 / 24.0);
            double hi = bandHz[b] * Math.pow(2.0, 1.0 / 24.0);
            active[b] = bandHz[b] < sampleRate * .5;
            if (active[b]) activeBands++;
            bandLo[b] = Math.min(nb - 1, Math.max(1, (int) Math.floor(lo * FFT_SIZE / sampleRate)));
            bandHi[b] = Math.min(nb - 1, (int) Math.ceil(hi * FFT_SIZE / sampleRate));
            if (bandHi[b] < bandLo[b]) bandHi[b] = bandLo[b];
        }

        // 3) Per-frame band levels (dB).
        int nFrames = engine.frameCount(frames);
        final float[] level = new float[Math.multiplyExact(nFrames, NUM_BANDS)];
        engine.analyzePower(samples, channels, (power, idx) ->
        {
            int row = idx * NUM_BANDS;
            for (int b = 0; b < NUM_BANDS; b++)
            {
                if (!active[b]) continue;
                double p = 0.0;
                for (int k = bandLo[b]; k <= bandHi[b]; k++) p += power[k];
                p /= (bandHi[b] - bandLo[b] + 1);
                double db = 10.0 * Math.log10(Math.max(p, 1e-12));
                level[row + b] = (float) Math.max(db, LEVEL_FLOOR_DB);
            }
        });

        // 4) Target shape (centred), then per-frame desired correction (centred per frame).
        double[] targetShape = new double[NUM_BANDS];
        double targetMean = 0.0;
        for (int b = 0; b < NUM_BANDS; b++)
        {
            targetShape[b] = target.slopeDbPerOct * (Math.log(bandHz[b] / REF_HZ) / Math.log(2.0));
            if (active[b]) targetMean += targetShape[b];
        }
        targetMean /= Math.max(1, activeBands);

        float[] gain = new float[nFrames * NUM_BANDS];
        for (int f = 0; f < nFrames; f++)
        {
            checkCancelled();
            int row = f * NUM_BANDS;
            double frameMean = 0.0;
            for (int b = 0; b < NUM_BANDS; b++) if (active[b]) frameMean += level[row + b];
            frameMean /= Math.max(1, activeBands);
            for (int b = 0; b < NUM_BANDS; b++)
            {
                if (!active[b]) continue;
                double raw = (targetShape[b] - targetMean) - (level[row + b] - frameMean);
                if (raw > 0.0)
                {
                    // Don't boost near-silent bands (noise guard).
                    double present = DspMath.clamp((level[row + b] - (frameMean - 30.0)) / 20.0, 0.0, 1.0);
                    raw *= present;
                }
                raw *= STRENGTH * edgeWeight(bandHz[b]);   // gentler + roll off the extremes
                gain[row + b] = (float) raw;
            }
        }

        // 5) Ride each band over time (attack engages, release relaxes), then Amount + clamp.
        double frameRate = (double) sampleRate / HOP;
        double atk = Math.exp(-1.0 / (Math.max(attackSec, 1e-3) * frameRate));
        double rel = Math.exp(-1.0 / (Math.max(releaseSec, 1e-3) * frameRate));
        for (int b = 0; b < NUM_BANDS; b++)
        {
            checkCancelled();
            double g = gain[b];                       // first frame
            for (int f = 0; f < nFrames; f++)
            {
                double d = gain[f * NUM_BANDS + b];
                double c = (Math.abs(d) > Math.abs(g)) ? atk : rel;
                g = d + c * (g - d);
                gain[f * NUM_BANDS + b] = (float) DspMath.clamp(amount * g, -MAX_CUT_DB, MAX_BOOST_DB);
            }
        }

        // 6) Map each bin to a fractional band position (fixed for this rate).
        final double[] binBandPos = new double[nb];
        for (int k = 0; k < nb; k++)
        {
            double hz = engine.binToHz(k, sampleRate);
            double pos = (hz <= MIN_HZ) ? 0.0 : 12.0 * (Math.log(hz / MIN_HZ) / Math.log(2.0));
            binBandPos[k] = DspMath.clamp(pos, 0.0, NUM_BANDS - 1);
        }

        // 7) Render each channel with the (linked) per-frame, per-bin gain.
        final float[] gainMap = gain;
        float[] out = new float[samples.length];
        SpectralEngine.BinGain binGain = (freq, idx) ->
        {
            int row = Math.min(idx, nFrames - 1) * NUM_BANDS;
            for (int k = 0; k < nb; k++)
            {
                double bp = binBandPos[k];
                int b0 = (int) bp;
                double fr = bp - b0;
                double gdb = (b0 >= NUM_BANDS - 1) ? gainMap[row + NUM_BANDS - 1]
                        : gainMap[row + b0] * (1 - fr) + gainMap[row + b0 + 1] * fr;
                float gg = (float) DspMath.decibelsToGain(gdb);
                freq[2 * k] *= gg;
                freq[2 * k + 1] *= gg;
            }
        };
        float[] ch = new float[frames];
        for (int c = 0; c < channels; c++)
        {
            for (int f = 0; f < frames; f++) ch[f] = samples[f * channels + c];
            float[] r = engine.render(ch, binGain);
            for (int f = 0; f < frames; f++) out[f * channels + c] = r[f];
        }

        // Reshape tone only: never raise the peak (avoids added clipping/distortion).
        float inPeak = 0.0f, outPeak = 0.0f;
        for (float v : samples) { float a = Math.abs(v); if (a > inPeak) inPeak = a; }
        for (float v : out)     { float a = Math.abs(v); if (a > outPeak) outPeak = a; }
        if (outPeak > inPeak && outPeak > 1e-9f)
        {
            float g = inPeak / outPeak;
            for (int i = 0; i < out.length; i++) out[i] *= g;
        }

        checkCancelled();
        this.render = new Render(out, frames, channels, sampleRate, sig, gain, nFrames);
    }

    /** Fills {@code outDb} with the correction (dB) applied at each frequency for the given position. */
    public void fillCorrection(double[] freqs, long samplePos, double[] outDb)
    {
        Render snapshot = render;
        float[] gm = snapshot == null ? null : snapshot.gain;
        int nf = snapshot == null ? 0 : snapshot.gainFrames;
        if (gm == null || nf == 0) { java.util.Arrays.fill(outDb, 0.0); return; }
        // Analysis includes three leading padded windows. Use their centre
        // times rather than indexing the map as if it had no boundary windows.
        double timeFrame = (double) samplePos / HOP + 1.0;
        int frame = (int) Math.max(0, Math.min(nf - 1, timeFrame));
        int row = frame * NUM_BANDS;
        for (int i = 0; i < freqs.length; i++)
        {
            double hz = freqs[i];
            double pos = (hz <= MIN_HZ) ? 0.0 : 12.0 * (Math.log(hz / MIN_HZ) / Math.log(2.0));
            pos = DspMath.clamp(pos, 0.0, NUM_BANDS - 1);
            int b0 = (int) pos;
            double fr = pos - b0;
            outDb[i] = (b0 >= NUM_BANDS - 1) ? gm[row + NUM_BANDS - 1]
                    : gm[row + b0] * (1 - fr) + gm[row + b0 + 1] * fr;
        }
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        Render snapshot = render;
        if (!enabled || snapshot == null || channels != snapshot.channels) return buffer;
        float[] r = snapshot.samples;
        int renderedFrames = snapshot.frames, renderedRate = snapshot.rate;
        int frames = buffer.length / channels;
        long pos = framesProcessed;

        // The render is stored at its analysis (base) rate; position it by time,
        // so the same render plays correctly at any oversampled processing rate.
        double ratio = (sampleRate > 0 && renderedRate > 0)
                ? (double) renderedRate / sampleRate : 1.0;
        if (ratio == 1.0)
        {
            for (int f = 0; f < frames; f++)
            {
                long s = pos + f;
                if (s < 0 || s >= renderedFrames) continue;
                int bi = f * channels, ri = (int) (s * channels);
                for (int c = 0; c < channels; c++) buffer[bi + c] = r[ri + c];
            }
        }
        else
        {
            for (int f = 0; f < frames; f++)
            {
                double s = (pos + f) * ratio;
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

    /** Frequency weight that tapers the correction to zero toward the spectral extremes. */
    private static double edgeWeight(double hz)
    {
        double w = 1.0;
        if (hz < LOW_FULL_HZ)
            w = Math.min(w, DspMath.clamp((hz - LOW_ZERO_HZ) / (LOW_FULL_HZ - LOW_ZERO_HZ), 0.0, 1.0));
        if (hz > HIGH_FULL_HZ)
            w = Math.min(w, DspMath.clamp((HIGH_ZERO_HZ - hz) / (HIGH_ZERO_HZ - HIGH_FULL_HZ), 0.0, 1.0));
        return w;
    }

    private long signature(float[] samples, int channels, int frames)
    {
        long h = 1125899906842597L;
        h = h * 31 + frames;
        h = h * 31 + channels;
        h = h * 31 + sampleRate;
        h = h * 31 + Double.hashCode(amount);
        h = h * 31 + target.ordinal();
        h = h * 31 + Double.hashCode(attackSec);
        h = h * 31 + Double.hashCode(releaseSec);
        // A sparse 512-sample fingerprint misses edits between its probes and
        // can replay another track's PCM. Account for every input sample.
        for (int i = 0; i < samples.length; i++)
        {
            if ((i & 16383) == 0) checkCancelled();
            h = h * 1099511628211L + Float.floatToIntBits(samples[i]);
        }
        return h;
    }

    private static double finiteClamp(double value, double min, double max) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite Auto EQ parameter.");
        return DspMath.clamp(value, min, max);
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Auto EQ cancelled.");
    }
}
