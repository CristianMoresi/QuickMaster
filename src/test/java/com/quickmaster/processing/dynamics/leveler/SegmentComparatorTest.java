package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityScore;

class SegmentComparatorTest
{
    @Test
    void identicalDescriptorsProduceUnitFourFactorScore()
    {
        SegmentDescriptor first = descriptor(0, LevelerModelFixtures.unitChroma());
        SegmentDescriptor second = descriptor(1, LevelerModelFixtures.unitChroma());

        SimilarityScore score = MusicalModelFixtures.centralComparison(
                first, second, LevelerCalibrationProfile.V1);

        assertEquals(SimilarityRejectionReason.NONE, score.rejectionReason());
        assertEquals(1.0d, score.h(), 1.0e-15d);
        assertEquals(1.0d, score.t(), 1.0e-15d);
        assertEquals(1.0d, score.a(), 1.0e-15d);
        assertEquals(1.0d, score.c(), 1.0e-15d);
        assertEquals(32, score.validBins());
    }

    @Test
    void transpositionUsesStableNonzeroRotationAndPenalty()
    {
        double[] transposed = new double[12];
        transposed[2] = 1.0d;
        SimilarityScore score = MusicalModelFixtures.centralComparison(
                descriptor(0, LevelerModelFixtures.unitChroma()),
                descriptor(1, transposed), LevelerCalibrationProfile.V1);

        assertEquals(SimilarityRejectionReason.NONE, score.rejectionReason());
        assertEquals(2, score.chromaRotation());
        assertEquals(0.95d, score.h(), 1.0e-15d);
    }

    @Test
    void twentyThreeBinsAreRejectedWithoutImputation()
    {
        SegmentDescriptor first = descriptor(0, LevelerModelFixtures.unitChroma());
        SegmentDescriptor full = descriptor(1, LevelerModelFixtures.unitChroma());
        SegmentDescriptor onlyTwentyThree = new SegmentDescriptor(full.id(), full.range(), full.bins(),
                (1L << 23) - 1L, full.regionalLoudness(), full.loudnessSlopeLuPerSec(),
                full.loudnessDeltaLu(), full.loudnessConsistency(), full.activitySlopePerSec(),
                full.activitySpread(), full.foregroundRatio(), full.context(), full.sketch());

        SimilarityScore score = MusicalModelFixtures.centralComparison(
                first, onlyTwentyThree, LevelerCalibrationProfile.V1);

        assertEquals(SimilarityRejectionReason.INSUFFICIENT_VALID_BINS, score.rejectionReason());
        assertEquals(23, score.validBins());
    }

    private static SegmentDescriptor descriptor(int ordinal, double[] chroma)
    {
        return LevelerModelFixtures.descriptor(ordinal, -20.0d, chroma,
                LevelerModelFixtures.unitSpectral(), 0.8d,
                LevelerModelFixtures.neutralContext(),
                LevelerModelFixtures.impulseSketch(2, 1.0d, ordinal));
    }
}
