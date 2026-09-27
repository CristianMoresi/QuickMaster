package com.quickmaster.processing.analysis;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;

/** Content key for immutable whole-file analysis features. Never samples sparsely.
 * Computed on a worker, retaining only 64 hash characters, not another PCM copy. */
public record AnalysisInputKey(int sampleRate, int channels, int samples, String sha256) {
    public static AnalysisInputKey of(float[] input, int rate, int channels) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[65536];
            int used = 0;
            for (int i = 0; i < input.length; i++) {
                int bits = Float.floatToRawIntBits(input[i]);
                block[used++] = (byte) bits; block[used++] = (byte) (bits >>> 8);
                block[used++] = (byte) (bits >>> 16); block[used++] = (byte) (bits >>> 24);
                if (used == block.length) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    hash.update(block); used = 0;
                }
            }
            hash.update(block, 0, used);
            return new AnalysisInputKey(rate, channels, input.length, HexFormat.of().formatHex(hash.digest()));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
