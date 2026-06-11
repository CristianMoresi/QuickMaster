package com.quickmaster.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializable snapshot of the entire mastering chain configuration:
 * every module's enabled state and parameters, the EQ bands, the chain
 * and dynamics ordering, the output settings and the oversampling
 * choice. Used three ways: saved to / loaded from disk as a JSON
 * preset, held in memory as the A/B settings slots, and pushed onto
 * the undo history so parameter changes are undoable like edits.
 * <p>
 * Plain public fields so Gson maps it without adapters; enum-typed
 * values are stored by name for forward compatibility.
 */
public final class ChainPreset
{
    public int version = 1;

    /* Auto EQ */
    public boolean autoEqOn;
    public double autoEqAmount;
    public String autoEqTarget;
    public double autoEqAttackSec;
    public double autoEqReleaseSec;

    /* Equalizer */
    public boolean eqOn;
    public List<BandPreset> bands = new ArrayList<>();

    /* Fade */
    public double fadeInSec;
    public double fadeOutSec;
    public String fadeType;

    /* Dynamics */
    public boolean dynamicsOn;
    public boolean peakCompOn;
    public double peakCompTargetDb;
    public boolean beatCompOn;
    public double beatCompTargetDb;
    public String beatNote;
    public boolean levelerOn;
    public double leveling;
    public double levelerSpeed;
    public boolean punchOn;
    public double punchAmountDb;
    /** Compressor order, by key: "peak", "beat", "leveler", "punch". */
    public List<String> dynamicsOrder = new ArrayList<>();

    /* Clip */
    public boolean clipOn;
    public boolean softClipOn;
    public double softClipDb;
    public String softClipAlgo;
    public boolean hardClipOn;
    public double hardClipDb;
    public String hardClipCurve;

    /* Limit */
    public boolean limitOn;
    public double[] mbPushDb = new double[4];
    public double bbPushDb;

    /* Output */
    public boolean normalizerOn;
    public double normalizerTargetDbtp;
    public boolean osOn;
    public int osFactor = 4;

    /** Chain module order, by name: "EQ", "Dynamics", "Clip", "Limit". */
    public List<String> chainOrder = new ArrayList<>();

    /** One equalizer band. */
    public static final class BandPreset
    {
        public String type;
        public String channel;
        public String phase;
        public double frequency;
        public double gainDb;
        public double q;
        public int slope = 12;
        public boolean enabled = true;
        public boolean dynamic;
        public double threshold;
        public double aboveRatio;
        public double aboveAttackMs;
        public double aboveReleaseMs;
        public double aboveRangeDb;
        public boolean aboveBoost;
        public double belowRatio;
        public double belowAttackMs;
        public double belowReleaseMs;
        public double belowRangeDb;
        public boolean belowBoost;
    }
}
