package com.quickmaster.processing.dynamics.leveler;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.function.IntFunction;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Declared structural observations for component tests, not PCM engine evidence. */
final class MusicalContextFixtures
{
    static StructuralBin base()
    {
        double[] c = new double[12], s = new double[8]; c[0] = 1d; s[0] = 1d;
        return new StructuralBin(c, s, 0d, .75d);
    }

    static StructuralBin exterior()
    {
        double[] c = new double[12], s = new double[8];
        c[1] = 1d; s[0] = -1d;
        return new StructuralBin(c, s, 0d, .75d);
    }

    static FeatureTimeline features(long hop, long frames, IntFunction<StructuralBin> values)
    {
        Object[] rows = new Object[Math.toIntExact(1L + (frames - 1L) / hop)];
        for (int i = 0; i < rows.length; i++)
        {
            StructuralBin b = values.apply(i);
            double[] c = new double[12], s = new double[8];
            for (int k = 0; k < 12; k++) c[k] = b.chromaAt(k);
            for (int k = 0; k < 8; k++) s[k] = b.spectralAt(k);
            long start = i * hop;
            rows[i] = new StructuralFrame(start + (Math.min(hop, frames - start) - 1L) / 2L,
                    c, s, b.onsetFlux(), b.activity());
        }
        return new FeatureTimeline(hop, new FrozenList<StructuralFrame>(rows));
    }

    @SuppressWarnings("unchecked")
    static FrozenList<StructuralBin> bins(FeatureTimeline features, FrameRange range, long frames)
            throws Exception
    {
        Method method = SegmentDescriptorBuilder.class.getDeclaredMethod("structuralBins",
                FeatureTimeline.class, FrameRange.class, long.class);
        method.setAccessible(true);
        Object result = method.invoke(null, features, range, frames);
        if (result == null) return null;
        return result instanceof Object[] values ? new FrozenList<StructuralBin>(values)
                : (FrozenList<StructuralBin>) result;
    }

    static SegmentDescriptor descriptor(int id, FeatureTimeline features, FrameRange range, long frames)
            throws Exception
    {
        FrozenList<StructuralBin> bins = bins(features, range, frames);
        long mask = 0;
        for (int k = 0; k < 32; k++)
        {
            StructuralBin b = bins.get(k);
            double c = 0d, s = 0d;
            for (int j = 0; j < 12; j++) c += b.chromaAt(j) * b.chromaAt(j);
            for (int j = 0; j < 8; j++) s += b.spectralAt(j) * b.spectralAt(j);
            if (Double.isFinite(c) && Double.isFinite(s) && Math.sqrt(c) > 1e-12 && Math.sqrt(s) > 1e-12)
                mask |= 1L << k;
        }
        return new SegmentDescriptor(new SegmentId(id), range, bins, mask,
                new MeasuredLoudness(true, -20d), 0d, 0d, 0d, 0d, 0d, .75d,
                new BodyContextVector(1d, 1d, 0d, 0d, 0d, 0d), MusicalModelFixtures.sketch(id));
    }

    /** Legacy branch exists only to run discriminating red tests against the preserved preimage. */
    static SimilarityScore compare(SegmentDescriptor a, SegmentDescriptor b,
                                   AudioFormat source, FeatureTimeline features) throws Exception
    {
        try
        {
            Method method = SegmentComparator.class.getMethod("compare", SegmentDescriptor.class,
                    SegmentDescriptor.class, AudioFormat.class, FeatureTimeline.class,
                    LevelerCalibrationProfile.class);
            return (SimilarityScore) method.invoke(new SegmentComparator(), a, b, source, features,
                    LevelerCalibrationProfile.V1);
        }
        catch (NoSuchMethodException legacy)
        {
            Method method = SegmentComparator.class.getMethod("compare", SegmentDescriptor.class,
                    SegmentDescriptor.class, LevelerCalibrationProfile.class);
            return (SimilarityScore) method.invoke(new SegmentComparator(), a, b, LevelerCalibrationProfile.V1);
        }
    }

    static LoudnessTimeline loudness(int sr, long frames, double[] observations, BitSet valid)
    {
        int hop = Math.max(1, (int) Math.round(.1 * sr));
        int count = Math.toIntExact(1L + (frames - 1L) / hop);
        double[] values = new double[count];
        System.arraycopy(observations, 0, values, 0, Math.min(count, observations.length));
        return new LoudnessTimeline(Math.max(1, (int) Math.round(.4 * sr)), 3 * sr, hop,
                new double[count], new BitSet(count), values, valid, MeasuredLoudness.absent());
    }

    static FrozenList<SegmentDescriptor> build(int sr, long frames, FeatureTimeline features,
                                              LoudnessTimeline loudness, long... boundaries)
    {
        Object[] ranges = new Object[boundaries.length - 1];
        for (int i = 0; i < ranges.length; i++) ranges[i] = new FrameRange(boundaries[i], boundaries[i + 1]);
        return new SegmentDescriptorBuilder().build(new float[Math.toIntExact(frames)],
                new AudioFormat(sr, 1, frames), loudness, features,
                new SegmentLayout(LayoutStatus.READY, new FrozenList<FrameRange>(ranges)));
    }
}
