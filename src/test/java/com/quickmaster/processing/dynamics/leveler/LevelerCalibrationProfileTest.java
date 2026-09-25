package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ProtectionDecision;
import com.quickmaster.processing.dynamics.leveler.model.ProtectionFlags;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SegmentId;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisSnapshot;

class LevelerCalibrationProfileTest
{
    @Test
    void structuralV1AndComparisonV2AreTheOnlyFixedProfilesAndHaveNoPerTrackState()
    {
        Field[] fields = LevelerCalibrationProfile.class.getDeclaredFields();
        assertArrayEquals(new String[] { "V1", "V2", "profileId" },
                Arrays.stream(fields).map(Field::getName).toArray(String[]::new));
        assertEquals(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, fields[0].getModifiers());
        assertEquals(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, fields[1].getModifiers());
        assertEquals(LevelerCalibrationProfile.class, fields[0].getType());
        assertEquals(LevelerCalibrationProfile.class, fields[1].getType());
        assertEquals(Modifier.PRIVATE | Modifier.FINAL, fields[2].getModifiers());
        assertEquals(String.class, fields[2].getType());
        assertEquals("QM-LEVELER-V1", LevelerCalibrationProfile.V1.profileId());
        assertEquals("QM-LEVELER-V2", LevelerCalibrationProfile.V2.profileId());
        assertNotSame(LevelerCalibrationProfile.V1, LevelerCalibrationProfile.V2);
        assertTrue(Arrays.stream(LevelerCalibrationProfile.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
        assertFalse(Arrays.stream(LevelerCalibrationProfile.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("forSourceRate")
                        || method.getName().equals("sourceSampleRateHz")));
    }

    @Test
    void comparisonV2KeepsEveryStructuralV1EngineeringThreshold() throws Exception
    {
        String[] thresholdNames = { "structuralHopSec", "descriptorBins", "sketchBins",
                "maximumSegments", "noveltyMadMultiplier", "boundaryRadiusSec",
                "minimumBoundarySeparationSec", "durationRatioMinimum", "durationRatioMaximum",
                "minimumValidBins", "maximumInvalidRun", "groupH", "groupT", "groupA", "groupC",
                "groupSeparation", "pairH", "pairT", "pairA", "pairC", "pairMargin", "deadbandLu" };
        assertEquals(thresholdNames.length, Arrays.stream(LevelerCalibrationProfile.class.getDeclaredMethods())
                .filter(method -> method.getParameterCount() == 0 && method.getReturnType().isPrimitive()).count());
        for (String name : thresholdNames)
        {
            Method getter = LevelerCalibrationProfile.class.getDeclaredMethod(name);
            assertEquals(getter.invoke(LevelerCalibrationProfile.V1), getter.invoke(LevelerCalibrationProfile.V2), name);
        }
        for (int rate : new int[] { 44_100, 48_000, 96_000, Integer.MAX_VALUE })
        {
            long boundary = 3L * rate;
            for (long frames : new long[] { 0L, boundary - 1L, boundary, boundary + 1L, Long.MAX_VALUE })
                assertEquals(frames < boundary, LevelerCalibrationProfile.V2.isShortTransition(frames, rate));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 44_100, 48_000, 96_000, Integer.MAX_VALUE })
    void strictThreeSecondPredicateUsesLongArithmeticWithoutState(int sampleRate)
    {
        LevelerCalibrationProfile profile = LevelerCalibrationProfile.V1;
        long threshold = 3L * sampleRate;
        assertTrue(profile.isShortTransition(threshold - 1L, sampleRate));
        assertFalse(profile.isShortTransition(threshold, sampleRate));
        assertFalse(profile.isShortTransition(threshold + 1L, sampleRate));
        assertFalse(profile.isShortTransition(Long.MAX_VALUE, sampleRate));
        assertSame(profile, LevelerCalibrationProfile.V1);
        assertEquals("QM-LEVELER-V1", profile.profileId());
    }

    @ParameterizedTest
    @ValueSource(ints = { 44_100, 48_000, 96_000 })
    void engineUnionsTheTemporalBitAndKeepsClassifierBitsAndSegmentIdentity(int sampleRate)
    {
        for (int neighbor = -1; neighbor <= 1; neighbor++)
        {
            int frames = 3 * sampleRate + neighbor;
            ShadowAnalysisSnapshot snapshot = new LevelerAnalysisEngine().analyzeShadow(
                    new float[frames], new AudioFormat(sampleRate, 1, frames), new CancellationToken());
            assertNotNull(snapshot);
            assertEquals("QM-LEVELER-S001-COMPARISON-V2", snapshot.cache().algorithmId());
            assertEquals("QM-LEVELER-V2", snapshot.cache().profileId());
            assertNotNull(snapshot.cache().comparison());
            assertSame(snapshot.cache().format(), snapshot.cache().comparison().format());
            assertEquals(1, snapshot.cache().descriptors().size());
            SegmentDescriptor descriptor = snapshot.cache().descriptors().get(0);
            ProtectionDecision classified = new ProtectionClassifier().classify(
                    0, snapshot.cache().descriptors(), LevelerCalibrationProfile.V1);
            ProtectionDecision finalDecision = snapshot.cache().protections().get(0);
            long expected = classified.flags().reasonBits() | (neighbor < 0 ? 1L << 5 : 0L);
            assertEquals(7L, classified.flags().reasonBits(), "Silence and both edge bits are present");
            assertEquals(expected, finalDecision.flags().reasonBits());
            assertSame(descriptor.id(), classified.id());
            assertSame(classified.id(), finalDecision.id());
            assertTrue(finalDecision.isBlocked());
            assertEquals(0L, Double.doubleToRawLongBits(
                    snapshot.cache().referencePlan().targets().get(0).confidenceWeightedDb()));
        }
    }

    @Test
    void temporalUnionPreservesEveryCombinationOfClassifierBitsAndCannotUndoAVeto() throws Exception
    {
        Method union = LevelerAnalysisEngine.class.getDeclaredMethod("protectShortTransition",
                ProtectionDecision.class, long.class, int.class);
        union.setAccessible(true);
        for (int rate : new int[] { 44_100, 48_000, 96_000 })
        {
            for (long bits = 0L; bits <= 255L; bits++)
            {
                SegmentId id = new SegmentId((int) (bits % 64));
                ProtectionDecision original = new ProtectionDecision(id, new ProtectionFlags(bits));
                for (int neighbor = -1; neighbor <= 1; neighbor++)
                {
                    ProtectionDecision result = (ProtectionDecision) union.invoke(null, original,
                            3L * rate + neighbor, rate);
                    assertEquals(bits | (neighbor < 0 ? 1L << 5 : 0L), result.flags().reasonBits());
                    assertSame(id, result.id());
                    assertEquals(bits, original.flags().reasonBits());
                    if (neighbor >= 0) assertSame(original, result);
                    if (bits != 0L || neighbor < 0) assertTrue(result.isBlocked());
                }
            }
        }
    }
}
