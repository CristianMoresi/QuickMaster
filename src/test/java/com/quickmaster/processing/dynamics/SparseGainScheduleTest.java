package com.quickmaster.processing.dynamics;

import com.quickmaster.processing.dynamics.leveler.model.GainPiece;
import com.quickmaster.processing.dynamics.leveler.model.GainPieceShape;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent source-clock and ownership checks for the structural renderer's schedule. */
class SparseGainScheduleTest {
    private static GainPiece piece(double start, double end, double first, double last) {
        return new GainPiece(start, end, first, last,
                first == last ? GainPieceShape.HOLD : GainPieceShape.SMOOTHSTEP);
    }

    @Test void gapAndOutsideDomainAreCanonicalUnit() {
        var schedule = new SparseGainSchedule(48_000, 400,
                new GainPiece[]{piece(100, 200, -2, -2), piece(250, 300, 1, 1)});
        for (double position : new double[]{Double.NEGATIVE_INFINITY, -10, -0.0,
                0, 99.5, 200, 249.999, 300, 400, 401, Double.POSITIVE_INFINITY, Double.NaN}) {
            assertEquals(0L, Double.doubleToRawLongBits(schedule.gainDbAt(position)), "position " + position);
        }
        assertEquals(-2, schedule.gainDbAt(100));
        assertEquals(-2, schedule.gainDbAt(199.999));
        assertEquals(1, schedule.gainDbAt(250));
        assertEquals(1, schedule.gainDbAt(299.999));
    }

    @Test void fractionalSmoothstepUsesUnroundedSourceClock() {
        var schedule = new SparseGainSchedule(48_000, 20,
                new GainPiece[]{piece(.25, 4.25, 0, -2), piece(4.25, 8.25, -2, 0)});
        for (double position : new double[]{.25, .5, 1.25, 2.25, 3.25, 4, 4.25, 6.25, 8.25}) {
            double expected = 0;
            if (position >= .25 && position < 4.25) {
                double u = (position - .25) / 4;
                expected = -2 * (3 * u * u - 2 * u * u * u);
            } else if (position >= 4.25 && position < 8.25) {
                double u = (position - 4.25) / 4;
                expected = -2 + 2 * (3 * u * u - 2 * u * u * u);
            }
            // The contract canonicalizes every zero-dB result, including -2 * +0.
            if (expected == 0) expected = 0;
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(schedule.gainDbAt(position)));
        }
    }

    @Test void binaryPositionAndSequentialCursorAgreeAcrossSeeksAndGenerations() {
        var schedule = new SparseGainSchedule(48_000, 20,
                new GainPiece[]{piece(1, 4, 0, -2), piece(4, 8, -2, -2), piece(10, 15, 1, 1)});
        var cursor = new SparseGainCursor();
        for (long generation : new long[]{1, 2, 2, 3}) {
            for (long seek : new long[]{-4, 0, 20, 10, 1, 7, 0}) {
                cursor.invalidate(seek);
                cursor.align(generation, seek, schedule, seek / 2.0);
                for (int offset = 0; offset < 30; offset++) {
                    double position = (seek + offset) / 2.0;
                    assertEquals(Double.doubleToRawLongBits(schedule.gainDbAt(position)),
                            Double.doubleToRawLongBits(cursor.gainDbAt(schedule, position)));
                }
                cursor.advanceTo(seek + 30);
            }
        }
    }

    @Test void arrayOwnershipAndPublicCapacityAreEnforced() {
        GainPiece first = piece(0, 2, 1, 1);
        GainPiece[] input = {first};
        var schedule = new SparseGainSchedule(48_000, 10, input);
        input[0] = piece(0, 2, -1, -1);
        assertSame(first, schedule.pieceAt(0));
        assertEquals(1, schedule.gainDbAt(1));
        assertEquals(GainDomain.SPARSE_DB, schedule.domain());
        assertFalse(schedule.isUnit());
        assertTrue(SparseGainSchedule.unit(48_000, 10).isUnit());
        GainPiece[] maximum = new GainPiece[258];
        for (int i = 0; i < maximum.length; i++) maximum[i] = piece(i, i + 1, 1, 1);
        assertEquals(258, new SparseGainSchedule(48_000, 300, maximum).pieceCount());
        assertThrows(IllegalArgumentException.class,
                () -> new SparseGainSchedule(48_000, 300, new GainPiece[259]));
    }

    @Test void invalidFormatsOverlapAndExtentAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SparseGainSchedule.unit(0, 10));
        assertThrows(IllegalArgumentException.class, () -> SparseGainSchedule.unit(48_000, 0));
        assertThrows(IllegalArgumentException.class, () -> new SparseGainSchedule(48_000, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SparseGainSchedule(48_000, 10, new GainPiece[]{null}));
        assertThrows(IllegalArgumentException.class,
                () -> new SparseGainSchedule(48_000, 10, new GainPiece[]{piece(0, 4, 1, 1), piece(3, 5, 1, 1)}));
        assertThrows(IllegalArgumentException.class,
                () -> new SparseGainSchedule(48_000, 10, new GainPiece[]{piece(9, 11, 1, 1)}));
    }
}
