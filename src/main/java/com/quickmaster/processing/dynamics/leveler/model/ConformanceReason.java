package com.quickmaster.processing.dynamics.leveler.model;

/** Stable fail-closed reasons for the official file-loudness profile. */
public enum ConformanceReason
{
    NONE,
    NOT_RUN,
    CORPUS_UNAVAILABLE,
    CORPUS_PARTIAL,
    MANIFEST_MISMATCH,
    EVIDENCE_MISMATCH,
    DUPLICATE_EVIDENCE,
    INVALID_EVIDENCE,
    BUILD_BINDING_MISMATCH,
    READOUT_MISMATCH,
    LAYOUT_MISMATCH,
    CONTAINER_MISMATCH,
    ATTESTATION_MISMATCH
}
