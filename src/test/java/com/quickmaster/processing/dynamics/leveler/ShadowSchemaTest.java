package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisCache;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisSnapshot;
import com.quickmaster.processing.dynamics.leveler.model.ShadowMemoryCounters;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityScore;

class ShadowSchemaTest
{
    @Test
    void rootAndSimilarityFieldsMatchTheLiteralM004Schema()
    {
        assertFieldNames(ShadowAnalysisSnapshot.class, "result", "cache", "diagnostics");
        assertFieldNames(SimilarityScore.class, "h", "t", "a", "c", "chromaRotation",
                "validBins", "rejectionReason");
        for (Field field : ShadowAnalysisSnapshot.class.getDeclaredFields())
        {
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }
        SimilarityScore score = new SimilarityScore(1.0d, 1.0d, 1.0d, 1.0d,
                0, 32, SimilarityRejectionReason.NONE);
        assertEquals(1.0d, score.c(), 0.0d);
    }

    @Test
    void memoryCountersHaveExactlyEighteenFinalLongs()
    {
        Field[] fields = ShadowMemoryCounters.class.getDeclaredFields();
        assertEquals(18, fields.length);
        for (Field field : fields)
        {
            assertEquals(long.class, field.getType());
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }
    }

    @Test
    void frozenListHasOneExactBackingAndDoesNotExposeIt()
    {
        assertFieldNames(FrozenList.class, "elements");
        FrozenList<String> list = new FrozenList<String>(new Object[] { "A", "B" });
        Object[] copy = list.toArray();
        copy[0] = "changed";
        assertEquals("A", list.get(0));
    }

    @Test
    void futureM005CacheAndResultTypesDoNotExist()
    {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.quickmaster.processing.dynamics.leveler.model.LevelerAnalysisResult"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.quickmaster.processing.dynamics.leveler.model.LevelerAnalysisCache"));
        for (Field field : ShadowAnalysisCache.class.getDeclaredFields())
        {
            String type = field.getType().getName();
            assertFalse(type.contains("PublishedGain"));
            assertFalse(type.contains("Dense"));
            assertFalse(type.contains("Control"));
            assertFalse(type.contains("PeakSafety"));
        }
    }

    private static void assertFieldNames(Class<?> type, String... expected)
    {
        String[] actual = Arrays.stream(type.getDeclaredFields()).map(Field::getName).toArray(String[]::new);
        assertArrayEquals(expected, actual, type.getName());
    }
}
