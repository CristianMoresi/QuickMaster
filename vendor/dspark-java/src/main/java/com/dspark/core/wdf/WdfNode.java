package com.dspark.core.wdf;

/**
 * A wave digital filter port seen from its parent: a one-port element or
 * the up-facing port of an adaptor.
 * <p>
 * Wave convention: voltage waves with a = v + R i (incident) and
 * b = v - R i (reflected), so v = (a + b) / 2 and i = (a - b) / (2 R).
 * Per sample a root first collects {@link #reflected()} from its tree, then
 * scatters back down with {@link #incident(double)}.
 */
public interface WdfNode
{
    /**
     * Stores the sample rate (reactances) and forwards it to the children.
     *
     * @param sampleRate  sample rate in Hz
     */
    void prepare(double sampleRate);

    /** Recomputes the port resistance (and an adaptor's scattering) from the children. */
    void updatePorts();

    /** Clears the wave states of this node and its children. */
    void reset();

    /**
     * Port resistance in ohms, valid after {@link #updatePorts()}.
     *
     * @return the port resistance
     */
    double portResistance();

    /**
     * Reflected wave toward the parent.
     *
     * @return the wave b
     */
    double reflected();

    /**
     * Incident wave from the parent.
     *
     * @param a  the wave a
     */
    void incident(double a);
}
