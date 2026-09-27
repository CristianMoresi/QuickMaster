package com.dspark.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DcBlocker}. */
class DcBlockerTest
{
    @Test
    @DisplayName("A constant (DC) signal decays to zero")
    void removesDc()
    {
        DcBlocker dc = new DcBlocker();
        dc.prepare(48000);
        float[] buf = new float[48000];
        java.util.Arrays.fill(buf, 1.0f);
        dc.process(buf, 1);
        // After ~1 s the 5 Hz high-pass has fully removed the DC step.
        assertTrue(Math.abs(buf[buf.length - 1]) < 0.01,
                "DC not removed, tail = " + buf[buf.length - 1]);
    }

    @Test
    @DisplayName("Disabled blocker leaves the buffer unchanged")
    void disabledPassthrough()
    {
        DcBlocker dc = new DcBlocker();
        dc.prepare(48000);
        dc.setEnabled(false);
        float[] buf = { 1f, 1f, 1f, 1f };
        dc.process(buf, 1);
        for (float v : buf) assertEquals(1f, v, 0.0f);
    }
}
