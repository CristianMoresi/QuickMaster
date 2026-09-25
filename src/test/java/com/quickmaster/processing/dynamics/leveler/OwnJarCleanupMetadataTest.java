package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual binary mutations, never decoded-model substitutes. No inspected Code executes. */
class OwnJarCleanupMetadataTest
{
    private static final String LOADER = "com/quickmaster/processing/dynamics/leveler/ConformanceArtifactLoader";

    @Test void actualLoaderFinallyMetadataPassesBothComposedGuards() throws Exception
    {
        byte[] original = loader();
        assertTrue(M004StaticClosureContract.metadata(M004Classfile.parse(original), "java/lang/Throwable"),
                "OWN_JAR_EXACT_FINALLY_METADATA_REJECTED");
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspect(original));
        assertEquals(List.of(), M004StaticClosurePolicy.inspect(original));
    }

    @Test void everyCleanupInstructionByteAndHandlerTupleIsNecessary() throws Exception
    {
        byte[] original = loader();
        int tested = 0;
        for (var m : M004Classfile.parse(original).methods)
            if (Set.of("readBundle", "readEntry").contains(m.name()))
            {
                int pc = m.name().equals("readBundle") ? 452 : 146;
                for (int offset = pc; offset < m.code().bytes().length; offset++)
                {
                    byte[] bad = original.clone(); bad[m.code().absoluteStart() + offset] ^= 1;
                    rejected(bad); tested++;
                }
                int handlers = m.code().absoluteStart() + m.code().bytes().length + 2;
                for (int h = 0; h < m.code().handlers().size(); h++)
                    for (int component = 0; component < 4; component++)
                    {
                        byte[] bad = original.clone(); bad[handlers + h * 8 + component * 2 + 1] ^= 1;
                        rejected(bad); tested++;
                    }
                for (int at : m.name().equals("readBundle") ? new int[] { 201, 203, 206, 208, 444, 446, 449, 451 }
                        : new int[] { 138, 140, 143, 145 })
                {
                    byte[] bad = original.clone(); bad[m.code().absoluteStart() + at] ^= 1;
                    rejected(bad); tested++;
                }
            }
        assertEquals(52, tested);
    }

    @Test void duplicateUnusedPoolEntriesAndOtherMetadataRolesCannotBorrowPermission() throws Exception
    {
        byte[] original = loader();
        rejected(M004ClassfileFixtures.clazz(original, "java/lang/Throwable").bytes());
        rejected(M004ClassfileFixtures.utf(original, "java/lang/Throwable").bytes());
        rejected(M004ClassfileFixtures.utf(original, "Ljava/lang/Throwable;").bytes());
        var pool = M004Classfile.parse(original);
        int throwable = 0;
        for (int i = 1; i < pool.pool.length; i++)
            if (pool.pool[i] != null && pool.pool[i].tag() == 7 && pool.className(i).equals("java/lang/Throwable")) throwable = i;
        assertTrue(throwable > 0);
        byte[] wrongParent = original.clone(); M004ClassfileFixtures.putU2(wrongParent, pool.poolEnd + 4, throwable);
        rejected(wrongParent);
        for (String source : List.of(
                "Throwable wrong;",
                "static Throwable wrong(Throwable value) { return value; }",
                "static <T extends Throwable> T wrong(T value) { return value; }",
                "static Object wrong() { return new Throwable(); }",
                "static void wrong(Throwable t) { t.addSuppressed(t); }",
                "static void wrong() throws Throwable {}",
                "static Object wrong() { return Throwable.class; }",
                "static Object wrong(Object t) { return (Throwable)t; }",
                "static boolean wrong(Object t) { return t instanceof Throwable; }",
                "static Object wrong() { return new Throwable[1]; }",
                "static Object wrong() { return new Throwable[1][1]; }",
                "static void wrong() { try { throw new IllegalArgumentException(); } catch(Throwable t) {} }"))
        {
            byte[] bad = M004ClassfileFixtures.fieldless(source);
            assertFalse(AsyncEscapeBytecodeGuard.inspect(bad).isEmpty(), source);
        }
    }

    @Test void unrelatedInstructionDriftCannotPassBecauseCleanupStillMatches() throws Exception
    {
        byte[] original = loader();
        var f = M004Classfile.parse(original);
        for (var m : f.methods)
            if (Set.of("readBundle", "readEntry").contains(m.name()))
                for (var instruction : m.code().instructions())
                {
                    byte[] bad = original.clone();
                    bad[m.code().absoluteStart() + instruction.offset()] ^= 1;
                    rejected(bad);
                }
    }

    @Test void currentCoreHasPreciselyTheAdjudicatedFieldsAndHelperMethods() throws Exception
    {
        byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("qm.coreClasses",
                System.getProperty("qm.staticClasses", "target/classes")),
                "com/quickmaster/processing/dynamics/leveler/LoudnessCore.class"));
        assertEquals(List.of(), AsyncEscapeBytecodeGuard.inspect(bytes));
        assertEquals(List.of(), M004StaticClosurePolicy.inspect(bytes));
        rejectedCore(M004StaticClosureFixtures.removeField(bytes, "weightedPowerTree"));
        rejectedCore(M004StaticClosureFixtures.fieldAccess(bytes, "weightedPowerTree", 2));
        rejectedCore(M004StaticClosureFixtures.fieldDescriptor(bytes, "weightedPowerTree", "[F"));
        rejectedCore(M004ClassfileFixtures.addMethod(bytes, "unadjudicated", "()V", 10,
                new byte[] { (byte) 0xb1 }, 0, 0));
        for (String name : List.of("nodePower", "rebuildTree", "treeRange", "treeWindow"))
        {
            var m = M004Classfile.parse(bytes).methods.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
            byte[] bad = bytes.clone(); M004ClassfileFixtures.putU2(bad, m.start(), 9);
            rejectedCore(bad);
        }
    }

    @Test void rawStackMapPositionCannotBeConfusedWithGenericTypeUse() throws Exception
    {
        byte[] original = loader();
        for (String name : List.of("readBundle", "readEntry"))
        {
            byte[] changed = shiftedHandlerFrame(original, name);
            var file = M004Classfile.parse(changed);
            assertEquals(2, file.typeUses.stream().filter(u -> u.type().equals("java/lang/Throwable")).count(),
                    "The old generic TypeUse inventory cannot see this displacement");
            rejected(changed);
            assertTrue(ActiveLevelerGuardContract.cleanup(file, Set.of("STACK")),
                    "Removing only exact StackMap position validation must expose this neighbor");
        }
    }

    @Test void preciseNewRulesHaveIndependentDeletionControls() throws Exception
    {
        byte[] original = loader();
        assertFalse(M004BytecodePolicy.JDK_CALLS.stream().anyMatch(c -> c.owner().equals("java/lang/Throwable")));
        assertFalse(M004StaticClosureContract.LOADER_TYPES.contains("java/lang/Throwable"));
        var file = M004Classfile.parse(original);
        var method = file.methods.stream().filter(m -> m.name().equals("readBundle")).findFirst().orElseThrow();
        byte[] changedCode = original.clone(); changedCode[method.code().absoluteStart() + 455] = 3;
        rejected(changedCode);
        assertTrue(ActiveLevelerGuardContract.cleanup(M004Classfile.parse(changedCode), Set.of("CODE")));
        byte[] changedHandler = original.clone();
        int table = method.code().absoluteStart() + method.code().bytes().length + 2;
        M004ClassfileFixtures.putU2(changedHandler, table, 103);
        rejected(changedHandler);
        assertTrue(ActiveLevelerGuardContract.cleanup(M004Classfile.parse(changedHandler), Set.of("HANDLERS")));
        byte[] duplicate = M004ClassfileFixtures.clazz(original, "java/lang/Throwable").bytes();
        rejected(duplicate);
        assertTrue(ActiveLevelerGuardContract.cleanup(M004Classfile.parse(duplicate), Set.of("POOL")));
    }

    @Test void absentThrowableCannotDisableMandatoryCleanupCheck() throws Exception
    {
        byte[] original = loader();
        var f = M004Classfile.parse(original);
        byte[] changed = original.clone();
        for (var cp : f.pool)
            if (cp != null && cp.tag() == 1 && cp.value().equals("java/lang/Throwable"))
            {
                byte[] encoded;
                try (var bytes = new java.io.ByteArrayOutputStream(); var out = new java.io.DataOutputStream(bytes))
                { out.writeByte(1); out.writeUTF("java/lang/RuntimeException"); encoded = bytes.toByteArray(); }
                changed = M004ClassfileFixtures.replace(changed, cp.start(), cp.end(), encoded);
                break;
            }
        var parsed = M004Classfile.parse(changed);
        assertFalse(parsed.referencedClasses.contains("java/lang/Throwable"));
        assertTrue(M004StaticClosurePolicy.inspect(changed).stream().anyMatch(v -> v.code().equals("STATIC_LOADER_PERIMETER")),
                "Loader close contract is mandatory independently of the Throwable dependency branch");
    }

    private static byte[] shiftedHandlerFrame(byte[] original, String name)
    {
        var file = M004Classfile.parse(original);
        var method = file.methods.stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        var code = method.code();
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(original);
        b.position(code.absoluteStart() + code.bytes().length + 2 + code.handlers().size() * 8);
        int wanted = name.equals("readBundle") ? 452 : 146;
        for (int n = b.getShort() & 65535; n > 0; n--)
        {
            String attribute = file.utf(b.getShort() & 65535); int length = b.getInt(), end = b.position() + length;
            if (attribute.equals("StackMapTable"))
            {
                int pc = -1;
                for (int frames = b.getShort() & 65535; frames > 0; frames--)
                {
                    int tagOffset = b.position(), tag = b.get() & 255;
                    int delta = tag <= 63 ? tag : tag <= 127 ? tag - 64 : b.getShort() & 65535;
                    pc += delta + 1;
                    if (pc == wanted)
                    {
                        assertTrue(tag == 247 || tag == 255, "Actual handler uses explicit delta");
                        byte[] changed = original.clone();
                        M004ClassfileFixtures.putU2(changed, tagOffset + 1, delta - 1);
                        return changed;
                    }
                    if (tag >= 64 && tag <= 127 || tag == 247) skipVerification(b);
                    else if (tag >= 252 && tag <= 254) for (int i = 251; i < tag; i++) skipVerification(b);
                    else if (tag == 255)
                    {
                        for (int i = b.getShort() & 65535; i > 0; i--) skipVerification(b);
                        for (int i = b.getShort() & 65535; i > 0; i--) skipVerification(b);
                    }
                }
            }
            b.position(end);
        }
        throw new AssertionError("Actual handler StackMap was not exercised");
    }
    private static void skipVerification(java.nio.ByteBuffer b)
    { int tag = b.get() & 255; if (tag == 7 || tag == 8) b.getShort(); }

    static byte[] loader() throws Exception
    {
        return Files.readAllBytes(Path.of(System.getProperty("qm.cleanupClasses",
                System.getProperty("qm.staticClasses", "target/classes")), LOADER + ".class"));
    }
    private static void rejectedCore(byte[] bytes)
    {
        assertTrue(!AsyncEscapeBytecodeGuard.inspect(bytes).isEmpty()
                || !M004StaticClosurePolicy.inspect(bytes).isEmpty(), "Unexpected Core schema permission");
    }
    private static void rejected(byte[] bytes)
    {
        try
        {
            M004Classfile parsed = M004Classfile.parse(bytes);
            assertFalse(M004StaticClosureContract.metadata(parsed, "java/lang/Throwable"), "Unexpected contextual Throwable permission");
            assertFalse(AsyncEscapeBytecodeGuard.inspect(bytes).isEmpty(), "Composed dependency guard missed changed cleanup");
        }
        catch (IllegalArgumentException invalidClassfile)
        {
            assertTrue(invalidClassfile.getMessage().startsWith(M004Classfile.FORMAT));
            // Such a neighbor is a parser rejection, not a JVM-valid mutant.
        }
    }
}
