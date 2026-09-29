package com.quickmaster.audio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/** Closed export allowlist: creation time and software credit, never source tags.
 * Serialized directly into the new container, without rereading/reencoding audio.
 * The credit identifies the application, not the artist or copyright owner. */
record ExportMetadata(Instant generatedAt) {
    static final String SOFTWARE = "Made with QuickMaster by Cristian Moresi";
    private static final DateTimeFormatter UTC_SECONDS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    ExportMetadata { Objects.requireNonNull(generatedAt, "generatedAt"); }

    static ExportMetadata now() { return new ExportMetadata(Instant.now()); }

    /** RIFF LIST/INFO: NUL-terminated strings, little-endian sizes, word padding. */
    byte[] wavInfoChunk() {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.writeBytes(ascii("INFO"));
        info.writeBytes(riffChunk("ICRD", ascii(UTC_SECONDS.format(generatedAt) + "Z\0")));
        info.writeBytes(riffChunk("ISFT", ascii(SOFTWARE + "\0")));
        return riffChunk("LIST", info.toByteArray());
    }

    /** ID3v2.4: TDEN is encoding time (UTC by specification), not recording date.
     * TSSE is software, not performer. No ID3v1, padding, artwork or other fields.
     * See https://id3.org/id3v2.4.0-frames and /id3v2.4.0-structure. */
    byte[] mp3Tag() {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.writeBytes(textFrame("TDEN", UTC_SECONDS.format(generatedAt)));
        frames.writeBytes(textFrame("TSSE", SOFTWARE));
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.writeBytes(new byte[]{'I', 'D', '3', 4, 0, 0});
        tag.writeBytes(synchsafe(frames.size()));
        tag.writeBytes(frames.toByteArray());
        return tag.toByteArray();
    }

    private static byte[] textFrame(String id, String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(11 + utf8.length).put(ascii(id))
                .put(synchsafe(1 + utf8.length)).putShort((short) 0)
                .put((byte) 3).put(utf8).array();
    }

    private static byte[] riffChunk(String id, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length + (payload.length & 1)).order(ByteOrder.LITTLE_ENDIAN)
                .put(ascii(id)).putInt(payload.length).put(payload).array();
    }

    private static byte[] synchsafe(int value) {
        return new byte[]{(byte)(value >>> 21 & 127), (byte)(value >>> 14 & 127),
                (byte)(value >>> 7 & 127), (byte)(value & 127)};
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
}
