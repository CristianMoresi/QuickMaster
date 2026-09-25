package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.*;

/** Explicit component observations; never substitutes for a PCM engine fixture. */
final class MusicalModelFixtures
{
    private MusicalModelFixtures() { }

    /** Central kernel oracle only: fabricated component descriptors have no source provenance. */
    static SimilarityScore centralComparison(SegmentDescriptor a, SegmentDescriptor b,
                                              LevelerCalibrationProfile profile)
    {
        try
        {
            var kernel = SegmentComparator.class.getDeclaredMethod("kernel", FrozenList.class,
                    FrozenList.class, long.class, long.class, long.class, long.class,
                    LevelerCalibrationProfile.class, double[].class, int[].class);
            kernel.setAccessible(true);
            double[] scores = new double[4];
            int[] details = new int[2];
            SimilarityRejectionReason reason = (SimilarityRejectionReason) kernel.invoke(null,
                    a.bins(), b.bins(), a.validBinMask(), b.validBinMask(), a.range().lengthFrames(),
                    b.range().lengthFrames(), profile, scores, details);
            // This test-only DTO exposes central figures to the existing component assertions.
            return new SimilarityScore(scores[0], scores[1], scores[2], scores[3], details[0], details[1], reason);
        }
        catch (ReflectiveOperationException error)
        {
            throw new AssertionError("Actual central comparison kernel was not exercised", error);
        }
    }

    static StructuralBin bin(int chroma, int spectral, double activity)
    {
        double[] c = new double[12];
        double[] s = new double[8];
        if (chroma >= 0) c[chroma] = 1.0d;
        if (spectral >= 0) s[spectral] = 1.0d;
        return new StructuralBin(c, s, 0.1d, activity);
    }

    static StructuralBin[] bins(int chroma, int spectral, double activity)
    {
        StructuralBin[] result = new StructuralBin[32];
        for (int i = 0; i < result.length; i++) result[i] = bin(chroma, spectral, activity);
        return result;
    }

    static SegmentDescriptor descriptor(int id, double lufs)
    {
        return descriptor(id, lufs, bins(0, 0, 0.9d), 0xFFFF_FFFFL, 576_000L);
    }

    static SegmentDescriptor descriptor(int id, double lufs, StructuralBin[] bins,
                                         long mask, long duration)
    {
        return new SegmentDescriptor(new SegmentId(id), new FrameRange(id * 1_000_000L,
                id * 1_000_000L + duration), new FrozenList<StructuralBin>(bins), mask,
                new MeasuredLoudness(true, lufs), 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.9d,
                new BodyContextVector(1.0d, 1.0d, 0.0d, 0.0d, 0.25d, 0.5d), sketch(id));
    }

    static SegmentDescriptor withContext(SegmentDescriptor source, BodyContextVector context,
                                          PcmSketch sketch)
    {
        return new SegmentDescriptor(source.id(), source.range(), source.bins(), source.validBinMask(),
                source.regionalLoudness(), source.loudnessSlopeLuPerSec(), source.loudnessDeltaLu(),
                source.loudnessConsistency(), source.activitySlopePerSec(), source.activitySpread(),
                source.foregroundRatio(), context, sketch);
    }

    static SegmentDescriptor withStats(SegmentDescriptor source, MeasuredLoudness loudness,
                                        double slope, double delta, double consistency,
                                        double activitySlope, double spread, double foreground)
    {
        return new SegmentDescriptor(source.id(), source.range(), source.bins(), source.validBinMask(),
                loudness, slope, delta, consistency, activitySlope, spread, foreground,
                source.context(), source.sketch());
    }

    static PcmSketch sketch(int variant)
    {
        double[][] values = new double[2][2048];
        values[0][100] = 0.5d;
        values[1][500 + variant * 17] = 1.0d / 64.0d;
        return new PcmSketch(2, 2048, values);
    }

    static FrozenList<SegmentDescriptor> descriptors(int... order)
    {
        Object[] all = new Object[order.length];
        for (int i = 0; i < order.length; i++) all[i] = descriptor(order[i], -20.0d);
        return new FrozenList<SegmentDescriptor>(all);
    }

    static SimilarityMatrix matrix(int[] order, double[][] byId)
    {
        Object[] scores = new Object[order.length * (order.length - 1) / 2];
        int position = 0;
        for (int i = 0; i < order.length; i++)
        {
            for (int j = i + 1; j < order.length; j++)
            {
                double c = byId[order[i]][order[j]];
                scores[position++] = new SimilarityScore(c, c, c, c, 0, 32,
                        SimilarityRejectionReason.NONE);
            }
        }
        return new SimilarityMatrix(order.length, new FrozenList<SimilarityScore>(scores));
    }

    static void edge(double[][] matrix, int first, int second, double c)
    {
        matrix[first][second] = c;
        matrix[second][first] = c;
    }
}
