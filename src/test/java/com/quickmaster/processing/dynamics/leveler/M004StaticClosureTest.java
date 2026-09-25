package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** SB-P1..P6 and SB-N1..N7; SB-P7 lives in the dynamics compatibility suite. */
class M004StaticClosureTest
{
    private static final String L = M004StaticClosureContract.L, M = M004StaticClosureContract.M;

    @Test void sbP1ExactLegacyAbstractAndSbN1EveryOtherAbstractIsRejected() throws Exception
    {
        byte[] original = M004StaticClosureFixtures.real(M004StaticClosureContract.A);
        var f = M004Classfile.parse(original);
        assertEquals(0x421, f.access); assertEquals(11, f.fields.size());
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectActiveControl(original, Set.of(), false));
        assertEquals(List.of(), M004StaticClosurePolicy.inspectActive(M004Classfile.parse(original), Set.of()));
        for (String name : List.of("computeFeatures", "mapFeaturesToGain"))
        {
            var method = f.methods.stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
            for (int flags : new int[]{0x401, 0x402, 0x1404})
            {
                byte[] bad = M004ClassfileFixtures.method(original, name, name, method.descriptor(), flags, null, 0, 0, new int[0][]);
                assertTrue(codes(AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(), false)).contains("ABSTRACT_METHOD_OUTSIDE_BOUNDARY"));
                assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(AsyncEscapeBytecodeGuard.Rule.ABSTRACT), false));
            }
            byte[] code = M004ClassfileFixtures.method(original, name, name, method.descriptor(), 0x404, new byte[]{(byte)0xb1}, 0, name.equals("computeFeatures") ? 5 : 1, new int[0][]);
            assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(code)).contains("ABSTRACT_METHOD_OUTSIDE_BOUNDARY"));
        }
        byte[] third = M004ClassfileFixtures.addMethod(original, "third", "()V", 0x404, null, 0, 0);
        rejects(third, AsyncEscapeBytecodeGuard.Rule.ABSTRACT, "STATIC_ABSTRACT_TABLE");
        byte[] unrelated = M004ClassfileFixtures.addMethod(M004ClassfileFixtures.fieldless(""), "third", "()V", 0x404, null, 0, 0);
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(unrelated)).contains("ABSTRACT_METHOD_OUTSIDE_BOUNDARY"));
    }

    @Test void sbP2AllElevenEnumBodiesAndSbN4EveryCanonicalBodyIsNecessary() throws Exception
    {
        for (var entry : M004StaticClosureContract.ENUMS.entrySet())
        {
            byte[] original = M004StaticClosureFixtures.real(entry.getKey()); assertEquals(List.of(), M004StaticClosurePolicy.inspect(original), entry.getKey());
            for (String method : List.of("<init>", "values", "valueOf", "$values", "<clinit>"))
            {
                byte[] bad = M004StaticClosureFixtures.nop(original, method);
                rejects(bad, AsyncEscapeBytecodeGuard.Rule.CLOSED_ENUM, "STATIC_ENUM_PROTOCOL");
                M004ClassfileFixtures.persist("closed-enum-" + entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1) + "-" + method.replace('<','_').replace('>','_'), original, bad);
            }
            Class<?> type = Class.forName(entry.getKey().replace('/', '.'));
            Object[] constants = type.getEnumConstants(), clone = (Object[]) type.getMethod("values").invoke(null);
            assertNotSame(constants, clone); assertEquals(entry.getValue().size(), constants.length);
            for (int i = 0; i < constants.length; i++) { Enum<?> e = (Enum<?>)constants[i]; assertEquals(entry.getValue().get(i), e.name()); assertEquals(i, e.ordinal()); assertSame(e, clone[i]); }
            clone[0] = null; assertSame(constants[0], ((Object[])type.getMethod("values").invoke(null))[0]);
        }
    }

    @Test void sbP3EightExactTuplesHaveRealPositiveAndIndividualPermitRemoval() throws Exception
    {
        int found = 0;
        for (var permit : M004StaticClosureContract.ADDITIONAL_CALLS)
        {
            boolean tested = false;
            Set<String> candidates = new TreeSet<>(M004BytecodePolicy.SCANNED_M004_CLASSFILES);
            // ADR012 retained this exact pure tuple; binding011 no longer needs it in the current product.
            if (permit.owner().equals("java/lang/String") && permit.name().equals("compareTo")) candidates.add("unused-adjudicated-tuple-fixture");
            // Engine's V2 class is not a historical M004 class. Preserve the exact old JDK grant.
            if (permit.equals(new M004BytecodePolicy.Call(182, "java/security/MessageDigest", "update", "([BII)V"))) candidates.add("historical-digest-tuple-fixture");
            for (String owner : candidates)
            {
                byte[] original = owner.equals("unused-adjudicated-tuple-fixture") ? M004ClassfileFixtures.fieldless("static int approved(String a, String b) { return a.compareTo(b); }")
                        : owner.equals("historical-digest-tuple-fixture") ? M004ClassfileFixtures.fieldless("static void approved(java.security.MessageDigest digest, byte[] bytes, int offset, int count) { digest.update(bytes, offset, count); }")
                        : M004StaticClosureFixtures.real(owner); var f = M004Classfile.parse(original);
                if (!AsyncEscapeBytecodeGuard.inspect(original).isEmpty()) continue;
                for (var method : f.methods)
                {
                    if (method.code() == null || method.code().instructions().stream().noneMatch(i -> i.opcode() == permit.opcode() && f.member(i.operand()).equals(new M004Classfile.Member(permit.owner(), permit.name(), permit.descriptor())))) continue;
                    var denied = AsyncEscapeBytecodeGuard.inspectWithoutPermit(original, permit);
                    assertTrue(denied.stream().anyMatch(v -> v.code().equals("BYTECODE_CALL_NOT_ALLOWED") && v.owner().equals(permit.owner()) && v.name().equals(permit.name()) && v.descriptor().equals(permit.descriptor())));
                    assertThrows(AssertionError.class, () -> assertEquals(List.of(), denied));
                    String negativeDescriptor = M004StaticClosureContract.STATIC.getAsJsonArray("jdkAdditionalCalls").asList().stream().map(JsonElement::getAsJsonObject).filter(x -> x.get("owner").getAsString().equals(permit.owner()) && x.get("name").getAsString().equals(permit.name())).findFirst().orElseThrow().get("negativeDescriptor").getAsString();
                    String independentDescriptor = negativeDescriptor.contains("java/nio/ByteBuffer") ? "([BIJ)V" : negativeDescriptor;
                    List<M004BytecodePolicy.Call> mutants = List.of(
                            new M004BytecodePolicy.Call(permit.opcode() == 184 ? 182 : 184, permit.owner(), permit.name(), permit.descriptor()),
                            new M004BytecodePolicy.Call(permit.opcode(), permit.owner().equals("java/lang/Math") ? "java/lang/StrictMath" : "java/lang/Math", permit.name(), permit.descriptor()),
                            new M004BytecodePolicy.Call(permit.opcode(), permit.owner(), permit.name().equals("<init>") ? "changedConstructor" : permit.name() + "Changed", permit.descriptor()),
                            new M004BytecodePolicy.Call(permit.opcode(), permit.owner(), permit.name(), independentDescriptor));
                    for (var mutation : mutants)
                    {
                        byte[] bad = M004StaticClosureFixtures.call(original, method.name() + method.descriptor(), permit, mutation);
                        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(bad)).contains("BYTECODE_CALL_NOT_ALLOWED"), mutation.toString());
                        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectControl(bad, Set.of(AsyncEscapeBytecodeGuard.Rule.CALLS), false));
                        assertThrows(AssertionError.class, () -> assertFalse(AsyncEscapeBytecodeGuard.inspectControl(bad, Set.of(AsyncEscapeBytecodeGuard.Rule.CALLS), false).isEmpty()));
                        M004ClassfileFixtures.persist("tuple-" + permit.name().replace('<','_').replace('>','_'), original, bad);
                    }
                    found++; tested = true; break;
                }
                if (tested) break;
            }
            assertTrue(tested, "Real positive caller missing for " + permit);
        }
        assertEquals(8, found);
        assertEquals(Integer.MIN_VALUE, Math.toIntExact(Integer.MIN_VALUE)); assertEquals(Integer.MAX_VALUE, Math.toIntExact(Integer.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> Math.toIntExact(Integer.MIN_VALUE - 1L)); assertThrows(ArithmeticException.class, () -> Math.toIntExact(Integer.MAX_VALUE + 1L));
        for (double zero : new double[]{0.0, -0.0}) { assertEquals(Double.doubleToRawLongBits(zero), Double.doubleToRawLongBits(StrictMath.signum(zero))); assertEquals(Double.doubleToRawLongBits(zero), Double.doubleToRawLongBits(StrictMath.copySign(0.0, zero))); }
        assertTrue(Double.isNaN(StrictMath.signum(Double.NaN))); assertEquals(0L, Double.doubleToRawLongBits(StrictMath.log1p(0.0))); assertTrue(Double.isFinite(StrictMath.log1p(0.25)));
    }

    @Test void sbP4StringBoundsAndRawFloatBigEndianFingerprint() throws Exception
    {
        assertTrue("".isEmpty()); assertFalse("a".isEmpty()); assertEquals(0, "aa".compareTo("aa")); assertTrue("a".compareTo("aa") < 0); assertTrue("B".compareTo("a") < 0);
        // Current cancellation-aware helper must preserve every historical raw-bit digest vector.
        var fingerprint = LevelerAnalysisEngine.class.getDeclaredMethod("fingerprint", float[].class, CancellationToken.class); fingerprint.setAccessible(true);
        for (int size : new int[]{0, 1, 2048, 2049, 4099})
        {
            float[] samples = new float[size]; ByteBuffer bytes = ByteBuffer.allocate(size * 4).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < size; i++) { int bits = i % 4 == 0 ? 0x80000000 : i % 4 == 1 ? 0x7fc12345 : Float.floatToRawIntBits((i - 33) * 0.03125f); samples[i] = Float.intBitsToFloat(bits); bytes.putInt(bits); }
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes.array()), (byte[]) fingerprint.invoke(null, samples, new CancellationToken()));
        }
        FrozenList<String> list = new FrozenList<>(new Object[]{"a"}); assertEquals("a", list.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1)); assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
        BodyContextVector vector = new BodyContextVector(1, 2, 3, 4, 5, 6); for (int i = 0; i < 6; i++) assertEquals(i + 1.0, vector.componentAt(i));
        assertThrows(IndexOutOfBoundsException.class, () -> vector.componentAt(-1)); assertThrows(IndexOutOfBoundsException.class, () -> vector.componentAt(6));
    }

    @Test void sbP5Fields85TypedValuesAndSbN3EveryFieldTupleIsClosed() throws Exception
    {
        int historicalCount = 0, comparisonV2Count = 0;
        for (JsonElement element : M004StaticClosureContract.STATIC.getAsJsonArray("classes"))
        {
            String owner = element.getAsJsonObject().get("owner").getAsString(); byte[] original = M004StaticClosureFixtures.real(owner); var f = M004Classfile.parse(original);
            assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectActiveControl(original, Set.of(), false)); assertEquals(List.of(), M004StaticClosurePolicy.inspectActive(M004Classfile.parse(original), Set.of()));
            for (var field : f.fields)
            {
                if (owner.equals(L + "LevelerCalibrationProfile") && field.name().equals("V2"))
                {
                    assertEquals("L" + L + "LevelerCalibrationProfile;", field.descriptor());
                    assertEquals(25, field.access()); assertEquals(0, field.constantIndex());
                    comparisonV2Count++;
                }
                else historicalCount++;
                List<byte[]> mutants = new ArrayList<>();
                mutants.add(M004StaticClosureFixtures.fieldName(original, field.name(), field.name() + "Changed"));
                mutants.add(M004StaticClosureFixtures.removeField(original, field.name()));
                // Access includes visibility/static/final/volatile, compared as full bitset.
                for (int bit : new int[]{1, 2, 4, 8, 16, 64}) mutants.add(M004StaticClosureFixtures.fieldAccess(original, field.name(), field.access() ^ bit));
                if (field.constantIndex() == 0) mutants.add(M004StaticClosureFixtures.fieldDescriptor(original, field.name(), field.descriptor().equals("I") ? "J" : "I"));
                for (byte[] bad : mutants)
                {
                    assertTrue(codes(AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(), false)).contains("FIELD_SCHEMA_MISMATCH"), owner + "." + field.name());
                    assertFalse(codes(AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(AsyncEscapeBytecodeGuard.Rule.FIELDS), false)).contains("FIELD_SCHEMA_MISMATCH"));
                }
                if (field.constantIndex() != 0)
                {
                    var cp = f.pool[field.constantIndex()]; byte[] bad = original.clone();
                    if (cp.tag() == 8) bad[f.pool[cp.a()].end() - 1] ^= 1; else bad[cp.end() - 1] ^= 1;
                    rejects(bad, AsyncEscapeBytecodeGuard.Rule.CONSTANT_VALUES, "STATIC_CONSTANT_VALUE");
                    M004ClassfileFixtures.persist("constant-" + field.name(), original, bad);
                }
            }
        }
        assertEquals(70, historicalCount, "Original historical field universe stays exact");
        assertEquals(1, comparisonV2Count, "Only the separately declared V2 singleton is additional");
    }

    @Test void sbP5ProfileAndSbN7AllFixedGettersIdsAndSingletonBodies() throws Exception
    {
        for (String owner : List.of(L + "LevelerCalibrationProfile", L + "LoudnessStandard"))
        {
            byte[] original = M004StaticClosureFixtures.real(owner); var f = M004Classfile.parse(original);
            for (var m : f.methods)
                if (!m.name().equals("isShortTransition")) rejects(M004StaticClosureFixtures.nop(original, m.name()), AsyncEscapeBytecodeGuard.Rule.FIXED_PROFILE, "STATIC_FIXED_PROFILE");
        }
        assertTrue(LevelerCalibrationProfile.V1.isShortTransition(143999L, 48000)); assertFalse(LevelerCalibrationProfile.V1.isShortTransition(144000L, 48000));
        byte[] leveler = M004StaticClosureFixtures.real("com/quickmaster/processing/dynamics/LevelerProcessor"); var f = M004Classfile.parse(leveler);
        var zero = f.fields.stream().filter(x -> x.name().equals("MIN_LEVELING")).findFirst().orElseThrow(); byte[] bad = leveler.clone(); bad[f.pool[zero.constantIndex()].start() + 1] ^= (byte)128;
        rejects(bad, AsyncEscapeBytecodeGuard.Rule.CONSTANT_VALUES, "STATIC_CONSTANT_VALUE");
    }

    @Test void sbP6MetadataAreContextualAndSbN6UnusedCpDoesNotBorrowJoinPermission() throws Exception
    {
        byte[] engine = M004StaticClosureFixtures.real(L + "LevelerAnalysisEngine"), legacy = M004StaticClosureFixtures.real(M004StaticClosureContract.A);
        assertTrue(M004StaticClosureContract.metadata(M004Classfile.parse(engine), "java/lang/Exception"));
        assertTrue(M004StaticClosureContract.metadata(M004Classfile.parse(legacy), "java/lang/Deprecated"));
        for (String type : List.of("java/lang/Exception", "java/lang/Deprecated"))
        {
            byte[] original = type.endsWith("Exception") ? engine : legacy;
            byte[] bad = M004ClassfileFixtures.clazz(original, type).bytes();
            assertTrue(codes(AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(), false)).contains("BYTECODE_METADATA_ROLE_NOT_ALLOWED"));
            assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(AsyncEscapeBytecodeGuard.Rule.DEPENDENCY), false));
            M004ClassfileFixtures.persist("metadata-unused-" + type.substring(type.lastIndexOf('/') + 1), original, bad);
        }
        for (String source : List.of("static Exception wrong(Exception x) { return x; }", "static Object wrong() { return new Exception(); }", "static void wrong() { try { throw new IllegalArgumentException(); } catch(Exception ex) {} }", "@Deprecated static void wrong() {}"))
        {
            byte[] bad = M004ClassfileFixtures.fieldless(source);
            assertTrue(codes(AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(), false)).contains("BYTECODE_METADATA_ROLE_NOT_ALLOWED"));
        }
    }

    @Test void sbN2HelperAndNoSuchFieldErrorRemainForbidden() throws Exception
    {
        byte[] original = M004StaticClosureFixtures.real(M004StaticClosureContract.A);
        for (String forbidden : List.of(M004StaticClosureContract.A + "$1", "java/lang/NoSuchFieldError", "java/lang/Throwable"))
        {
            byte[] bad = M004ClassfileFixtures.clazz(original, forbidden).bytes(); assertFalse(AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(), false).isEmpty());
            assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspectActiveControl(bad, Set.of(AsyncEscapeBytecodeGuard.Rule.DEPENDENCY), false));
        }
    }

    @Test void literal94TableHasStaticBytecodeAndRuntimeFieldByFieldOracles() throws Exception
    {
        byte[] original = M004StaticClosureFixtures.real(L + "ConformanceRequirement"); assertEquals(List.of(), M004StaticClosurePolicy.inspect(original));
        var requirement = ConformanceRequirement.OFFICIAL_LOUDNESS_V1; assertEquals(94, requirement.requiredReadings().size());
        JsonArray readings = M004StaticClosureContract.RUNTIME_PROFILE.getAsJsonArray("requiredReadings");
        for (int i = 0; i < 94; i++)
        {
            Object actual = requirement.requiredReadings().get(i); JsonObject expected = readings.get(i).getAsJsonObject();
            for (var rule : M004BytecodePolicy.FIELDS.get(M + "RequiredOfficialReading"))
            {
                var field = actual.getClass().getDeclaredField(rule.name()); field.setAccessible(true); Object value = field.get(actual);
                if (value instanceof Double d) assertEquals(expected.get(rule.name()).getAsString(), String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(d)));
                else if (value instanceof Number n) assertEquals(expected.get(rule.name()).getAsLong(), n.longValue());
                else assertEquals(expected.get(rule.name()).getAsString(), value instanceof Enum<?> e ? e.name() : value);
            }
        }
        byte[] bad = M004StaticClosureFixtures.nop(original, "<init>"); rejects(bad, AsyncEscapeBytecodeGuard.Rule.STATIC_TABLE, "STATIC_REQUIRED_TABLE");
        var f = M004Classfile.parse(original); int tested = 0;
        for (var cp : f.pool)
            if (cp != null && cp.tag() == 8 && f.utf(cp.a()).matches("[0-9a-f]{64}"))
            { bad = original.clone(); bad[f.pool[cp.a()].end() - 1] ^= 1; rejects(bad, AsyncEscapeBytecodeGuard.Rule.STATIC_TABLE, "STATIC_REQUIRED_TABLE"); tested++; }
        assertEquals(92, tested, "90 original files plus two manifest pins; duplicates in readings share literal strings");
    }

    @Test void exactDsparkAndLoaderTuplesNeverGrantAdjacentOrForeignCapabilities() throws Exception
    {
        assertEquals("de368f326f668267d0cba20f34135efb466dafc53f84a4aee32d8af4347e4141", AsyncEscapeBytecodeGuard.sha(Files.readAllBytes(Path.of("libs/dspark-0.1.0.jar"))));
        Path loadedJar = Path.of(com.dspark.core.FFTReal.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals("de368f326f668267d0cba20f34135efb466dafc53f84a4aee32d8af4347e4141", AsyncEscapeBytecodeGuard.sha(Files.readAllBytes(loadedJar)), "Executing dependency bytes must match the binding too");
        byte[] loader = M004StaticClosureFixtures.real(M004StaticClosureContract.LOADER);
        assertFalse(codes(M004StaticClosurePolicy.inspect(loader)).contains("STATIC_LOADER_PERIMETER"));
        var f = M004Classfile.parse(loader);
        for (String needle : List.of("jar", "file", "META-INF/quickmaster/leveler-conformance.json", "QuickMaster-Loudness-Runner-SHA256"))
        {
            var cp = Arrays.stream(f.pool).filter(Objects::nonNull).filter(x -> x.tag() == 8 && f.utf(x.a()).equals(needle)).findFirst().orElseThrow();
            byte[] bad = loader.clone(); bad[f.pool[cp.a()].end() - 1] ^= 1;
            rejects(bad, AsyncEscapeBytecodeGuard.Rule.LOADER_BOUNDARY, "STATIC_LOADER_PERIMETER");
        }
        byte[] foreign = M004ClassfileFixtures.fieldless("static void foreign(java.io.InputStream in) throws java.io.IOException { in.close(); }");
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(foreign)).contains("BYTECODE_CALL_NOT_ALLOWED"));
        assertTrue(codes(AsyncEscapeBytecodeGuard.inspect(foreign)).contains("BYTECODE_DEPENDENCY_NOT_ALLOWED"));
    }

    private static Set<String> codes(List<AsyncEscapeBytecodeGuard.Violation> v) { Set<String> out = new HashSet<>(); for (var x : v) out.add(x.code()); return out; }
    private static void rejects(byte[] bytes, AsyncEscapeBytecodeGuard.Rule rule, String code)
    {
        assertTrue(codes(M004StaticClosurePolicy.inspect(bytes)).contains(code));
        assertFalse(codes(M004StaticClosurePolicy.inspect(M004Classfile.parse(bytes), Set.of(rule))).contains(code));
        assertThrows(AssertionError.class, () -> assertTrue(codes(M004StaticClosurePolicy.inspect(M004Classfile.parse(bytes), Set.of(rule))).contains(code)));
    }
}
