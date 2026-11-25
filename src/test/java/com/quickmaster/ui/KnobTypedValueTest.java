package com.quickmaster.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a knob shows in its editor and what it reads back from it. */
class KnobTypedValueTest
{
    @Test
    @DisplayName("A share shown in percent is typed in percent")
    void percentIsTypedAsPercent()
    {
        assertEquals(0.70, Knob.typedValue("70", 100.0), 0.0);
        assertEquals(0.25, Knob.typedValue(" 25 % ", 100.0), 0.0);
        assertEquals(0.125, Knob.typedValue("12.5", 100.0), 0.0);
        assertEquals(1.0, Knob.typedValue("100%", 100.0), 0.0);
        assertEquals(2.5, Knob.typedValue("250", 100.0), 0.0, "clamping to the range is the knob's own");
        assertEquals("70", Knob.typedText(0.70, 100.0));
        assertEquals("7", Knob.typedText(0.07, 100.0), "a product a rounding away from a whole number shows that number");
        assertEquals("12.50", Knob.typedText(0.125, 100.0));
        assertEquals("0", Knob.typedText(0.0, 100.0));
        for (int percent = 0; percent <= 100; percent++)
            assertEquals(percent / 100.0, Knob.typedValue(Knob.typedText(percent / 100.0, 100.0), 100.0), 0.0,
                    percent + " % comes back as it was shown");
    }

    @Test
    @DisplayName("Without a typed scale the number typed is the value")
    void plainValues()
    {
        assertEquals(1000.0, Knob.typedValue("1000", 1.0), 0.0);
        assertEquals(1500.0, Knob.typedValue("1.5 kHz", 1.0), 0.0);
        assertEquals(1000.0, Knob.typedValue("1k", 1.0), 0.0);
        assertEquals(-3.0, Knob.typedValue("-3 dB", 1.0), 0.0);
        assertEquals(-1.5, Knob.typedValue("-1.5", 1.0), 0.0);
        assertEquals("1000", Knob.typedText(1000.0, 1.0));
        assertEquals("-3", Knob.typedText(-3.0, 1.0));
        assertEquals("0.71", Knob.typedText(0.707, 1.0));
    }

    @Test
    @DisplayName("Text with no number in it is not a value")
    void noNumber()
    {
        assertTrue(Double.isNaN(Knob.typedValue("", 100.0)));
        assertTrue(Double.isNaN(Knob.typedValue("abc", 1.0)));
        assertTrue(Double.isNaN(Knob.typedValue(null, 1.0)));
    }
}
