package com.quickmaster.audio;

import java.io.*;
import java.nio.*;
import java.util.ArrayList;

/** Bounded byte scratch for PCM decoding, with cancellation between blocks. */
final class PcmDecoder {
    private PcmDecoder() { }
    static float[] read(InputStream input, int bits, boolean floating, int channels, long expectedFrames)
            throws IOException, AudioFileException {
        if ((channels != 1 && channels != 2) || (bits != 16 && bits != 24 && bits != 32)
                || (floating && bits != 32) || expectedFrames < -1)
            throw new AudioFileException("Unsupported PCM format.");
        if (expectedFrames > (Integer.MAX_VALUE - 8L) / channels)
            throw new AudioFileException("Audio is too large for an in-memory track.");
        AtomicAudioWrite.checkCancelled();
        float[] result = expectedFrames >= 0 ? new float[(int) expectedFrames * channels] : null;
        ArrayList<float[]> chunks = result == null ? new ArrayList<>() : null;
        byte[] bytes = new byte[8192 * channels * (bits / 8)];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        while (true) {
            AtomicAudioWrite.checkCancelled();
            int read = input.readNBytes(bytes, 0, bytes.length);
            AtomicAudioWrite.checkCancelled();
            if (read == 0) break;
            if (read % (channels * (bits / 8)) != 0) throw new AudioFileException("Incomplete PCM frame.");
            int samples = read / (bits / 8);
            if ((long) count + samples > Integer.MAX_VALUE - 8L || (result != null && samples > result.length - count))
                throw new AudioFileException("PCM exceeds the declared length or maximum track size.");
            float[] destination = result != null ? result : new float[samples];
            int offset = result != null ? count : 0;
            buffer.position(0);
            for (int i = 0; i < samples; i++) {
                float value;
                if (floating) value = buffer.getFloat();
                else if (bits == 16) value = buffer.getShort() / 32768f;
                else if (bits == 32) value = (float) (buffer.getInt() / 2147483648.0);
                else {
                    int integer = (buffer.get() & 255) | (buffer.get() & 255) << 8 | buffer.get() << 16;
                    value = integer / 8388608f;
                }
                if (!Float.isFinite(value)) throw new AudioFileException("Non-finite PCM sample.");
                destination[offset + i] = value;
            }
            if (chunks != null) chunks.add(destination);
            count += samples;
        }
        if (result != null) {
            if (count != result.length) throw new AudioFileException("Truncated PCM: declared frame count was not decoded.");
            return result;
        }
        result = new float[count];
        int offset = 0;
        for (float[] chunk : chunks) {
            AtomicAudioWrite.checkCancelled();
            System.arraycopy(chunk, 0, result, offset, chunk.length); offset += chunk.length;
        }
        return result;
    }
}
