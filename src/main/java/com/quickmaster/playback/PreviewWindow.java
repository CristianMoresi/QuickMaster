package com.quickmaster.playback;

/** Immutable after publication. PCM ownership is transferred, not shared with DSP scratch. */
public record PreviewWindow(float[] source, long generation, long startFrame, int channels, float[] pcm,
                            double eqGainDb, double outputGainDb) {
    public PreviewWindow(float[] source, long generation, long startFrame, int channels, float[] pcm) {
        this(source, generation, startFrame, channels, pcm, 0, 0);
    }
    public PreviewWindow {
        if (source == null || pcm == null || channels < 1 || startFrame < 0 || pcm.length % channels != 0
                || startFrame + pcm.length / channels > source.length / channels
                || !Double.isFinite(eqGainDb) || !Double.isFinite(outputGainDb))
            throw new IllegalArgumentException("Invalid audition window");
    }
    public long endFrame() { return startFrame + pcm.length / channels; }
    public boolean covers(long frame, int frames) { return frame >= startFrame && frame + frames <= endFrame(); }
    public float sample(long frame, int channel) { return pcm[Math.toIntExact((frame - startFrame) * channels + channel)]; }
}
