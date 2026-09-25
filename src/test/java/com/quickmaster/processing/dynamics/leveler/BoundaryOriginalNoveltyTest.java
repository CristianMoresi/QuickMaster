package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class BoundaryOriginalNoveltyTest
{
    @Test
    void chromaAndSpectralOnlyStepsReachContextsFromOriginalMultiscaleQ() throws Exception
    {
        for (boolean chroma : new boolean[] { true, false })
        {
            long hop = 32L, frames = 1025L * hop;
            FeatureTimeline features = MusicalContextFixtures.features(hop, frames, i -> {
                double[] c = new double[12], s = new double[8];
                c[chroma && i >= 512 ? 1 : 0] = 1d;
                s[0] = !chroma && i >= 512 ? -1d : 1d;
                return new StructuralBin(c, s, 0d, .75d);
            });
            FrozenList<SegmentDescriptor> descriptors = MusicalContextFixtures.build(64, frames, features,
                    MusicalContextFixtures.loudness(64, frames, new double[0], new BitSet()),
                    0L, 512L * hop, frames);
            assertNotNull(descriptors);
            double expected = chroma ? 5e11d : 3e11d;
            assertEquals(expected, descriptors.get(0).context().rightNoveltyMad(), 0d);
            assertEquals(expected, descriptors.get(1).context().leftNoveltyMad(), 0d);
            assertEquals(0L, Double.doubleToRawLongBits(descriptors.get(0).context().leftNoveltyMad()));
            assertEquals(0L, Double.doubleToRawLongBits(descriptors.get(1).context().rightNoveltyMad()));
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(original(features, frames)[511]));
        }
    }

    @Test
    void sharedHelperRejectsInvalidExtentAndCentersAndPreservesSingleHopLongExtremes() throws Exception
    {
        Method helper = BoundaryDetector.class.getDeclaredMethod("originalNovelty", FeatureTimeline.class,
                long.class, LevelerCalibrationProfile.class);
        assertEquals(Modifier.STATIC, helper.getModifiers());
        FeatureTimeline one = MusicalContextFixtures.features(1L, 1L, i -> MusicalContextFixtures.base());
        assertNull(original(null, 1));
        assertNull(original(one, 0));
        assertNull(original(one, 4_294_967_297L));
        assertEquals(0, original(one, 1).length);
        FeatureTimeline maximum = MusicalContextFixtures.features(Long.MAX_VALUE, Long.MAX_VALUE,
                i -> MusicalContextFixtures.base());
        assertEquals(0, original(maximum, Long.MAX_VALUE).length);
        FeatureTimeline bad = new FeatureTimeline(24_000L, new FrozenList<StructuralFrame>(new Object[] {
                new StructuralFrame(11999, new double[12], new double[8], 0d, .75d),
                new StructuralFrame(36000, new double[12], new double[8], 0d, .75d) }));
        assertNull(original(bad, 48_000L));
    }

    @Test
    void unalignedInternalBoundaryFailsButPartialEofMapsToPositiveZero()
    {
        long frames = 65L;
        FeatureTimeline features = MusicalContextFixtures.features(32L, frames, i -> MusicalContextFixtures.base());
        LoudnessTimeline loudness = MusicalContextFixtures.loudness(64, frames, new double[0], new BitSet());
        assertNull(MusicalContextFixtures.build(64, frames, features, loudness, 0L, 31L, frames));
        FrozenList<SegmentDescriptor> valid = MusicalContextFixtures.build(64, frames, features,
                loudness, 0L, 32L, frames);
        assertNotNull(valid);
        assertEquals(0L, Double.doubleToRawLongBits(valid.get(1).context().rightNoveltyMad()));
    }

    @Test
    void threeFrameTailRejectsTheFormerZeroCenterFixture()
    {
        FeatureTimeline bad = new FeatureTimeline(24_000L, new FrozenList<StructuralFrame>(new Object[] {
                new StructuralFrame(0L, new double[12], new double[8], 0d, 1d) }));
        assertNull(MusicalContextFixtures.build(48_000, 3L, bad,
                MusicalContextFixtures.loudness(48_000, 3L, new double[0], new BitSet()), 0L, 3L));
    }

    private static double[] original(FeatureTimeline features, long frames) throws Exception
    {
        Method method = BoundaryDetector.class.getDeclaredMethod("originalNovelty", FeatureTimeline.class,
                long.class, LevelerCalibrationProfile.class);
        method.setAccessible(true);
        return (double[]) method.invoke(null, features, frames, LevelerCalibrationProfile.V1);
    }
}
