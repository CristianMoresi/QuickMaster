package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.*;

/** Transient input to the closed official conformance verifier. */
public final class BuildAlgorithmBinding
{
    private final String algorithmId;
    private final String algorithmSha256;
    private final String profileSha256;
    private final String attestationSha256;
    private final String runnerSha256;
    private final long createdAtEpochSecond;

    BuildAlgorithmBinding(String algorithmId,
        String algorithmSha256,
        String profileSha256,
        String attestationSha256,
        String runnerSha256,
        long createdAtEpochSecond)
    {
        if (algorithmId == null || !algorithmId.equals("QM-LOUDNESS-CORE-BS1770-5-V1")
                || createdAtEpochSecond < 0L || !sha256(algorithmSha256) || !sha256(profileSha256)
                || !sha256(attestationSha256) || !sha256(runnerSha256))
            throw new IllegalArgumentException("Invalid BuildAlgorithmBinding.");
        this.algorithmId = "QM-LOUDNESS-CORE-BS1770-5-V1";
        this.algorithmSha256 = algorithmSha256;
        this.profileSha256 = profileSha256;
        this.attestationSha256 = attestationSha256;
        this.runnerSha256 = runnerSha256;
        this.createdAtEpochSecond = createdAtEpochSecond;
    }

    public String algorithmId() { return algorithmId; }
    public String algorithmSha256() { return algorithmSha256; }
    public String profileSha256() { return profileSha256; }
    public String attestationSha256() { return attestationSha256; }
    public String runnerSha256() { return runnerSha256; }
    public long createdAtEpochSecond() { return createdAtEpochSecond; }

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
}
