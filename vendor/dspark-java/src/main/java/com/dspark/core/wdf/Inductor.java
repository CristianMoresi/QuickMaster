package com.dspark.core.wdf;

/** Inductor, bilinear discretization: b[n] = -a[n-1], Rp = 2 fs L. */
public final class Inductor implements WdfNode
{
    private double value;
    private double fs = 48000.0;
    private double portR = 1.0;
    private double state = 0.0, a = 0.0, b = 0.0;

    /**
     * @param henries  inductance in henries
     */
    public Inductor(double henries)
    {
        value = henries;
    }

    /**
     * Sets the inductance (at least 1e-12 H); it takes effect at the next
     * {@link #updatePorts()}.
     *
     * @param henries  inductance in henries
     */
    public void setInductance(double henries)
    {
        value = (henries < 1e-12) ? 1e-12 : henries;
    }

    @Override
    public void prepare(double sampleRate)
    {
        fs = sampleRate;
    }

    @Override
    public void updatePorts()
    {
        portR = 2.0 * fs * value;
    }

    @Override
    public void reset()
    {
        state = 0.0;
        a = 0.0;
        b = 0.0;
    }

    @Override
    public double portResistance()
    {
        return portR;
    }

    @Override
    public double reflected()
    {
        b = -state;
        return b;
    }

    @Override
    public void incident(double a)
    {
        this.a = a;
        state = a;
    }

    /** Voltage across the inductor (valid after the root scattered). */
    public double getVoltage()
    {
        return (a + b) * 0.5;
    }

    /** Current through the inductor. */
    public double getCurrent()
    {
        return (a - b) * 0.5 / portR;
    }
}
