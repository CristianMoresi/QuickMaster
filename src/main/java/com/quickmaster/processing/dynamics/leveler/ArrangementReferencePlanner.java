package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.quickmaster.processing.dynamics.leveler.model.*;

/**
 * A second, level-independent route for comparable musical bodies whose performances
 * or lengths differ. It compares distributions, not time-aligned copies. It never
 * overrides a protection or an existing repetition reference. All scratch storage
 * is analysis-local; no PCM, feature summary or mutable cluster is published.
 */
public final class ArrangementReferencePlanner
{
    public ReferencePlan supplement(ReferencePlan repetition, FrozenList<SegmentDescriptor> segments,
                                    FrozenList<ProtectionDecision> protections,
                                    ComparisonTimeline timeline, CancellationToken token)
    {
        if (repetition == null || segments == null || protections == null || timeline == null
                || repetition.size() != segments.size() || protections.size() != segments.size()
                || segments.size() > 64) throw new IllegalArgumentException("Invalid arrangement inputs.");
        int size = segments.size();
        double[][] evidence = new double[size][];
        double[][] similarity = new double[size][size];
        Object[] targets = new Object[size];
        double[] weights = repetition.copyWeights();
        int[][] clusters = new int[size][size];
        int[] counts = new int[size];
        for (int i = 0; i < size; i++)
        {
            if (cancelled(token)) return null;
            targets[i] = repetition.targets().get(i);
            if (repetition.targets().get(i).reason() != ReferenceReason.NOT_COMPARABLE
                    || !eligible(i, segments, protections, timeline.format().sampleRateHz())) continue;
            evidence[i] = summarize(segments.get(i).range(), timeline, token);
            if (evidence[i] != null)
            {
                clusters[i][0] = i;
                counts[i] = 1;
            }
        }
        BodyContextGate intent = new BodyContextGate();
        for (int i = 0; i < size; i++) for (int j = i + 1; j < size; j++)
        {
            if (cancelled(token)) return null;
            if (evidence[i] == null || evidence[j] == null) continue;
            SegmentDescriptor a = segments.get(i), b = segments.get(j);
            double[] ambiguity = intent.assess(a.sketch(), b.sketch(), a.context(), b.context());
            if (ambiguity != null && ambiguity[2] <= 1.0e-4d && ambiguity[3] <= 0.05d) continue;
            similarity[i][j] = similarity[j][i] = confidence(evidence[i], evidence[j]);
        }
        // Complete linkage prevents a sequence of marginal matches from joining
        // mutually different arrangements. Neither duration nor loudness enters it.
        while (true)
        {
            if (cancelled(token)) return null;
            int left = -1, right = -1;
            double best = 0;
            for (int i = 0; i < size; i++) for (int j = i + 1; j < size; j++)
            {
                if (counts[i] == 0 || counts[j] == 0) continue;
                double minimum = 1;
                for (int a = 0; a < counts[i]; a++) for (int b = 0; b < counts[j]; b++)
                    minimum = Math.min(minimum, similarity[clusters[i][a]][clusters[j][b]]);
                if (minimum > best)
                {
                    best = minimum;
                    left = i;
                    right = j;
                }
            }
            if (left < 0) break;
            System.arraycopy(clusters[right], 0, clusters[left], counts[left], counts[right]);
            counts[left] += counts[right];
            counts[right] = 0;
        }
        for (int cluster = 0; cluster < size; cluster++)
        {
            int count = counts[cluster];
            if (count < 2) continue;
            double quality = 1;
            double[] levels = new double[count];
            for (int a = 0; a < count; a++)
            {
                levels[a] = segments.get(clusters[cluster][a]).regionalLoudness().lufs();
                for (int b = a + 1; b < count; b++)
                    quality = Math.min(quality, similarity[clusters[cluster][a]][clusters[cluster][b]]);
            }
            Arrays.sort(levels);
            // Equal member weights stop an extended final chorus from dominating.
            // The even median is interpolated, including the two-body case.
            double reference = (levels[(count - 1) / 2] + levels[count / 2]) * 0.5d;
            for (int member = 0; member < count; member++)
            {
                int index = clusters[cluster][member];
                SegmentDescriptor segment = segments.get(index);
                double difference = reference - segment.regionalLoudness().lufs();
                // A 0.5 LU pair tolerance preserves small musical level differences.
                double raw = Math.copySign(Math.max(0, Math.abs(difference) - 0.25d), difference);
                if (raw == 0) raw = 0;
                targets[index] = new ReferenceTarget(segment.id(), new MeasuredLoudness(true, reference),
                        raw, quality, raw * quality, ReferenceReason.ARRANGEMENT_REFERENCE);
                weights[index] = 1.0d / count;
            }
        }
        return cancelled(token) ? null : new ReferencePlan(new FrozenList<ReferenceTarget>(targets), weights);
    }

    private static boolean eligible(int index, FrozenList<SegmentDescriptor> segments,
                                     FrozenList<ProtectionDecision> protections, int rate)
    {
        if (index == 0 || index + 1 == segments.size() || protections.get(index).isBlocked()) return false;
        SegmentDescriptor segment = segments.get(index);
        return segment.range().lengthFrames() >= 8L * rate
                && segment.regionalLoudness().present() && segment.foregroundRatio() >= 0.80d
                && segment.activitySpread() <= 0.15d && Math.abs(segment.activitySlopePerSec()) < 0.01d
                && Math.abs(segment.loudnessSlopeLuPerSec()) < 0.10d
                && !(Math.abs(segment.loudnessDeltaLu()) >= 2 && segment.loudnessConsistency() >= 0.75d)
                && Long.bitCount(segment.validBinMask()) >= 24;
    }

    /** 16 spectral dimensions x 3 quartiles, followed by 36 mean tonal probabilities. */
    private static double[] summarize(FrameRange range, ComparisonTimeline timeline, CancellationToken token)
    {
        int rate = timeline.format().sampleRateHz();
        // Ignore the outer half-second, where cross-boundary analysis windows mix arrangements.
        long startFrame = range.startInclusive() + rate / 2;
        long endFrame = range.endExclusive() - rate / 2;
        int start = Math.toIntExact((startFrame * 40 + rate - 1) / rate);
        int end = Math.min(timeline.shortCount(), Math.toIntExact(endFrame * 40 / rate));
        if (end <= start) return null;
        double[] summary = new double[84];
        double[] values = new double[end - start];
        for (int component = 0; component < 16; component++)
        {
            if (cancelled(token)) return null;
            int count = 0;
            for (int row = start; row < end; row++)
            {
                if ((row & 255) == 0 && cancelled(token)) return null;
                if ((timeline.shortFlagsAt(row) & 3) != 3) continue;
                values[count++] = timeline.shortValueAt(row, 36 + component) / 256.0d;
            }
            if (count * 4L < (end - start) * 3L) return null;
            Arrays.sort(values, 0, count);
            for (int q = 0; q < 3; q++) summary[component * 3 + q] = values[(count - 1) * (q + 1) / 4];
        }
        int tonalCount = 0;
        for (int row = start; row < end; row++)
        {
            if ((row & 255) == 0 && cancelled(token)) return null;
            if ((timeline.shortFlagsAt(row) & 7) != 7) continue;
            double sum = 0;
            for (int component = 0; component < 36; component++) sum += timeline.shortValueAt(row, component) & 65535;
            for (int component = 0; component < 36; component++)
                summary[48 + component] += (timeline.shortValueAt(row, component) & 65535) / sum;
            tonalCount++;
        }
        if (tonalCount * 4L < (end - start) * 3L) return null;
        for (int component = 48; component < 84; component++) summary[component] /= tonalCount;
        return summary;
    }

    private static double confidence(double[] first, double[] second)
    {
        double shape = 0, contrast = 0, tone = 0;
        for (int i = 0; i < 24; i++)
        {
            double a = first[i] - second[i], b = first[24 + i] - second[24 + i];
            shape += a * a / 24;
            contrast += b * b / 24;
        }
        for (int i = 48; i < 84; i++) tone += Math.abs(first[i] - second[i]) * 0.5d;
        double distance = Math.max(Math.sqrt(shape) / 2.5d, Math.max(Math.sqrt(contrast) / 3.0d, tone / 0.12d));
        if (!Double.isFinite(distance) || distance >= 1) return 0;
        return 1 - 0.5d * distance;
    }

    private static boolean cancelled(CancellationToken token) { return token != null && token.isCancelled(); }
}
