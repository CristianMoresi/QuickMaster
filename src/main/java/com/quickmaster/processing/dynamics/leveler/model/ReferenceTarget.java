package com.quickmaster.processing.dynamics.leveler.model;

/** M-004 raw reference only; no controls, clamps, ramps, or schedule. */
public final class ReferenceTarget
{
    private final SegmentId segmentId;
    private final MeasuredLoudness referenceLoudness;
    private final double rawDb;
    private final double gConf;
    private final double confidenceWeightedDb;
    private final ReferenceReason reason;

    public ReferenceTarget(SegmentId segmentId,
                           MeasuredLoudness referenceLoudness,
                           double rawDb,
                           double gConf,
                           double confidenceWeightedDb,
                           ReferenceReason reason)
    {
        if (segmentId == null || referenceLoudness == null || reason == null
                || !Double.isFinite(rawDb) || !Double.isFinite(gConf)
                || !Double.isFinite(confidenceWeightedDb) || gConf < 0.0d || gConf > 1.0d
                || Double.doubleToRawLongBits(confidenceWeightedDb)
                        != Double.doubleToRawLongBits(rawDb * gConf))
        {
            throw new IllegalArgumentException("Invalid reference target.");
        }
        if (!referenceLoudness.present()
                && (Double.doubleToRawLongBits(rawDb) != Double.doubleToRawLongBits(0.0d)
                    || Double.doubleToRawLongBits(gConf) != Double.doubleToRawLongBits(0.0d)
                    || Double.doubleToRawLongBits(confidenceWeightedDb)
                        != Double.doubleToRawLongBits(0.0d)))
        {
            throw new IllegalArgumentException("Absent reference must be canonical unit.");
        }
        this.segmentId = segmentId;
        this.referenceLoudness = referenceLoudness;
        this.rawDb = rawDb;
        this.gConf = gConf;
        this.confidenceWeightedDb = confidenceWeightedDb;
        this.reason = reason;
    }

    public SegmentId segmentId() { return segmentId; }
    public MeasuredLoudness referenceLoudness() { return referenceLoudness; }
    public double rawDb() { return rawDb; }
    public double gConf() { return gConf; }
    public double confidenceWeightedDb() { return confidenceWeightedDb; }
    public ReferenceReason reason() { return reason; }
}
