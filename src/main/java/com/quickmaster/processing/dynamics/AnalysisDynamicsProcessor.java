package com.quickmaster.processing.dynamics;
import com.quickmaster.processing.AudioProcessor;

/**
 * Base class for the analysis-driven dynamics processors (Peak Comp, Beat Comp,
 * Leveler, Punch).
 * <p>
 * Because the whole signal is available before playback, these processors react
 * to peaks and transients with no attack lag: each one precomputes a per-frame
 * <b>gain envelope</b> over the entire signal in {@link #analyze} and then
 * applies it.
 * <p>
 * <b>Features vs. gain.</b> {@link #analyze} computes the input-dependent
 * <i>features</i> (level / transient envelopes); {@link #mapFeaturesToSchedule()}
 * then turns those, together with this processor's parameters, into one
 * immutable {@link PublishedGain}. The audio thread captures that single
 * volatile publication once per block.
 * <p>
 * <b>Rate independence.</b> {@link #process} applies the envelope by time
 * position ({@code t = framesProcessed / sampleRate}, index {@code t · envRate}),
 * so the same base-rate envelope applies correctly during live playback, offline
 * export and at any oversampling factor. {@link #prepare} resets only the
 * playback position; the analysis is kept. Latency is zero.
 * <p>
 * Subclasses implement {@link #computeFeatures} and {@link #mapFeaturesToGain()}.
 * Processors are disabled by default and stereo-linked (one gain for both
 * channels).
 */
public abstract class AnalysisDynamicsProcessor implements AudioProcessor
{
    /** Whether this stage is active (default off). */
    protected volatile boolean enabled = false;

    /**
     * Legacy per-frame linear gain envelope (base rate). It remains protected
     * during the staged migration so existing subclasses keep their mapper API.
     * The default mapper uses it only as a transfer slot and clears the slot
     * before publishing, so audio rendering never shares this mutable alias.
     */
    @Deprecated
    protected volatile float[] gainEnv = null;

    /** Sample rate at which {@link #gainEnv} is indexed (the base rate). */
    protected double envRate = 0.0;

    /** Number of frames the analysed audio had (length of {@link #gainEnv}). */
    protected int envFrames = 0;

    /** The only gain state visible to the audio renderer. */
    private volatile PublishedGain published = PublishedGain.unit(0L, AnalysisStatus.UNIT);

    /** Monotonic local generation assigned outside the audio thread. */
    private long publicationSequence = 0L;

    /** Current sample rate from the last {@link #prepare} (may be oversampled). */
    private int preparedRateHz = 0;

    /** Source-clock position of the next prepared-rate frame. */
    private long preparedFrameCursor = 0L;

    /** Constructed once; never published and never allocated in process(). */
    private final DenseGainCursor denseCursor = new DenseGainCursor();

    /** Separate source-clock cursor; it never enters the legacy linear renderer. */
    private final SparseGainCursor sparseCursor = new SparseGainCursor();

    /** Worst (most negative) gain reduction applied in the last block, in dB. */
    private volatile double currentGrDb = 0.0;

    @Override
    public boolean usesAnalysis() { return true; }

    @Override
    public int getLatencyFrames() { return 0; }

    @Override
    public void prepare(int sampleRate, long totalSamples)
    {
        this.preparedRateHz = (sampleRate > 0 && totalSamples >= 0L) ? sampleRate : 0;
        this.preparedFrameCursor = 0L;
        this.denseCursor.invalidate(0L);
        this.sparseCursor.invalidate(0L);
        this.currentGrDb = 0.0;
        // The immutable publication is intentionally kept across prepare().
    }

    @Override
    public void setPlaybackPosition(long frame)
    {
        this.preparedFrameCursor = frame;
        this.denseCursor.invalidate(frame);
        this.sparseCursor.invalidate(frame);
    }

    @Override
    public synchronized void analyze(float[] samples, int channels)
    {
        long generation = ++publicationSequence;
        if (!validAnalysisInput(samples, channels, preparedRateHz))
        {
            clearLegacyState();
            published = PublishedGain.unit(generation, AnalysisStatus.INVALID_INPUT);
            return;
        }
        int frames = samples.length / channels;
        this.envRate = preparedRateHz;
        this.envFrames = frames;
        this.gainEnv = null;
        try
        {
            computeFeatures(samples, channels, preparedRateHz, frames);
            GainSchedule schedule = mapFeaturesToSchedule();
            PublishedGain next = new PublishedGain(
                    schedule, preparedRateHz, channels, generation, AnalysisStatus.LEGACY_READY);
            published = next;
        }
        catch (IllegalArgumentException | IllegalStateException ex)
        {
            clearLegacyState();
            published = PublishedGain.unit(generation, AnalysisStatus.INVALID_INPUT);
        }
    }

    @Override
    public float[] process(float[] buffer, int channels)
    {
        PublishedGain blockPublication = published; // exactly one volatile read per block
        if (buffer == null)
        {
            currentGrDb = 0.0;
            return null;
        }
        if (channels < 1 || buffer.length % channels != 0)
        {
            currentGrDb = 0.0;
            return buffer;
        }

        int frames = buffer.length / channels;
        long startPreparedFrame = preparedFrameCursor;
        long nextPreparedFrame = startPreparedFrame + frames;
        double meterDb = 0.0;

        if (enabled && preparedRateHz > 0 && blockPublication.isRenderableFor(channels)
                && finiteBuffer(buffer))
        {
            GainDomain domain = blockPublication.schedule().domain();
            java.util.Objects.requireNonNull(domain);
            if (domain == GainDomain.LEGACY_LINEAR)
            {
                meterDb = renderDenseLegacy(blockPublication, buffer, channels, frames, startPreparedFrame);
            }
            else if (domain == GainDomain.SPARSE_DB)
            {
                meterDb = renderSparseDb(blockPublication, buffer, channels, frames, startPreparedFrame);
            }
        }

        currentGrDb = meterDb;
        preparedFrameCursor = nextPreparedFrame;
        denseCursor.advanceTo(nextPreparedFrame);
        sparseCursor.advanceTo(nextPreparedFrame);
        return buffer;
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Signed most-extreme gain in dB in the most recent block. */
    public double getGainReductionDb() { return currentGrDb; }

    /** True once {@link #analyze} has produced a gain envelope. */
    public boolean isAnalyzed()
    {
        PublishedGain snapshot = published;
        return snapshot.status() == AnalysisStatus.LEGACY_READY || snapshot.status() == AnalysisStatus.STRUCTURAL_READY;
    }

    /* --- For subclasses --- */

    /**
     * Computes and caches the expensive, input-dependent features (level or
     * transient envelopes). Called once per analysis pass with the
     * <i>post-upstream</i> signal.
     *
     * @param samples     interleaved post-upstream samples (whole audio)
     * @param channels    channel count
     * @param sampleRate  base sample rate of {@code samples}
     * @param frames      number of frames ({@code samples.length / channels})
     */
    protected abstract void computeFeatures(float[] samples, int channels, int sampleRate, int frames);

    /**
     * Builds {@link #gainEnv} (length {@link #envFrames}) from the cached
     * features and this processor's current parameters. Must allocate a fresh
     * array and assign it to {@link #gainEnv} atomically. Cheap (no FFT) so it
     * can run live on a parameter change.
     */
    protected abstract void mapFeaturesToGain();

    /** A live safety bound for a previously published dense envelope. */
    protected float minimumLegacyGain() { return 0.0f; }

    /** Source rate for a synchronous structural analysis; prepare still preserves published gain. */
    protected final int analysisRateHz() { return preparedRateHz; }

    /** Publish one fully proved structural result, without allocating a frame-sized legacy envelope. */
    protected final synchronized void publishStructural(SparseGainSchedule schedule, int channels)
    {
        PublishedGain next = new PublishedGain(schedule, schedule.sourceRateHz(), channels,
                ++publicationSequence, AnalysisStatus.STRUCTURAL_READY);
        clearLegacyState();
        published = next;
    }

    /** Invalidate the entire audio publication together; a diagnostic never reuses stale gain. */
    protected final synchronized void publishUnit(AnalysisStatus status)
    {
        PublishedGain next = PublishedGain.unit(++publicationSequence, status);
        clearLegacyState();
        published = next;
    }

    /**
     * Migration template. M-003 keeps every dynamics processor, including the
     * Leveler, on the exact legacy mapper and transfers that fresh array to one
     * dense schedule without a copy or domain conversion.
     */
    protected GainSchedule mapFeaturesToSchedule()
    {
        mapFeaturesToGain();
        float[] mapped = gainEnv;
        if (mapped == null || mapped.length != envFrames)
        {
            throw new IllegalStateException("Legacy mapper produced an invalid envelope length.");
        }
        DenseGainSchedule schedule = new DenseGainSchedule(envRate, mapped);
        gainEnv = null; // ownership transferred; cut the only legacy mutable alias
        return schedule;
    }

    /** Rebuilds the gain envelope from the cached features, if any. */
    protected synchronized void remap()
    {
        if (envFrames > 0)
        {
            long generation = ++publicationSequence;
            try
            {
                GainSchedule schedule = mapFeaturesToSchedule();
                PublishedGain previous = published;
                int channels = previous.status() == AnalysisStatus.LEGACY_READY
                        ? previous.sourceChannels() : 0;
                if (channels == 0)
                {
                    clearLegacyState();
                    published = PublishedGain.unit(generation, AnalysisStatus.INVALID_INPUT);
                    return;
                }
                published = new PublishedGain(
                        schedule, (int) envRate, channels, generation, AnalysisStatus.LEGACY_READY);
            }
            catch (IllegalArgumentException | IllegalStateException ex)
            {
                clearLegacyState();
                published = PublishedGain.unit(generation, AnalysisStatus.INVALID_INPUT);
            }
        }
    }

    /**
     * Replaces this processor's immutable gain publication with another's.
     * The legacy transfer slot remains empty so adoption cannot recreate an
     * alias to the dense schedule's owned array.
     *
     * @param src  the processor whose envelope to copy
     */
    public synchronized void adoptEnvelope(AnalysisDynamicsProcessor src)
    {
        if (src == null)
        {
            throw new IllegalArgumentException("Source processor must not be null.");
        }
        PublishedGain sourcePublication = src.published;
        adoptPublication(sourcePublication);
    }

    /** Adopt a captured immutable source snapshot; callers need not hold two processor locks. */
    final synchronized void adoptPublication(PublishedGain sourcePublication)
    {
        this.envRate = sourcePublication.sourceRateHz();
        this.envFrames = sourcePublication.sourceChannels() == 0 ? 0 : Math.toIntExact(sourcePublication.schedule().sourceFrames());
        this.gainEnv = null;
        this.publicationSequence = Math.max(publicationSequence,
                sourcePublication.analysisGeneration()) + 1;
        // Two analysis forks can carry the same local generation. Give adoption a
        // fresh destination generation so a Sparse cursor cannot reuse an old piece index.
        this.published = new PublishedGain(sourcePublication.schedule(), sourcePublication.sourceRateHz(),
                sourcePublication.sourceChannels(), publicationSequence, sourcePublication.status());
    }

    /** Publishes unit for load/close without adding a reset method to AudioProcessor. */
    synchronized void clearAnalysis()
    {
        long generation = ++publicationSequence;
        clearLegacyState();
        published = PublishedGain.unit(generation, AnalysisStatus.CLEARED);
    }

    /** Package-private observation point for lifecycle tests; audio does not use it. */
    PublishedGain publishedGain()
    {
        return published;
    }

    /**
     * Peak magnitude over the first ~50&nbsp;ms - a stable level to prime an
     * envelope follower with, so a steady passage at the very start is not
     * mistaken for one enormous transient.
     */
    protected static double primeLevel(float[] samples, int channels, int frames, int sampleRate)
    {
        int pre = Math.min(frames, Math.max(1, (int) (0.05 * sampleRate)));
        float init = 0.0f;
        for (int f = 0; f < pre; f++)
        {
            int base = f * channels;
            for (int c = 0; c < channels; c++)
            {
                float a = Math.abs(samples[base + c]);
                if (a > init) init = a;
            }
        }
        return init;
    }

    private double renderDenseLegacy(PublishedGain blockPublication,
                                     float[] buffer,
                                     int channels,
                                     int frames,
                                     long startPreparedFrame)
    {
        DenseGainSchedule dense = (DenseGainSchedule) blockPublication.schedule();
        denseCursor.align(blockPublication.analysisGeneration(), startPreparedFrame);
        double rateRatio = dense.envRateHz() / preparedRateHz;
        float minimumGain = minimumLegacyGain();
        double extreme = 0.0;
        for (int f = 0; f < frames; f++)
        {
            double pos = (startPreparedFrame + f) * rateRatio;
            float g = dense.sampleLinearLegacy(pos);
            if (g < minimumGain) g = minimumGain;
            int base = f * channels;
            for (int c = 0; c < channels; c++)
            {
                buffer[base + c] *= g;
            }
            double dB = 20.0 * Math.log10(Math.max(g, 1e-6f));
            if (Math.abs(dB) > Math.abs(extreme)) extreme = dB;
        }
        return extreme;
    }

    private double renderSparseDb(PublishedGain blockPublication,
                                   float[] buffer,
                                   int channels,
                                   int frames,
                                   long startPreparedFrame)
    {
        SparseGainSchedule sparse = (SparseGainSchedule) blockPublication.schedule();
        if (startPreparedFrame > Long.MAX_VALUE - frames) return 0;
        double sourceStart = ((double) startPreparedFrame * sparse.sourceRateHz()) / preparedRateHz;
        sparseCursor.align(blockPublication.analysisGeneration(), startPreparedFrame, sparse, sourceStart);
        double extreme = 0;
        for (int f = 0; f < frames; f++)
        {
            double sourcePosition = ((double) (startPreparedFrame + f) * sparse.sourceRateHz()) / preparedRateHz;
            double gainDb = sparseCursor.gainDbAt(sparse, sourcePosition);
            if (gainDb != 0)
            {
                double linear = StrictMath.exp(gainDb * StrictMath.log(10.0) / 20.0);
                int base = f * channels;
                for (int c = 0; c < channels; c++)
                    buffer[base + c] = (float) (buffer[base + c] * linear);
            }
            if (Math.abs(gainDb) > Math.abs(extreme)) extreme = gainDb;
        }
        return extreme;
    }

    private static boolean validAnalysisInput(float[] samples, int channels, int sampleRate)
    {
        if (samples == null || samples.length == 0 || sampleRate <= 0
                || (channels != 1 && channels != 2) || samples.length % channels != 0)
        {
            return false;
        }
        for (float sample : samples)
        {
            if (!Float.isFinite(sample)) return false;
        }
        return true;
    }

    private static boolean finiteBuffer(float[] buffer)
    {
        for (float sample : buffer)
        {
            if (!Float.isFinite(sample)) return false;
        }
        return true;
    }

    private void clearLegacyState()
    {
        gainEnv = null;
        envRate = 0.0;
        envFrames = 0;
    }
}
