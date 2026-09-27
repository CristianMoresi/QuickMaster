package com.quickmaster.processing;

import com.quickmaster.audio.WavFile;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PipelinePositionAuditTest {
    private static final class Delay implements AudioProcessor {
        private final int latency;
        private float[] ring; private int index;
        Delay(int latency) { this.latency = latency; }
        public void prepare(int rate, long total) { ring = new float[latency]; index = 0; }
        public int getLatencyFrames() { return latency; }
        public boolean isEnabled() { return true; }
        public void setEnabled(boolean on) { }
        public float[] process(float[] input, int ch) {
            for (int i = 0; i < input.length; i++) { float old = ring[index]; ring[index] = input[i]; input[i] = old; index = (index + 1) % latency; }
            return input;
        }
    }
    @Test void cumulativeUpstreamLatencyKeepsLiveEnvelopeAlignedWithOffline() {
        for (int blockSize : new int[]{1, 31, 256, 1024}) {
            float[] source = new float[4096]; Arrays.fill(source, .5f);
            ProcessingPipeline pipeline = new ProcessingPipeline();
            pipeline.addProcessor(new Delay(73)); pipeline.addProcessor(new Delay(64));
            pipeline.addProcessor(new FadeProcessor(.02, .02));
            WavFile audio = new WavFile("generated", 48000, 1, source, 32, true); pipeline.process(audio);
            pipeline.prepare(48000, source.length);
            float[] live = new float[source.length + 137];
            for (int position = 0; position < live.length; position += blockSize) {
                int count = Math.min(blockSize, live.length - position); float[] block = new float[count];
                if (position < source.length) System.arraycopy(source, position, block, 0, Math.min(count, source.length - position));
                pipeline.setPlaybackPosition(position); pipeline.execute(block, 1); System.arraycopy(block, 0, live, position, count);
            }
            assertArrayEquals(audio.getSamples(), Arrays.copyOfRange(live, 137, live.length), 1e-7f);
        }
    }
}
