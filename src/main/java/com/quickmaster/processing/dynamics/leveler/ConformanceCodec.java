package com.quickmaster.processing.dynamics.leveler;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Closed, bounded ASCII codec. No I/O, general JSON parser, retained buffer or mutable static state. */
public final class ConformanceCodec
{
    private ConformanceCodec() { }

    public static byte[] profileBytes(ConformanceRequirement requirement)
    {
        if (requirement != ConformanceRequirement.OFFICIAL_LOUDNESS_V1)
            throw new IllegalArgumentException("Only the compiled official requirement is supported.");
        byte[] out = new byte[262144];
        int[] cursor = new int[1];
        append(out, cursor, "{\"E_REQ\":94,\"algorithmId\":\"QM-LOUDNESS-CORE-BS1770-5-V1\",\"enumValues\":{\"ChannelLayout\":[\"MONO_MAIN\",\"STEREO_LR\",\"SURROUND_5_0\",\"SURROUND_5_1\"],\"ConformanceReason\":[\"NONE\",\"NOT_RUN\",\"CORPUS_UNAVAILABLE\",\"CORPUS_PARTIAL\",\"MANIFEST_MISMATCH\",\"EVIDENCE_MISMATCH\",\"DUPLICATE_EVIDENCE\",\"INVALID_EVIDENCE\",\"BUILD_BINDING_MISMATCH\",\"READOUT_MISMATCH\",\"LAYOUT_MISMATCH\",\"CONTAINER_MISMATCH\",\"ATTESTATION_MISMATCH\"],\"ConformanceState\":[\"NOT_RUN\",\"UNAVAILABLE\",\"PARTIAL\",\"FAILED\",\"PASSED\"],\"LoudnessValueKind\":[\"FINITE\",\"NO_LOUDNESS\"],\"ReadingKind\":[\"MOMENTARY\",\"SHORT_TERM\",\"INTEGRATED\"],\"ReadoutMode\":[\"EOF_INTEGRATED\",\"STEADY_EOF\",\"CONSTANT_INTERVAL\",\"MAXIMUM_FULL_WINDOWS\"]},\"norms\":{\"absoluteGateLufs\":\"c051800000000000\",\"id\":\"BS.1770-5\",\"integratedBlockSeconds\":\"3fd999999999999a\",\"integratedHopSeconds\":\"3fb999999999999a\",\"lfePowerWeight\":\"0000000000000000\",\"mainPowerWeight\":\"3ff0000000000000\",\"momentarySeconds\":\"3fd999999999999a\",\"offsetLufs\":\"bfe61cac083126e9\",\"parserVersion\":\"QM-OFFICIAL-RIFF-ORIGINAL-1\",\"readoutVersion\":\"QM-SOURCE-FRAME-READOUT-1\",\"relativeGateLu\":\"c024000000000000\",\"shortTermSeconds\":\"4008000000000000\",\"surroundPowerWeight\":\"3ff68f5c28f5c28f\"},\"pinnedManifests\":[{\"setId\":\"ITU-R-BS.2217-1\",\"setVersion\":\"BS.2217-1\",\"sha256\":\"eb33cd80973eeccbb23efc17a71f37cd307fb040e0b7940f0ef4eb3407714280\"},{\"setId\":\"EBU-TECH-3341-V4\",\"setVersion\":\"LTS-5.0\",\"sha256\":\"73a6e342ae20cc4fd7549d90a8ba5c6437c1e738a97a96b37699999ad0794b29\"}],\"requiredReadings\":");
        append(out, cursor, "[");
        for (int i = 0; i < requirement.requiredReadings().size(); i++)
        {
            if (i != 0) append(out, cursor, ",");
            writeRequirement(out, cursor, requirement.requiredReadings().get(i));
        }
        append(out, cursor, "]");
        append(out, cursor, ",\"requirementId\":\"QM-OFFICIAL-LOUDNESS-FILE-V1\",\"schemaVersion\":\"QM-OFFICIAL-LOUDNESS-PROFILE-1\"}");
        return Arrays.copyOf(out, cursor[0]);
    }

    public static byte[] artifactBytes(ConformanceRequirement requirement, ConformanceRun run)
    {
        if (requirement != ConformanceRequirement.OFFICIAL_LOUDNESS_V1 || run == null)
            throw new IllegalArgumentException("Invalid artifact input.");
        byte[] out = new byte[262144];
        int[] cursor = new int[1];
        append(out, cursor, "{\"algorithmId\":");
        string(out, cursor, run.algorithmId());
        append(out, cursor, ",\"algorithmSha256\":");
        string(out, cursor, run.algorithmSha256());
        append(out, cursor, ",\"attempted\":");
        append(out, cursor, run.attempted() ? "true" : "false");
        append(out, cursor, ",\"createdAtEpochSecond\":");
        number(out, cursor, run.createdAtEpochSecond());
        append(out, cursor, ",\"ebuManifestSha256\":");
        string(out, cursor, run.ebuManifestSha256());
        append(out, cursor, ",\"ebuTermsAuthorized\":");
        append(out, cursor, run.ebuTermsAuthorized() ? "true" : "false");
        append(out, cursor, ",\"evidence\":");
        append(out, cursor, "[");
        for (int i = 0; i < run.evidence().size(); i++)
        {
            if (i != 0) append(out, cursor, ",");
            writeEvidence(out, cursor, run.evidence().get(i));
        }
        append(out, cursor, "]");
        append(out, cursor, ",\"failureReason\":");
        string(out, cursor, label(run.failureReason()));
        append(out, cursor, ",\"ituManifestSha256\":");
        string(out, cursor, run.ituManifestSha256());
        append(out, cursor, ",\"profileSha256\":");
        string(out, cursor, run.profileSha256());
        append(out, cursor, ",\"requirementId\":");
        string(out, cursor, requirement.requirementId());
        append(out, cursor, ",\"runnerSha256\":");
        string(out, cursor, run.runnerSha256());
        append(out, cursor, ",\"schemaVersion\":");
        string(out, cursor, "QM-OFFICIAL-LOUDNESS-ATTESTATION-1");
        append(out, cursor, "}");
        return Arrays.copyOf(out, cursor[0]);
    }

    public static ConformanceRun decodeArtifact(byte[] input)
    {
        if (input == null || input.length == 0 || input.length > 262144)
            throw new IllegalArgumentException("Invalid artifact size.");
        int[] cursor = new int[1];
        expect(input, cursor, "{\"algorithmId\":");
        String algorithmId = readString(input, cursor);
        expect(input, cursor, ",\"algorithmSha256\":");
        String algorithmSha256 = readString(input, cursor);
        expect(input, cursor, ",\"attempted\":");
        boolean attempted = readBoolean(input, cursor);
        expect(input, cursor, ",\"createdAtEpochSecond\":");
        long createdAtEpochSecond = readNumber(input, cursor);
        expect(input, cursor, ",\"ebuManifestSha256\":");
        String ebuManifestSha256 = readString(input, cursor);
        expect(input, cursor, ",\"ebuTermsAuthorized\":");
        boolean ebuTermsAuthorized = readBoolean(input, cursor);
        expect(input, cursor, ",\"evidence\":");
        expect(input, cursor, "[");
        Object[] rows = new Object[ConformanceRequirement.E_REQ];
        int count = 0;
        int previous = -1;
        if (!at(input, cursor, ']'))
        {
            do
            {
                if (count != 0) expect(input, cursor, ",");
                if (count == rows.length) throw new IllegalArgumentException("Too many evidence rows.");
                OfficialSignalEvidence item = readEvidence(input, cursor);
                int order = requiredIndex(item);
                if (order <= previous) throw new IllegalArgumentException("Evidence order or identity mismatch.");
                previous = order;
                rows[count++] = item;
            } while (!at(input, cursor, ']'));
        }
        expect(input, cursor, "]");
        Object[] exact = new Object[count];
        System.arraycopy(rows, 0, exact, 0, count);
        FrozenList<OfficialSignalEvidence> evidence = new FrozenList<OfficialSignalEvidence>(exact);
        expect(input, cursor, ",\"failureReason\":");
        ConformanceReason failureReason = ConformanceReason.valueOf(readString(input, cursor));
        expect(input, cursor, ",\"ituManifestSha256\":");
        String ituManifestSha256 = readString(input, cursor);
        expect(input, cursor, ",\"profileSha256\":");
        String profileSha256 = readString(input, cursor);
        expect(input, cursor, ",\"requirementId\":");
        if (!readString(input, cursor).equals("QM-OFFICIAL-LOUDNESS-FILE-V1"))
            throw new IllegalArgumentException("Unknown conformance schema.");
        expect(input, cursor, ",\"runnerSha256\":");
        String runnerSha256 = readString(input, cursor);
        expect(input, cursor, ",\"schemaVersion\":");
        if (!readString(input, cursor).equals("QM-OFFICIAL-LOUDNESS-ATTESTATION-1"))
            throw new IllegalArgumentException("Unknown conformance schema.");
        expect(input, cursor, "}");
        if (cursor[0] != input.length) throw new IllegalArgumentException("Trailing artifact bytes.");
        ConformanceRun result = new ConformanceRun(ituManifestSha256, ebuManifestSha256, evidence, ebuTermsAuthorized, attempted, failureReason, algorithmId, algorithmSha256, profileSha256, runnerSha256, createdAtEpochSecond);
        if (!Arrays.equals(input, artifactBytes(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, result)))
            throw new IllegalArgumentException("Noncanonical artifact.");
        return result;
    }

    private static void writeRequirement(byte[] out, int[] cursor, RequiredOfficialReading item)
    {
        append(out, cursor, "{\"bitsPerSample\":");
        number(out, cursor, item.bitsPerSample());
        append(out, cursor, ",\"blockAlign\":");
        number(out, cursor, item.blockAlign());
        append(out, cursor, ",\"caseNumber\":");
        number(out, cursor, item.caseNumber());
        append(out, cursor, ",\"channelLayout\":");
        string(out, cursor, label(item.channelLayout()));
        append(out, cursor, ",\"channelMask\":");
        number(out, cursor, item.channelMask());
        append(out, cursor, ",\"channels\":");
        number(out, cursor, item.channels());
        append(out, cursor, ",\"completeGatingBlocks\":");
        number(out, cursor, item.completeGatingBlocks());
        append(out, cursor, ",\"dataBytes\":");
        number(out, cursor, item.dataBytes());
        append(out, cursor, ",\"expectedKind\":");
        string(out, cursor, label(item.expectedKind()));
        append(out, cursor, ",\"expectedLufs\":");
        hex(out, cursor, item.expectedLufs());
        append(out, cursor, ",\"fileBytes\":");
        number(out, cursor, item.fileBytes());
        append(out, cursor, ",\"firstReadoutEndFrame\":");
        number(out, cursor, item.firstReadoutEndFrame());
        append(out, cursor, ",\"lastReadoutEndFrame\":");
        number(out, cursor, item.lastReadoutEndFrame());
        append(out, cursor, ",\"readingKind\":");
        string(out, cursor, label(item.readingKind()));
        append(out, cursor, ",\"readoutCount\":");
        number(out, cursor, item.readoutCount());
        append(out, cursor, ",\"readoutMode\":");
        string(out, cursor, label(item.readoutMode()));
        append(out, cursor, ",\"riffDeclaredEnd\":");
        number(out, cursor, item.riffDeclaredEnd());
        append(out, cursor, ",\"sampleRateHz\":");
        number(out, cursor, item.sampleRateHz());
        append(out, cursor, ",\"setId\":");
        string(out, cursor, item.setId());
        append(out, cursor, ",\"setVersion\":");
        string(out, cursor, item.setVersion());
        append(out, cursor, ",\"signalId\":");
        string(out, cursor, item.signalId());
        append(out, cursor, ",\"signalSha256\":");
        string(out, cursor, item.signalSha256());
        append(out, cursor, ",\"sourceFrames\":");
        number(out, cursor, item.sourceFrames());
        append(out, cursor, ",\"toleranceLu\":");
        hex(out, cursor, item.toleranceLu());
        append(out, cursor, ",\"waveFormatTag\":");
        number(out, cursor, item.waveFormatTag());
        append(out, cursor, "}");
    }

    private static void writeEvidence(byte[] out, int[] cursor, OfficialSignalEvidence item)
    {
        append(out, cursor, "{\"absoluteGateBlocks\":");
        number(out, cursor, item.absoluteGateBlocks());
        append(out, cursor, ",\"caseNumber\":");
        number(out, cursor, item.caseNumber());
        append(out, cursor, ",\"channelLayout\":");
        string(out, cursor, label(item.channelLayout()));
        append(out, cursor, ",\"channels\":");
        number(out, cursor, item.channels());
        append(out, cursor, ",\"completeGatingBlocks\":");
        number(out, cursor, item.completeGatingBlocks());
        append(out, cursor, ",\"eofReached\":");
        append(out, cursor, item.eofReached() ? "true" : "false");
        append(out, cursor, ",\"expectedKind\":");
        string(out, cursor, label(item.expectedKind()));
        append(out, cursor, ",\"expectedLufs\":");
        hex(out, cursor, item.expectedLufs());
        append(out, cursor, ",\"firstReadoutEndFrame\":");
        number(out, cursor, item.firstReadoutEndFrame());
        append(out, cursor, ",\"lastReadoutEndFrame\":");
        number(out, cursor, item.lastReadoutEndFrame());
        append(out, cursor, ",\"maxEndFrame\":");
        number(out, cursor, item.maxEndFrame());
        append(out, cursor, ",\"measuredKind\":");
        string(out, cursor, label(item.measuredKind()));
        append(out, cursor, ",\"measuredLufs\":");
        hex(out, cursor, item.measuredLufs());
        append(out, cursor, ",\"minEndFrame\":");
        number(out, cursor, item.minEndFrame());
        append(out, cursor, ",\"minimumLufs\":");
        hex(out, cursor, item.minimumLufs());
        append(out, cursor, ",\"readingKind\":");
        string(out, cursor, label(item.readingKind()));
        append(out, cursor, ",\"readoutCount\":");
        number(out, cursor, item.readoutCount());
        append(out, cursor, ",\"readoutMode\":");
        string(out, cursor, label(item.readoutMode()));
        append(out, cursor, ",\"relativeGateBlocks\":");
        number(out, cursor, item.relativeGateBlocks());
        append(out, cursor, ",\"resetCount\":");
        number(out, cursor, item.resetCount());
        append(out, cursor, ",\"sampleRateHz\":");
        number(out, cursor, item.sampleRateHz());
        append(out, cursor, ",\"setId\":");
        string(out, cursor, item.setId());
        append(out, cursor, ",\"setVersion\":");
        string(out, cursor, item.setVersion());
        append(out, cursor, ",\"signalId\":");
        string(out, cursor, item.signalId());
        append(out, cursor, ",\"signalSha256\":");
        string(out, cursor, item.signalSha256());
        append(out, cursor, ",\"sourceFramesConsumed\":");
        number(out, cursor, item.sourceFramesConsumed());
        append(out, cursor, ",\"toleranceLu\":");
        hex(out, cursor, item.toleranceLu());
        append(out, cursor, "}");
    }

    private static OfficialSignalEvidence readEvidence(byte[] input, int[] cursor)
    {
        expect(input, cursor, "{\"absoluteGateBlocks\":");
        long absoluteGateBlocks = readNumber(input, cursor);
        expect(input, cursor, ",\"caseNumber\":");
        int caseNumber = Math.toIntExact(readNumber(input, cursor));
        expect(input, cursor, ",\"channelLayout\":");
        ChannelLayout channelLayout = ChannelLayout.valueOf(readString(input, cursor));
        expect(input, cursor, ",\"channels\":");
        int channels = Math.toIntExact(readNumber(input, cursor));
        expect(input, cursor, ",\"completeGatingBlocks\":");
        long completeGatingBlocks = readNumber(input, cursor);
        expect(input, cursor, ",\"eofReached\":");
        boolean eofReached = readBoolean(input, cursor);
        expect(input, cursor, ",\"expectedKind\":");
        LoudnessValueKind expectedKind = LoudnessValueKind.valueOf(readString(input, cursor));
        expect(input, cursor, ",\"expectedLufs\":");
        double expectedLufs = readHex(input, cursor);
        expect(input, cursor, ",\"firstReadoutEndFrame\":");
        long firstReadoutEndFrame = readNumber(input, cursor);
        expect(input, cursor, ",\"lastReadoutEndFrame\":");
        long lastReadoutEndFrame = readNumber(input, cursor);
        expect(input, cursor, ",\"maxEndFrame\":");
        long maxEndFrame = readNumber(input, cursor);
        expect(input, cursor, ",\"measuredKind\":");
        LoudnessValueKind measuredKind = LoudnessValueKind.valueOf(readString(input, cursor));
        expect(input, cursor, ",\"measuredLufs\":");
        double measuredLufs = readHex(input, cursor);
        expect(input, cursor, ",\"minEndFrame\":");
        long minEndFrame = readNumber(input, cursor);
        expect(input, cursor, ",\"minimumLufs\":");
        double minimumLufs = readHex(input, cursor);
        expect(input, cursor, ",\"readingKind\":");
        ReadingKind readingKind = ReadingKind.valueOf(readString(input, cursor));
        expect(input, cursor, ",\"readoutCount\":");
        long readoutCount = readNumber(input, cursor);
        expect(input, cursor, ",\"readoutMode\":");
        ReadoutMode readoutMode = ReadoutMode.valueOf(readString(input, cursor));
        expect(input, cursor, ",\"relativeGateBlocks\":");
        long relativeGateBlocks = readNumber(input, cursor);
        expect(input, cursor, ",\"resetCount\":");
        int resetCount = Math.toIntExact(readNumber(input, cursor));
        expect(input, cursor, ",\"sampleRateHz\":");
        int sampleRateHz = Math.toIntExact(readNumber(input, cursor));
        expect(input, cursor, ",\"setId\":");
        String setId = readString(input, cursor);
        expect(input, cursor, ",\"setVersion\":");
        String setVersion = readString(input, cursor);
        expect(input, cursor, ",\"signalId\":");
        String signalId = readString(input, cursor);
        expect(input, cursor, ",\"signalSha256\":");
        String signalSha256 = readString(input, cursor);
        expect(input, cursor, ",\"sourceFramesConsumed\":");
        long sourceFramesConsumed = readNumber(input, cursor);
        expect(input, cursor, ",\"toleranceLu\":");
        double toleranceLu = readHex(input, cursor);
        expect(input, cursor, "}");
        return new OfficialSignalEvidence(setId, setVersion, caseNumber, signalId, signalSha256, sampleRateHz, channels, readingKind, expectedLufs, measuredLufs, toleranceLu, channelLayout, readoutMode, expectedKind, measuredKind, minimumLufs, sourceFramesConsumed, firstReadoutEndFrame, lastReadoutEndFrame, readoutCount, minEndFrame, maxEndFrame, completeGatingBlocks, absoluteGateBlocks, relativeGateBlocks, resetCount, eofReached);
    }

    private static int requiredIndex(OfficialSignalEvidence item)
    {
        FrozenList<RequiredOfficialReading> rows = ConformanceRequirement.OFFICIAL_LOUDNESS_V1.requiredReadings();
        for (int i = 0; i < rows.size(); i++)
        {
            RequiredOfficialReading row = rows.get(i);
            if (row.setId().equals(item.setId()) && row.setVersion().equals(item.setVersion())
                    && row.caseNumber() == item.caseNumber() && row.signalId().equals(item.signalId())
                    && row.readingKind() == item.readingKind()) return i;
        }
        throw new IllegalArgumentException("Unknown official evidence key.");
    }

    private static void append(byte[] out, int[] cursor, String text)
    {
        if (text.length() > out.length - cursor[0]) throw new IllegalArgumentException("Artifact cap exceeded.");
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c > 127) throw new IllegalArgumentException("Artifact must be ASCII.");
            out[cursor[0]++] = (byte) c;
        }
    }

    private static void string(byte[] out, int[] cursor, String text)
    {
        if (text == null || text.length() > 128) throw new IllegalArgumentException("Invalid bounded string.");
        append(out, cursor, "\"");
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c < 32 || c > 126 || c == '"' || c == '\\')
                throw new IllegalArgumentException("Escapes are not canonical.");
        }
        append(out, cursor, text);
        append(out, cursor, "\"");
    }

    private static void number(byte[] out, int[] cursor, long value)
    {
        if (value < 0L) throw new IllegalArgumentException("Negative counter.");
        append(out, cursor, "" + value);
    }

    private static void hex(byte[] out, int[] cursor, double value)
    {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite artifact value.");
        long bits = Double.doubleToRawLongBits(value);
        append(out, cursor, "\"");
        for (int shift = 60; shift >= 0; shift -= 4)
        {
            int digit = (int) ((bits >>> shift) & 15L);
            if (cursor[0] == out.length) throw new IllegalArgumentException("Artifact cap exceeded.");
            out[cursor[0]++] = (byte) (digit < 10 ? '0' + digit : 'a' + digit - 10);
        }
        append(out, cursor, "\"");
    }

    private static boolean at(byte[] input, int[] cursor, char value)
    {
        return cursor[0] < input.length && input[cursor[0]] == value;
    }

    private static void expect(byte[] input, int[] cursor, String text)
    {
        if (text.length() > input.length - cursor[0]) throw new IllegalArgumentException("Truncated artifact.");
        for (int i = 0; i < text.length(); i++)
            if (input[cursor[0]++] != text.charAt(i)) throw new IllegalArgumentException("Noncanonical artifact syntax.");
    }

    private static String readString(byte[] input, int[] cursor)
    {
        expect(input, cursor, "\"");
        int begin = cursor[0];
        while (!at(input, cursor, '"'))
        {
            if (cursor[0] >= input.length || cursor[0] - begin >= 128)
                throw new IllegalArgumentException("Unbounded artifact string.");
            int c = input[cursor[0]++] & 255;
            if (c < 32 || c > 126 || c == '\\') throw new IllegalArgumentException("Invalid artifact ASCII.");
        }
        int length = cursor[0] - begin;
        cursor[0]++;
        return new String(input, begin, length, StandardCharsets.US_ASCII);
    }

    private static long readNumber(byte[] input, int[] cursor)
    {
        int begin = cursor[0];
        long value = 0L;
        while (cursor[0] < input.length && input[cursor[0]] >= '0' && input[cursor[0]] <= '9')
        {
            value = Math.addExact(Math.multiplyExact(value, 10L), input[cursor[0]++] - '0');
        }
        if (cursor[0] == begin || (cursor[0] - begin > 1 && input[begin] == '0'))
            throw new IllegalArgumentException("Noncanonical integer.");
        return value;
    }

    private static boolean readBoolean(byte[] input, int[] cursor)
    {
        if (at(input, cursor, 't')) { expect(input, cursor, "true"); return true; }
        expect(input, cursor, "false");
        return false;
    }

    private static double readHex(byte[] input, int[] cursor)
    {
        expect(input, cursor, "\"");
        long bits = 0L;
        for (int i = 0; i < 16; i++)
        {
            if (cursor[0] == input.length) throw new IllegalArgumentException("Truncated double.");
            int c = input[cursor[0]++] & 255;
            int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
            if (digit < 0) throw new IllegalArgumentException("Noncanonical double.");
            bits = (bits << 4) | digit;
        }
        expect(input, cursor, "\"");
        double value = Double.longBitsToDouble(bits);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite artifact double.");
        return value;
    }

    private static String label(ChannelLayout value)
    {
        if (value == ChannelLayout.MONO_MAIN) return "MONO_MAIN";
        if (value == ChannelLayout.STEREO_LR) return "STEREO_LR";
        if (value == ChannelLayout.SURROUND_5_0) return "SURROUND_5_0";
        if (value == ChannelLayout.SURROUND_5_1) return "SURROUND_5_1";
        throw new IllegalArgumentException("Unknown enum value.");
    }

    private static String label(ReadingKind value)
    {
        if (value == ReadingKind.MOMENTARY) return "MOMENTARY";
        if (value == ReadingKind.SHORT_TERM) return "SHORT_TERM";
        if (value == ReadingKind.INTEGRATED) return "INTEGRATED";
        throw new IllegalArgumentException("Unknown enum value.");
    }

    private static String label(ReadoutMode value)
    {
        if (value == ReadoutMode.EOF_INTEGRATED) return "EOF_INTEGRATED";
        if (value == ReadoutMode.STEADY_EOF) return "STEADY_EOF";
        if (value == ReadoutMode.CONSTANT_INTERVAL) return "CONSTANT_INTERVAL";
        if (value == ReadoutMode.MAXIMUM_FULL_WINDOWS) return "MAXIMUM_FULL_WINDOWS";
        throw new IllegalArgumentException("Unknown enum value.");
    }

    private static String label(LoudnessValueKind value)
    {
        if (value == LoudnessValueKind.FINITE) return "FINITE";
        if (value == LoudnessValueKind.NO_LOUDNESS) return "NO_LOUDNESS";
        throw new IllegalArgumentException("Unknown enum value.");
    }

    private static String label(ConformanceReason value)
    {
        if (value == ConformanceReason.NONE) return "NONE";
        if (value == ConformanceReason.NOT_RUN) return "NOT_RUN";
        if (value == ConformanceReason.CORPUS_UNAVAILABLE) return "CORPUS_UNAVAILABLE";
        if (value == ConformanceReason.CORPUS_PARTIAL) return "CORPUS_PARTIAL";
        if (value == ConformanceReason.MANIFEST_MISMATCH) return "MANIFEST_MISMATCH";
        if (value == ConformanceReason.EVIDENCE_MISMATCH) return "EVIDENCE_MISMATCH";
        if (value == ConformanceReason.DUPLICATE_EVIDENCE) return "DUPLICATE_EVIDENCE";
        if (value == ConformanceReason.INVALID_EVIDENCE) return "INVALID_EVIDENCE";
        if (value == ConformanceReason.BUILD_BINDING_MISMATCH) return "BUILD_BINDING_MISMATCH";
        if (value == ConformanceReason.READOUT_MISMATCH) return "READOUT_MISMATCH";
        if (value == ConformanceReason.LAYOUT_MISMATCH) return "LAYOUT_MISMATCH";
        if (value == ConformanceReason.CONTAINER_MISMATCH) return "CONTAINER_MISMATCH";
        if (value == ConformanceReason.ATTESTATION_MISMATCH) return "ATTESTATION_MISMATCH";
        throw new IllegalArgumentException("Unknown enum value.");
    }
}

