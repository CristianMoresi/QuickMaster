package com.quickmaster.audio;

import com.dspark.core.Dither;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ServiceLoader;

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

        try (AudioInputStream in = openWavInputStream(file))
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

            if (sampleRate <= 0 || format.getSampleRate() != sampleRate
                    || (isFloatEncoding && bits != 32)
                    || format.isBigEndian() || format.getFrameSize() != channels * (bits / 8))
                throw new AudioFileException("Unsupported or malformed WAV sample format: " + format);

            validateRiffData(file, format.getFrameSize());
            float[] decoded = PcmDecoder.read(in, bits, isFloatEncoding, channels, in.getFrameLength());

            AtomicAudioWrite.validateSamples(decoded, sampleRate, channels);
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

    /** Java Sound rounds incomplete data chunks down to whole frames; reject
     * that corruption before allocating from an untrusted declared frame count. */
    private static void validateRiffData(File file, int frameBytes) throws IOException, AudioFileException {
        try (var raw = new java.io.RandomAccessFile(file, "r")) {
            if (raw.length() < 12 || raw.readInt() != 0x52494646) throw new AudioFileException("Invalid WAV RIFF header.");
            long end = Integer.toUnsignedLong(Integer.reverseBytes(raw.readInt())) + 8;
            if (raw.readInt() != 0x57415645 || end < 12 || end > raw.length())
                throw new AudioFileException("Truncated or invalid WAV RIFF length.");
            for (long position = 12; position + 8 <= end; ) {
                AtomicAudioWrite.checkCancelled();
                raw.seek(position); int id = raw.readInt();
                long size = Integer.toUnsignedLong(Integer.reverseBytes(raw.readInt()));
                if (size > end - position - 8) throw new AudioFileException("Truncated WAV chunk.");
                if (id == 0x64617461) {
                    if (size % frameBytes != 0) throw new AudioFileException("Incomplete WAV data frame.");
                    return;
                }
                position += 8 + size + (size & 1);
            }
            throw new AudioFileException("WAV has no audio data chunk.");
        }
    }

    /**
     * Opens a WAV with the runtime's dedicated WAV readers instead of asking
     * {@link AudioSystem} to probe every installed decoder. The mp3spi reader
     * can otherwise inspect some large floating-point WAV files first and
     * exhaust its mark buffer, aborting detection with "Resetting to invalid
     * mark" before Java reaches the correct WAV reader.
     */
    private static AudioInputStream openWavInputStream(File file)
            throws UnsupportedAudioFileException, IOException
    {
        IOException ioFailure = null;
        boolean foundWavReader = false;

        for (AudioFileReader reader : ServiceLoader.load(AudioFileReader.class))
        {
            // Java's PCM, float and extensible implementations are named
            // WaveFileReader, WaveFloatFileReader and WaveExtensibleFileReader.
            if (!reader.getClass().getSimpleName().startsWith("Wave"))
            {
                continue;
            }

            foundWavReader = true;
            try
            {
                return reader.getAudioInputStream(file);
            }
            catch (UnsupportedAudioFileException ignored)
            {
                // This WAV variant belongs to one of the other WAV readers.
            }
            catch (IOException e)
            {
                if (ioFailure == null)
                {
                    ioFailure = e;
                }
                else
                {
                    ioFailure.addSuppressed(e);
                }
            }
        }

        if (ioFailure != null)
        {
            throw ioFailure;
        }

        String detail = foundWavReader
                ? "No WAV reader recognised the file"
                : "No WAV reader is available in the Java runtime";
        throw new UnsupportedAudioFileException(detail + ": " + file);
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
        AtomicAudioWrite.validateSamples(samples, getSampleRate(), getChannels());
        if ((bitDepth != 16 && bitDepth != 24 && bitDepth != 32) || (isFloat && bitDepth != 32))
            throw new AudioFileException(
                    "Unsupported WAV format: use integer 16/24/32-bit or float 32-bit.");
        int bytesPerSample = bitDepth / 8;
        int frameSize = bytesPerSample * getChannels();
        long dataSize = (long) samples.length * bytesPerSample;
        int headerSize = isFloat ? 58 : 44; // IEEE float: WAVEFORMATEX + fact chunk.
        byte[] metadata = ExportMetadata.now().wavInfoChunk();
        long riffSize = headerSize - 8L + dataSize + (dataSize & 1) + metadata.length;
        long byteRate = (long) getSampleRate() * frameSize;
        if (riffSize > 0xffff_ffffL || byteRate > 0xffff_ffffL)
            throw new AudioFileException("Audio exceeds the 4 GiB RIFF/WAV limit.");

        AtomicAudioWrite.write(filePath, staging -> {
            try (var out = new BufferedOutputStream(Files.newOutputStream(staging))) {
                ByteBuffer header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
                header.putInt(0x46464952).putInt((int) riffSize).putInt(0x45564157);
                header.putInt(0x20746d66).putInt(isFloat ? 18 : 16);
                header.putShort((short) (isFloat ? 3 : 1)).putShort((short) getChannels());
                header.putInt(getSampleRate()).putInt((int) byteRate);
                header.putShort((short) frameSize).putShort((short) bitDepth);
                if (isFloat) {
                    header.putShort((short) 0);
                    header.putInt(0x74636166).putInt(4).putInt(samples.length / getChannels());
                }
                header.putInt(0x61746164).putInt((int) dataSize);
                out.write(header.array());
                Dither dither = bitDepth < 32 ? new Dither(bitDepth, false) : null;
                ByteBuffer block = ByteBuffer.allocate(8192 * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
                for (int offset = 0; offset < samples.length;) {
                    AtomicAudioWrite.checkCancelled();
                    block.clear();
                    int end = Math.min(samples.length, offset + 8192);
                    for (int i = offset; i < end; i++) {
                        float s = samples[i];
                        if (isFloat) block.putFloat(s); // Preserve finite float headroom.
                        else if (bitDepth == 32) {
                            long value = Math.round((double) clamp(s) * 2147483648.0);
                            block.putInt((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value)));
                        } else {
                            double scale = bitDepth == 16 ? 32768.0 : 8388608.0;
                            float q = dither.processSample(clamp(s), i % getChannels());
                            int value = (int) Math.max(-scale, Math.min(scale - 1, Math.rint(q * scale)));
                            if (bitDepth == 16) block.putShort((short) value);
                            else block.put((byte) value).put((byte) (value >> 8)).put((byte) (value >> 16));
                        }
                    }
                    out.write(block.array(), 0, block.position());
                    offset = end;
                }
                if ((dataSize & 1) != 0) out.write(0);
                out.write(metadata);
            }
        });
    }

    /* --- Encoders: normalized float[] -> bytes --- */

    private static float clamp(float v)
    {
        if (v >  1.0f) return  1.0f;
        if (v < -1.0f) return -1.0f;
        return v;
    }

}
