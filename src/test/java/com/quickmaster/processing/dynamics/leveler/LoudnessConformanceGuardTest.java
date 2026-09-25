package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class LoudnessConformanceGuardTest
{
    @Test
    void absentUnauthorizedOfficialSetsRemainUnavailableAndNeverPass()
    {
        StandardValidationReport report = new LoudnessConformanceGuard().verify(
                ConformanceRequirement.OFFICIAL_LOUDNESS_V1,
                new ConformanceRun("", "", new FrozenList<OfficialSignalEvidence>(new Object[0]),
                        false, true, ConformanceReason.CORPUS_UNAVAILABLE,
                        "QM-LOUDNESS-CORE-BS1770-5-V1", "", "", "", 0L), null);

        assertEquals(ConformanceState.UNAVAILABLE, report.state());
        assertEquals(2, report.sets().size());
        assertEquals(ConformanceState.UNAVAILABLE, report.sets().get(0).state());
        assertEquals(ConformanceState.UNAVAILABLE, report.sets().get(1).state());
    }

    @Test
    void publicDtoConstructorCannotForgePassedAuthorization()
    {
        FrozenList<RequiredSetReport> sets = new FrozenList<RequiredSetReport>(new Object[] {
                new RequiredSetReport("ITU-R-BS.2217-1", "BS.2217-1", "", ConformanceState.UNAVAILABLE,
                        new FrozenList<OfficialSignalEvidence>(new Object[0]), ConformanceReason.CORPUS_UNAVAILABLE),
                new RequiredSetReport("EBU-TECH-3341-V4", "LTS-5.0", "", ConformanceState.UNAVAILABLE,
                        new FrozenList<OfficialSignalEvidence>(new Object[0]), ConformanceReason.CORPUS_UNAVAILABLE)
        });
        // The same otherwise-valid public input accepts a non-PASS state.
        StandardValidationReport control = new StandardValidationReport("QM-OFFICIAL-LOUDNESS-FILE-V1",
                ConformanceState.UNAVAILABLE, "QM-LOUDNESS-CORE-BS1770-5-V1", "", "", "", "", sets, 0L);
        assertEquals(ConformanceState.UNAVAILABLE, control.state());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new StandardValidationReport(
                "QM-OFFICIAL-LOUDNESS-FILE-V1", ConformanceState.PASSED,
                "QM-LOUDNESS-CORE-BS1770-5-V1", "", "", "", "", sets, 0L));
        assertEquals("PASSED is reserved for the official verifier factory.", failure.getMessage());
    }

    @Test
    void defaultWithoutAttemptIsIndependentlyNotRun()
    {
        StandardValidationReport report = new LoudnessConformanceGuard().verify(
                ConformanceRequirement.OFFICIAL_LOUDNESS_V1,
                new ConformanceRun("", "", new FrozenList<OfficialSignalEvidence>(new Object[0]),
                        false, false, ConformanceReason.NONE, "QM-LOUDNESS-CORE-BS1770-5-V1", "", "", "", 0L), null);
        assertEquals(ConformanceState.NOT_RUN, report.state());
        assertEquals(ConformanceState.NOT_RUN, report.sets().get(0).state());
        assertEquals(ConformanceState.NOT_RUN, report.sets().get(1).state());
        assertEquals("", report.algorithmSha256());
        assertEquals("", report.profileSha256());
        assertEquals("", report.attestationSha256());
        assertEquals("", report.runnerSha256());
    }
}
