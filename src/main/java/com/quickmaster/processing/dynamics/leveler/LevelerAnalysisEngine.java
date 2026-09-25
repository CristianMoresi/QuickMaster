package com.quickmaster.processing.dynamics.leveler;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ConformanceState;
import com.quickmaster.processing.dynamics.leveler.model.ComparisonTimeline;
import com.quickmaster.processing.dynamics.leveler.model.DiagnosticCode;
import com.quickmaster.processing.dynamics.leveler.model.DiagnosticEntry;
import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.GroupingResult;
import com.quickmaster.processing.dynamics.leveler.model.LayoutStatus;
import com.quickmaster.processing.dynamics.leveler.model.LoudnessTimeline;
import com.quickmaster.processing.dynamics.leveler.model.ProtectionDecision;
import com.quickmaster.processing.dynamics.leveler.model.ProtectionFlags;
import com.quickmaster.processing.dynamics.leveler.model.ReferencePlan;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SegmentLayout;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisCache;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisResult;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisSnapshot;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisStatus;
import com.quickmaster.processing.dynamics.leveler.model.ShadowDiagnostics;
import com.quickmaster.processing.dynamics.leveler.model.ShadowMemoryCounters;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityMatrix;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityScore;
import com.quickmaster.processing.dynamics.leveler.model.StandardValidationReport;

/** Synchronous, stateless orchestrator for the M-004 shadow pipeline. */
public final class LevelerAnalysisEngine
{
    public static final String ALGORITHM_ID = "QM-LEVELER-S001-COMPARISON-V2";

    public ShadowAnalysisSnapshot analyzeShadow(float[] pcm,
                                                AudioFormat source,
                                                CancellationToken cancellation)
    {
        if (pcm == null || source == null) return null;
        CancellationToken token = cancellation == null ? new CancellationToken() : cancellation;
        if (token.isCancelled()) return null;
        try
        {
            // LoudnessAnalyzer validates every sample with cancellation polling.
            LoudnessTimeline loudness = new LoudnessAnalyzer().analyze(pcm, source, token);
            if (loudness == null || token.isCancelled()) return null;
            FeatureTimeline features = new StructuralFeatureExtractor().extract(
                    pcm, source, loudness, token);
            if (features == null || token.isCancelled()) return null;
            BoundaryDetector detector = new BoundaryDetector();
            SegmentLayout layout = detector.detect(
                    features, source.frames(), LevelerCalibrationProfile.V1);
            if (layout.status() != LayoutStatus.READY || token.isCancelled()) return null;
            layout = detector.refineLoudnessTransitions(features, loudness, layout,
                    source.sampleRateHz(), source.frames(), LevelerCalibrationProfile.V1);
            if (layout.status() != LayoutStatus.READY || token.isCancelled()) return null;
            FrozenList<SegmentDescriptor> descriptors = new SegmentDescriptorBuilder().build(
                    pcm, source, loudness, features, layout);
            if (descriptors == null || token.isCancelled()) return null;

            Object[] protectionObjects = new Object[descriptors.size()];
            BodyEligibility[] eligibility = new BodyEligibility[descriptors.size()];
            ProtectionClassifier protectionClassifier = new ProtectionClassifier();
            for (int i = 0; i < descriptors.size(); i++)
            {
                ProtectionDecision decision = protectionClassifier.classify(
                        i, descriptors, LevelerCalibrationProfile.V1);
                protectionObjects[i] = protectShortTransition(decision,
                        descriptors.get(i).range().lengthFrames(), source.sampleRateHz());
            }
            FrozenList<ProtectionDecision> protections =
                    new FrozenList<ProtectionDecision>(protectionObjects);
            BodyContextGate bodyGate = new BodyContextGate();
            for (int i = 0; i < descriptors.size(); i++)
            {
                eligibility[i] = bodyGate.evaluate(i, descriptors, protections);
            }

            ComparisonTimeline comparison = new ComparisonFeatureExtractor().extract(pcm, source, token);
            if (comparison == null || token.isCancelled()) return null;

            int pairCount = Math.multiplyExact(descriptors.size(), descriptors.size() - 1) / 2;
            Object[] scoreObjects = new Object[pairCount];
            ComparisonComparator comparator = new ComparisonComparator();
            int scoreIndex = 0;
            for (int first = 0; first < descriptors.size(); first++)
            {
                for (int second = first + 1; second < descriptors.size(); second++)
                {
                    if (token.isCancelled()) return null;
                    SimilarityScore score;
                    if (!eligibility[first].eligible())
                    {
                        score = rejected(eligibility[first].rejectionReason());
                    }
                    else if (!eligibility[second].eligible())
                    {
                        score = rejected(eligibility[second].rejectionReason());
                    }
                    else
                    {
                        SimilarityRejectionReason bodyReason = bodyGate.compare(
                                descriptors.get(first), descriptors.get(second));
                        score = bodyReason == SimilarityRejectionReason.NONE
                                ? comparator.compare(descriptors.get(first).range(), descriptors.get(second).range(),
                                        source, comparison, LevelerCalibrationProfile.V2, token)
                                : rejected(bodyReason);
                    }
                    if (score == null || token.isCancelled()) return null;
                    scoreObjects[scoreIndex++] = score;
                }
            }
            SimilarityMatrix similarity = new SimilarityMatrix(descriptors.size(),
                    new FrozenList<SimilarityScore>(scoreObjects));
            GroupingResult grouping = new ComparableGroupBuilder().build(
                    descriptors, similarity, LevelerCalibrationProfile.V2);
            ReferencePlan referencePlan = new ReferencePlanner().plan(
                    grouping, descriptors, LevelerCalibrationProfile.V2);

            StandardValidationReport standardValidation = ConformanceArtifactLoader.loadCurrent();
            Object[] diagnosticObjects = diagnostics(protections, similarity, standardValidation);
            ShadowMemoryCounters memoryCounters = counters(source, loudness, features,
                    descriptors, similarity, grouping, comparison);
            byte[] fingerprint = fingerprint(pcm, token);
            if (fingerprint == null || token.isCancelled()) return null;
            ShadowAnalysisCache cache = new ShadowAnalysisCache(source, fingerprint,
                    ALGORITHM_ID, LevelerCalibrationProfile.V2.profileId(), loudness, features,
                    layout, descriptors, protections, similarity, grouping, referencePlan, comparison);
            ShadowAnalysisStatus status = ShadowAnalysisStatus.DONE;
            ShadowAnalysisResult result = new ShadowAnalysisResult(status, referencePlan);
            ShadowDiagnostics diagnostics = new ShadowDiagnostics(status, 0x1FFL,
                    new FrozenList<DiagnosticEntry>(diagnosticObjects), standardValidation, memoryCounters);
            ShadowAnalysisSnapshot snapshot = new ShadowAnalysisSnapshot(result, cache, diagnostics);
            return token.isCancelled() ? null : snapshot;
        }
        catch (IllegalArgumentException | IllegalStateException | ArithmeticException
                | NoSuchAlgorithmException ex)
        {
            return null;
        }
    }

    private static ProtectionDecision protectShortTransition(ProtectionDecision decision,
                                                              long lengthFrames,
                                                              int sampleRateHz)
    {
        if (!LevelerCalibrationProfile.V1.isShortTransition(lengthFrames, sampleRateHz)) return decision;
        return new ProtectionDecision(decision.id(),
                new ProtectionFlags(decision.flags().reasonBits() | (1L << 5)));
    }

    private static SimilarityScore rejected(SimilarityRejectionReason reason)
    {
        return new SimilarityScore(0.0d, 0.0d, 0.0d, 0.0d, 0, 0, reason);
    }

    private static Object[] diagnostics(FrozenList<ProtectionDecision> protections,
                                        SimilarityMatrix similarity,
                                        StandardValidationReport validation)
    {
        long protectedCount = 0L;
        for (int i = 0; i < protections.size(); i++)
        {
            if (protections.get(i).isBlocked()) protectedCount++;
        }
        long rejectedCount = 0L;
        for (int i = 0; i < similarity.scores().size(); i++)
        {
            if (similarity.scores().get(i).rejectionReason() != SimilarityRejectionReason.NONE)
            {
                rejectedCount++;
            }
        }
        int count = (protectedCount > 0L ? 1 : 0) + (rejectedCount > 0L ? 1 : 0)
                + (validation.state() == ConformanceState.PASSED ? 0 : 1);
        Object[] entries = new Object[count];
        int index = 0;
        if (protectedCount > 0L)
        {
            entries[index++] = new DiagnosticEntry(DiagnosticCode.PROTECTED, -1, -1, protectedCount);
        }
        if (rejectedCount > 0L)
        {
            entries[index++] = new DiagnosticEntry(DiagnosticCode.SIMILARITY_REJECTED,
                    -1, -1, rejectedCount);
        }
        if (validation.state() != ConformanceState.PASSED)
        {
            entries[index] = new DiagnosticEntry(DiagnosticCode.STANDARD_VALIDATION_FAILED,
                    -1, -1, 1L);
        }
        return entries;
    }

    private static ShadowMemoryCounters counters(AudioFormat source,
                                                 LoudnessTimeline loudness,
                                                 FeatureTimeline features,
                                                 FrozenList<SegmentDescriptor> descriptors,
                                                 SimilarityMatrix similarity,
                                                 GroupingResult grouping,
                                                 ComparisonTimeline comparison)
    {
        long timelineElements = Math.addExact(
                Math.addExact(loudness.momentaryCount(), ceilDiv(loudness.momentaryCount(), 64L)),
                Math.addExact(loudness.shortTermCount(), ceilDiv(loudness.shortTermCount(), 64L)));
        timelineElements = Math.addExact(timelineElements,
                Math.addExact(Math.multiplyExact(53L, comparison.shortCount()),
                        Math.multiplyExact(37L, comparison.longCount())));
        long descriptorArrays = Math.addExact(Math.multiplyExact(2L, features.size()),
                Math.multiplyExact(Math.multiplyExact(2L, descriptors.size()), 32L));
        descriptorArrays = Math.addExact(descriptorArrays,
                Math.multiplyExact(2L, grouping.groups().size()));
        long descriptorElements = Math.addExact(Math.multiplyExact(22L, features.size()),
                Math.multiplyExact(Math.multiplyExact(22L, descriptors.size()), 32L));
        long sketchArrays = Math.multiplyExact(descriptors.size(), source.channels() + 1L);
        long sketchElements = Math.multiplyExact(Math.multiplyExact(descriptors.size(),
                source.channels()), 2048L);
        return new ShadowMemoryCounters(0L, 0L, 8L, timelineElements,
                descriptorArrays, descriptorElements, sketchArrays, sketchElements,
                similarity.scores().size(), 0L, 0L, 0L, 0L, 0L,
                loudness.momentaryCount(), features.size(), descriptors.size(),
                similarity.scores().size());
    }

    private static byte[] fingerprint(float[] pcm, CancellationToken token) throws NoSuchAlgorithmException
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[8192];
        int chunkBytes = 0;
        for (int i = 0; i < pcm.length; i++)
        {
            if ((i & 4095) == 0 && token.isCancelled()) return null;
            int bits = Float.floatToRawIntBits(pcm[i]);
            chunk[chunkBytes++] = (byte) (bits >>> 24);
            chunk[chunkBytes++] = (byte) (bits >>> 16);
            chunk[chunkBytes++] = (byte) (bits >>> 8);
            chunk[chunkBytes++] = (byte) bits;
            if (chunkBytes == chunk.length)
            {
                digest.update(chunk, 0, chunkBytes);
                chunkBytes = 0;
            }
        }
        if (chunkBytes > 0) digest.update(chunk, 0, chunkBytes);
        return token.isCancelled() ? null : digest.digest();
    }

    private static long ceilDiv(long numerator, long denominator)
    {
        return (numerator + denominator - 1L) / denominator;
    }
}
