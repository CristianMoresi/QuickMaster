package com.quickmaster.processing.eq;
import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.OfflineMetering;

import com.dspark.effects.MasterEqualizer;

/**
 * Equalizer stage of the QuickMaster pipeline - the unified <b>first
 * block</b> of the mastering chain.
 * <p>
 * Adapts the DSP engine's {@link MasterEqualizer} to the
 * {@link AudioProcessor} contract. That one engine subsumes what used to
 * be separate modules: per-band channel routing
 * ({@link MasterEqualizer.Channel#STEREO STEREO}/{@code LEFT}/{@code RIGHT}/
 * {@code MID}/{@code SIDE}) plus a flat {@link MasterEqualizer.BandType#GAIN
 * GAIN} band turn the EQ into L/R volume, Mid/Side gain and stereo width as
 * well as full parametric / tilt EQ; any band can be dynamic; and each
 * static band is linear-phase by default or minimum-phase per band.
 * <p>
 * <b>Lazy / eager preparation.</b> The engine needs the channel count and a
 * maximum block size that {@link AudioProcessor#prepare(int, long)} does not
 * supply. The wrapper eagerly prepares the engine for stereo at
 * {@code prepare} time (so {@link #getLatencyFrames()} is correct before the
 * first block) and re-prepares it on the first {@link #process} call if the
 * real channel count differs. Blocks must not exceed
 * {@link #MAX_BLOCK_FRAMES} frames; the player and the offline renderer both
 * honour that bound.
 * <p>
 * <b>Latency.</b> Half the engine's linear-phase kernel when at least one
 * linear-phase static band is active, otherwise 0; the offline renderer
 * compensates for it. The kernel spans a constant time window, so the EQ
 * curve is realized identically at any processing rate.
 * <p>
 * <b>Default state.</b> Enabled but with no bands configured - a transparent,
 * zero-latency passthrough until bands are added.
 */
public final class EqualizerProcessor implements AudioProcessor, OfflineMetering
{
    /**
     * Maximum block size, in frames, accepted by {@link #process}. Matches
     * {@code AudioPlayer.BUFFER_FRAMES} and
     * {@code ProcessingPipeline.OFFLINE_BLOCK_FRAMES}; in linear-phase mode it
     * is also the latency introduced.
     */
    public static final int MAX_BLOCK_FRAMES = 1024;

    private final MasterEqualizer engine;

    private int sampleRate = 0;
    private int preparedChannels = 0;
    private boolean enginePrepared = false;
    private boolean enabled = true;
    private long totalSamples, frameCursor;
    private record MeterHistory(float[] values, int bands, int step, int rate) { }
    private volatile MeterHistory meters;
    private MeterHistory recording;
    // FX-only response engine; it never processes audio or shares DSP state.
    private MasterEqualizer meterResponse;

    @Override public void setPlaybackPosition(long frame) { frameCursor = frame; }

    @Override public void beginOfflineMetering(int channels) {
        recording = null;
        if (!enabled || !hasActiveDynamicBand()) { meters = null; return; }
        int step = Math.max(1, sampleRate / 50);
        int rows = Math.toIntExact((totalSamples / channels + step - 1) / step);
        int bands = getNumBands();
        float[] values = new float[Math.multiplyExact(Math.multiplyExact(rows, bands), 2)];
        for (int i = 1; i < values.length; i += 2) values[i] = Float.NaN;
        recording = new MeterHistory(values, bands, step, sampleRate);
    }

    @Override public void endOfflineMetering() {
        if (recording != null) meters = recording;
        recording = null;
    }

    /** Share only the completed immutable timeline when a tonal-prefix cache is reused. */
    public void adoptMeters(EqualizerProcessor source) { meters = source.meters; }

    public double getBandMeterAt(int band, long baseFrame, int baseRate, boolean detector) {
        MeterHistory m = meters;
        if (m == null || band < 0 || band >= m.bands || baseFrame < 0 || baseRate <= 0)
            return detector ? Double.NaN : 0;
        long row = (long) (baseFrame * (double)m.rate / baseRate / m.step);
        long index = (row * m.bands + band) * 2 + (detector ? 1 : 0);
        return index >= m.values.length ? (detector ? Double.NaN : 0) : m.values[(int)index];
    }

    /** Dynamic response of the approved render at the source-clock playhead. */
    public void getMagnitudeResponseAt(MasterEqualizer.Channel domain, double[] frequencies,
                                       double[] magnitudes, long baseFrame, int baseRate) {
        if (meterResponse == null) {
            meterResponse = new MasterEqualizer(getMaxBands());
            meterResponse.setNumBands(getNumBands());
            // Minimum-phase is sufficient for magnitude drawing; no FIR kernels on FX.
            for (int i = 0; i < getNumBands(); i++) {
                MasterEqualizer.Band band = getBand(i);
                band.dynamic = false; band.phase = MasterEqualizer.BandPhase.MINIMUM;
                meterResponse.setBand(i, band);
            }
            meterResponse.prepare(sampleRate, MAX_BLOCK_FRAMES, 2);
        }
        for (int i = 0; i < getNumBands(); i++) {
            MasterEqualizer.Band band = getBand(i);
            if (band.dynamic) band.gainDb += getBandMeterAt(i, baseFrame, baseRate, false);
            band.dynamic = false; band.phase = MasterEqualizer.BandPhase.MINIMUM;
            meterResponse.setBand(i, band);
        }
        meterResponse.getMagnitudeResponse(domain, frequencies, magnitudes);
    }

    public EqualizerProcessor() { this(MasterEqualizer.DEFAULT_MAX_BANDS); }

    public EqualizerProcessor(int maxBands)
    {
        this.engine = new MasterEqualizer(maxBands);
    }

    /* --- Band API (delegated to the engine) --- */

    public void setBand(int index, MasterEqualizer.Band band) { engine.setBand(index, band); }

    public MasterEqualizer.Band getBand(int index) { return engine.getBand(index); }

    public void setNumBands(int n) { engine.setNumBands(n); }

    public int getNumBands() { return engine.getNumBands(); }

    public int getMaxBands() { return engine.getMaxBands(); }

    /** Combined static linear magnitude response of the bands routed to {@code domain}. */
    public void getMagnitudeResponse(MasterEqualizer.Channel domain,
                                     double[] frequencies, double[] magnitudes)
    {
        engine.getMagnitudeResponse(domain, frequencies, magnitudes);
    }

    /**
     * Combined linear magnitude response; when {@code dynamicLive}, each dynamic
     * band's live gain modulation is included (for drawing the moving curve).
     */
    public void getMagnitudeResponse(MasterEqualizer.Channel domain,
                                     double[] frequencies, double[] magnitudes, boolean dynamicLive)
    {
        engine.getMagnitudeResponse(domain, frequencies, magnitudes, dynamicLive);
    }

    /** Static magnitude response of a single band (ignores its enabled flag), for display. */
    public void getBandMagnitudeResponse(int index, double[] frequencies, double[] magnitudes)
    {
        engine.getBandMagnitudeResponse(index, frequencies, magnitudes);
    }

    /** True if any enabled band is dynamic. */
    public boolean hasActiveDynamicBand() { return engine.hasActiveDynamicBand(); }

    /** Current dynamic gain (dB) a band is applying, for metering. */
    public double getBandGainReductionDb(int index) { return engine.getBandGainReductionDb(index); }

    /** Last detector level (dBFS) a band saw, for the threshold reference. */
    public double getBandDetectorDb(int index) { return engine.getBandDetectorDb(index); }

    /* --- AudioProcessor --- */

    @Override
    public void prepare(int sampleRate, long totalSamples)
    {
        this.sampleRate = sampleRate;
        this.totalSamples = totalSamples;
        this.frameCursor = 0;
        // Eagerly prepare for stereo so getLatencyFrames() is correct before
        // the first block; re-prepared on the first process() if mono.
        engine.prepare(sampleRate, MAX_BLOCK_FRAMES, 2);
        preparedChannels = 2;
        enginePrepared = true;
    }

    @Override
    public int getLatencyFrames()
    {
        return (enabled && enginePrepared) ? engine.getLatencyFrames() : 0;
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        if (!enabled || sampleRate <= 0)
        {
            return passthrough(buffer);
        }
        if (!enginePrepared || channels != preparedChannels)
        {
            engine.prepare(sampleRate, MAX_BLOCK_FRAMES, channels);
            preparedChannels = channels;
            enginePrepared = true;
        }
        engine.process(buffer, channels);
        MeterHistory m = recording;
        int frames = buffer.length / channels;
        if (m != null && m.bands > 0) {
            long first = Math.max(0, frameCursor - getLatencyFrames());
            long end = Math.max(0, frameCursor + frames - getLatencyFrames());
            int rowCount = m.values.length / (m.bands * 2);
            int firstRow = (int)Math.min(rowCount, first / m.step);
            int lastRow = (int)Math.min(rowCount, (end + m.step - 1) / m.step);
            for (int row = firstRow; row < lastRow; row++) for (int band = 0; band < m.bands; band++) {
                int index = (row * m.bands + band) * 2;
                m.values[index] = (float)engine.getBandGainReductionDb(band);
                m.values[index + 1] = (float)engine.getBandDetectorDb(band);
            }
        }
        frameCursor += frames;
        return buffer;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
