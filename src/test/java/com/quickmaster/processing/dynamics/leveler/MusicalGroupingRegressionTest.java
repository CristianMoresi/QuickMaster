package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalGroupingRegressionTest
{
    @Test
    void alreadyGroupedCandidatesRemainSecondBestEvidenceForPairs()
    {
        double[][] scores = new double[5][5];
        MusicalModelFixtures.edge(scores, 0, 1, .99d);
        MusicalModelFixtures.edge(scores, 0, 2, .99d);
        MusicalModelFixtures.edge(scores, 1, 2, .99d);
        MusicalModelFixtures.edge(scores, 3, 4, .98d);
        MusicalModelFixtures.edge(scores, 0, 3, .88d);
        int[] order = { 0, 1, 2, 3, 4 };
        GroupingResult result = new ComparableGroupBuilder().build(
                MusicalModelFixtures.descriptors(order), MusicalModelFixtures.matrix(order, scores),
                LevelerCalibrationProfile.V1);
        assertEquals(1, result.groups().size());
        assertEquals(0, result.pairs().size(), "D-E margin is .10, not .98 after discarding A");
    }

    @Test
    void completeLinkTieIsInvariantUnderEveryInputPermutation()
    {
        double[][] scores = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++)
            MusicalModelFixtures.edge(scores, i, j, .95d);
        MusicalModelFixtures.edge(scores, 0, 3, .7d);
        // The tied complete-link candidate containing the smallest ID is ABC.
        // External .95 prevents acceptance; no order may publish another group or pair.
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
        for (int c = 0; c < 4; c++) for (int d = 0; d < 4; d++)
        {
            if (a == b || a == c || a == d || b == c || b == d || c == d) continue;
            int[] order = { a, b, c, d };
            GroupingResult result = new ComparableGroupBuilder().build(
                    MusicalModelFixtures.descriptors(order), MusicalModelFixtures.matrix(order, scores),
                    LevelerCalibrationProfile.V1);
            assertEquals(0, result.groups().size());
            assertEquals(0, result.pairs().size());
        }
    }

    @Test
    void separatedGroupPreservesSortedIdsAndQualityAcrossAllPermutations()
    {
        double[][] scores = new double[4][4];
        MusicalModelFixtures.edge(scores, 0, 1, .96d);
        MusicalModelFixtures.edge(scores, 0, 2, .94d);
        MusicalModelFixtures.edge(scores, 1, 2, .95d);
        MusicalModelFixtures.edge(scores, 0, 3, .7d);
        MusicalModelFixtures.edge(scores, 1, 3, .71d);
        MusicalModelFixtures.edge(scores, 2, 3, .72d);
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
        for (int c = 0; c < 4; c++) for (int d = 0; d < 4; d++)
        {
            if (a == b || a == c || a == d || b == c || b == d || c == d) continue;
            int[] order = { a, b, c, d };
            GroupingResult result = new ComparableGroupBuilder().build(
                    MusicalModelFixtures.descriptors(order), MusicalModelFixtures.matrix(order, scores),
                    LevelerCalibrationProfile.V1);
            assertEquals(1, result.groups().size());
            ComparableGroup group = result.groups().get(0);
            assertArrayEquals(new int[] { 0, 1, 2 }, group.copyMemberOrdinals());
            assertArrayEquals(new double[] { (.94d - .85d) / .1d, 1d,
                    (.94d - .85d) / .1d }, group.copyMemberQuality(), 1e-14d);
        }
    }

    @Test
    void fullAbcxyMatrixDerivesTheGoldenReferenceWithoutInjectedConfidence()
    {
        double[][] c = {
            { 1, 0 }, { 1, .2d }, { 1, 0, .2d },
            { .7d, .714142842854285d }, { .6d, 0, .8d }
        };
        Object[] observations = new Object[5];
        for (int id = 0; id < 5; id++)
        {
            double[] chroma = new double[12];
            double[] spectral = new double[8];
            System.arraycopy(c[id], 0, chroma, 0, c[id].length);
            System.arraycopy(c[id], 0, spectral, 0, c[id].length);
            StructuralBin[] bins = new StructuralBin[32];
            for (int k = 0; k < 32; k++) bins[k] = new StructuralBin(chroma, spectral, .1d, .8d);
            observations[id] = MusicalModelFixtures.descriptor(id, id == 1 ? -26 : -20,
                    bins, 0xFFFF_FFFFL, 576_000L);
        }
        FrozenList<SegmentDescriptor> descriptors = new FrozenList<SegmentDescriptor>(observations);
        Object[] calculated = new Object[10];
        int slot = 0;
        for (int i = 0; i < 5; i++) for (int j = i + 1; j < 5; j++)
            calculated[slot++] = MusicalModelFixtures.centralComparison(descriptors.get(i), descriptors.get(j),
                    LevelerCalibrationProfile.V1);
        SimilarityMatrix matrix = new SimilarityMatrix(5, new FrozenList<SimilarityScore>(calculated));
        double[][] expected = {
            { 0, .9805806756909201d, .9805806756909201d, .6641428428542849d, .75d },
            { 0, 0, .9615384615384615d, .826461407260822d, .734464540552736d },
            { 0, 0, 0, .6502746713858892d, .734464540552736d }
        };
        for (int i = 0; i < 3; i++) for (int j = i + 1; j < 5; j++)
            assertEquals(expected[i][j], matrix.scoreAt(i, j).c(), 1e-14d, i + ":" + j);
        GroupingResult result = new ComparableGroupBuilder().build(descriptors, matrix,
                LevelerCalibrationProfile.V1);
        assertEquals(1, result.groups().size());
        ComparableGroup group = result.groups().get(0);
        assertArrayEquals(new int[] { 0, 1, 2 }, group.copyMemberOrdinals());
        double cohesion = matrix.scoreAt(1, 2).c();
        double separation = cohesion - matrix.scoreAt(1, 3).c();
        double quality = Math.min(1d, Math.max(0d, (cohesion - .85d) / .10d));
        double marginQuality = Math.min(1d, Math.max(0d, (separation - .08d) / .12d));
        double derivedConfidence = quality * quality * (3d - 2d * quality)
                * (marginQuality * marginQuality * (3d - 2d * marginQuality));
        assertEquals(Double.doubleToRawLongBits(derivedConfidence),
                Double.doubleToRawLongBits(group.confidence()));
        // Published decimal goldens have a 1e-12 mathematical tolerance; formula above is bit exact.
        assertEquals(.43860126820672457d, group.confidence(), 1e-12d);
        ReferencePlan plan = new ReferencePlanner().plan(result, descriptors, LevelerCalibrationProfile.V1);
        ReferenceTarget b = plan.targets().get(1);
        assertEquals(-20, b.referenceLoudness().lufs());
        assertEquals(5, b.rawDb());
        assertEquals(.43860126820672457d, b.gConf(), 1e-12d);
        assertEquals(2.1930063410336227d, b.confidenceWeightedDb(), 1e-12d);
        for (int id : new int[] { 0, 2, 3, 4 }) assertEquals(0d,
                plan.targets().get(id).confidenceWeightedDb());
    }

    @Test
    void pairMarginAndStrictMutualityAreDerivedBeforeLookingAtLoudness()
    {
        double[][] scores = new double[4][4];
        MusicalModelFixtures.edge(scores, 0, 1, .98d);
        MusicalModelFixtures.edge(scores, 0, 2, .73d);
        MusicalModelFixtures.edge(scores, 1, 3, .74d);
        int[] order = { 0, 1, 2, 3 };
        GroupingResult result = new ComparableGroupBuilder().build(MusicalModelFixtures.descriptors(order),
                MusicalModelFixtures.matrix(order, scores), LevelerCalibrationProfile.V1);
        assertEquals(1, result.pairs().size());
        assertEquals(.24d, result.pairs().get(0).margin(), 1e-15d);
        assertEquals(1d, result.pairs().get(0).confidence(), 1e-15d);
        MusicalModelFixtures.edge(scores, 0, 2, Math.nextDown(.98d));
        result = new ComparableGroupBuilder().build(MusicalModelFixtures.descriptors(order),
                MusicalModelFixtures.matrix(order, scores), LevelerCalibrationProfile.V1);
        assertEquals(0, result.pairs().size(), "within 1e-12 is an ambiguous tie");
    }
}
