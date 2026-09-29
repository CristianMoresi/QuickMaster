package com.quickmaster.processing.stereo;

/** Immutable, validated control state. Percentages in the UI map to fractions here. */
public record StereoImageSettings(boolean generation, double generationAmount,
        boolean generateLowFrequencies, boolean harmonics, boolean leveling,
        double levelingAmount, boolean guard, double guardMarginDb,
        StereoProfile profile, double referenceSideShare, Double generatedLowCutHz,
        double sideGainDb) {
    public static final StereoImageSettings DEFAULT = new StereoImageSettings(
            false, .25, true, true, false, .35, false, 3, StereoProfile.AUTO, .12, 0.0, 0);

    /** Legacy API/JSON: unchecked bass generation used the 150–200 Hz FIR. */
    public StereoImageSettings(boolean generation, double generationAmount,
            boolean generateLowFrequencies, boolean harmonics, boolean leveling,
            double levelingAmount, boolean guard, double guardMarginDb,
            StereoProfile profile, double referenceSideShare) {
        this(generation, generationAmount, generateLowFrequencies, harmonics, leveling,
                levelingAmount, guard, guardMarginDb, profile, referenceSideShare, null, 0);
    }

    public StereoImageSettings(boolean generation, double generationAmount, double lowCutHz,
            boolean leveling, double levelingAmount, boolean guard, double guardMarginDb,
            StereoProfile profile, double referenceSideShare, double sideGainDb) {
        this(generation, generationAmount, lowCutHz == 0, true, leveling, levelingAmount,
                guard, guardMarginDb, profile, referenceSideShare, lowCutHz, sideGainDb);
    }

    public StereoImageSettings {
        // Retain the legacy JSON field, but generation now always includes its
        // automatic harmonic layer. An old preset cannot silently disable it.
        harmonics = true;
        if (generatedLowCutHz == null) generatedLowCutHz = generateLowFrequencies ? 0.0 : 175.0;
        range(generatedLowCutHz, 0, 5000);
        if (generatedLowCutHz > 0 && generatedLowCutHz < 20)
            throw new IllegalArgumentException("Generated low cut must be Off or at least 20 Hz");
        generateLowFrequencies = generatedLowCutHz == 0;
        range(sideGainDb, -12, 12);
        range(generationAmount, 0, 1); range(levelingAmount, 0, 1);
        range(guardMarginDb, 0, 12); range(referenceSideShare, .001, .49);
        if (profile == null) throw new IllegalArgumentException("Missing stereo profile");
    }
    private static void range(double x, double lo, double hi) {
        if (!Double.isFinite(x) || x < lo || x > hi)
            throw new IllegalArgumentException("Stereo parameter outside [" + lo + ", " + hi + "]");
    }
    public boolean generates() { return generation && generationAmount > 0; }
    public boolean regulates() { return leveling && levelingAmount > 0; }
    public boolean active() { return generates() || regulates() || guard || sideGainDb != 0; }
}
