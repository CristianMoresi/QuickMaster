package com.quickmaster.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for auto-detecting the format of an audio file
 * based on its content (file header), and instantiating the
 * appropriate {@link AudioFile} subclass.
 * <p>
 * Detection inspects the first bytes of the file (the so-called
 * "magic numbers") rather than relying on the file extension,
 * which can be misleading or absent. Supported formats are
 * declared in {@link AudioFileType}.
 * <p>
 * This class is stateless: all methods are {@code static} and
 * the constructor is private to prevent instantiation. It exists
 * solely to centralise the "what format is this file?" decision
 * in one place, so that the rest of the application can work
 * with {@link AudioFile} polymorphically without ever asking
 * about the underlying format itself.
 */
public final class AudioFormatDetector
{
    /** Number of bytes read from the file header for detection. */
    private static final int HEADER_SIZE = 12;

    private AudioFormatDetector()
    {
        // Utility class - not instantiable.
    }

    /**
     * Inspects the file header and returns the detected
     * {@link AudioFileType}. Does not load the file content.
     *
     * @param filePath  path to the file on disk
     * @return the detected format, or {@link AudioFileType#UNKNOWN}
     *         if the header does not match any supported format
     * @throws AudioFileException if the file does not exist or
     *         cannot be read
     */
    public static AudioFileType detect(String filePath) throws AudioFileException
    {
        Path path = Path.of(filePath);

        if (!Files.exists(path))
        {
            throw new AudioFileException(
                    "File does not exist: " + filePath);
        }

        if (!Files.isReadable(path))
        {
            throw new AudioFileException(
                    "File cannot be read (check permissions): " + filePath);
        }

        byte[] header = readHeader(path);

        if (isWav(header))
        {
            return AudioFileType.WAV;
        }

        if (isMp3(header))
        {
            return AudioFileType.MP3;
        }
        return AudioFileType.UNKNOWN;
    }

    /**
     * Detects the format of the file and returns the appropriate
     * {@link AudioFile} subclass, ready to have {@link AudioFile#load()}
     * called on it.
     * <p>
     * Note: this method does not invoke {@code load()} itself. It
     * only constructs the correct subclass. The caller is responsible
     * for calling {@code load()} afterwards. This separation lets the
     * caller decide whether to load synchronously, on a background
     * thread, or defer the operation entirely.
     *
     * @param filePath  path to the file on disk
     * @return a concrete {@link AudioFile} subclass matching the
     *         detected format
     * @throws AudioFileException if the file does not exist, cannot
     *         be read, or its format is not supported
     */
    public static AudioFile loadAuto(String filePath) throws AudioFileException
    {
        AudioFileType type = detect(filePath);

        return switch (type)
        {
            case WAV -> new WavFile(filePath);
            case MP3 -> new Mp3File(filePath);
            case UNKNOWN -> throw new AudioFileException(
                    "Unsupported or unrecognised audio format: " + filePath);
        };
    }

    /* --- Private helpers --- */

    /**
     * Reads up to {@link #HEADER_SIZE} bytes from the start of the
     * file. Returns a smaller array if the file is shorter than
     * the requested size.
     */
    private static byte[] readHeader(Path path) throws AudioFileException
    {
        byte[] buffer = new byte[HEADER_SIZE];
        try (InputStream in = Files.newInputStream(path))
        {
            int bytesRead = in.read(buffer);
            if (bytesRead <= 0)
            {
                return new byte[0];
            }

            if (bytesRead < HEADER_SIZE)
            {
                byte[] truncated = new byte[bytesRead];
                System.arraycopy(buffer, 0, truncated, 0, bytesRead);
                return truncated;
            }

            return buffer;
        }
        catch (IOException e)
        {
            throw new AudioFileException(
                    "I/O error while reading file header: " + path, e);
        }
    }

    /**
     * Checks whether the header matches the WAV magic numbers.
     * A WAV file starts with the ASCII bytes "RIFF" (offset 0)
     * and "WAVE" (offset 8).
     */
    private static boolean isWav(byte[] header)
    {
        if (header.length < 12)
        {
            return false;
        }

        return header[0] == 'R' && header[1] == 'I'
                && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'A'
                && header[10] == 'V' && header[11] == 'E';
    }

    /**
     * Checks whether the header matches the MP3 magic numbers.
     * An MP3 file starts either with the ASCII bytes "ID3"
     * (ID3v2 metadata header) or with an MP3 frame sync:
     * the first byte is 0xFF and the second byte has the top
     * 3 bits set (i.e. byte & 0xE0 == 0xE0).
     */
    private static boolean isMp3(byte[] header)
    {
        if (header.length < 3)
        {
            return false;
        }

        boolean hasId3 = header[0] == 'I' && header[1] == 'D' && header[2] == '3';
        boolean hasFrameSync = (header[0] & 0xFF) == 0xFF
                && ((header[1] & 0xE0) == 0xE0);
        return hasId3 || hasFrameSync;
    }
}