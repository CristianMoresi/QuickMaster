package com.quickmaster.processing.dynamics.leveler.model;

/** Explicit optional loudness value without Optional retention. */
public final class MeasuredLoudness
{
    private final boolean present;
    private final double lufs;

    public MeasuredLoudness(boolean present, double lufs)
    {
        if (present)
        {
            if (!Double.isFinite(lufs))
            {
                throw new IllegalArgumentException("Present loudness must be finite.");
            }
        }
        else if (Double.doubleToRawLongBits(lufs) != Double.doubleToRawLongBits(0.0d))
        {
            throw new IllegalArgumentException("Absent loudness must use canonical +0.0.");
        }
        this.present = present;
        this.lufs = lufs;
    }

    public static MeasuredLoudness absent()
    {
        return new MeasuredLoudness(false, 0.0d);
    }

    public boolean present() { return present; }
    public double lufs() { return lufs; }
}
