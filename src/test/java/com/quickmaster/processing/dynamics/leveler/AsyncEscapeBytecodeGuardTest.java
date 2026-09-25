package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AsyncEscapeBytecodeGuardTest
{
    @Test
    void realProductMustSatisfyTheClosedContract() throws Exception
    {
        Path results = M004ClassfileFixtures.RESULTS;
        Files.createDirectories(results);
        Path run = Files.createTempDirectory(results, "product-");
        Path classes = Path.of(System.getProperty("qm.staticClasses", "target/classes"));
        // S-001 current-positive successor. The separate legacy scanner and all negative fixtures remain closed.
        var result = AsyncEscapeBytecodeGuard.scanActive(classes);
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        Files.writeString(run.resolve("guard-result.json"), gson.toJson(result));
        Files.writeString(run.resolve("jdk.txt"), System.getProperty("java.home") + "\n" + System.getProperty("java.version") + "\n");
        Map<String, Object> proposed = new TreeMap<>();
        proposed.put("status", "NOT_APPROVED_OBSERVED_INPUT_FOR_ADJUDICATION_ONLY");
        Map<String, Object> missingSchemas = new TreeMap<>();
        for (var violation : result.violations())
            if (violation.code().equals("FIELD_SCHEMA_UNADJUDICATED"))
            {
                var file = M004Classfile.parse(Files.readAllBytes(Path.of("target/classes", violation.classfile() + ".class")));
                missingSchemas.put(file.owner, file.fields);
            }
        proposed.put("unadjudicatedFieldSchemas", missingSchemas);
        proposed.put("unadjudicatedCalls", result.violations().stream().filter(v -> v.code().equals("BYTECODE_CALL_NOT_ALLOWED")).toList());
        proposed.put("vendoredDsparkSha256", AsyncEscapeBytecodeGuard.sha(Files.readAllBytes(Path.of("libs/dspark-0.1.0.jar"))));
        Files.writeString(run.resolve("compatibility-proposals-NOT-APPROVED.json"), gson.toJson(proposed));
        // This is a real positive acceptance test. A contract incompatibility stays red.
        result.requirePassed();
    }

    @Test
    void literalPolicyIsIndependentAndFullyEnumerated() throws Exception
    {
        assertEquals(67, M004BytecodePolicy.SCANNED_M004_CLASSFILES.size());
        assertEquals(7, M004BytecodePolicy.BOUNDARIES.size());
        assertTrue(M004BytecodePolicy.SCANNED_M004_CLASSFILES.stream().noneMatch(n -> n.contains("$")));
        assertFalse(M004BytecodePolicy.JDK_CALLS.contains(new M004BytecodePolicy.Call(0xB8, "java/lang/Math", "nextUp", "(D)D")));
        assertTrue(M004BytecodePolicy.JDK_CALLS.contains(new M004BytecodePolicy.Call(0xB8, "java/lang/Math", "toIntExact", "(J)I")), "ADR012 exact new permission; neighbor remains rejected");
        assertFalse(M004BytecodePolicy.JDK_CALLS.contains(new M004BytecodePolicy.Call(0xB8, "java/lang/Math", "toIntExact", "(J)J")));
    }

    @Test
    void universeRejectsMissingExtraAndNestedMembersWithADeletionControl()
    {
        Set<String> positive = M004BytecodePolicy.SCANNED_M004_CLASSFILES;
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.universeCheck(positive, false));
        for (String added : List.of(M004BytecodePolicy.LEVELER + "Unexpected", M004BytecodePolicy.LEVELER + "BoundaryDetector$1"))
        {
            Set<String> mutated = new TreeSet<>(positive); mutated.add(added);
            assertEquals(Set.of("CLASSFILE_UNIVERSE_EXTRA"), codes(AsyncEscapeBytecodeGuard.universeCheck(mutated, false)));
            assertEquals(List.of(), AsyncEscapeBytecodeGuard.universeCheck(mutated, true));
            assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.universeCheck(mutated, true).isEmpty()));
        }
        Set<String> missing = new TreeSet<>(positive); missing.remove(M004BytecodePolicy.LEVELER + "BoundaryDetector");
        assertEquals(Set.of("CLASSFILE_UNIVERSE_MISSING"), codes(AsyncEscapeBytecodeGuard.universeCheck(missing, false)));
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.universeCheck(missing, true));
        assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.universeCheck(missing, true).isEmpty()));
    }

    @Test
    void concatDoesNotGrantCodeOrSignaturesAccessToStructuralLinkageTypes() throws Exception
    {
        byte[] bytes = M004ClassfileFixtures.fieldless("static String text(int n) { return \"n=\"+n; } static Class<?> leak(Class<?> value) { return value; }");
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(bytes)).contains("BYTECODE_STRUCTURAL_LINKAGE_ESCAPE"));
    }

    @Test
    void fieldlessClassAndPermittedCallsArePositive() throws Exception
    {
        byte[] bytes = M004ClassfileFixtures.fieldless("static double valid(double x) { return Math.sqrt(Math.max(0d,x)); }");
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspect(bytes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"java/lang/Thread", "java/lang/ThreadLocal", "java/lang/InheritableThreadLocal",
            "java/util/concurrent/FutureTask", "java/util/concurrent/CompletableFuture", "java/util/concurrent/Executor",
            "java/util/stream/Stream", "java/util/Timer", "java/util/TimerTask", "java/lang/Runtime",
            "java/lang/ref/Cleaner", "java/lang/ref/Finalizer", "java/lang/reflect/Method", "java/lang/invoke/MethodHandles",
            "sun/misc/Unsafe", "jdk/internal/misc/Unsafe", "java/lang/ClassLoader", "java/util/ServiceLoader",
            "java/net/Socket", "java/nio/file/Files", "java/io/File", "com/quickmaster/UnapprovedHelper"})
    void unusedForbiddenReferencesAreRejectedAndDependencyRemovalIsDiscriminating(String owner) throws Exception
    {
        byte[] original = M004ClassfileFixtures.fieldless("");
        byte[] mutated = M004ClassfileFixtures.clazz(original, owner).bytes();
        String code = owner.startsWith("com/quickmaster/") ? "BYTECODE_DEPENDENCY_OUTSIDE_UNIVERSE" : "BYTECODE_DEPENDENCY_NOT_ALLOWED";
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(mutated)).contains(code));
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(mutated, Set.of(AsyncEscapeBytecodeGuard.Rule.DEPENDENCY), false));
        assertThrows(AssertionError.class, () -> assertTrue(codes(AsyncEscapeBytecodeGuard.inspectControl(mutated,
                Set.of(AsyncEscapeBytecodeGuard.Rule.DEPENDENCY), false)).contains(code)), "removing only dependency protection must break the negative oracle");
        M004ClassfileFixtures.persist("unused-dependency", original, mutated);
    }

    @Test
    void signatureAndFieldDescriptorsAreDependenciesWithoutAClassConstant() throws Exception
    {
        byte[] bytes = M004ClassfileFixtures.fieldless("static java.util.concurrent.Future<?> absent() { return null; }");
        var file = M004Classfile.parse(bytes);
        assertTrue(file.referencedClasses.contains("java/util/concurrent/Future"));
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(bytes)).contains("BYTECODE_DEPENDENCY_NOT_ALLOWED"));
    }

    @Test
    void literalCallsDiscriminateOpcodeOwnerNameDescriptorAndContext() throws Exception
    {
        byte[] bytes = M004ClassfileFixtures.fieldless("static double invalid(double x) { return Math.nextUp(x); }");
        var violation = AsyncEscapeBytecodeGuard.inspect(bytes).stream().filter(v -> v.code().equals("BYTECODE_CALL_NOT_ALLOWED")).findFirst().orElseThrow();
        assertEquals(0xB8, violation.opcode()); assertEquals("java/lang/Math", violation.owner());
        assertEquals("nextUp", violation.name()); assertEquals("(D)D", violation.descriptor());
        assertTrue(violation.offset() >= 0); assertEquals("invalid(D)D", violation.method());
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(bytes, Set.of(AsyncEscapeBytecodeGuard.Rule.CALLS), false));
        assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.inspectControl(bytes,
                Set.of(AsyncEscapeBytecodeGuard.Rule.CALLS), false).isEmpty()));
        byte[] digest = M004ClassfileFixtures.fieldless("static byte[] digest() throws java.security.NoSuchAlgorithmException { return java.security.MessageDigest.getInstance(\"SHA-256\").digest(); }");
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspect(digest));
        byte[] wrongDigest = M004ClassfileFixtures.fieldless("static byte[] digest() throws java.security.NoSuchAlgorithmException { return java.security.MessageDigest.getInstance(\"MD5\").digest(); }");
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(wrongDigest)).contains("BYTECODE_CALL_NOT_ALLOWED"));
    }

    @Test
    void nullStaticAndExtraInstanceFieldsCannotHideOutsideSchema() throws Exception
    {
        for (String declaration : List.of("private static byte[] hidden;", "private final int hidden=0;", "private double[] hidden;"))
        {
            byte[] bytes = M004ClassfileFixtures.fieldless(declaration);
            assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(bytes)).contains("FIELD_SCHEMA_MISMATCH"));
            assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(bytes, Set.of(AsyncEscapeBytecodeGuard.Rule.FIELDS), false));
            assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.inspectControl(bytes,
                    Set.of(AsyncEscapeBytecodeGuard.Rule.FIELDS), false).isEmpty()));
        }
    }

    @Test
    void putstaticIsAllowedOnlyForLiteralAtomInOwnClinit() throws Exception
    {
        String owner = M004BytecodePolicy.LEVELER + "LevelerCalibrationProfile";
        byte[] original = M004ClassfileFixtures.compile(owner.replace('/', '.'), """
                package com.quickmaster.processing.dynamics.leveler;
                public final class LevelerCalibrationProfile {
                  public static final LevelerCalibrationProfile V1 = new LevelerCalibrationProfile("QM-LEVELER-V1");
                  private final String profileId;
                  private LevelerCalibrationProfile(String id) { profileId=id; }
                }
                """).bytes();
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspect(original));
        var clinit = M004Classfile.parse(original).methods.stream().filter(m -> m.name().equals("<clinit>")).findFirst().orElseThrow();
        byte[] mutated = M004ClassfileFixtures.method(original, "<clinit>", "reset", "()V", 8,
                clinit.code().bytes(), clinit.code().maxStack(), clinit.code().maxLocals(), new int[0][]);
        assertEquals(Set.of("PRODUCTIVE_NON_CONSTANT_PUTSTATIC"), codes(AsyncEscapeBytecodeGuard.inspect(mutated)));
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(mutated, Set.of(AsyncEscapeBytecodeGuard.Rule.PUTSTATIC), false));
        assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.inspectControl(mutated,
                Set.of(AsyncEscapeBytecodeGuard.Rule.PUTSTATIC), false).isEmpty()));
        M004ClassfileFixtures.persist("putstatic-method", original, mutated);
    }

    @Test
    void canonicalStringConcatIsPositiveAndWrongBootstrapIsDiscriminated() throws Exception
    {
        byte[] original = M004ClassfileFixtures.fieldless("static String text(int value) { return \"value=\"+value; }");
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspect(original));
        var added = M004ClassfileFixtures.member(original, 10, "java/lang/Math", "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;");
        byte[] mutated = added.bytes().clone();
        var file = M004Classfile.parse(mutated);
        int handle = file.bootstraps.get(0).handle();
        M004ClassfileFixtures.putU2(mutated, file.pool[handle].start() + 2, added.index());
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(mutated)).contains("BYTECODE_BOOTSTRAP_NOT_ALLOWED"));
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(mutated, Set.of(AsyncEscapeBytecodeGuard.Rule.BOOTSTRAPS), false));
        assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.inspectControl(mutated,
                Set.of(AsyncEscapeBytecodeGuard.Rule.BOOTSTRAPS), false).isEmpty()));
        M004ClassfileFixtures.persist("bootstrap-owner", original, mutated);
    }

    @Test
    void lambdaObjectConcatAndExecutableMethodHandlesAreRejected() throws Exception
    {
        byte[] lambda = M004ClassfileFixtures.fieldless("static Runnable task() { return () -> {}; }");
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(lambda)).contains("BYTECODE_BOOTSTRAP_NOT_ALLOWED"));
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(lambda)).contains("BYTECODE_DEPENDENCY_NOT_ALLOWED"));
        byte[] object = M004ClassfileFixtures.fieldless("static String text(Object value) { return \"x=\"+value; }");
        assertFalse(AsyncEscapeBytecodeGuard.inspect(object).isEmpty(), "Object-to-String conversion/side effects are not permitted");
        byte[] original = M004ClassfileFixtures.fieldless("static String text(int value) { return \"value=\"+value; }");
        int handle = M004Classfile.parse(original).bootstraps.get(0).handle();
        byte[] mutated = M004ClassfileFixtures.addMethod(original, "loadHandle", "()V", 8,
                new byte[]{0x13, (byte) (handle >>> 8), (byte) handle, 0x57, (byte) 0xB1}, 1, 0);
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(mutated)).contains("BYTECODE_EXECUTABLE_HANDLE"));
    }

    @Test
    void everyEnumHasARelease17PositiveAndCanonicalExceptionIsNecessary() throws Exception
    {
        for (String owner : new TreeSet<>(M004BytecodePolicy.ENUMS))
        {
            String packageName = owner.substring(0, owner.lastIndexOf('/')).replace('/', '.');
            String name = owner.substring(owner.lastIndexOf('/') + 1);
            byte[] fixture = M004ClassfileFixtures.compile(owner.replace('/', '.'), "package " + packageName + "; public enum " + name + " { FIRST, SECOND }").bytes();
            assertEquals(List.of(), AsyncEscapeBytecodeGuard.enumCheck(fixture, false), owner);
            assertFalse(AsyncEscapeBytecodeGuard.enumCheck(fixture, true).isEmpty());
            assertThrows(AssertionError.class, () -> assertEquals(List.of(), AsyncEscapeBytecodeGuard.enumCheck(fixture, true)));
            byte[] real = Files.readAllBytes(Path.of(System.getProperty("qm.staticClasses", "target/classes"), owner + ".class"));
            assertEquals(List.of(), AsyncEscapeBytecodeGuard.enumCheck(real, false), owner + " actual compiled enum");
        }
    }

    @Test
    void eachEnumDeviationHasItsOwnMutant() throws Exception
    {
        String owner = M004BytecodePolicy.MODEL + "ConformanceState";
        byte[] original = M004ClassfileFixtures.compile(owner.replace('/', '.'), "package com.quickmaster.processing.dynamics.leveler.model; public enum ConformanceState { NOT_RUN, UNAVAILABLE, PARTIAL, FAILED, PASSED }").bytes();
        var method = M004Classfile.parse(original).methods.stream().filter(m -> m.name().equals("valueOf")).findFirst().orElseThrow();
        Map<String, byte[]> mutants = new LinkedHashMap<>();
        var wrongOwner = M004ClassfileFixtures.member(original, 10, "java/lang/Object", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;");
        mutants.put("call-owner", M004ClassfileFixtures.replaceOperand(wrongOwner.bytes(), "valueOf", 0xB8, wrongOwner.index()));
        var wrongDescriptor = M004ClassfileFixtures.member(original, 10, "java/lang/Enum", "valueOf", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Enum;");
        mutants.put("call-descriptor", M004ClassfileFixtures.replaceOperand(wrongDescriptor.bytes(), "valueOf", 0xB8, wrongDescriptor.index()));
        var literal = M004ClassfileFixtures.clazz(original, "java/lang/Object");
        mutants.put("literal", M004ClassfileFixtures.replaceOperand(literal.bytes(), "valueOf", 0x12, literal.index()));
        mutants.put("cast", M004ClassfileFixtures.replaceOperand(literal.bytes(), "valueOf", 0xC0, literal.index()));
        for (int flags : new int[]{8, 1, 0x1009, 0x49})
            mutants.put("access-" + flags, M004ClassfileFixtures.method(original, "valueOf", "valueOf", method.descriptor(), flags,
                    method.code().bytes(), 2, (flags & 8) == 0 ? 2 : 1, new int[0][]));
        mutants.put("method-name", M004ClassfileFixtures.method(original, "valueOf", "other", method.descriptor(), 9, method.code().bytes(), 2, 1, new int[0][]));
        mutants.put("method-descriptor", M004ClassfileFixtures.method(original, "valueOf", "valueOf", "(Ljava/lang/String;)Ljava/lang/Enum;", 9, method.code().bytes(), 2, 1, new int[0][]));
        byte[] reordered = method.code().bytes().clone();
        reordered[0] = 0x2A; reordered[1] = 0x12; reordered[2] = method.code().bytes()[1];
        mutants.put("instruction-order", M004ClassfileFixtures.method(original, "valueOf", "valueOf", method.descriptor(), 9, reordered, 2, 1, new int[0][]));
        byte[] extra = new byte[method.code().bytes().length + 1];
        System.arraycopy(method.code().bytes(), 0, extra, 1, extra.length - 1);
        mutants.put("extra-nop", M004ClassfileFixtures.method(original, "valueOf", "valueOf", method.descriptor(), 9, extra, 2, 1, new int[0][]));
        mutants.put("handler", M004ClassfileFixtures.method(original, "valueOf", "valueOf", method.descriptor(), 9, method.code().bytes(), 2, 1, new int[][]{{0, 2, 9, 0}}));
        var extraCall = M004ClassfileFixtures.member(original, 10, "java/lang/Math", "abs", "(I)I");
        byte[] withCall = new byte[method.code().bytes().length + 5];
        withCall[0] = 3; withCall[1] = (byte) 0xB8; withCall[2] = (byte) (extraCall.index() >>> 8); withCall[3] = (byte) extraCall.index(); withCall[4] = 0x57;
        System.arraycopy(method.code().bytes(), 0, withCall, 5, method.code().bytes().length);
        mutants.put("extra-call", M004ClassfileFixtures.method(extraCall.bytes(), "valueOf", "valueOf", method.descriptor(), 9, withCall, 2, 1, new int[0][]));
        for (var entry : mutants.entrySet())
        {
            assertEquals(Set.of(AsyncEscapeBytecodeGuard.ENUM_FAILURE), codes(AsyncEscapeBytecodeGuard.enumCheck(entry.getValue(), false)), entry.getKey());
            M004ClassfileFixtures.persist("enum-" + entry.getKey(), original, entry.getValue());
        }
        byte[] ldcWide = new byte[method.code().bytes().length + 1];
        ldcWide[0] = 0x13; ldcWide[1] = 0; ldcWide[2] = method.code().bytes()[1];
        System.arraycopy(method.code().bytes(), 2, ldcWide, 3, method.code().bytes().length - 2);
        byte[] widePositive = M004ClassfileFixtures.method(original, "valueOf", "valueOf", method.descriptor(), 9, ldcWide, 2, 1, new int[0][]);
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.enumCheck(widePositive, false));
    }

    @Test
    void finalizerAndNativeMethodTableRulesAreIndependentOfInvokesAndGc() throws Exception
    {
        byte[] original = M004ClassfileFixtures.fieldless("");
        byte[] finalizer = M004ClassfileFixtures.addMethod(original, "finalize", "()V", 4, new byte[]{(byte) 0xB1}, 0, 1);
        assertEquals(Set.of("FINALIZER_DECLARATION"), codes(AsyncEscapeBytecodeGuard.inspect(finalizer)));
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(finalizer, Set.of(AsyncEscapeBytecodeGuard.Rule.FINALIZER), false));
        assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.inspectControl(finalizer,
                Set.of(AsyncEscapeBytecodeGuard.Rule.FINALIZER), false).isEmpty()));
        byte[] nativeMethod = M004ClassfileFixtures.addMethod(original, "nativeAction", "()V", 0x104, null, 0, 0);
        assertEquals(Set.of("NATIVE_METHOD_DECLARATION"), codes(AsyncEscapeBytecodeGuard.inspect(nativeMethod)));
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(nativeMethod, Set.of(AsyncEscapeBytecodeGuard.Rule.NATIVE), false));
        assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.inspectControl(nativeMethod,
                Set.of(AsyncEscapeBytecodeGuard.Rule.NATIVE), false).isEmpty()));
        M004ClassfileFixtures.persist("finalizer-empty", original, finalizer);
        M004ClassfileFixtures.persist("native-no-code", original, nativeMethod);
        for (int flags : new int[]{0, 1, 2, 4, 0x1004})
            for (byte[] code : new byte[][]{null, new byte[]{(byte) 0xB1}})
            {
                byte[] bytes = M004ClassfileFixtures.addMethod(original, "finalize", "()V", flags, code, 0, 1);
                assertEquals(Set.of("FINALIZER_DECLARATION"), codes(AsyncEscapeBytecodeGuard.inspect(bytes)));
            }
        for (int flags : new int[]{0x100, 0x109, 0x1104})
            for (byte[] code : new byte[][]{null, new byte[]{(byte) 0xB1}})
            {
                byte[] bytes = M004ClassfileFixtures.addMethod(original, "nativeAction", "()V", flags, code, 0, (flags & 8) == 0 ? 1 : 0);
                assertEquals(Set.of("NATIVE_METHOD_DECLARATION"), codes(AsyncEscapeBytecodeGuard.inspect(bytes)));
            }
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspect(M004ClassfileFixtures.addMethod(original, "finalize", "()V", 8, new byte[]{(byte) 0xB1}, 0, 0)), "static finalize is not an instance finalizer");
    }

    @Test
    void boundariesKeepTheirHashAndReceiveTheNarrowMethodTableScan() throws Exception
    {
        for (var boundary : M004BytecodePolicy.BOUNDARIES.entrySet())
        {
            byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("qm.staticClasses", "target/classes"), boundary.getKey() + ".class"));
            if (ActiveLevelerGuardContract.boundaries().containsKey(boundary.getKey()))
                assertEquals(boundary.getValue(), AsyncEscapeBytecodeGuard.sha(bytes), boundary.getKey());
            else
                assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectActive(bytes), "Explicit active semantic boundary: " + boundary.getKey());
            assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(bytes, Set.of(), true));
            byte[] nativeMethod = M004ClassfileFixtures.addMethod(bytes, "nativeAction", "()V", 0x109, null, 0, 0);
            assertEquals(Set.of("NATIVE_METHOD_DECLARATION"), codes(AsyncEscapeBytecodeGuard.inspectControl(nativeMethod, Set.of(), true)));
        }
    }

    private static Set<String> codes(List<AsyncEscapeBytecodeGuard.Violation> violations)
    { return violations.stream().map(AsyncEscapeBytecodeGuard.Violation::code).collect(java.util.stream.Collectors.toSet()); }
}
