package com.quickmaster.processing;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OfflineRenderBufferTest {
    /** Independent causal delay, including a processor returning a fresh block. */
    private static final class Delay implements AudioProcessor {
        final int latency;
        final boolean fresh;
        final Set<float[]> inputs = Collections.newSetFromMap(new IdentityHashMap<>());
        float[] history;
        int position;
        Delay(int latency, boolean fresh) { this.latency = latency; this.fresh = fresh; }
        public void prepare(int rate, long samples) { history = null; position = 0; }
        public int getLatencyFrames() { return latency; }
        public boolean isEnabled() { return true; }
        public void setEnabled(boolean enabled) { }
        public float[] process(float[] block, int channels) {
            inputs.add(block);
            float[] out = fresh ? new float[block.length] : block;
            if (history == null) history = new float[latency * channels];
            for (int i = 0; i < block.length; i++) {
                float sample = block[i];
                if (history.length == 0) out[i] = sample * .5f;
                else {
                    out[i] = history[position] * .5f;
                    history[position] = sample;
                    position = (position + 1) % history.length;
                }
            }
            return out;
        }
    }

    @Test void preservesEveryFrameAcrossShortLongAndNonAlignedLatencies() {
        Random random = new Random(8171);
        for (int channels : new int[]{1, 2})
            for (int frames : new int[]{0, 1, 7, 1023, 1024, 2051, 8197})
                for (int latency : new int[]{0, 1, 31, 1024, 3097})
                    for (boolean fresh : new boolean[]{false, true}) {
                        float[] input = new float[frames * channels];
                        for (int i = 0; i < input.length; i++) input[i] = random.nextFloat() - .5f;
                        float[] original = input.clone();
                        Delay delay = new Delay(latency, fresh);
                        ProcessingPipeline pipeline = new ProcessingPipeline();
                        pipeline.addProcessor(delay);
                        pipeline.prepare(48000, input.length);
                        float[] output = pipeline.analyzeAndRender(input, channels, 0, null, null, null);
                        assertEquals(input.length, output.length);
                        for (int i = 0; i < input.length; i++) assertEquals(input[i] * .5f, output[i]);
                        assertArrayEquals(original, input);
                        assertTrue(delay.inputs.size() <= 2, "Only full and final partial blocks are needed");
                    }
    }
}
