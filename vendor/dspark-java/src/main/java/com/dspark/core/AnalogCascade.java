package com.dspark.core;

/**
 * Up to {@value #MAX_SECTIONS} analog sections in series. {@link #add}
 * copies the section it is given, so the cascade owns its sections.
 */
public final class AnalogCascade
{
    /** Largest number of sections. */
    public static final int MAX_SECTIONS = 16;

    /** The sections; the first {@link #count} are in the cascade. */
    public final AnalogSection[] sections = new AnalogSection[MAX_SECTIONS];

    /** Number of sections in the cascade. */
    public int count = 0;

    /** An empty cascade. */
    public AnalogCascade()
    {
        for (int i = 0; i < MAX_SECTIONS; ++i) sections[i] = new AnalogSection();
    }

    /** A copy of another cascade. */
    public AnalogCascade(AnalogCascade other)
    {
        for (int i = 0; i < MAX_SECTIONS; ++i) sections[i] = new AnalogSection(other.sections[i]);
        count = other.count;
    }

    /** Appends a copy of a section (ignored once the cascade is full). */
    public void add(AnalogSection section)
    {
        if (count < MAX_SECTIONS) sections[count++].set(section);
    }

    /** Squared magnitude of the whole cascade at an angular frequency (rad/s). */
    public double magnitudeSquared(double omega)
    {
        double m = 1.0;
        for (int i = 0; i < count; ++i) m *= sections[i].magnitudeSquared(omega);
        return m;
    }

    /** Magnitude of the whole cascade in decibels at a frequency in hertz. */
    public double magnitudeDb(double hz)
    {
        final double m = magnitudeSquared(2.0 * Math.PI * hz);
        return 10.0 * Math.log10((m < 1e-30) ? 1e-30 : m);
    }
}
