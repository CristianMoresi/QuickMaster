package com.quickmaster.processing.dynamics.leveler.model;

/** The sole retained M-004 root. */
public final class ShadowAnalysisSnapshot
{
    private final ShadowAnalysisResult result;
    private final ShadowAnalysisCache cache;
    private final ShadowDiagnostics diagnostics;

    public ShadowAnalysisSnapshot(ShadowAnalysisResult result,
                                  ShadowAnalysisCache cache,
                                  ShadowDiagnostics diagnostics)
    {
        if (result == null || cache == null || diagnostics == null
                || result.status() != diagnostics.status()
                || result.referencePlan() != cache.referencePlan())
        {
            throw new IllegalArgumentException("Invalid shadow root aliases.");
        }
        for (int i = 0; i < cache.descriptors().size(); i++)
        {
            SegmentDescriptor descriptor = cache.descriptors().get(i);
            if (descriptor.range() != cache.layout().regions().get(i)
                    || descriptor.id() != cache.protections().get(i).id()
                    || descriptor.id() != cache.referencePlan().targets().get(i).segmentId())
            {
                throw new IllegalArgumentException("Invalid per-segment shadow aliases.");
            }
        }
        this.result = result;
        this.cache = cache;
        this.diagnostics = diagnostics;
    }

    public ShadowAnalysisResult result() { return result; }
    public ShadowAnalysisCache cache() { return cache; }
    public ShadowDiagnostics diagnostics() { return diagnostics; }
}
