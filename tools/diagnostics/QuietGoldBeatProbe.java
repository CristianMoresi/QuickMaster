import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.processing.dynamics.BeatCompProcessor;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Read-only real-song tempo and applied-gain check for the packaged Beat Comp. */
public final class QuietGoldBeatProbe {
    private static final String SOURCE_SHA = "cd6667b19b4c641b13b17ac3220b7ddbaeb4e9b390e907d59148a10fb944e1d0";

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] chunk = new byte[65536];
            int read;
            while ((read = input.read(chunk)) != -1) digest.update(chunk, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Source WAV and block frames required.");
        Path path = Path.of(args[0]);
        int blockSize = Integer.parseInt(args[1]);
        if (blockSize < 1 || blockSize > 65536) throw new IllegalArgumentException("Invalid block size.");
        if (!SOURCE_SHA.equals(hash(path))) throw new AssertionError("SOURCE_HASH_MISMATCH");
        WavFile wave = new WavFile(path.toString());
        wave.load();
        float[] input = wave.getSamples();
        int channels = wave.getChannels(), rate = wave.getSampleRate(), frames = input.length / channels;
        TrackAnalysis analysis = new TrackAnalysis();
        analysis.analyze(input, channels, rate);
        BeatCompProcessor beat = new BeatCompProcessor();
        beat.setTrackAnalysis(analysis);
        beat.setTargetDb(-1.0);
        beat.setEnabled(true);
        beat.prepare(rate, frames);
        beat.analyze(input, channels);
        beat.prepare(rate, frames);
        System.out.printf(Locale.ROOT, "TEMPO bpm=%.5f confidence=%.5f reliable=%s onsets=%d releaseMs=%.3f%n",
                analysis.getBpm(), analysis.getConfidence(), analysis.isTempoReliable(),
                analysis.getOnsetCount(), beat.getReleaseMs());
        double minimumRatio = 1.0, meterMinimumDb = 0.0, stereoLinkError = 0.0;
        long measured = 0, overLimit = 0;
        double hardFloor = Math.pow(10.0, -1.0 / 20.0);
        MessageDigest outputHash = MessageDigest.getInstance("SHA-256");
        for (int first = 0; first < frames; first += blockSize) {
            int blockFrames = Math.min(blockSize, frames - first);
            float[] block = new float[blockFrames * channels];
            System.arraycopy(input, first * channels, block, 0, block.length);
            beat.process(block, channels);
            meterMinimumDb = Math.min(meterMinimumDb, beat.getGainReductionDb());
            for (int i = 0; i < block.length; i++) {
                float x = input[first * channels + i], y = block[i];
                if (!Float.isFinite(y)) throw new AssertionError("NONFINITE_OUTPUT");
                int bits = Float.floatToRawIntBits(y);
                outputHash.update((byte) (bits >>> 24));
                outputHash.update((byte) (bits >>> 16));
                outputHash.update((byte) (bits >>> 8));
                outputHash.update((byte) bits);
                if (Math.abs(x) < 1e-4f) continue;
                double ratio = y / (double) x;
                minimumRatio = Math.min(minimumRatio, ratio);
                if (ratio < hardFloor - 1e-6) overLimit++;
                measured++;
            }
            if (channels == 2) for (int frame = 0; frame < blockFrames; frame++) {
                float left = input[(first + frame) * 2], right = input[(first + frame) * 2 + 1];
                if (Math.abs(left) >= 1e-3f && Math.abs(right) >= 1e-3f) {
                    double gainLeft = block[frame * 2] / (double) left;
                    double gainRight = block[frame * 2 + 1] / (double) right;
                    stereoLinkError = Math.max(stereoLinkError, Math.abs(gainLeft - gainRight));
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "BEAT blockFrames=%d targetDb=-1.0 analyzed=%s minAppliedGain=%.9f minAppliedDb=%.9f meterMinimumDb=%.9f overLimit=%d/%d linkError=%.10f outputSha=%s%n",
                blockSize,
                beat.isAnalyzed(), minimumRatio, 20.0 * Math.log10(minimumRatio), meterMinimumDb,
                overLimit, measured, stereoLinkError, HexFormat.of().formatHex(outputHash.digest()));
        if (overLimit != 0 || meterMinimumDb < -1.00001) throw new AssertionError("TARGET_EXCEEDED");
        if (!SOURCE_SHA.equals(hash(path))) throw new AssertionError("SOURCE_CHANGED");
        System.out.println("SOURCE_UNCHANGED=true");
    }
}
