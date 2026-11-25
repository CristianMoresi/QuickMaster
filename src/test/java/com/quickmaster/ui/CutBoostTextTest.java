package com.quickmaster.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the Cut/Boost control of the Auto EQ is written and read back. */
class CutBoostTextTest
{
    @Test
    @DisplayName("The control is written as the share lowered and the share raised")
    void written()
    {
        assertEquals("100/100", CutBoostText.text(0.0));
        assertEquals("100/0", CutBoostText.text(-1.0));
        assertEquals("0/100", CutBoostText.text(1.0));
        assertEquals("100/60", CutBoostText.text(-0.4));
        assertEquals("60/100", CutBoostText.text(0.4));
        assertEquals("100/75", CutBoostText.text(-0.25));
        assertEquals("100/100", CutBoostText.text(1e-16), "a rounding away from the middle is the middle");
    }

    @Test
    @DisplayName("What is written is read back as the same position")
    void readBack()
    {
        for (double lean = -1.0; lean <= 1.0; lean += 0.05)
        {
            double rounded = Math.rint(lean * 100.0) / 100.0;
            assertEquals(rounded, CutBoostText.value(CutBoostText.text(rounded)), 1e-12, "at " + rounded);
        }
    }

    @Test
    @DisplayName("Two shares are read with the larger as the whole; one number is the lean in percent")
    void typed()
    {
        assertEquals(-0.4, CutBoostText.value("100/60"), 1e-12);
        assertEquals(0.4, CutBoostText.value("60/100"), 1e-12);
        assertEquals(-0.5, CutBoostText.value("50/25"), 1e-12, "the larger of the two is the whole");
        assertEquals(0.5, CutBoostText.value(" 20 / 40 "), 1e-12);
        assertEquals(0.0, CutBoostText.value("80/80"), 1e-12);
        assertEquals(-1.0, CutBoostText.value("100/0"), 0.0);
        assertEquals(1.0, CutBoostText.value("0/100"), 0.0);
        assertEquals(-0.4, CutBoostText.value("-40"), 1e-12);
        assertEquals(0.25, CutBoostText.value("25 %"), 1e-12);
        assertEquals(1.0, CutBoostText.value("250"), 0.0);
        assertEquals(-1.0, CutBoostText.value("-999"), 0.0);
        for (String none : new String[] { null, "", "cut", "/", "100/", "/60", "0/0" })
            assertTrue(Double.isNaN(CutBoostText.value(none)), String.valueOf(none));
    }
}
