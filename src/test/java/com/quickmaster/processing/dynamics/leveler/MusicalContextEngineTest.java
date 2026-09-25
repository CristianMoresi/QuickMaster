package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalContextEngineTest
{
    @Test
    void deterministicPcmReachesComparisonInThreeCompleteEngineRuns()
    {
        String expected = null;
        String expectedCells = null;
        var references = new ComparisonV2Oracle.ReferenceMemo();
        for (int run = 0; run < 3; run++)
        {
            ShadowAnalysisSnapshot snapshot = MusicalContextPcmFixture.analyze();
            assertNotNull(snapshot);
            String summary = summarize(snapshot);
            System.out.println("PCM_ENGINE_" + run + " " + summary);
            assertEquals(ShadowAnalysisStatus.DONE, snapshot.result().status());
            int calculated = 0;
            for (int i = 0; i < snapshot.cache().similarity().scores().size(); i++)
            {
                SimilarityScore score = snapshot.cache().similarity().scores().get(i);
                if (score.rejectionReason() == SimilarityRejectionReason.NONE) calculated++;
            }
            assertEquals(3, calculated, "All three actual V2 pairs must be present: " + summary);
            assertEquals(2, ComparisonV2Oracle.version(snapshot.cache()));
            String cells = ComparisonV2Oracle.sourceIdentity(ComparisonV2Test.source(snapshot.cache().comparison()));
            if (expectedCells == null) expectedCells = cells; else assertEquals(expectedCells, cells, "Complete retained cells/format must repeat bit-exactly");
            verifyIndependentOracle(snapshot, references);
            if (expected == null) expected = summary;
            else assertEquals(expected, summary, "Repeated engine output must have identical bits.");
        }
    }

    static void verifyIndependentOracle(ShadowAnalysisSnapshot snapshot)
    { verifyIndependentOracle(snapshot, new ComparisonV2Oracle.ReferenceMemo()); }

    private static void verifyIndependentOracle(ShadowAnalysisSnapshot snapshot, ComparisonV2Oracle.ReferenceMemo references)
    {
        var cache = snapshot.cache(); int index = 0;
        java.util.List<String> pairs = new java.util.ArrayList<>();
        for (int a = 0; a < cache.descriptors().size(); a++) for (int b = a + 1; b < cache.descriptors().size(); b++)
        {
            SimilarityScore actual = cache.similarity().scores().get(index++);
            if (actual.rejectionReason() != SimilarityRejectionReason.NONE) continue;
            pairs.add(a + ":" + b);
            var expected = references.observe(cache, cache.descriptors().get(a).range(), cache.descriptors().get(b).range());
            ComparisonV2Oracle.requireVersion(cache, expected);
            var wrong = new ComparisonV2Oracle.Observed(expected.version() == 2 ? 1 : 2,
                    expected.central(), expected.variants(), expected.reason(), expected.evidence());
            assertEquals("QM_CONTEXT_ORACLE_VERSION_MISMATCH", assertThrows(IllegalArgumentException.class,
                    () -> ComparisonV2Oracle.requireVersion(cache, wrong)).getMessage());
            assertEquals(expected.reason(), actual.rejectionReason());
            assertEquals(expected.central().h(), actual.h(), 1e-12);
            assertEquals(expected.central().t(), actual.t(), 1e-12);
            assertEquals(expected.central().a(), actual.a(), 1e-12);
            assertEquals(expected.central().c(), actual.c(), 1e-12);
            assertEquals(expected.central().rotation(), actual.chromaRotation());
            assertEquals(expected.central().count(), actual.validBins());
            if (expected.version() == 2) {
                assertEquals(8, expected.variants().size());
                var full = (ComparisonV2Oracle.Comparison) expected.evidence();
                int largest = full.variants().stream().mapToInt(v -> v.score().n()).max().orElseThrow();
                assertEquals(2420, largest, "The real .5s extended context view is not shortened or downsampled");
            }
        }
        if (ComparisonV2Oracle.version(cache) == 2) assertEquals(java.util.List.of("2:3", "2:4", "3:4"), pairs);
    }

    @Test
    void valid2421CellReferenceFailsOnlyTheExplicitFullGridGuard()
    {
        var format = new AudioFormat(40, 1, 2421);
        ComparisonV2Oracle.Source source = new ComparisonV2Oracle.Source() {
            public AudioFormat format() { return format; }
            public int size(boolean longer) { return ComparisonV2Oracle.count(format.frames(), 40, longer ? 8 : 40); }
            public int flags(boolean longer, int row) { return 7; }
            public int packed(boolean longer, int row, int column) { return column == 0 ? 65535 : 0; }
        };
        ComparisonV2Oracle.validate(source);
        assertEquals(2420, ComparisonV2Oracle.MAX_REFERENCE_N);
        assertEquals(2421, ComparisonV2Oracle.view(source, false, new ComparisonV2Oracle.Range(0, 2421)).cells().size());
        var error = assertThrows(IllegalArgumentException.class, () -> ComparisonV2Oracle.score(source,
                new ComparisonV2Oracle.Range(0, 2421), new ComparisonV2Oracle.Range(0, 2421)));
        assertEquals("bounded test oracle, not product duration rejection", error.getMessage());
    }

    static String summarize(ShadowAnalysisSnapshot snapshot)
    {
        StringBuilder result = new StringBuilder();
        var cache = snapshot.cache();
        result.append(cache.algorithmId()).append('/').append(cache.profileId()).append('/');
        for (int i = 0; i < cache.descriptors().size(); i++)
        {
            var d = cache.descriptors().get(i);
            result.append(i).append(':').append(d.range().startInclusive()).append('-')
                    .append(d.range().endExclusive()).append('/').append(cache.protections().get(i).flags().reasonBits())
                    .append('/').append(d.regionalLoudness().present()).append('/').append(d.regionalLoudness().lufs())
                    .append('/').append(d.loudnessSlopeLuPerSec()).append('/').append(d.context().leftNoveltyMad())
                    .append('/').append(d.context().rightNoveltyMad()).append(';');
            if (cache.protections().get(i).isBlocked())
                assertEquals(0L, Double.doubleToRawLongBits(cache.referencePlan().targets().get(i).confidenceWeightedDb()));
        }
        for (int i = 0; i < cache.similarity().scores().size(); i++)
        {
            var score = cache.similarity().scores().get(i);
            result.append(score.rejectionReason()).append(':')
                .append(Double.toHexString(score.h())).append(',').append(Double.toHexString(score.t())).append(',')
                .append(Double.toHexString(score.a())).append(',').append(Double.toHexString(score.c())).append(',')
                .append(score.chromaRotation()).append(',').append(score.validBins()).append(';');
        }
        for (int i = 0; i < cache.referencePlan().targets().size(); i++)
        {
            var target = cache.referencePlan().targets().get(i);
            result.append(target.reason()).append(':')
                .append(Double.toHexString(target.rawDb())).append(':')
                .append(Double.toHexString(target.confidenceWeightedDb())).append(';');
        }
        return result.toString();
    }
}
