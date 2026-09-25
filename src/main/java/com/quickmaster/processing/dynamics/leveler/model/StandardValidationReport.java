package com.quickmaster.processing.dynamics.leveler.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.quickmaster.processing.dynamics.leveler.BuildAlgorithmBinding;
import com.quickmaster.processing.dynamics.leveler.ConformanceCodec;
import com.quickmaster.processing.dynamics.leveler.ConformanceRequirement;
import com.quickmaster.processing.dynamics.leveler.ConformanceRun;

/** Finite, bounded diagnostic report. PASSED is reserved for the full build-bound verifier. */
public final class StandardValidationReport
{
    private final String requirementId;
    private final ConformanceState state;
    private final String algorithmId;
    private final String algorithmSha256;
    private final String profileSha256;
    private final String attestationSha256;
    private final String runnerSha256;
    private final FrozenList<RequiredSetReport> sets;
    private final long createdAtEpochSecond;

    public StandardValidationReport(String requirementId, ConformanceState state, String algorithmId,
                                    String algorithmSha256, String profileSha256, String attestationSha256,
                                    String runnerSha256, FrozenList<RequiredSetReport> sets, long createdAtEpochSecond)
    {
        this(requirementId, state, algorithmId, algorithmSha256, profileSha256, attestationSha256,
                runnerSha256, sets, createdAtEpochSecond, false);
    }

    private StandardValidationReport(String requirementId, ConformanceState state, String algorithmId,
                                     String algorithmSha256, String profileSha256, String attestationSha256,
                                     String runnerSha256, FrozenList<RequiredSetReport> sets,
                                     long createdAtEpochSecond, boolean verified)
    {
        if (state == ConformanceState.PASSED && !verified)
            throw new IllegalArgumentException("PASSED is reserved for the official verifier factory.");
        if (!"QM-OFFICIAL-LOUDNESS-FILE-V1".equals(requirementId) || state == null
                || !"QM-LOUDNESS-CORE-BS1770-5-V1".equals(algorithmId) || createdAtEpochSecond < 0L
                || algorithmSha256 == null || profileSha256 == null || attestationSha256 == null || runnerSha256 == null
                || sets == null || sets.size() != 2
                || !"ITU-R-BS.2217-1".equals(sets.get(0).setId())
                || !"EBU-TECH-3341-V4".equals(sets.get(1).setId()))
            throw new IllegalArgumentException("Invalid standard validation report.");
        boolean unbound = algorithmSha256.length() == 0 && profileSha256.length() == 0
                && attestationSha256.length() == 0 && runnerSha256.length() == 0;
        if (!unbound && !(sha256(algorithmSha256) && sha256(profileSha256)
                && sha256(attestationSha256) && sha256(runnerSha256)))
            throw new IllegalArgumentException("Binding must have all four hashes or none.");
        this.requirementId = "QM-OFFICIAL-LOUDNESS-FILE-V1";
        this.state = state;
        this.algorithmId = "QM-LOUDNESS-CORE-BS1770-5-V1";
        this.algorithmSha256 = ownedHash(algorithmSha256);
        this.profileSha256 = ownedHash(profileSha256);
        this.attestationSha256 = ownedHash(attestationSha256);
        this.runnerSha256 = ownedHash(runnerSha256);
        this.sets = sets;
        this.createdAtEpochSecond = createdAtEpochSecond;
    }

    public String requirementId() { return requirementId; }
    public ConformanceState state() { return state; }
    public String algorithmId() { return algorithmId; }
    public String algorithmSha256() { return algorithmSha256; }
    public String profileSha256() { return profileSha256; }
    public String attestationSha256() { return attestationSha256; }
    public String runnerSha256() { return runnerSha256; }
    public FrozenList<RequiredSetReport> sets() { return sets; }
    public long createdAtEpochSecond() { return createdAtEpochSecond; }

    public boolean matches(BuildAlgorithmBinding binding)
    {
        return state == ConformanceState.PASSED && binding != null && algorithmSha256.length() == 64
                && algorithmId.equals(binding.algorithmId()) && algorithmSha256.equals(binding.algorithmSha256())
                && profileSha256.equals(binding.profileSha256()) && attestationSha256.equals(binding.attestationSha256())
                && runnerSha256.equals(binding.runnerSha256()) && createdAtEpochSecond == binding.createdAtEpochSecond();
    }

    public static StandardValidationReport verifyBound(ConformanceRequirement requirement, ConformanceRun run,
                                                        BuildAlgorithmBinding binding)
    {
        if (requirement != ConformanceRequirement.OFFICIAL_LOUDNESS_V1)
            throw new IllegalArgumentException("Only the pinned official requirement is supported.");
        if (run == null)
            return report(requirement, null, ConformanceState.NOT_RUN,
                    emptySet(requirement, true, ConformanceState.NOT_RUN, ConformanceReason.NOT_RUN),
                    emptySet(requirement, false, ConformanceState.NOT_RUN, ConformanceReason.NOT_RUN), 0L);
        boolean bound = validBinding(requirement, run, binding);
        if (!run.attempted())
        {
            if (binding != null && !bound)
                return failed(requirement, ConformanceReason.BUILD_BINDING_MISMATCH, run.createdAtEpochSecond());
            return report(requirement, bound ? binding : null, ConformanceState.NOT_RUN,
                    emptySet(requirement, true, ConformanceState.NOT_RUN, ConformanceReason.NOT_RUN),
                    emptySet(requirement, false, ConformanceState.NOT_RUN, ConformanceReason.NOT_RUN),
                    run.createdAtEpochSecond());
        }
        ConformanceReason runFailure = run.failureReason();
        if (runFailure != ConformanceReason.NONE && runFailure != ConformanceReason.CORPUS_UNAVAILABLE)
            return failed(requirement, runFailure, run.createdAtEpochSecond());
        if (binding != null && !bound)
            return failed(requirement, ConformanceReason.BUILD_BINDING_MISMATCH, run.createdAtEpochSecond());

        Object[] ituRows = new Object[39];
        Object[] ebuRows = new Object[55];
        int ituCount = 0;
        int ebuCount = 0;
        int previous = -1;
        ConformanceReason ituReason = ConformanceReason.NONE;
        ConformanceReason ebuReason = ConformanceReason.NONE;
        for (int i = 0; i < run.evidence().size(); i++)
        {
            OfficialSignalEvidence item = run.evidence().get(i);
            int index = indexOf(requirement, item);
            if (index < 0) return failed(requirement, ConformanceReason.EVIDENCE_MISMATCH, run.createdAtEpochSecond());
            if (index <= previous)
                return failed(requirement, index == previous || seenBefore(run, i, index, requirement)
                        ? ConformanceReason.DUPLICATE_EVIDENCE : ConformanceReason.EVIDENCE_MISMATCH,
                        run.createdAtEpochSecond());
            previous = index;
            RequiredOfficialReading expected = requirement.requiredReadings().get(index);
            ConformanceReason reason = readingReason(expected, item);
            if (index < 39)
            {
                ituRows[ituCount++] = item;
                if (reason != ConformanceReason.NONE) ituReason = reason;
            }
            else
            {
                ebuRows[ebuCount++] = item;
                if (reason != ConformanceReason.NONE) ebuReason = reason;
            }
        }
        RequiredSetReport itu = evaluateSet(requirement, true, run.ituManifestSha256(), ituRows, ituCount,
                ituReason, true);
        RequiredSetReport ebu = evaluateSet(requirement, false, run.ebuManifestSha256(), ebuRows, ebuCount,
                ebuReason, run.ebuTermsAuthorized());
        ConformanceState overall;
        if (itu.state() == ConformanceState.FAILED || ebu.state() == ConformanceState.FAILED)
            overall = ConformanceState.FAILED;
        else if (itu.state() == ConformanceState.UNAVAILABLE && ebu.state() == ConformanceState.UNAVAILABLE)
            overall = ConformanceState.UNAVAILABLE;
        else if (itu.state() == ConformanceState.PASSED && ebu.state() == ConformanceState.PASSED)
            overall = ConformanceState.PASSED;
        else overall = ConformanceState.PARTIAL;
        if (overall == ConformanceState.PASSED && !bound)
            return failed(requirement, ConformanceReason.BUILD_BINDING_MISMATCH, run.createdAtEpochSecond());
        return report(requirement, bound ? binding : null, overall, itu, ebu, run.createdAtEpochSecond());
    }

    private static boolean seenBefore(ConformanceRun run, int stop, int index, ConformanceRequirement requirement)
    {
        for (int i = 0; i < stop; i++)
            if (indexOf(requirement, run.evidence().get(i)) == index) return true;
        return false;
    }

    private static int indexOf(ConformanceRequirement requirement, OfficialSignalEvidence item)
    {
        for (int i = 0; i < requirement.requiredReadings().size(); i++)
        {
            RequiredOfficialReading expected = requirement.requiredReadings().get(i);
            if (expected.setId().equals(item.setId()) && expected.setVersion().equals(item.setVersion())
                    && expected.caseNumber() == item.caseNumber() && expected.signalId().equals(item.signalId())
                    && expected.readingKind() == item.readingKind()) return i;
        }
        return -1;
    }

    private static ConformanceReason readingReason(RequiredOfficialReading expected, OfficialSignalEvidence item)
    {
        if (!expected.signalSha256().equals(item.signalSha256()) || expected.sampleRateHz() != item.sampleRateHz()
                || expected.channels() != item.channels() || expected.expectedKind() != item.expectedKind()
                || !sameBits(expected.expectedLufs(), item.expectedLufs())
                || !sameBits(expected.toleranceLu(), item.toleranceLu())) return ConformanceReason.EVIDENCE_MISMATCH;
        if (expected.channelLayout() != item.channelLayout()) return ConformanceReason.LAYOUT_MISMATCH;
        if (expected.readoutMode() != item.readoutMode() || expected.sourceFrames() != item.sourceFramesConsumed()
                || expected.firstReadoutEndFrame() != item.firstReadoutEndFrame()
                || expected.lastReadoutEndFrame() != item.lastReadoutEndFrame()
                || expected.readoutCount() != item.readoutCount() || item.resetCount() != 1 || !item.eofReached()
                || expected.completeGatingBlocks() != item.completeGatingBlocks())
            return ConformanceReason.READOUT_MISMATCH;
        if (item.readoutMode() == ReadoutMode.EOF_INTEGRATED || item.readoutMode() == ReadoutMode.STEADY_EOF)
        {
            if (item.minEndFrame() != expected.sourceFrames() || item.maxEndFrame() != expected.sourceFrames()
                    || !sameBits(item.minimumLufs(), item.measuredLufs())) return ConformanceReason.READOUT_MISMATCH;
        }
        if (item.readoutMode() != ReadoutMode.EOF_INTEGRATED
                && (item.completeGatingBlocks() != 0L || item.absoluteGateBlocks() != 0L || item.relativeGateBlocks() != 0L))
            return ConformanceReason.READOUT_MISMATCH;
        if (item.readoutMode() == ReadoutMode.MAXIMUM_FULL_WINDOWS
                && (!sameBits(item.minimumLufs(), item.measuredLufs()) || item.minEndFrame() != item.maxEndFrame()))
            return ConformanceReason.READOUT_MISMATCH;
        if (item.readoutMode() == ReadoutMode.CONSTANT_INTERVAL && item.minimumLufs() > item.measuredLufs())
            return ConformanceReason.READOUT_MISMATCH;
        if (item.measuredKind() != expected.expectedKind()) return ConformanceReason.EVIDENCE_MISMATCH;
        if (expected.expectedKind() == LoudnessValueKind.NO_LOUDNESS)
        {
            if (!"ITU-R-BS.2217-1".equals(expected.setId()) || (expected.caseNumber() != 21 && expected.caseNumber() != 27)
                    || item.readoutMode() != ReadoutMode.EOF_INTEGRATED || item.completeGatingBlocks() <= 0L
                    || item.absoluteGateBlocks() != 0L || item.relativeGateBlocks() != 0L)
                return ConformanceReason.EVIDENCE_MISMATCH;
            return ConformanceReason.NONE;
        }
        if (item.readoutMode() == ReadoutMode.EOF_INTEGRATED
                && (item.absoluteGateBlocks() == 0L || item.relativeGateBlocks() == 0L))
            return ConformanceReason.READOUT_MISMATCH;
        double lower = expected.expectedLufs() - expected.toleranceLu();
        double upper = expected.expectedLufs() + expected.toleranceLu();
        if (item.measuredLufs() < lower || item.measuredLufs() > upper
                || (item.readoutMode() == ReadoutMode.CONSTANT_INTERVAL
                    && (item.minimumLufs() < lower || item.minimumLufs() > upper)))
            return ConformanceReason.EVIDENCE_MISMATCH;
        return ConformanceReason.NONE;
    }

    private static RequiredSetReport evaluateSet(ConformanceRequirement requirement, boolean itu, String manifest,
                                                 Object[] rows, int count, ConformanceReason reason, boolean authorized)
    {
        String pin = itu ? requirement.ituPinnedManifestSha256() : requirement.ebuPinnedManifestSha256();
        if (manifest.length() != 0 && !manifest.equals(pin)) reason = ConformanceReason.MANIFEST_MISMATCH;
        if (count != 0 && manifest.length() == 0) reason = ConformanceReason.MANIFEST_MISMATCH;
        if (count != 0 && !authorized) reason = ConformanceReason.INVALID_EVIDENCE;
        ConformanceState state;
        if (reason != ConformanceReason.NONE) state = ConformanceState.FAILED;
        else if (!authorized || count == 0)
        {
            state = ConformanceState.UNAVAILABLE;
            reason = ConformanceReason.CORPUS_UNAVAILABLE;
        }
        else if (count == (itu ? 39 : 55)) state = ConformanceState.PASSED;
        else
        {
            state = ConformanceState.PARTIAL;
            reason = ConformanceReason.CORPUS_PARTIAL;
        }
        Object[] exact = new Object[count];
        System.arraycopy(rows, 0, exact, 0, count);
        return new RequiredSetReport(itu ? requirement.ituSetId() : requirement.ebuSetId(),
                itu ? requirement.ituSetVersion() : requirement.ebuSetVersion(), sha256(manifest) ? manifest : "",
                state, new FrozenList<OfficialSignalEvidence>(exact), reason);
    }

    private static boolean validBinding(ConformanceRequirement requirement, ConformanceRun run, BuildAlgorithmBinding binding)
    {
        if (binding == null || !requirement.algorithmId().equals(binding.algorithmId())
                || !run.algorithmId().equals(binding.algorithmId())
                || !run.algorithmSha256().equals(binding.algorithmSha256())
                || !run.profileSha256().equals(binding.profileSha256())
                || !run.runnerSha256().equals(binding.runnerSha256())
                || run.createdAtEpochSecond() != binding.createdAtEpochSecond()) return false;
        try
        {
            String profile = hash(ConformanceCodec.profileBytes(requirement));
            return profile.equals("daad1207ff680b67b64108692a2070be389227ac8ddffa177ae9b8ac39ffbc14")
                    && profile.equals(binding.profileSha256())
                    && hash(ConformanceCodec.artifactBytes(requirement, run)).equals(binding.attestationSha256());
        }
        catch (IllegalArgumentException ex)
        {
            return false;
        }
        catch (ArithmeticException ex)
        {
            return false;
        }
        catch (NoSuchAlgorithmException ex)
        {
            return false;
        }
    }

    private static StandardValidationReport failed(ConformanceRequirement requirement, ConformanceReason reason, long time)
    {
        return report(requirement, null, ConformanceState.FAILED,
                emptySet(requirement, true, ConformanceState.FAILED, reason),
                emptySet(requirement, false, ConformanceState.FAILED, reason), time);
    }

    private static RequiredSetReport emptySet(ConformanceRequirement requirement, boolean itu,
                                              ConformanceState state, ConformanceReason reason)
    {
        return new RequiredSetReport(itu ? requirement.ituSetId() : requirement.ebuSetId(),
                itu ? requirement.ituSetVersion() : requirement.ebuSetVersion(), "", state,
                new FrozenList<OfficialSignalEvidence>(new Object[0]), reason);
    }

    private static StandardValidationReport report(ConformanceRequirement requirement, BuildAlgorithmBinding binding,
                                                    ConformanceState state, RequiredSetReport itu,
                                                    RequiredSetReport ebu, long time)
    {
        return new StandardValidationReport(requirement.requirementId(), state, requirement.algorithmId(),
                binding == null ? "" : binding.algorithmSha256(), binding == null ? "" : binding.profileSha256(),
                binding == null ? "" : binding.attestationSha256(), binding == null ? "" : binding.runnerSha256(),
                new FrozenList<RequiredSetReport>(new Object[] {itu, ebu}), time, true);
    }

    private static String ownedHash(String value)
    {
        return value.length() == 0 ? "" : new String(value.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }

    private static boolean sha256(String value)
    {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < 64; i++)
        {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static boolean sameBits(double first, double second)
    {
        return Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(second);
    }

    private static String hash(byte[] input) throws NoSuchAlgorithmException
    {
        MessageDigest hasher = MessageDigest.getInstance("SHA-256");
        hasher.update(input);
        byte[] digest = hasher.digest();
        byte[] hex = new byte[64];
        for (int i = 0; i < digest.length; i++)
        {
            int high = (digest[i] >>> 4) & 15;
            int low = digest[i] & 15;
            hex[i * 2] = (byte) (high < 10 ? '0' + high : 'a' + high - 10);
            hex[i * 2 + 1] = (byte) (low < 10 ? '0' + low : 'a' + low - 10);
        }
        return new String(hex, StandardCharsets.US_ASCII);
    }
}
