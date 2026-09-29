// DSPark - Copyright (c) 2026 Cristian Moresi - MIT License
package com.dspark.io;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Checked offline MP3 container. Metadata parsing follows DSPark C++ Mp3File.
 * Immutable encoded input; each decoder owns its synthesis state. No audio device I/O. */
public final class Mp3Stream {
    public static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private static final int[] BR1 = {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320};
    private static final int[] BR2 = {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};
    private static final int[] SAMPLE_RATES = {44100,48000,32000};
    final byte[] data;
    final int[] offsets;
    private final Header first;
    private final int bitrate, trimStart, trimEnd;
    private final boolean vbr;

    record Header(int version, int rate, int channels, int bitrate, int size,
                  int sideSize, int headerSize, int mode, int modeExt) {
        int frames() { return version == 1 ? 1152 : 576; }
    }

    /** Decoder output is interleaved, finite float PCM, without a full-scale clamp. */
    public record Audio(int sampleRate, int channels, float[] samples, int bitrateKbps,
                        boolean variableBitrate) {}

    public static Mp3Stream read(Path path) throws IOException {
        checkCancelled();
        long size = Files.size(path);
        if (size < 4 || size > MAX_FILE_BYTES) throw bad("MP3 file size is outside the supported range");
        byte[] bytes = new byte[(int) size];
        try (InputStream in = Files.newInputStream(path)) {
            int at = 0;
            while (at < bytes.length) {
                checkCancelled();
                int n = in.read(bytes, at, Math.min(65536, bytes.length - at));
                if (n < 0) throw bad("MP3 file changed or was truncated while reading");
                at += n;
            }
            if (in.read() != -1) throw bad("MP3 file changed while reading");
        }
        return new Mp3Stream(bytes);
    }

    public static Mp3Stream fromBytes(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes);
        checkCancelled();
        if (bytes.length < 4 || bytes.length > MAX_FILE_BYTES) throw bad("Invalid MP3 size");
        return new Mp3Stream(bytes.clone());
    }

    private Mp3Stream(byte[] bytes) throws IOException {
        data = bytes;
        int pos = 0;
        while (matches(pos, "ID3")) {
            if (data.length - pos < 10) throw bad("Truncated ID3 header");
            int version = u(pos + 3);
            if (version < 2 || version > 4) throw bad("Unsupported ID3 version");
            long size = 0;
            for (int i = 6; i < 10; i++) {
                int b = u(pos + i);
                if ((b & 128) != 0) throw bad("Invalid ID3 synchsafe size");
                size = (size << 7) | b;
            }
            long end = pos + 10L + size + (version == 4 && (u(pos + 5) & 16) != 0 ? 10 : 0);
            if (end > data.length) throw bad("Truncated ID3 tag");
            pos = (int) end;
        }
        int audioEnd = audioEnd();
        if (pos >= audioEnd) throw bad("MP3 contains metadata but no audio");
        first = header(pos);
        int[] frames = new int[Math.min(4096, data.length / 24 + 1)];
        int count = 0;
        long audioBytes = 0;
        while (pos < audioEnd) {
            checkCancelled();
            if (data[pos] == 0) {
                for (int i = pos; i < audioEnd; i++) {
                    if ((i & 65535) == 0) checkCancelled();
                    if (data[i] != 0) throw bad("Unexpected bytes after MP3 audio");
                }
                break;
            }
            Header h = header(pos);
            if (h.version != first.version || h.rate != first.rate || h.channels != first.channels)
                throw bad("MP3 format changes inside the stream");
            if (h.size > audioEnd - pos) throw bad("Truncated MP3 frame");
            if (count == frames.length) frames = Arrays.copyOf(frames, Math.multiplyExact(count, 2));
            frames[count++] = pos;
            audioBytes += h.size;
            pos += h.size;
        }
        if (count == 0) throw bad("No MP3 frames");
        Tag tag = tag(frames[0], first);
        if (tag.present) {
            audioBytes -= first.size;
            frames = Arrays.copyOfRange(frames, 1, count);
            count--;
        } else frames = Arrays.copyOf(frames, count);
        if (count == 0) throw bad("MP3 contains metadata but no audio");
        offsets = frames;
        boolean variable = false;
        int audioBitrate = header(frames[0]).bitrate;
        for (int offset : frames) variable |= header(offset).bitrate != audioBitrate;
        if (tag.frames > count) throw bad("MP3 ends before its declared audio frame count");
        long rawFrames = (long) count * first.frames();
        long maxSamples = Math.min(1L << 28, Runtime.getRuntime().maxMemory() / 16);
        if (rawFrames * first.channels > maxSamples)
            throw bad("MP3 decoded audio exceeds the in-memory track limit");
        int start = 0, end = (int) rawFrames;
        if (tag.gapless) {
            int candidateStart = tag.delay + 529;
            long candidateEnd = Math.min(rawFrames,
                    (tag.frames > 0 ? tag.frames : count) * (long) first.frames() - tag.padding + 529);
            if (candidateStart < candidateEnd) { start = candidateStart; end = (int) candidateEnd; }
        }
        trimStart = start;
        trimEnd = end;
        vbr = variable || tag.vbr;
        bitrate = (int) Math.round(audioBytes * 8.0 * first.rate / (rawFrames * 1000.0));
    }

    public int version() { return first.version; }
    public int sampleRate() { return first.rate; }
    public int channels() { return first.channels; }
    public int rawFrames() { return offsets.length * first.frames(); }
    public int outputFrames() { return trimEnd - trimStart; }
    public int bitrateKbps() { return bitrate; }
    public boolean variableBitrate() { return vbr; }

    private int audioEnd() throws IOException {
        int end = data.length;
        if (end >= 128 && matches(end - 128, "TAG")) end -= 128;
        if (end >= 32 && matches(end - 32, "APETAGEX")) {
            int footer = end - 32;
            long version = little32(footer + 8), size = little32(footer + 12), flags = little32(footer + 20);
            if ((version != 1000 && version != 2000) || size < 32 || size > end || (flags & 0x20000000L) != 0)
                throw bad("Invalid APE tag footer");
            end -= (int) size;
            if ((flags & 0x80000000L) != 0) {
                int header = end - 32;
                if (header < 0 || !matches(header, "APETAGEX") || little32(header + 8) != version
                        || little32(header + 12) != size || (little32(header + 20) & 0x20000000L) == 0)
                    throw bad("Invalid APE tag header");
                end = header;
            }
        }
        return end;
    }

    /** The audio frames only, without a Xing/Info/VBRI pseudo-frame or trailing tags. */
    public InputStream openAudioStream() throws IOException {
        int last = offsets[offsets.length - 1];
        return new ByteArrayInputStream(data, offsets[0], last + header(last).size - offsets[0]);
    }

    /** Checks frame count and applies validated encoder delay/padding exactly once. */
    public Audio finish(float[] raw) throws IOException {
        checkCancelled();
        if (raw.length != rawFrames() * channels()) throw bad("Decoder returned an incomplete MP3 stream");
        for (int i = 0; i < raw.length; i++) {
            if ((i & 65535) == 0) checkCancelled();
            if (!Float.isFinite(raw[i])) throw bad("Non-finite decoded MP3 sample");
        }
        float[] samples = trimStart == 0 && trimEnd == rawFrames() ? raw
                : Arrays.copyOfRange(raw, trimStart * channels(), trimEnd * channels());
        return new Audio(sampleRate(), channels(), samples, bitrate, vbr);
    }

    Header header(int p) throws IOException {
        if (p < 0 || p > data.length - 4) throw bad("Missing MP3 header");
        int h = (int) read32(p);
        int ver = (h >>> 19) & 3, layer = (h >>> 17) & 3;
        int br = (h >>> 12) & 15, sr = (h >>> 10) & 3;
        if ((h & 0xffe00000) != 0xffe00000 || ver == 1 || layer != 1 || br == 0 || br == 15 || sr == 3)
            throw bad("Invalid or unsupported MPEG Layer III header at byte " + p);
        int version = ver == 3 ? 1 : ver == 2 ? 2 : 25;
        int rate = SAMPLE_RATES[sr] / (version == 1 ? 1 : version == 2 ? 2 : 4);
        int kbps = (version == 1 ? BR1 : BR2)[br];
        int mode = (h >>> 6) & 3, ch = mode == 3 ? 1 : 2;
        int side = version == 1 ? (ch == 1 ? 17 : 32) : (ch == 1 ? 9 : 17);
        int hs = ((h >>> 16) & 1) == 0 ? 6 : 4;
        int size = (version == 1 ? 144000 : 72000) * kbps / rate + ((h >>> 9) & 1);
        if (size < hs + side) throw bad("Invalid MP3 frame length");
        return new Header(version, rate, ch, kbps, size, side, hs, mode, (h >>> 4) & 3);
    }

    private record Tag(boolean present, boolean vbr, long frames, boolean gapless, int delay, int padding) {}
    private Tag tag(int p, Header h) {
        int end = p + h.size, x = p + h.headerSize + h.sideSize;
        if (p + h.headerSize + 36 <= end && matches(p + h.headerSize + 32, "VBRI"))
            return new Tag(true, true, 0, false, 0, 0);
        boolean xing = matches(x, "Xing");
        if (x + 8 > end || (!xing && !matches(x, "Info"))) return new Tag(false,false,0,false,0,0);
        long flags = read32(x + 4), frames = 0;
        x += 8;
        if ((flags & 1) != 0) { if (x+4 > end) return new Tag(true,xing,0,false,0,0); frames=read32(x); x+=4; }
        if ((flags & 2) != 0) x+=4;
        if ((flags & 4) != 0) x+=100;
        if ((flags & 8) != 0) x+=4;
        if (x + 36 <= end) {
            int stored = (u(x+34)<<8) | u(x+35);
            // FFmpeg's fixed-190-byte convention includes the still-zero CRC
            // slot in mono tags (and zero padding beyond a short tag frame).
            // Never read outside the frame or compare a CRC against itself.
            if (crc16(p, x+34-p) == stored || crc16(p,190,x+34,end) == stored)
                return new Tag(true,xing,frames,true,(u(x+21)<<4)|(u(x+22)>>>4),((u(x+22)&15)<<8)|u(x+23));
        }
        return new Tag(true,xing,frames,false,0,0);
    }

    private int crc16(int offset, int length) {
        return crc16(offset,length,-1,data.length);
    }
    private int crc16(int offset, int length, int zeroField, int end) {
        int crc=0;
        for(int i=offset;i<offset+length;i++) {
            crc ^= i>=end || (zeroField>=0 && (i==zeroField || i==zeroField+1)) ? 0 : u(i);
            for(int bit=0;bit<8;bit++) crc=(crc&1)!=0 ? (crc>>>1)^0xa001 : crc>>>1;
        }
        return crc;
    }
    private int u(int p) { return data[p] & 255; }
    private long little32(int p) { return (long)u(p) | (long)u(p+1)<<8 | (long)u(p+2)<<16 | (long)u(p+3)<<24; }
    private long read32(int p) { return (long)u(p)<<24 | (long)u(p+1)<<16 | (long)u(p+2)<<8 | u(p+3); }
    private boolean matches(int p, String s) {
        if (p < 0 || p > data.length - s.length()) return false;
        for(int i=0;i<s.length();i++) if(u(p+i)!=s.charAt(i)) return false;
        return true;
    }
    static IOException bad(String message) { return new IOException(message); }
    static void checkCancelled() {
        if(Thread.currentThread().isInterrupted()) throw new CancellationException("MP3 decoding cancelled");
    }
}
