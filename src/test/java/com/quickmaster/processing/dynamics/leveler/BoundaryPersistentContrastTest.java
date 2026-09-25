package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.IntToDoubleFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class BoundaryPersistentContrastTest
{
    private static final long HOP = 24_000L;

    @Test
    void weakerSustainedTonalChangeSurvivesStrongerTrackChanges()
    {
        FeatureTimeline features = tonal(256, i -> i < 40 ? .1d : i < 88 ? 1.1d
                : i < 136 ? .1d : i < 184 ? 1.1d : i < 216 ? .5d : .1d, i -> 0d);
        SegmentLayout layout = new BoundaryDetector().detect(features, 256L * HOP,
                LevelerCalibrationProfile.V1);
        assertEquals(LayoutStatus.READY, layout.status());
        for (int boundary : new int[] { 40, 88, 136, 184, 216 })
            assertTrue(hasBoundaryNear(layout, boundary * HOP, HOP), "Missing sustained change " + boundary);
        assertEquals(256L * HOP, layout.regions().get(layout.regions().size() - 1).endExclusive());
    }

    @Test
    void shortTonalImpulseAndFluxOnlySpikeDoNotAddPersistentBoundaries() throws Exception
    {
        for (int duration : new int[] { 1, 2 })
        {
            FeatureTimeline features = tonal(160, i -> i >= 80 && i < 80 + duration ? 1.1d : .1d,
                    i -> i == 80 ? 1.0e9d : 0d);
            assertOriginalSelectionUnchanged(features, 160);
        }
        FeatureTimeline flux = tonal(160, i -> .1d, i -> i == 80 ? 1.0e12d : 0d);
        assertOriginalSelectionUnchanged(flux, 160);
    }

    @Test
    void smoothTonalEvolutionHasNoAdditionalPersistentBoundary() throws Exception
    {
        FeatureTimeline features = tonal(160, i -> .1d + i * .004d, i -> 0d);
        assertOriginalSelectionUnchanged(features, 160);
    }

    private static void assertOriginalSelectionUnchanged(FeatureTimeline features, int count) throws Exception
    {
        // Original Q remains an independent contract: compare with the unchanged selector.
        double[] novelty = BoundaryDetector.originalNovelty(features, count * HOP, LevelerCalibrationProfile.V1);
        java.lang.reflect.Method percentile = BoundaryDetector.class.getDeclaredMethod("percentileLower", double[].class, double.class);
        java.lang.reflect.Method select = BoundaryDetector.class.getDeclaredMethod("select", double[].class, double.class, long.class);
        percentile.setAccessible(true); select.setAccessible(true);
        double median = (double) percentile.invoke(null, novelty, .5d);
        double[] deviations = novelty.clone();
        for (int i = 0; i < deviations.length; i++) deviations[i] = Math.abs(deviations[i] - median);
        double mad = (double) percentile.invoke(null, deviations, .5d);
        double threshold = mad > 0 ? median + 3 * mad : (double) percentile.invoke(null, novelty, .95d);
        int[] original = (int[]) select.invoke(null, novelty, threshold, HOP);
        SegmentLayout actual = new BoundaryDetector().detect(features, count * HOP, LevelerCalibrationProfile.V1);
        assertEquals(original.length + 1, actual.regions().size());
        for (int i = 0; i < original.length; i++) assertEquals(original[i] * HOP, actual.regions().get(i).endExclusive());
    }

    @Test
    void frozenMusicalRepeatsRetainCoverageAndPurityAtBothRatesAndLayouts()
    {
        for (MusicalPcmFixture.Case test : MusicalPcmFixture.catalog())
        {
            if (!test.key().startsWith("P0")) continue;
            for (int rate : new int[] { 44_100, 48_000 }) for (int channels : new int[] { 1, 2 })
            {
                MusicalPcmFixture.Clip clip = MusicalPcmFixture.generate(test, rate, channels);
                CancellationToken cancellation = new CancellationToken();
                LoudnessTimeline loudness = new LoudnessAnalyzer().analyze(clip.pcm(), clip.format(), cancellation);
                assertNotNull(loudness);
                FeatureTimeline features = new StructuralFeatureExtractor().extract(clip.pcm(), clip.format(), loudness, cancellation);
                assertNotNull(features);
                SegmentLayout layout = new BoundaryDetector().detect(features, clip.format().frames(), LevelerCalibrationProfile.V1);
                assertEquals(LayoutStatus.READY, layout.status());
                for (MusicalPcmFixture.Truth truth : clip.truth())
                {
                    if (!truth.label().equals(test.first()) && !truth.label().equals(test.second()) && !truth.label().equals(test.third())) continue;
                    long bestOverlap = -1; FrameRange best = null;
                    for (int i = 0; i < layout.regions().size(); i++)
                    {
                        FrameRange region = layout.regions().get(i);
                        long overlap = Math.max(0L, Math.min(truth.end(), region.endExclusive()) - Math.max(truth.start(), region.startInclusive()));
                        if (overlap > bestOverlap) { bestOverlap = overlap; best = region; }
                    }
                    String label = test.key() + " " + rate + " " + channels + " " + truth.label();
                    assertTrue(bestOverlap / (double) (truth.end() - truth.start()) >= .8d, label + " coverage");
                    assertTrue(bestOverlap / (double) best.lengthFrames() >= .8d, label + " purity");
                }
            }
        }
    }

    @Test
    void frozenGainScaledRepeatsStayAmbiguous()
    {
        MusicalPcmFixture.Case test = MusicalPcmFixture.catalog().stream()
                .filter(c -> c.key().equals("N08_GAIN_SCALED_INTENT")).findFirst().orElseThrow();
        for (int rate : new int[] { 44_100, 48_000 }) for (int channels : new int[] { 1, 2 })
        {
            MusicalPcmFixture.Clip clip = MusicalPcmFixture.generate(test, rate, channels);
            ShadowAnalysisSnapshot snapshot = new LevelerAnalysisEngine().analyzeShadow(
                    clip.pcm(), clip.format(), new CancellationToken());
            assertNotNull(snapshot);
            for (int i = 0; i < snapshot.cache().referencePlan().size(); i++)
                assertEquals(0.0d, snapshot.cache().referencePlan().targets().get(i).confidenceWeightedDb(),
                        "Gain-only repetition must abstain: " + rate + " " + channels + " region " + i);
        }
    }

    @Test
    void noveltyDifferenceCannotIdentifyGainIntentButStillGatesComparability()
    {
        BodyContextVector first = new BodyContextVector(1d, 1d, 0d, 0d, .1d, .1d);
        BodyContextVector second = new BodyContextVector(1d, 1d, 0d, 0d, 1.5d, .1d);
        PcmSketch a = LevelerModelFixtures.impulseSketch(1, 1d, 0);
        PcmSketch b = LevelerModelFixtures.impulseSketch(1, .5d, 0);
        BodyContextGate gate = new BodyContextGate();
        double[] assessment = gate.assess(a, b, first, second);
        assertNotNull(assessment);
        assertEquals(0d, assessment[2], 1e-15d);
        assertEquals(0d, assessment[3], 0d);
        assertEquals(SimilarityRejectionReason.CONTEXT_MISMATCH, gate.compare(
                LevelerModelFixtures.descriptor(0, -18d, LevelerModelFixtures.unitChroma(), LevelerModelFixtures.unitSpectral(), 1d, first, a),
                LevelerModelFixtures.descriptor(1, -24d, LevelerModelFixtures.unitChroma(), LevelerModelFixtures.unitSpectral(), 1d, second, b)));
    }

    @Test
    void frozenSameContentFadeHasItsOwnProtectedRegion()
    {
        MusicalPcmFixture.Case test = MusicalPcmFixture.catalog().stream()
                .filter(c -> c.key().equals("N04_OUTRO_FADE")).findFirst().orElseThrow();
        for (int rate : new int[] { 44_100, 48_000 }) for (int channels : new int[] { 1, 2 })
        {
            MusicalPcmFixture.Clip clip = MusicalPcmFixture.generate(test, rate, channels);
            ShadowAnalysisSnapshot snapshot = new LevelerAnalysisEngine().analyzeShadow(
                    clip.pcm(), clip.format(), new CancellationToken());
            assertNotNull(snapshot);
            MusicalPcmFixture.Truth fade = clip.truth().stream().filter(t -> t.label().equals("subject")).findFirst().orElseThrow();
            long bestOverlap = -1; int best = -1;
            for (int i = 0; i < snapshot.cache().descriptors().size(); i++)
            {
                FrameRange range = snapshot.cache().descriptors().get(i).range();
                long overlap = Math.max(0L, Math.min(fade.end(), range.endExclusive()) - Math.max(fade.start(), range.startInclusive()));
                if (overlap > bestOverlap) { bestOverlap = overlap; best = i; }
            }
            String label = "same-content fade " + rate + " " + channels;
            FrameRange range = snapshot.cache().descriptors().get(best).range();
            assertTrue(bestOverlap / (double) (fade.end() - fade.start()) >= .8d, label + " coverage");
            assertTrue(bestOverlap / (double) range.lengthFrames() >= .8d, label + " purity");
            assertTrue(snapshot.cache().protections().get(best).flags().containsBit(3), label + " fade protection");
            assertEquals(0d, snapshot.cache().referencePlan().targets().get(best).confidenceWeightedDb(), label + " gain");
        }
    }

    @Test
    void sustainedChangesAboveCapacityFailTheWholeLayout()
    {
        FeatureTimeline features = tonal(1_600, i -> (i / 24) % 2 == 0 ? .1d : 1.1d, i -> 0d);
        SegmentLayout layout = new BoundaryDetector().detect(features, 1_600L * HOP, LevelerCalibrationProfile.V1);
        assertEquals(LayoutStatus.TOO_MANY_SEGMENTS, layout.status());
        assertEquals(0, layout.regions().size());
    }

    @Test
    void loudnessRefinementKeepsStationaryContinuousRampAndIsolatedLevelStepWhole()
    {
        for (DoubleUnaryOperator level : new DoubleUnaryOperator[] {
                t -> -24d, t -> -36d + .3d * t, t -> t < 32d ? -24d : -18d,
                t -> t >= 32d && t < 32.1d ? -12d : -24d })
        {
            SegmentLayout layout = refineSynthetic(64, level);
            assertEquals(LayoutStatus.READY, layout.status());
            assertEquals(1, layout.regions().size());
            assertEquals(64L * 48_000L, layout.regions().get(0).endExclusive());
        }
    }

    @Test
    void loudnessRampOverflowIsAnEmptyWholeTrackFallback()
    {
        SegmentLayout layout = refineSynthetic(1_600, t -> {
            double phase = t % 48d;
            if (phase < 12d) return -30d;
            if (phase < 24d) return -30d + .5d * (phase - 12d);
            if (phase < 36d) return -24d;
            return -24d - .5d * (phase - 36d);
        });
        assertEquals(LayoutStatus.TOO_MANY_SEGMENTS, layout.status());
        assertEquals(0, layout.regions().size());
    }

    private static SegmentLayout refineSynthetic(int seconds, DoubleUnaryOperator level)
    {
        long total = seconds * 48_000L;
        FeatureTimeline features = tonal(seconds * 2, i -> .1d, i -> 0d);
        int count = seconds * 10;
        double[] momentary = new double[count], shortTerm = new double[count];
        BitSet momentaryValid = new BitSet(count), shortTermValid = new BitSet(count);
        for (int i = 0; i < count; i++)
        {
            if (i * 4_800L + 19_200L <= total) { momentary[i] = .01d; momentaryValid.set(i); }
            if (i * 4_800L + 144_000L <= total) { shortTerm[i] = level.applyAsDouble(i * .1d + 1.5d); shortTermValid.set(i); }
        }
        LoudnessTimeline loudness = new LoudnessTimeline(19_200, 144_000, 4_800, momentary,
                momentaryValid, shortTerm, shortTermValid, MeasuredLoudness.absent());
        SegmentLayout base = new SegmentLayout(LayoutStatus.READY,
                new FrozenList<FrameRange>(new Object[] { new FrameRange(0L, total) }));
        return new BoundaryDetector().refineLoudnessTransitions(features, loudness, base, 48_000,
                total, LevelerCalibrationProfile.V1);
    }

    private static boolean hasBoundaryNear(SegmentLayout layout, long boundary, long tolerance)
    {
        for (int i = 0; i + 1 < layout.regions().size(); i++)
            if (Math.abs(layout.regions().get(i).endExclusive() - boundary) <= tolerance) return true;
        return false;
    }

    private static FeatureTimeline tonal(int count, IntToDoubleFunction angle, IntToDoubleFunction flux)
    {
        Object[] frames = new Object[count];
        for (int i = 0; i < count; i++)
        {
            double[] chroma = new double[12], spectral = new double[8];
            chroma[0] = Math.cos(angle.applyAsDouble(i)); chroma[1] = Math.sin(angle.applyAsDouble(i));
            spectral[0] = 1d;
            frames[i] = new StructuralFrame(i * HOP + (HOP - 1L) / 2L, chroma, spectral, flux.applyAsDouble(i), 1d);
        }
        return new FeatureTimeline(HOP, new FrozenList<StructuralFrame>(frames));
    }
}
