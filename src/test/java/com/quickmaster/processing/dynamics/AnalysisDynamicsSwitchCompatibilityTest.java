package com.quickmaster.processing.dynamics;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** ADR012: no PublishedGain forgery; unreachable domains use a minimal block harness. */
@SuppressWarnings("deprecation")
class AnalysisDynamicsSwitchCompatibilityTest
{
    private static final class Ramp extends AnalysisDynamicsProcessor
    {
        protected void computeFeatures(float[] pcm, int channels, int rate, int frames) { }
        protected void mapFeaturesToGain()
        {
            gainEnv = new float[envFrames];
            for (int i = 0; i < envFrames; i++) gainEnv[i] = 0.125f + i * 0.0078125f;
        }
    }

    @Test void helperMustBePhysicallyAbsentAndSourceRepairIsExact() throws Exception
    {
        Path root = Path.of(System.getProperty("qm.staticClasses", "target/classes"));
        assertFalse(Files.exists(root.resolve("com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor$1.class")));
        String source = Files.readString(Path.of("src/main/java/com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor.java"));
        String body = source.substring(source.indexOf("public float[] process("), source.indexOf("public boolean isEnabled()"));
        assertFalse(body.contains("switch"));
        assertEquals(1, body.split("schedule\\(\\)\\.domain\\(\\)", -1).length - 1);
        assertTrue(body.contains("java.util.Objects.requireNonNull(domain);"));
    }

    @Test void guardsPreserveCursorAndRawMeter() throws Exception
    {
        Ramp p = prepared(1, 1);
        p.process(new float[]{1}, 1);
        assertNotEquals(0L, Double.doubleToRawLongBits(p.getGainReductionDb()));
        p.setPlaybackPosition(9);
        assertNull(p.process(null, 1)); assertEquals(9, cursor(p)); zero(p);
        for (int channels : new int[]{0, -1, 2})
        {
            float[] invalid = {1}; assertSame(invalid, p.process(invalid, channels));
            assertEquals(9, cursor(p)); zero(p);
        }
        float[] nonfinite = {Float.intBitsToFloat(0x7fc12345), -0.0f};
        int[] bits = bits(nonfinite); p.process(nonfinite, 1);
        assertArrayEquals(bits, bits(nonfinite)); assertEquals(11, cursor(p)); zero(p);
        p.setEnabled(false); unitAndAdvance(p, 1, 2); p.setEnabled(true);
        p.prepare(0, 64); unitAndAdvance(p, 1, 2);
        Ramp unit = new Ramp(); unit.prepare(48000, 64); unit.setEnabled(true); unitAndAdvance(unit, 1, 2);
        Ramp mono = prepared(1, 1); unitAndAdvance(mono, 2, 2);
    }

    @Test void rawBitMonoStereoOversamplingSeekAndAdoption() throws Exception
    {
        for (int channels : new int[]{1, 2}) for (int factor : new int[]{1, 2, 4})
        {
            Ramp p = prepared(channels, factor);
            DenseGainSchedule schedule = (DenseGainSchedule) p.publishedGain().schedule();
            for (long seek : new long[]{0, 7, -2, 63, 100})
            {
                p.setPlaybackPosition(seek);
                float[] buffer = new float[17 * channels];
                for (int i = 0; i < buffer.length; i++) buffer[i] = (i % 2 == 0 ? -1f : 1f) * (i + 1) / 32f;
                float[] expected = buffer.clone(); double meter = 0;
                for (int frame = 0; frame < 17; frame++)
                {
                    float gain = schedule.sampleLinearLegacy((seek + frame) * (1.0 / factor));
                    for (int c = 0; c < channels; c++) expected[frame * channels + c] *= gain;
                    double db = 20.0 * Math.log10(Math.max(gain, 1e-6f));
                    if (Math.abs(db) > Math.abs(meter)) meter = db;
                }
                assertSame(buffer, p.process(buffer, channels)); assertArrayEquals(bits(expected), bits(buffer));
                assertEquals(Double.doubleToRawLongBits(meter), Double.doubleToRawLongBits(p.getGainReductionDb()));
                assertEquals(seek + 17, cursor(p));
                Ramp adopted = new Ramp(); adopted.adoptEnvelope(p); adopted.prepare(48000 * factor, 64L * channels * factor);
                adopted.setEnabled(true); adopted.setPlaybackPosition(seek); float[] original = new float[channels];
                java.util.Arrays.fill(original, 1f); float[] twin = original.clone();
                p.setPlaybackPosition(seek); assertArrayEquals(bits(p.process(original, channels)), bits(adopted.process(twin, channels)));
            }
        }
    }

    @Test void isolatedBlockPreservesNullDomainNpeAndSparseUnit()
    {
        assertThrows(NullPointerException.class, () -> block(true, null));
        assertEquals(0L, Double.doubleToRawLongBits(block(true, GainDomain.SPARSE_DB)));
        assertEquals(-7.0, block(true, GainDomain.LEGACY_LINEAR));
        assertEquals(0L, Double.doubleToRawLongBits(block(false, null)));
    }
    private static double block(boolean guard, GainDomain supplied)
    {
        double meterDb = 0.0;
        if (guard)
        {
            GainDomain domain = supplied;
            java.util.Objects.requireNonNull(domain);
            if (domain == GainDomain.LEGACY_LINEAR) meterDb = -7.0;
            else if (domain == GainDomain.SPARSE_DB) meterDb = 0.0;
        }
        return meterDb;
    }
    private static Ramp prepared(int channels, int factor)
    { Ramp p = new Ramp(); p.prepare(48000, 64L * channels); p.analyze(new float[64 * channels], channels); p.prepare(48000 * factor, 64L * channels * factor); p.setEnabled(true); return p; }
    private static long cursor(Ramp p) throws Exception
    { var field = AnalysisDynamicsProcessor.class.getDeclaredField("preparedFrameCursor"); field.setAccessible(true); long cursor = field.getLong(p); var dense = AnalysisDynamicsProcessor.class.getDeclaredField("denseCursor"); dense.setAccessible(true); var next = DenseGainCursor.class.getDeclaredField("nextPreparedFrame"); next.setAccessible(true); assertEquals(cursor, next.getLong(dense.get(p))); return cursor; }
    private static void zero(Ramp p) { assertEquals(0L, Double.doubleToRawLongBits(p.getGainReductionDb())); }
    private static void unitAndAdvance(Ramp p, int channels, int frames) throws Exception
    { long before = cursor(p); float[] b = new float[channels * frames]; java.util.Arrays.fill(b, 1); assertArrayEquals(bits(b), bits(p.process(b, channels))); assertEquals(before + frames, cursor(p)); zero(p); }
    private static int[] bits(float[] b) { int[] out = new int[b.length]; for (int i = 0; i < b.length; i++) out[i] = Float.floatToRawIntBits(b[i]); return out; }
}
