package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import com.quickmaster.processing.dynamics.leveler.model.ChannelLayout;

class LoudnessCorePrecisionTest
{
    @Test
    void quietMaterialAfterLoudPrefixIsMeasuredFromItsActualWindowNotResidualRoundoff() throws Exception
    {
        for (int variant = 0; variant < 32; variant++)
        {
        LoudnessCore core = new LoudnessCore(48_000, ChannelLayout.MONO_MAIN);
        float[] frame = new float[1];
        for (int i = 0; i < 48_000; i++)
        {
            double amplitude = i < 4_800 ? 0.5d : 1.0e-10d;
            frame[0] = (float) (amplitude * StrictMath.sin(2.0d * StrictMath.PI * 997.0d * i / 48_000 + variant * 0.173d));
            core.acceptFrame(frame, 0);
        }
        // Independent window arithmetic over the actual filtered sample powers:
        // this neither substitutes a test-only filter nor assumes any quiet floor.
        Field ringField = LoudnessCore.class.getDeclaredField("weightedPowerRing");
        Field cursorField = LoudnessCore.class.getDeclaredField("ringCursor");
        ringField.setAccessible(true);
        cursorField.setAccessible(true);
        double[][] ring = (double[][]) ringField.get(core);
        int cursor = cursorField.getInt(core) - 19_200;
        int ringLength = 3 * 48_000;
        if (cursor < 0) cursor += ringLength;
        double sum = 0.0d;
        for (int i = 0; i < 19_200; i++)
        {
            sum += ring[cursor >>> 15][cursor & 32767];
            cursor++;
            if (cursor == ringLength) cursor = 0;
        }
        double expected = -0.691d + 10.0d * StrictMath.log10(sum / 19_200);
        double measured = LoudnessCore.fromPower(core.momentaryPower()).lufs();
        assertEquals(expected, measured, 1.0e-8d,
                "Window LUFS must follow its own physical power, not earlier loud samples; variant=" + variant);
        }
    }
}
