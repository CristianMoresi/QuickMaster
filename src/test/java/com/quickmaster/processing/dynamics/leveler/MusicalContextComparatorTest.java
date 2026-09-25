package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalContextComparatorTest
{
    @Test
    void equalInteriorsDistinguishStableAndExteriorBoundaryContext() throws Exception
    {
        long hop = 24_000L, frames = 32L * hop;
        AudioFormat source = new AudioFormat(48_000, 1, frames);
        for (boolean outside : new boolean[] { false, true })
        {
            FeatureTimeline features = MusicalContextFixtures.features(hop, frames,
                    i -> outside && i == 3 ? MusicalContextFixtures.exterior() : MusicalContextFixtures.base());
            SegmentDescriptor a = MusicalContextFixtures.descriptor(0, features, new FrameRange(4 * hop, 10 * hop), frames);
            SegmentDescriptor b = MusicalContextFixtures.descriptor(1, features, new FrameRange(16 * hop, 22 * hop), frames);
            SimilarityScore score = MusicalContextFixtures.compare(a, b, source, features);
            assertEquals(outside ? SimilarityRejectionReason.UNSTABLE_BOUNDARY : SimilarityRejectionReason.NONE,
                    score.rejectionReason());
            assertEquals(32, score.validBins());
            if (outside) { assertEquals(0L, Double.doubleToRawLongBits(score.a())); assertEquals(0, score.chromaRotation()); }
            else { assertEquals(1d, score.h(), 0d); assertEquals(1d, score.t(), 0d); assertEquals(1d, score.a(), 0d); assertEquals(1d, score.c(), 0d); }
        }
    }

    @Test
    void comparisonRequiresTheDeclaredSourceClock() throws Exception
    {
        long hop = 24_000L, frames = 32L * hop;
        FeatureTimeline features = MusicalContextFixtures.features(hop, frames, i -> MusicalContextFixtures.base());
        SegmentDescriptor a = MusicalContextFixtures.descriptor(0, features, new FrameRange(4 * hop, 10 * hop), frames);
        SegmentDescriptor b = MusicalContextFixtures.descriptor(1, features, new FrameRange(16 * hop, 22 * hop), frames);
        assertEquals(SimilarityRejectionReason.NON_FINITE, MusicalContextFixtures.compare(a, b,
                new AudioFormat(96_000, 1, frames), features).rejectionReason());
        assertEquals(SimilarityRejectionReason.NON_FINITE, MusicalContextFixtures.compare(a, b,
                new AudioFormat(48_000, 1, frames - hop), features).rejectionReason());
        Method method = SegmentComparator.class.getMethod("compare", SegmentDescriptor.class,
                SegmentDescriptor.class, AudioFormat.class, FeatureTimeline.class, LevelerCalibrationProfile.class);
        assertEquals(SimilarityScore.class, method.getReturnType());
    }

    @Test
    void inclusiveCentralDurationDoesNotAuthorizeAnInvalidPerturbation() throws Exception
    {
        long hop = 24_000L, frames = 32L * hop;
        FeatureTimeline features = MusicalContextFixtures.features(hop, frames, i -> MusicalContextFixtures.base());
        SegmentDescriptor a = MusicalContextFixtures.descriptor(0, features, new FrameRange(4 * hop, 10 * hop), frames);
        SegmentDescriptor b = MusicalContextFixtures.descriptor(1, features, new FrameRange(16 * hop, 24 * hop), frames);
        assertEquals(SimilarityRejectionReason.UNSTABLE_BOUNDARY, MusicalContextFixtures.compare(a, b,
                new AudioFormat(48_000, 1, frames), features).rejectionReason());
    }

    @Test
    void structuralAreasCoverUnalignedRangesAndValidateOnlyTouchedCenters() throws Exception
    {
        long hop = 7L, frames = 31L;
        FeatureTimeline features = MusicalContextFixtures.features(hop, frames,
                i -> MusicalModelFixtures.bin(i, i, i / 4d));
        FrameRange range = new FrameRange(3L, 30L);
        FrozenList<StructuralBin> bins = MusicalContextFixtures.bins(features, range, frames);
        assertEquals(32, bins.size());
        for (int k = 0; k < 32; k++) for (int component = 0; component < 12; component++)
        {
            double expected = 0d;
            for (long n = 3; n < 30; n++)
            {
                long overlap = Math.max(0L, Math.min((k + 1L) * 27L, (n - 2L) * 32L)
                        - Math.max(k * 27L, (n - 3L) * 32L));
                if ((int) (n / hop) == component) expected += overlap;
            }
            assertEquals(expected / 27d, bins.get(k).chromaAt(component), 1e-15);
        }
        Object[] badRows = new Object[features.size()];
        for (int i = 0; i < badRows.length; i++) badRows[i] = features.frame(i);
        badRows[4] = new StructuralFrame(30L, new double[12], new double[8], 0d, .5d);
        FeatureTimeline bad = new FeatureTimeline(hop, new FrozenList<StructuralFrame>(badRows));
        assertNotNull(MusicalContextFixtures.bins(bad, new FrameRange(0, 7), frames));
        assertNull(MusicalContextFixtures.bins(bad, range, frames));
        StructuralBin[] oracle = MusicalContextOracle.bins(features, range, frames);
        for (int bin = 0; bin < 32; bin++)
        {
            for (int component = 0; component < 8; component++)
                assertEquals(oracle[bin].spectralAt(component), bins.get(bin).spectralAt(component), 1e-15);
            assertEquals(oracle[bin].onsetFlux(), bins.get(bin).onsetFlux(), 1e-15);
            assertEquals(oracle[bin].activity(), bins.get(bin).activity(), 1e-15);
        }
    }

    @Test
    void sourceRateChangesTheExactFirstBinMixtureWithoutChangingCentralDescriptors() throws Exception
    {
        long length = 7_056_000L, frames = 6 * length;
        for (int rate : new int[] { 44_100, 96_000 })
        {
            long hop = Math.round(rate * .5d), m = length / hop;
            FeatureTimeline features = MusicalContextFixtures.features(hop, frames,
                    i -> i == m - 1 ? MusicalContextFixtures.exterior() : MusicalContextFixtures.base());
            FrozenList<StructuralBin> changed = MusicalContextFixtures.bins(features, new FrameRange(length - hop, 2 * length), frames);
            assertEquals((m - 31d) / (m + 1d), changed.get(0).chromaAt(0), 1e-15);
            assertEquals(32d / (m + 1d), changed.get(0).chromaAt(1), 1e-15);
            assertTrue(changed.get(0).spectralAt(0) > 0);
            for (int i = 1; i < 32; i++) assertEquals(1d, changed.get(i).chromaAt(0), 0d);
        }
    }

    @Test
    void exactAreaOverflowAndMalformedMetadataFailClosedAndOneFramePerturbationCannotInvert() throws Exception
    {
        FeatureTimeline maximum = MusicalContextFixtures.features(Long.MAX_VALUE, Long.MAX_VALUE,
                i -> MusicalContextFixtures.base());
        assertNull(MusicalContextFixtures.bins(maximum, new FrameRange(0, Long.MAX_VALUE), Long.MAX_VALUE));
        assertNull(MusicalContextFixtures.bins(null, new FrameRange(0, 1), 1));
        FeatureTimeline features = MusicalContextFixtures.features(1, 4, i -> MusicalContextFixtures.base());
        assertNull(MusicalContextFixtures.bins(features, new FrameRange(0, 1), 3));
        assertNull(MusicalContextFixtures.bins(features, new FrameRange(0, 5), 4));
        SimilarityScore score = MusicalContextAdversarialTest.actual(features, 2, 4, new FrameRange(0, 1), new FrameRange(2, 3));
        assertEquals(SimilarityRejectionReason.UNSTABLE_BOUNDARY, score.rejectionReason());
        assertEquals(32, score.validBins()); assertEquals(0L, Double.doubleToRawLongBits(score.c()));
    }
}
