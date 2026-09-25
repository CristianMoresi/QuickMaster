package com.quickmaster.processing.dynamics.leveler;

/** Falsifiable V1 policy thresholds; no control values live here. */
public final class LevelerCalibrationProfile
{
    public static final LevelerCalibrationProfile V1 = new LevelerCalibrationProfile("QM-LEVELER-V1");
    public static final LevelerCalibrationProfile V2 = new LevelerCalibrationProfile("QM-LEVELER-V2");

    private final String profileId;

    private LevelerCalibrationProfile(String profileId)
    {
        this.profileId = profileId;
    }

    public String profileId() { return profileId; }
    public boolean isShortTransition(long lengthFrames, int sourceSampleRateHz)
    {
        return lengthFrames < 3L * sourceSampleRateHz;
    }
    public double structuralHopSec() { return 0.5d; }
    public int descriptorBins() { return 32; }
    public int sketchBins() { return 2048; }
    public int maximumSegments() { return 64; }
    public double noveltyMadMultiplier() { return 3.0d; }
    public double boundaryRadiusSec() { return 2.0d; }
    public double minimumBoundarySeparationSec() { return 2.0d; }
    public double durationRatioMinimum() { return 0.75d; }
    public double durationRatioMaximum() { return 1.33d; }
    public int minimumValidBins() { return 24; }
    public int maximumInvalidRun() { return 4; }
    public double groupH() { return 0.85d; }
    public double groupT() { return 0.80d; }
    public double groupA() { return 0.82d; }
    public double groupC() { return 0.85d; }
    public double groupSeparation() { return 0.08d; }
    public double pairH() { return 0.92d; }
    public double pairT() { return 0.90d; }
    public double pairA() { return 0.90d; }
    public double pairC() { return 0.92d; }
    public double pairMargin() { return 0.12d; }
    public double deadbandLu() { return 1.0d; }
}
