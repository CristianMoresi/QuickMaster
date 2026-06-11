package com.quickmaster.audio;

import com.quickmaster.config.AppLogger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries a source file's metadata over to an exported file of the same
 * container, so mastering does not silently strip tags:
 * <ul>
 *   <li><b>MP3 → MP3</b>: the source's ID3v2 tag (leading) is prepended to
 *       the exported stream and an ID3v1 tag (trailing 128 bytes) is
 *       appended. The tags are copied verbatim as opaque blocks.</li>
 *   <li><b>WAV → WAV</b>: the source's {@code LIST}(INFO) and {@code bext}
 *       chunks are appended after the exported file's data chunk (a legal
 *       position for RIFF chunks) and the RIFF size field is updated.</li>
 * </ul>
 * Metadata copying is best-effort: any failure is logged and swallowed so
 * a tagging quirk can never fail an export.
 */
public final class MetadataPreserver
{
    private MetadataPreserver() { }

    /**
     * Copies the metadata of {@code srcPath} into {@code dstPath} when both
     * are the same container type (.mp3 → .mp3 or .wav → .wav). No-op
     * otherwise.
     *
     * @param srcPath  the originally loaded file
     * @param dstPath  the freshly exported file
     */
    public static void preserve(String srcPath, String dstPath)
    {
        try
        {
            String src = srcPath.toLowerCase(java.util.Locale.ROOT);
            String dst = dstPath.toLowerCase(java.util.Locale.ROOT);
            if (src.endsWith(".mp3") && dst.endsWith(".mp3"))
            {
                copyMp3Tags(srcPath, dstPath);
            }
            else if (src.endsWith(".wav") && dst.endsWith(".wav"))
            {
                copyWavMetadata(srcPath, dstPath);
            }
        }
        catch (Exception e)
        {
            AppLogger.warn("Could not preserve metadata from " + srcPath + ": " + e.getMessage());
        }
    }

    /* ====================================================================
     *  MP3: ID3v2 (leading) + ID3v1 (trailing)
     * ==================================================================== */

    private static void copyMp3Tags(String srcPath, String dstPath) throws IOException
    {
        byte[] src = Files.readAllBytes(Path.of(srcPath));

        byte[] id3v2 = extractId3v2(src);
        byte[] id3v1 = extractId3v1(src);
        if (id3v2 == null && id3v1 == null) return;

        Path dst = Path.of(dstPath);
        byte[] exported = Files.readAllBytes(dst);

        Path tmp = dst.resolveSibling(dst.getFileName() + ".tagtmp");
        try (var out = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        {
            if (id3v2 != null) out.write(id3v2);
            out.write(exported);
            if (id3v1 != null) out.write(id3v1);
        }
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The complete leading ID3v2 block (header + frames + optional footer), or null. */
    private static byte[] extractId3v2(byte[] data)
    {
        if (data.length < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') return null;
        int size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14)
                 | ((data[8] & 0x7F) << 7)  | (data[9] & 0x7F);
        boolean footer = (data[5] & 0x10) != 0;
        int total = 10 + size + (footer ? 10 : 0);
        if (total <= 10 || total > data.length) return null;
        return java.util.Arrays.copyOfRange(data, 0, total);
    }

    /** The trailing 128-byte ID3v1 tag, or null. */
    private static byte[] extractId3v1(byte[] data)
    {
        if (data.length < 128) return null;
        int off = data.length - 128;
        if (data[off] == 'T' && data[off + 1] == 'A' && data[off + 2] == 'G')
        {
            return java.util.Arrays.copyOfRange(data, off, data.length);
        }
        return null;
    }

    /* ====================================================================
     *  WAV: LIST(INFO) + bext chunks
     * ==================================================================== */

    private static void copyWavMetadata(String srcPath, String dstPath) throws IOException
    {
        List<byte[]> chunks = collectWavMetadataChunks(srcPath);
        if (chunks.isEmpty()) return;

        try (RandomAccessFile dst = new RandomAccessFile(dstPath, "rw"))
        {
            if (dst.length() < 12) return;
            byte[] hdr = new byte[4];
            dst.readFully(hdr);
            if (!"RIFF".equals(new String(hdr, StandardCharsets.US_ASCII))) return;

            dst.seek(dst.length());
            for (byte[] chunk : chunks)
            {
                dst.write(chunk);
                if ((chunk.length & 1) == 1) dst.write(0);   // RIFF chunks are word-aligned
            }
            // RIFF size = file length minus the 8-byte RIFF header.
            long riffSize = dst.length() - 8;
            dst.seek(4);
            dst.write((int) ( riffSize        & 0xFF));
            dst.write((int) ((riffSize >> 8)  & 0xFF));
            dst.write((int) ((riffSize >> 16) & 0xFF));
            dst.write((int) ((riffSize >> 24) & 0xFF));
        }
    }

    /** Every LIST(INFO) and bext chunk of the source WAV, as raw bytes (id + size + data). */
    private static List<byte[]> collectWavMetadataChunks(String srcPath) throws IOException
    {
        List<byte[]> found = new ArrayList<>();
        try (RandomAccessFile src = new RandomAccessFile(srcPath, "r"))
        {
            if (src.length() < 12) return found;
            byte[] four = new byte[4];
            src.readFully(four);
            if (!"RIFF".equals(new String(four, StandardCharsets.US_ASCII))) return found;
            src.skipBytes(4);                       // RIFF size
            src.readFully(four);
            if (!"WAVE".equals(new String(four, StandardCharsets.US_ASCII))) return found;

            while (src.getFilePointer() + 8 <= src.length())
            {
                long chunkStart = src.getFilePointer();
                src.readFully(four);
                String id = new String(four, StandardCharsets.US_ASCII);
                long size = readLeUint32(src);
                if (size < 0 || chunkStart + 8 + size > src.length()) break;

                boolean isInfoList = false;
                if ("LIST".equals(id) && size >= 4)
                {
                    src.readFully(four);
                    isInfoList = "INFO".equals(new String(four, StandardCharsets.US_ASCII));
                    src.seek(chunkStart + 8);       // rewind to the chunk data start
                }
                if (isInfoList || "bext".equals(id))
                {
                    byte[] whole = new byte[(int) (8 + size)];
                    src.seek(chunkStart);
                    src.readFully(whole);
                    found.add(whole);
                    src.seek(chunkStart + 8);
                }
                long next = chunkStart + 8 + size + (size & 1);   // word alignment
                if (next <= chunkStart) break;
                src.seek(next);
            }
        }
        return found;
    }

    private static long readLeUint32(RandomAccessFile f) throws IOException
    {
        int b0 = f.read(), b1 = f.read(), b2 = f.read(), b3 = f.read();
        if ((b0 | b1 | b2 | b3) < 0) return -1;
        return (b0 & 0xFFL) | ((b1 & 0xFFL) << 8) | ((b2 & 0xFFL) << 16) | ((b3 & 0xFFL) << 24);
    }
}
