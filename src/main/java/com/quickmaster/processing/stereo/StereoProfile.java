package com.quickmaster.processing.stereo;

/** Equal-recording mean energy shares from five references/family, 2026-09-28.
 * Provenance: docs/research/stereo-study-20260928/energy-profiles.json.
 * These are contextual references, not perceptual optima or safety limits.
 */
public enum StereoProfile {
    AUTO("Auto (This Track)", Double.NaN),
    ELECTRONIC("Electronic", .11742653694439928), POP("Pop", .10152972980950033),
    ROCK("Rock", .1465344341212382), METAL("Metal", .16277752172426688),
    HIP_HOP("Hip Hop", .020251819767419257), RNB("R&B / Soul / Funk", .11823388343293233),
    ACOUSTIC("Acoustic / Folk / Country", .10488657531289694),
    JAZZ("Jazz / Blues", .14148676968930962),
    ORCHESTRAL("Orchestral / Cinematic", .35095834281904137), LATIN("Latin", .09024819745810443),
    REFERENCE("Reference / Custom", Double.NaN);
    private final String label;
    private final double sideShare;
    StereoProfile(String label, double sideShare) { this.label = label; this.sideShare = sideShare; }
    public double sideShare() { return sideShare; }
    @Override public String toString() { return label; }
}
