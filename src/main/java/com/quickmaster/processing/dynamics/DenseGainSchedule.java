package com.quickmaster.processing.dynamics;

/**
 * Ownership-transferring adapter around a freshly produced legacy envelope.
 *
 * <p>The array is deliberately neither copied nor exposed: the legacy mapper
 * already allocates it, and a second O(F) allocation would defeat the walking
 * skeleton. The caller must not mutate the transferred array after
 * construction.</p>
 */
final class DenseGainSchedule implements GainSchedule
{
    private static final DenseGainSchedule UNIT =
            new DenseGainSchedule(1.0, new float[] { 1.0f });

    private final double envRateHz;
    private final float[] sampleEnv;

    DenseGainSchedule(double envRateHz, float[] sampleEnv)
    {
        if (!Double.isFinite(envRateHz) || envRateHz <= 0.0)
        {
            throw new IllegalArgumentException("Dense envelope rate must be finite and positive.");
        }
        if (sampleEnv == null || sampleEnv.length == 0)
        {
            throw new IllegalArgumentException("Dense envelope must contain at least one frame.");
        }
        for (float gain : sampleEnv)
        {
            if (!Float.isFinite(gain) || gain < 0.0f)
            {
                throw new IllegalArgumentException(
                        "Dense envelope gains must be finite and non-negative.");
            }
        }
        this.envRateHz = envRateHz;
        this.sampleEnv = sampleEnv;
    }

    static DenseGainSchedule unit()
    {
        return UNIT;
    }

    @Override
    public GainDomain domain()
    {
        return GainDomain.LEGACY_LINEAR;
    }

    @Override
    public long sourceFrames()
    {
        return sampleEnv.length;
    }

    double envRateHz()
    {
        return envRateHz;
    }

    /** Exact interpolation and clamps of the pre-M-003 renderer. */
    float sampleLinearLegacy(double pos)
    {
        if (pos <= 0.0) return sampleEnv[0];
        int i = (int) pos;
        if (i >= sampleEnv.length - 1) return sampleEnv[sampleEnv.length - 1];
        float frac = (float) (pos - i);
        return sampleEnv[i] + (sampleEnv[i + 1] - sampleEnv[i]) * frac;
    }
}
