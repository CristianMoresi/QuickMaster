import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.*;
import java.util.Arrays;

/** Analytic delay + position-dependent envelope, no hardware or private audio. */
public class PipelineAlignmentAudit {
    static class Delay implements AudioProcessor {
        float[] ring = new float[137]; int index;
        public void prepare(int rate, long total) { Arrays.fill(ring, 0); index = 0; }
        public int getLatencyFrames() { return ring.length; }
        public boolean isEnabled() { return true; }
        public void setEnabled(boolean value) { }
        public float[] process(float[] block, int channels) {
            for (int i = 0; i < block.length; i++) { float previous = ring[index]; ring[index] = block[i]; block[i] = previous; index = (index + 1) % ring.length; }
            return block;
        }
    }
    public static void main(String[] args) {
        float[] input = new float[4096]; Arrays.fill(input, .5f);
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new Delay());
        pipeline.addProcessor(new FadeProcessor(.02, .02));
        WavFile file = new WavFile("synthetic", 48000, 1, input, 32, true);
        pipeline.process(file);
        pipeline.prepare(48000, input.length);
        float[] live = new float[input.length + 137];
        int written = 0;
        while (written < live.length) {
            int count = Math.min(256, live.length - written);
            float[] block = new float[count];
            if (written < input.length) System.arraycopy(input, written, block, 0, Math.min(count, input.length - written));
            pipeline.setPlaybackPosition(written);
            float[] output = pipeline.execute(block, 1);
            System.arraycopy(output, 0, live, written, count); written += count;
        }
        double error = 0;
        for (int i = 0; i < input.length; i++) error = Math.max(error, Math.abs(live[i + 137] - file.getSamples()[i]));
        System.out.println("PIPELINE_ALIGNMENT maxSampleError=" + error);
        if (error > 1e-6) System.exit(1);
    }
}
