package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalReferenceRegressionTest
{
    @Test
    void waterFillingCapsStrictlyAtPointFourWithoutAnEpsilonAllowance()
    {
        double weight = .40d + 5e-13d;
        double quality = 2d * weight / (1d - weight) - 1d;
        ReferencePlan plan = new ReferencePlanner().plan(group(new double[] { quality, 0, 0 }),
                MusicalModelFixtures.descriptors(0, 1, 2), LevelerCalibrationProfile.V1);
        assertEquals(.40d, plan.weightAt(0), 0d);
        assertEquals(.30d, plan.weightAt(1), 0d);
        assertEquals(.30d, plan.weightAt(2), 0d);
    }

    @Test
    void referenceMembershipUsesIdsEvenWhenTheInputIsPermuted()
    {
        FrozenList<SegmentDescriptor> segments = new FrozenList<SegmentDescriptor>(new Object[] {
                MusicalModelFixtures.descriptor(2, -20), MusicalModelFixtures.descriptor(0, -20),
                MusicalModelFixtures.descriptor(1, -26) });
        ReferencePlan plan = new ReferencePlanner().plan(group(new double[] { 1, 1, 1 }),
                segments, LevelerCalibrationProfile.V1);
        for (int i = 0; i < segments.size(); i++)
        {
            assertSame(segments.get(i).id(), plan.targets().get(i).segmentId());
            assertEquals(segments.get(i).id().ordinal() == 1 ? 5d : 0d,
                    plan.targets().get(i).rawDb());
        }
    }

    @Test
    void pairUsesMidpointAndDoesNotClampRawTargets()
    {
        FrozenList<SegmentDescriptor> segments = new FrozenList<SegmentDescriptor>(new Object[] {
                MusicalModelFixtures.descriptor(0, -40), MusicalModelFixtures.descriptor(1, -20) });
        GroupingResult pair = new GroupingResult(new FrozenList<ComparableGroup>(new Object[0]),
                new FrozenList<ComparablePair>(new Object[] { new ComparablePair(0, 1, .5d, .24d) }));
        ReferencePlan plan = new ReferencePlanner().plan(pair, segments, LevelerCalibrationProfile.V1);
        assertEquals(-30, plan.targets().get(0).referenceLoudness().lufs());
        assertEquals(9, plan.targets().get(0).rawDb());
        assertEquals(-9, plan.targets().get(1).rawDb());
        assertEquals(4.5d, plan.targets().get(0).confidenceWeightedDb());
        assertEquals(.5d, plan.weightAt(0));
        assertEquals(.5d, plan.weightAt(1));
    }

    @Test
    void nonFiniteDerivedArithmeticFallsBackToCanonicalUnit()
    {
        FrozenList<SegmentDescriptor> segments = new FrozenList<SegmentDescriptor>(new Object[] {
                MusicalModelFixtures.descriptor(0, Double.MAX_VALUE),
                MusicalModelFixtures.descriptor(1, -Double.MAX_VALUE),
                MusicalModelFixtures.descriptor(2, Double.MAX_VALUE) });
        ReferencePlan plan = assertDoesNotThrow(() -> new ReferencePlanner().plan(
                group(new double[] { 1, 1, 1 }), segments, LevelerCalibrationProfile.V1));
        ReferenceTarget target = plan.targets().get(1);
        assertEquals(0L, Double.doubleToRawLongBits(target.rawDb()));
        assertEquals(0L, Double.doubleToRawLongBits(target.gConf()));
        assertEquals(0L, Double.doubleToRawLongBits(target.confidenceWeightedDb()));
        assertFalse(target.referenceLoudness().present());
        assertEquals(0d, plan.weightAt(1));
    }

    private static GroupingResult group(double[] quality)
    {
        return new GroupingResult(new FrozenList<ComparableGroup>(new Object[] {
                new ComparableGroup(0, new int[] { 0, 1, 2 }, quality, 1d) }),
                new FrozenList<ComparablePair>(new Object[0]));
    }
}
