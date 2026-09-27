package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.quickmaster.processing.dynamics.leveler.model.*;

class ArrangementPcmAcceptanceTest
{
    @Test void extendedRepeatedPerformanceReceivesActualBoundGain() throws Exception
    {
        var source = MusicalPcmFixture.catalog().stream().filter(c -> c.key().equals("P01_ABA_LEVEL")).findFirst().orElseThrow();
        var parts = new ArrayList<>(source.parts());
        var repeated = parts.get(4);
        parts.set(4, new MusicalPcmFixture.Part(repeated.label(), 40, repeated.family(), repeated.rmsDb(),
                repeated.endDb(), repeated.performance(), repeated.duty(), repeated.transpose(), repeated.pan(), repeated.melody()));
        var test = MusicalPcmFixture.c("EXTENDED_PERFORMANCE", "Same arrangement with a 40 s second chorus, not a synchronized copy",
                parts, "C1", "C2", null, -1);
        var clip = MusicalPcmFixture.generate(test, 48000, 2);
        var analysis = new LevelerAnalysisEngine().analyzeShadow(clip.pcm(), clip.format(), new CancellationToken());
        assertNotNull(analysis);
        int first = find(analysis.cache(), clip, "C1");
        int second = find(analysis.cache(), clip, "C2");
        assertTrue(first >= 0 && second >= 0);
        assertEquals(ReferenceReason.ARRANGEMENT_REFERENCE, analysis.cache().referencePlan().targets().get(first).reason());
        assertTrue(analysis.cache().referencePlan().targets().get(first).confidenceWeightedDb() < -.8);
        assertTrue(analysis.cache().referencePlan().targets().get(second).confidenceWeightedDb() > .8);
        try (var processor = RuntimeLevelerAcceptanceTest.processor())
        {
            processor.controls(1, .5);
            processor.analyze(clip.pcm(), 48000, 2);
            assertEquals("STRUCTURAL_READY", processor.status());
            double[] gains = new double[2];
            for (int n = 0; n < 2; n++)
            {
                String label = nLabel(n);
                var part = clip.truth().stream().filter(t -> t.label().equals(label)).findFirst().orElseThrow();
                long at = part.start() + (part.end() - part.start()) / 2;
                float[] input = Arrays.copyOfRange(clip.pcm(), Math.toIntExact(at * 2), Math.toIntExact(at * 2 + 8192));
                float[] output = processor.render(input, 2, at);
                double in = 0, out = 0;
                for (int i = 0; i < input.length; i++) { in += input[i] * (double)input[i]; out += output[i] * (double)output[i]; }
                gains[n] = 10 * Math.log10(out / in);
            }
            assertTrue(gains[0] < -.8 && gains[1] > .8, Arrays.toString(gains));
        }
    }

    private static String nLabel(int n) { return n == 0 ? "C1" : "C2"; }

    private static int find(ShadowAnalysisCache cache, MusicalPcmFixture.Clip clip, String label)
    {
        var truth = clip.truth().stream().filter(t -> t.label().equals(label)).findFirst().orElseThrow();
        for (int i = 0; i < cache.descriptors().size(); i++)
        {
            var range = cache.descriptors().get(i).range();
            long overlap = Math.min(range.endExclusive(), truth.end()) - Math.max(range.startInclusive(), truth.start());
            if (overlap >= .8 * range.lengthFrames() && overlap >= .8 * (truth.end() - truth.start())) return i;
        }
        return -1;
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 48_000, 240_000 })
    void silentTailDoesNotMakeARepeatedOutroCorrectable(int silentFrames)
    {
        var test = MusicalPcmFixture.catalog().stream().filter(c -> c.key().equals("N10_FLAT_OUTRO_REPEAT")).findFirst().orElseThrow();
        var clip = MusicalPcmFixture.generate(test, 48000, 2);
        float[] pcm = Arrays.copyOf(clip.pcm(), clip.pcm().length + silentFrames * 2);
        var format = new AudioFormat(48000, 2, pcm.length / 2);
        var analysis = new LevelerAnalysisEngine().analyzeShadow(pcm, format, new CancellationToken());
        assertNotNull(analysis);
        var outro = clip.truth().get(clip.truth().size() - 1);
        for (int i = 0; i < analysis.cache().descriptors().size(); i++)
        {
            var region = analysis.cache().descriptors().get(i).range();
            if (region.endExclusive() > outro.start())
                assertEquals(0, analysis.cache().referencePlan().targets().get(i).confidenceWeightedDb(), "outro region " + i);
        }
    }
}
