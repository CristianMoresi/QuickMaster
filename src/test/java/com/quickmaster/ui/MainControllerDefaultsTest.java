package com.quickmaster.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainControllerDefaultsTest
{
    @Test
    @DisplayName("Exports default to 48 kHz and 24-bit")
    void deliveryDefaults()
    {
        assertEquals(48_000, MainController.DEFAULT_EXPORT_SAMPLE_RATE);
        assertEquals(24, MainController.DEFAULT_EXPORT_BIT_DEPTH);
    }
}
