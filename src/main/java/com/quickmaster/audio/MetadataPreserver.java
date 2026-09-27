package com.quickmaster.audio;

import com.quickmaster.config.AppLogger;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Best-effort metadata for a new master; failures leave the output intact.
 * BWF descriptive provenance is retained, but the new master starts at time zero
 * and source loudness values are not misrepresented as output measurements. */
public final class MetadataPreserver {
    private MetadataPreserver() { }

    public static void preserve(String srcPath, String dstPath) {
        AtomicAudioWrite.checkCancelled();
        try {
            if (Files.isSameFile(Path.of(srcPath), Path.of(dstPath))) return;
            String src = srcPath.toLowerCase(java.util.Locale.ROOT);
            String dst = dstPath.toLowerCase(java.util.Locale.ROOT);
            if (src.endsWith(".mp3") && dst.endsWith(".mp3")) copyMp3Tags(srcPath, dstPath);
            else if (src.endsWith(".wav") && dst.endsWith(".wav")) copyWavMetadata(srcPath, dstPath);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            AppLogger.warn("Could not preserve metadata from " + srcPath + ": " + e.getMessage());
        }
    }

    private record Range(long offset, long length) { }

    private static void copyMp3Tags(String srcPath, String dstPath) throws IOException, AudioFileException {
        final Range v2, v1, oldV2, oldV1;
        final long audioStart, audioEnd;
        try (RandomAccessFile src = new RandomAccessFile(srcPath, "r");
             RandomAccessFile dst = new RandomAccessFile(dstPath, "r")) {
            v2 = id3v2(src); v1 = id3v1(src);
            if (v2 == null && v1 == null) return;
            oldV2 = id3v2(dst); oldV1 = id3v1(dst);
            audioStart = oldV2 == null ? 0 : oldV2.length;
            audioEnd = oldV1 == null ? dst.length() : oldV1.offset;
            if (audioEnd < audioStart) throw new IOException("Overlapping MP3 metadata blocks.");
        }
        AtomicAudioWrite.write(dstPath, staging -> {
            try (RandomAccessFile src = new RandomAccessFile(srcPath, "r");
                 RandomAccessFile dst = new RandomAccessFile(dstPath, "r");
                 RandomAccessFile out = new RandomAccessFile(staging.toFile(), "rw")) {
                byte[] block = new byte[65536];
                copy(v2 != null ? src : dst, v2 != null ? v2 : oldV2, out, block);
                copy(dst, new Range(audioStart, audioEnd - audioStart), out, block);
                copy(v1 != null ? src : dst, v1 != null ? v1 : oldV1, out, block);
            }
        });
    }

    private static Range id3v2(RandomAccessFile file) throws IOException {
        if (file.length() < 10) return null;
        byte[] h = new byte[10];
        file.seek(0); file.readFully(h);
        if (h[0] != 'I' || h[1] != 'D' || h[2] != '3' || h[3] < 2 || h[3] > 4) return null;
        for (int i = 6; i < 10; i++) if ((h[i] & 0x80) != 0) return null;
        int size = ((h[6] & 127) << 21) | ((h[7] & 127) << 14) | ((h[8] & 127) << 7) | (h[9] & 127);
        boolean footer = h[3] == 4 && (h[5] & 0x10) != 0;
        long total = 10L + size + (footer ? 10 : 0);
        if (total > file.length()) return null;
        if (footer) {
            byte[] f = new byte[10];
            file.seek(total - 10); file.readFully(f);
            if (f[0] != '3' || f[1] != 'D' || f[2] != 'I') return null;
            for (int i = 3; i < 10; i++) if (f[i] != h[i]) return null;
        }
        return new Range(0, total);
    }

    private static Range id3v1(RandomAccessFile file) throws IOException {
        if (file.length() < 128) return null;
        long offset = file.length() - 128;
        file.seek(offset);
        return file.read() == 'T' && file.read() == 'A' && file.read() == 'G' ? new Range(offset, 128) : null;
    }

    private static void copyWavMetadata(String srcPath, String dstPath) throws IOException, AudioFileException {
        final List<Range> chunks;
        try (RandomAccessFile src = new RandomAccessFile(srcPath, "r");
             RandomAccessFile dst = new RandomAccessFile(dstPath, "r")) {
            chunks = wavMetadata(src);
            if (chunks.isEmpty() || !isWave(dst)) return;
            long length = dst.length() + (dst.length() & 1);
            for (Range chunk : chunks) length += chunk.length + (chunk.length & 1);
            if (length - 8 > 0xffff_ffffL) throw new IOException("Metadata exceeds the RIFF size limit.");
        }
        AtomicAudioWrite.write(dstPath, staging -> {
            try (RandomAccessFile src = new RandomAccessFile(srcPath, "r");
                 RandomAccessFile dst = new RandomAccessFile(dstPath, "r");
                 RandomAccessFile out = new RandomAccessFile(staging.toFile(), "rw")) {
                byte[] block = new byte[65536];
                copy(dst, new Range(0, dst.length()), out, block);
                if ((out.length() & 1) != 0) out.write(0);
                for (Range chunk : chunks) {
                    long destinationOffset = out.getFilePointer();
                    copy(src, chunk, out, block);
                    if ((chunk.length & 1) != 0) out.write(0);
                    long next = out.getFilePointer();
                    out.seek(destinationOffset);
                    if (out.readInt() == 0x62657874 && chunk.length >= 610) {
                        // EBU Tech 3285: TimeReference is in sample-rate units,
                        // and 0x7fff denotes unavailable loudness parameters.
                        // A master may have been cropped/resampled/reprocessed.
                        out.seek(destinationOffset + 8 + 338); out.writeLong(0);
                        out.seek(destinationOffset + 8 + 346);
                        int version = Short.toUnsignedInt(Short.reverseBytes(out.readShort()));
                        if (version >= 2) {
                            out.seek(destinationOffset + 8 + 412);
                            for (int i = 0; i < 5; i++) { out.write(0xff); out.write(0x7f); }
                        }
                    }
                    out.seek(next);
                }
                out.seek(4);
                out.writeInt(Integer.reverseBytes((int) (out.length() - 8)));
            }
        });
    }

    private static boolean isWave(RandomAccessFile file) throws IOException {
        if (file.length() < 12) return false;
        file.seek(0);
        if (file.readInt() != 0x52494646) return false;
        file.skipBytes(4);
        return file.readInt() == 0x57415645;
    }

    private static List<Range> wavMetadata(RandomAccessFile src) throws IOException {
        List<Range> chunks = new ArrayList<>();
        if (!isWave(src)) return chunks;
        src.seek(4);
        long limit = 8 + Integer.toUnsignedLong(Integer.reverseBytes(src.readInt()));
        if (limit > src.length()) throw new IOException("Truncated source RIFF.");
        for (long start = 12; start + 8 <= limit;) {
            AtomicAudioWrite.checkCancelled();
            src.seek(start);
            byte[] idBytes = new byte[4]; src.readFully(idBytes);
            String id = new String(idBytes, StandardCharsets.US_ASCII);
            long size = Integer.toUnsignedLong(Integer.reverseBytes(src.readInt()));
            if (start + 8 + size > limit) throw new IOException("Truncated source RIFF chunk.");
            boolean info = "LIST".equals(id) && size >= 4 && src.readInt() == 0x494e464f;
            if (info || "bext".equals(id)) {
                if (chunks.size() >= 4096) throw new IOException("Too many metadata chunks.");
                chunks.add(new Range(start, 8 + size));
            }
            start += 8 + size + (size & 1);
        }
        return chunks;
    }

    private static void copy(RandomAccessFile from, Range range, RandomAccessFile to, byte[] block) throws IOException {
        if (range == null) return;
        from.seek(range.offset);
        long left = range.length;
        while (left > 0) {
            AtomicAudioWrite.checkCancelled();
            int count = (int) Math.min(left, block.length);
            from.readFully(block, 0, count);
            to.write(block, 0, count);
            left -= count;
        }
    }
}
