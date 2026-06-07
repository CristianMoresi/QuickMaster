package com.quickmaster.audio;

import de.sciss.jump3r.lowlevel.LameEncoder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * Concrete {@link AudioFile} implementation for MP3 files.
 * <p>
 * MP3 is not natively handled by the JDK, so QuickMaster relies
 * on two pure-Java libraries to bridge the gap:
 * <ul>
 *   <li><b>JLayer + mp3spi</b> for decoding (read support).
 *       The SPI registers an MP3 decoder with the standard
 *       {@code javax.sound.sampled.AudioSystem}, so the same
 *       APIs used for WAV decoding work transparently for MP3:
 *       open an {@link AudioInputStream}, ask the system to
 *       convert the MPEG-encoded stream into linear PCM, and
 *       then read raw bytes.</li>
 *   <li><b>jump3r</b> (a pure-Java port of LAME 3.98.4) for
 *       encoding (write support). The
 *       {@link LameEncoder} convenience wrapper takes a buffer
 *       of 16-bit signed little-endian interleaved PCM and
 *       produces an MP3 byte stream.</li>
 * </ul>
 * Both libraries are pure Java, so the application stays free
 * of native dependencies and remains portable across operating
 * systems.
 * <p>
 * <b>Internal representation.</b> Following the contract of
 * {@link AudioFile}, samples are stored as interleaved floats
 * in the normalized range [-1.0, +1.0], regardless of the bit
 * depth used on disk. The decoder converts 16-bit signed PCM
 * (the standard output of LAME-family decoders) into floats by
 * dividing by 32768. The encoder does the inverse: floats are
 * quantised to 16-bit signed little-endian PCM, with clamping
 * to the valid range as a defensive safeguard against any
 * upstream out-of-range value.
 * <p>
 * <b>Bitrate handling.</b> The class carries a {@code bitrate}
 * field in kbps. After {@link #load()}, the field reflects the
 * actual bitrate of the source MP3 as reported by mp3spi (or
 * {@value #FALLBACK_DECODED_BITRATE_KBPS} as a fallback if the
 * property is not available, which can happen on some VBR
 * files). The field can be overwritten via
 * {@link #setBitrate(int)} before calling {@link #save(String)}
 * so the user can pick a target bitrate at export time. The
 * default for files created from scratch (no MP3 source) is
 * {@value #DEFAULT_BITRATE_KBPS} kbps, the highest standard
 * MP3 bitrate.
 * <p>
 * <b>Encoder configuration.</b> The save path produces a
 * constant-bit-rate MP3 at the bitrate currently held on the
 * instance. The MPEG mode is chosen automatically from the
 * channel count: joint stereo for two channels (the standard
 * choice for music) and mono for one channel. The mode value
 * is the {@code int} expected by jump3r's {@link LameEncoder}
 * constructor, using the canonical LAME mode constants
 * (STEREO=0, JOINT_STEREO=1, DUAL_CHANNEL=2, MONO=3). The
 * encoder's internal quality setting is fixed to LAME quality
 * 0 (slowest algorithms, best possible quality) since
 * encoding is performed offline at export time and speed is
 * not a concern.
 * <p>
 * <b>Sample-rate limitation.</b> The MP3 format only supports
 * the sample rates 32&nbsp;000, 44&nbsp;100 and 48&nbsp;000 Hz
 * for the MPEG-1 family commonly used in QuickMaster's range.
 * If the user attempts to export an audio whose sample rate
 * falls outside this set (for example a 96&nbsp;000 or
 * 192&nbsp;000 Hz WAV imported earlier), {@link #save(String)}
 * fails fast with an {@link AudioFileException} carrying a
 * clear, actionable message.
 * <p>
 * <b>Non-destructive editing.</b> {@link #load()} installs the
 * decoded samples via {@link #setSamplesAsLoaded(float[])}, so
 * the inherited {@code reset} and {@code trim} workflows behave
 * identically to {@link WavFile}.
 */
public class Mp3File extends AudioFile
{
    /**
     * Default MP3 encoding bitrate, in kbps. 320 is the highest
     * standard MP3 bitrate and the safest default for a
     * mastering tool exporting to a lossy format.
     */
    public static final int DEFAULT_BITRATE_KBPS = 320;

    /**
     * Fallback bitrate used when the source MP3's bitrate
     * property is not reported by mp3spi (some VBR files).
     */
    public static final int FALLBACK_DECODED_BITRATE_KBPS = 192;

    /**
     * LAME internal algorithm quality setting (0..9, where 0 is
     * the slowest/highest-quality setting). Since QuickMaster
     * exports offline, the encoder is configured for the best
     * possible quality regardless of speed.
     */
    private static final int LAME_QUALITY = 0;

    /**
     * LAME MPEG mode constants. These match the canonical
     * values used by the C LAME library and its Java ports,
     * which jump3r's {@link LameEncoder} expects as a plain
     * {@code int} in its constructor.
     */
    private static final int LAME_MODE_STEREO       = 0;
    private static final int LAME_MODE_JOINT_STEREO = 1;
    private static final int LAME_MODE_DUAL_CHANNEL = 2;
    private static final int LAME_MODE_MONO         = 3;

    /** Allowed MP3 sample rates for the MPEG-1 family (Hz). */
    private static final int[] SUPPORTED_SAMPLE_RATES = { 32000, 44100, 48000 };

    private int bitrate;
    private boolean vbr;

    /**
     * Constructs an empty Mp3File bound to the given path. The
     * file is not read until {@link #load()} is invoked. The
     * bitrate defaults to {@value #DEFAULT_BITRATE_KBPS} kbps
     * and VBR is disabled; both fields are overwritten by
     * {@link #load()} based on the source file's metadata.
     *
     * @param filePath  path to the MP3 file on disk
     */
    public Mp3File(String filePath)
    {
        super(filePath);
        this.bitrate = DEFAULT_BITRATE_KBPS;
        this.vbr = false;
    }

    /**
     * Full constructor for creating an Mp3File programmatically
     * with all data already in memory.
     *
     * @param filePath    path the file is or will be associated with
     * @param sampleRate  sample rate in Hz (must be 32000, 44100 or 48000)
     * @param channels    number of channels (1 = mono, 2 = stereo)
     * @param samples     interleaved float samples in range -1.0 to +1.0
     * @param bitrate     bitrate in kbps (e.g. 128, 192, 320)
     * @param vbr         true if the encoding uses Variable Bit Rate
     */
    public Mp3File(String filePath, int sampleRate, int channels, float[] samples,
                   int bitrate, boolean vbr)
    {
        super(filePath, sampleRate, channels, samples);
        this.bitrate = bitrate;
        this.vbr = vbr;
    }

    /**
     * Returns the bitrate (in kbps) currently associated with
     * this instance. After {@link #load()} this is the source
     * MP3's bitrate as reported by mp3spi; for files created
     * from scratch it is {@value #DEFAULT_BITRATE_KBPS}.
     *
     * @return the bitrate in kbps
     */
    public int getBitrate() { return bitrate; }

    /**
     * Overrides the bitrate used by {@link #save(String)}.
     * Intended to be called by the controller right before
     * export so the user can pick a target bitrate via the UI.
     *
     * @param bitrate  desired bitrate in kbps (typically one of
     *                 64, 96, 128, 160, 192, 224, 256, 320)
     * @throws IllegalArgumentException if the value is not
     *         positive
     */
    public void setBitrate(int bitrate)
    {
        if (bitrate <= 0)
        {
            throw new IllegalArgumentException(
                    "bitrate must be > 0; got " + bitrate);
        }
        this.bitrate = bitrate;
    }

    /**
     * Returns whether the source MP3 was encoded with Variable
     * Bit Rate. Not currently exposed in the UI; kept for
     * completeness and future use.
     */
    public boolean isVbr() { return vbr; }

    /* ====================================================================
     *  LOAD
     * ==================================================================== */

    /**
     * Reads the MP3 file at the configured path, decodes the
     * MPEG stream into normalized float samples, and populates
     * the inherited sample rate, channel count and sample array
     * (both the editable buffer and the pristine original
     * snapshot, via {@link #setSamplesAsLoaded(float[])}).
     * <p>
     * Decoding goes through the standard
     * {@code javax.sound.sampled.AudioSystem}: the mp3spi
     * service provider, registered automatically by being on
     * the classpath, recognises the MPEG stream and exposes it
     * as an {@link AudioInputStream}. The stream is then
     * converted to 16-bit signed little-endian linear PCM
     * (which is what LAME-family decoders natively produce),
     * fully read into memory, and finally normalised to the
     * float range [-1.0, +1.0] used throughout the application.
     * <p>
     * The source bitrate is read from the mp3spi-provided
     * format properties and stored so that a later export keeps
     * the same bitrate by default. If the property is not
     * available (some VBR files), the field falls back to
     * {@value #FALLBACK_DECODED_BITRATE_KBPS} kbps.
     *
     * @throws AudioFileException if the file does not exist,
     *         cannot be read, is not a valid MP3, or its
     *         channel count is unsupported
     */
    @Override
    public void load() throws AudioFileException
    {
        File file = new File(getFilePath());

        if (!file.exists())
        {
            throw new AudioFileException(
                    "MP3 file does not exist: " + getFilePath());
        }
        if (!file.canRead())
        {
            throw new AudioFileException(
                    "MP3 file cannot be read (check permissions): " + getFilePath());
        }

        try (AudioInputStream mpegStream = AudioSystem.getAudioInputStream(file))
        {
            AudioFormat baseFormat = mpegStream.getFormat();
            int channels   = baseFormat.getChannels();
            int sampleRate = (int) baseFormat.getSampleRate();

            if (channels < 1 || channels > 2)
            {
                throw new AudioFileException(
                        "Unsupported MP3 channel count: " + channels
                                + " (supported: 1 mono, 2 stereo)");
            }

            // Inspect mp3spi properties to recover source bitrate
            // and VBR flag for later export defaults.
            extractBitrateAndVbr(baseFormat);

            // Target PCM format: 16-bit signed little-endian,
            // same sample rate and channel layout as the source.
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    channels,
                    channels * 2,           // frame size in bytes
                    sampleRate,
                    false                   // little-endian
            );

            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, mpegStream))
            {
                byte[] raw = pcmStream.readAllBytes();
                float[] decoded = decode16BitInt(raw);

                setSampleRate(sampleRate);
                setChannels(channels);
                setSamplesAsLoaded(decoded);
            }
        }
        catch (UnsupportedAudioFileException e)
        {
            throw new AudioFileException(
                    "File is not a recognised MP3: " + getFilePath(), e);
        }
        catch (IOException e)
        {
            throw new AudioFileException(
                    "I/O error while reading MP3: " + getFilePath(), e);
        }
    }

    /**
     * Extracts the source bitrate and VBR flag from the
     * mp3spi-provided {@link AudioFormat} properties, if
     * available. Updates the instance fields accordingly. If
     * the properties are not present, falls back to
     * {@value #FALLBACK_DECODED_BITRATE_KBPS} kbps and
     * {@code vbr = false}.
     */
    private void extractBitrateAndVbr(AudioFormat baseFormat)
    {
        Map<String, Object> properties = baseFormat.properties();
        Object bitrateProperty = (properties != null) ? properties.get("bitrate") : null;
        if (bitrateProperty instanceof Integer)
        {
            // mp3spi reports bitrate in bits per second.
            this.bitrate = ((Integer) bitrateProperty) / 1000;
            if (this.bitrate <= 0)
            {
                this.bitrate = FALLBACK_DECODED_BITRATE_KBPS;
            }
        }
        else
        {
            this.bitrate = FALLBACK_DECODED_BITRATE_KBPS;
        }

        Object vbrProperty = (properties != null) ? properties.get("vbr") : null;
        this.vbr = (vbrProperty instanceof Boolean) && (Boolean) vbrProperty;
    }

    /**
     * Converts a raw byte array of interleaved 16-bit signed
     * little-endian PCM samples into the normalized float
     * representation used throughout the application.
     */
    private static float[] decode16BitInt(byte[] raw)
    {
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int n = raw.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++)
        {
            short s = bb.getShort();
            out[i] = s / 32768.0f;
        }
        return out;
    }

    /* ====================================================================
     *  SAVE
     * ==================================================================== */

    /**
     * Encodes the current sample array as an MP3 file at the
     * given path, using the bitrate stored on this instance and
     * a joint-stereo MPEG mode for stereo inputs (mono for
     * single-channel inputs).
     * <p>
     * The encoder operates on 16-bit signed little-endian
     * interleaved PCM, so the float samples are first quantised
     * and clamped to that representation before being fed to
     * {@link LameEncoder}. The encoder consumes buffer-sized
     * chunks of PCM and produces an MP3 byte stream which is
     * written to disk; a final {@code encodeFinish()} flushes the
     * last MP3 frames including the LAME info tag.
     *
     * @param filePath  destination path on disk
     * @throws AudioFileException if there are no samples to
     *         save, the file's sample rate is not supported by
     *         the MP3 format, the channel count is unsupported,
     *         or an I/O error occurs during writing
     */
    @Override
    public void save(String filePath) throws AudioFileException
    {
        float[] samples = getSamples();
        if (samples == null || samples.length == 0)
        {
            throw new AudioFileException(
                    "No samples available to save - load a file or set samples first.");
        }

        int sampleRate = getSampleRate();
        int channels   = getChannels();

        if (sampleRate <= 0 || channels <= 0)
        {
            throw new AudioFileException(
                    "Invalid audio metadata: sample rate=" + sampleRate
                            + ", channels=" + channels);
        }
        if (channels < 1 || channels > 2)
        {
            throw new AudioFileException(
                    "Unsupported channel count for MP3 export: " + channels
                            + " (supported: 1 mono, 2 stereo)");
        }
        if (!isSupportedSampleRate(sampleRate))
        {
            throw new AudioFileException(
                    "Unsupported sample rate for MP3 export: " + sampleRate
                            + " Hz (supported: 32000, 44100, 48000 Hz). "
                            + "Export to WAV instead, or resample the audio.");
        }

        // Quantise floats to 16-bit signed little-endian PCM.
        byte[] pcm = encode16BitInt(samples);

        // Build the source AudioFormat that the encoder needs.
        AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false                       // little-endian
        );

        // LAME mode as a plain int: joint stereo for two
        // channels, mono for one.
        int mode = (channels == 2) ? LAME_MODE_JOINT_STEREO : LAME_MODE_MONO;

        try
        {
            LameEncoder encoder = new LameEncoder(
                    pcmFormat, bitrate, mode, LAME_QUALITY, false);

            // Input is consumed in chunks of getPCMBufferSize(); the
            // encoded output goes into a getMP3BufferSize() buffer.
            final int pcmChunkSize = encoder.getPCMBufferSize();
            final byte[] mp3Buffer = new byte[encoder.getMP3BufferSize()];

            try (BufferedOutputStream out =
                         new BufferedOutputStream(new FileOutputStream(filePath)))
            {
                int pcmPosition = 0;
                while (pcmPosition < pcm.length)
                {
                    int chunk = Math.min(pcmChunkSize, pcm.length - pcmPosition);
                    int encoded = encoder.encodeBuffer(pcm, pcmPosition, chunk, mp3Buffer);
                    if (encoded > 0)
                    {
                        out.write(mp3Buffer, 0, encoded);
                    }
                    pcmPosition += chunk;
                }

                // Flush the final MP3 frame(s) and the LAME info tag.
                // Without this call the encoded file is truncated.
                int rest = encoder.encodeFinish(mp3Buffer);
                if (rest > 0)
                {
                    out.write(mp3Buffer, 0, rest);
                }
            }
            finally
            {
                encoder.close();
            }
        }
        catch (IOException e)
        {
            throw new AudioFileException(
                    "I/O error while writing MP3: " + filePath, e);
        }
    }

    /**
     * Clamps a sample to the normalized range [-1.0, +1.0] -
     * defensive safeguard against upstream out-of-range values.
     */
    private static float clamp(float v)
    {
        if (v >  1.0f) return  1.0f;
        if (v < -1.0f) return -1.0f;
        return v;
    }

    /**
     * Encodes a normalized float sample array as interleaved
     * 16-bit signed little-endian PCM, with defensive clamping
     * to the valid range before quantisation.
     */
    private static byte[] encode16BitInt(float[] samples)
    {
        ByteBuffer bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples)
        {
            int v = Math.round(clamp(s) * 32767.0f);
            bb.putShort((short) v);
        }
        return bb.array();
    }

    /**
     * Checks whether the given sample rate is one of the MPEG-1
     * sample rates supported by the MP3 format used here.
     */
    private static boolean isSupportedSampleRate(int sampleRate)
    {
        for (int sr : SUPPORTED_SAMPLE_RATES)
        {
            if (sr == sampleRate)
            {
                return true;
            }
        }
        return false;
    }
}