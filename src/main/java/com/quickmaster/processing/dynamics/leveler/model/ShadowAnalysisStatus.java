package com.quickmaster.processing.dynamics.leveler.model;

/** Stable shadow-analysis lifecycle outcome. */
public enum ShadowAnalysisStatus
{
    SHADOW_READY,
    DONE,
    INVALID_INPUT,
    CANCELLED,
    INSUFFICIENT_FEATURES,
    TOO_MANY_SEGMENTS,
    MEMORY_BUDGET,
    ANALYSIS_FAILED
}
