package com.dspark.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmoothedValueTimingTest {
    @Test void linearDurationIsIndependentOfParameterUnitsAndDirection() {
        for (double start : new double[]{0, 20000, -6})
            for (double target : new double[]{.25, 1000, -12}) {
                SmoothedValue value = new SmoothedValue();
                value.prepare(48000, 10);
                value.setSmoothingType(SmoothedValue.Type.LINEAR);
                value.reset(start); value.setTargetValue(target);
                for (int i = 1; i <= 480; i++) {
                    value.setTargetValue(target); // automation must not restart the ramp
                    assertEquals(start + (target - start) * i / 480, value.getNextValue(), 1e-7);
                }
                assertFalse(value.isSmoothing());
            }
    }
    @Test void retargetAndPrepareUseRemainingDistanceWithFullNewDuration() {
        SmoothedValue value = new SmoothedValue();
        value.prepare(1000, 20); value.setSmoothingType(SmoothedValue.Type.LINEAR);
        value.setTargetValue(1000);
        for (int i = 0; i < 10; i++) value.getNextValue();
        assertEquals(500, value.getCurrentValue(), 1e-10);
        value.setTargetValue(100); value.prepare(1000, 10);
        for (int i = 0; i < 9; i++) value.getNextValue();
        assertEquals(140, value.getCurrentValue(), 1e-10);
        assertEquals(100, value.getNextValue(), 0);
    }
    @Test void zeroNegativeAndNanTimeAreInstantForEveryCurve() {
        for (var type : SmoothedValue.Type.values())
            for (double time : new double[]{0, -1, Double.NaN}) {
                SmoothedValue value = new SmoothedValue();
                value.prepare(48000, time); value.setSmoothingType(type); value.setTargetValue(1000);
                assertEquals(1000, value.getNextValue(), 0);
            }
    }
    @Test void defaultConstructionActuallySmooths() {
        SmoothedValue value = new SmoothedValue(); value.setTargetValue(1);
        assertTrue(value.getNextValue() > 0);
        assertTrue(value.getCurrentValue() < .01);
    }
}
