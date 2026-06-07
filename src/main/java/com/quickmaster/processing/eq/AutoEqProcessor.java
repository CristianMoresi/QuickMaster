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
    private volatile float[] rendered = null;
    private int renderedFrames = 0;
    private int renderedChannels = 0;
    private long renderSignature = 0L;    // cache key: input + params

    // Per-frame per-band gain (dB), kept for the correction display.
    private volatile float[] gainMap = null;
    private int gainFrames = 0;

    public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean e) { this.enabled = e; }

    public double getAmount() { return amount; }
    public void setAmount(double a) { this.amount = DspMath.clamp(a, MIN_AMOUNT, MAX_AMOUNT); }

    public Target getTarget() { return target; }
    public void setTarget(Target t) { this.target = (t == null) ? Target.PINK : t; }

    public double getAttackSec() { return attackSec; }
    public void setAttackSec(double s) { this.attackSec = DspMath.clamp(s, MIN_ATTACK_SEC, MAX_ATTACK_SEC); }

    public double getReleaseSec() { return releaseSec; }
    public void setReleaseSec(double s) { this.releaseSec = DspMath.clamp(s, MIN_RELEASE_SEC, MAX_RELEASE_SEC); }

    @Override public boolean usesAnalysis() { return true; }
    @Override public int getLatencyFrames() { return 0; }

    @Override
    public void prepare(int sampleRate, int totalSamples)
    {
        this.sampleRate = sampleRate;
        this.framesProcessed = 0L;
    }

    @Override
    public void setPlaybackPosition(long frame) { this.framesProcessed = frame; }

    @Override
    public void analyze(float[] samples, int channels)
    {
        if (!enabled || samples == null || channels < 1 || sampleRate <= 0) return;
        int frames = samples.length / channels;
        if (frames <= 0) return;

        long sig = signature(samples, channels, frames);
        if (sig == renderSignature && rendered != null) return;     // input + params unchanged

        // 1) Mono mixdown.
        float[] mono = new float[frames];
        for (int f = 0; f < frames; f++)
        {
            int base = f * channels;
            double m = 0.0;
            for (int c = 0; c < channels; c++) m += samples[base + c];
            mono[f] = (float) (m / channels);
        }

        // 2) Band ranges (per bin and per band).
        int nb = engine.getNumBins();
        int[] bandLo = new int[NUM_BANDS], bandHi = new int[NUM_BANDS];
        double[] bandHz = new double[NUM_BANDS];
        for (int b = 0; b < NUM_BANDS; b++)
        {
            bandHz[b] = MIN_HZ * Math.pow(2.0, b / 12.0);
            double lo = bandHz[b] * Math.pow(2.0, -1.0 / 24.0);
            double hi = bandHz[b] * Math.pow(2.0, 1.0 / 24.0);
            bandLo[b] = Math.max(1, (int) Math.floor(lo * FFT_SIZE / sampleRate));
            bandHi[b] = Math.min(nb - 1, (int) Math.ceil(hi * FFT_SIZE / sampleRate));
            if (bandHi[b] < bandLo[b]) bandHi[b] = bandLo[b];
        }

        // 3) Per-frame band levels (dB).
        int nFrames = engine.frameCount(frames);
        final float[] level = new float[nFrames * NUM_BANDS];
        engine.analyze(mono, (mag, idx) ->
        {
            int row = idx * NUM_BANDS;
            for (int b = 0; b < NUM_BANDS; b++)
            {
                double p = 0.0;
                for (int k = bandLo[b]; k <= bandHi[b]; k++) p += (double) mag[k] * mag[k];
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
            targetMean += targetShape[b];
        }
        targetMean /= NUM_BANDS;

        float[] gain = new float[nFrames * NUM_BANDS];
        for (int f = 0; f < nFrames; f++)
        {
            int row = f * NUM_BANDS;
            double frameMean = 0.0;
            for (int b = 0; b < NUM_BANDS; b++) frameMean += level[row + b];
            frameMean /= NUM_BANDS;
            for (int b = 0; b < NUM_BANDS; b++)
            {
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

        this.gainMap = gain;
        this.gainFrames = nFrames;
        this.rendered = out;
        this.renderedFrames = frames;
        this.renderedChannels = channels;
        this.renderSignature = sig;
    }

    /** Fills {@code outDb} with the correction (dB) applied at each frequency for the given position. */
    public void fillCorrection(double[] freqs, long samplePos, double[] outDb)
    {
        float[] gm = gainMap;
        int nf = gainFrames;
        if (gm == null || nf == 0) { java.util.Arrays.fill(outDb, 0.0); return; }
        int frame = (int) (samplePos / HOP);
        if (frame < 0) frame = 0; else if (frame >= nf) frame = nf - 1;
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
        float[] r = rendered;
        if (!enabled || r == null || channels != renderedChannels) return buffer;
        int frames = buffer.length / channels;
        long pos = framesProcessed;
        for (int f = 0; f < frames; f++)
        {
            long s = pos + f;
            if (s < 0 || s >= renderedFrames) continue;
            int bi = f * channels, ri = (int) (s * channels);
            for (int c = 0; c < channels; c++) buffer[bi + c] = r[ri + c];
        }
        framesProcessed += frames;
        return buffer;
    }

    /** Adopts another instance's rendered output (after a background re-analysis). */
    public void adopt(AutoEqProcessor src)
    {
        this.renderedFrames = src.renderedFrames;
        this.renderedChannels = src.renderedChannels;
        this.renderSignature = src.renderSignature;
        this.gainFrames = src.gainFrames;
        this.gainMap = src.gainMap;
        this.rendered = src.rendered;        // volatile publish, last
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
        h = h * 31 + Double.hashCode(amount);
        h = h * 31 + target.ordinal();
        h = h * 31 + Double.hashCode(attackSec);
        h = h * 31 + Double.hashCode(releaseSec);
        int stride = Math.max(1, samples.length / 512);
        for (int i = 0; i < samples.length; i += stride)
            h = h * 1099511628211L + Float.floatToIntBits(samples[i]);
        return h;
    }
}
