package com.quickmaster.processing.dynamics.leveler.model;

/** Shadow-only per-segment references and deterministic cohort weights. */
public final class ReferencePlan
{
    private final FrozenList<ReferenceTarget> targets;
    private final double[] weights;

    public ReferencePlan(FrozenList<ReferenceTarget> targets, double[] weights)
    {
        if (targets == null || weights == null || targets.size() != weights.length
                || weights.length > 64)
        {
            throw new IllegalArgumentException("Invalid reference plan dimensions.");
        }
        this.targets = targets;
        this.weights = new double[weights.length];
        for (int i = 0; i < weights.length; i++)
        {
            double weight = weights[i];
            if (!Double.isFinite(weight) || weight < 0.0d || weight > 1.0d)
            {
                throw new IllegalArgumentException("Invalid reference weight.");
            }
            this.weights[i] = weight;
        }
    }

    public FrozenList<ReferenceTarget> targets() { return targets; }
    public int size() { return weights.length; }
    public double weightAt(int index) { return weights[index]; }

    public double[] copyWeights()
    {
        double[] copy = new double[weights.length];
        System.arraycopy(weights, 0, copy, 0, copy.length);
        return copy;
    }
}
