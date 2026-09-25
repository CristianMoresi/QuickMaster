package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual S-001 bytes and separately compiled neighbors; no observed whitelist generation. */
class ActiveLevelerGuardTest
{
    private static final String HISTORICAL_ENTRY_MUTANT_SHA = "f34b349e6d47c67b3ef680f11ebc4b801f81104fec41ce2e2be8b35ff4bb6cc0";
    private static final String COMPARISON_V2_ENTRY_MUTANT_SHA = "6c68dd82532bf5dbe2cd63e4dbc6f6ad2b0fbccc1c9bee02d93fe8ab7dfce16f";
    @Test void currentActiveProductSatisfiesExplicitSuccessor() throws Exception
    {
        var result = AsyncEscapeBytecodeGuard.scanActive(classes());
        assertTrue(result.passed(), "ACTIVE_STATIC_SUCCESSOR_REQUIRED\n" + result.violations());
    }

    @Test void contextMaskSuccessorClosesDeclarationsCallsAndHistoricalBoundary() throws Exception {
        String p="com/quickmaster/processing/dynamics/leveler/",vector=p+"model/BodyContextVector",builder=p+"SegmentDescriptorBuilder",gate=p+"BodyContextGate";
        for(String owner:List.of(vector,builder,gate))assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(compile(owner,source(owner))),owner);
        byte[] actual=compile(vector,source(vector));
        assertTrue(AsyncEscapeBytecodeGuard.inspect(actual).stream().anyMatch(v->v.code().equals("FIELD_SCHEMA_MISMATCH")),"Historical six-double contract must not widen");
        assertRejected(compile(vector,source(vector).replace("private final int loudnessAvailabilityMask;","private final int loudnessAvailabilityMask; private int extra;")),"FIELD_SCHEMA_MISMATCH");
        assertRejected(compile(vector,source(vector).replace("private final int loudnessAvailabilityMask;","private final long loudnessAvailabilityMask;").replace("return loudnessAvailabilityMask;","return (int) loudnessAvailabilityMask;")),"FIELD_SCHEMA_MISMATCH");
        assertRejected(compile(vector,source(vector).replace("return loudnessAvailabilityMask;","return 7;")),"ACTIVE_CONTEXT_MASK_GETTER");
        assertRejected(compile(builder,source(builder).replace("private static MeasuredLoudness contextualLoudness(","public int extra() { return 0; }\n    private static MeasuredLoudness contextualLoudness(")),"ACTIVE_METHOD_TABLE");
        assertRejected(compile(gate,source(gate).replace("if (first == null || second == null) return SimilarityRejectionReason.NON_FINITE;","new Thread().start(); if (first == null || second == null) return SimilarityRejectionReason.NON_FINITE;")),"BYTECODE_CALL_NOT_ALLOWED");
    }

    @Test void contextCallAllowanceIsMethodSpecificAndHasCausalDeletionControl() throws Exception {
        String owner="com/quickmaster/processing/dynamics/leveler/SegmentDescriptorBuilder",original=source(owner);
        byte[] neighbor=compile(owner,original.replace("long hop = timeline.hopFrames();","new LevelerAnalysisEngine(); long hop = timeline.hopFrames();"));
        assertRejected(neighbor,"ACTIVE_CONTEXT_CALL_TABLE");
        var removed=Set.of(AsyncEscapeBytecodeGuard.Rule.ACTIVE_COMPARISON_CALLS);
        List<AsyncEscapeBytecodeGuard.Violation> remaining=new ArrayList<>(AsyncEscapeBytecodeGuard.inspectActiveControl(neighbor,removed,false));
        remaining.addAll(M004StaticClosurePolicy.inspectActive(M004Classfile.parse(neighbor),removed));assertEquals(List.of(),remaining);
        byte[] wrongCaller=compile(owner,original.replace("return Math.max(minimum, Math.min(maximum, value));","Math.subtractExact(2L, 1L); return Math.max(minimum, Math.min(maximum, value));"));
        assertRejected(wrongCaller,"BYTECODE_CALL_NOT_ALLOWED");
    }

    @Test void comparisonV2DeclarationsCallsAndConstantsAreClosed() throws Exception
    {
        String prefix="com/quickmaster/processing/dynamics/leveler/";
        for(String owner:List.of(prefix+"model/ComparisonTimeline",prefix+"ComparisonFeatureExtractor",prefix+"ComparisonComparator"))
            assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(compile(owner,source(owner))),"COMPARISON_V2_SOURCE_CLOSURE_REQUIRED:"+owner);
        String timeline=prefix+"model/ComparisonTimeline",extractor=prefix+"ComparisonFeatureExtractor",comparator=prefix+"ComparisonComparator";
        assertRejected(compile(timeline,source(timeline).replace("private final AudioFormat format;","private final AudioFormat format; private float[] retainedPcm;")),"FIELD_SCHEMA_MISMATCH");
        assertRejected(compile(extractor,source(extractor).replace("public final class ComparisonFeatureExtractor\n{","public final class ComparisonFeatureExtractor\n{\n public void extra() {}")),"ACTIVE_METHOD_TABLE");
        assertRejected(compile(comparator,source(comparator).replace("DECODED = 96","DECODED = 97")),"STATIC_CONSTANT_VALUE");
        assertRejected(compile(extractor,source(extractor).replace("return token != null && token.isCancelled();","new LevelerAnalysisEngine(); return token != null && token.isCancelled();")),"ACTIVE_COMPARISON_CALL_TABLE");
        assertRejected(compile(comparator,source(comparator).replace("private static double clamp(double value) { return Math.max(0.0d, Math.min(1.0d, value)); }","private static double clamp(double value) { new Thread().start(); return Math.max(0.0d, Math.min(1.0d, value)); }")),"BYTECODE_CALL_NOT_ALLOWED");
    }

    @Test void comparisonProfileKeepsBothExactAtomsAndHistoricalThresholds() throws Exception
    {
        String owner="com/quickmaster/processing/dynamics/leveler/LevelerCalibrationProfile",original=source(owner);
        assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(compile(owner,original)),"COMPARISON_V2_PROFILE_REQUIRED");
        for(String changed:List.of(original.replace("QM-LEVELER-V2","QM-LEVELER-V9"),original.replace("groupH() { return 0.85d; }","groupH() { return 0.84d; }")))
            assertRejected(compile(owner,changed),"STATIC_FIXED_PROFILE");
    }
    @Test void deletingComparisonCallClosureReopensOnlyItsNeighbor() throws Exception {
        String owner="com/quickmaster/processing/dynamics/leveler/ComparisonFeatureExtractor";
        byte[] unchanged=compile(owner,source(owner));
        assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(unchanged));
        byte[] neighbor=compile(owner,source(owner).replace("return token != null && token.isCancelled();","new LevelerAnalysisEngine(); return token != null && token.isCancelled();"));
        assertRejected(neighbor,"ACTIVE_COMPARISON_CALL_TABLE");
        var removed=Set.of(AsyncEscapeBytecodeGuard.Rule.ACTIVE_COMPARISON_CALLS);
        List<AsyncEscapeBytecodeGuard.Violation> remaining=new ArrayList<>(AsyncEscapeBytecodeGuard.inspectActiveControl(neighbor,removed,false));
        remaining.addAll(M004StaticClosurePolicy.inspectActive(M004Classfile.parse(neighbor),removed));
        assertEquals(List.of(),remaining,"Deleting only the new per-method call inventory must reopen the valid existing-owner neighbor");
    }

    @Test void extraScalarAndPcmRetentionRemainForbidden() throws Exception
    {
        String source = source("com/quickmaster/processing/dynamics/LevelerProcessor");
        for (String field : List.of("private long extra;", "private float[] retainedPcm;"))
        {
            byte[] bytes = compile("com/quickmaster/processing/dynamics/LevelerProcessor",
                    source.replace("private volatile double speed", field + "\nprivate volatile double speed"));
            assertRejected(bytes, "FIELD_SCHEMA_MISMATCH");
        }
    }

    @Test void asynchronousEscapeIsRejectedEvenInsideAnExistingMethod() throws Exception
    {
        String owner = "com/quickmaster/processing/dynamics/LevelerProcessor";
        byte[] bytes = compile(owner, source(owner).replace("public double getSpeed() { return speed; }",
                "public double getSpeed() { new Thread().start(); return speed; }"));
        assertRejected(bytes, "BYTECODE_DEPENDENCY_NOT_ALLOWED");
        assertRejected(bytes, "BYTECODE_CALL_NOT_ALLOWED");
    }

    @Test void cachedReportCannotReplaceCurrentBuildAuthority() throws Exception
    {
        String owner = "com/quickmaster/processing/dynamics/leveler/LoudnessConformanceGuard";
        byte[] bytes = compile(owner, source(owner).replace(
                "report != null && report.matches(ConformanceArtifactLoader.currentBuildBinding())", "report != null"));
        assertRejected(bytes, "ACTIVE_AUTHORITY_CODE");
    }

    @Test void sameFormatCannotReplaceFullPcmCacheIdentity() throws Exception
    {
        String owner = "com/quickmaster/processing/dynamics/LevelerProcessor";
        byte[] bytes = compile(owner, source(owner).replace(
                "Arrays.equals(cache.copyPcmFingerprintSha256(), fingerprint)", "true"));
        assertRejected(bytes, "ACTIVE_CACHE_IDENTITY_CODE");
    }

    @Test void callerCannotIgnoreCurrentAuthorityOrSafetyProof() throws Exception
    {
        String owner="com/quickmaster/processing/dynamics/LevelerProcessor",source=source(owner);
        byte[] noAuthority=compile(owner,source.replace("!new LoudnessConformanceGuard().authorizesCurrentBuild(next.diagnostics().standardValidation())","false"));
        assertRejected(noAuthority,"ACTIVE_AUTHORITY_CALLSITE");
        byte[] noProof=compile(owner,source.replace("safe == null || !safe.proof().proven()","safe == null"));
        assertRejected(noProof,"ACTIVE_AUTHORITY_CALLSITE");
    }

    @Test void publicEntryCannotBypassTheCurrentAuthorityProtocol() throws Exception
    {
        String owner="com/quickmaster/processing/dynamics/LevelerProcessor",original=source(owner);
        assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(compile(owner,original)),"Unchanged independent compilation must pass");
        String entry="analyze(samples, channels, new CancellationToken());";
        assertEquals(original.indexOf(entry),original.lastIndexOf(entry));assertTrue(original.contains(entry));
        String bypass="publishStructural(new TruePeakSafety().constrain(samples, new AudioFormat(analysisRateHz(), channels, samples.length / channels), new GainPlanner().plan(shadowAnalysis.cache(), new ControlState(leveling, speed)), new CancellationToken()).schedule(), channels);";
        byte[] mutant=compile(owner,original.replace(entry,bypass));
        byte[] historical = compile(owner, historicalCacheSource(original).replace(entry,bypass));
        assertEquals(HISTORICAL_ENTRY_MUTANT_SHA,AsyncEscapeBytecodeGuard.sha(historical),"Exact independent historical Critic control preserved");
        assertRejected(historical,"ACTIVE_ENTRY_AUTHORITY_CODE");
        assertRejected(historical,"ACTIVE_PUBLICATION_CALLER");
        assertEquals(COMPARISON_V2_ENTRY_MUTANT_SHA,AsyncEscapeBytecodeGuard.sha(mutant),"Explicit current V2 source neighbor; not the historical class");
        assertRejected(mutant,"ACTIVE_ENTRY_AUTHORITY_CODE");
        assertRejected(mutant,"ACTIVE_PUBLICATION_CALLER");
    }

    @Test void publicationCallsCannotMoveToExistingUnprivilegedMethods() throws Exception
    {
        String owner="com/quickmaster/processing/dynamics/LevelerProcessor",original=source(owner);
        for(String call:List.of("publishStructural(null, 1);", "super.publishStructural(null, 1);",
                "adoptPublication(publishedGain());", "super.adoptPublication(publishedGain());",
                "adoptEnvelope(this);", "super.adoptEnvelope(this);", "remap();", "super.remap();",
                "analyze(null, 1);", "super.analyze(null, 1);"))
            assertRejected(compile(owner,original.replace("public double getSpeed() { return speed; }",
                    "public double getSpeed() { "+call+" return speed; }")),"ACTIVE_PUBLICATION_CALLER");
        String base="com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor";
        assertRejected(compile(base,source(base).replace("public double getGainReductionDb() { return currentGrDb; }",
                "public double getGainReductionDb() { adoptPublication(published); return currentGrDb; }")),"ACTIVE_PUBLICATION_CALLER");
        assertRejected(compile(base,source(base).replace("public double getGainReductionDb() { return currentGrDb; }",
                "public double getGainReductionDb() { new PublishedGain(published.schedule(), published.sourceRateHz(), published.sourceChannels(), publicationSequence, published.status()); return currentGrDb; }")),"ACTIVE_PUBLICATION_CALLER");
    }

    @Test void publicationAndGenerationWritesCannotMoveToAnExistingBaseGetter() throws Exception
    {
        String base="com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor",original=source(base);
        assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(compile(base,original)));
        for(String write:List.of("published = null;", "publicationSequence = 0L;"))
            assertRejected(compile(base,original.replace("public double getGainReductionDb() { return currentGrDb; }",
                    "public double getGainReductionDb() { "+write+" return currentGrDb; }")),"ACTIVE_PUBLICATION_WRITE");
    }

    @Test void everyInheritedPublicationWriterAndCaptureHelperKeepsItsSemantics() throws Exception
    {
        String base="com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor",original=source(base);
        assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(compile(base,original)));
        String[][] changes={
            {"private volatile PublishedGain published", "PublishedGain.unit(0L, AnalysisStatus.UNIT)", "PublishedGain.unit(1L, AnalysisStatus.UNIT)"},
            {"public synchronized void analyze(", "schedule, preparedRateHz, channels, generation, AnalysisStatus.LEGACY_READY", "schedule, preparedRateHz, channels, 0L, AnalysisStatus.LEGACY_READY"},
            {"protected final synchronized void publishStructural(", "++publicationSequence, AnalysisStatus.STRUCTURAL_READY", "publicationSequence, AnalysisStatus.STRUCTURAL_READY"},
            {"protected final synchronized void publishUnit(", "PublishedGain.unit(++publicationSequence, status)", "PublishedGain.unit(publicationSequence, status)"},
            {"protected synchronized void remap()", "channels, generation, AnalysisStatus.LEGACY_READY", "channels, 0L, AnalysisStatus.LEGACY_READY"},
            {"public synchronized void adoptEnvelope(", "PublishedGain sourcePublication = src.published;", "PublishedGain sourcePublication = this.published;"},
            {"final synchronized void adoptPublication(", "sourcePublication.analysisGeneration()) + 1", "sourcePublication.analysisGeneration()) + 0"},
            {"synchronized void clearAnalysis()", "PublishedGain.unit(generation, AnalysisStatus.CLEARED)", "PublishedGain.unit(0L, AnalysisStatus.CLEARED)"},
            {"PublishedGain publishedGain()", "return published;", "return PublishedGain.unit(0L, AnalysisStatus.UNIT);"},
            {"protected final int analysisRateHz()", "return preparedRateHz;", "return 48000;"},
            {"private void clearLegacyState()", "envRate = 0.0;", "envRate = 1.0;"}
        };
        for(String[] change:changes)assertRejected(compile(base,replaceAfter(original,change[0],change[1],change[2])),"ACTIVE_PUBLICATION_HELPER_CODE");
    }

    @Test void inheritedPublicationExceptionProtocolsRemainExact() throws Exception
    {
        String base="com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor",original=source(base);
        for(String method:List.of("public synchronized void analyze(","protected synchronized void remap()"))
            assertRejected(compile(base,replaceAfter(original,method,
                    "catch (IllegalArgumentException | IllegalStateException ex)","catch (IllegalArgumentException ex)")),"ACTIVE_PUBLICATION_HELPER_CODE");
    }

    @Test void removingOnlyThePublicationRuleReopensItsCausalNeighbors() throws Exception
    {
        String base="com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor",original=source(base);
        byte[] baseline=compile(base,original);
        assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(baseline));
        assertEquals(List.of(),withoutPublicationRule(baseline));
        for(String statement:List.of("published = null;","publicationSequence = 0L;","adoptPublication(published);")) {
            byte[] mutant=compile(base,original.replace("public double getGainReductionDb() { return currentGrDb; }",
                    "public double getGainReductionDb() { "+statement+" return currentGrDb; }"));
            assertFalse(AsyncEscapeBytecodeGuard.inspectActive(mutant).isEmpty());
            assertEquals(List.of(),withoutPublicationRule(mutant),"Only publication authority rule must cause this valid neighbor rejection");
        }
        byte[] helper=compile(base,replaceAfter(original,"final synchronized void adoptPublication(",
                "sourcePublication.analysisGeneration()) + 1","sourcePublication.analysisGeneration()) + 0"));
        assertRejected(helper,"ACTIVE_PUBLICATION_HELPER_CODE");assertEquals(List.of(),withoutPublicationRule(helper));
        String leveler="com/quickmaster/processing/dynamics/LevelerProcessor";
        String bypass="publishStructural(new TruePeakSafety().constrain(samples, new AudioFormat(analysisRateHz(), channels, samples.length / channels), new GainPlanner().plan(shadowAnalysis.cache(), new ControlState(leveling, speed)), new CancellationToken()).schedule(), channels);";
        byte[] entry=compile(leveler,source(leveler).replace("analyze(samples, channels, new CancellationToken());",bypass));
        assertEquals(COMPARISON_V2_ENTRY_MUTANT_SHA,AsyncEscapeBytecodeGuard.sha(entry));
        assertEquals(List.of(),withoutPublicationRule(entry));
        byte[] asynchronous=compile(leveler,source(leveler).replace("public double getSpeed() { return speed; }",
                "public double getSpeed() { new Thread().start(); return speed; }"));
        assertTrue(withoutPublicationRule(asynchronous).stream().anyMatch(v->v.code().equals("BYTECODE_CALL_NOT_ALLOWED")),"Deletion cannot relax unrelated escape controls");
    }

    @Test void everyMonitorExitAndCatchRangeRemainsNecessary() throws Exception
    {
        byte[] original=Files.readAllBytes(classes().resolve("com/quickmaster/processing/dynamics/LevelerProcessor.class"));
        var f=M004Classfile.parse(original);var m=f.methods.stream().filter(x->x.name().equals("adoptEnvelope")).findFirst().orElseThrow();
        int exits=0;
        for(var i:m.code().instructions())if(i.opcode()==195){byte[] bad=original.clone();bad[m.code().absoluteStart()+i.offset()]=87;assertRejected(bad,"ACTIVE_LEVELER_CLEANUP_CODE");exits++;}
        assertEquals(5,exits);
        int table=m.code().absoluteStart()+m.code().bytes().length+2;
        for(int h=0;h<m.code().handlers().size();h++)for(int c=0;c<4;c++) {
            byte[] bad=original.clone();bad[table+h*8+c*2+1]^=1;
            try { assertRejected(bad,"ACTIVE_LEVELER_CLEANUP_CODE"); }
            catch(IllegalArgumentException malformed) {
                assertTrue(malformed.getMessage().startsWith(M004Classfile.FORMAT));
                // A malformed range is a parser rejection, not a JVM-valid cleanup neighbor.
            }
        }
    }

    @Test void throwablePermissionDoesNotTransferToWrongCallerOrCause() throws Exception
    {
        byte[] caller=M004ClassfileFixtures.fieldless("static void wrong(){try { java.security.MessageDigest.getInstance(\"SHA-256\"); }catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(\"x\",ex);}}");
        assertRejected(caller,"BYTECODE_METADATA_ROLE_NOT_ALLOWED");
        String owner="com/quickmaster/processing/dynamics/LevelerProcessor";
        byte[] wrong=compile(owner,source(owner).replace("new IllegalStateException(\"SHA-256 is required for analysis identity.\", ex)","new IllegalStateException(\"SHA-256 is required for analysis identity.\", new IllegalArgumentException())"));
        assertRejected(wrong,"ACTIVE_LEVELER_CLEANUP_CODE");
    }

    @Test void coefficientShapeValueAndStaticArrayEscapeRemainForbidden() throws Exception
    {
        String owner="com/quickmaster/processing/dynamics/leveler/FiniteTruePeakStream",source=source(owner);
        for(String changed:List.of(source.replace(".001708984375",".001708984376"),
                source.replace("{.001708984375,","{0,.001708984375,")))assertRejected(compile(owner,changed),"ACTIVE_ANNEX2_CODE");
        assertRejected(compile(owner,source.replace("private final int channels;","private static final double[] extra = new double[2];\nprivate final int channels;")),"FIELD_SCHEMA_MISMATCH");
        assertRejected(compile(owner,source.replace("public double tailMaximum(){return tailMaximum;}","public double tailMaximum(){ANNEX2[0][0]=0; return tailMaximum;}")),"ACTIVE_ANNEX2_CODE");
    }

    @Test void recordPublicationAndSealedBoundaryAreSemanticNotDebugHashes() throws Exception
    {
        String owner="com/quickmaster/processing/dynamics/PublishedGain";
        byte[] stripped=compile(owner,source(owner)); // independently compiled -g:none by the fixed fixture compiler
        assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(stripped));
        assertRejected(compile(owner,source(owner).replace("return (status == AnalysisStatus.LEGACY_READY || status == AnalysisStatus.STRUCTURAL_READY)","return true || (status == AnalysisStatus.LEGACY_READY || status == AnalysisStatus.STRUCTURAL_READY)")),"ACTIVE_PUBLICATION_CODE");
        String domain="com/quickmaster/processing/dynamics/GainDomain";
        assertEquals(List.of(),AsyncEscapeBytecodeGuard.inspectActive(compile(domain,"package com.quickmaster.processing.dynamics; enum GainDomain { LEGACY_LINEAR, SPARSE_DB }")));
    }

    private static void assertRejected(byte[] bytes, String code)
    {
        var violations = AsyncEscapeBytecodeGuard.inspectActive(bytes);
        assertTrue(violations.stream().anyMatch(v -> v.code().equals(code)), code + " not detected: " + violations);
    }
    private static List<AsyncEscapeBytecodeGuard.Violation> withoutPublicationRule(byte[] bytes)
    {
        var removed=Set.of(AsyncEscapeBytecodeGuard.Rule.ACTIVE_PUBLICATION_AUTHORITY);
        List<AsyncEscapeBytecodeGuard.Violation> out=new ArrayList<>(AsyncEscapeBytecodeGuard.inspectActiveControl(bytes,removed,false));
        out.addAll(M004StaticClosurePolicy.inspectActive(M004Classfile.parse(bytes),removed));return List.copyOf(out);
    }
    private static String replaceAfter(String original,String anchor,String from,String to)
    {
        int start=original.indexOf(anchor);assertTrue(start>=0,"Missing source anchor "+anchor);
        int at=original.indexOf(from,start);assertTrue(at>=start,"Missing source neighbor "+from);
        return original.substring(0,at)+to+original.substring(at+from.length());
    }
    /** Reconstruct only the reviewed historical cache predicate; the byte hash proves exact identity. */
    private static String historicalCacheSource(String current)
    {
        String comparison = "                && cache.comparison() != null\n"
                + "                && cache.comparison().format() == cache.format()\n";
        assertTrue(current.contains(comparison));
        return current.replace(comparison, "")
                .replace("cache.profileId().equals(LevelerCalibrationProfile.V2.profileId())", "cache.profileId().equals(LevelerCalibrationProfile.V1.profileId())")
                .replace("cache.algorithmId().equals(LevelerAnalysisEngine.ALGORITHM_ID)", "cache.algorithmId().equals(\"QM-LEVELER-SHADOW-M004-V1\")");
    }
    private static Path classes() { return Path.of(System.getProperty("qm.staticClasses", "target/classes")); }
    private static String source(String owner) throws Exception
    { return Files.readString(Path.of(System.getProperty("qm.activeSources", "src/main/java"), owner + ".java")); }
    private static byte[] compile(String owner, String source) throws Exception
    { return M004ClassfileFixtures.compile(owner.replace('/', '.'), source).bytes(); }
}
