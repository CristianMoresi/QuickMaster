package com.quickmaster.processing.clip;

/**
 * What the saturation stage lowers the peaks with. The three curves are
 * memoryless and solved in closed form; the four models are DSPark's analog
 * saturation, rendered over the whole song with their drive solved for the
 * reduction asked, measured against their own linear response, and their
 * loudness kept.
 */
public enum SaturationType
{
    /** DSPark's sine knee. */
    SINE("Sine", true),
    /** The hyperbolic tangent. */
    TANH("Tanh", true),
    /** A linear body that bends at the golden ratio of the ceiling. */
    GOLDEN("Golden knee", true),
    /** A 12AX7 stage solved as a circuit, with the sag of its supply and a neutral tone stack. */
    TUBE_PREAMP("Tube preamp", false),
    /** The Saturation effect's core saturation: the low end saturates before the top. */
    TRANSFORMER("Transformer", false),
    /** The Saturation effect's tube, tape and transformer in series. */
    MULTI_STAGE("Multi-stage", false),
    /** A reel-to-reel machine; its alignment is a {@link TapePreset}. */
    TAPE("Tape machine", false);

    private final String label;
    private final boolean curve;

    SaturationType(String label, boolean curve)
    {
        this.label = label;
        this.curve = curve;
    }

    /** The name shown in the interface. */
    public String label() { return label; }

    /** True for the memoryless curves, false for the analog models. */
    public boolean isCurve() { return curve; }

    /**
     * The type a preset names: {@code saturationType} when present, otherwise
     * the curve its {@code softClipAlgo} meant (TUBE was the sine knee, TAPE
     * the tanh and TRANSFORMER the golden knee).
     */
    public static SaturationType fromPreset(String saturationType, String softClipAlgo)
    {
        if (saturationType != null)
            for (SaturationType t : values()) if (t.name().equals(saturationType)) return t;
        if ("TAPE".equals(softClipAlgo)) return TANH;
        if ("TRANSFORMER".equals(softClipAlgo)) return GOLDEN;
        return SINE;
    }

    /** The {@code softClipAlgo} an older QuickMaster reads: the nearest curve. */
    public String legacyAlgorithm()
    {
        return switch (this)
        {
            case TANH -> "TAPE";
            case GOLDEN -> "TRANSFORMER";
            default -> "TUBE";
        };
    }
}
