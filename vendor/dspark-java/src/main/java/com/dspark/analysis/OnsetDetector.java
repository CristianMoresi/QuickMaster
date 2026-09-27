package com.dspark.analysis;

import com.dspark.core.FFTReal;
import com.dspark.core.WindowFunctions;

/**
 * Spectral-flux onset detector.
 * <p>
 * Transforms an audio signal into a one-dimensional <i>onset detection
 * function</i> (ODF) that spikes at percussive/transient events, then
 * peak-picks that function to locate individual onsets (kick, snare,
 * plucks, accents). It is the front end for tempo estimation
 * ({@link TempoEstimator}) and for transient-aware dynamics (a
 * transient designer / "punch" effect).
 * <p>
 * <b>Algorithm.</b> The signal is down-mixed to mono and analysed with a
 * short-time Fourier transform (Hann window, size {@value #DEFAULT_FFT_SIZE},
 * hop {@value #DEFAULT_HOP}). For every frame the <i>spectral flux</i> —
 * the sum over bins of the positive frame-to-frame magnitude increase — is
 * accumulated into the ODF. Spectral flux responds to sudden broadband
 * energy gains (the signature of a transient) while ignoring steady tones,
 * which makes it robust across genres. The ODF is normalised to {@code [0,1]}.
 * <p>
 * <b>Peak picking.</b> A frame is reported as an onset when its ODF value is
 * a local maximum, exceeds a locally adaptive threshold (local mean plus a
 * fixed margin), and is separated from the previous onset by at least a
 * minimum inter-onset interval. Each onset carries a normalised strength and
 * an estimated transient duration (how long the burst takes to decay), both
 * used by downstream transient processing.
 * <p>
 * All work happens in {@link #analyze(float[], int, double)}; the results are
 * then read through the getters. The detector is reusable: a second
 * {@code analyze} call overwrites the previous results. Not thread-safe.
 */
public final class OnsetDetector
{
    /** Default STFT window size in samples (a power of two). */
    public static final int DEFAULT_FFT_SIZE = 1024;
    /** Default STFT hop size in samples (50&nbsp;% overlap). */
    public static final int DEFAULT_HOP = 512;

    /** Onset peak-picking margin above the local mean, on the normalised ODF. */
    private static final float PEAK_DELTA = 0.06f;
    /** Local-mean window radius for the adaptive threshold, in seconds. */
    private static final double THRESH_WINDOW_SEC = 0.10;
    /** Minimum spacing between consecutive onsets, in seconds. */
    private static final double MIN_ONSET_GAP_SEC = 0.05;
    /** Bounds on the estimated transient duration, in seconds. */
    private static final double MIN_TRANSIENT_SEC = 0.020;
    private static final double MAX_TRANSIENT_SEC = 0.120;

    private final int fftSize;
    private final int hop;
    private final FFTReal fft;
    private final double[] window;

    // Results, populated by analyze().
    private float[] odf = new float[0];
    private double frameRate = 0.0;
    private int[] onsetFrames = new int[0];
    private float[] onsetStrengths = new float[0];
    private double[] onsetTimesSec = new double[0];
    private double[] onsetDurationsSec = new double[0];

    /** Creates a detector with the default window/hop sizes. */
    public OnsetDetector()
    {
        this(DEFAULT_FFT_SIZE, DEFAULT_HOP);
    }

    /**
     * Creates a detector with a custom window and hop.
     *
     * @param fftSize  STFT window size (a power of two &ge; 4)
     * @param hop      hop size in samples (1 &le; hop &le; fftSize)
     */
    public OnsetDetector(int fftSize, int hop)
    {
        if (hop < 1 || hop > fftSize)
        {
            throw new IllegalArgumentException("hop must be in [1, fftSize]");
        }
        this.fftSize = fftSize;
        this.hop = hop;
        this.fft = new FFTReal(fftSize);
        this.window = new double[fftSize];
        WindowFunctions.hann(window, fftSize);
    }

    /**
     * Computes the onset detection function and onsets for the given audio.
     *
     * @param interleaved  interleaved float samples (the whole signal)
     * @param channels     channel count (1 = mono, 2 = stereo)
     * @param sampleRate   sample rate in Hz (&gt; 0)
     */
    public void analyze(float[] interleaved, int channels, double sampleRate)
    {
        if (interleaved == null || channels < 1 || sampleRate <= 0.0)
        {
            resetEmpty();
            return;
        }
        int totalFrames = interleaved.length / channels;
        if (totalFrames < fftSize)
        {
            resetEmpty();
            return;
        }

        this.frameRate = sampleRate / hop;

        // Down-mix to mono once.
        float[] mono = new float[totalFrames];
        if (channels == 1)
        {
            System.arraycopy(interleaved, 0, mono, 0, totalFrames);
        }
        else
        {
            for (int f = 0; f < totalFrames; f++)
            {
                float sum = 0.0f;
                int base = f * channels;
                for (int c = 0; c < channels; c++) sum += interleaved[base + c];
                mono[f] = sum / channels;
            }
        }

        int numFrames = 1 + (totalFrames - fftSize) / hop;
        float[] flux = new float[numFrames];

        float[] frameBuf = new float[fftSize];
        float[] freqBuf  = new float[fft.getFrequencyDomainSize()];
        int bins = fft.getNumBins();
        float[] mags = new float[bins];
        float[] prevMags = new float[bins];

        for (int fr = 0; fr < numFrames; fr++)
        {
            int start = fr * hop;
            for (int i = 0; i < fftSize; i++)
            {
                frameBuf[i] = (float) (mono[start + i] * window[i]);
            }
            fft.forward(frameBuf, freqBuf);
            fft.computeMagnitudes(freqBuf, mags);

            // Spectral flux: sum of positive magnitude increases (skip DC).
            float f = 0.0f;
            for (int k = 1; k < bins; k++)
            {
                float diff = mags[k] - prevMags[k];
                if (diff > 0.0f) f += diff;
            }
            flux[fr] = f;

            float[] tmp = prevMags; prevMags = mags; mags = tmp;
        }

        // Normalise the ODF to [0, 1] for scale-independent thresholds.
        float max = 0.0f;
        for (float v : flux) if (v > max) max = v;
        if (max > 0.0f)
        {
            float inv = 1.0f / max;
            for (int i = 0; i < numFrames; i++) flux[i] *= inv;
        }
        this.odf = flux;

        pickOnsets();
    }

    /* ---- Peak picking ---- */

    private void pickOnsets()
    {
        int n = odf.length;
        int w = Math.max(1, (int) Math.round(THRESH_WINDOW_SEC * frameRate));
        int minGap = Math.max(1, (int) Math.round(MIN_ONSET_GAP_SEC * frameRate));

        int[] framesTmp = new int[n];
        float[] strengthTmp = new float[n];
        int count = 0;
        int lastOnset = -minGap;

        for (int i = 1; i < n - 1; i++)
        {
            float v = odf[i];
            // Local maximum.
            if (v < odf[i - 1] || v < odf[i + 1]) continue;

            // Adaptive threshold = local mean + margin.
            int a = Math.max(0, i - w);
            int b = Math.min(n - 1, i + w);
            float sum = 0.0f;
            for (int j = a; j <= b; j++) sum += odf[j];
            float localMean = sum / (b - a + 1);

            if (v >= localMean + PEAK_DELTA && (i - lastOnset) >= minGap)
            {
                framesTmp[count] = i;
                strengthTmp[count] = v;
                count++;
                lastOnset = i;
            }
        }

        onsetFrames = new int[count];
        onsetStrengths = new float[count];
        onsetTimesSec = new double[count];
        onsetDurationsSec = new double[count];
        for (int k = 0; k < count; k++)
        {
            int fr = framesTmp[k];
            onsetFrames[k] = fr;
            onsetStrengths[k] = strengthTmp[k];
            onsetTimesSec[k] = fr / frameRate;
            onsetDurationsSec[k] = estimateDuration(fr);
        }
    }

    /**
     * Estimates a transient's duration as the time for the ODF to fall to a
     * fraction of its onset peak, clamped to a musically sane range.
     */
    private double estimateDuration(int onsetFrame)
    {
        float peak = odf[onsetFrame];
        float floor = 0.35f * peak;
        int maxFrames = (int) Math.ceil(MAX_TRANSIENT_SEC * frameRate);
        int end = onsetFrame;
        for (int i = onsetFrame + 1; i < odf.length && (i - onsetFrame) <= maxFrames; i++)
        {
            end = i;
            if (odf[i] <= floor) break;
        }
        double dur = (end - onsetFrame) / frameRate;
        if (dur < MIN_TRANSIENT_SEC) dur = MIN_TRANSIENT_SEC;
        if (dur > MAX_TRANSIENT_SEC) dur = MAX_TRANSIENT_SEC;
        return dur;
    }

    private void resetEmpty()
    {
        odf = new float[0];
        frameRate = 0.0;
        onsetFrames = new int[0];
        onsetStrengths = new float[0];
        onsetTimesSec = new double[0];
        onsetDurationsSec = new double[0];
    }

    /* ---- Results ---- */

    /** The normalised onset detection function, one value per STFT frame. */
    public float[] getOdf() { return odf; }

    /** STFT frame rate in frames per second ({@code sampleRate / hop}). */
    public double getFrameRate() { return frameRate; }

    /** Detected onset positions, as STFT frame indices. */
    public int[] getOnsetFrames() { return onsetFrames; }

    /** Detected onset strengths in {@code [0,1]} (the ODF peak value). */
    public float[] getOnsetStrengths() { return onsetStrengths; }

    /** Detected onset positions in seconds. */
    public double[] getOnsetTimesSec() { return onsetTimesSec; }

    /** Estimated transient durations in seconds, parallel to the onsets. */
    public double[] getOnsetDurationsSec() { return onsetDurationsSec; }

    /** Number of detected onsets. */
    public int getOnsetCount() { return onsetFrames.length; }
}
