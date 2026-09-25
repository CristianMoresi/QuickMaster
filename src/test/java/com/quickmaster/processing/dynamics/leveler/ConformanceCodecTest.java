package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickmaster.processing.dynamics.leveler.model.*;

class ConformanceCodecTest
{
    @Test
    void compiled94LiteralRequirementsMatchIndependentFrozenResourceAndCanonicalPin() throws Exception
    {
        ConformanceRequirement requirement = ConformanceRequirement.OFFICIAL_LOUDNESS_V1;
        assertEquals(94, ConformanceRequirement.E_REQ);
        assertEquals(94, requirement.requiredReadings().size());
        JsonObject fixture;
        try (var stream = getClass().getResourceAsStream("/official-conformance/required-profile.json"))
        {
            assertNotNull(stream);
            fixture = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.US_ASCII)).getAsJsonObject();
        }
        assertEquals("daad1207ff680b67b64108692a2070be389227ac8ddffa177ae9b8ac39ffbc14",
                ConformancePackageAssembler.hash(ConformanceCodec.profileBytes(requirement)));
        JsonArray rows = fixture.getAsJsonArray("requiredReadings");
        int itu = 0, ebu = 0, absent = 0;
        HashSet<String> sources = new HashSet<>();
        for (int i = 0; i < 94; i++)
        {
            RequiredOfficialReading actual = requirement.requiredReadings().get(i);
            JsonObject expected = rows.get(i).getAsJsonObject();
            for (Field field : RequiredOfficialReading.class.getDeclaredFields())
            {
                assertEquals(Modifier.PRIVATE | Modifier.FINAL, field.getModifiers());
                field.setAccessible(true);
                Object value = field.get(actual);
                String observed = value instanceof Double ? String.format("%016x", Double.doubleToRawLongBits((Double) value)) : value.toString();
                assertEquals(expected.get(field.getName()).getAsString(), observed, i + ":" + field.getName());
            }
            if (actual.setId().startsWith("ITU")) itu++; else ebu++;
            if (actual.expectedKind() == LoudnessValueKind.NO_LOUDNESS) absent++;
            sources.add(actual.setId() + ":" + actual.signalId());
        }
        assertEquals(39, itu); assertEquals(55, ebu); assertEquals(2, absent); assertEquals(90, sources.size());
    }

    @Test
    void strictCanonicalArtifactRoundtripAndClosedSyntaxRejectMutations() throws Exception
    {
        ConformanceRun input = run(new Object[] {sample(0)}, true, ConformanceReason.NONE);
        byte[] bytes = ConformanceCodec.artifactBytes(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, input);
        assertArrayEquals(bytes, ConformanceCodec.artifactBytes(ConformanceRequirement.OFFICIAL_LOUDNESS_V1,
                ConformanceCodec.decodeArtifact(bytes)));
        String json = new String(bytes, StandardCharsets.US_ASCII);
        for (String mutant : new String[] {" " + json, json + "\n", "\ufeff" + json,
                json.replace("\"attempted\":true", "\"attempted\":true,\"attempted\":true"),
                json.replace("\"attempted\":true", "\"state\":\"PASSED\",\"attempted\":true"),
                json.replace("\"attempted\":true,", ""),
                json.replace("\"createdAtEpochSecond\":0", "\"createdAtEpochSecond\":00"),
                json.replace("\"caseNumber\":1", "\"caseNumber\":9999999999999999999999999"),
                json.replace("QM-OFFICIAL-LOUDNESS-ATTESTATION-1", "UNKNOWN"),
                json.replace("\"setId\":\"ITU", "\"setId\":\"\\u0049TU"),
                json.replace("c024000000000000", "C024000000000000"),
                json.replace("c024000000000000", "7ff8000000000000"),
                json.replace("\"algorithmId\"", "\"attestationSha256\"")})
            assertThrows(RuntimeException.class, () -> ConformanceCodec.decodeArtifact(mutant.getBytes(StandardCharsets.UTF_8)), mutant);
        assertThrows(IllegalArgumentException.class, () -> ConformanceCodec.decodeArtifact(new byte[262145]));
        assertThrows(IllegalArgumentException.class, () -> ConformanceCodec.decodeArtifact(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> ConformanceCodec.decodeArtifact(ConformanceCodec.artifactBytes(
                ConformanceRequirement.OFFICIAL_LOUDNESS_V1, run(new Object[] {sample(1), sample(0)}, true, ConformanceReason.NONE))));
    }

    @Test
    void everyNumericEndpointIsInclusiveAndAdjacentOutsideValueFailsWithoutGrantingOfficialPassed() throws Exception
    {
        int finite = 0;
        for (int i = 0; i < 94; i++)
        {
            RequiredOfficialReading required = ConformanceRequirement.OFFICIAL_LOUDNESS_V1.requiredReadings().get(i);
            if (required.expectedKind() != LoudnessValueKind.FINITE) continue;
            finite++;
            double lo = required.expectedLufs() - 0.1d;
            double hi = required.expectedLufs() + 0.1d;
            for (double endpoint : new double[] {lo, hi})
            {
                OfficialSignalEvidence row = change(change(sample(i), "measuredLufs", endpoint), "minimumLufs", endpoint);
                assertEquals(ConformanceState.PARTIAL, report(row).state(), i + " endpoint=" + endpoint);
            }
            for (double outside : new double[] {Math.nextDown(lo), Math.nextUp(hi)})
            {
                OfficialSignalEvidence row = change(change(sample(i), "measuredLufs", outside), "minimumLufs", outside);
                assertEquals(ConformanceState.FAILED, report(row).state(), i + " outside=" + outside);
            }
        }
        assertEquals(92, finite);
    }

    @Test
    void exactKeysIdentityOracleAndAllReadoutCountersAreAuthoritative() throws Exception
    {
        OfficialSignalEvidence valid = sample(0);
        assertEquals(ConformanceState.PARTIAL, report(valid).state());
        String[] fields = {"signalSha256", "caseNumber", "signalId", "sampleRateHz", "expectedLufs", "toleranceLu",
                "readoutMode", "sourceFramesConsumed", "firstReadoutEndFrame", "readoutCount", "resetCount", "eofReached"};
        Object[] values = {"0".repeat(64), 999, "unknown", 44100, -9d, .2d, ReadoutMode.STEADY_EOF,
                192001L, 191999L, 2L, 0, false};
        for (int i = 0; i < fields.length; i++) assertEquals(ConformanceState.FAILED, report(change(valid, fields[i], values[i])).state(), fields[i]);
        assertEquals(ConformanceState.FAILED, report(change(valid, "resetCount", 2)).state());
        ConformanceRun duplicate = run(new Object[] {valid, sample(0)}, true, ConformanceReason.NONE);
        assertEquals(ConformanceState.FAILED, StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, duplicate, null).state());
        ConformanceRun reversed = run(new Object[] {sample(1), valid}, true, ConformanceReason.NONE);
        assertEquals(ConformanceState.FAILED, StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, reversed, null).state());
        assertThrows(IllegalArgumentException.class, () -> change(valid, "setVersion", "BS.2217-2"));
        assertThrows(IllegalArgumentException.class, () -> change(valid, "channelLayout", ChannelLayout.MONO_MAIN));
    }

    @Test
    void lfeCategoryRequiresObservedCompleteExecutionAndNumericAbsenceFails() throws Exception
    {
        for (int index : new int[] {20, 26})
        {
            OfficialSignalEvidence lfe = sample(index);
            assertEquals(ConformanceState.PARTIAL, report(lfe).state());
            for (String field : new String[] {"eofReached", "resetCount", "completeGatingBlocks"})
            {
                Object replacement;
                if (field.equals("eofReached")) replacement = Boolean.FALSE;
                else if (field.equals("resetCount")) replacement = Integer.valueOf(0);
                else replacement = Long.valueOf(0L);
                assertEquals(ConformanceState.FAILED, report(change(lfe, field, replacement)).state());
            }
            assertEquals(ConformanceState.FAILED, report(change(lfe, "measuredKind", LoudnessValueKind.FINITE)).state());
            assertThrows(IllegalArgumentException.class, () -> change(lfe, "measuredLufs", -0.0d));
            assertThrows(IllegalArgumentException.class, () -> change(lfe, "toleranceLu", 0.1d));
        }
        OfficialSignalEvidence numeric = sample(0);
        numeric = change(change(numeric, "measuredLufs", 0.0d), "minimumLufs", 0.0d);
        assertEquals(ConformanceState.FAILED, report(change(numeric, "measuredKind", LoudnessValueKind.NO_LOUDNESS)).state());
    }

    @Test
    void nonfiniteSlotsCannotBeRetainedAndConstantsValidateEveryExtremum() throws Exception
    {
        for (String field : new String[] {"expectedLufs", "measuredLufs", "minimumLufs", "toleranceLu"})
            for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
                assertThrows(IllegalArgumentException.class, () -> change(sample(0), field, invalid));
        for (int index : new int[] {52, 73})
        {
            OfficialSignalEvidence constant = sample(index);
            assertEquals(ConformanceState.PARTIAL, report(constant).state());
            assertEquals(ConformanceState.FAILED, report(change(constant, "minimumLufs", -23.10000000001d)).state());
            assertEquals(ConformanceState.FAILED, report(change(constant, "firstReadoutEndFrame", constant.firstReadoutEndFrame() - 1L)).state());
            assertEquals(ConformanceState.FAILED, report(change(constant, "readoutCount", constant.readoutCount() - 1L)).state());
        }
        for (int index : new int[] {53, 74})
            assertEquals(ConformanceState.FAILED, report(change(sample(index), "minimumLufs", -23.01d)).state());
    }

    @Test
    void reportBindingRejectsByteTimestampAndHashMismatchAndRetainsOnlyOwnedCompleteHashes() throws Exception
    {
        String profile = ConformancePackageAssembler.hash(ConformanceCodec.profileBytes(ConformanceRequirement.OFFICIAL_LOUDNESS_V1));
        ConformanceRun input = new ConformanceRun("", "", new FrozenList<OfficialSignalEvidence>(new Object[0]), false,
                false, ConformanceReason.NONE, "QM-LOUDNESS-CORE-BS1770-5-V1", "a".repeat(64), profile, "b".repeat(64), 0L);
        String payload = ConformancePackageAssembler.hash(ConformanceCodec.artifactBytes(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, input));
        BuildAlgorithmBinding binding = new BuildAlgorithmBinding(input.algorithmId(), input.algorithmSha256(), profile, payload, input.runnerSha256(), 0L);
        StandardValidationReport report = StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, input, binding);
        assertEquals(ConformanceState.NOT_RUN, report.state());
        assertFalse(report.matches(binding));
        assertEquals(4, new HashSet<>(Arrays.asList(report.algorithmSha256(), report.profileSha256(), report.attestationSha256(), report.runnerSha256())).size());
        assertNotSame(binding.algorithmSha256(), report.algorithmSha256());
        assertNotSame(binding.profileSha256(), report.profileSha256());
        for (BuildAlgorithmBinding mutant : new BuildAlgorithmBinding[] {
                new BuildAlgorithmBinding(input.algorithmId(), "c".repeat(64), profile, payload, input.runnerSha256(), 0L),
                new BuildAlgorithmBinding(input.algorithmId(), input.algorithmSha256(), "c".repeat(64), payload, input.runnerSha256(), 0L),
                new BuildAlgorithmBinding(input.algorithmId(), input.algorithmSha256(), profile, "c".repeat(64), input.runnerSha256(), 0L),
                new BuildAlgorithmBinding(input.algorithmId(), input.algorithmSha256(), profile, payload, "c".repeat(64), 0L),
                new BuildAlgorithmBinding(input.algorithmId(), input.algorithmSha256(), profile, payload, input.runnerSha256(), 1L)})
            assertEquals(ConformanceState.FAILED, StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, input, mutant).state());
        assertEquals(0, BuildAlgorithmBinding.class.getConstructors().length);
        assertEquals(27, OfficialSignalEvidence.class.getDeclaredFields().length);
        assertEquals(25, RequiredOfficialReading.class.getDeclaredFields().length);
        assertEquals(9, StandardValidationReport.class.getDeclaredFields().length);
        OfficialSignalEvidence first = sample(0), second = sample(0);
        assertSame(first.setId(), second.setId()); assertSame(first.setVersion(), second.setVersion());
        assertNotSame(first.signalId(), second.signalId()); assertNotSame(first.signalSha256(), second.signalSha256());
        assertNotSame(first.signalId(), ConformanceRequirement.OFFICIAL_LOUDNESS_V1.requiredReadings().get(0).signalId());
    }

    static OfficialSignalEvidence sample(int index)
    {
        RequiredOfficialReading row = ConformanceRequirement.OFFICIAL_LOUDNESS_V1.requiredReadings().get(index);
        long selected = row.readoutMode() == ReadoutMode.MAXIMUM_FULL_WINDOWS || row.readoutMode() == ReadoutMode.CONSTANT_INTERVAL
                ? row.firstReadoutEndFrame() : row.sourceFrames();
        long gated = row.expectedKind() == LoudnessValueKind.NO_LOUDNESS ? 0L : row.completeGatingBlocks();
        return new OfficialSignalEvidence(row.setId(), row.setVersion(), row.caseNumber(), row.signalId(), row.signalSha256(),
                row.sampleRateHz(), row.channels(), row.readingKind(), row.expectedLufs(), row.expectedLufs(), row.toleranceLu(),
                row.channelLayout(), row.readoutMode(), row.expectedKind(), row.expectedKind(), row.expectedLufs(), row.sourceFrames(),
                row.firstReadoutEndFrame(), row.lastReadoutEndFrame(), row.readoutCount(), selected, selected,
                row.completeGatingBlocks(), gated, gated, 1, true);
    }

    static OfficialSignalEvidence change(OfficialSignalEvidence source, String name, Object value) throws Exception
    {
        Field[] fields = OfficialSignalEvidence.class.getDeclaredFields();
        Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++)
        {
            fields[i].setAccessible(true);
            values[i] = fields[i].getName().equals(name) ? value : fields[i].get(source);
        }
        Constructor<?> constructor = OfficialSignalEvidence.class.getConstructors()[0];
        try { return (OfficialSignalEvidence) constructor.newInstance(values); }
        catch (InvocationTargetException ex)
        {
            if (ex.getCause() instanceof IllegalArgumentException) throw (IllegalArgumentException) ex.getCause();
            throw ex;
        }
    }

    static ConformanceRun run(Object[] evidence, boolean attempted, ConformanceReason reason)
    {
        ConformanceRequirement requirement = ConformanceRequirement.OFFICIAL_LOUDNESS_V1;
        return new ConformanceRun(attempted ? requirement.ituPinnedManifestSha256() : "",
                attempted ? requirement.ebuPinnedManifestSha256() : "", new FrozenList<OfficialSignalEvidence>(evidence), true,
                attempted, reason, requirement.algorithmId(), "", "", "", 0L);
    }

    static StandardValidationReport report(OfficialSignalEvidence row)
    {
        return StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1,
                run(new Object[] {row}, true, ConformanceReason.NONE), null);
    }
}
