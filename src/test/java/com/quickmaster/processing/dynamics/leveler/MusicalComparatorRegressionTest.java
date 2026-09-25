package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalComparatorRegressionTest
{
    @Test
    void goldenOrthogonalAndCoverageUseTheSameDenominator()
    {
        SimilarityScore orthogonal = compare(MusicalModelFixtures.bins(0, 0, 0.0d),
                MusicalModelFixtures.bins(0, 1, 1.0d), 0xFFFF_FFFFL);
        assertScore(orthogonal, 1.0d, 0.4d, 0.65d, 0.45d);
        long mask = 0xFFFF_FFFFL;
        for (int i = 3; i < 32; i += 4) mask &= ~(1L << i);
        StructuralBin[] bins = MusicalModelFixtures.bins(0, 0, 0.9d);
        assertScore(compare(bins, bins, mask), 0.75d, 0.75d, 0.75d, 0.75d);
        mask &= ~(1L << 0);
        assertEquals(SimilarityRejectionReason.INSUFFICIENT_VALID_BINS,
                compare(bins, bins, mask).rejectionReason());
        assertEquals(SimilarityRejectionReason.INVALID_BIN_RUN,
                compare(bins, bins, 0xFFFF_FFE0L).rejectionReason());
    }

    @Test
    void dtwBandUsesOriginalIndicesAndEpsilonTies()
    {
        Random seeded = new Random(0x4d55534943414cL);
        boolean discriminatedCompressedBand = false;
        for (int trial = 0; trial < 128; trial++)
        {
            StructuralBin[] a = MusicalModelFixtures.bins(0, 0, 0.0d);
            StructuralBin[] b = MusicalModelFixtures.bins(0, 0, 0.0d);
            for (int i = 0; i < 32; i++)
            {
                a[i] = MusicalModelFixtures.bin(0, 0, seeded.nextInt(4) / 4.0d);
                b[i] = MusicalModelFixtures.bin(0, 0, seeded.nextInt(4) / 4.0d);
            }
            long mask = 0xFFFF_FFFFL;
            for (int i = 3; i < 32; i += 4) mask &= ~(1L << i);
            double expected = alignment(a, b, mask, true, true);
            double mutant = alignment(a, b, mask, false, true);
            if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(mutant))
            {
                discriminatedCompressedBand = true;
                assertEquals(expected, compare(a, b, mask).a(), 1.0e-15d,
                        "original-index band; seed trial " + trial);
            }
        }
        assertTrue(discriminatedCompressedBand, "the oracle must kill the compressed-index mutant");

        boolean discriminatedStrictTie = false;
        for (int trial = 0; trial < 128; trial++)
        {
            StructuralBin[] a = MusicalModelFixtures.bins(0, 0, 0.0d);
            StructuralBin[] b = MusicalModelFixtures.bins(0, 0, 0.0d);
            for (int i = 0; i < 32; i++)
            {
                a[i] = MusicalModelFixtures.bin(0, 0, seeded.nextInt(4) * 1.0e-12d);
                b[i] = MusicalModelFixtures.bin(0, 0, seeded.nextInt(4) * 1.0e-12d);
            }
            double expected = alignment(a, b, 0xFFFF_FFFFL, true, true);
            double mutant = alignment(a, b, 0xFFFF_FFFFL, true, false);
            if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(mutant))
            {
                discriminatedStrictTie = true;
                assertEquals(Double.doubleToRawLongBits(expected),
                        Double.doubleToRawLongBits(compare(a, b, 0xFFFF_FFFFL).a()),
                        "epsilon tie priority; seed trial " + trial);
            }
        }
        assertTrue(discriminatedStrictTie, "the oracle must kill strict-cost tie comparison");
    }

    @Test
    void rotationNeedsEightyPercentAndTiesSelectSmallestRotation()
    {
        StructuralBin[] a = MusicalModelFixtures.bins(0, 0, 0.9d);
        StructuralBin[] b = MusicalModelFixtures.bins(2, 0, 0.9d);
        for (int i = 26; i < 32; i++) b[i] = MusicalModelFixtures.bin(3, 0, 0.9d);
        assertEquals(SimilarityRejectionReason.NONE, compare(a, b, 0xFFFF_FFFFL).rejectionReason());
        b[25] = MusicalModelFixtures.bin(3, 0, 0.9d);
        assertEquals(SimilarityRejectionReason.AMBIGUOUS, compare(a, b, 0xFFFF_FFFFL).rejectionReason());
        double[] equal = new double[12];
        Arrays.fill(equal, 1.0d);
        for (int i = 0; i < 32; i++) a[i] = b[i] = new StructuralBin(equal,
                new double[] { 1, 0, 0, 0, 0, 0, 0, 0 }, 0.0d, 0.9d);
        assertEquals(0, compare(a, b, 0xFFFF_FFFFL).chromaRotation());
    }

    @Test
    void scoreIsIndependentOfRegionalLoudnessAndDurationLimitsAreInclusive() throws Exception
    {
        long frames = 1000L;
        AudioFormat source = new AudioFormat(2, 1, frames);
        FeatureTimeline features = MusicalContextFixtures.features(1L, frames,
                i -> MusicalModelFixtures.bin(0, 0, .9d));
        SegmentDescriptor a = MusicalContextFixtures.descriptor(0, features, new FrameRange(100, 200), frames);
        SegmentDescriptor b = MusicalContextFixtures.descriptor(1, features, new FrameRange(500, 600), frames);
        SimilarityScore first = MusicalContextFixtures.compare(a, b, source, features);
        SegmentDescriptor quieter = MusicalModelFixtures.withStats(b, new MeasuredLoudness(true, -60d),
                b.loudnessSlopeLuPerSec(), b.loudnessDeltaLu(), b.loudnessConsistency(),
                b.activitySlopePerSec(), b.activitySpread(), b.foregroundRatio());
        SimilarityScore changed = MusicalContextFixtures.compare(a, quieter, source, features);
        assertEquals(SimilarityRejectionReason.NONE, first.rejectionReason());
        assertEquals(SimilarityRejectionReason.NONE, changed.rejectionReason());
        assertEquals(Double.doubleToRawLongBits(first.c()), Double.doubleToRawLongBits(changed.c()));
        for (long duration : new long[] { 75, 133 })
        {
            SegmentDescriptor edge = MusicalContextFixtures.descriptor(0, features,
                    new FrameRange(100, 100 + duration), frames);
            assertEquals(SimilarityRejectionReason.NONE, MusicalModelFixtures.centralComparison(
                    edge, b, LevelerCalibrationProfile.V1).rejectionReason());
            SimilarityScore score = MusicalContextFixtures.compare(edge, b, source, features);
            assertEquals(SimilarityRejectionReason.UNSTABLE_BOUNDARY, score.rejectionReason());
        }
        for (long duration : new long[] { 74, 134 })
        {
            assertEquals(SimilarityRejectionReason.DURATION_RATIO,
                    MusicalContextFixtures.compare(MusicalContextFixtures.descriptor(0, features,
                    new FrameRange(100, 100 + duration), frames), b, source, features).rejectionReason());
        }
    }

    private static SimilarityScore compare(StructuralBin[] a, StructuralBin[] b, long mask)
    {
        return MusicalModelFixtures.centralComparison(
                MusicalModelFixtures.descriptor(0, -20, a, mask, 576_000),
                MusicalModelFixtures.descriptor(1, -26, b, mask, 576_000), LevelerCalibrationProfile.V1);
    }

    private static void assertScore(SimilarityScore score, double h, double t, double a, double c)
    {
        assertEquals(SimilarityRejectionReason.NONE, score.rejectionReason());
        assertEquals(h, score.h(), 1e-15);
        assertEquals(t, score.t(), 1e-15);
        assertEquals(a, score.a(), 1e-15);
        assertEquals(c, score.c(), 1e-15);
    }

    /** Independent explicit full-grid oracle; fixtures vary only activity. */
    private static double alignment(StructuralBin[] a, StructuralBin[] b, long mask,
                                    boolean originalBand, boolean epsilonTie)
    {
        int[] indices = new int[32];
        int n = 0;
        for (int i = 0; i < 32; i++) if ((mask & (1L << i)) != 0) indices[n++] = i;
        double[][] sums = new double[n][n];
        int[][] lengths = new int[n][n];
        for (double[] row : sums) Arrays.fill(row, Double.POSITIVE_INFINITY);
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
        {
            if (Math.abs(originalBand ? indices[i] - indices[j] : i - j) > 4) continue;
            double local = .2d * Math.abs(a[indices[i]].activity() - b[indices[j]].activity());
            if (i == 0 && j == 0) { sums[i][j] = local; lengths[i][j] = 1; continue; }
            double selected = Double.POSITIVE_INFINITY;
            int length = 0;
            int[][] parents = { { i - 1, j - 1 }, { i - 1, j }, { i, j - 1 } };
            for (int[] parent : parents)
            {
                if (parent[0] < 0 || parent[1] < 0) continue;
                double candidate = sums[parent[0]][parent[1]];
                if (candidate < selected - (epsilonTie ? 1e-12d : 0.0d))
                { selected = candidate; length = lengths[parent[0]][parent[1]]; }
            }
            if (Double.isFinite(selected)) { sums[i][j] = selected + local; lengths[i][j] = length + 1; }
        }
        return n / 32.0d * (1.0d - sums[n - 1][n - 1] / lengths[n - 1][n - 1]);
    }
}
