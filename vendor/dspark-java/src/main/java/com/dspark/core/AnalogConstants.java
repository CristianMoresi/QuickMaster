package com.dspark.core;

/**
 * Reference constants from analog-hardware research and measurement.
 * <p>
 * A curated collection of physical constants, measured noise floors,
 * component tolerances and classic-equipment behaviour figures. They document
 * the real-world values that the framework's analog-modelling defaults are
 * derived from, and are available for building other component models. The
 * analog noise and drift generators ({@link AnalogRandom}) take their
 * component defaults from {@link VariationPercent}.
 * <p>
 * None of these constants is required by the processing paths: this is pure
 * reference data. The tables are returned as fresh copies.
 */
public final class AnalogConstants
{
    private AnalogConstants() { }

    /** Noise constants. */
    public static final class NoiseConstants
    {
        private NoiseConstants() { }

        /** Thermal (Johnson-Nyquist) noise. */
        public static final class Thermal
        {
            private Thermal() { }

            /** J/K (exact, SI 2019). */
            public static final double BOLTZMANN_CONSTANT = 1.380649e-23;
            /** 20 degrees C. */
            public static final double ROOM_TEMP_KELVIN = 293.15;
            /** Ohms. */
            public static final double TYPICAL_RESISTANCE = 1000.0;
            /** Hz. */
            public static final double NOISE_BANDWIDTH = 20000.0;
        }

        /** Shot noise. */
        public static final class Shot
        {
            private Shot() { }

            /** Coulombs (exact, SI 2019). */
            public static final double ELEMENTARY_CHARGE = 1.602176634e-19;
            /** Amperes. */
            public static final double TYPICAL_DC_CURRENT = 1.0e-3;
            /** Hz. */
            public static final double AUDIO_BANDWIDTH = 20000.0;
        }

        /** Flicker (1/f^alpha) noise. */
        public static final class Flicker
        {
            private Flicker() { }

            /** 1/f^alpha exponent of pink noise. */
            public static final double ALPHA_PINK = 1.0;
            /** 1/f^alpha exponent of brown noise. */
            public static final double ALPHA_BROWN = 2.0;
            public static final double SPECTRAL_SLOPE_DB_PER_OCTAVE_PINK = -3.0;
            public static final double SPECTRAL_SLOPE_DB_PER_OCTAVE_BROWN = -6.0;
        }

        /** Measured noise floors of classic equipment. */
        public static final class PracticalNoiseFloors
        {
            private PracticalNoiseFloors() { }

            /** EIN, 200 ohm source, max gain. */
            public static final double NEVE_1073_EIN_DBU = -125.0;
            /** Line-amp noise. */
            public static final double NEVE_1073_NOISE_LINE_DBU = -83.0;
            /** dBu, EIN at max gain. */
            public static final double API_512C_EIN = -129.0;
            /** Spec: noise 75 dB below +10 dBm. */
            public static final double LA_2A_NOISE_DB_BELOW_10DBM = -75.0;
            public static final double AMPEX_351_SNR_DB = 55.0;
            public static final double STUDER_C37_SNR_DB = 75.0;
            public static final double TAPE_NOISE_OFFSET_DB = 8.0;

            private static final double[] HUM_FREQUENCIES_HZ = { 50.0, 60.0, 100.0, 120.0, 180.0, 240.0, 300.0 };
            private static final double[] HUM_AMPLITUDES_DB = { -60.0, -55.0, -65.0, -70.0, -75.0, -80.0, -85.0 };

            /** @return the mains hum frequencies, in Hz (7 values, a copy) */
            public static double[] humFrequenciesHz() { return HUM_FREQUENCIES_HZ.clone(); }

            /** @return the hum levels matching {@link #humFrequenciesHz()}, in dB (a copy) */
            public static double[] humAmplitudesDb() { return HUM_AMPLITUDES_DB.clone(); }
        }
    }

    /** Saturation constants. */
    public static final class SaturationConstants
    {
        private SaturationConstants() { }

        public static final class General
        {
            private General() { }

            public static final double DRIVE_THRESHOLD_DB = -6.0;
            public static final double MAX_THD_PERCENTAGE = 0.07;
        }

        public static final class Tube
        {
            private Tube() { }

            public static final double EVEN_HARMONIC_DOMINANCE_RATIO = 0.7;

            private static final double[] TRANSFER_FUNCTION_COEFFS = { 1.0, 0.5, 0.1 };

            /** @return the transfer-function coefficients (3 values, a copy) */
            public static double[] transferFunctionCoeffs() { return TRANSFER_FUNCTION_COEFFS.clone(); }
        }

        public static final class Tape
        {
            private Tape() { }

            public static final double ODD_HARMONIC_DOMINANCE_RATIO = 0.6;
            public static final double SATURATION_THRESHOLD_DB = 0.0;
            public static final double HIGH_END_ROLL_OFF_HZ = 10000.0;
            public static final double LOW_END_BOOST_DB = 2.0;
        }
    }

    /** Component tolerances and ageing. */
    public static final class ComponentConstants
    {
        private ComponentConstants() { }

        public static final class Resistors
        {
            private Resistors() { }

            public static final double TOLERANCE_PERCENTAGE_1 = 1.0;
            public static final double TOLERANCE_PERCENTAGE_5 = 5.0;
            public static final double TOLERANCE_PERCENTAGE_10 = 10.0;
            public static final double AGING_DRIFT_RATE_PERCENTAGE_PER_YEAR = 0.5;
        }

        public static final class Capacitors
        {
            private Capacitors() { }

            public static final double AGING_RATE_X7R_PERCENT_PER_DECADE_HOUR = -2.5;
            public static final double AGING_RATE_Y5V_PERCENT_PER_DECADE_HOUR = -7.0;
            public static final double CURIE_POINT_TEMPERATURE_CELSIUS = 125.0;
        }

        public static final class TransistorsValves
        {
            private TransistorsValves() { }

            public static final double GAIN_VARIATION_PERCENTAGE = 5.0;
            public static final double TEMPERATURE_SENSITIVITY_FACTOR = 0.1;
            public static final double AGING_FACTOR_GAIN_DRIFT = 0.2;
        }

        public static final class Transformers
        {
            private Transformers() { }

            public static final double SATURATION_THRESHOLD_FLUX_DENSITY_TESLAS = 1.5;
            public static final double HIGH_FREQUENCY_ROLL_OFF_HZ = 10000.0;
        }
    }

    /** Behaviour figures of classic equipment. */
    public static final class EquipmentConstants
    {
        private EquipmentConstants() { }

        public static final class TapeMachines
        {
            private TapeMachines() { }

            public static final double WOW_DEPTH_PERCENTAGE = 0.1;
            public static final double FLUTTER_DEPTH_PERCENTAGE = 0.15;
            public static final double TAPE_HISS_SNR_DB = 55.0;
            public static final double TAPE_SATURATION_THRESHOLD_DB = 0.0;
        }

        public static final class Preamplifiers
        {
            private Preamplifiers() { }

            public static final double MAX_MIC_GAIN_DB = 80.0;
            public static final double EIN_AT_MAX_GAIN_DBU = -125.0;
            public static final double SATURATION_THRESHOLD_DB = -6.0;
            public static final double THD_PERCENTAGE = 0.07;
        }

        public static final class Compressors
        {
            private Compressors() { }

            public static final double MAX_GAIN_REDUCTION_DB = 40.0;
            public static final double THD_AT_10DBM_PERCENT = 0.35;
            public static final double FIXED_ATTACK_TIME_SECONDS = 0.01;
            public static final double FIXED_RELEASE_TIME_SECONDS = 0.5;
        }

        public static final class Equalizers
        {
            private Equalizers() { }

            private static final double[] FREQUENCY_POINTS_LOW_HZ = { 20.0, 30.0, 60.0, 100.0 };
            private static final double[] FREQUENCY_POINTS_HIGH_HZ = { 3000.0, 5000.0, 10000.0 };

            public static final double BANDWIDTH_CONTROL_RANGE = 1.0;

            /** @return the low-shelf frequency points, in Hz (4 values, a copy) */
            public static double[] frequencyPointsLowHz() { return FREQUENCY_POINTS_LOW_HZ.clone(); }

            /** @return the high-shelf frequency points, in Hz (3 values, a copy) */
            public static double[] frequencyPointsHighHz() { return FREQUENCY_POINTS_HIGH_HZ.clone(); }
        }

        public static final class Consoles
        {
            private Consoles() { }

            public static final double CROSSTALK_LEVEL_DB_AT_1KHZ = -70.0;
            public static final double CROSSTALK_FREQUENCY_SLOPE_DB_PER_OCTAVE = 6.0;
            public static final double VINTAGE_CROSSTALK_MULTIPLIER = 1.5;
            public static final double MODERN_CROSSTALK_MULTIPLIER = 0.5;
        }
    }

    /** Component variation, in percent, behind the generators' analog defaults (single precision). */
    public static final class VariationPercent
    {
        private VariationPercent() { }

        public static final float TAPE_MACHINE = 0.1f;
        public static final float VACUUM_TUBE = 5.0f;
        public static final float TRANSISTOR = 3.0f;
        public static final float COMPRESSOR = 1.5f;
        public static final float EQUALIZER = 1.0f;
    }
}
