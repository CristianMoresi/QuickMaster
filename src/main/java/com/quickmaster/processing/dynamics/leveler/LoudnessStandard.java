package com.quickmaster.processing.dynamics.leveler;

/** Normative loudness constants, separate from calibrable MIR policy. */
public final class LoudnessStandard
{
    public static final LoudnessStandard BS1770_5 = new LoudnessStandard("BS1770-5");

    private final String standardId;

    private LoudnessStandard(String standardId)
    {
        this.standardId = standardId;
    }

    public String standardId() { return standardId; }
    public double momentaryWindowSec() { return 0.4d; }
    public double shortTermWindowSec() { return 3.0d; }
    public double hopSec() { return 0.1d; }
    public double absoluteGateLufs() { return -70.0d; }
    public double integratedRelativeGateLu() { return -10.0d; }
    public double regionalRelativeGateLu() { return -20.0d; }
    public double lufsOffset() { return -0.691d; }
}
