package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalBodyRegressionTest
{
    @Test
    void productionAmbiguityPredicateUsesInclusiveIeeeLimits() throws Exception
    {
        Method predicate = BodyContextGate.class.getDeclaredMethod("ambiguous", double.class, double.class);
        predicate.setAccessible(true);
        assertEquals(true, predicate.invoke(null, Math.nextDown(1e-4d), Math.nextDown(.05d)));
        assertEquals(true, predicate.invoke(null, 1e-4d, .05d));
        assertEquals(false, predicate.invoke(null, Math.nextUp(1e-4d), .05d));
        assertEquals(false, predicate.invoke(null, 1e-4d, Math.nextUp(.05d)));
        assertEquals(false, predicate.invoke(null, Double.NaN, .05d));
        assertEquals(false, predicate.invoke(null, 1e-4d, Double.NaN));
    }

    @Test
    void n08VetoSurvivesPerfectScoresAndSixLuGap()
    {
        double[][] x = new double[2][2048];
        x[0][100] = .5d; x[0][701] = -.25d;
        x[1][311] = .375d; x[1][1703] = -.125d;
        double[][] y = new double[2][2048];
        for (int c = 0; c < 2; c++) for (int k = 0; k < 2048; k++) y[c][k] = .5d * x[c][k];
        BodyContextVector context = new BodyContextVector(1, 1, 0, 0, .25d, .5d);
        SegmentDescriptor a = MusicalModelFixtures.withContext(MusicalModelFixtures.descriptor(1, -20),
                context, new PcmSketch(2, 2048, x));
        SegmentDescriptor b = MusicalModelFixtures.withContext(MusicalModelFixtures.descriptor(3, -26),
                context, new PcmSketch(2, 2048, y));
        SimilarityScore structural = MusicalModelFixtures.centralComparison(a, b, LevelerCalibrationProfile.V1);
        assertEquals(1d, structural.h()); assertEquals(1d, structural.t());
        assertEquals(1d, structural.a()); assertEquals(1d, structural.c());
        assertEquals(6d, a.regionalLoudness().lufs() - b.regionalLoudness().lufs());
        BodyContextGate gate = new BodyContextGate();
        assertArrayEquals(new double[] { 0, 2, 0, 0 },
                gate.assess(a.sketch(), b.sketch(), context, context), 1e-15d);
        assertEquals(SimilarityRejectionReason.INTENT_UNIDENTIFIABLE, gate.compare(a, b));
        SimilarityScore rejected = new SimilarityScore(0, 0, 0, 0, 0, 0, gate.compare(a, b));
        GroupingResult grouping = new ComparableGroupBuilder().build(
                new FrozenList<SegmentDescriptor>(new Object[] { a, b }),
                new SimilarityMatrix(2, new FrozenList<SimilarityScore>(new Object[] { rejected })),
                LevelerCalibrationProfile.V1);
        assertEquals(0, grouping.groups().size()); assertEquals(0, grouping.pairs().size());
    }

    @Test
    void lagsUseSignedAlignmentAndPreferZeroThenNegativeInEqualResiduals()
    {
        BodyContextGate gate = new BodyContextGate();
        BodyContextVector context = new BodyContextVector(1, 1, 0, 0, 0, 0);
        for (int lag = -4; lag <= 4; lag++)
        {
            double[][] x = new double[2][2048];
            double[][] y = new double[2][2048];
            x[0][100] = .5d; x[1][750] = -.25d;
            y[0][100 + lag] = .25d; y[1][750 + lag] = -.125d;
            double[] result = gate.assess(new PcmSketch(2, 2048, x), new PcmSketch(2, 2048, y),
                    context, context);
            assertNotNull(result); assertEquals(lag, result[0]); assertEquals(2d, result[1]);
            assertEquals(0d, result[2]);
        }
        double[][] x = new double[1][2048];
        double[][] same = new double[1][2048];
        double[][] odd = new double[1][2048];
        for (int i = 0; i < 2048; i++) { x[0][i] = i % 2 == 0 ? .5d : 0;
            same[0][i] = x[0][i]; odd[0][i] = i % 2 == 1 ? .25d : 0; }
        assertEquals(0d, gate.assess(new PcmSketch(1, 2048, x), new PcmSketch(1, 2048, same),
                context, context)[0]);
        assertEquals(-1d, gate.assess(new PcmSketch(1, 2048, x), new PcmSketch(1, 2048, odd),
                context, context)[0]);
        assertNull(gate.assess(new PcmSketch(1, 2048, new double[1][2048]),
                new PcmSketch(1, 2048, same), context, context));
    }

    @Test
    void contextMismatchPrecedesSketchDegeneracy()
    {
        SegmentDescriptor a = MusicalModelFixtures.descriptor(0, -20);
        SegmentDescriptor b = MusicalModelFixtures.withContext(MusicalModelFixtures.descriptor(1, -20),
                new BodyContextVector(1.5d, 1, 0, 0, .25d, .5d),
                new PcmSketch(2, 2048, new double[2][2048]));
        assertEquals(SimilarityRejectionReason.CONTEXT_MISMATCH, new BodyContextGate().compare(a, b));
    }

    @Test
    void everyHardFlagRemainsIrrevocableAndBodyThresholdsAreExact()
    {
        SegmentDescriptor center = MusicalModelFixtures.descriptor(1, -20);
        FrozenList<SegmentDescriptor> all = new FrozenList<SegmentDescriptor>(new Object[] {
                MusicalModelFixtures.descriptor(0, -20), center, MusicalModelFixtures.descriptor(2, -20) });
        BodyContextGate gate = new BodyContextGate();
        for (int bit = 0; bit < 8; bit++)
            assertEquals(SimilarityRejectionReason.PROTECTED,
                    gate.evaluate(1, all, protections(all, 1L << bit, 0L)).rejectionReason());
        assertTrue(gate.evaluate(1, all, protections(all, 0L, 0L)).eligible());
        assertFalse(gate.evaluate(1, all, protections(all, 0L, 1L << 5)).eligible());
        double[][] stats = {
            { 0, 0, 0, .15d, .80d }, { .10d, 0, 0, 0, .9d },
            { 0, 2d, 0, 0, .9d }, { 0, 0, .01d, 0, .9d },
            { 0, 0, 0, Math.nextUp(.15d), .9d }, { 0, 0, 0, 0, Math.nextDown(.80d) }
        };
        for (int i = 0; i < stats.length; i++)
        {
            double[] s = stats[i];
            SegmentDescriptor changed = MusicalModelFixtures.withStats(center, center.regionalLoudness(),
                    s[0], s[1], 0, s[2], s[3], s[4]);
            FrozenList<SegmentDescriptor> test = new FrozenList<SegmentDescriptor>(new Object[] {
                    all.get(0), changed, all.get(2) });
            assertEquals(i == 0, gate.evaluate(1, test, protections(test, 0L, 0L)).eligible(), "case " + i);
        }
    }

    private static FrozenList<ProtectionDecision> protections(FrozenList<SegmentDescriptor> all,
                                                               long current, long previous)
    {
        return new FrozenList<ProtectionDecision>(new Object[] {
                new ProtectionDecision(all.get(0).id(), new ProtectionFlags(previous)),
                new ProtectionDecision(all.get(1).id(), new ProtectionFlags(current)),
                new ProtectionDecision(all.get(2).id(), new ProtectionFlags(0L)) });
    }
}
