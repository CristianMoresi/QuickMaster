package com.quickmaster.processing.dynamics;

/** Publication outcome. Diagnostic fallback states never authorize audio gain. */
enum AnalysisStatus
{
    UNIT,
    LEGACY_READY,
    INVALID_INPUT,
    CLEARED,
    STRUCTURAL_READY,
    STANDARD_VALIDATION_FAILED,
    INSUFFICIENT_ANALYSIS,
    PEAK_UNSAFE,
    CANCELLED,
    INFEASIBLE_INPUT_BASELINE
}
