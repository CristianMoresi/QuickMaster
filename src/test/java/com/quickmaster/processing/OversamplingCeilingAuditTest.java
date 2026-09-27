package com.quickmaster.processing;

import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.clip.*;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import com.quickmaster.processing.dynamics.leveler.FiniteTruePeakStream;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class OversamplingCeilingAuditTest {
    @Test void actualDeliveredWaveformHonorsCeilingAcross270Combinations() {
        for (int rate : new int[]{44100, 48000, 96000}) for (int ch : new int[]{1, 2})
            for (int factor : new int[]{1, 2, 4, 8, 16}) for (int shape = 0; shape < 3; shape++)
                for (int clip = 0; clip < 3; clip++) {
                    float[] pcm = new float[8193 * ch]; Random random = new Random(415);
                    for (int f = 0; f < 8193; f++) for (int c = 0; c < ch; c++)
                        pcm[f * ch + c] = (float) (.8 * (shape == 0 ? Math.sin(f * Math.PI * .37 + c * .6)
                                : shape == 1 ? random.nextDouble() * 2 - 1 : f % 173 == 0 ? c == 0 ? 1 : -1 : 0));
                    ProcessingPipeline pipeline = new ProcessingPipeline();
                    if (clip == 1) { HardClipProcessor p = new HardClipProcessor(); p.setEnabled(true); p.setClipDb(6); pipeline.addProcessor(p); }
                    if (clip == 2) { SoftClipProcessor p = new SoftClipProcessor(); p.setEnabled(true); p.setSatDb(6); pipeline.addProcessor(p); }
                    pipeline.addProcessor(new PeakNormalizer(-1));
                    WavFile audio = new WavFile("generated", rate, ch, pcm, 32, true);
                    float[] source = pcm.clone(); pipeline.processOversampled(audio, factor, null);
                    FiniteTruePeakStream independent = new FiniteTruePeakStream(ch, true);
                    independent.accept(audio.getSamples(), 0, 8193);
                    double peakDb = 20 * Math.log10(independent.finish());
                    assertTrue(Double.isFinite(peakDb) && peakDb <= -.99999,
                            "rate=" + rate + " ch=" + ch + " factor=" + factor + " shape=" + shape + " clip=" + clip + " peak=" + peakDb);
                    assertEquals(-1, peakDb, .005); assertArrayEquals(source, pcm);
                }
    }

    @Test void normalizerIsLinearAndRejectsNonFiniteParameters() {
        PeakNormalizer normalizer = new PeakNormalizer(0); normalizer.setAnalyzedPeak(.5);
        assertArrayEquals(new float[]{4, -4}, normalizer.process(new float[]{2, -2}, 1));
        assertThrows(IllegalArgumentException.class, () -> normalizer.setTargetDbfs(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> normalizer.setAnalyzedPeak(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> normalizer.setAnalyzedPeak(-1));
        assertThrows(IllegalArgumentException.class, () -> normalizer.setAnalyzedPeak(Double.POSITIVE_INFINITY));
    }

    @Test void invalidBlockSizesFactorsAndCancellationFailBeforeRendering() {
        ProcessingPipeline pipeline = new ProcessingPipeline();
        WavFile audio = new WavFile("generated", 48000, 1, new float[]{.5f, .25f}, 32, true);
        for (int factor : new int[]{0, 3, 32}) assertThrows(IllegalArgumentException.class, () -> pipeline.processOversampled(audio, factor, null));
        assertThrows(IllegalArgumentException.class, () -> pipeline.executeBlocks(new float[2], 1, 0));
        assertThrows(IllegalArgumentException.class, () -> pipeline.executeBlocks(new float[3], 2, 1024));
        pipeline.prepare(48000, 2); CancellationToken token = new CancellationToken(); token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> pipeline.renderAnalyzedOversampled(audio.getSamples(), 1, 4, null, token));
        assertArrayEquals(new float[]{.5f, .25f}, audio.getSamples());
    }
}
