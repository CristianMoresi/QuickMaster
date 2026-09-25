package com.quickmaster.processing.dynamics;

/**
 * Immutable description of gain over source frames.
 *
 * <p>The dense linear adapter preserves Peak/Beat/Punch arithmetic. Structural
 * Leveler schedules use a separate sparse dB representation.</p>
 */
sealed interface GainSchedule permits DenseGainSchedule, SparseGainSchedule
{
    GainDomain domain();

    long sourceFrames();
}

/** The two deliberately non-interchangeable gain domains. */
enum GainDomain
{
    LEGACY_LINEAR,
    SPARSE_DB
}
