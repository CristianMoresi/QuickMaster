package com.quickmaster.processing.dynamics.leveler.model;

import java.nio.charset.StandardCharsets;

/** Validation outcome for one required official set. */
public final class RequiredSetReport
{
    private final String setId;
    private final String setVersion;
    private final String manifestSha256;
    private final ConformanceState state;
    private final FrozenList<OfficialSignalEvidence> evidence;
    private final ConformanceReason reason;

    public RequiredSetReport(String setId,
                             String setVersion,
                             String manifestSha256,
                             ConformanceState state,
                             FrozenList<OfficialSignalEvidence> evidence,
                             ConformanceReason reason)
    {
        if (setId == null || !(setId.equals("ITU-R-BS.2217-1") || setId.equals("EBU-TECH-3341-V4"))
                || setVersion == null || !(setVersion.equals("BS.2217-1") || setVersion.equals("LTS-5.0"))
                || (setId.equals("ITU-R-BS.2217-1") != setVersion.equals("BS.2217-1"))
                || manifestSha256 == null || state == null || evidence == null || reason == null
                || evidence.size() > (setId.equals("ITU-R-BS.2217-1") ? 39 : 55)
                || (!manifestSha256.isEmpty() && !isSha256(manifestSha256)))
        {
            throw new IllegalArgumentException("Invalid required-set report.");
        }
        for (int i = 0; i < evidence.size(); i++)
        {
            OfficialSignalEvidence item = evidence.get(i);
            if (item.setId() != setId && !item.setId().equals(setId))
            {
                throw new IllegalArgumentException("Evidence set ID does not match its report.");
            }
            if (item.setVersion() != setVersion && !item.setVersion().equals(setVersion))
            {
                throw new IllegalArgumentException("Evidence version does not match its report.");
            }
        }
        this.setId = setId.equals("ITU-R-BS.2217-1") ? "ITU-R-BS.2217-1" : "EBU-TECH-3341-V4";
        this.setVersion = setVersion.equals("BS.2217-1") ? "BS.2217-1" : "LTS-5.0";
        this.manifestSha256 = manifestSha256.length() == 0 ? ""
                : new String(manifestSha256.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
        this.state = state;
        this.evidence = evidence;
        this.reason = reason;
    }

    public String setId() { return setId; }
    public String setVersion() { return setVersion; }
    public String manifestSha256() { return manifestSha256; }
    public ConformanceState state() { return state; }
    public FrozenList<OfficialSignalEvidence> evidence() { return evidence; }
    public ConformanceReason reason() { return reason; }

    private static boolean isSha256(String value)
    {
        if (value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
