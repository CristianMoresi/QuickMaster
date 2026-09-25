package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.ComparableGroup;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.GroupingResult;
import com.quickmaster.processing.dynamics.leveler.model.ReferencePlan;
import com.quickmaster.processing.dynamics.leveler.model.ReferenceTarget;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;

class ReferencePlannerTest
{
    @Test
    void waterFillingCapsDominantMemberAtPointFour()
    {
        FrozenList<SegmentDescriptor> segments = descriptors(-20.0d, -21.0d, -22.0d);
        ComparableGroup group = new ComparableGroup(0, new int[] { 0, 1, 2 },
                new double[] { 1.0d, 0.0d, 0.0d }, 0.5d);
        GroupingResult grouping = new GroupingResult(
                new FrozenList<ComparableGroup>(new Object[] { group }),
                new FrozenList<com.quickmaster.processing.dynamics.leveler.model.ComparablePair>(
                        new Object[0]));

        ReferencePlan plan = new ReferencePlanner().plan(
                grouping, segments, LevelerCalibrationProfile.V1);

        assertEquals(0.4d, plan.weightAt(0), 1.0e-12d);
        assertEquals(0.3d, plan.weightAt(1), 1.0e-12d);
        assertEquals(0.3d, plan.weightAt(2), 1.0e-12d);
    }

    @Test
    void goldenBSeparatesReferenceRawConfidenceAndWeightedValue()
    {
        FrozenList<SegmentDescriptor> segments = descriptors(-20.0d, -26.0d, -20.0d);
        double confidence = 0.43860126820672457d;
        ComparableGroup group = new ComparableGroup(0, new int[] { 0, 1, 2 },
                new double[] { 1.0d, 1.0d, 1.0d }, confidence);
        GroupingResult grouping = new GroupingResult(
                new FrozenList<ComparableGroup>(new Object[] { group }),
                new FrozenList<com.quickmaster.processing.dynamics.leveler.model.ComparablePair>(
                        new Object[0]));

        ReferencePlan plan = new ReferencePlanner().plan(
                grouping, segments, LevelerCalibrationProfile.V1);
        ReferenceTarget b = plan.targets().get(1);

        assertEquals(-20.0d, b.referenceLoudness().lufs(), 0.0d);
        assertEquals(5.0d, b.rawDb(), 0.0d);
        assertEquals(confidence, b.gConf(), 0.0d);
        assertEquals(2.1930063410336227d, b.confidenceWeightedDb(), 0.0d);
        assertEquals(0.0d, plan.targets().get(0).rawDb(), 0.0d);
        assertEquals(0.0d, plan.targets().get(2).confidenceWeightedDb(), 0.0d);
    }

    private static FrozenList<SegmentDescriptor> descriptors(double first, double second, double third)
    {
        double[] loudness = new double[] { first, second, third };
        Object[] values = new Object[3];
        for (int i = 0; i < values.length; i++)
        {
            values[i] = LevelerModelFixtures.descriptor(i, loudness[i],
                    LevelerModelFixtures.unitChroma(), LevelerModelFixtures.unitSpectral(),
                    0.8d, LevelerModelFixtures.neutralContext(),
                    LevelerModelFixtures.impulseSketch(2, 1.0d, i));
        }
        return new FrozenList<SegmentDescriptor>(values);
    }
}
