package com.quickmaster.processing.dynamics.leveler;

import java.util.*;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Independent bin-major integration and full-grid dynamic program; no production helpers. */
final class MusicalContextOracle
{
    record Score(double h, double t, double a, double c, int rotation, int count, SimilarityRejectionReason reason) { }
    record Comparison(Score central, List<Score> variants, SimilarityRejectionReason reason) { }

    static StructuralBin[] bins(FeatureTimeline features, FrameRange range, long frames)
    {
        StructuralBin[] bins = new StructuralBin[32]; long length = range.lengthFrames(), hop = features.hopFrames();
        for (int k = 0; k < 32; k++)
        {
            double[] c = new double[12], s = new double[8]; double flux = 0, activity = 0;
            for (int i = 0; i < features.size(); i++)
            {
                long left = Math.max(range.startInclusive(), i * hop), right = Math.min(range.endExclusive(), Math.min(frames, (i + 1L) * hop));
                if (right <= left) continue;
                long area = Math.max(0, Math.min((k + 1L) * length, 32 * (right - range.startInclusive()))
                        - Math.max(k * length, 32 * (left - range.startInclusive())));
                StructuralFrame frame = features.frame(i);
                for (int j = 0; j < 12; j++) c[j] += area * frame.chromaAt(j);
                for (int j = 0; j < 8; j++) s[j] += area * frame.spectralAt(j);
                flux += area * frame.onsetFlux(); activity += area * frame.activity();
            }
            for (int j = 0; j < 12; j++) c[j] /= length;
            for (int j = 0; j < 8; j++) s[j] /= length;
            bins[k] = new StructuralBin(c, s, flux / length, activity / length);
        }
        return bins;
    }

    static Comparison compare(FeatureTimeline features, AudioFormat source, FrameRange a, FrameRange b)
    {
        StructuralBin[] first = bins(features, a, source.frames()), second = bins(features, b, source.frames());
        Score central = score(first, second, a.lengthFrames(), b.lengthFrames());
        List<Score> variants = new ArrayList<>();
        SimilarityRejectionReason reason = central.reason;
        long delta = Math.max(1, Math.round(source.sampleRateHz() / 2d));
        for (int slot = 0; slot < 8; slot++)
        {
            FrameRange r = slot < 4 ? a : b;
            long start = r.startInclusive(), end = r.endExclusive();
            switch (slot % 4)
            {
                case 0 -> start = Math.max(0, start - delta);
                case 1 -> start = Math.min(source.frames(), start + delta);
                case 2 -> end = Math.max(0, end - delta);
                case 3 -> end = Math.min(source.frames(), end + delta);
            }
            Score variant = start >= end ? rejected(SimilarityRejectionReason.UNSTABLE_BOUNDARY, 0)
                    : score(slot < 4 ? bins(features, new FrameRange(start, end), source.frames()) : first,
                            slot < 4 ? second : bins(features, new FrameRange(start, end), source.frames()),
                            slot < 4 ? end - start : a.lengthFrames(), slot < 4 ? b.lengthFrames() : end - start);
            variants.add(variant);
            if (reason == SimilarityRejectionReason.NONE && (variant.reason != SimilarityRejectionReason.NONE || Math.abs(variant.a - central.a) > .05))
                reason = SimilarityRejectionReason.UNSTABLE_BOUNDARY;
        }
        return new Comparison(central, variants, reason);
    }

    static Score score(StructuralBin[] a, StructuralBin[] b, long durationA, long durationB)
    {
        double ratio = durationA / (double) durationB;
        if (ratio < .75 || ratio > 1.33) return rejected(SimilarityRejectionReason.DURATION_RATIO, 0);
        List<Integer> valid = new ArrayList<>(); int run = 0, longest = 0;
        for (int i = 0; i < 32; i++)
        {
            if (valid(a[i]) && valid(b[i])) { valid.add(i); run = 0; }
            else longest = Math.max(longest, ++run);
        }
        int count = valid.size();
        if (count < 24) return rejected(SimilarityRejectionReason.INSUFFICIENT_VALID_BINS, count);
        if (longest > 4) return rejected(SimilarityRejectionReason.INVALID_BIN_RUN, count);
        double[] means = new double[12];
        for (int r = 0; r < 12; r++) for (int i : valid) means[r] += clamp(cos(a[i], b[i], r, true), 0, 1);
        for (int r = 0; r < 12; r++) means[r] /= count;
        int rotation = 0;
        for (int r = 1; r < 12; r++) if (means[r] > means[rotation] + 1e-12) rotation = r;
        if (rotation != 0)
        {
            int supporters = 0;
            for (int i : valid)
            {
                double chosen = cos(a[i], b[i], rotation, true), best = chosen;
                for (int r = 0; r < 12; r++) best = Math.max(best, cos(a[i], b[i], r, true));
                if (chosen >= best - 1e-12) supporters++;
            }
            if (supporters * 5 < count * 4) return rejected(SimilarityRejectionReason.AMBIGUOUS, count);
        }
        double rho = count / 32d, timbre = 0;
        for (int i : valid) timbre += .8 * (1 + clamp(cos(a[i], b[i], 0, false), -1, 1)) / 2
                + .2 * (1 - Math.abs(a[i].activity() - b[i].activity()));
        double[][] costs = new double[count + 1][count + 1]; int[][] lengths = new int[count + 1][count + 1];
        for (double[] row : costs) Arrays.fill(row, Double.POSITIVE_INFINITY); costs[0][0] = 0;
        for (int i = 1; i <= count; i++) for (int j = 1; j <= count; j++)
        {
            int ai = valid.get(i - 1), bj = valid.get(j - 1); if (Math.abs(ai - bj) > 4) continue;
            double local = .5 * (1 - clamp(cos(a[ai], b[bj], rotation, true), 0, 1))
                    + .3 * (1 - (1 + clamp(cos(a[ai], b[bj], 0, false), -1, 1)) / 2)
                    + .2 * Math.abs(a[ai].activity() - b[bj].activity());
            int pi = i - 1, pj = j - 1;
            if (costs[i - 1][j] < costs[pi][pj] - 1e-12) { pi = i - 1; pj = j; }
            if (costs[i][j - 1] < costs[pi][pj] - 1e-12) { pi = i; pj = j - 1; }
            costs[i][j] = costs[pi][pj] + local; lengths[i][j] = lengths[pi][pj] + 1;
        }
        double h = rho * clamp(means[rotation] - (rotation == 0 ? 0 : .05), 0, 1);
        double t = rho * timbre / count, alignment = rho * clamp(1 - costs[count][count] / lengths[count][count], 0, 1);
        return new Score(h, t, alignment, Math.min(h, Math.min(t + .05, alignment + .03)), rotation, count, SimilarityRejectionReason.NONE);
    }

    private static Score rejected(SimilarityRejectionReason reason, int count) { return new Score(0, 0, 0, 0, 0, count, reason); }
    private static boolean valid(StructuralBin b)
    {
        double c = 0, s = 0; for (int i = 0; i < 12; i++) c += b.chromaAt(i) * b.chromaAt(i);
        for (int i = 0; i < 8; i++) s += b.spectralAt(i) * b.spectralAt(i);
        return Double.isFinite(c) && Double.isFinite(s) && Math.sqrt(c) > 1e-12 && Math.sqrt(s) > 1e-12;
    }
    private static double cos(StructuralBin a, StructuralBin b, int rotation, boolean chroma)
    {
        double aa = 0, bb = 0, ab = 0;
        for (int i = 0; i < (chroma ? 12 : 8); i++)
        {
            double x = chroma ? a.chromaAt(i) : a.spectralAt(i), y = chroma ? b.chromaAt((i + rotation) % 12) : b.spectralAt(i);
            aa += x * x; bb += y * y; ab += x * y;
        }
        return ab / (Math.sqrt(aa) * Math.sqrt(bb));
    }
    private static double clamp(double value, double min, double max) { return Math.min(max, Math.max(min, value)); }
}
