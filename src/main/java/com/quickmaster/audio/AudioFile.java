package com.quickmaster.audio;

/**
 * Abstract base class representing an audio file loaded into memory.
 * <p>
 * Defines the common state shared by all supported audio formats:
 * file path, sample rate, channel count, and the decoded samples
 * stored in a normalized float representation (range -1.0 to +1.0).
 * <p>
 * The file path is immutable after construction. Sample rate, channel
 * count, and the sample array are populated when {@link #load()} is
 * invoked: subclasses parse the file header and update these fields
 * via the protected setters provided by this class.
 * <p>
 * <b>Original samples and non-destructive editing.</b> To support a
 * non-destructive editing workflow, the user can trim the start and
 * end of the audio,
 * apply the processing pipeline, change parameters, and revert
 * trims or processing without losing fidelity. To support this,
 * the class keeps a separate {@code originalSamples} array that
 * holds the samples exactly as decoded from disk and is never
 * modified after {@link #load()}. The mutable {@code samples}
 * array carries the current edit state and is what the pipeline
 * and the export operate on. {@link #reset()} restores
 * {@code samples} from {@code originalSamples}, and
 * {@link #trim(double, double)} produces a new {@code samples}
 * array derived from the original (trim is non-cumulative: each
 * call starts from the original, so changing trim values is
 * idempotent rather than additive).
 * <p>
 * Concrete subclasses (such as {@link WavFile} and {@link Mp3File})
 * provide format-specific implementations of {@link #load()} and
 * {@link #save(String)}. This polymorphic design allows the rest
 * of the application to manipulate any audio file uniformly,
 * without needing to know its underlying format.
 * <p>
 * Both {@code load} and {@code save} declare {@link AudioFileException}
 * to enforce explicit, recoverable error handling at the call site:
 * unreadable files, malformed headers, unsupported encodings, and
 * write failures must all be communicated to the caller without
 * crashing the application.
 */
public abstract class AudioFile
{
    private final String filePath;
    private int sampleRate;
    private int channels;

    /**
     * Current edit state - the samples that the pipeline operates
     * on and that get written to disk on {@link #save(String)}.
     * Mutable.
     */
    private float[] samples;

    /**
     * Pristine snapshot of the samples as decoded from disk.
     * Set once by {@link #load()} (or by the full constructor)
     * and never modified afterwards. Used by {@link #reset()} and
     * {@link #trim(double, double)} as the source of truth for
     * non-destructive operations.
     */
    private float[] originalSamples;

    /**
     * Full constructor used when all metadata is already known
     * (for example, when creating an AudioFile programmatically
     * rather than loading from disk).
     * <p>
     * The provided sample array is used both as the current edit
     * state and as the pristine original (a defensive copy is
     * stored as the original to ensure later in-place modifications
     * to {@code samples} do not corrupt it).
     *
     * @param filePath    path to the source file on disk
     * @param sampleRate  sample rate in Hz (e.g. 44100, 48000, 96000, 192000)
     * @param channels    number of audio channels (1 = mono, 2 = stereo)
     * @param samples     decoded audio samples in interleaved float format
     */
    protected AudioFile(String filePath, int sampleRate, int channels, float[] samples)
    {
        this.filePath = filePath;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.samples = samples;
        this.originalSamples = (samples != null) ? samples.clone() : null;
    }

    /**
     * Lightweight constructor used when only the file path is known.
     * The remaining fields (sample rate, channel count, samples) are
     * populated later by the subclass implementation of {@link #load()}.
     *
     * @param filePath  path to the source file on disk
     */
    protected AudioFile(String filePath)
    {
        this.filePath = filePath;
        this.sampleRate = 0;
        this.channels = 0;
        this.samples = null;
        this.originalSamples = null;
    }

    /**
     * Loads and decodes the file at the current {@code filePath} into
     * the internal float sample array, and updates sample rate and
     * channel count from the file header. The implementation is
     * format-specific.
     * <p>
     * Implementations must call {@link #setSamplesAsLoaded(float[])}
     * (rather than {@link #setSamples(float[])}) when assigning the
     * decoded data, so that both the editable buffer and the pristine
     * original snapshot are populated.
     *
     * @throws AudioFileException if the file cannot be read, its
     *         header is malformed, or its format is not supported
     */
    public abstract void load() throws AudioFileException;

    /**
     * Encodes the current sample array and writes it to the given
     * path using the format implemented by the subclass. If the
     * destination file already exists, it is overwritten.
     *
     * @param filePath  destination path on disk
     * @throws AudioFileException if there are no samples to save or
     *         the file cannot be written to disk
     */
    public abstract void save(String filePath) throws AudioFileException;

    /**
     * Returns the duration of the current edit state in seconds,
     * computed from the total sample count, channel count, and
     * sample rate. Returns 0 if the file has not been loaded yet.
     *
     * @return duration of the current samples in seconds
     */
    public double getDuration()
    {
        if (samples == null || channels == 0 || sampleRate == 0)
        {
            return 0.0;
        }
        return (double) samples.length / channels / sampleRate;
    }

    /**
     * Returns the duration of the original (pre-edit) audio in
     * seconds, ignoring any trim or processing applied. Returns 0
     * if the file has not been loaded yet.
     *
     * @return duration of the original samples in seconds
     */
    public double getOriginalDuration()
    {
        if (originalSamples == null || channels == 0 || sampleRate == 0)
        {
            return 0.0;
        }
        return (double) originalSamples.length / channels / sampleRate;
    }

    /**
     * Restores the editable sample buffer to a fresh copy of the
     * original (as decoded from disk), discarding any trim or
     * processing that had been applied.
     * <p>
     * Has no effect if the file has not been loaded yet.
     */
    public void reset()
    {
        if (originalSamples != null)
        {
            this.samples = originalSamples.clone();
        }
    }

    /**
     * Trims the start and end of the audio, replacing the current
     * editable buffer with a freshly extracted slice of the original.
     * <p>
     * <b>Non-cumulative.</b> Each call starts from
     * {@code originalSamples} rather than from the current edit
     * state. Calling {@code trim(2.0, 1.0)} and then
     * {@code trim(0.5, 0.5)} leaves the audio with 0.5 seconds
     * trimmed from each end of the original - not 2.5 from the
     * start and 1.5 from the end. This matches the natural
     * behaviour expected from a slider-style trim control in the
     * UI, and ensures that the operation is idempotent and
     * lossless across repeated adjustments.
     * <p>
     * Any processing previously applied to {@code samples} is
     * discarded by this call: the resulting buffer is a clean
     * slice of the original.
     *
     * @param startSec  seconds to remove from the start (≥ 0)
     * @param endSec    seconds to remove from the end (≥ 0)
     * @throws IllegalStateException     if the file has not been loaded
     * @throws IllegalArgumentException  if either value is negative,
     *         or if the requested trim would leave zero or negative
     *         frames remaining
     */
    public void trim(double startSec, double endSec)
    {
        if (originalSamples == null || sampleRate == 0 || channels == 0)
        {
            throw new IllegalStateException(
                    "Cannot trim before the file is loaded.");
        }
        if (startSec < 0.0 || endSec < 0.0)
        {
            throw new IllegalArgumentException(
                    "Trim values must be non-negative; got startSec=" + startSec
                            + ", endSec=" + endSec);
        }

        long totalFrames = originalSamples.length / channels;
        long startFrames = Math.round(startSec * sampleRate);
        long endFrames   = Math.round(endSec   * sampleRate);

        if (startFrames + endFrames >= totalFrames)
        {
            throw new IllegalArgumentException(
                    "Trim leaves no audio remaining (startSec=" + startSec
                            + ", endSec=" + endSec + ", duration="
                            + ((double) totalFrames / sampleRate) + "s).");
        }

        int startIndex = (int) (startFrames * channels);
        int endIndex   = (int) ((totalFrames - endFrames) * channels);
        int newLength  = endIndex - startIndex;

        float[] trimmed = new float[newLength];
        System.arraycopy(originalSamples, startIndex, trimmed, 0, newLength);
        this.samples = trimmed;
    }

    /* Getters */

    public String getFilePath()  { return filePath; }
    public int getSampleRate()   { return sampleRate; }
    public int getChannels()     { return channels; }
    public float[] getSamples()  { return samples; }

    /* Setter (only for samples - modified by pipeline processors) */

    /**
     * Replaces the current editable sample buffer. Used by pipeline
     * processors that produce a new array (for example, when the
     * length changes). Does <b>not</b> modify the pristine original
     * snapshot.
     *
     * @param samples  the new editable sample array
     */
    public void setSamples(float[] samples)
    {
        this.samples = samples;
    }

    /* Protected setters used by subclasses during load() */

    protected void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    protected void setChannels(int channels)     { this.channels = channels; }

    /**
     * Used by subclass implementations of {@link #load()} to install
     * the decoded sample array. Stores a defensive copy as the
     * pristine original snapshot, and sets the editable buffer to
     * the same data.
     *
     * @param decoded  freshly decoded samples to install as both
     *                 the original and the current edit state
     */
    protected void setSamplesAsLoaded(float[] decoded)
    {
        this.samples = decoded;
        this.originalSamples = (decoded != null) ? decoded.clone() : null;
    }
}