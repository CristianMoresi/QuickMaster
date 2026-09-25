package com.quickmaster.processing.dynamics;

import java.util.Objects;

/**
 * One immutable, atomically published gain snapshot.
 *
 * <p>Schedule, source format, generation and status travel together so the
 * audio thread never combines fields from different analyses.</p>
 */
record PublishedGain(GainSchedule schedule,
                     int sourceRateHz,
                     int sourceChannels,
                     long analysisGeneration,
                     AnalysisStatus status)
{
    PublishedGain
    {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(status, "status");
        if (analysisGeneration < 0L)
        {
            throw new IllegalArgumentException("Analysis generation must be non-negative.");
        }
        boolean dense = schedule instanceof DenseGainSchedule && schedule.domain() == GainDomain.LEGACY_LINEAR;
        boolean sparse = schedule instanceof SparseGainSchedule && schedule.domain() == GainDomain.SPARSE_DB;
        if (!dense && !sparse)
        {
            throw new IllegalArgumentException("Schedule type and gain domain must agree.");
        }

        if (status == AnalysisStatus.LEGACY_READY || status == AnalysisStatus.STRUCTURAL_READY)
        {
            if (sourceRateHz <= 0 || (sourceChannels != 1 && sourceChannels != 2))
            {
                throw new IllegalArgumentException("Ready publication requires a supported source format.");
            }
            if (schedule.sourceFrames() <= 0L)
            {
                throw new IllegalArgumentException("Ready publication requires source frames.");
            }
            if ((status == AnalysisStatus.LEGACY_READY && !dense)
                    || (status == AnalysisStatus.STRUCTURAL_READY && !sparse))
                throw new IllegalArgumentException("Ready status and schedule domain must agree.");
            double scheduleRate = dense ? ((DenseGainSchedule) schedule).envRateHz()
                    : ((SparseGainSchedule) schedule).sourceRateHz();
            if (scheduleRate != sourceRateHz)
            {
                throw new IllegalArgumentException("Schedule rate and source rate must match.");
            }
        }
        else if (sourceRateHz != 0 || sourceChannels != 0)
        {
            throw new IllegalArgumentException("Unit publication must not claim a source format.");
        }
    }

    static PublishedGain unit(long generation, AnalysisStatus status)
    {
        if (status == AnalysisStatus.LEGACY_READY || status == AnalysisStatus.STRUCTURAL_READY)
        {
            throw new IllegalArgumentException("A ready publication cannot be unit fallback.");
        }
        return new PublishedGain(DenseGainSchedule.unit(), 0, 0, generation, status);
    }

    boolean isRenderableFor(int channels)
    {
        return (status == AnalysisStatus.LEGACY_READY || status == AnalysisStatus.STRUCTURAL_READY)
                && sourceChannels == channels;
    }
}
