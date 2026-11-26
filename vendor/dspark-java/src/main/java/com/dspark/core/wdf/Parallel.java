package com.dspark.core.wdf;

/**
 * Adapted three-port parallel connector.
 * <p>
 * Children connect to ports 1-2; the up-facing port 3 is reflection-free
 * (G3 = G1 + G2).
 */
public final class Parallel implements WdfNode
{
    private final WdfNode c1;
    private final WdfNode c2;
    private double portR = 0.5;
    private double d1 = 0.5;
    private double a1 = 0.0, a2 = 0.0, bUp = 0.0;

    /**
     * @param c1  child on port 1
     * @param c2  child on port 2
     */
    public Parallel(WdfNode c1, WdfNode c2)
    {
        this.c1 = c1;
        this.c2 = c2;
    }

    @Override
    public void prepare(double sampleRate)
    {
        c1.prepare(sampleRate);
        c2.prepare(sampleRate);
    }

    @Override
    public void updatePorts()
    {
        c1.updatePorts();
        c2.updatePorts();
        final double g1 = 1.0 / c1.portResistance();
        final double g2 = 1.0 / c2.portResistance();
        portR = 1.0 / (g1 + g2);
        d1 = g1 / (g1 + g2);
    }

    @Override
    public void reset()
    {
        c1.reset();
        c2.reset();
        a1 = 0.0;
        a2 = 0.0;
        bUp = 0.0;
    }

    @Override
    public double portResistance()
    {
        return portR;
    }

    @Override
    public double reflected()
    {
        a1 = c1.reflected();
        a2 = c2.reflected();
        bUp = d1 * a1 + (1.0 - d1) * a2;   // weighted node wave
        return bUp;
    }

    @Override
    public void incident(double a3)
    {
        // Common node wave: bNode = bUp + a3; then b_k = bNode - a_k.
        final double bNode = bUp + a3;
        c1.incident(bNode - a1);
        c2.incident(bNode - a2);
    }
}
