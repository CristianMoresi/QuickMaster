package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.BitSet;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.*;
import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.LayoutStatus;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SegmentLayout;
import com.quickmaster.processing.dynamics.leveler.model.StructuralFrame;

class SegmentDescriptorBuilderTest
{
    @Test
    void oneFrameRegionRepeatsItsSampleInAll2048BinsAndAliasesRange()
    {
        float[] pcm = new float[] { 0.375f };
        FrameRange range = new FrameRange(0L, 1L);

        SegmentDescriptor descriptor = build(pcm, 1, range).get(0);

        assertSame(range, descriptor.range());
        assertEquals(1, descriptor.sketch().channels());
        assertEquals(2048, descriptor.sketch().bins());
        for (int bin = 0; bin < 2048; bin++)
        {
            assertEquals(0.375d, descriptor.sketch().valueAt(0, bin), 0.0d);
        }
    }

    @Test
    void fractionalAreaSketchIncludesEveryTailSampleAndPreservesMean()
    {
        float[] pcm = new float[] { 1.0f, 2.0f, 3.0f };
        SegmentDescriptor descriptor = build(pcm, 3, new FrameRange(0L, 3L)).get(0);

        double sum = 0.0d;
        for (int bin = 0; bin < 2048; bin++) sum += descriptor.sketch().valueAt(0, bin);
        assertEquals(2.0d, sum / 2048.0d, 1.0e-15d);
        assertEquals(1.0d, descriptor.sketch().valueAt(0, 0), 0.0d);
        assertEquals(3.0d, descriptor.sketch().valueAt(0, 2047), 0.0d);
    }

    private static FrozenList<SegmentDescriptor> build(float[] pcm, int frames, FrameRange range)
    {
        AudioFormat format = new AudioFormat(48_000, 1, frames);
        double[] zero = new double[1];
        LoudnessTimeline loudness = new LoudnessTimeline(19_200, 144_000, 4_800,
                zero, new BitSet(), new double[1], new BitSet(), MeasuredLoudness.absent());
        StructuralFrame frame = new StructuralFrame((frames - 1L) / 2L,
                LevelerModelFixtures.unitChroma(), LevelerModelFixtures.unitSpectral(), 0.0d, 1.0d);
        FeatureTimeline features = new FeatureTimeline(24_000L,
                new FrozenList<StructuralFrame>(new Object[] { frame }));
        SegmentLayout layout = new SegmentLayout(LayoutStatus.READY,
                new FrozenList<FrameRange>(new Object[] { range }));
        return new SegmentDescriptorBuilder().build(pcm, format, loudness, features, layout);
    }

    @Test
    void activeThreeSecondNeighboursProvideContextAcrossSourceFormats() {
        for (int fs : new int[] {44100, 48000})
            for (int channels : new int[] {1, 2}) ContextAvailabilityChecks.activeThreeSeconds(fs, channels);
    }

    @Test
    void contextualFallbackDoesNotPromoteInsufficientTrendConfidence() {
        ContextAvailabilityChecks.trendUnavailableIsStillProtected();
    }

    @Test
    void existingRegionalContextValuesRemainBitExact() {
        ContextAvailabilityChecks.existingLongContextsStayExact();
    }

    @Test
    void contextualFallbackRetainsSupportAndBothGates() {
        ContextAvailabilityChecks.fallbackSupportCases();
    }

    @Test
    void contextualWindowsExcludeShiftedBoundariesAndPartialEof() {
        ContextAvailabilityChecks.shiftedCompleteWindows();
        ContextAvailabilityChecks.partialEofAndTruncatedTimeline();
    }

    @Test
    void contextualFallbackHandlesHalfCoverageFinitePowersAndOneFrameGeometry() {
        ContextAvailabilityChecks.criticArithmeticAndGeometry();
    }

    @Test
    void contextAvailabilityRetainsOwnProtectionAndNeighbourVetoes() {
        ContextAvailabilityChecks.ownStableAndNeighbourGatesStayRequired();
    }

    @Test
    void endpointsAndNonTonalComparisonCannotCreateCorrectiveReference() {
        ContextAvailabilityChecks.endpointMasksAndNonTonalDownstream();
    }

    /** Exact synthetic model controls frozen before the context-availability repair. */
    static final class ContextAvailabilityChecks {
        private static final BodyContextGate GATE = new BodyContextGate();
        private static void activeThreeSeconds(int fs, int channels) {
            String id = "active3s-" + fs + "-" + channels;
            Fixture f = fixture(fs, channels, new double[] {0, 3, 15, 27}, 0.5, -1);
            f.shortInside(0, -24);
            f.momentaryInside(0, -24);
            FrozenList<SegmentDescriptor> d = f.build();
            check(id + "/rawRegionalAbsent", !new LoudnessAnalyzer()
                    .regionalLoudness(f.timeline(), f.ranges[0], fs).present());
            check(id + "/ownRegionalAbsent", !d.get(0).regionalLoudness().present());
            check(id + "/ownProtectionPreserved", protections(d).get(0).isBlocked());
            check(id + "/bodyRegionalPresent", d.get(1).regionalLoudness().present());
            check(id + "/bodyUnprotected", !protections(d).get(1).isBlocked());
            check(id + "/allOtherOldEligibilityTerms", oldTermsExceptNeighbourRegional(d, 1));
            check(id + "/causalBodyEligible", GATE.evaluate(1, d, protections(d)).eligible());
            near(id + "/entryFromContext", .5, d.get(1).context().entryLoudness12(), 2e-15);
            near(id + "/nextLongContext", 0, d.get(1).context().exitLoudness12(), 0);
            checkMask(id + "/mask", d.get(1).context(), 7);
            emit(id, d, 1);
        }
    
        private static void trendUnavailableIsStillProtected() {
            Fixture f = fixture(48000, 1, new double[] {0, 3.2, 15.2, 27.2}, .05, -1);
            f.shortInside(0, -30);
            f.momentaryInside(0, -24);
            FrozenList<SegmentDescriptor> d = f.build();
            check("trend3.2/rawRegionalPresent", new LoudnessAnalyzer()
                    .regionalLoudness(f.timeline(), f.ranges[0], 48000).present());
            check("trend3.2/finalRegionalAbsent", !d.get(0).regionalLoudness().present());
            check("trend3.2/ownProtectionUnchanged", protections(d).get(0).isBlocked());
            check("trend3.2/ownBodyStillRejected", !GATE.evaluate(0, d, protections(d)).eligible());
            check("trend3.2/neighbourCanBeBody", GATE.evaluate(1, d, protections(d)).eligible());
            near("trend3.2/usesMomentaryNotRawRegional", .5, d.get(1).context().entryLoudness12(), 2e-15);
        }
    
        private static void existingLongContextsStayExact() {
            Fixture f = fixture(48000, 1, new double[] {0, 12, 24, 36}, .5, -1);
            f.shortInside(0, -24); f.shortInside(1, -18); f.shortInside(2, -15);
            for (int i = 0; i < 3; i++) f.momentaryInside(i, -6);
            FrozenList<SegmentDescriptor> d = f.build();
            double[] expected = {1, 1, .5, .25, 0, 0};
            for (int i = 0; i < 6; i++)
                bits("longContext/component" + i, expected[i], d.get(1).context().componentAt(i));
            bits("longContext/regional0", -24, d.get(0).regionalLoudness().lufs());
            bits("longContext/regional1", -18, d.get(1).regionalLoudness().lufs());
            bits("longContext/regional2", -15, d.get(2).regionalLoudness().lufs());
            check("longContext/bodyEligible", GATE.evaluate(1, d, protections(d)).eligible());
            checkMask("longContext/mask", d.get(1).context(), 7);
        }
    
        private static void fallbackSupportCases() {
            fallbackCase("missing", 0, false, -24);
            fallbackCase("zeroPower", 1, false, -24);
            fallbackCase("belowAbsolute", 2, false, -24);
            fallbackCase("twoValid", 3, false, -24);
            fallbackCase("thirteenOf27", 4, false, -24);
            fallbackCase("fourteenOf27", 5, true, -24);
            fallbackCase("onlyTwoAboveAbsolute", 6, false, -24);
            fallbackCase("onlyTwoAboveRelative", 7, false, -10);
            fallbackCase("threeAboveRelative", 8, true, -10);
            fallbackCase("oddLowerMedian", 9, true, -25);
            Fixture even = fixture(48000, 1, new double[] {0, 3.1, 15.1, 27.1}, .05, -1);
            even.clearMomentary(0);
            for (int i = 0; i < 28; i++) even.momentary(i, i % 2 == 0 ? -25 : -23);
            FrozenList<SegmentDescriptor> d = even.build();
            near("evenLowerMedian/entry", 7.0 / 12, d.get(1).context().entryLoudness12(), 2e-15);
            checkMask("evenLowerMedian/mask", d.get(1).context(), 7);
            check("evenLowerMedian/bodyEligible", GATE.evaluate(1, d, protections(d)).eligible());
        }
    
        private static void fallbackCase(String name, int mode, boolean available, double expectedLufs) {
            Fixture f = fixture(48000, 1, new double[] {0, 3, 15, 27}, .5, -1);
            f.clearMomentary(0);
            int first = 0, count = 27;
            if (mode == 1) for (int i = 0; i < count; i++) { f.mv.set(i); f.mp[i] = 0; }
            if (mode == 2) for (int i = 0; i < count; i++) f.momentary(i, -71);
            if (mode == 3 || mode == 4 || mode == 5) {
                int valid = mode == 3 ? 2 : mode == 4 ? 13 : 14;
                for (int i = 0; i < valid; i++) f.momentary(first + i, -24);
            }
            if (mode == 6 || mode == 7 || mode == 8) {
                int selected = mode == 8 ? 3 : 2;
                for (int i = 0; i < count; i++)
                    f.momentary(i, i < selected ? -10 : mode == 6 ? -80 : -60);
            }
            if (mode == 9) for (int i = 0; i < count; i++) f.momentary(i, i % 2 == 0 ? -25 : -23);
            FrozenList<SegmentDescriptor> d = f.build();
            check("support/" + name + "/ownAbsent", !d.get(0).regionalLoudness().present());
            check("support/" + name + "/bodyState", GATE.evaluate(1, d, protections(d)).eligible() == available);
            checkMask("support/" + name + "/mask", d.get(1).context(), available ? 7 : 6);
            near("support/" + name + "/entry", available ? (-18 - expectedLufs) / 12 : 0,
                    d.get(1).context().entryLoudness12(), 2e-15);
        }
    
        private static void shiftedCompleteWindows() {
            Fixture f = fixture(48000, 1, new double[] {0, .05, 3.05, 15.05, 27.05}, .05, -1);
            Arrays.fill(f.mp, power(-3));
            f.mv.set(0, f.mp.length);
            // Region [.05,3.05): precisely starts 1..26, not 0 or 27.
            for (int i = 1; i <= 26; i++) f.momentary(i, i % 2 == 1 ? -25 : -23);
            FrozenList<SegmentDescriptor> d = f.build();
            near("shifted/lowerMedianExcludesBothCrossers", 7.0 / 12,
                    d.get(2).context().entryLoudness12(), 2e-15);
            check("shifted/bodyEligible", GATE.evaluate(2, d, protections(d)).eligible());
            checkMask("shifted/mask", d.get(2).context(), 7);
        }
    
        private static void partialEofAndTruncatedTimeline() {
            Fixture f = fixture(48000, 1, new double[] {0, 12, 24, 24.75}, .05, 250);
            f.clearMomentary(2);
            // Four complete starts 240..243. Starts 244+ cross EOF and must not enter.
            for (int i = 240; i <= 243; i++) f.momentary(i, -24 + i - 240);
            for (int i = 244; i < f.mp.length; i++) f.momentary(i, -3);
            FrozenList<SegmentDescriptor> d = f.build();
            near("partialEOF/noOutsideWindows", -5.0 / 12, d.get(1).context().exitLoudness12(), 2e-15);
            checkMask("partialEOF/mask", d.get(1).context(), 7);
            Fixture two = fixture(48000, 1, new double[] {0, 12, 24, 24.599979166666667}, .05, 247);
            two.clearMomentary(2);
            for (int i = 240; i < two.mp.length; i++) two.momentary(i, -24);
            FrozenList<SegmentDescriptor> td = two.build();
            checkMask("partialEOF/twoCompleteNotThree", td.get(1).context(), 3);
            check("partialEOF/twoCompleteBodyRejected", !GATE.evaluate(1, td, protections(td)).eligible());
            Fixture truncated = fixture(48000, 1, new double[] {0, 12, 24, 27}, .5, 243);
            truncated.momentaryInside(2, -24);
            FrozenList<SegmentDescriptor> tr = truncated.build();
            check("truncated/bodyRegionalPresent", tr.get(1).regionalLoudness().present());
            checkMask("truncated/threeOf27NotThreeOfThree", tr.get(1).context(), 3);
            check("truncated/bodyRejected", !GATE.evaluate(1, tr, protections(tr)).eligible());
        }
    
        private static void criticArithmeticAndGeometry() {
            Fixture half = fixture(48000, 1, new double[] {0, 3.1, 15.1, 27.1}, .05, -1);
            half.clearMomentary(0);
            for (int i = 0; i < 14; i++) half.momentary(i, -24);
            FrozenList<SegmentDescriptor> hd = half.build();
            check("C1/exactHalf14of28", GATE.evaluate(1, hd, protections(hd)).eligible());
            checkMask("C1/exactHalfMask", hd.get(1).context(), 7);
            for (int mode = 0; mode < 3; mode++) {
                Fixture f = fixture(48000, 1, new double[] {0, 3, 15, 27}, .5, -1);
                f.clearMomentary(0);
                for (int i = 0; i < 27; i++) {
                    f.mv.set(i);
                    f.mp[i] = mode == 0 ? power(0) : mode == 1 ? Double.MIN_VALUE : Double.MAX_VALUE;
                }
                FrozenList<SegmentDescriptor> d = f.build();
                check("C1/finitePowerCase" + mode, GATE.evaluate(1, d, protections(d)).eligible() == (mode == 0));
                checkMask("C1/finitePowerMask" + mode, d.get(1).context(), mode == 0 ? 7 : 6);
                near("C1/finitePowerEntry" + mode, mode == 0 ? -1 : 0,
                        d.get(1).context().entryLoudness12(), 2e-15);
            }
            // Pure source-clock arithmetic at 100 frames/s: one frame != one 100 ms hop.
            // This is not an assertion about DSP support at 100 Hz sample rate.
            Fixture one = fixture(100, 1, new double[] {0, .01, 3.01, 15.01, 27.01}, .01, -1);
            Arrays.fill(one.mp, power(-3)); one.mv.set(0, one.mp.length);
            for (int i = 1; i <= 26; i++) one.momentary(i, i % 2 == 1 ? -25 : -23);
            FrozenList<SegmentDescriptor> od = one.build();
            near("C1/oneFrameStartShift", 7.0/12, od.get(2).context().entryLoudness12(), 2e-15);
            checkMask("C1/oneFrameStartMask", od.get(2).context(), 7);
            Fixture tiny = fixture(48000, 1, new double[] {0, 12, 24, 24.399979166666667}, .5, -1);
            FrozenList<SegmentDescriptor> td = tiny.build();
            checkMask("C1/smallerThanWindow", td.get(1).context(), 3);
            check("C1/smallerThanWindowRejected", !GATE.evaluate(1, td, protections(td)).eligible());
        }
    
        private static void ownStableAndNeighbourGatesStayRequired() {
            Fixture f = fixture(48000, 1, new double[] {0, 12, 15, 27}, .5, -1);
            FrozenList<SegmentDescriptor> d = f.build();
            check("own3s/stillAbsent", !d.get(1).regionalLoudness().present());
            check("own3s/stillProtected", protections(d).get(1).isBlocked());
            check("own3s/stillIneligible", !GATE.evaluate(1, d, protections(d)).eligible());
            Fixture g = fixture(48000, 1, new double[] {0, 3, 15, 27}, .5, -1);
            g.momentaryInside(0, -24);
            FrozenList<SegmentDescriptor> gd = g.build();
            Object[] altered = new Object[gd.size()];
            FrozenList<ProtectionDecision> p = protections(gd);
            for (int i = 0; i < altered.length; i++) altered[i] = p.get(i);
            altered[0] = new ProtectionDecision(gd.get(0).id(), new ProtectionFlags(1L << 5));
            check("transition/neighbourVetoPreserved", !GATE.evaluate(1, gd,
                    new FrozenList<ProtectionDecision>(altered)).eligible());
            Object[] desc = new Object[gd.size()];
            for (int i = 0; i < desc.length; i++) desc[i] = gd.get(i);
            desc[0] = copy(gd.get(0), gd.get(0).context(), .49, 0, 0, 0, 0);
            check("foreground/neighbourVetoPreserved", !GATE.evaluate(1,
                    new FrozenList<SegmentDescriptor>(desc), p).eligible());
            for (int mode = 0; mode < 4; mode++) {
                desc[0] = gd.get(0);
                desc[1] = copy(gd.get(1), gd.get(1).context(), 1, mode == 0 ? .16 : 0,
                        mode == 1 ? .01 : 0, mode == 2 ? .10 : 0, mode == 3 ? 2 : 0);
                check("stable/currentThresholdPreserved" + mode, !GATE.evaluate(1,
                        new FrozenList<SegmentDescriptor>(desc), p).eligible());
            }
        }
    
        static void maskAndDirectGateContract() throws Exception {
            BodyContextVector legacy = new BodyContextVector(1, 1, 0, 0, .25, .5);
            double[] expected = {1, 1, 0, 0, .25, .5};
            for (int i = 0; i < 6; i++) bits("legacy/component" + i, expected[i], legacy.componentAt(i));
            checkMask("legacy/defaultAvailable", legacy, 7);
            for (int invalid : new int[] {-1, 8, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                boolean rejected = false;
                try { context(invalid, expected); }
                catch (IllegalArgumentException ex) { rejected = true; }
                check("mask/reject" + invalid, rejected);
            }
            PcmSketch x = LevelerModelFixtures.impulseSketch(2, 1, 0);
            double[][] yv = x.copyValues();
            yv[1][500] += 1.0 / 64;
            PcmSketch y = new PcmSketch(2, 2048, yv);
            for (int mask = 0; mask <= 7; mask++) {
                BodyContextVector c = context(mask, expected);
                checkMask("mask/roundtrip" + mask, c, mask);
                for (int i = 0; i < 6; i++) bits("mask/components" + mask + "-" + i, expected[i], c.componentAt(i));
                SegmentDescriptor a = LevelerModelFixtures.descriptor(0, -18, LevelerModelFixtures.unitChroma(),
                        LevelerModelFixtures.unitSpectral(), 1, c, x);
                SegmentDescriptor b = LevelerModelFixtures.descriptor(1, -18, LevelerModelFixtures.unitChroma(),
                        LevelerModelFixtures.unitSpectral(), 1, legacy, y);
                check("mask/directAssessFirst" + mask, (GATE.assess(x, y, c, legacy) != null) == (mask == 7));
                check("mask/directAssessSecond" + mask, (GATE.assess(x, y, legacy, c) != null) == (mask == 7));
                check("mask/directCompareFirst" + mask, GATE.compare(a, b) ==
                        (mask == 7 ? SimilarityRejectionReason.NONE : SimilarityRejectionReason.CONTEXT_MISMATCH));
                check("mask/directCompareSecond" + mask, GATE.compare(b, a) ==
                        (mask == 7 ? SimilarityRejectionReason.NONE : SimilarityRejectionReason.CONTEXT_MISMATCH));
            }
            for (int component = 0; component < 6; component++) {
                double[] shifted = expected.clone();
                shifted[component] += component < 4 ? .250001 : 1.000001;
                BodyContextVector c = context(7, shifted);
                SegmentDescriptor a = LevelerModelFixtures.descriptor(0, -18, LevelerModelFixtures.unitChroma(),
                        LevelerModelFixtures.unitSpectral(), 1, legacy, x);
                SegmentDescriptor b = LevelerModelFixtures.descriptor(1, -18, LevelerModelFixtures.unitChroma(),
                        LevelerModelFixtures.unitSpectral(), 1, c, y);
                check("mask/preservesSixComponentDistance" + component,
                        GATE.compare(a, b) == SimilarityRejectionReason.CONTEXT_MISMATCH);
            }
            Fixture f = fixture(48000, 1, new double[] {0, 12, 24, 36}, .5, -1);
            FrozenList<SegmentDescriptor> d = f.build();
            for (int mask = 0; mask <= 7; mask++) {
                Object[] values = new Object[] {d.get(0), copy(d.get(1), context(mask, new double[]{1,1,0,0,0,0}),
                        1, 0, 0, 0, 0), d.get(2)};
                check("mask/evaluateWithPresentNeighbours" + mask,
                        GATE.evaluate(1, new FrozenList<SegmentDescriptor>(values), protections(d)).eligible() == (mask == 7));
            }
        }
    
    
        private static void endpointMasksAndNonTonalDownstream() {
            Fixture single = fixture(48000, 1, new double[] {0, 12}, .5, -1);
            FrozenList<SegmentDescriptor> sd = single.build();
            checkMask("endpoint/single", sd.get(0).context(), 2);
            Fixture longRegions = fixture(48000, 1, new double[] {0, 12, 24, 36}, .5, -1);
            FrozenList<SegmentDescriptor> ld = longRegions.build();
            checkMask("endpoint/first", ld.get(0).context(), 6);
            checkMask("endpoint/last", ld.get(2).context(), 3);
    
            Fixture f = fixture(48000, 1, new double[] {0, 3, 15, 18, 30, 33}, .5, -1);
            // Non-scaled actual sketch inputs; the loudness/features remain explicit model inputs.
            int first = (int) f.ranges[3].startInclusive();
            int end = (int) f.ranges[3].endExclusive();
            for (int frame = first; frame < end; frame++) f.pcm[frame] += frame % 4096 < 2048 ? .03125f : -.03125f;
            FrozenList<SegmentDescriptor> d = f.build();
            check("downstream/firstEligible", GATE.evaluate(1, d, protections(d)).eligible());
            check("downstream/secondEligible", GATE.evaluate(3, d, protections(d)).eligible());
            check("downstream/contextPasses", GATE.compare(d.get(1), d.get(3)) == SimilarityRejectionReason.NONE);
            int ns = Math.toIntExact((f.format.frames() * 40 + 47999) / 48000);
            int nl = Math.toIntExact((f.format.frames() * 8 + 47999) / 48000);
            byte[] sf = new byte[ns], lf = new byte[nl];
            Arrays.fill(sf, (byte) 3); Arrays.fill(lf, (byte) 3);
            // Explicit valid energy/support but no tonal evidence: a representation-model
            // control, not a claim that a particular rendered noise waveform has these rows.
            ComparisonTimeline comparison = new ComparisonTimeline(f.format,
                    new short[ns * 52], new short[nl * 36], sf, lf);
            SimilarityScore score = new ComparisonComparator().compare(d.get(1).range(), d.get(3).range(),
                    f.format, comparison, LevelerCalibrationProfile.V2, null);
            check("downstream/comparatorActuallyExecuted", score != null);
            check("downstream/nonTonalRejected", score.rejectionReason() == SimilarityRejectionReason.INSUFFICIENT_VALID_BINS);
            FrozenList<SegmentDescriptor> eligible = new FrozenList<SegmentDescriptor>(new Object[] {d.get(1), d.get(3)});
            GroupingResult groups = new ComparableGroupBuilder().build(eligible,
                    new SimilarityMatrix(2, new FrozenList<SimilarityScore>(new Object[] {score})),
                    LevelerCalibrationProfile.V2);
            check("downstream/noGroup", groups.groups().size() == 0 && groups.pairs().size() == 0);
            ReferencePlan plan = new ReferencePlanner().plan(groups, d, LevelerCalibrationProfile.V2);
            for (int i = 0; i < d.size(); i++) {
                bits("downstream/unitRaw" + i, 0, plan.targets().get(i).rawDb());
                bits("downstream/unitWeighted" + i, 0, plan.targets().get(i).confidenceWeightedDb());
                check("downstream/noReference" + i, !plan.targets().get(i).referenceLoudness().present());
            }
            for (int i : new int[] {0, 2, 4}) {
                check("downstream/shortRegionalAbsent" + i, !d.get(i).regionalLoudness().present());
                check("downstream/shortProtected" + i, protections(d).get(i).isBlocked());
            }
        }
    
        private static SegmentDescriptor copy(SegmentDescriptor d, BodyContextVector c, double fg,
                double spread, double activitySlope, double loudnessSlope, double loudnessDelta) {
            return new SegmentDescriptor(d.id(), d.range(), d.bins(), d.validBinMask(), d.regionalLoudness(),
                    loudnessSlope, loudnessDelta, d.loudnessConsistency(), activitySlope, spread, fg, c, d.sketch());
        }
    
        private static BodyContextVector context(int mask, double[] v) {
            return new BodyContextVector(v[0], v[1], v[2], v[3], v[4], v[5], mask);
        }
        private static void checkMask(String id, BodyContextVector c, int expected) {
            check(id, c.loudnessAvailabilityMask() == expected);
        }
    
        private static boolean oldTermsExceptNeighbourRegional(FrozenList<SegmentDescriptor> d, int i) {
            SegmentDescriptor c = d.get(i);
            return c.foregroundRatio() >= .8 && c.regionalLoudness().present()
                    && c.activitySpread() <= .15 && Math.abs(c.activitySlopePerSec()) < .01
                    && Math.abs(c.loudnessSlopeLuPerSec()) < .10 && Math.abs(c.loudnessDeltaLu()) < 2
                    && d.get(i-1).foregroundRatio() >= .5 && d.get(i+1).foregroundRatio() >= .5;
        }
        private static FrozenList<ProtectionDecision> protections(FrozenList<SegmentDescriptor> d) {
            Object[] p = new Object[d.size()];
            for (int i = 0; i < p.length; i++) p[i] = new ProtectionClassifier().classify(i, d, LevelerCalibrationProfile.V1);
            return new FrozenList<ProtectionDecision>(p);
        }
        private static void emit(String id, FrozenList<SegmentDescriptor> d, int i) { }
        private static void check(String id, boolean pass) {
            org.junit.jupiter.api.Assertions.assertTrue(pass, id);
        }
        private static void near(String id, double expected, double actual, double tolerance) {
            check(id + " expected=" + expected + " actual=" + actual, Math.abs(expected - actual) <= tolerance);
        }
        private static void bits(String id, double expected, double actual) {
            check(id, Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual));
        }
        private static double power(double lufs) { return StrictMath.pow(10, (lufs + .691) / 10); }
    
        private static Fixture fixture(int fs, int channels, double[] seconds, double featureHopSec, int count) {
            return new Fixture(fs, channels, seconds, featureHopSec, count);
        }
        private static final class Fixture {
            final int fs, channels, hop, mw, sw;
            final AudioFormat format;
            final FrameRange[] ranges;
            final double[] mp, sl;
            final BitSet mv = new BitSet(), sv = new BitSet();
            final float[] pcm;
            final FeatureTimeline features;
            Fixture(int fs, int channels, double[] seconds, double featureHopSec, int count) {
                this.fs = fs; this.channels = channels;
                hop = (int) Math.round(fs * .1); mw = (int) Math.round(fs * .4); sw = fs * 3;
                long frames = Math.round(seconds[seconds.length-1] * fs);
                format = new AudioFormat(fs, channels, frames);
                ranges = new FrameRange[seconds.length-1];
                for (int i = 0; i < ranges.length; i++)
                    ranges[i] = new FrameRange(Math.round(seconds[i] * fs), Math.round(seconds[i+1] * fs));
                int size = count >= 0 ? count : (int) (1 + (frames-1) / hop);
                mp = new double[size]; sl = new double[size];
                for (int i = 0; i < ranges.length; i++) { shortInside(i, -18); momentaryInside(i, -18); }
                pcm = new float[Math.toIntExact(frames * channels)];
                Arrays.fill(pcm, .125f);
                long featureHop = Math.round(fs * featureHopSec);
                Object[] values = new Object[(int) (1 + (frames-1) / featureHop)];
                for (int i = 0; i < values.length; i++) {
                    long start = i * featureHop, length = Math.min(featureHop, frames-start);
                    values[i] = new StructuralFrame(start+(length-1)/2,
                            LevelerModelFixtures.unitChroma(), LevelerModelFixtures.unitSpectral(), 0, 1);
                }
                features = new FeatureTimeline(featureHop, new FrozenList<StructuralFrame>(values));
            }
            void shortInside(int region, double lufs) {
                FrameRange r = ranges[region];
                for (int i = 0; i < sl.length; i++) {
                    long start = (long) i * hop;
                    if (start >= r.startInclusive() && start + sw <= r.endExclusive()) {
                        sl[i] = lufs; sv.set(i);
                    }
                }
            }
            void momentaryInside(int region, double lufs) {
                FrameRange r = ranges[region];
                for (int i = 0; i < mp.length; i++) {
                    long start = (long) i * hop;
                    if (start >= r.startInclusive() && start + mw <= r.endExclusive()) momentary(i, lufs);
                }
            }
            void clearMomentary(int region) {
                FrameRange r = ranges[region];
                for (int i = 0; i < mp.length; i++) {
                    long start = (long) i * hop;
                    if (start >= r.startInclusive() && start + mw <= r.endExclusive()) { mp[i] = 0; mv.clear(i); }
                }
            }
            void momentary(int i, double lufs) { mp[i] = power(lufs); mv.set(i); }
            LoudnessTimeline timeline() {
                return new LoudnessTimeline(mw, sw, hop, mp, mv, sl, sv, MeasuredLoudness.absent());
            }
            FrozenList<SegmentDescriptor> build() {
                Object[] rr = new Object[ranges.length];
                System.arraycopy(ranges, 0, rr, 0, ranges.length);
                FrozenList<SegmentDescriptor> result = new SegmentDescriptorBuilder().build(pcm, format,
                        timeline(), features, new SegmentLayout(LayoutStatus.READY, new FrozenList<FrameRange>(rr)));
                if (result == null) throw new AssertionError("Invalid test fixture: builder returned null");
                return result;
            }
        }
        }
}
