package com.quickmaster.processing;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelinePublicationTest {
    @Test void aRunningBlockKeepsItsCompleteChainAcrossReplacement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ProcessingPipeline pipeline = new ProcessingPipeline();
        pipeline.addProcessor(new AudioProcessor() {
            public void prepare(int rate, long n) { }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean on) { }
            public float[] process(float[] x, int ch) {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return x;
            }
        });
        PeakNormalizer gain = new PeakNormalizer(0); gain.setAnalyzedPeak(.5); pipeline.addProcessor(gain);
        List<AudioProcessor> snapshot = pipeline.getProcessors();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<float[]> output = executor.submit(() -> pipeline.execute(new float[]{.25f}, 1));
            assertTrue(entered.await(3, TimeUnit.SECONDS)); pipeline.clear(); release.countDown();
            assertArrayEquals(new float[]{.5f}, output.get(3, TimeUnit.SECONDS));
            assertEquals(2, snapshot.size()); assertTrue(pipeline.getProcessors().isEmpty());
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
