package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.*;

/** Transient input to the closed official conformance verifier. */
public final class ConformanceRun
{
    private final String ituManifestSha256;
    private final String ebuManifestSha256;
    private final FrozenList<OfficialSignalEvidence> evidence;
    private final boolean ebuTermsAuthorized;
    private final boolean attempted;
    private final ConformanceReason failureReason;
    private final String algorithmId;
    private final String algorithmSha256;
    private final String profileSha256;
    private final String runnerSha256;
    private final long createdAtEpochSecond;

    public ConformanceRun(String ituManifestSha256,
        String ebuManifestSha256,
        FrozenList<OfficialSignalEvidence> evidence,
        boolean ebuTermsAuthorized,
        boolean attempted,
        ConformanceReason failureReason,
        String algorithmId,
        String algorithmSha256,
        String profileSha256,
        String runnerSha256,
        long createdAtEpochSecond)
    {
        if (algorithmId == null || !algorithmId.equals("QM-LOUDNESS-CORE-BS1770-5-V1")
                || createdAtEpochSecond < 0L || algorithmSha256 == null || profileSha256 == null || runnerSha256 == null
                || ituManifestSha256 == null || ebuManifestSha256 == null || evidence == null
                || evidence.size() > ConformanceRequirement.E_REQ || failureReason == null
                || (!attempted && (failureReason != ConformanceReason.NONE || evidence.size() != 0
                    || ituManifestSha256.length() != 0 || ebuManifestSha256.length() != 0)))
            throw new IllegalArgumentException("Invalid ConformanceRun.");
        this.ituManifestSha256 = ituManifestSha256;
        this.ebuManifestSha256 = ebuManifestSha256;
        this.evidence = evidence;
        this.ebuTermsAuthorized = ebuTermsAuthorized;
        this.attempted = attempted;
        this.failureReason = failureReason;
        this.algorithmId = "QM-LOUDNESS-CORE-BS1770-5-V1";
        this.algorithmSha256 = algorithmSha256;
        this.profileSha256 = profileSha256;
        this.runnerSha256 = runnerSha256;
        this.createdAtEpochSecond = createdAtEpochSecond;
    }

    public String ituManifestSha256() { return ituManifestSha256; }
    public String ebuManifestSha256() { return ebuManifestSha256; }
    public FrozenList<OfficialSignalEvidence> evidence() { return evidence; }
    public boolean ebuTermsAuthorized() { return ebuTermsAuthorized; }
    public boolean attempted() { return attempted; }
    public ConformanceReason failureReason() { return failureReason; }
    public String algorithmId() { return algorithmId; }
    public String algorithmSha256() { return algorithmSha256; }
    public String profileSha256() { return profileSha256; }
    public String runnerSha256() { return runnerSha256; }
    public long createdAtEpochSecond() { return createdAtEpochSecond; }

}
