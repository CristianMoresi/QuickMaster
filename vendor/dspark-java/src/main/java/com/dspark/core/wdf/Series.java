package com.dspark.core.wdf;

/**
 * Adapted three-port series connector.
 * <p>
 * Children connect to ports 1-2; the up-facing port 3 is reflection-free
 * (R3 = R1 + R2), which is what allows nesting under any parent or root.
 * <p>
 * A polarity inversion is folded into the up-facing port. The raw Fettweis
 * series adaptor enforces KVL as v1 + v2 + v3 = 0, which makes elements read
 * upside down relative to circuit intuition (and flips again at every
 * nesting level). With the folded inversion the natural convention holds
 * instead: the voltage across the up port equals v1 + v2, so a source
 * driving Series(R, C) yields the textbook divider with all element voltages
 * in their expected polarity, at any nesting depth.
 */
public final class Series implements WdfNode
{
    private final WdfNode c1;
    private final WdfNode c2;
    private double portR = 2.0;
    private double gamma1 = 0.5;
    private double a1 = 0.0, a2 = 0.0;

    /**
     * @param c1  child on port 1
     * @param c2  child on port 2
     */
    public Series(WdfNode c1, WdfNode c2)
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
        final double r1 = c1.portResistance();
        final double r2 = c2.portResistance();
        portR = r1 + r2;
        gamma1 = r1 / portR;
    }

    @Override
    public void reset()
    {
        c1.reset();
        c2.reset();
        a1 = 0.0;
        a2 = 0.0;
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
        return a1 + a2;             // -(a1+a2) with the up-port inversion folded in
    }

    @Override
    public void incident(double a3)
    {
        // b_k = a_k - gamma_k * (a1 + a2 + a3'), a3' = -a3 (folded inversion),
        // with gamma1 + gamma2 = 1 on the adapted port.
        final double sum = a1 + a2 - a3;
        c1.incident(a1 - gamma1 * sum);
        c2.incident(a2 - (sum - gamma1 * sum));   // a2 - gamma2 * sum
    }
}
