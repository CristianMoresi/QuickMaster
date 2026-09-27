package com.dspark.core;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent tone oracles: no production filter coefficients in the expectations. */
class ResamplerDeliveryTest {
    @Test void finiteDurationDoesNotGainAFrameFromRoundedRateRatios() {
        for (int from : new int[]{44100,48000,88200,96000,176400,192000})
            for (int to : new int[]{44100,48000,88200,96000,176400,192000}) {
                Resampler r=converter(from,to);
                assertEquals(to, r.getOutputLength(from), from+" -> "+to);
                int frames=from/4+17;
                assertEquals((int)(((long)frames*to+from-1)/from), r.getOutputLength(frames));
            }
    }
    @Test void supportedDownconversionsPreserveTheAudioBand() {
        for (int from : new int[]{48000, 88200, 96000, 176400, 192000})
            for (int to : new int[]{44100, 48000}) {
                if (from <= to) continue;
                for (int hz : new int[]{1000, 18000, 20000})
                    assertEquals(0, toneGain(from, to, hz), .1, from+" -> "+to+" @ "+hz);
            }
        assertEquals(0, toneGain(192000, 8000, 997), .04);
    }

    @Test void supportedDownconversionsRejectOutOfBandTones() {
        for (int from : new int[]{96000, 176400, 192000})
            for (int to : new int[]{44100, 48000})
                for (int hz : new int[]{28000, 35000})
                    assertTrue(toneGain(from, to, hz) < -90, from+" -> "+to+" @ "+hz);
    }

    @Test void interleavedConversionKeepsHeadroomPolarityAndExactFrameExtent() {
        Resampler r = converter(192000, 44100);
        float[] mono = tone(192000, 997, 1.5), stereo = new float[mono.length * 2];
        for (int i=0;i<mono.length;i++) { stereo[2*i]=mono[i]; stereo[2*i+1]=-mono[i]; }
        float[] original = stereo.clone(), expected = r.process(mono), actual = r.resampleInterleaved(stereo, 2);
        assertEquals(2 * (int)Math.ceil(mono.length*r.getRatio()), actual.length);
        for (int i=0;i<expected.length;i++) {
            assertEquals(expected[i], actual[2*i], 0);
            assertEquals(-expected[i], actual[2*i+1], 0);
        }
        assertArrayEquals(original, stereo);
        assertTrue(actual[actual.length/2+12] != 0);
    }

    @Test void interleavedPathAllocatesOnlyItsResultNotWholeTrackPlanarCopies() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported()) return;
        allocation.setThreadAllocatedMemoryEnabled(true);
        Resampler r = converter(48000, 44100);
        float[] in = new float[96000];
        for (int i=0;i<3;i++) r.resampleInterleaved(in, 2);
        long tid = Thread.currentThread().getId(), before = allocation.getThreadAllocatedBytes(tid);
        float[] out = r.resampleInterleaved(in, 2);
        long bytes = allocation.getThreadAllocatedBytes(tid) - before;
        assertTrue(bytes < 4L*out.length+32768, "Allocated "+bytes+" for "+out.length+" output samples");
    }

    @Test void rejectsMalformedNonfiniteAndImpossibleBuffersWithoutChangingSource() {
        Resampler r = converter(44100, 48000);
        assertThrows(IllegalArgumentException.class, () -> r.resampleInterleaved(new float[3], 2));
        assertThrows(IllegalArgumentException.class, () -> r.resampleInterleaved(new float[2], 0));
        assertThrows(IllegalArgumentException.class, () -> r.process(new float[]{Float.NaN}));
        assertThrows(IllegalArgumentException.class, () -> r.getOutputLength(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> r.getOutputLength(-1));
        assertThrows(IllegalArgumentException.class, () -> r.prepare(192000, .001, Resampler.Quality.ULTRA));
        assertEquals(48000.0/44100, r.getRatio());
    }

    @Test void cancellationIsCooperativeAndDoesNotClearTheInterrupt() {
        Resampler r = converter(192000, 44100);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> r.process(new float[192000]));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static Resampler converter(int from, int to) {
        Resampler r = new Resampler(); r.prepare(from, to, Resampler.Quality.ULTRA); return r;
    }
    private static float[] tone(int rate, int hz, double amplitude) {
        float[] data = new float[rate];
        for (int i=0;i<data.length;i++) data[i]=(float)(amplitude*Math.sin(2*Math.PI*hz*i/rate));
        return data;
    }
    private static double toneGain(int from, int to, int hz) {
        float[] out=converter(from,to).process(tone(from,hz,.5));
        double sum=0; int first=to/4, last=3*to/4;
        // Coherent half-second except the deliberately odd 997 Hz low-rate case;
        // its residual integration error is < .006 dB, below the .04 dB limit.
        for(int i=first;i<last;i++)sum+=(double)out[i]*out[i];
        return 20*Math.log10(Math.sqrt(2*sum/(last-first))/.5);
    }
}
