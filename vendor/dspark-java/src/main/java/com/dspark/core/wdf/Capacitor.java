package com.dspark.core.wdf;

/** Capacitor, bilinear discretization: b[n] = a[n-1], Rp = 1/(2 fs C). */
public final class Capacitor implements WdfNode
{
    private double value;
    private double fs = 48000.0;
    private double portR = 1.0;
    private double state = 0.0, a = 0.0, b = 0.0;

    /**
     * @param farads  capacitance in farads
     */
    public Capacitor(double farads)
    {
        value = farads;
    }

    /**
     * Sets the capacitance (at least 1e-18 F); it takes effect at the next
     * {@link #updatePorts()}.
     *
     * @param farads  capacitance in farads
     */
    public void setCapacitance(double farads)
    {
        value = (farads < 1e-18) ? 1e-18 : farads;
    }

    /** Current capacitance in farads. */
    public double getCapacitance()
    {
        return value;
    }

    @Override
    public void prepare(double sampleRate)
    {
        fs = sampleRate;
    }

    @Override
    public void updatePorts()
    {
        portR = 1.0 / (2.0 * fs * value);
    }

    @Override
    public void reset()
    {
        state = 0.0;
        a = 0.0;
        b = 0.0;
    }

    /**
     * Shifts the voltage reference without changing the capacitor current:
     * the offset is added to both wave variables and the delayed wave,
     * preserving their difference. Stream-owner only.
     *
     * @param offset  voltage offset
     */
    public void offsetVoltage(double offset)
    {
        state += offset;
        a += offset;
        b += offset;
    }

    /**
     * Copies the whole element (value, sample rate, port resistance and wave
     * states) from another capacitor.
     *
     * @param other  the capacitor to copy
     */
    public void copyFrom(Capacitor other)
    {
        value = other.value;
        fs = other.fs;
        portR = other.portR;
        state = other.state;
        a = other.a;
        b = other.b;
    }

    @Override
    public double portResistance()
    {
        return portR;
    }

    @Override
    public double reflected()
    {
        b = state;
        return b;
    }

    @Override
    public void incident(double a)
    {
        this.a = a;
        state = a;
    }

    /** Voltage across the capacitor (valid after the root scattered). */
    public double getVoltage()
    {
        return (a + b) * 0.5;
    }

    /** Current through the capacitor. */
    public double getCurrent()
    {
        return (a - b) * 0.5 / portR;
    }
}
