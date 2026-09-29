import com.quickmaster.audio.Mp3File;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Application import vs independent float reference; never opens an audio device. */
public final class Mp3ApplicationDecodeAudit {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            Path source = Path.of(path); byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source));
            long start = System.nanoTime(); Mp3File file = new Mp3File(path); file.load();
            double seconds = (System.nanoTime() - start) * 1e-9;
            Path reference = source.resolveSibling(source.getFileName().toString().replaceFirst("\\.mp3$", ".ffmpeg.f32le"));
            FloatBuffer expected = ByteBuffer.wrap(Files.readAllBytes(reference)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] samples = file.getSamples();
            if (samples.length != expected.remaining()) throw new AssertionError("Frame mismatch: " + source);
            double peak = 0, error = 0, energy = 0;
            for (float sample : samples) {
                if (!Float.isFinite(sample)) throw new AssertionError("Non-finite PCM");
                double value = expected.get(), delta = sample - value;
                peak = Math.max(peak, Math.abs(delta)); error += delta * delta; energy += value * value;
            }
            double relativeDb = 10 * Math.log10(Math.max(error, 1e-300) / Math.max(energy, 1e-300));
            System.out.printf(Locale.ROOT, "APP_MP3_REFERENCE %s rate=%d channels=%d frames=%d decodeSeconds=%.4f maxError=%.9g relativeDb=%.3f%n",
                    source.getFileName(), file.getSampleRate(), file.getChannels(), samples.length / file.getChannels(), seconds, peak, relativeDb);
            if (peak > 4e-6 || relativeDb > -100) throw new AssertionError("MP3 reference mismatch: " + source);
            if (!Arrays.equals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))))
                throw new AssertionError("Source was modified");
        }
    }
}
