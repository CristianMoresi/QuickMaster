package com.dspark.core.wdf;

/** Two-port polarity inverter (flips the polarity of the connected subtree). */
public final class Inverter implements WdfNode
{
    private final WdfNode c;

    /**
     * @param c  the subtree whose polarity is flipped
     */
    public Inverter(WdfNode c)
    {
        this.c = c;
    }

    @Override
    public void prepare(double sampleRate)
    {
        c.prepare(sampleRate);
    }

    @Override
    public void updatePorts()
    {
        c.updatePorts();
    }

    @Override
    public void reset()
    {
        c.reset();
    }

    @Override
    public double portResistance()
    {
        return c.portResistance();
    }

    @Override
    public double reflected()
    {
        return -c.reflected();
    }

    @Override
    public void incident(double a)
    {
        c.incident(-a);
    }
}
