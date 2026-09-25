package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.GroupingResult;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityMatrix;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityScore;

class ComparableGroupBuilderTest
{
    @Test
    void completeTriangleFormsOneSeparatedGroup()
    {
        FrozenList<SegmentDescriptor> descriptors = descriptors(3);
        SimilarityMatrix matrix = matrix(3, new double[] { 0.95d, 0.96d, 0.94d });

        GroupingResult result = new ComparableGroupBuilder().build(
                descriptors, matrix, LevelerCalibrationProfile.V1);

        assertEquals(1, result.groups().size());
        assertEquals(3, result.groups().get(0).size());
        assertEquals(0, result.pairs().size());
    }

    @Test
    void nonTransitiveChainCannotBecomeAGroup()
    {
        FrozenList<SegmentDescriptor> descriptors = descriptors(3);
        SimilarityMatrix matrix = matrix(3, new double[] { 0.93d, 0.70d, 0.92d });

        GroupingResult result = new ComparableGroupBuilder().build(
                descriptors, matrix, LevelerCalibrationProfile.V1);

        assertEquals(0, result.groups().size());
    }

    @Test
    void mutualBestTieIsAmbiguousAndCreatesNoPair()
    {
        FrozenList<SegmentDescriptor> descriptors = descriptors(3);
        SimilarityMatrix matrix = matrix(3, new double[] { 0.95d, 0.95d, 0.70d });

        GroupingResult result = new ComparableGroupBuilder().build(
                descriptors, matrix, LevelerCalibrationProfile.V1);

        assertEquals(0, result.groups().size());
        assertEquals(0, result.pairs().size());
    }

    private static FrozenList<SegmentDescriptor> descriptors(int count)
    {
        Object[] values = new Object[count];
        for (int i = 0; i < count; i++)
        {
            values[i] = LevelerModelFixtures.descriptor(i, -20.0d,
                    LevelerModelFixtures.unitChroma(), LevelerModelFixtures.unitSpectral(),
                    0.8d, LevelerModelFixtures.neutralContext(),
                    LevelerModelFixtures.impulseSketch(2, 1.0d, i));
        }
        return new FrozenList<SegmentDescriptor>(values);
    }

    private static SimilarityMatrix matrix(int count, double[] combined)
    {
        Object[] scores = new Object[combined.length];
        for (int i = 0; i < combined.length; i++)
        {
            scores[i] = new SimilarityScore(combined[i], combined[i], combined[i], combined[i],
                    0, 32, SimilarityRejectionReason.NONE);
        }
        return new SimilarityMatrix(count, new FrozenList<SimilarityScore>(scores));
    }
}
