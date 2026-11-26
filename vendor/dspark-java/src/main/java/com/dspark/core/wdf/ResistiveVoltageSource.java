package com.dspark.core.wdf;

/**
 * Voltage source with series resistance (Thevenin leaf): b = Vs.
 * <p>
 * The series resistance doubles as the port resistance, which is what makes
 * the source usable anywhere in the tree (an ideal source can only be a
 * root).
 */
public final class ResistiveVoltageSource implements WdfNode
{
    private double value;
    private double portR = 1.0;
    private double vs = 0.0, a = 0.0;

    /**
     * @param seriesResistanceOhms  series resistance in ohms
     */
    public ResistiveVoltageSource(double seriesResistanceOhms)
    {
        value = seriesResistanceOhms;
    }

    /**
     * Sets the series resistance (at least 1e-9 ohm); it takes effect at the
     * next {@link #updatePorts()}.
     *
     * @param ohms  resistance in ohms
     */
    public void setResistance(double ohms)
    {
        value = (ohms < 1e-9) ? 1e-9 : ohms;
    }

    /**
     * Sets the source voltage.
     *
     * @param volts  source voltage
     */
    public void setVoltage(double volts)
    {
        vs = volts;
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
        return vs;
    }

    @Override
    public void incident(double a)
    {
        this.a = a;
    }

    /** Voltage at the source terminals (after the series resistance). */
    public double getVoltage()
    {
        return (a + vs) * 0.5;
    }
}
