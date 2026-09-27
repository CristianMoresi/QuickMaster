package com.quickmaster.processing;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingPipelineBypassTest {
    private static final class Probe implements AudioProcessor {
        boolean enabled, metadata;
        int analyses, renders, prepares;
        float[] observed;
        Probe(boolean enabled, boolean metadata) { this.enabled = enabled; this.metadata = metadata; }
        public void prepare(int rate, long size) { prepares++; }
        public boolean usesAnalysis() { return true; }
        public boolean analyzeWhenBypassed() { return metadata; }
        public void analyze(float[] input, int channels) { analyses++; observed = input.clone(); }
        public float[] process(float[] input, int channels) {
            renders++;
            if (enabled) for (int i = 0; i < input.length; i++) input[i] *= .5f;
            return input;
        }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
    }
    @Test void bypassDoesNotAnalyzeRenderOrCopyButStillPublishesStageTap() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        Probe bypass = new Probe(false, false); pipeline.addProcessor(bypass);
        float[] source = {.8f, -.4f};
        var taps = new ArrayList<float[]>();
        pipeline.prepare(48000, source.length);
        float[] output = pipeline.analyzeAndRender(source, 1, 0, null, null, (pcm, stage) -> taps.add(pcm));
        assertEquals(0, bypass.analyses);
        assertEquals(0, bypass.renders);
        assertSame(source, output);
        assertSame(output, taps.get(0));
        assertArrayEquals(new float[]{.8f, -.4f}, source);
    }
    @Test void bypassedControlMetadataSeesActualUpstreamWithoutRenderingTheBypass() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        Probe active = new Probe(true, false), metadata = new Probe(false, true);
        pipeline.addProcessor(active); pipeline.addProcessor(metadata);
        float[] source = {.8f, -.4f}; pipeline.prepare(48000, source.length);
        float[] output = pipeline.analyzeAndRender(source, 1, 0, null, null, null);
        assertEquals(1, active.analyses); assertEquals(1, active.renders);
        assertEquals(1, metadata.analyses); assertEquals(0, metadata.renders);
        assertArrayEquals(new float[]{.4f, -.2f}, metadata.observed);
        assertArrayEquals(metadata.observed, output);
        assertArrayEquals(new float[]{.8f, -.4f}, source);
    }
    @Test void enablingAfterSourceChangeAlwaysAnalyzesTheNewInput() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        Probe stage = new Probe(false, false); pipeline.addProcessor(stage);
        pipeline.prepare(48000, 2);
        pipeline.analyzeAndRender(new float[]{.8f, -.4f}, 1, 0, null, null, null);
        stage.setEnabled(true);
        float[] output = pipeline.analyzeAndRender(new float[]{.2f, -.6f}, 1, 0, null, null, null);
        assertEquals(1, stage.analyses);
        assertArrayEquals(new float[]{.2f, -.6f}, stage.observed);
        assertArrayEquals(new float[]{.1f, -.3f}, output);
    }

    @Test void cancellationStopsBeforeDownstreamAnalysisAndBetweenRenderBlocks() {
        var token = new com.quickmaster.processing.dynamics.leveler.CancellationToken();
        ProcessingPipeline pipeline = new ProcessingPipeline();
        Probe active = new Probe(true, false), downstream = new Probe(true, false);
        pipeline.addProcessor(active); pipeline.addProcessor(downstream);
        float[] source = new float[4096]; java.util.Arrays.fill(source, .8f);
        pipeline.prepare(48000, source.length);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> pipeline.analyzeAndRender(source, 1, 0, null, progress -> token.cancel(), null, token));
        assertEquals(1, active.renders);
        assertEquals(0, downstream.analyses);
        for (float sample : source) assertEquals(.8f, sample);
    }

    @Test void metadataOptInsAreExplicitAndExpensiveBypassesRemainOff() {
        assertTrue(new PeakNormalizer().analyzeWhenBypassed());
        assertTrue(new com.quickmaster.processing.dynamics.PeakCompProcessor().analyzeWhenBypassed());
        assertTrue(new com.quickmaster.processing.dynamics.BeatCompProcessor().analyzeWhenBypassed());
        assertFalse(new com.quickmaster.processing.dynamics.LevelerProcessor().analyzeWhenBypassed());
        assertFalse(new com.quickmaster.processing.limit.MultibandLimiterProcessor().analyzeWhenBypassed());
        assertFalse(new com.quickmaster.processing.limit.BroadbandLimiterProcessor().analyzeWhenBypassed());
    }

    @Test void alreadyCancelledPassDoesNotPrepareOrAnalyze() {
        var token = new com.quickmaster.processing.dynamics.leveler.CancellationToken(); token.cancel();
        ProcessingPipeline pipeline = new ProcessingPipeline(); Probe stage = new Probe(true, false);
        pipeline.addProcessor(stage); pipeline.prepare(48000, 2);
        int preparesBeforeCancel = stage.prepares;
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> pipeline.analyzeAndRender(new float[]{1,1}, 1, 0, null, null, null, token));
        assertEquals(0, stage.analyses); assertEquals(0, stage.renders);
        assertEquals(preparesBeforeCancel, stage.prepares);
    }

    @Test void defaultCancellationContractDelegatesOnlyForAnActiveOrAbsentToken() {
        Probe stage = new Probe(true, false);
        float[] source = {.3f, -.7f};
        stage.analyze(source, 1, null);
        var token = new com.quickmaster.processing.dynamics.leveler.CancellationToken();
        stage.analyze(source, 1, token);
        assertEquals(2, stage.analyses);
        assertArrayEquals(source, stage.observed);
        token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> stage.analyze(source, 1, token));
        assertEquals(2, stage.analyses);
    }
}
