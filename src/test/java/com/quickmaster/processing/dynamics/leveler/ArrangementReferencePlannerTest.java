package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Component observations, separate from the PCM and packaged-render acceptance. */
class ArrangementReferencePlannerTest
{
    @Test void comparableBodiesDoNotNeedEqualLengthOrEqualEntryLevel()
    {
        var fixture = fixture(new double[] { 0, 0, 0, 0, 0 }, -24, -18);
        var result = plan(fixture);
        assertEquals(ReferenceReason.ARRANGEMENT_REFERENCE, result.targets().get(1).reason());
        assertEquals(2.75, result.targets().get(1).confidenceWeightedDb(), 1e-10);
        assertEquals(-2.75, result.targets().get(3).confidenceWeightedDb(), 1e-10);
        assertEquals(.5, result.weightAt(1));
        assertEquals(.5, result.weightAt(3));
        assertEquals(0, result.targets().get(0).confidenceWeightedDb());
        assertEquals(0, result.targets().get(4).confidenceWeightedDb());
    }

    @Test void tinyDifferencesDoNotCreateMovement()
    {
        var result = plan(fixture(new double[5], -20.1, -20));
        assertEquals(ReferenceReason.ARRANGEMENT_REFERENCE, result.targets().get(1).reason());
        assertEquals(0, result.targets().get(1).confidenceWeightedDb());
        assertEquals(0, result.targets().get(3).confidenceWeightedDb());
    }

    @Test void distinctInstrumentationIsNotJoinedEvenWithIdenticalTonalContent()
    {
        var result = plan(fixture(new double[] { 0, 0, 0, 6, 0 }, -24, -18));
        assertEquals(ReferenceReason.NOT_COMPARABLE, result.targets().get(1).reason());
        assertEquals(0, result.targets().get(3).confidenceWeightedDb());
    }

    @Test void everyProtectionBitVetoesTheAdditionalRoute()
    {
        for (int bit = 0; bit < 8; bit++)
        {
            Fixture f = fixture(new double[5], -24, -18);
            Object[] protections = copy(f.protections);
            protections[1] = new ProtectionDecision(f.segments.get(1).id(), new ProtectionFlags(1L << bit));
            var result = new ArrangementReferencePlanner().supplement(f.reference, f.segments,
                    new FrozenList<ProtectionDecision>(protections), f.timeline, new CancellationToken());
            assertEquals(0, result.targets().get(1).confidenceWeightedDb(), "bit " + bit);
            assertEquals(0, result.targets().get(3).confidenceWeightedDb(), "no single-member reference");
        }
    }

    @Test void slowButConsistentCrescendoIsPreserved()
    {
        Fixture f = fixture(new double[5], -24, -18);
        Object[] segments = copy(f.segments);
        SegmentDescriptor original = f.segments.get(3);
        segments[3] = MusicalModelFixtures.withStats(original, original.regionalLoudness(),
                .08, 3, .9, 0, 0, 1);
        var result = new ArrangementReferencePlanner().supplement(f.reference,
                new FrozenList<SegmentDescriptor>(segments), f.protections, f.timeline, new CancellationToken());
        assertEquals(0, result.targets().get(3).confidenceWeightedDb());
    }

    @Test void existingRepetitionReferenceIsNeverOverwritten()
    {
        Fixture f = fixture(new double[5], -24, -18);
        Object[] targets = copy(f.reference.targets());
        var preserved = new ReferenceTarget(f.segments.get(1).id(), new MeasuredLoudness(true, -22),
                1, .7, .7, ReferenceReason.PAIR_REFERENCE);
        targets[1] = preserved;
        var reference = new ReferencePlan(new FrozenList<ReferenceTarget>(targets), new double[5]);
        var result = new ArrangementReferencePlanner().supplement(reference, f.segments,
                f.protections, f.timeline, new CancellationToken());
        assertSame(preserved, result.targets().get(1));
        assertEquals(ReferenceReason.NOT_COMPARABLE, result.targets().get(3).reason());
    }

    @Test void cancellationDoesNotReturnAPartialPlan()
    {
        Fixture f = fixture(new double[5], -24, -18);
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertNull(new ArrangementReferencePlanner().supplement(f.reference, f.segments,
                f.protections, f.timeline, token));
    }

    private record Fixture(ReferencePlan reference, FrozenList<SegmentDescriptor> segments,
                           FrozenList<ProtectionDecision> protections, ComparisonTimeline timeline) { }

    private static ReferencePlan plan(Fixture f)
    {
        return new ArrangementReferencePlanner().supplement(f.reference, f.segments,
                f.protections, f.timeline, new CancellationToken());
    }

    private static Object[] copy(FrozenList<?> values)
    {
        Object[] copy = new Object[values.size()];
        for (int i = 0; i < copy.length; i++) copy[i] = values.get(i);
        return copy;
    }

    private static Fixture fixture(double[] shapeOffset, double firstLevel, double secondLevel)
    {
        // Deliberately different durations and surrounding level jumps. The third
        // region is a protected break, not an anchor or an eligible cluster member.
        int[] seconds = { 8, 12, 8, 24, 8 };
        int total = Arrays.stream(seconds).sum();
        AudioFormat format = new AudioFormat(1000, 2, total * 1000L);
        short[] shorts = new short[total * 40 * 52], longs = new short[total * 8 * 36];
        byte[] shortFlags = new byte[total * 40], longFlags = new byte[total * 8];
        Arrays.fill(shortFlags, (byte)7);
        Arrays.fill(longFlags, (byte)7);
        Object[] segments = new Object[5], protections = new Object[5], targets = new Object[5];
        int time = 0;
        for (int i = 0; i < seconds.length; i++)
        {
            double level = i == 1 ? firstLevel : i == 3 ? secondLevel : -35;
            SegmentDescriptor base = MusicalModelFixtures.descriptor(i, level);
            SegmentDescriptor segment = new SegmentDescriptor(base.id(), new FrameRange(time * 1000L,
                    (time + seconds[i]) * 1000L), base.bins(), base.validBinMask(), base.regionalLoudness(),
                    0, 0, 0, 0, 0, 1, new BodyContextVector(1, 1, i == 1 ? 1 : .2, -.5, 3, 8), base.sketch());
            segments[i] = segment;
            protections[i] = new ProtectionDecision(base.id(), new ProtectionFlags(i == 2 ? 16 : 0));
            targets[i] = new ReferenceTarget(base.id(), MeasuredLoudness.absent(), 0, 0, 0, ReferenceReason.NOT_COMPARABLE);
            for (int row = time * 40; row < (time + seconds[i]) * 40; row++)
            {
                shorts[row * 52] = 1000;
                for (int band = 0; band < 8; band++)
                {
                    shorts[row * 52 + 36 + band] = (short)((-12 + shapeOffset[i]) * 256);
                    shorts[row * 52 + 44 + band] = 10 * 256;
                }
            }
            for (int row = time * 8; row < (time + seconds[i]) * 8; row++) longs[row * 36] = 1000;
            time += seconds[i];
        }
        return new Fixture(new ReferencePlan(new FrozenList<ReferenceTarget>(targets), new double[5]),
                new FrozenList<SegmentDescriptor>(segments), new FrozenList<ProtectionDecision>(protections),
                new ComparisonTimeline(format, shorts, longs, shortFlags, longFlags));
    }
}
