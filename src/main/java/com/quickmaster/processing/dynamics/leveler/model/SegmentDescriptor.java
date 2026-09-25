package com.quickmaster.processing.dynamics.leveler.model;

/** Complete bounded descriptor for one structural region. */
public final class SegmentDescriptor
{
    private final SegmentId id;
    private final FrameRange range;
    private final FrozenList<StructuralBin> bins;
    private final long validBinMask;
    private final MeasuredLoudness regionalLoudness;
    private final double loudnessSlopeLuPerSec;
    private final double loudnessDeltaLu;
    private final double loudnessConsistency;
    private final double activitySlopePerSec;
    private final double activitySpread;
    private final double foregroundRatio;
    private final BodyContextVector context;
    private final PcmSketch sketch;

    public SegmentDescriptor(SegmentId id,
                             FrameRange range,
                             FrozenList<StructuralBin> bins,
                             long validBinMask,
                             MeasuredLoudness regionalLoudness,
                             double loudnessSlopeLuPerSec,
                             double loudnessDeltaLu,
                             double loudnessConsistency,
                             double activitySlopePerSec,
                             double activitySpread,
                             double foregroundRatio,
                             BodyContextVector context,
                             PcmSketch sketch)
    {
        if (id == null || range == null || bins == null || bins.size() != 32
                || regionalLoudness == null || context == null || sketch == null
                || !Double.isFinite(loudnessSlopeLuPerSec) || !Double.isFinite(loudnessDeltaLu)
                || !Double.isFinite(loudnessConsistency) || !Double.isFinite(activitySlopePerSec)
                || !Double.isFinite(activitySpread) || !Double.isFinite(foregroundRatio)
                || loudnessConsistency < 0.0d || loudnessConsistency > 1.0d
                || activitySpread < 0.0d || foregroundRatio < 0.0d || foregroundRatio > 1.0d
                || (validBinMask & ~0xFFFF_FFFFL) != 0L)
        {
            throw new IllegalArgumentException("Invalid segment descriptor.");
        }
        this.id = id;
        this.range = range;
        this.bins = bins;
        this.validBinMask = validBinMask;
        this.regionalLoudness = regionalLoudness;
        this.loudnessSlopeLuPerSec = loudnessSlopeLuPerSec;
        this.loudnessDeltaLu = loudnessDeltaLu;
        this.loudnessConsistency = loudnessConsistency;
        this.activitySlopePerSec = activitySlopePerSec;
        this.activitySpread = activitySpread;
        this.foregroundRatio = foregroundRatio;
        this.context = context;
        this.sketch = sketch;
    }

    public SegmentId id() { return id; }
    public FrameRange range() { return range; }
    public FrozenList<StructuralBin> bins() { return bins; }
    public long validBinMask() { return validBinMask; }
    public MeasuredLoudness regionalLoudness() { return regionalLoudness; }
    public double loudnessSlopeLuPerSec() { return loudnessSlopeLuPerSec; }
    public double loudnessDeltaLu() { return loudnessDeltaLu; }
    public double loudnessConsistency() { return loudnessConsistency; }
    public double activitySlopePerSec() { return activitySlopePerSec; }
    public double activitySpread() { return activitySpread; }
    public double foregroundRatio() { return foregroundRatio; }
    public BodyContextVector context() { return context; }
    public PcmSketch sketch() { return sketch; }
}
