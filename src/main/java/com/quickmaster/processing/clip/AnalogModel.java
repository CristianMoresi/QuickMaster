package com.quickmaster.processing.clip;

/**
 * One of DSPark's analog circuits as the saturation stage drives it: a drive
 * into the circuit, which keeps its level near its alignment, and a delay the
 * stage takes back.
 */
interface AnalogModel
{
    /** Prepares for a rate, a channel count and a largest block, from rest. */
    void prepare(double sampleRate, int channels, int maxBlock);

    /** Back to rest, the settings kept. */
    void reset();

    /**
     * The drive into the circuit in decibels, within {@link #minDriveDb()} and
     * {@link #maxDriveDb()}. Set before {@link #prepare}: the circuit then
     * starts calibrated at that drive, with no ramp toward it.
     */
    void setDriveDb(double db);

    double minDriveDb();

    double maxDriveDb();

    /** Samples the output lags the input at the prepared rate. */
    int latency();

    /** Processes planar blocks in place. */
    void process(double[][] channels, int frames);

    /** The programme loudness, in LUFS, the circuit is aligned to: its 0 VU. */
    double alignmentLufs();

    /**
     * Seconds of programme after which a circuit started from rest gives the
     * same output as one that has been running: its couplings, supply and
     * magnetic memory settled.
     */
    default double settleSeconds() { return 1.5; }
}
