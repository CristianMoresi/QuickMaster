package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;

/** Temporary per-segment body/context eligibility; never retained in the snapshot. */
public final class BodyEligibility
{
    private final int segmentOrdinal;
    private final boolean eligible;
    private final SimilarityRejectionReason rejectionReason;

    public BodyEligibility(int segmentOrdinal,
                           boolean eligible,
                           SimilarityRejectionReason rejectionReason)
    {
        if (segmentOrdinal < 0 || segmentOrdinal > 63 || rejectionReason == null
                || (eligible && rejectionReason != SimilarityRejectionReason.NONE)
                || (!eligible && rejectionReason == SimilarityRejectionReason.NONE))
        {
            throw new IllegalArgumentException("Invalid body eligibility.");
        }
        this.segmentOrdinal = segmentOrdinal;
        this.eligible = eligible;
        this.rejectionReason = rejectionReason;
    }

    public int segmentOrdinal() { return segmentOrdinal; }
    public boolean eligible() { return eligible; }
    public SimilarityRejectionReason rejectionReason() { return rejectionReason; }
}
