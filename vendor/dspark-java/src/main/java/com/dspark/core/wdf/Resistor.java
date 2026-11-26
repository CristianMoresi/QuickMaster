package com.dspark.core.wdf;

/** Ideal resistor. Absorbs its incident wave (b = 0). */
public final class Resistor implements WdfNode
{
    private double value;
    private double portR = 1.0;
    private double a = 0.0;

    /**
     * @param resistanceOhms  resistance in ohms
     */
    public Resistor(double resistanceOhms)
    {
        value = resistanceOhms;
    }

    /**
     * Sets the resistance (at least 1e-9 ohm); it takes effect at the next
     * {@link #updatePorts()}.
     *
     * @param ohms  resistance in ohms
     */
    public void setResistance(double ohms)
    {
        value = (ohms < 1e-9) ? 1e-9 : ohms;
    }

    /** Current resistance in ohms. */
    public double getResistance()
    {
        return value;
    }

    @Override
    public void prepare(double sampleRate)
    {
    }

    @Override
    public void updatePorts()
    {
        portR = value;
    }

    @Override
    public void reset()
    {
        a = 0.0;
    }

    @Override
    public double portResistance()
    {
        return portR;
    }

    @Override
    public double reflected()
    {
        return 0.0;
    }

    @Override
    public void incident(double a)
    {
        this.a = a;
    }

    /** Voltage across the resistor (valid after the root scattered). */
    public double getVoltage()
    {
        return a * 0.5;
    }

    /** Current through the resistor. */
    public double getCurrent()
    {
        return a * 0.5 / portR;
    }
}
