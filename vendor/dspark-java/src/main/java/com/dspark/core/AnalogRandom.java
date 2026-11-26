package com.dspark.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Analog-style random modulation: white, pink and brown drift generators.
 * <p>
 * {@link Generator} produces smooth, analog-style random values for
 * modulation:
 * <ul>
 *   <li>Colored target sequences: every new sample-and-hold target is the
 *       next step of a white, pink (1/f) or brown (1/f^2 random walk)
 *       sequence, so pink and brown drift organically at any rate. At a rate
 *       equal to the sample rate (a new target every sample) this is
 *       continuous audio-rate colored noise.</li>
 *   <li>Sample-and-hold LFO behaviour with one-pole smoothing.</li>
 *   <li>Zero allocations in the generation calls.</li>
 *   <li>DC-free denormal mitigation: an offset of 1e-18 whose sign flips
 *       every sample.</li>
 * </ul>
 * The random numbers come from xoshiro256++ ({@link Detail.Xoshiro256pp}),
 * seeded through splitmix64.
 * <p>
 * Not thread-safe: the setters take effect at the next generation call, and
 * a {@link Generator#reseed} is adopted (with a {@link Generator#reset}) at
 * the next generation call.
 */
public final class AnalogRandom
{
    private AnalogRandom() { }

    /** Internal details: the pseudo-random number generator. */
    public static final class Detail
    {
        private Detail() { }

        /**
         * A compact xoshiro256++ PRNG (not thread-safe). The 256-bit state is
         * seeded by chaining splitmix64 from {@code seed + 0x9E3779B97F4A7C15}.
         * All arithmetic is unsigned 64-bit (two's complement wrap-around).
         */
        public static final class Xoshiro256pp
        {
            private long s0;
            private long s1;
            private long s2;
            private long s3;

            /** A generator seeded with 1. */
            public Xoshiro256pp() { this(1L); }

            /** @param seed the seed, read as an unsigned 64-bit value (0 becomes 1) */
            public Xoshiro256pp(long seed) { reseed(seed); }

            /** Reseeds the state; a zero seed becomes 1. */
            public void reseed(long seed)
            {
                if (seed == 0L) seed = 1L;
                s0 = splitmix64(seed + 0x9E3779B97F4A7C15L);
                s1 = splitmix64(s0);
                s2 = splitmix64(s1);
                s3 = splitmix64(s2);
            }

            /** @return the next 64 random bits (an unsigned value held in a long) */
            public long next()
            {
                final long result = Long.rotateLeft(s0 + s3, 23) + s0;
                final long t = s1 << 17;
                s2 ^= s0;
                s3 ^= s1;
                s1 ^= s2;
                s0 ^= s3;
                s2 ^= t;
                s3 = Long.rotateLeft(s3, 45);
                return result;
            }

            /** @return a uniform double in [0, 1) from the top 53 bits of {@link #next()} */
            public double nextDouble()
            {
                final long x = next();
                final double inv2pow53 = 1.0 / 9007199254740992.0;
                return (double) (x >>> 11) * inv2pow53;
            }

            /** Copies the state of another generator. */
            public void copyFrom(Xoshiro256pp other)
            {
                s0 = other.s0;
                s1 = other.s1;
                s2 = other.s2;
                s3 = other.s3;
            }

            /** @return state word {@code index} (0 to 3) */
            public long getState(int index)
            {
                switch (index)
                {
                    case 0: return s0;
                    case 1: return s1;
                    case 2: return s2;
                    case 3: return s3;
                    default: throw new IndexOutOfBoundsException(index);
                }
            }

            /** Sets the four state words directly (an all-zero state never leaves zero). */
            public void setState(long w0, long w1, long w2, long w3)
            {
                s0 = w0;
                s1 = w1;
                s2 = w2;
                s3 = w3;
            }

            private static long splitmix64(long x)
            {
                x += 0x9E3779B97F4A7C15L;
                x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
                x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
                return x ^ (x >>> 31);
            }
        }
    }

    /** The spectral distribution of the random fluctuations. */
    public enum NoiseType
    {
        /** 1/f noise for organic drift. */
        PINK,
        /** 1/f^2 noise for slow wow and flutter. */
        BROWN,
        /** Uncorrelated noise for sample-and-hold effects. */
        WHITE
    }

    /** Note divisions for tempo-synced retriggering. */
    public enum BpmDivision
    {
        ONE, HALF, QUARTER, EIGHTH, SIXTEENTH, THIRTY_SECOND,
        HALF_TRIPLET, QUARTER_TRIPLET, EIGHTH_TRIPLET, SIXTEENTH_TRIPLET,
        DOTTED_HALF, DOTTED_QUARTER, DOTTED_EIGHTH
    }

    /** Components with calibrated drift defaults ({@link Generator#setAnalogDefault}). */
    public enum AnalogComponent
    {
        TAPE_MACHINE, VACUUM_TUBE, TRANSISTOR, COMPRESSOR, EQUALIZER
    }

    /**
     * Generator of analog-style random modulation, in double precision.
     * <p>
     * Defaults: pink noise, a free-running rate of 1 Hz, the range [-1, 1],
     * no smoothing, no quantization, a 44.1 kHz rate until
     * {@link #prepare(double)}.
     */
    public static final class Generator
    {
        private static final AtomicLong INSTANCE_COUNTER = new AtomicLong(0L);

        private final Detail.Xoshiro256pp prng;
        private double sampleRate = 44100.0;
        private double phaseAccumulator = 0.0;
        private boolean triggerNext = true;
        private double currentValue = 0.0;
        private double targetValue = 0.0;
        private NoiseType noiseType = NoiseType.PINK;
        private boolean useBpmSync = false;
        private float rateHz = 1.0f;
        private float bpm = 120.0f;
        private BpmDivision bpmDivision = BpmDivision.QUARTER;
        private double min = -1.0;
        private double max = 1.0;
        private boolean smoothingEnabled = false;
        private double smoothingCoeff = 1.0;
        private double smoothingTimeMs = 0.0;
        private double quantizationStep = 0.0;
        private long pendingSeed = 0L;
        private double publishedValue = 0.0;
        private double publishedPhase = 0.0;
        private double brownNoiseState = 0.0;
        private final double[] pinkNoiseOctaves = new double[7];
        private double denormalFlip = 1e-18;

        /** A generator with a seed unique to this instance and moment (not reproducible). */
        public Generator()
        {
            prng = new Detail.Xoshiro256pp(generateUniqueSeed());
        }

        /** @param seed the PRNG seed, an unsigned 64-bit value (0 becomes 1) */
        public Generator(long seed)
        {
            prng = new Detail.Xoshiro256pp(seed);
        }

        /**
         * Prepares the generator with the audio sample rate (non-positive or
         * non-finite rates keep the previous one), re-derives the smoothing
         * coefficient from the stored smoothing time and resets.
         */
        public void prepare(double sampleRate)
        {
            if (sampleRate > 0.0 && Double.isFinite(sampleRate)) this.sampleRate = sampleRate;
            // The smoothing time is a time: re-derive its per-sample
            // coefficient for this rate (setSmoothing() is usually called
            // before prepare(), e.g. through setAnalogDefault()).
            updateSmoothingCoeff();
            reset();
        }

        /** Resets the phase, the smoothing and the noise states (the PRNG keeps running). */
        public void reset()
        {
            phaseAccumulator = 0.0;
            triggerNext = true;
            currentValue = 0.0;
            targetValue = 0.0;
            brownNoiseState = 0.0;
            java.util.Arrays.fill(pinkNoiseOctaves, 0.0);
            publishedValue = 0.0;
            publishedPhase = 0.0;
        }

        /**
         * Requests a reseed of the PRNG, applied (with a reset) at the next
         * generation call.
         *
         * @param newSeed the new seed, an unsigned 64-bit value (0 becomes 1)
         */
        public void reseed(long newSeed)
        {
            if (newSeed == 0L) newSeed = 1L;
            pendingSeed = newSeed;
        }

        /**
         * Generates and returns the next modulation sample.
         *
         * @return the next modulation value, mapped into [min, max]
         */
        public double getNextSample()
        {
            final long pending = pendingSeed;
            if (pending != 0L)
            {
                pendingSeed = 0L;
                prng.reseed(pending);
                reset();
            }

            updatePhase();

            if (triggerNext)
            {
                triggerNext = false;
                generateNewTarget(tickColoredNoise(noiseType));
            }

            if (smoothingEnabled)
            {
                final double coeff = smoothingCoeff;
                currentValue += coeff * (targetValue - currentValue);
            }
            else
            {
                currentValue = targetValue;
            }

            // DC-free denormal mitigation.
            denormalFlip = -denormalFlip;
            currentValue += denormalFlip;

            publishedValue = currentValue;
            publishedPhase = phaseAccumulator;

            return currentValue;
        }

        /** Fills the whole buffer with the next modulation samples. */
        public void getNextBlock(double[] outputBuffer)
        {
            getNextBlock(outputBuffer, 0, outputBuffer.length);
        }

        /**
         * Generates a block of modulation samples, the same values as as many
         * {@link #getNextSample()} calls.
         *
         * @param outputBuffer destination
         * @param offset       first index written
         * @param numSamples   samples to generate (nothing happens when not positive)
         */
        public void getNextBlock(double[] outputBuffer, int offset, int numSamples)
        {
            if (numSamples <= 0) return;

            final long pending = pendingSeed;
            if (pending != 0L)
            {
                pendingSeed = 0L;
                prng.reseed(pending);
                reset();
            }

            final boolean smoothing = smoothingEnabled;
            final double coeff = smoothingCoeff;
            final NoiseType type = noiseType;
            final double quantStep = quantizationStep;
            double currentMin = min;
            double currentMax = max;

            if (currentMin > currentMax)
            {
                final double swap = currentMin;
                currentMin = currentMax;
                currentMax = swap;
            }

            final int end = offset + numSamples;
            for (int i = offset; i < end; ++i)
            {
                updatePhase();

                if (triggerNext)
                {
                    triggerNext = false;
                    // Clamp to [-1, 1] before mapping, matching generateNewTarget()
                    // (pink noise can momentarily exceed unity).
                    final double cn = DspMath.clamp(tickColoredNoise(type), -1.0, 1.0);
                    targetValue = currentMin + ((cn * 0.5) + 0.5) * (currentMax - currentMin);
                    if (quantStep > 0.0) targetValue = round(targetValue / quantStep) * quantStep;
                }

                if (smoothing)
                {
                    currentValue += coeff * (targetValue - currentValue);
                }
                else
                {
                    currentValue = targetValue;
                }

                denormalFlip = -denormalFlip;
                currentValue += denormalFlip;

                outputBuffer[i] = currentValue;
            }

            publishedValue = currentValue;
            publishedPhase = phaseAccumulator;
        }

        /** @return the output value of the last generation call (0 after a reset) */
        public double getCurrentValue() { return publishedValue; }

        /** @return the LFO phase in [0, 1) after the last generation call (0 after a reset) */
        public double getPhase() { return publishedPhase; }

        /** Sets the noise type (null is ignored). */
        public void setNoiseType(NoiseType type)
        {
            if (type == null) return;
            noiseType = type;
        }

        /**
         * Free-running retrigger rate in Hz, held in single precision; turns
         * tempo sync off. Non-finite values are ignored. A rate of 0 or less
         * stops the retriggering.
         */
        public void setRateHz(double rateInHz)
        {
            if (!Double.isFinite(rateInHz)) return;
            useBpmSync = false;
            rateHz = (float) rateInHz;
        }

        /**
         * Tempo-synced retrigger rate: one target per note of the division at
         * the tempo (held in single precision). Non-finite tempos are ignored.
         */
        public void setRateBPM(double bpm, BpmDivision division)
        {
            if (!Double.isFinite(bpm) || division == null) return;
            this.bpm = (float) bpm;
            bpmDivision = division;
            useBpmSync = true;
        }

        /** Updates the tempo of the synced rate (non-finite values are ignored). */
        public void updateBPM(double newBpm)
        {
            if (!Double.isFinite(newBpm)) return;
            bpm = (float) newBpm;
        }

        /**
         * Sets the output range; the bounds are swapped when given in reverse.
         * A non-finite bound makes the call ignored.
         */
        public void setRange(double min, double max)
        {
            if (!Double.isFinite(min) || !Double.isFinite(max)) return;
            if (min > max)
            {
                final double swap = min;
                min = max;
                max = swap;
            }
            this.min = min;
            this.max = max;
        }

        /** Enables or disables one-pole smoothing of the held targets with a 50 ms time constant. */
        public void setSmoothing(boolean shouldBeEnabled)
        {
            setSmoothing(shouldBeEnabled, 50.0);
        }

        /**
         * Enables one-pole smoothing of the held targets.
         *
         * @param shouldBeEnabled true to smooth, false for hard steps
         * @param timeInMs        time constant in ms; zero, negative or NaN means
         *                        instantaneous. Stored as a time, so it stays
         *                        correct across a later prepare() at another rate.
         */
        public void setSmoothing(boolean shouldBeEnabled, double timeInMs)
        {
            smoothingEnabled = shouldBeEnabled;
            smoothingTimeMs = timeInMs > 0.0 ? timeInMs : 0.0;
            updateSmoothingCoeff();
        }

        /**
         * Quantizes every new target to a multiple of the step (0 for none).
         * Non-finite or negative steps mean none.
         */
        public void setQuantization(double step)
        {
            if (!Double.isFinite(step) || step < 0.0) step = 0.0;
            quantizationStep = step;
        }

        /** Applies the calibrated drift defaults of a component (null is ignored). */
        public void setAnalogDefault(AnalogComponent component)
        {
            if (component == null) return;
            switch (component)
            {
                case TAPE_MACHINE:
                    setNoiseType(NoiseType.BROWN);
                    setRange(-AnalogConstants.VariationPercent.TAPE_MACHINE / 100.0f, AnalogConstants.VariationPercent.TAPE_MACHINE / 100.0f);
                    setRateHz(0.5f);
                    setSmoothing(true, 100.0f);
                    break;
                case VACUUM_TUBE:
                    setNoiseType(NoiseType.PINK);
                    setRange(-AnalogConstants.VariationPercent.VACUUM_TUBE / 100.0f, AnalogConstants.VariationPercent.VACUUM_TUBE / 100.0f);
                    setRateHz(2.0f);
                    setSmoothing(true, 50.0f);
                    break;
                case TRANSISTOR:
                    setNoiseType(NoiseType.PINK);
                    setRange(-AnalogConstants.VariationPercent.TRANSISTOR / 100.0f, AnalogConstants.VariationPercent.TRANSISTOR / 100.0f);
                    setRateHz(1.0f);
                    setSmoothing(true, 75.0f);
                    break;
                case COMPRESSOR:
                    setNoiseType(NoiseType.PINK);
                    setRange(-AnalogConstants.VariationPercent.COMPRESSOR / 100.0f, AnalogConstants.VariationPercent.COMPRESSOR / 100.0f);
                    setRateHz(1.5f);
                    setSmoothing(true, 80.0f);
                    break;
                case EQUALIZER:
                    setNoiseType(NoiseType.BROWN);
                    setRange(-AnalogConstants.VariationPercent.EQUALIZER / 100.0f, AnalogConstants.VariationPercent.EQUALIZER / 100.0f);
                    setRateHz(0.8f);
                    setSmoothing(true, 120.0f);
                    break;
                default:
                    break;
            }
        }

        /**
         * The next sample mapped onto the integers imin to imax (rounded half
         * away from zero and clamped). When imax is not above imin, imin is
         * returned without generating a sample.
         */
        public long getNextDiscrete(long imin, long imax)
        {
            if (imax <= imin) return imin;
            final double value = getNextSample();

            double minVal = min;
            double maxVal = max;
            if (minVal > maxVal)
            {
                final double swap = minVal;
                minVal = maxVal;
                maxVal = swap;
            }
            if (maxVal == minVal) return imin;

            final double t = (value - minVal) / (maxVal - minVal);
            // The span in double: imax - imin can overflow the integer type.
            final double span = (double) imax - (double) imin;
            final double mapped = t * span + (double) imin;
            final long rounded = llround(mapped);
            return (rounded < imin) ? imin : (imax < rounded) ? imax : rounded;
        }

        /** {@link #getNextDiscrete(long, long)} for int bounds. */
        public int getNextDiscreteInt(int imin, int imax)
        {
            return (int) getNextDiscrete(imin, imax);
        }

        /** Derives the one-pole coefficient from the stored smoothing time. */
        private void updateSmoothingCoeff()
        {
            final double timeMs = smoothingTimeMs;
            // A zero smoothing time means instantaneous.
            final double coeff = (timeMs > 0.0)
                ? 1.0 - Math.exp(-1.0 / (sampleRate * timeMs / 1000.0))
                : 1.0;
            smoothingCoeff = coeff;
        }

        /**
         * Draws the next step of the colored sequence (one per new target).
         * Pink and brown are filtered sequences, so their correlation spans
         * successive targets whatever the rate.
         */
        private double tickColoredNoise(NoiseType type)
        {
            final double white = prng.nextDouble() * 2.0 - 1.0;
            switch (type)
            {
                case WHITE: return white;
                case PINK:  return tickPinkNoise(white);
                case BROWN: return tickBrownNoise(white);
                default:    return white;
            }
        }

        /** Maps one step of the colored sequence to the user boundaries. */
        private void generateNewTarget(double sampledNoise)
        {
            sampledNoise = DspMath.clamp(sampledNoise, -1.0, 1.0);

            double currentMin = min;
            double currentMax = max;
            if (currentMin > currentMax)
            {
                final double swap = currentMin;
                currentMin = currentMax;
                currentMax = swap;
            }

            targetValue = currentMin + ((sampledNoise * 0.5) + 0.5) * (currentMax - currentMin);

            final double quantStep = quantizationStep;
            if (quantStep > 0.0)
            {
                targetValue = round(targetValue / quantStep) * quantStep;
            }
        }

        private void updatePhase()
        {
            double rate = 0.0;
            if (useBpmSync)
            {
                final float bpmNow = bpm;
                if (bpmNow > 0.0f)
                {
                    final double noteLengthInBeats = 4.0 / getBpmDivisionMultiplier(bpmDivision);
                    final double periodInSeconds = (60.0 / (double) bpmNow) * noteLengthInBeats;
                    if (periodInSeconds > 0.0) rate = 1.0 / periodInSeconds;
                }
            }
            else
            {
                rate = (double) rateHz;
            }

            if (rate <= 0.0) return;

            phaseAccumulator += rate / sampleRate;
            if (phaseAccumulator >= 1.0)
            {
                // floor(), not a single -1.0: with rates at or above the
                // sample rate the fixed decrement would let the accumulator
                // grow without bound.
                phaseAccumulator -= Math.floor(phaseAccumulator);
                triggerNext = true;
            }
        }

        /**
         * Paul Kellett's refined 7-state pink-noise filter: a parallel
         * one-pole bank whose summed response tracks -3 dB/oct across the
         * audio band to within about 0.5 dB.
         */
        private double tickPinkNoise(double white)
        {
            double b0 = pinkNoiseOctaves[0];
            double b1 = pinkNoiseOctaves[1];
            double b2 = pinkNoiseOctaves[2];
            double b3 = pinkNoiseOctaves[3];
            double b4 = pinkNoiseOctaves[4];
            double b5 = pinkNoiseOctaves[5];
            final double b6 = pinkNoiseOctaves[6];

            b0 = 0.99886 * b0 + white * 0.0555179;
            b1 = 0.99332 * b1 + white * 0.0750759;
            b2 = 0.96900 * b2 + white * 0.1538520;
            b3 = 0.86650 * b3 + white * 0.3104856;
            b4 = 0.55000 * b4 + white * 0.5329522;
            b5 = -0.7616 * b5 - white * 0.0168980;

            final double pink = b0 + b1 + b2 + b3 + b4 + b5 + b6
                              + white * 0.5362;

            pinkNoiseOctaves[0] = b0;
            pinkNoiseOctaves[1] = b1;
            pinkNoiseOctaves[2] = b2;
            pinkNoiseOctaves[3] = b3;
            pinkNoiseOctaves[4] = b4;
            pinkNoiseOctaves[5] = b5;
            pinkNoiseOctaves[6] = white * 0.115926;

            // Normalisation keeps the peak below unity for white in [-1, 1]
            // (measured worst case about 0.90 over 2^21 samples, RMS about 0.19).
            return pink * 0.11;
        }

        private double tickBrownNoise(double white)
        {
            brownNoiseState += white * 0.02;
            brownNoiseState *= 0.995;
            brownNoiseState = DspMath.clamp(brownNoiseState, -1.0, 1.0);
            return brownNoiseState;
        }

        private static double getBpmDivisionMultiplier(BpmDivision division)
        {
            switch (division)
            {
                case ONE:               return 4.0;
                case HALF:              return 2.0;
                case QUARTER:           return 1.0;
                case EIGHTH:            return 0.5;
                case SIXTEENTH:         return 0.25;
                case THIRTY_SECOND:     return 0.125;
                case HALF_TRIPLET:      return 4.0 / 3.0;
                case QUARTER_TRIPLET:   return 2.0 / 3.0;
                case EIGHTH_TRIPLET:    return 1.0 / 3.0;
                case SIXTEENTH_TRIPLET: return 1.0 / 6.0;
                case DOTTED_HALF:       return 3.0;
                case DOTTED_QUARTER:    return 1.5;
                case DOTTED_EIGHTH:     return 0.75;
                default:                return 1.0;
            }
        }

        private static long generateUniqueSeed()
        {
            final long instanceId = INSTANCE_COUNTER.getAndIncrement();
            final long address = System.identityHashCode(INSTANCE_COUNTER) & 0xFFFFFFFFL;
            final long now = System.nanoTime();
            final long seed = instanceId ^ (address << 1) ^ (now + 0x9E3779B97F4A7C15L);
            return seed == 0L ? 1L : seed;
        }

        /** Rounds half away from zero (as C++ std::round). */
        private static double round(double x)
        {
            final double ax = Math.abs(x);
            if (!(ax < 4503599627370496.0)) return x;   // 2^52 and up, infinities and NaN
            double t = Math.floor(ax);
            if (ax - t >= 0.5) t += 1.0;
            return Math.copySign(t, x);
        }

        /** Rounds half away from zero to a long (NaN gives Long.MIN_VALUE, out of range saturates). */
        private static long llround(double x)
        {
            if (Double.isNaN(x)) return Long.MIN_VALUE;
            return (long) round(x);
        }
    }
}
