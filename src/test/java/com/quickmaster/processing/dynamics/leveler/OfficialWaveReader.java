package com.quickmaster.processing.dynamics.leveler;

import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.sun.jna.Memory;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.quickmaster.processing.dynamics.leveler.model.RequiredOfficialReading;

/** Test-only original RIFF reader. No repair, resampling, normalization or implicit padding. */
public final class OfficialWaveReader implements AutoCloseable
{
    private final Path path;
    private final RequiredOfficialReading required;
    private final InputStream input;
    private final MessageDigest actualDigest;
    private final byte[] bytes;
    private long framesRead;
    private long bytesRead;
    private boolean eofReached;

    public OfficialWaveReader(Path path, RequiredOfficialReading required) throws IOException, NoSuchAlgorithmException
    {
        validateRegular(path);
        this.path = path;
        this.required = required;
        if (Files.size(path) != required.fileBytes() || !sha256(path).equals(required.signalSha256()))
            throw new IllegalArgumentException("Original file size/hash mismatch: " + path.getFileName());
        long dataOffset = inspect(path, required);
        this.actualDigest = MessageDigest.getInstance("SHA-256");
        this.bytes = new byte[65536 * 18];
        this.input = Files.newInputStream(path);
        try
        {
            consume(dataOffset);
        }
        catch (IOException | RuntimeException ex)
        {
            input.close();
            throw ex;
        }
    }

    public int readPcm(float[] output) throws IOException
    {
        if (output == null || output.length < required.channels())
            throw new IllegalArgumentException("PCM output needs a complete frame.");
        if (framesRead == required.sourceFrames()) return -1;
        int count = (int) Math.min(Math.min(65536, output.length / required.channels()),
                required.sourceFrames() - framesRead);
        int byteCount = Math.multiplyExact(count, required.blockAlign());
        readExact(byteCount);
        int sampleBytes = required.bitsPerSample() / 8;
        for (int i = 0; i < count * required.channels(); i++)
        {
            int offset = i * sampleBytes;
            int sample = (bytes[offset] & 255) | (bytes[offset + 1] << 8);
            if (sampleBytes == 3)
                sample = (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8) | (bytes[offset + 2] << 16);
            output[i] = sample / (sampleBytes == 2 ? 32768.0f : 8388608.0f);
        }
        framesRead += count;
        return count;
    }

    public void finish() throws IOException, NoSuchAlgorithmException
    {
        if (eofReached) return;
        if (framesRead != required.sourceFrames()) throw new IllegalArgumentException("PCM was not fully consumed.");
        consume(required.fileBytes() - bytesRead);
        if (input.read() != -1) throw new IllegalArgumentException("Physical file grew during reading.");
        if (bytesRead != required.fileBytes() || !HexFormat.of().formatHex(actualDigest.digest()).equals(required.signalSha256())
                || Files.size(path) != required.fileBytes() || !sha256(path).equals(required.signalSha256()))
            throw new IllegalArgumentException("Original changed before, during or after decoding.");
        validateRegular(path);
        eofReached = true;
    }

    public long framesRead() { return framesRead; }
    public long bytesRead() { return bytesRead; }
    public boolean eofReached() { return eofReached; }

    @Override
    public void close() throws IOException { input.close(); }

    public static void validateRegular(Path path) throws IOException
    {
        Path absolute = path.toAbsolutePath().normalize();
        if (!path.toAbsolutePath().equals(absolute)) throw new IllegalArgumentException("Traversal path is forbidden.");
        if (!absolute.equals(absolute.toRealPath())) throw new IllegalArgumentException("Alias/reparse path is forbidden.");
        for (Path current = absolute; current != null; current = current.getParent())
        {
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalArgumentException("Link/reparse path is forbidden.");
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Regular file required.");
        if (Platform.isWindows())
        {
            // Read-only file attributes; no native mapping or new helper class enters the runner cone.
            HANDLE handle = Kernel32.INSTANCE.CreateFile(absolute.toString(), 0x80, 7, null, 3, 0x00200000, null);
            if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IllegalArgumentException("Cannot inspect original file identity.");
            try (Memory info = new Memory(24))
            {
                if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, 1, info, new DWORD(24)) || info.getInt(16) != 1)
                    throw new IllegalArgumentException("Ambiguous hardlinked original is forbidden.");
                info.clear();
                if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, 9, info, new DWORD(8)) || (info.getInt(0) & 0x400) != 0)
                    throw new IllegalArgumentException("Reparse-point original is forbidden.");
            }
            finally { Kernel32.INSTANCE.CloseHandle(handle); }
        }
        else
        {
            if (((Number) Files.getAttribute(absolute, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1L)
                throw new IllegalArgumentException("Ambiguous hardlinked original is forbidden.");
        }
    }

    public static String sha256(Path path) throws IOException, NoSuchAlgorithmException
    {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(path))
        {
            byte[] part = new byte[32768];
            int count;
            while ((count = stream.read(part)) != -1)
            {
                if (count == 0) throw new IllegalArgumentException("Stalled file read.");
                hash.update(part, 0, count);
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private void consume(long count) throws IOException
    {
        if (count < 0L) throw new IllegalArgumentException("Negative file remainder.");
        while (count != 0L)
        {
            int part = (int) Math.min(count, bytes.length);
            readExact(part);
            count -= part;
        }
    }

    private void readExact(int length) throws IOException
    {
        int offset = 0;
        while (offset != length)
        {
            int count = input.read(bytes, offset, length - offset);
            if (count <= 0) throw new IllegalArgumentException("Truncated original file.");
            actualDigest.update(bytes, offset, count);
            bytesRead = Math.addExact(bytesRead, count);
            offset += count;
        }
    }

    private static long inspect(Path path, RequiredOfficialReading expected) throws IOException
    {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r"))
        {
            long physical = file.length();
            if (physical < 12L || four(file) != 0x46464952L) throw new IllegalArgumentException("RIFF required.");
            long declaredEnd = Math.addExact(u32(file), 8L);
            if (four(file) != 0x45564157L) throw new IllegalArgumentException("WAVE required.");
            if (physical != expected.fileBytes() || declaredEnd != expected.riffDeclaredEnd()
                    || (declaredEnd != physical && !shortRiff(expected)))
                throw new IllegalArgumentException("Unapproved physical RIFF extent.");
            long position = 12L;
            long dataOffset = -1L;
            boolean formatSeen = false;
            boolean dataSeen = false;
            while (position < physical)
            {
                if (physical - position < 8L) throw new IllegalArgumentException("Truncated RIFF chunk header.");
                file.seek(position);
                long id = four(file);
                long length = u32(file);
                long begin = Math.addExact(position, 8L);
                long end = Math.addExact(begin, length);
                long paddedEnd = Math.addExact(end, length & 1L);
                if (end > physical || paddedEnd > physical) throw new IllegalArgumentException("RIFF chunk exceeds physical EOF.");
                if (id == 0x20746d66L)
                {
                    if (formatSeen || dataSeen || length < 16L || length > 65536L)
                        throw new IllegalArgumentException("Duplicate, late or malformed fmt chunk.");
                    formatSeen = true;
                    int tag = u16(file);
                    int channels = u16(file);
                    long rate = u32(file);
                    long byteRate = u32(file);
                    int align = u16(file);
                    int bits = u16(file);
                    int mask = 0;
                    if (tag == 65534)
                    {
                        if (length < 40L) throw new IllegalArgumentException("Truncated extensible format.");
                        int cbSize = u16(file);
                        int validBits = u16(file);
                        mask = Math.toIntExact(u32(file));
                        byte[] guid = new byte[16];
                        file.readFully(guid);
                        if (cbSize < 22 || length != 18L + cbSize || validBits != bits
                                || !HexFormat.of().formatHex(guid).equals("0100000000001000800000aa00389b71"))
                            throw new IllegalArgumentException("Only exact full-resolution PCM WAVEEX is supported.");
                    }
                    else if (tag != 1 || !(length == 16L || (length == 18L && u16(file) == 0)))
                        throw new IllegalArgumentException("Only signed little-endian PCM is supported.");
                    if ((bits != 16 && bits != 24) || channels != expected.channels() || rate != expected.sampleRateHz()
                            || align != channels * (bits / 8) || align != expected.blockAlign()
                            || byteRate != Math.multiplyExact(rate, align) || tag != expected.waveFormatTag()
                            || bits != expected.bitsPerSample() || mask != expected.channelMask()
                            || channels != expected.channelLayout().channels())
                        throw new IllegalArgumentException("Container format/layout does not match its pinned original.");
                }
                else if (id == 0x61746164L)
                {
                    if (!formatSeen || dataSeen || length != expected.dataBytes() || length % expected.blockAlign() != 0
                            || length / expected.blockAlign() != expected.sourceFrames())
                        throw new IllegalArgumentException("Invalid, duplicate or incomplete PCM data chunk.");
                    dataSeen = true;
                    dataOffset = begin;
                }
                if (paddedEnd > declaredEnd && !(shortRiff(expected) && id == 0x61746164L && paddedEnd == physical))
                    throw new IllegalArgumentException("Chunk crosses declared RIFF boundary.");
                position = paddedEnd;
            }
            if (!formatSeen || !dataSeen || position != physical) throw new IllegalArgumentException("Missing complete RIFF content.");
            return dataOffset;
        }
    }

    private static boolean shortRiff(RequiredOfficialReading expected)
    {
        return (expected.signalSha256().equals("eaa3eff1f4aec58dbcd0d0ece25efb8576bed6c11c293bce237566cc78970ed0")
                    && expected.fileBytes() == 15966176L && expected.riffDeclaredEnd() == 15966168L
                    && expected.dataBytes() == 15966108L && expected.sourceFrames() == 3991527L)
                || (expected.signalSha256().equals("fd7b534e2097601b2393b7e0b84392ee640d869e3d54bd5554a999c3e2da881d")
                    && expected.fileBytes() == 7997766L && expected.riffDeclaredEnd() == 7997758L
                    && expected.dataBytes() == 7997698L && expected.sourceFrames() == 3998849L)
                || (expected.signalSha256().equals("c7a5b24cfedfd9c781beafd05547e9dd90118ab6a78c4ccf181a02ae85160fc0")
                    && expected.fileBytes() == 11520080L && expected.riffDeclaredEnd() == 11520068L
                    && expected.dataBytes() == 11520000L && expected.sourceFrames() == 960000L);
    }

    private static int u16(RandomAccessFile file) throws IOException
    {
        return file.readUnsignedByte() | (file.readUnsignedByte() << 8);
    }

    private static long u32(RandomAccessFile file) throws IOException
    {
        return (long) file.readUnsignedByte() | ((long) file.readUnsignedByte() << 8)
                | ((long) file.readUnsignedByte() << 16) | ((long) file.readUnsignedByte() << 24);
    }

    private static long four(RandomAccessFile file) throws IOException { return u32(file); }
}
