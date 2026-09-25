package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.BodyContextVector;
import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.MeasuredLoudness;
import com.quickmaster.processing.dynamics.leveler.model.PcmSketch;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SegmentId;
import com.quickmaster.processing.dynamics.leveler.model.StructuralBin;

final class LevelerModelFixtures
{
    private LevelerModelFixtures() { }

    static SegmentDescriptor descriptor(int ordinal,
                                        double lufs,
                                        double[] chroma,
                                        double[] spectral,
                                        double activity,
                                        BodyContextVector context,
                                        PcmSketch sketch)
    {
        Object[] bins = new Object[32];
        for (int i = 0; i < bins.length; i++)
        {
            bins[i] = new StructuralBin(chroma, spectral, 0.1d, activity);
        }
        long start = ordinal * 480_000L;
        return new SegmentDescriptor(new SegmentId(ordinal),
                new FrameRange(start, start + 480_000L),
                new FrozenList<StructuralBin>(bins), 0xFFFF_FFFFL,
                new MeasuredLoudness(true, lufs), 0.0d, 0.0d, 1.0d,
                0.0d, 0.05d, 0.9d, context, sketch);
    }

    static PcmSketch impulseSketch(int channels, double scale, int offset)
    {
        double[][] values = new double[channels][];
        for (int channel = 0; channel < channels; channel++) values[channel] = new double[2048];
        values[0][100 + offset] = 0.5d * scale;
        values[0][701 + offset] = -0.25d * scale;
        if (channels == 2)
        {
            values[1][311 + offset] = 0.375d * scale;
            values[1][1703 + offset] = -0.125d * scale;
        }
        return new PcmSketch(channels, 2048, values);
    }

    static BodyContextVector neutralContext()
    {
        return new BodyContextVector(1.0d, 1.0d, 0.0d, 0.0d, 0.25d, 0.5d);
    }

    static double[] unitChroma()
    {
        double[] vector = new double[12];
        vector[0] = 1.0d;
        return vector;
    }

    static double[] unitSpectral()
    {
        double[] vector = new double[8];
        vector[0] = 1.0d;
        return vector;
    }
}
