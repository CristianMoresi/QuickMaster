package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.BodyContextVector;
import com.quickmaster.processing.dynamics.leveler.model.PcmSketch;

class BodyContextGateTest
{
    @Test
    void explicitAvailabilityIsRequiredByEveryGateWithoutChangingSixComponents() throws Exception
    {
        SegmentDescriptorBuilderTest.ContextAvailabilityChecks.maskAndDirectGateContract();
    }

    @Test
    void n08GainScaledCopyIsIdentifiedBeforeSimilarity()
    {
        PcmSketch first = LevelerModelFixtures.impulseSketch(2, 1.0d, 0);
        PcmSketch second = LevelerModelFixtures.impulseSketch(2, 0.5d, 0);
        BodyContextVector context = LevelerModelFixtures.neutralContext();

        double[] assessment = new BodyContextGate().assess(first, second, context, context);

        assertNotNull(assessment);
        assertEquals(0.0d, assessment[0], 0.0d);
        assertEquals(2.0d, assessment[1], 1.0e-15d);
        assertEquals(0.0d, assessment[2], 1.0e-15d);
        assertEquals(0.0d, assessment[3], 1.0e-15d);
        assertTrue(assessment[2] <= 1.0e-4d && assessment[3] <= 0.05d);
    }

    @Test
    void p01VariationIsHomologousButNotGainScaledAmbiguous()
    {
        double[][] x = new double[][] { new double[2048], new double[2048] };
        double[][] y = new double[][] { new double[2048], new double[2048] };
        x[0][100] = 0.5d;
        y[0][100] = 0.5d;
        y[1][500] = 1.0d / 64.0d;
        BodyContextVector firstContext = new BodyContextVector(1.0d, 1.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        BodyContextVector secondContext = new BodyContextVector(1.03125d, 1.0d, 0.0d, 0.0d, 0.0d, 0.0d);

        double[] assessment = new BodyContextGate().assess(
                new PcmSketch(2, 2048, x), new PcmSketch(2, 2048, y),
                firstContext, secondContext);

        assertNotNull(assessment);
        assertEquals(0.0d, assessment[0], 0.0d);
        assertEquals(1024.0d / 1025.0d, assessment[1], 1.0e-15d);
        assertEquals(1.0d / StrictMath.sqrt(1025.0d), assessment[2], 1.0e-15d);
        assertEquals(0.125d, assessment[3], 1.0e-15d);
        assertFalse(assessment[2] <= 1.0e-4d && assessment[3] <= 0.05d);
        assertTrue(assessment[3] <= 1.0d);
    }

    @Test
    void ambiguityThresholdsAreInclusiveWithIeeeNeighbours()
    {
        assertTrue(ambiguous(Math.nextDown(1.0e-4d), Math.nextDown(0.05d)));
        assertTrue(ambiguous(1.0e-4d, 0.05d));
        assertFalse(ambiguous(Math.nextUp(1.0e-4d), 0.05d));
        assertFalse(ambiguous(1.0e-4d, Math.nextUp(0.05d)));
    }

    private static boolean ambiguous(double residual, double context)
    {
        return residual <= 1.0e-4d && context <= 0.05d;
    }
}
