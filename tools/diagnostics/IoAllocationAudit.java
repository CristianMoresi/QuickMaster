import com.quickmaster.audio.WavFile;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.Locale;

/** Allocation measurement on generated PCM; writes only its own temporary fixture. */
public class IoAllocationAudit {
    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory(Path.of(args[0]), "io-allocation-");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        int frames = 48000 * 60;
        float[] pcm = new float[frames * 2];
        for (int f = 0; f < frames; f++) {
            pcm[f * 2] = (float) (.3 * Math.sin(2 * Math.PI * 997 * f / 48000));
            pcm[f * 2 + 1] = -pcm[f * 2];
        }
        Path output = work.resolve("synthetic.wav");
        for (int bits : new int[]{16, 24, 32}) {
            WavFile warmup = new WavFile("generated", 48000, 2, new float[2048], bits, bits == 32);
            warmup.save(output.toString());
            WavFile audio = new WavFile("generated", 48000, 2, pcm, bits, bits == 32);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long start = System.nanoTime();
            audio.save(output.toString());
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
            System.out.printf(Locale.ROOT, "WAV_SAVE bits=%d frames=%d allocatedBytes=%d elapsedMs=%.3f bytes=%d%n",
                    bits, frames, allocated, (System.nanoTime() - start) / 1e6, Files.size(output));
            WavFile decoded = new WavFile(output.toString());
            before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()); start = System.nanoTime();
            decoded.load();
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
            System.out.printf(Locale.ROOT, "WAV_LOAD bits=%d frames=%d allocatedBytes=%d elapsedMs=%.3f%n",
                    bits, decoded.getSamples().length / 2, allocated, (System.nanoTime() - start) / 1e6);
            if (decoded.getSamples().length != pcm.length) throw new AssertionError("Decode changed frame count");
        }
        Files.delete(output);
        Files.delete(work);
    }
}
