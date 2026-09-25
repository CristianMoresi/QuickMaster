package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Narrow INTENT373 regressions; these fixtures never attest an official execution. */
class ConformanceBindingContinuationTest
{
    private static final String PROFILE = "daad1207ff680b67b64108692a2070be389227ac8ddffa177ae9b8ac39ffbc14";

    @Test
    void decimalWriterPreservesLiteralZeroAndPermittedIntegerExtremes() throws Exception
    {
        long[] values = {0L, 1L, 9L, 10L, 65535L, 1788652800L, Integer.MAX_VALUE, 4294967295L, Long.MAX_VALUE};
        String[] expected = {"0", "1", "9", "10", "65535", "1788652800", "2147483647", "4294967295", "9223372036854775807"};
        for (int i = 0; i < values.length; i++) assertDecimal(values[i], expected[i]);
    }

    @Test
    void decimalWriterMatchesThePreviousJdkConversionAcrossBoundariesAndSeededValues() throws Exception
    {
        // The former production call is an oracle in tests only, never a new product converter.
        for (long power = 10L; power <= 1_000_000_000_000_000_000L; power *= 10L)
        {
            for (long value : new long[] {power - 1L, power, power + 1L}) assertDecimal(value, Long.toString(value));
            if (power == 1_000_000_000_000_000_000L) break;
        }
        Random random = new Random(373L);
        for (int i = 0; i < 1024; i++)
        {
            long value = random.nextLong() & Long.MAX_VALUE;
            assertDecimal(value, Long.toString(value));
        }
    }

    @Test
    void negativeValuesAndNoncanonicalOrOverflowingDecimalTokensStillReject() throws Exception
    {
        Method writer = numberMethod();
        for (long negative : new long[] {-1L, -10L, Integer.MIN_VALUE, Long.MIN_VALUE})
        {
            byte[] out = new byte[32];
            Arrays.fill(out, (byte) 90);
            int[] cursor = {2};
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> writer.invoke(null, out, cursor, negative));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertEquals("Negative counter.", failure.getCause().getMessage());
            assertEquals(2, cursor[0]);
            for (byte value : out) assertEquals(90, value);
        }
        String canonical = new String(ConformanceCodec.artifactBytes(requirement(), unboundRun(0L)), StandardCharsets.US_ASCII);
        for (String token : new String[] {"-1", "-0", "+0", "00", "01", "1.0", "1e0", "9223372036854775808", "18446744073709551615"})
        {
            byte[] mutated = canonical.replace("\"createdAtEpochSecond\":0", "\"createdAtEpochSecond\":" + token)
                    .getBytes(StandardCharsets.US_ASCII);
            assertFalse(Arrays.equals(canonical.getBytes(StandardCharsets.US_ASCII), mutated), token);
            assertThrows(RuntimeException.class, () -> ConformanceCodec.decodeArtifact(mutated), token);
        }
    }

    @Test
    void canonicalProfilePinAndLiteralPayloadAreUnchangedAtBothTimestampBounds() throws Exception
    {
        assertEquals(PROFILE, hash(ConformanceCodec.profileBytes(requirement())));
        long[] timestamps = {0L, Long.MAX_VALUE};
        String[] literals = {"0", "9223372036854775807"};
        for (int i = 0; i < timestamps.length; i++)
        {
            String expected = "{\"algorithmId\":\"QM-LOUDNESS-CORE-BS1770-5-V1\",\"algorithmSha256\":\"\",\"attempted\":false,\"createdAtEpochSecond\":"
                    + literals[i] + ",\"ebuManifestSha256\":\"\",\"ebuTermsAuthorized\":false,\"evidence\":[],\"failureReason\":\"NONE\",\"ituManifestSha256\":\"\",\"profileSha256\":\"\",\"requirementId\":\"QM-OFFICIAL-LOUDNESS-FILE-V1\",\"runnerSha256\":\"\",\"schemaVersion\":\"QM-OFFICIAL-LOUDNESS-ATTESTATION-1\"}";
            byte[] bytes = ConformanceCodec.artifactBytes(requirement(), unboundRun(timestamps[i]));
            assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), bytes);
            ConformanceRun decoded = ConformanceCodec.decodeArtifact(bytes);
            assertEquals(timestamps[i], decoded.createdAtEpochSecond());
            assertArrayEquals(bytes, ConformanceCodec.artifactBytes(requirement(), decoded));
            assertEquals(ConformanceState.NOT_RUN, StandardValidationReport.verifyBound(requirement(), decoded, null).state());
        }
    }

    @Test
    void codecClassfileUsesPrimitiveConcatWithoutTheUnauthorizedLongToStringTuple() throws Exception
    {
        ClassImage image = inspect(ConformanceCodec.class, "number");
        boolean primitiveConcat = false;
        boolean approvedBootstrap = false;
        for (int i = 1; i < image.tags().length; i++)
        {
            if (image.tags()[i] == 10)
            {
                int[] reference = (int[]) image.pool()[i];
                String owner = (String) image.pool()[(Integer) image.pool()[reference[0]]];
                int[] signature = (int[]) image.pool()[reference[1]];
                String name = (String) image.pool()[signature[0]];
                String descriptor = (String) image.pool()[signature[1]];
                assertFalse(owner.equals("java/lang/Long") && name.equals("toString")
                        && descriptor.equals("(J)Ljava/lang/String;"), "Forbidden Long.toString(J)String methodref");
                if (owner.equals("java/lang/invoke/StringConcatFactory") && name.equals("makeConcatWithConstants"))
                    approvedBootstrap = true;
            }
            if (image.tags()[i] == 18)
            {
                int[] dynamic = (int[]) image.pool()[i];
                int[] signature = (int[]) image.pool()[dynamic[1]];
                if ("(J)Ljava/lang/String;".equals(image.pool()[signature[1]])) primitiveConcat = true;
            }
        }
        assertTrue(approvedBootstrap);
        assertTrue(primitiveConcat, "The concat site must receive a primitive long, not a boxed number.");
    }

    @Test
    void bindingClassfileHasExactlyThreeTypedFalseHandlersAndNoExceptionJoin() throws Exception
    {
        ClassImage image = inspect(StandardValidationReport.class, "validBinding");
        for (Object value : image.pool())
            if (value instanceof String text)
                assertFalse(text.contains("java/lang/Exception"), "Forbidden broad Exception metadata: " + text);
        assertEquals(Set.of("java/lang/IllegalArgumentException", "java/lang/ArithmeticException",
                "java/security/NoSuchAlgorithmException"), new HashSet<>(Arrays.asList(image.catchTypes())));
        assertEquals(3, image.handlers().length);
        assertEquals(3, Arrays.stream(image.handlers()).distinct().count(), "Separate handlers must not merge to a broad join.");
        for (int handler : image.handlers())
        {
            int cursor = handler;
            int store = image.code()[cursor++] & 255;
            if (store == 58) cursor++; // astore with explicit local index
            else assertTrue(store >= 75 && store <= 78, "Expected astore_0 through astore_3.");
            assertEquals(3, image.code()[cursor++] & 255, "Each existing catch returns false (iconst_0).");
            assertEquals(172, image.code()[cursor] & 255, "Each existing catch returns immediately (ireturn).");
        }
    }

    @Test
    void validBindingStillBindsNotRunAndFailsClosedOnMalformedPayloadOrWrongHash() throws Exception
    {
        ConformanceRun valid = new ConformanceRun("", "", new FrozenList<OfficialSignalEvidence>(new Object[0]), false,
                false, ConformanceReason.NONE, requirement().algorithmId(), "a".repeat(64), PROFILE, "b".repeat(64), Long.MAX_VALUE);
        String payload = hash(ConformanceCodec.artifactBytes(requirement(), valid));
        BuildAlgorithmBinding binding = binding(valid, payload);
        StandardValidationReport report = StandardValidationReport.verifyBound(requirement(), valid, binding);
        assertEquals(ConformanceState.NOT_RUN, report.state());
        assertEquals(payload, report.attestationSha256());
        assertFalse(report.matches(binding));
        assertFailedBinding(StandardValidationReport.verifyBound(requirement(), valid, binding(valid, "c".repeat(64))));

        // Identity/hash prechecks pass; the malformed manifest reaches artifactBytes and its typed catch.
        ConformanceRun malformed = new ConformanceRun("\"", "", new FrozenList<OfficialSignalEvidence>(new Object[0]), false,
                true, ConformanceReason.NONE, valid.algorithmId(), valid.algorithmSha256(), PROFILE, valid.runnerSha256(), Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> ConformanceCodec.artifactBytes(requirement(), malformed));
        assertFailedBinding(StandardValidationReport.verifyBound(requirement(), malformed, binding(malformed, payload)));
    }

    private static void assertFailedBinding(StandardValidationReport report)
    {
        assertEquals(ConformanceState.FAILED, report.state());
        assertEquals("", report.algorithmSha256());
        assertEquals("", report.profileSha256());
        assertEquals("", report.attestationSha256());
        assertEquals("", report.runnerSha256());
        for (int i = 0; i < report.sets().size(); i++)
            assertEquals(ConformanceReason.BUILD_BINDING_MISMATCH, report.sets().get(i).reason());
    }

    private static BuildAlgorithmBinding binding(ConformanceRun run, String payload)
    {
        return new BuildAlgorithmBinding(run.algorithmId(), run.algorithmSha256(), run.profileSha256(), payload,
                run.runnerSha256(), run.createdAtEpochSecond());
    }

    private static ConformanceRequirement requirement() { return ConformanceRequirement.OFFICIAL_LOUDNESS_V1; }

    private static ConformanceRun unboundRun(long timestamp)
    {
        return new ConformanceRun("", "", new FrozenList<OfficialSignalEvidence>(new Object[0]), false, false,
                ConformanceReason.NONE, requirement().algorithmId(), "", "", "", timestamp);
    }

    private static String hash(byte[] bytes) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Method numberMethod() throws Exception
    {
        Method method = ConformanceCodec.class.getDeclaredMethod("number", byte[].class, int[].class, long.class);
        method.setAccessible(true);
        return method;
    }

    private static void assertDecimal(long value, String expected) throws Exception
    {
        byte[] out = new byte[32];
        Arrays.fill(out, (byte) 90);
        int[] cursor = {2};
        numberMethod().invoke(null, out, cursor, value);
        assertEquals(expected.length() + 2, cursor[0]);
        assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(out, 2, cursor[0]));
        assertEquals(90, out[0]);
        assertEquals(90, out[1]);
        for (int i = cursor[0]; i < out.length; i++) assertEquals(90, out[i]);
    }

    private record ClassImage(int[] tags, Object[] pool, byte[] code, String[] catchTypes, int[] handlers) { }

    /** Reads only the JVM structures needed by these two regressions; the authoritative guard remains separate. */
    private static ClassImage inspect(Class<?> type, String selectedMethod) throws Exception
    {
        try (var resource = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class");
             var input = new DataInputStream(resource))
        {
            assertEquals(0xcafebabe, input.readInt());
            input.readUnsignedShort(); input.readUnsignedShort();
            int count = input.readUnsignedShort();
            int[] tags = new int[count];
            Object[] pool = new Object[count];
            for (int i = 1; i < count; i++)
            {
                tags[i] = input.readUnsignedByte();
                switch (tags[i])
                {
                    case 1 -> pool[i] = input.readUTF();
                    case 3, 4 -> input.readInt();
                    case 5, 6 -> { input.readLong(); i++; }
                    case 7, 8, 16, 19, 20 -> pool[i] = input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> pool[i] = new int[] {input.readUnsignedShort(), input.readUnsignedShort()};
                    case 15 -> { input.readUnsignedByte(); input.readUnsignedShort(); }
                    default -> fail("Unknown JVM constant tag " + tags[i]);
                }
            }
            input.readUnsignedShort(); input.readUnsignedShort(); input.readUnsignedShort();
            int interfaces = input.readUnsignedShort();
            input.skipNBytes(interfaces * 2L);
            int fields = input.readUnsignedShort();
            for (int i = 0; i < fields; i++)
            {
                input.skipNBytes(6);
                skipAttributes(input);
            }
            int methods = input.readUnsignedShort();
            for (int i = 0; i < methods; i++)
            {
                input.readUnsignedShort();
                String name = (String) pool[input.readUnsignedShort()];
                input.readUnsignedShort();
                int attributes = input.readUnsignedShort();
                for (int j = 0; j < attributes; j++)
                {
                    String attribute = (String) pool[input.readUnsignedShort()];
                    byte[] bytes = input.readNBytes(input.readInt());
                    if (!name.equals(selectedMethod) || !attribute.equals("Code")) continue;
                    try (var codeInput = new DataInputStream(new ByteArrayInputStream(bytes)))
                    {
                        codeInput.readUnsignedShort(); codeInput.readUnsignedShort();
                        byte[] code = codeInput.readNBytes(codeInput.readInt());
                        int catches = codeInput.readUnsignedShort();
                        String[] types = new String[catches];
                        int[] handlers = new int[catches];
                        for (int k = 0; k < catches; k++)
                        {
                            codeInput.readUnsignedShort(); codeInput.readUnsignedShort();
                            handlers[k] = codeInput.readUnsignedShort();
                            int classIndex = codeInput.readUnsignedShort();
                            types[k] = classIndex == 0 ? "<finally>" : (String) pool[(Integer) pool[classIndex]];
                        }
                        return new ClassImage(tags, pool, code, types, handlers);
                    }
                }
            }
            throw new AssertionError("Missing code for " + selectedMethod);
        }
    }

    private static void skipAttributes(DataInputStream input) throws Exception
    {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++)
        {
            input.readUnsignedShort();
            input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
        }
    }
}
