package com.quickmaster.processing.dynamics.leveler.model;

/** Stable reason when a pair cannot participate in grouping. */
public enum SimilarityRejectionReason
{
    NONE,
    PROTECTED,
    BODY_INELIGIBLE,
    DURATION_RATIO,
    INSUFFICIENT_VALID_BINS,
    INVALID_BIN_RUN,
    DEGENERATE_VECTOR,
    DEGENERATE_SKETCH,
    INTENT_UNIDENTIFIABLE,
    CONTEXT_MISMATCH,
    UNSTABLE_BOUNDARY,
    NON_FINITE,
    AMBIGUOUS
}
