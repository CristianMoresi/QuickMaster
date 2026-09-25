package com.quickmaster.processing.dynamics.leveler.model;

/** Immutable V2 source-clock evidence; no PCM, spectra or decoded observation cache. */
public final class ComparisonTimeline
{
    private final AudioFormat format;
    private final short[] shortValues;
    private final short[] longValues;
    private final byte[] shortFlags;
    private final byte[] longFlags;

    public ComparisonTimeline(AudioFormat format, short[] shortValues, short[] longValues,
                              byte[] shortFlags, byte[] longFlags)
    {
        if (format == null || shortValues == null || longValues == null
                || shortFlags == null || longFlags == null || format.sampleRateHz() < 40)
            throw new IllegalArgumentException("Invalid comparison timeline.");
        int shorts = count(format, 40);
        int longs = count(format, 8);
        if (shortValues.length != Math.multiplyExact(shorts, 52)
                || longValues.length != Math.multiplyExact(longs, 36)
                || shortFlags.length != shorts || longFlags.length != longs)
            throw new IllegalArgumentException("Comparison timeline length mismatch.");
        this.format = format;
        this.shortValues = new short[shortValues.length];
        this.longValues = new short[longValues.length];
        this.shortFlags = new byte[shortFlags.length];
        this.longFlags = new byte[longFlags.length];
        System.arraycopy(shortValues, 0, this.shortValues, 0, shortValues.length);
        System.arraycopy(longValues, 0, this.longValues, 0, longValues.length);
        System.arraycopy(shortFlags, 0, this.shortFlags, 0, shortFlags.length);
        System.arraycopy(longFlags, 0, this.longFlags, 0, longFlags.length);
        // Validate the defensive copies, never caller-owned arrays after validation.
        validate(this.shortValues, this.shortFlags, 52);
        validate(this.longValues, this.longFlags, 36);
    }

    public AudioFormat format() { return format; }
    public int shortCount() { return shortFlags.length; }
    public int longCount() { return longFlags.length; }
    public byte shortFlagsAt(int row) { return shortFlags[row]; }
    public byte longFlagsAt(int row) { return longFlags[row]; }
    public short shortValueAt(int row, int component)
    {
        return shortValues[index(row, component, shortFlags.length, 52)];
    }
    public short longValueAt(int row, int component)
    {
        return longValues[index(row, component, longFlags.length, 36)];
    }

    private static int count(AudioFormat format, int rate)
    {
        return Math.toIntExact(Math.addExact(Math.multiplyExact(format.frames(), (long) rate),
                format.sampleRateHz() - 1L) / format.sampleRateHz());
    }

    private static int index(int row, int component, int count, int stride)
    {
        if (row < 0 || row >= count || component < 0 || component >= stride)
            throw new IllegalArgumentException("Comparison observation index out of range.");
        return Math.addExact(Math.multiplyExact(row, stride), component);
    }

    private static void validate(short[] values, byte[] flags, int stride)
    {
        for (int row = 0; row < flags.length; row++)
        {
            int bits = flags[row] & 255;
            boolean valid = (bits & 3) == 3;
            if ((bits & ~7) != 0 || ((bits & 4) != 0 && !valid))
                throw new IllegalArgumentException("Invalid comparison flags.");
            int offset = row * stride;
            long tonalSum = 0L;
            for (int component = 0; component < stride; component++)
            {
                short value = values[offset + component];
                if (!valid && value != 0)
                    throw new IllegalArgumentException("Invalid comparison payload is not zero.");
                if (component < 36) tonalSum += value & 65535;
                else if (component < 44 ? (value < -10240 || value > 0)
                        : (value < 0 || value > 10240))
                    throw new IllegalArgumentException("Comparison dB value outside Q8.8 bounds.");
            }
            if (((bits & 4) != 0) != (tonalSum > 0L))
                throw new IllegalArgumentException("Comparison tonal payload/flag mismatch.");
        }
    }
}
