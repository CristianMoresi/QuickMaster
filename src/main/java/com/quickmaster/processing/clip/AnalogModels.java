package com.quickmaster.processing.clip;

import com.dspark.effects.Saturation;
import com.dspark.effects.TapeMachine;
import com.dspark.effects.TubePreamp;

import java.util.function.Supplier;

/**
 * DSPark's analog saturation as the saturation stage uses it: each model
 * fully wet, its oversampling chosen from the rate it runs at (so an
 * oversampled chain lowers it), nothing random, and only the drive left to
 * the stage. Every one saturates further as the drive rises.
 */
final class AnalogModels
{
    /** How far below a circuit's own least drive the stage can drive it, by lowering the level into it. */
    static final double EXTENSION_DB = 24.0;

    private AnalogModels() { }

    /** A new model of a type, for each render or stream. */
    static Supplier<AnalogModel> factory(SaturationType type, TapePreset preset)
    {
        return switch (type)
        {
            case TAPE ->
            {
                TapePreset p = preset == null ? TapePreset.DEFAULT : preset;
                yield () -> new Tape(p);
            }
            case TRANSFORMER -> () -> new Curve(Saturation.Algorithm.TRANSFORMER);
            case TUBE_PREAMP -> Preamp::new;
            case MULTI_STAGE -> () -> new Curve(Saturation.Algorithm.MULTI_STAGE);
            default -> throw new IllegalArgumentException(type + " is a curve, not a circuit");
        };
    }

    /**
     * A circuit and its drive. Below the circuit's own least drive the level
     * into it is lowered by the difference and raised back by as much after
     * it, so a light saturation stays within reach of a circuit whose least
     * drive already saturates some moments of a song.
     */
    abstract static class Model implements AnalogModel
    {
        private final double least, most;
        private double pad = 1.0;

        Model(double least, double most)
        {
            this.least = least;
            this.most = most;
        }

        /** Sets the circuit's own drive, within its range. */
        abstract void drive(double db);

        /** Runs the circuit on planar blocks in place. */
        abstract void run(double[][] x, int n);

        @Override
        public final void setDriveDb(double db)
        {
            double d = Math.max(minDriveDb(), Math.min(most, db));
            drive(Math.max(least, d));
            pad = Math.pow(10.0, Math.min(0.0, d - least) / 20.0);
        }

        @Override public final double minDriveDb() { return least - EXTENSION_DB; }
        @Override public final double maxDriveDb() { return most; }

        @Override
        public final void process(double[][] x, int n)
        {
            if (pad == 1.0)
            {
                run(x, n);
                return;
            }
            for (double[] c : x) for (int i = 0; i < n; i++) c[i] *= pad;
            run(x, n);
            double back = 1.0 / pad;
            for (double[] c : x) for (int i = 0; i < n; i++) c[i] *= back;
        }
    }

    /** A reel-to-reel machine aligned by a preset. */
    static final class Tape extends Model
    {
        private final TapeMachine m = new TapeMachine();

        Tape(TapePreset preset)
        {
            super(-12.0, 24.0);
            preset.apply(m);
            m.setAutomaticOversampling(true);
        }

        @Override public void prepare(double rate, int channels, int block) { m.prepare(rate, channels, block); }
        @Override public void reset() { m.reset(); }
        @Override void drive(double db) { m.setDrive(db); }
        @Override public int latency() { return m.getLatency(); }
        @Override void run(double[][] x, int n) { m.process(x, n); }
        /** Its reference flux: a 1 kHz tone at -12 dBFS in both channels. */
        @Override public double alignmentLufs() { return -12.0; }
    }

    /** One 12AX7 stage with the tone stack at its reference and no cabinet. */
    static final class Preamp extends Model
    {
        private final TubePreamp m = new TubePreamp();

        Preamp()
        {
            super(-12.0, 36.0);
            m.setStages(1);
            m.setTreble(0.5);
            m.setBass(0.5);
            m.setMiddle(0.5);
            m.setCabinet(false);
            m.setOutput(0.0);
            m.setMix(1.0);
            m.setAutomaticOversampling(true);
        }

        @Override public void prepare(double rate, int channels, int block) { m.prepare(rate, channels, block); }
        @Override public void reset() { m.reset(); }
        @Override void drive(double db) { m.setDrive(db); }
        @Override public int latency() { return m.getLatency(); }
        @Override void run(double[][] x, int n) { m.process(x, n); }
        @Override public double alignmentLufs() { return -12.0; }
    }

    /** One of the Saturation effect's analog models, its level taken back by its drive compensation. */
    static final class Curve extends Model
    {
        private final Saturation m = new Saturation();

        Curve(Saturation.Algorithm algorithm)
        {
            super(-12.0, 36.0);
            m.setAlgorithm(algorithm);
            m.setDriveCompensation(true);
            m.setAnalogDrift(0.0);
            m.setDcBlocking(true);
            m.setAntialiasing(Saturation.Antialiasing.SECOND_ORDER);
            m.setProcessingMode(Saturation.ProcessingMode.STEREO);
            m.setOutputMode(Saturation.OutputMode.NORMAL);
            m.setMix(1.0);
            m.setAutomaticOversampling(true);
        }

        @Override public void prepare(double rate, int channels, int block) { m.prepare(rate, channels, block); }
        @Override public void reset() { m.reset(); }
        @Override void drive(double db) { m.setDrive(db); }
        @Override public int latency() { return m.getLatency(); }
        @Override void run(double[][] x, int n) { m.process(x, n); }
        /** Its drive compensation's programme: pink noise at -18 dBFS RMS. */
        @Override public double alignmentLufs() { return -16.0; }
    }
}
