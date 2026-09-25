package com.quickmaster.processing.dynamics.leveler.model;

/** Explicit conformance state; enum order is never authorization. */
public enum ConformanceState
{
    NOT_RUN,
    UNAVAILABLE,
    PARTIAL,
    FAILED,
    PASSED
}
