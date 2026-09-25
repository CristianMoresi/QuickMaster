package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalTemporalTest
{
    @Test
    void originalGapsUseGeometricQuartilesIndependentTimesAndLagFive()
    {
        double[] values = new double[40];
        BitSet valid = new BitSet(69); valid.set(0, 40);
        for (int i = 0; i < 40; i++) values[i] = -30d + i / 8d;
        valid.clear(3, 20); Arrays.fill(values, 3, 20, 0d);
        SegmentDescriptor descriptor = descriptor(values, valid);
        assertTrue(descriptor.regionalLoudness().present());
        assertEquals(4.125d, descriptor.loudnessDeltaLu(), 1e-12);
        assertEquals(1.25d, descriptor.loudnessSlopeLuPerSec(), 1e-12);
        assertEquals(1d, descriptor.loudnessConsistency(), 0d);
    }

    @Test
    void endpointOutliersDoNotReplaceTheRobustDelta()
    {
        double[] values = new double[40]; Arrays.fill(values, -30d);
        values[0] = -10d; values[39] = -50d;
        BitSet valid = new BitSet(69); valid.set(0, 40);
        SegmentDescriptor descriptor = descriptor(values, valid);
        assertEquals(0d, descriptor.loudnessDeltaLu(), 0d);
        assertEquals(0d, descriptor.loudnessSlopeLuPerSec(), 0d);
        assertEquals(0d, descriptor.loudnessConsistency(), 0d);
    }

    @Test
    void lagFiveUsesEveryPhaseWithoutBlockAveraging()
    {
        double[] values = new double[40];
        for (int i = 0; i < 40; i++) values[i] = -30d + i / 8d
                + (i % 5 == 0 ? (i / 5 % 2 == 0 ? 1d : -1d) : 0d);
        BitSet valid = new BitSet(69); valid.set(0, 40);
        SegmentDescriptor descriptor = descriptor(values, valid);
        assertEquals(3.75d, descriptor.loudnessDeltaLu(), 1e-12);
        assertEquals(1.25d, descriptor.loudnessSlopeLuPerSec(), 1e-12);
        assertEquals(31d / 35d, descriptor.loudnessConsistency(), 1e-12);
    }

    @Test
    void missingContiguousLagAndMissingQuartileAreAbsentWhileFlatEvidenceIsPresent()
    {
        for (int mode = 0; mode < 3; mode++)
        {
            double[] values = new double[40]; Arrays.fill(values, -30d);
            BitSet valid = new BitSet(69); valid.set(0, 40);
            if (mode == 1) for (int i = 5; i < 40; i += 6) { valid.clear(i); values[i] = 0d; }
            if (mode == 2) { valid.clear(0, 10); Arrays.fill(values, 0, 10, 0d); }
            SegmentDescriptor descriptor = descriptor(values, valid);
            assertEquals(mode == 0, descriptor.regionalLoudness().present(), "mode " + mode);
            assertEquals(0L, Double.doubleToRawLongBits(descriptor.loudnessDeltaLu()));
            assertEquals(0L, Double.doubleToRawLongBits(descriptor.loudnessSlopeLuPerSec()));
            assertEquals(0L, Double.doubleToRawLongBits(descriptor.loudnessConsistency()));
            if (mode != 0) assertTrue(new ProtectionClassifier().classify(0,
                    new FrozenList<SegmentDescriptor>(new Object[] { descriptor }),
                    LevelerCalibrationProfile.V1).flags().containsBit(0));
        }
    }

    @Test
    void activityUsesTheActualPartialTailCenter()
    {
        long frames = 48_001L;
        FeatureTimeline features = MusicalContextFixtures.features(24_000L, frames,
                i -> MusicalModelFixtures.bin(0, 0, (i + 1) / 4d));
        SegmentDescriptor descriptor = MusicalContextFixtures.build(48_000, frames, features,
                MusicalContextFixtures.loudness(48_000, frames, new double[0], new BitSet()),
                0L, frames).get(0);
        assertEquals(24_000d / 36_001d, descriptor.activitySlopePerSec(), 1e-12);
        assertEquals(.5d, descriptor.foregroundRatio(), 0d);
        assertEquals(.5d, descriptor.activitySpread(), 0d);
        assertFalse(descriptor.regionalLoudness().present());
    }

    @Test
    void temporalMedianDoesNotFollowTheObservationHoldingTheMedianValue()
    {
        double[] values = new double[40]; Arrays.fill(values, -25);
        for (int i = 0; i < 10; i++) { values[i] = -30 + i; values[i + 30] = -20 + i; }
        double swap = values[0]; values[0] = values[4]; values[4] = swap;
        swap = values[34]; values[34] = values[39]; values[39] = swap;
        BitSet valid = new BitSet(); valid.set(0, 40);
        SegmentDescriptor descriptor = descriptor(values, valid);
        assertTrue(descriptor.regionalLoudness().present());
        assertEquals(10d, descriptor.loudnessDeltaLu(), 0d);
        assertEquals(10d / 3d, descriptor.loudnessSlopeLuPerSec(), 1e-12);
        assertNotEquals(10d / 3.9d, descriptor.loudnessSlopeLuPerSec(), 1e-12);
    }

    private static SegmentDescriptor descriptor(double[] values, BitSet valid)
    {
        long frames = 331_200L;
        FeatureTimeline features = MusicalContextFixtures.features(24_000L, frames,
                i -> MusicalContextFixtures.base());
        return MusicalContextFixtures.build(48_000, frames, features,
                MusicalContextFixtures.loudness(48_000, frames, values, valid), 0L, frames).get(0);
    }
}
