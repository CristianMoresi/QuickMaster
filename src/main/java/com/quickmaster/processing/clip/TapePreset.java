package com.quickmaster.processing.clip;

import com.dspark.effects.TapeMachine;

/**
 * Typical alignments of a reel-to-reel machine for the tape saturation, from
 * a transparent master to a slow, coloured one. Each sets the speed, the
 * equalization standard, the tape, the track format, the bias, the playback
 * losses, the head bump and the machine's electronics; the drive is the
 * stage's to solve. None moves the pitch or adds noise: wow and flutter,
 * hiss, hum and the bleed between channels stay off, as a mastering transfer
 * keeps them.
 */
public enum TapePreset
{
    /** 30 ips, AES/CCIR, high-output tape, half track, bias at its optimum, light losses: the least colour. */
    CLEAN_MASTER_30("30 ips Clean master", TapeMachine.Speed.IPS_30, TapeMachine.Standard.CCIR,
            TapeMachine.Formulation.HIGH_OUTPUT, TapeMachine.TrackFormat.HALF, 0.5, 0.25, 0.25,
            TapeMachine.HeadModel.BUMP, TapeMachine.Electronics.IDEAL, 0.0, 0.0, 0.0),
    /** 15 ips, CCIR, classic tape, half track, the analog electronics: the usual mastering transfer. */
    MASTER_15("15 ips Master", TapeMachine.Speed.IPS_15, TapeMachine.Standard.CCIR,
            TapeMachine.Formulation.CLASSIC, TapeMachine.TrackFormat.HALF, 0.5, 0.5, 0.5,
            TapeMachine.HeadModel.CONTOUR, TapeMachine.Electronics.CIRCUIT, 0.0, 0.0, 0.0),
    /** 15 ips, NAB, a touch under-biased and aligned 3 dB hot, with even harmonics and output iron. */
    COLORED_15("15 ips Colored", TapeMachine.Speed.IPS_15, TapeMachine.Standard.NAB,
            TapeMachine.Formulation.CLASSIC, TapeMachine.TrackFormat.HALF, 0.45, 0.6, 0.8,
            TapeMachine.HeadModel.CONTOUR, TapeMachine.Electronics.CIRCUIT, 0.25, 0.3, 3.0),
    /** 7.5 ips, NAB: the top saturates first and the head bump rises lower. */
    WARM_7_5("7.5 ips Warm", TapeMachine.Speed.IPS_7_5, TapeMachine.Standard.NAB,
            TapeMachine.Formulation.CLASSIC, TapeMachine.TrackFormat.HALF, 0.5, 0.75, 0.8,
            TapeMachine.HeadModel.CONTOUR, TapeMachine.Electronics.CIRCUIT, 0.2, 0.4, 0.0),
    /** 3.75 ips, NAB, vintage tape on a quarter track, under-biased and hot: the most colour. */
    VINTAGE_3_75("3.75 ips Vintage", TapeMachine.Speed.IPS_3_75, TapeMachine.Standard.NAB,
            TapeMachine.Formulation.VINTAGE, TapeMachine.TrackFormat.QUARTER, 0.45, 1.0, 1.0,
            TapeMachine.HeadModel.CONTOUR, TapeMachine.Electronics.CIRCUIT, 0.4, 0.5, 3.0),
    /** 30 ips, NAB, full track: a large head bump in the low mids under a clean top. */
    FAT_30("30 ips Fat", TapeMachine.Speed.IPS_30, TapeMachine.Standard.NAB,
            TapeMachine.Formulation.CLASSIC, TapeMachine.TrackFormat.FULL, 0.5, 0.4, 1.0,
            TapeMachine.HeadModel.CONTOUR, TapeMachine.Electronics.CIRCUIT, 0.15, 0.3, 0.0);

    /** The alignment a new stage starts with. */
    public static final TapePreset DEFAULT = MASTER_15;

    private final String label;
    private final TapeMachine.Speed speed;
    private final TapeMachine.Standard standard;
    private final TapeMachine.Formulation formulation;
    private final TapeMachine.TrackFormat track;
    private final double bias, losses, headBump;
    private final TapeMachine.HeadModel head;
    private final TapeMachine.Electronics electronics;
    private final double evenHarmonics, outputIron, calibrationDb;

    TapePreset(String label, TapeMachine.Speed speed, TapeMachine.Standard standard,
               TapeMachine.Formulation formulation, TapeMachine.TrackFormat track, double bias, double losses,
               double headBump, TapeMachine.HeadModel head, TapeMachine.Electronics electronics,
               double evenHarmonics, double outputIron, double calibrationDb)
    {
        this.label = label;
        this.speed = speed;
        this.standard = standard;
        this.formulation = formulation;
        this.track = track;
        this.bias = bias;
        this.losses = losses;
        this.headBump = headBump;
        this.head = head;
        this.electronics = electronics;
        this.evenHarmonics = evenHarmonics;
        this.outputIron = outputIron;
        this.calibrationDb = calibrationDb;
    }

    /** The name shown in the interface. */
    public String label() { return label; }

    /** Aligns a machine: every setting of the preset, and off what a transfer keeps off. */
    void apply(TapeMachine m)
    {
        m.setSpeed(speed);
        m.setStandard(standard);
        m.setFormulation(formulation);
        m.setTrackFormat(track);
        m.setBias(bias);
        m.setLossEffects(losses);
        m.setHeadBump(headBump);
        m.setHeadModel(head);
        m.setElectronics(electronics);
        m.setEvenHarmonics(evenHarmonics);
        m.setOutputIron(outputIron);
        m.setCalibrationLevel(calibrationDb);
        m.setWowFlutter(0.0);
        m.setNoise(-200.0);
        m.setHum(-200.0, TapeMachine.Mains.HZ_50);
        m.setChannelBleed(-200.0);
        m.setAlignmentTolerance(0.0);
        m.setMix(1.0);
    }
}
