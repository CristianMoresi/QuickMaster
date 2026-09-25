package com.quickmaster.processing.dynamics.leveler.model;

/** M-004-only result facade. */
public final class ShadowAnalysisResult
{
    private final ShadowAnalysisStatus status;
    private final ReferencePlan referencePlan;

    public ShadowAnalysisResult(ShadowAnalysisStatus status, ReferencePlan referencePlan)
    {
        if (status == null || referencePlan == null)
        {
            throw new IllegalArgumentException("Shadow result values must not be null.");
        }
        this.status = status;
        this.referencePlan = referencePlan;
    }

    public ShadowAnalysisStatus status() { return status; }
    public ReferencePlan referencePlan() { return referencePlan; }
}
