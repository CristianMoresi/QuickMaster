package com.dspark.core.wdf;

/**
 * Ideal voltage source closing a linear tree: b = 2 Vs - a.
 * <p>
 * The whole tree hangs across the source. Set the voltage, call
 * {@link #process()}, then read voltages and currents anywhere in the tree.
 * A non-finite incident wave or source voltage resets the tree instead of
 * being scattered, so it cannot stay in the reactive states; the next finite
 * input continues from rest.
 */
public final class IdealVoltageSourceRoot
{
    private final WdfNode tree;
    private double vs = 0.0;

    /**
     * @param tree  the tree across the source
     */
    public IdealVoltageSourceRoot(WdfNode tree)
    {
        this.tree = tree;
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

    /**
     * Prepares the tree, computes its port resistances and clears its states.
     *
     * @param sampleRate  sample rate in Hz
     */
    public void prepare(double sampleRate)
    {
        tree.prepare(sampleRate);
        tree.updatePorts();
        tree.reset();
    }

    /** Clears the states of the tree. */
    public void reset()
    {
        tree.reset();
    }

    /** Scatters one sample through the tree. */
    public void process()
    {
        final double a = tree.reflected();
        // A non-finite source or wave would stay in the reactive states for
        // good: clear the tree instead, so the next finite input recovers.
        if (!Double.isFinite(a) || !Double.isFinite(vs))
        {
            tree.reset();
            return;
        }
        tree.incident(2.0 * vs - a);
    }
}
