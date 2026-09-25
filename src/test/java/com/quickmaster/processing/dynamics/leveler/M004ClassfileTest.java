package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class M004ClassfileTest
{
    @Test
    void rejectsEveryTruncatedPrefixWithStableFormatFailure() throws Exception
    {
        byte[] original = M004ClassfileFixtures.fieldless("static String concat(int n) { return \"n=\"+n; }");
        assertNotNull(M004Classfile.parse(original));
        for (int length = 0; length < original.length; length++)
        {
            byte[] bytes = Arrays.copyOf(original, length);
            var failure = assertThrows(IllegalArgumentException.class, () -> M004Classfile.parse(bytes), "prefix " + length);
            assertTrue(failure.getMessage().startsWith(M004Classfile.FORMAT), failure.toString());
        }
        M004ClassfileFixtures.persist("truncation", original, Arrays.copyOf(original, original.length - 1));
    }

    @Test
    void validatesMagicVersionTagsIndicesAndWideConstantSlots() throws Exception
    {
        byte[] original = M004ClassfileFixtures.fieldless("static final long LONG_VALUE=123456789123456789L; static final double DOUBLE_VALUE=123.456789d;");
        var file = M004Classfile.parse(original);
        int wide = 0;
        for (int i = 1; i < file.pool.length; i++)
            if (file.pool[i] != null && (file.pool[i].tag() == 5 || file.pool[i].tag() == 6))
            { assertNull(file.pool[i + 1]); wide++; }
        assertEquals(2, wide);
        byte[] bad = original.clone(); bad[0] = 0; format(bad);
        bad = original.clone(); bad[5] = 1; format(bad);
        bad = original.clone(); bad[7] = 62; format(bad);
        bad = original.clone(); bad[10] = 99; format(bad);
        bad = original.clone(); M004ClassfileFixtures.putU2(bad, file.poolEnd + 2, 65535); format(bad);
        bad = Arrays.copyOf(original, original.length + 1); format(bad);
        for (int i = 1; i < file.pool.length; i++)
            if (file.pool[i] != null && file.pool[i].tag() == 7)
            {
                bad = original.clone(); M004ClassfileFixtures.putU2(bad, file.pool[i].start() + 1, 65535); format(bad); break;
            }
        byte[] illegalUtf = original.clone();
        var utf = Arrays.stream(file.pool).filter(p -> p != null && p.tag() == 1).findFirst().orElseThrow();
        illegalUtf[utf.start() + 3] = (byte) 0xFF; format(illegalUtf);
    }

    @Test
    void parsesDescriptorsWithoutGuessingOrAcceptingTrailingGarbage()
    {
        var descriptor = M004Classfile.descriptor("([[DJLjava/lang/String;I)[Ljava/lang/Object;", true);
        assertEquals(5, descriptor.slots());
        assertEquals(4, descriptor.parameters().size());
        assertTrue(descriptor.classes().containsAll(List.of("java/lang/String", "java/lang/Object")));
        for (String text : List.of("", "V", "[V", "L;", "Ljava/lang/String", "Igarbage", "Lfoo.bar;", "[[", "(I)V"))
            assertThrows(IllegalArgumentException.class, () -> M004Classfile.descriptor(text, false), text);
        for (String text : List.of("()", "(V)V", "(I", "(I)Vx", "(Ljava/lang/String)V", "([V)V"))
            assertThrows(IllegalArgumentException.class, () -> M004Classfile.descriptor(text, true), text);
    }

    @Test
    void genericSignatureGrammarDoesNotMisreadFormalTypeVariableNames() throws Exception
    {
        byte[] bytes = M004ClassfileFixtures.compile("com.quickmaster.processing.dynamics.leveler.BoundaryDetector", """
                package com.quickmaster.processing.dynamics.leveler;
                final class BoundaryDetector<LongName extends Number & Comparable<LongName>> {
                  java.util.List<? extends LongName> method(java.util.List<? super LongName> value) { return null; }
                }
                """).bytes();
        var file = M004Classfile.parse(bytes);
        assertTrue(file.referencedClasses.containsAll(List.of("java/lang/Number", "java/lang/Comparable", "java/util/List")));
        assertFalse(file.referencedClasses.stream().anyMatch(x -> x.contains("LongName")));
    }

    @Test
    void decodesTableLookupWideAndHandlerBoundaries() throws Exception
    {
        StringBuilder source = new StringBuilder("static int dense(int n) { switch(n) { case 0:return 4; case 1:return 5; case 2:return 6; default:return 7; }} "
                + "static int sparse(int n) { switch(n) { case -1000:return 4; case 11:return 5; case 9999:return 6; default:return 7; }} "
                + "static int wide(int n) {");
        for (int i = 0; i < 270; i++) source.append("int v").append(i).append("=n+").append(i).append(";");
        source.append("v269++; return ");
        for (int i = 0; i < 270; i++) { if (i > 0) source.append('+'); source.append('v').append(i); }
        source.append(";} static int handled(int x) { try { return 42/x; } catch(ArithmeticException ex) { return 0; }}");
        byte[] original = M004ClassfileFixtures.fieldless(source.toString());
        var file = M004Classfile.parse(original);
        assertTrue(instructions(file, "dense").stream().anyMatch(i -> i.opcode() == 0xAA));
        assertTrue(instructions(file, "sparse").stream().anyMatch(i -> i.opcode() == 0xAB));
        assertTrue(instructions(file, "wide").stream().anyMatch(i -> i.opcode() == 0xC4));
        assertEquals(1, file.methods.stream().filter(m -> m.name().equals("handled")).findFirst().orElseThrow().code().handlers().size());
        var method = file.methods.stream().filter(m -> m.name().equals("dense")).findFirst().orElseThrow();
        var table = instructions(file, "dense").stream().filter(i -> i.opcode() == 0xAA).findFirst().orElseThrow();
        int aligned = (table.offset() + 4) & ~3;
        byte[] bad = original.clone();
        M004ClassfileFixtures.putU4(bad, method.code().absoluteStart() + aligned + 4, Integer.MIN_VALUE);
        M004ClassfileFixtures.putU4(bad, method.code().absoluteStart() + aligned + 8, Integer.MAX_VALUE);
        format(bad);
        bad = original.clone();
        M004ClassfileFixtures.putU4(bad, method.code().absoluteStart() + aligned, Integer.MAX_VALUE); format(bad);
        var wideMethod = file.methods.stream().filter(m -> m.name().equals("wide")).findFirst().orElseThrow();
        var wide = instructions(file, "wide").stream().filter(i -> i.opcode() == 0xC4).findFirst().orElseThrow();
        bad = original.clone(); bad[wideMethod.code().absoluteStart() + wide.offset() + 1] = 0; format(bad);
        M004ClassfileFixtures.persist("switch-overflow", original, bad);
    }

    @Test
    void attributesBootstrapAndInstructionOperandsHaveBoundedValidatedIndices() throws Exception
    {
        byte[] original = M004ClassfileFixtures.fieldless("static String text(int n) { return \"n=\"+n; }");
        var file = M004Classfile.parse(original);
        assertEquals(1, file.bootstraps.size());
        byte[] bad = original.clone();
        for (var cp : file.pool)
            if (cp != null && cp.tag() == 18) { M004ClassfileFixtures.putU2(bad, cp.start() + 1, 65535); break; }
        format(bad);
        var text = file.methods.stream().filter(m -> m.name().equals("text")).findFirst().orElseThrow();
        var invoke = text.code().instructions().stream().filter(i -> i.opcode() == 0xBA).findFirst().orElseThrow();
        bad = original.clone(); bad[text.code().absoluteStart() + invoke.offset() + 4] = 1; format(bad);
        bad = original.clone(); bad[text.code().absoluteStart() + text.code().bytes().length - 1] = (byte) 0xCA; format(bad);
        bad = original.clone(); M004ClassfileFixtures.putU4(bad, text.start() + 10, Integer.MAX_VALUE); format(bad);
        var unknown = M004ClassfileFixtures.utf(original, "UnsupportedAttribute");
        bad = unknown.bytes().clone();
        var expanded = M004Classfile.parse(bad);
        M004ClassfileFixtures.putU2(bad, expanded.classAttributesOffset + 2, unknown.index()); format(bad);
    }

    private static List<M004Classfile.Instruction> instructions(M004Classfile file, String method)
    { return file.methods.stream().filter(m -> m.name().equals(method)).findFirst().orElseThrow().code().instructions(); }
    private static void format(byte[] bytes)
    { assertTrue(assertThrows(IllegalArgumentException.class, () -> M004Classfile.parse(bytes)).getMessage().startsWith(M004Classfile.FORMAT)); }
}
