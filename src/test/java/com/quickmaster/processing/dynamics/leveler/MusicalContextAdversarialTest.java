package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalContextAdversarialTest
{
    @Test void ieeePredicateIsInclusiveWithoutAnEpsilon() throws Exception { predicate(); }
    @Test void exteriorAtTheLastSlotCannotBeOmitted() throws Exception { lastSlot(); }
    @Test void maskAndRotationAreRecomputedForEachVariant() throws Exception { mask(); rotation(); }
    @Test void everyVariantIsComparedToAZeroNotThePreviousScore() throws Exception { previous(); }

    static void predicate() throws Exception
    {
        Method predicate = SegmentComparator.class.getDeclaredMethod("stableAlignment", double.class, double.class);
        predicate.setAccessible(true);
        assertEquals(true, predicate.invoke(null, 0d, Math.nextDown(.05d)));
        assertEquals(true, predicate.invoke(null, 0d, .05d));
        assertEquals(false, predicate.invoke(null, 0d, Math.nextUp(.05d)));
        assertEquals(true, predicate.invoke(null, .05d, 0d));
        assertEquals(false, predicate.invoke(null, 1d, .95d));
        assertEquals(false, predicate.invoke(null, Double.NaN, 0d));
        assertEquals(false, predicate.invoke(null, 0d, Double.POSITIVE_INFINITY));
    }

    static void lastSlot() throws Exception
    {
        long hop = 24_000, frames = 32 * hop;
        FeatureTimeline features = MusicalContextFixtures.features(hop, frames,
                i -> i == 22 ? MusicalContextFixtures.exterior() : MusicalContextFixtures.base());
        FrameRange a = new FrameRange(4 * hop, 10 * hop), b = new FrameRange(16 * hop, 22 * hop);
        var oracle = MusicalContextOracle.compare(features, new AudioFormat(48_000, 1, frames), a, b);
        for (int slot = 0; slot < 7; slot++) assertEquals(1d, oracle.variants().get(slot).a(), 0d);
        assertEquals(283d / 320d, oracle.variants().get(7).a(), 1e-15);
        assertEquals(SimilarityRejectionReason.UNSTABLE_BOUNDARY, actual(features, 48_000, frames, a, b).rejectionReason());
    }

    static void mask() throws Exception
    {
        long hop = 24_000, frames = 32 * hop;
        FeatureTimeline features = MusicalContextFixtures.features(hop, frames, i -> {
            if (i != 3) return MusicalContextFixtures.base();
            double[] c = new double[12]; c[0] = 1;
            return new StructuralBin(c, new double[8], 0, .75);
        });
        FrameRange a = new FrameRange(4 * hop, 10 * hop), b = new FrameRange(16 * hop, 22 * hop);
        var oracle = MusicalContextOracle.compare(features, new AudioFormat(48_000, 1, frames), a, b);
        assertEquals(32, oracle.central().count()); assertEquals(28, oracle.variants().get(0).count());
        assertEquals(.875d, oracle.variants().get(0).a(), 0d);
        assertEquals(SimilarityRejectionReason.UNSTABLE_BOUNDARY, actual(features, 48_000, frames, a, b).rejectionReason());
    }

    static void rotation() throws Exception
    {
        long hop = 24_000, frames = 100 * hop;
        FeatureTimeline features = MusicalContextFixtures.features(hop, frames, i -> {
            int pitch = i == 59 || i >= 86 && i < 92 ? 3 : i >= 60 && i < 86 ? 2 : 0;
            double[] c = new double[12], s = new double[8]; c[pitch] = 1; s[0] = 1;
            return new StructuralBin(c, s, 0, .75);
        });
        FrameRange a = new FrameRange(4 * hop, 36 * hop), b = new FrameRange(60 * hop, 92 * hop);
        var oracle = MusicalContextOracle.compare(features, new AudioFormat(48_000, 1, frames), a, b);
        assertEquals(SimilarityRejectionReason.NONE, oracle.central().reason()); assertEquals(2, oracle.central().rotation());
        assertEquals(SimilarityRejectionReason.AMBIGUOUS, oracle.variants().get(4).reason());
        assertEquals(SimilarityRejectionReason.UNSTABLE_BOUNDARY, actual(features, 48_000, frames, a, b).rejectionReason());
    }

    static void previous() throws Exception
    {
        // Preserved discovery: seed 0x4150524556, trial 3. No adaptive search during verification.
        double[] activity = { 0, 0, 1, 0, .5, 0, 0, .5, 1, 0, 0, .5, .5, .5, 1, 1,
                0, 0, 1, 0, 0, 0, 1, 1, .5, .5, 1, .5, 0, .5, 1, 1 };
        long hop = 24_000, frames = 32 * hop;
        FrameRange a = new FrameRange(4 * hop, 10 * hop), b = new FrameRange(16 * hop, 22 * hop);
        AudioFormat source = new AudioFormat(48_000, 1, frames);
        FeatureTimeline features = MusicalContextFixtures.features(hop, frames,
                i -> MusicalModelFixtures.bin(0, 0, activity[i]));
        var oracle = MusicalContextOracle.compare(features, source, a, b);
        double[] expected = { .9351851851851851, .9548148148148148, .8866666666666666,
                .9605820105820105, .9515873015873015, .9125925925925926, .9522222222222222, .9037037037037037 };
        assertEquals(.9333333333333333, oracle.central().a(), 1e-15);
        for (int slot = 0; slot < 8; slot++)
        {
            assertEquals(expected[slot], oracle.variants().get(slot).a(), 1e-15);
            assertTrue(Math.abs(expected[slot] - oracle.central().a()) <= .05);
        }
        assertTrue(Math.abs(expected[2] - expected[1]) > .05, "Aprev would reject slot 2");
        assertEquals(SimilarityRejectionReason.NONE, actual(features, 48_000, frames, a, b).rejectionReason());
    }

    static SimilarityScore actual(FeatureTimeline features, int rate, long frames, FrameRange a, FrameRange b) throws Exception
    {
        return new SegmentComparator().compare(MusicalContextFixtures.descriptor(0, features, a, frames),
                MusicalContextFixtures.descriptor(1, features, b, frames), new AudioFormat(rate, 1, frames), features, LevelerCalibrationProfile.V1);
    }
}
