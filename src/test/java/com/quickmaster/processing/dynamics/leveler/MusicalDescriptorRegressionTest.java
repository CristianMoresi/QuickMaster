package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import org.junit.jupiter.api.Test;
import com.quickmaster.processing.dynamics.leveler.model.*;

class MusicalDescriptorRegressionTest
{
    @Test
    void fractionalAreaWidensEveryPcmProductBeforeAccumulatingAndIncludesBothChannels()
    {
        float[] pcm = { .1234567f, -.2345678f, .3456789f, -.4567891f, .5678912f, -.6789123f,
                .7891234f, -.8912345f, .9123456f, -.1357924f };
        SegmentDescriptor descriptor = build(pcm, 2, 5L).get(0);
        boolean distinguishesFloatProduct = false;
        for (int c = 0; c < 2; c++) for (int k = 0; k < 2048; k++)
        {
            long lo = k * 5L, hi = (k + 1L) * 5L;
            double expected = 0, mutant = 0;
            for (int n = 0; n < 5; n++)
            {
                long overlap = Math.max(0L, Math.min(hi, (n + 1L) * 2048) - Math.max(lo, n * 2048L));
                expected += (double) overlap * pcm[n * 2 + c];
                mutant += overlap * pcm[n * 2 + c];
            }
            expected /= 5L; mutant /= 5L;
            if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(mutant))
                distinguishesFloatProduct = true;
            assertEquals(expected, descriptor.sketch().valueAt(c, k), 0d, "channel/bin " + c + "/" + k);
        }
        assertTrue(distinguishesFloatProduct);
    }

    @Test
    void invalidPartitionIsRejectedBeforeAccessingPcm()
    {
        assertNull(assertDoesNotThrow(() -> build(new float[] { .2f }, 1, 2L)));
    }

    private static FrozenList<SegmentDescriptor> build(float[] pcm, int channels, long end)
    {
        AudioFormat format = new AudioFormat(48_000, channels, pcm.length / channels);
        LoudnessTimeline loudness = new LoudnessTimeline(19_200, 144_000, 4_800,
                new double[1], new BitSet(1), new double[1], new BitSet(1), MeasuredLoudness.absent());
        StructuralBin prototype = MusicalModelFixtures.bin(0, 0, 1d);
        double[] c = new double[12], s = new double[8];
        for (int i = 0; i < c.length; i++) c[i] = prototype.chromaAt(i);
        for (int i = 0; i < s.length; i++) s[i] = prototype.spectralAt(i);
        FeatureTimeline features = new FeatureTimeline(24_000L, new FrozenList<StructuralFrame>(new Object[] {
                new StructuralFrame((format.frames() - 1) / 2, c, s, 0d, 1d) }));
        SegmentLayout layout = new SegmentLayout(LayoutStatus.READY,
                new FrozenList<FrameRange>(new Object[] { new FrameRange(0, end) }));
        return new SegmentDescriptorBuilder().build(pcm, format, loudness, features, layout);
    }
}
