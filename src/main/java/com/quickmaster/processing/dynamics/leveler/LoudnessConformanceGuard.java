package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.StandardValidationReport;

/** Single delegation: only the report's closed verifier can grant PASSED. */
public final class LoudnessConformanceGuard
{
    /** A cached report cannot authorize a different or unbound containing build. */
    public boolean authorizesCurrentBuild(StandardValidationReport report)
    {
        return report != null && report.matches(ConformanceArtifactLoader.currentBuildBinding());
    }

    public StandardValidationReport verify(ConformanceRequirement requirement,
                                           ConformanceRun run,
                                           BuildAlgorithmBinding binding)
    {
        return StandardValidationReport.verifyBound(requirement, run, binding);
    }
}
