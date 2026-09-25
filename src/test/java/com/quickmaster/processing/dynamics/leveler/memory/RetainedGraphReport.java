package com.quickmaster.processing.dynamics.leveler.memory;

import java.util.*;

public record RetainedGraphReport(
        String normativeSchemaId, String candidateSha256, boolean approvedGlobalSchema,
        boolean agentPresent, boolean traversalComplete, List<String> inspectedRoots,
        List<Node> nodes, List<Edge> edges, List<Violation> violations,
        long totalRetainedBytes, long accountedAllowedBytes, long unaccountedRetainedBytes,
        Map<String,Long> dimensions)
{
    public record Node(long ordinal, String path, String type, String rule, long length, long shallowBytes, boolean accounted) { }
    public record Edge(long sourceOrdinal, long targetOrdinal, String path, boolean shared, boolean repeated) { }
    public record Violation(String code, String path, String detail) { }
    public boolean stageOneGraphPassed() { return traversalComplete && violations.isEmpty() && unaccountedRetainedBytes==0; }
}
