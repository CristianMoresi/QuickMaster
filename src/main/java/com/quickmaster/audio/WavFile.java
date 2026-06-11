package com.quickmaster.audio;

import com.dspark.core.Dither;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Concrete {@link AudioFile} implementation for WAV files.
 * <p>
 * Supports PCM-encoded WAV files in the following sample formats:
 * <ul>
 *   <li>16-bit signed integer</li>
 *   <li>24-bit signed integer (packed, little-endian)</li>
 *   <li>32-bit signed integer</li>
 *   <li>32-bit IEEE floating point</li>
 * </ul>
 * Sample rates from 44100 Hz up to 192000 Hz are supported, in mono
 * or stereo. All samples are decoded into the inherited normalized
 * float representation (range -1.0 to +1.0) regardless of the source
 * bit depth, so processors downstream do not need to be aware of the
 * original encoding.
 * <p>
 * Any failure during {@link #load()} or {@link #save(String)} is
 * reported via {@link AudioFileException}; the application is never
 * terminated by an unchecked exception originating from this class.
 */
public class WavFile extends AudioFile
{
    private int bitDepth;
    private boolean isFloat;

    /**
     * Constructs an empty WavFile bound to the given path. The file
     * is not read until {@link #load()} is invoked.
     *
     * @param filePath  path to the WAV file on disk
     */
    public WavFile(String filePath)
    {
        super(filePath);
    }

    /**
     * Full constructor for creating a WavFile programmatically with
     * all data already in memory (for example, when synthesising
     * audio or exporting from another source).
     *
     * @param filePath    path the file is or will be associated with
     * @param sampleRate  sample rate in Hz
     * @param channels    number of channels (1 = mono, 2 = stereo)
     * @param samples     interleaved float samples in range -1.0 to +1.0
     * @param bitDepth    bits per sample (16, 24 or 32)
     * @param isFloat     true for 32-bit float PCM, false for integer PCM
     */
    public WavFile(String filePath, int sampleRate, int channels, float[] samples,
                   int bitDepth, boolean isFloat)
    {
        super(filePath, sampleRate, channels, samples);
        this.bitDepth = bitDepth;
        this.isFloat = isFloat;
    }

    public int getBitDepth() { return bitDepth; }
    public boolean isFloat() { return isFloat; }

    /* ====================================================================
     *  LOAD
     * ==================================================================== */

    /**
     * Reads the WAV file at the configured path, decodes the PCM data
     * into normalized float samples, and populates the inherited
     * sample rate, channel count and sample array (both the editable
     * buffer and the pristine original snapshot, via
     * {@link #setSamplesAsLoaded(float[])}).
     *
     * @throws AudioFileException if the file does not exist, cannot
     *         be read, has a malformed header, or uses an encoding
     *         or bit depth not supported by the application
     */
    @Override
    public void load() throws AudioFileException
    {
        File file = new File(getFilePath());

        if (!file.exists())
        {
            throw new AudioFileException(
                    "WAV file does not exist: " + getFilePath());
        }
        if (!file.canRead())
        {
            throw new AudioFileException(
                    "WAV file cannot be read (check permissions): " + getFilePath());
        }

        try (AudioInputStream in = AudioSystem.getAudioInputStream(file))
        {
            AudioFormat format = in.getFormat();

            int channels   = format.getChannels();
            int sampleRate = (int) format.getSampleRate();
            int bits       = format.getSampleSizeInBits();
            AudioFormat.Encoding encoding = format.getEncoding();

            boolean isFloatEncoding = encoding == AudioFormat.Encoding.PCM_FLOAT;
            boolean isIntEncoding   = encoding == AudioFormat.Encoding.PCM_SIGNED;

            if (!isFloatEncoding && !isIntEncoding)
            {
                throw new AudioFileException(
                        "Unsupported WAV encoding: " + encoding
                                + " (only PCM signed integer and PCM 32-bit float are supported)");
            }
            if (bits != 16 && bits != 24 && bits != 32)
            {
                throw new AudioFileException(
                        "Unsupported WAV bit depth: " + bits
                                + " (supported: 16, 24, 32)");
            }
            if (channels < 1 || channels > 2)
            {
                throw new AudioFileException(
                        "Unsupported channel count: " + channels
                                + " (supported: 1 mono, 2 stereo)");
            }

            byte[] raw = in.readAllBytes();

            float[] decoded;
            if (isFloatEncoding && bits == 32)      decoded = decode32BitFloat(raw);
            else if (bits == 16)                    decoded = decode16BitInt(raw);
            else if (bits == 24)                    decoded = decode24BitInt(raw);
            else                                    decoded = decode32BitInt(raw);

            setSampleRate(sampleRate);
            setChannels(channels);
            setSamplesAsLoaded(decoded);
            this.bitDepth = bits;
            this.isFloat  = isFloatEncoding;
        }
        catch (UnsupportedAudioFileException e)
        {
            throw new AudioFileException(
                    "File is not a recognised WAV: " + getFilePath(), e);
        }
        catch (IOException e)
        {
            throw new AudioFileException(
                    "I/O error while reading WAV: " + getFilePath(), e);
        }
    }

    /* --- Decoders: bytes -> normalized float[] --- */

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

    private static float[] decode24BitInt(byte[] raw)
    {
        int n = raw.length / 3;
        float[] out = new float[n];
        for (int i = 0; i < n; i++)
        {
            int b0 = raw[i * 3]     & 0xFF;
            int b1 = raw[i * 3 + 1] & 0xFF;
            int b2 = raw[i * 3 + 2];                  // signed: keep top byte signed
            int sample = (b2 << 16) | (b1 << 8) | b0;
            out[i] = sample / 8388608.0f;             // 2^23
        }
        return out;
    }

    private static float[] decode32BitInt(byte[] raw)
    {
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int n = raw.length / 4;
        float[] out = new float[n];
        for (int i = 0; i < n; i++)
        {
            int s = bb.getInt();
            out[i] = (float) (s / 2147483648.0);      // 2^31
        }
        return out;
    }

    private static float[] decode32BitFloat(byte[] raw)
    {
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int n = raw.length / 4;
        float[] out = new float[n];
        for (int i = 0; i < n; i++)
        {
            out[i] = bb.getFloat();
        }
        return out;
    }

    /* ====================================================================
     *  SAVE
     * ==================================================================== */

    /**
     * Encodes the current sample array as a WAV file at the given
     * path, using the bit depth and encoding (integer or float)
     * currently configured on this instance. If the destination file
     * already exists, it is overwritten.
     *
     * @param filePath  destination path on disk
     * @throws AudioFileException if there are no samples to save,
     *         the configured bit depth is invalid, or an I/O error
     *         occurs during writing
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
        if (getSampleRate() <= 0 || getChannels() <= 0)
        {
            throw new AudioFileException(
                    "Invalid audio metadata: sample rate=" + getSampleRate()
                            + ", channels=" + getChannels());
        }

        byte[] raw;
        AudioFormat.Encoding encoding;
        int frameSize;

        if (isFloat && bitDepth == 32)
        {
            raw = encode32BitFloat(samples);
            encoding = AudioFormat.Encoding.PCM_FLOAT;
            frameSize = 4 * getChannels();
        }
        else if (bitDepth == 16)
        {
            raw = encode16BitInt(samples, getChannels());
            encoding = AudioFormat.Encoding.PCM_SIGNED;
            frameSize = 2 * getChannels();
        }
        else if (bitDepth == 24)
        {
            raw = encode24BitInt(samples, getChannels());
            encoding = AudioFormat.Encoding.PCM_SIGNED;
            frameSize = 3 * getChannels();
        }
        else if (bitDepth == 32)
        {
            raw = encode32BitInt(samples);
            encoding = AudioFormat.Encoding.PCM_SIGNED;
            frameSize = 4 * getChannels();
        }
        else
        {
            throw new AudioFileException(
                    "Unsupported bit depth for save: " + bitDepth
                            + " (supported: 16, 24, 32)");
        }

        AudioFormat format = new AudioFormat(
                encoding,
                getSampleRate(),
                bitDepth,
                getChannels(),
                frameSize,
                getSampleRate(),
                false                               // little-endian
        );

        long frameLength = raw.length / frameSize;

        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(raw), format, frameLength))
        {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(filePath));
        }
        catch (IOException e)
        {
            throw new AudioFileException(
                    "I/O error while writing WAV: " + filePath, e);
        }
    }

    /* --- Encoders: normalized float[] -> bytes --- */

    private static float clamp(float v)
    {
        if (v >  1.0f) return  1.0f;
        if (v < -1.0f) return -1.0f;
        return v;
    }

    /**
     * Quantises to 16-bit with TPDF dither (per-channel state), replacing
     * truncation distortion with a flat, uncorrelated noise floor - the
     * standard practice when delivering a float master at a reduced depth.
     */
    private static byte[] encode16BitInt(float[] samples, int channels)
    {
        Dither dither = new Dither(16, false);
        int ch = Math.max(1, channels);
        ByteBuffer bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples.length; i++)
        {
            float q = dither.processSample(clamp(samples[i]), i % ch);
            int v = (int) Math.rint(q * 32768.0);
            if (v >  32767) v =  32767;
            if (v < -32768) v = -32768;
            bb.putShort((short) v);
        }
        return bb.array();
    }

    /** Quantises to 24-bit with TPDF dither (see {@link #encode16BitInt}). */
    private static byte[] encode24BitInt(float[] samples, int channels)
    {
        Dither dither = new Dither(24, false);
        int ch = Math.max(1, channels);
        byte[] out = new byte[samples.length * 3];
        for (int i = 0; i < samples.length; i++)
        {
            float q = dither.processSample(clamp(samples[i]), i % ch);
            int v = (int) Math.rint(q * 8388608.0);                 // 2^23
            if (v >  8388607) v =  8388607;
            if (v < -8388608) v = -8388608;
            out[i * 3]     = (byte) ( v        & 0xFF);
            out[i * 3 + 1] = (byte) ((v >> 8)  & 0xFF);
            out[i * 3 + 2] = (byte) ((v >> 16) & 0xFF);
        }
        return out;
    }

    private static byte[] encode32BitInt(float[] samples)
    {
        ByteBuffer bb = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples)
        {
            long v = Math.round((double) clamp(s) * 2147483647.0);  // 2^31 - 1
            bb.putInt((int) v);
        }
        return bb.array();
    }

    private static byte[] encode32BitFloat(float[] samples)
    {
        ByteBuffer bb = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples)
        {
            bb.putFloat(s);
        }
        return bb.array();
    }
}