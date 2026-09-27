package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;

class MacroDynamicsProtectionTest
{
    @Test
    void stationaryBodyAndOneSilentTailAreNotAMacroBuildup()
    {
        assertMacro(false, new double[] { -12, -12, -12, -12, Double.NaN },
                new double[] { 1, 1, 1, 1, 0 });
    }

    @Test
    void singleLevelStepAfterAPlateauIsNotAMacroBuildup()
    {
        assertMacro(false, new double[] { -18, -18, -18, -18, -12 },
                new double[] { 1, 1, 1, 1, 1 });
        assertMacro(false, new double[] { -18, -18, -18, -18, -24 },
                new double[] { 1, 1, 1, 1, 1 });
    }

    @Test
    void genuineDistributedRisesAndFallsRemainProtectedIncludingOnePlateau()
    {
        assertMacro(true, new double[] { -18, -16.5, -15, -13.5, -12 },
                new double[] { 1, 1, 1, 1, 1 });
        assertMacro(true, new double[] { -12, -12, -14, -16, -18 },
                new double[] { 1, 1, 1, 1, 1 });
        assertMacro(true, new double[] { -18, -18, -18, -18, -18 },
                new double[] { .6, .7, .8, .9, 1 });
        assertMacro(true, new double[] { -18, -18, -18, -18, -18 },
                new double[] { 1, .9, .8, .7, .6 });
    }

    @Test
    void tinyNumericalDriftDoesNotTurnAnIsolatedJumpIntoATrend()
    {
        assertMacro(false, new double[] { -18, -17.999, -17.998, -17.997, -12 },
                new double[] { .9997, .9998, .9999, 1, 0 });
    }

    private static void assertMacro(boolean expected, double[] loudness, double[] activity)
    {
        Object[] descriptors = new Object[loudness.length];
        for (int i = 0; i < descriptors.length; i++)
        {
            MeasuredLoudness measured = Double.isFinite(loudness[i])
                    ? new MeasuredLoudness(true, loudness[i]) : MeasuredLoudness.absent();
            descriptors[i] = MusicalModelFixtures.withStats(MusicalModelFixtures.descriptor(i, -18),
                    measured, 0, 0, 0, 0, 0, activity[i]);
        }
        long bits = new ProtectionClassifier().classify(2,
                new FrozenList<SegmentDescriptor>(descriptors), LevelerCalibrationProfile.V1).flags().reasonBits();
        assertEquals(expected, (bits & (1L << 7)) != 0);
    }
}
