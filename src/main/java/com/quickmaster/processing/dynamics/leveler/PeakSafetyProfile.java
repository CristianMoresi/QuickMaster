package com.quickmaster.processing.dynamics.leveler;

/** Metadata only until an independently verified tile optimization is enabled. */
public final class PeakSafetyProfile {
    public static final String KERNEL_ID="BS1770-ANNEX2-4X-12TAP-FINITE6-V1";
    private PeakSafetyProfile() { }
    public static boolean tileOptimizationEnabled(){return false;}
}
