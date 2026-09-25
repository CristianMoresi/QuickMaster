package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.LayoutStatus;
import com.quickmaster.processing.dynamics.leveler.model.SegmentLayout;
import com.quickmaster.processing.dynamics.leveler.model.StructuralFrame;

class BoundaryDetectorTest
{
    private static final long HOP = 24_000L;

    @Test
    void ftMad0TieKeepsEarlierOfFourEqualMaxima()
    {
        double[] activity = new double[1025];
        for (int i = 0; i < activity.length; i++) activity[i] = i < 512 ? 0.2d : 0.8d;

        SegmentLayout layout = new BoundaryDetector().detect(timeline(activity), activity.length * HOP,
                LevelerCalibrationProfile.V1);

        assertEquals(LayoutStatus.READY, layout.status());
        assertEquals(2, layout.regions().size());
        assertEquals(511L * HOP, layout.regions().get(0).endExclusive());
        assertPartition(layout, activity.length * HOP);
    }

    @Test
    void ftExact64AcceptsTheInitialSelectionWithoutTruncation()
    {
        int count = 131_073;
        double[] activity = alternatingTransitions(count, 63);

        SegmentLayout layout = new BoundaryDetector().detect(timeline(activity), activity.length * HOP,
                LevelerCalibrationProfile.V1);

        assertEquals(LayoutStatus.READY, layout.status());
        assertEquals(64, layout.regions().size());
        for (int j = 1; j <= 63; j++)
        {
            long expectedBoundary = (j % 2 == 1 ? 1024L * j - 1L : 1024L * j - 2L) * HOP;
            assertEquals(expectedBoundary, layout.regions().get(j - 1).endExclusive(), "transition " + j);
        }
        assertPartition(layout, activity.length * HOP);
    }

    @Test
    void ftPersistent65FailsClosedInsteadOfTruncating()
    {
        double[] activity = alternatingTransitions(131_073, 64);

        SegmentLayout layout = new BoundaryDetector().detect(timeline(activity), activity.length * HOP,
                LevelerCalibrationProfile.V1);

        assertEquals(LayoutStatus.TOO_MANY_SEGMENTS, layout.status());
        assertEquals(0, layout.regions().size());
    }

    @Test
    void ftRetryOrderAcceptsP975ResultOf51BeforeP99ResultOf31()
    {
        int count = 30_000;
        double[] activity = new double[count];
        for (int i = 0; i < count; i++) activity[i] = 0.2d;
        for (int j = 0; j < 80; j++)
        {
            int transition = 1000 + 300 * j;
            double high = j < 30 ? 0.8d : j < 50 ? 0.6d : 0.4d;
            double after = j % 2 == 0 ? high : 0.2d;
            int end = j == 79 ? count : 1000 + 300 * (j + 1);
            for (int i = transition; i < end && i < count; i++) activity[i] = after;
        }

        SegmentLayout layout = new BoundaryDetector().detect(timeline(activity), activity.length * HOP,
                LevelerCalibrationProfile.V1);

        assertEquals(LayoutStatus.READY, layout.status());
        assertEquals(51, layout.regions().size());
        for (int j = 0; j < 50; j++)
        {
            long transition = 1000L + 300L * j;
            long expected = (j % 2 == 0 ? transition - 1L : transition - 2L) * HOP;
            assertEquals(expected, layout.regions().get(j).endExclusive(), "transition " + j);
        }
        assertPartition(layout, activity.length * HOP);
    }

    @Test
    void sameTimelineAcceptsBothAliasedTailExtentsWithoutPromotion()
    {
        FeatureTimeline features = atCenters(HOP, 11_999L, 35_999L);
        for (long frames : new long[] { 47_999L, 48_000L })
        {
            SegmentLayout layout = new BoundaryDetector().detect(features, frames,
                    LevelerCalibrationProfile.V1);
            assertEquals(1, layout.regions().size());
            assertPartition(layout, frames);
        }
    }

    @Test
    void nullNonpositiveCardinalityAndNoncanonicalCentersFailEmpty()
    {
        BoundaryDetector detector = new BoundaryDetector();
        FeatureTimeline single = atCenters(1L, 0L);
        assertInsufficient(detector.detect(null, 1L, LevelerCalibrationProfile.V1));
        assertInsufficient(detector.detect(single, 1L, null));
        for (long frames : new long[] { Long.MIN_VALUE, -1L, 0L, 2L, 4_294_967_297L,
                4_296_015_872L, 5_368_709_119L, Long.MAX_VALUE })
        {
            assertInsufficient(detector.detect(single, frames, LevelerCalibrationProfile.V1));
        }
        assertInsufficient(detector.detect(atCenters(HOP), HOP, LevelerCalibrationProfile.V1));
        assertInsufficient(detector.detect(atCenters(HOP, 12_000L, 35_999L), 48_000L,
                LevelerCalibrationProfile.V1));
        assertInsufficient(detector.detect(atCenters(HOP, 11_999L, 36_000L), 48_000L,
                LevelerCalibrationProfile.V1));
        assertInsufficient(detector.detect(atCenters(1L, 0L, 1L), 1L,
                LevelerCalibrationProfile.V1));
    }

    @Test
    void hostileNonpositiveHopIsRejectedBeforeDivision() throws Exception
    {
        Field hop = FeatureTimeline.class.getDeclaredField("hopFrames");
        hop.setAccessible(true);
        for (long value : new long[] { 0L, -1L, Long.MIN_VALUE })
        {
            FeatureTimeline features = atCenters(1L, 0L);
            hop.setLong(features, value);
            assertInsufficient(new BoundaryDetector().detect(features, 1L,
                    LevelerCalibrationProfile.V1));
        }
    }

    @Test
    void oneFrameAndMaximumLongExtentDoNotNeedNoveltyRadii()
    {
        assertPartition(new BoundaryDetector().detect(atCenters(1L, 0L), 1L,
                LevelerCalibrationProfile.V1), 1L);
        assertPartition(new BoundaryDetector().detect(
                atCenters(Long.MAX_VALUE, 4_611_686_018_427_387_903L), Long.MAX_VALUE,
                LevelerCalibrationProfile.V1), Long.MAX_VALUE);
    }

    @Test
    void everySmallTailParityPreservesItsExplicitFinalFrame()
    {
        for (long hop = 1L; hop <= 16L; hop++)
        {
            for (long frames = 1L; frames <= 64L; frames++)
            {
                int count = (int) (1L + (frames - 1L) / hop);
                long[] centers = new long[count];
                for (int i = 0; i < count; i++)
                {
                    long start = i * hop;
                    centers[i] = start + (Math.min(hop, frames - start) - 1L) / 2L;
                }
                assertPartition(new BoundaryDetector().detect(atCenters(hop, centers), frames,
                        LevelerCalibrationProfile.V1), frames);
            }
        }
    }

    @Test
    void detectorHasOnlyThePackagePrivateExplicitExtentApi() throws Exception
    {
        assertTrue(Modifier.isFinal(BoundaryDetector.class.getModifiers()));
        assertFalse(Modifier.isPublic(BoundaryDetector.class.getModifiers()));
        assertEquals(0, BoundaryDetector.class.getDeclaredFields().length);
        int detectMethods = 0;
        for (Method method : BoundaryDetector.class.getDeclaredMethods())
        {
            assertFalse(method.getName().equals("inferTotalFrames"));
            if (!method.getName().equals("detect")) continue;
            detectMethods++;
            assertEquals(0, method.getModifiers());
            assertFalse(method.isSynthetic());
            assertFalse(method.isBridge());
        }
        assertEquals(1, detectMethods);
        assertEquals(SegmentLayout.class, BoundaryDetector.class.getDeclaredMethod("detect",
                FeatureTimeline.class, long.class, LevelerCalibrationProfile.class).getReturnType());
    }

    @Test
    void positiveThresholdEqualityIsNotACandidateAndPercentilesStayLower() throws Exception
    {
        Method select = BoundaryDetector.class.getDeclaredMethod("select", double[].class, double.class,
                long.class);
        select.setAccessible(true);
        assertEquals(0, ((int[]) select.invoke(null, new double[] { 0.0d, 6.0d, 0.0d }, 6.0d, HOP)).length);
        assertEquals(1, ((int[]) select.invoke(null, new double[] { 0.0d, Math.nextUp(6.0d), 0.0d },
                6.0d, HOP)).length);
        Method percentile = BoundaryDetector.class.getDeclaredMethod("percentileLower", double[].class,
                double.class);
        percentile.setAccessible(true);
        assertEquals(2.0d, (double) percentile.invoke(null, new double[] { 4.0d, 1.0d, 3.0d, 2.0d }, 0.5d));
        assertEquals(4.0d, (double) percentile.invoke(null, new double[] { 4.0d, 1.0d, 3.0d, 2.0d }, 0.95d));
    }

    @Test
    void rawNoveltyUsesBothHalfOpenWindowsAndTheirLowerMedians() throws Exception
    {
        Method novelty = BoundaryDetector.class.getDeclaredMethod("rawNovelty", FeatureTimeline.class,
                int.class, int.class);
        novelty.setAccessible(true);
        FeatureTimeline features = timeline(new double[] {
                0.1d, 0.2d, 0.8d, 0.9d, 0.7d, 0.7d, 0.7d, 0.7d, 0.99d });
        // At k=4,r=4 the windows are [0,4) and [4,8): lower medians .2 and .7.
        // Omitting the first pre-frame changes its median to .8 and therefore Q.
        double expected = 0.1d * StrictMath.abs(0.2d - 0.7d);
        assertEquals(expected, (double) novelty.invoke(null, features, 4, 4), 1.0e-16d);
    }

    private static FeatureTimeline atCenters(long hop, long... centers)
    {
        Object[] frames = new Object[centers.length];
        for (int i = 0; i < frames.length; i++)
        {
            frames[i] = new StructuralFrame(centers[i], new double[12], new double[8], 0.0d, 0.0d);
        }
        return new FeatureTimeline(hop, new FrozenList<StructuralFrame>(frames));
    }

    private static void assertInsufficient(SegmentLayout layout)
    {
        assertEquals(LayoutStatus.INSUFFICIENT_FEATURES, layout.status());
        assertEquals(0, layout.regions().size());
    }

    private static void assertPartition(SegmentLayout layout, long frames)
    {
        assertEquals(LayoutStatus.READY, layout.status());
        assertTrue(layout.regions().size() >= 1 && layout.regions().size() <= 64);
        long end = 0L;
        for (int i = 0; i < layout.regions().size(); i++)
        {
            assertEquals(end, layout.regions().get(i).startInclusive());
            assertTrue(layout.regions().get(i).endExclusive() > end);
            end = layout.regions().get(i).endExclusive();
        }
        assertEquals(frames, end);
    }

    private static double[] alternatingTransitions(int count, int transitions)
    {
        double[] activity = new double[count];
        for (int i = 0; i < count; i++)
        {
            int transitionCount = Math.min(transitions, i / 1024);
            activity[i] = (transitionCount & 1) == 0 ? 0.2d : 0.8d;
        }
        return activity;
    }

    private static FeatureTimeline timeline(double[] activity)
    {
        Object[] frames = new Object[activity.length];
        double[] chroma = LevelerModelFixtures.unitChroma();
        double[] spectral = LevelerModelFixtures.unitSpectral();
        for (int i = 0; i < activity.length; i++)
        {
            long start = i * HOP;
            frames[i] = new StructuralFrame(start + (HOP - 1L) / 2L,
                    chroma, spectral, 0.0d, activity[i]);
        }
        return new FeatureTimeline(HOP, new FrozenList<StructuralFrame>(frames));
    }
}
