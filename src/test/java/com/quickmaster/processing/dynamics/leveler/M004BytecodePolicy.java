package com.quickmaster.processing.dynamics.leveler;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Literal ADR007/009/010/011/012 policy; normative annex composition, never inferred from bytes. */
final class M004BytecodePolicy
{
    static final String DYNAMICS = "com/quickmaster/processing/dynamics/";
    static final String LEVELER = DYNAMICS + "leveler/";
    static final String MODEL = LEVELER + "model/";
    static final Set<String> SCANNED_M004_CLASSFILES = names(LEVELER, """
            BodyContextGate BodyEligibility BoundaryDetector BuildAlgorithmBinding CancellationToken
            ComparableGroupBuilder ConformanceRequirement ConformanceRun KWeightingAdapter
            LevelerAnalysisEngine LevelerCalibrationProfile LoudnessAnalyzer LoudnessConformanceGuard
            LoudnessStandard ProtectionClassifier ReferencePlanner SegmentComparator
            SegmentDescriptorBuilder StructuralFeatureExtractor
            LoudnessCore ConformanceCodec ConformanceArtifactLoader
            """, MODEL, """
            AudioFormat BodyContextVector ComparableGroup ComparablePair ConformanceReason ConformanceState
            DiagnosticCode DiagnosticEntry FeatureTimeline FrameRange FrozenList GroupingResult LayoutStatus
            LoudnessTimeline MeasuredLoudness OfficialSignalEvidence PcmSketch ProtectionDecision ProtectionFlags
            ReadingKind ReferencePlan ReferenceReason ReferenceTarget RequiredSetReport SegmentDescriptor SegmentId
            SegmentLayout ShadowAnalysisCache ShadowAnalysisResult ShadowAnalysisSnapshot ShadowAnalysisStatus
            ShadowDiagnostics ShadowMemoryCounters SimilarityMatrix SimilarityRejectionReason SimilarityScore
            StandardValidationReport StructuralBin StructuralFrame
            RequiredOfficialReading ChannelLayout ReadoutMode LoudnessValueKind
            """, DYNAMICS, "LevelerProcessor AnalysisDynamicsProcessor");

    // Hashes read from the installed, already accepted M-003 JAR, not learned from target/classes.
    static final Map<String, String> BOUNDARIES = Map.of(
            "com/quickmaster/processing/AudioProcessor", "5647bbbbb686c0b5b87d167be60b4762e0ff13b91194da541a2b6427328b2545",
            DYNAMICS + "AnalysisStatus", "7845e55b6081f1796cf99965ecdb03412080b533b375a643a5ce150157322e56",
            DYNAMICS + "GainSchedule", "422a9dc0391f15f6e6e481dbd4fc3fda85c62fcaa12f8634bf09594ddd66e04a",
            DYNAMICS + "GainDomain", "631b29aa4fa691ab5724557e57c1ff0f77ee8a510a93206fafac126b75bee2c3",
            DYNAMICS + "DenseGainSchedule", "13a9de0bf2d2148d72760b9000f0024a72b8f809a95481156b39c371e0244615",
            DYNAMICS + "DenseGainCursor", "16fda6e4ba1b3e7bec4169cc6caab4b1d7c2530f797641350c94d254c9a317f6",
            DYNAMICS + "PublishedGain", "c15e68a357358d0f97a32874c802628cd2a6a9823037c430f3b8ae137eef7cc5");

    static final Set<String> ENUMS = names(MODEL, """
            ConformanceReason ConformanceState DiagnosticCode LayoutStatus ReadingKind ReferenceReason
            ShadowAnalysisStatus SimilarityRejectionReason
            ChannelLayout ReadoutMode LoudnessValueKind
            """, DYNAMICS, "AnalysisStatus GainDomain");
    record FieldRule(String name, String descriptor, int access) { }
    record Call(int opcode, String owner, String name, String descriptor) { }
    static final Map<String, List<FieldRule>> FIELDS = fields();
    static final Set<Call> JDK_CALLS = calls();

    // ADR011: exact seven caller-qualified tuples are additionally mandatory.
    static final Set<String> DSPARK_OWNERS = Set.of("com/dspark/core/FFTReal", "com/dspark/core/DspMath");

    private static Set<String> names(String... pairs)
    {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < pairs.length; i += 2)
            for (String name : pairs[i + 1].strip().split("\\s+")) names.add(pairs[i] + name);
        return Set.copyOf(names);
    }

    private static Map<String, List<FieldRule>> fields()
    {
        Map<String, List<FieldRule>> schemas = new LinkedHashMap<>();
        dto(schemas, "AudioFormat", "sampleRateHz:I channels:I frames:J");
        dto(schemas, "FrameRange", "startInclusive:J endExclusive:J");
        dto(schemas, "MeasuredLoudness", "present:Z lufs:D");
        dto(schemas, "FrozenList", "elements:[Ljava/lang/Object;");
        dto(schemas, "LoudnessTimeline", "momentaryWindowFrames:I shortTermWindowFrames:I hopFrames:I momentaryPower:[D momentaryValid:Ljava/util/BitSet; shortTermLufs:[D shortTermValid:Ljava/util/BitSet; integrated:@MeasuredLoudness");
        dto(schemas, "FeatureTimeline", "hopFrames:J frames:@FrozenList");
        dto(schemas, "StructuralFrame", "centerFrame:J chroma12:[D spectral8:[D onsetFlux:D activity:D");
        dto(schemas, "SegmentLayout", "status:@LayoutStatus regions:@FrozenList");
        dto(schemas, "SegmentId", "ordinal:I");
        dto(schemas, "PcmSketch", "channels:I bins:I values:[[D");
        dto(schemas, "BodyContextVector", "previousActivityRatio:D nextActivityRatio:D entryLoudness12:D exitLoudness12:D leftNoveltyMad:D rightNoveltyMad:D");
        dto(schemas, "StructuralBin", "chroma12:[D spectral8:[D onsetFlux:D activity:D");
        dto(schemas, "SegmentDescriptor", "id:@SegmentId range:@FrameRange bins:@FrozenList validBinMask:J regionalLoudness:@MeasuredLoudness loudnessSlopeLuPerSec:D loudnessDeltaLu:D loudnessConsistency:D activitySlopePerSec:D activitySpread:D foregroundRatio:D context:@BodyContextVector sketch:@PcmSketch");
        dto(schemas, "ProtectionFlags", "reasonBits:J");
        dto(schemas, "ProtectionDecision", "id:@SegmentId flags:@ProtectionFlags");
        dto(schemas, "SimilarityScore", "h:D t:D a:D c:D chromaRotation:I validBins:I rejectionReason:@SimilarityRejectionReason");
        dto(schemas, "SimilarityMatrix", "segmentCount:I scores:@FrozenList");
        dto(schemas, "ComparableGroup", "ordinal:I memberOrdinals:[I memberQuality:[D confidence:D");
        dto(schemas, "ComparablePair", "firstOrdinal:I secondOrdinal:I confidence:D margin:D");
        dto(schemas, "GroupingResult", "groups:@FrozenList pairs:@FrozenList");
        dto(schemas, "ReferenceTarget", "segmentId:@SegmentId referenceLoudness:@MeasuredLoudness rawDb:D gConf:D confidenceWeightedDb:D reason:@ReferenceReason");
        dto(schemas, "ReferencePlan", "targets:@FrozenList weights:[D");
        dto(schemas, "OfficialSignalEvidence", "setId:Ljava/lang/String; setVersion:Ljava/lang/String; caseNumber:I signalId:Ljava/lang/String; signalSha256:Ljava/lang/String; sampleRateHz:I channels:I readingKind:@ReadingKind expectedLufs:D measuredLufs:D toleranceLu:D");
        dto(schemas, "RequiredSetReport", "setId:Ljava/lang/String; setVersion:Ljava/lang/String; manifestSha256:Ljava/lang/String; state:@ConformanceState evidence:@FrozenList reason:@ConformanceReason");
        dto(schemas, "StandardValidationReport", "requirementId:Ljava/lang/String; state:@ConformanceState algorithmId:Ljava/lang/String; algorithmSha256:Ljava/lang/String; profileSha256:Ljava/lang/String; attestationSha256:Ljava/lang/String; sets:@FrozenList createdAtEpochSecond:J");
        dto(schemas, "ShadowAnalysisResult", "status:@ShadowAnalysisStatus referencePlan:@ReferencePlan");
        dto(schemas, "ShadowAnalysisCache", "format:@AudioFormat pcmFingerprintSha256:[B algorithmId:Ljava/lang/String; profileId:Ljava/lang/String; loudness:@LoudnessTimeline features:@FeatureTimeline layout:@SegmentLayout descriptors:@FrozenList protections:@FrozenList similarity:@SimilarityMatrix grouping:@GroupingResult referencePlan:@ReferencePlan");
        dto(schemas, "DiagnosticEntry", "code:@DiagnosticCode segmentOrdinal:I relatedOrdinal:I occurrenceCount:J");
        dto(schemas, "ShadowDiagnostics", "status:@ShadowAnalysisStatus completedPhaseBits:J entries:@FrozenList standardValidation:@StandardValidationReport memoryCounters:@ShadowMemoryCounters");
        dto(schemas, "ShadowMemoryCounters", "denseEnvelopeCount:J denseEnvelopeElements:J timelineArrayCount:J timelinePrimitiveElements:J descriptorArrayCount:J descriptorPrimitiveElements:J sketchArrayCount:J sketchPrimitiveElements:J matrixScoreCount:J directBufferBytes:J retainedPcmRefs:J retainedChunkCount:J retainedSpectrumCount:J maxChunkFrames:J loudnessHops:J structuralHops:J segments:J pairs:J");
        dto(schemas, "ShadowAnalysisSnapshot", "result:@ShadowAnalysisResult cache:@ShadowAnalysisCache diagnostics:@ShadowDiagnostics");
        for (String name : "BodyContextGate BoundaryDetector ComparableGroupBuilder KWeightingAdapter LoudnessAnalyzer LoudnessConformanceGuard ProtectionClassifier ReferencePlanner SegmentComparator SegmentDescriptorBuilder StructuralFeatureExtractor".split(" "))
            schemas.put(LEVELER + name, List.of());
        schemas.put(LEVELER + "CancellationToken", List.of(new FieldRule("cancelled", "Z", 0x42)));
        schemas.put(LEVELER + "LevelerAnalysisEngine", List.of(new FieldRule("ALGORITHM_ID", "Ljava/lang/String;", 0x19)));
        schemas.put(LEVELER + "LevelerCalibrationProfile", List.of(
                new FieldRule("V1", "L" + LEVELER + "LevelerCalibrationProfile;", 0x19),
                new FieldRule("profileId", "Ljava/lang/String;", 0x12)));
        schemas.put(LEVELER + "LoudnessStandard", List.of(
                new FieldRule("BS1770_5", "L" + LEVELER + "LoudnessStandard;", 0x19),
                new FieldRule("standardId", "Ljava/lang/String;", 0x12)));
        enumFields(schemas, MODEL + "ConformanceState", "NOT_RUN UNAVAILABLE PARTIAL FAILED PASSED");
        enumFields(schemas, MODEL + "LayoutStatus", "READY INSUFFICIENT_FEATURES TOO_MANY_SEGMENTS");
        enumFields(schemas, MODEL + "ReadingKind", "MOMENTARY SHORT_TERM INTEGRATED");
        M004StaticClosureContract.mergeFields(schemas);
        return Map.copyOf(schemas);
    }

    private static void dto(Map<String, List<FieldRule>> schemas, String owner, String fields)
    {
        schemas.put(MODEL + owner, java.util.Arrays.stream(fields.split(" ")).map(spec ->
        {
            String[] parts = spec.split(":", 2);
            String descriptor = parts[1].startsWith("@") ? "L" + MODEL + parts[1].substring(1) + ";" : parts[1];
            return new FieldRule(parts[0], descriptor, 0x12);
        }).toList());
    }
    private static void enumFields(Map<String, List<FieldRule>> schemas, String owner, String constants)
    {
        java.util.ArrayList<FieldRule> fields = new java.util.ArrayList<>();
        for (String name : constants.split(" ")) fields.add(new FieldRule(name, "L" + owner + ";", 0x4019));
        fields.add(new FieldRule("$VALUES", "[L" + owner + ";", 0x101A));
        schemas.put(owner, List.copyOf(fields));
    }

    private static Set<Call> calls()
    {
        Set<Call> result = new LinkedHashSet<>();
        add(result, 0xB7, "java/lang/Object", "<init>", "()V");
        add(result, 0xB7, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V");
        for (String exception : List.of("IllegalArgumentException", "IllegalStateException", "ArithmeticException", "RuntimeException"))
            add(result, 0xB7, "java/lang/" + exception, "<init>", "()V (Ljava/lang/String;)V");
        for (String owner : List.of("java/lang/Math", "java/lang/StrictMath"))
        {
            add(result, 0xB8, owner, "abs", "(I)I (J)J (F)F (D)D");
            for (String name : List.of("min", "max")) add(result, 0xB8, owner, name, "(II)I (JJ)J (FF)F (DD)D");
            add(result, 0xB8, owner, "round", "(F)I (D)J");
            for (String name : List.of("ceil", "floor", "sqrt", "exp", "log", "log10", "sin", "cos")) add(result, 0xB8, owner, name, "(D)D");
            add(result, 0xB8, owner, "floorDiv", "(II)I (JI)J (JJ)J");
            add(result, 0xB8, owner, "addExact", "(II)I (JJ)J");
            add(result, 0xB8, owner, "multiplyExact", "(II)I (JI)J (JJ)J");
            add(result, 0xB8, owner, "pow", "(DD)D");
        }
        add(result, 0xB8, "java/lang/Double", "isFinite", "(D)Z");
        add(result, 0xB8, "java/lang/Float", "isFinite", "(F)Z");
        add(result, 0xB8, "java/lang/Double", "compare", "(DD)I");
        add(result, 0xB8, "java/lang/Float", "compare", "(FF)I");
        add(result, 0xB8, "java/lang/Integer", "compare", "(II)I");
        add(result, 0xB8, "java/lang/Long", "compare", "(JJ)I");
        add(result, 0xB8, "java/lang/Double", "doubleToRawLongBits", "(D)J");
        add(result, 0xB8, "java/lang/Double", "longBitsToDouble", "(J)D");
        add(result, 0xB8, "java/lang/Float", "floatToRawIntBits", "(F)I");
        add(result, 0xB8, "java/lang/Float", "intBitsToFloat", "(I)F");
        add(result, 0xB8, "java/lang/Double", "parseDouble", "(Ljava/lang/String;)D");
        add(result, 0xB8, "java/lang/Float", "parseFloat", "(Ljava/lang/String;)F");
        add(result, 0xB8, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I (Ljava/lang/String;I)I");
        add(result, 0xB8, "java/lang/Long", "parseLong", "(Ljava/lang/String;)J (Ljava/lang/String;I)J");
        add(result, 0xB6, "java/lang/String", "length", "()I");
        add(result, 0xB6, "java/lang/String", "charAt", "(I)C");
        add(result, 0xB6, "java/lang/String", "equals", "(Ljava/lang/Object;)Z");
        add(result, 0xB6, "java/lang/String", "hashCode", "()I");
        add(result, 0xB6, "java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B");
        add(result, 0xB8, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V");
        for (String element : List.of("Z", "B", "C", "S", "I", "J", "F", "D", "Ljava/lang/Object;"))
        {
            String array = "[" + element;
            add(result, 0xB8, "java/util/Arrays", "copyOf", "(" + array + "I)" + array);
            add(result, 0xB8, "java/util/Arrays", "fill", "(" + array + element + ")V (" + array + "II" + element + ")V");
            add(result, 0xB8, "java/util/Arrays", "equals", "(" + array + array + ")Z");
            add(result, 0xB8, "java/util/Arrays", "hashCode", "(" + array + ")I");
            if (!element.equals("Z")) add(result, 0xB8, "java/util/Arrays", "sort", "(" + array + ")V (" + array + "II)V");
        }
        add(result, 0xB8, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object; (Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
        add(result, 0xB7, "java/util/BitSet", "<init>", "(I)V");
        add(result, 0xB6, "java/util/BitSet", "set", "(I)V");
        add(result, 0xB6, "java/util/BitSet", "get", "(I)Z");
        add(result, 0xB6, "java/util/BitSet", "cardinality", "()I");
        add(result, 0xB6, "java/util/BitSet", "length", "()I");
        add(result, 0xB8, "java/security/MessageDigest", "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
        add(result, 0xB6, "java/security/MessageDigest", "update", "(B)V ([B)V");
        add(result, 0xB6, "java/security/MessageDigest", "digest", "()[B");
        result.addAll(M004StaticClosureContract.ADDITIONAL_CALLS);
        return Set.copyOf(result);
    }
    private static void add(Set<Call> set, int opcode, String owner, String name, String descriptors)
    { for (String descriptor : descriptors.split(" ")) set.add(new Call(opcode, owner, name, descriptor)); }
}
