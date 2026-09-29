package com.quickmaster.audio;

import com.dspark.core.Dither;
import com.dspark.io.Mp3Decoder;
import com.dspark.io.Mp3Stream;
import de.sciss.jump3r.lowlevel.LameEncoder;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP3 import/export with an unchanged AudioFile contract.
 * Imports MPEG-1 through DSPark's pure-Java float decoder; MPEG-2/2.5 use the
 * explicit float-output compatibility adapter. Neither path quantizes or clips
 * decoded PCM. Validated encoder delay/padding are removed before editing.
 * Encoding retains the existing offline LAME path and TPDF-dithered PCM16 input.
 * MP3 has no PCM bit depth; internal full scale is nominal, not a float clamp.
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
     * metadata does not report a usable bitrate.
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
     * MP3's measured mean audio bitrate; for files created
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

    /** Decode completely before publishing, preserving the previous audio on failure. */
    @Override
    public void load() throws AudioFileException
    {
        try
        {
            Mp3Stream stream = Mp3Stream.read(Path.of(getFilePath()));
            Mp3Stream.Audio audio = stream.version() == 1
                    ? Mp3Decoder.decode(stream) : MpegLsfFloatDecoder.decode(stream);
            AtomicAudioWrite.validateSamples(audio.samples(), audio.sampleRate(), audio.channels());
            AtomicAudioWrite.checkCancelled();
            setSampleRate(audio.sampleRate());
            setChannels(audio.channels());
            setSamplesAsLoaded(audio.samples());
            bitrate = audio.bitrateKbps() > 0 ? audio.bitrateKbps() : FALLBACK_DECODED_BITRATE_KBPS;
            vbr = audio.variableBitrate();
        }
        catch (IOException e)
        {
            throw new AudioFileException("Cannot read MP3: " + getFilePath() + " — " + e.getMessage(), e);
        }
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
        AtomicAudioWrite.validateSamples(samples, getSampleRate(), getChannels());
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

        AtomicAudioWrite.write(filePath, staging -> {
            LameEncoder encoder = new LameEncoder(
                    pcmFormat, bitrate, mode, LAME_QUALITY, false);

            // Input is consumed in chunks of getPCMBufferSize(); the
            // encoded output goes into a getMP3BufferSize() buffer.
            final int pcmChunkSize = encoder.getPCMBufferSize() / (2 * channels) * (2 * channels);
            if (pcmChunkSize <= 0) {
                encoder.close();
                throw new AudioFileException("MP3 encoder supplied an invalid input buffer size.");
            }
            final byte[] pcm = new byte[pcmChunkSize];
            final Dither dither = new Dither(16, false);
            final byte[] mp3Buffer = new byte[encoder.getMP3BufferSize()];

            try (BufferedOutputStream out =
                         new BufferedOutputStream(Files.newOutputStream(staging)))
            {
                // LameEncoder disables automatic ID3 writing. Add only our two
                // fresh descriptive fields; leave encoder audio/timing frames intact.
                out.write(ExportMetadata.now().mp3Tag());
                int samplePosition = 0;
                while (samplePosition < samples.length)
                {
                    AtomicAudioWrite.checkCancelled();
                    int count = Math.min(pcm.length / 2, samples.length - samplePosition);
                    for (int i = 0; i < count; i++) {
                        float q = dither.processSample(clamp(samples[samplePosition + i]), i % channels);
                        int value = (int) Math.max(-32768, Math.min(32767, Math.rint(q * 32768.0)));
                        pcm[2 * i] = (byte) value;
                        pcm[2 * i + 1] = (byte) (value >> 8);
                    }
                    int encoded = encoder.encodeBuffer(pcm, 0, count * 2, mp3Buffer);
                    if (encoded < 0) throw new AudioFileException("MP3 encoding failed: " + encoded);
                    if (encoded > 0)
                    {
                        out.write(mp3Buffer, 0, encoded);
                    }
                    samplePosition += count;
                }

                // Flush the final MP3 frame(s) and the LAME info tag.
                // Without this call the encoded file is truncated.
                int rest = encoder.encodeFinish(mp3Buffer);
                if (rest < 0) throw new AudioFileException("MP3 encoder flush failed: " + rest);
                if (rest > 0)
                {
                    out.write(mp3Buffer, 0, rest);
                }
            }
            finally
            {
                encoder.close();
            }
        });
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
