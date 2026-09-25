package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ComparisonTimeline;
import com.quickmaster.processing.dynamics.leveler.model.FrameRange;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityScore;

/** V2 ordered comparison on immutable source cells; structural features are not inputs. */
public final class ComparisonComparator
{
    private static final int DECODED = 96;
    private static final double TIE = 1.0e-12d;
    // Exact double result of 10 * StrictMath.log10(2); retained as a compile-time scalar.
    private static final double POWER_DOUBLING_DB = 3.010299956639812d;

    public SimilarityScore compare(FrameRange first, FrameRange second, AudioFormat source,
                                   ComparisonTimeline timeline, LevelerCalibrationProfile profile,
                                   CancellationToken token)
    {
        if (!validInput(first, second, source, timeline, profile))
            return rejected(SimilarityRejectionReason.NON_FINITE, 0, 0);
        if (cancelled(token)) return null;
        try
        {
            SimilarityScore central = kernel(first, second, source, timeline, token);
            if (central == null) return null;
            boolean stable = central.isAccepted();
            long delta = StrictMath.round(0.5d * source.sampleRateHz());
            for (int slot = 0; slot < 8; slot++)
            {
                if (cancelled(token)) return null;
                FrameRange range = slot < 4 ? first : second;
                long start = range.startInclusive(), end = range.endExclusive();
                switch (slot % 4)
                {
                    case 0 -> start = Math.max(0L, start - delta);
                    case 1 -> start = Math.min(source.frames(), Math.addExact(start, delta));
                    case 2 -> end = Math.max(0L, end - delta);
                    case 3 -> end = Math.min(source.frames(), Math.addExact(end, delta));
                    default -> throw new IllegalStateException("Endpoint slot.");
                }
                SimilarityScore variant;
                if (start >= end)
                    variant = rejected(SimilarityRejectionReason.UNSTABLE_BOUNDARY, 0, 0);
                else
                {
                    FrameRange changed = new FrameRange(start, end);
                    variant = kernel(slot < 4 ? changed : first, slot < 4 ? second : changed,
                            source, timeline, token);
                }
                if (variant == null) return null;
                if (!variant.isAccepted() || variant.chromaRotation() != central.chromaRotation()
                        || StrictMath.abs(variant.a() - central.a()) > 0.05d)
                    stable = false;
            }
            if (!central.isAccepted()) return central;
            return stable ? central : rejected(SimilarityRejectionReason.UNSTABLE_BOUNDARY,
                    central.validBins(), central.chromaRotation());
        }
        catch (IllegalArgumentException | ArithmeticException ex)
        {
            return rejected(SimilarityRejectionReason.NON_FINITE, 0, 0);
        }
    }

    private static boolean validInput(FrameRange a, FrameRange b, AudioFormat source,
                                      ComparisonTimeline timeline, LevelerCalibrationProfile profile)
    {
        return a != null && b != null && source != null && timeline != null
                && timeline.format() == source && profile == LevelerCalibrationProfile.V2
                && a.startInclusive() >= 0L && b.startInclusive() >= 0L
                && a.endExclusive() <= source.frames() && b.endExclusive() <= source.frames()
                && a.lengthFrames() > 0L && b.lengthFrames() > 0L;
    }

    /** One complete range view evaluation, also used for every endpoint re-slicing. */
    private static SimilarityScore kernel(FrameRange a, FrameRange b, AudioFormat source,
                                           ComparisonTimeline timeline, CancellationToken token)
    {
        double ratio = (double) a.lengthFrames() / b.lengthFrames();
        if (ratio < 0.75d || ratio > 1.33d)
            return rejected(SimilarityRejectionReason.DURATION_RATIO, 0, 0);
        int fs = source.sampleRateHz();
        int longFirstA = firstCell(a.startInclusive(), fs, 8);
        int longFirstB = firstCell(b.startInclusive(), fs, 8);
        int longCountA = endCell(a.endExclusive(), fs, 8) - longFirstA;
        int longCountB = endCell(b.endExclusive(), fs, 8) - longFirstB;
        int longN = Math.max(longCountA, longCountB);
        double[] rotationMeans = new double[12];
        double[] toneA = new double[36], toneB = new double[36];
        int longValid = 0;
        for (int k = 0; k < longN; k++)
        {
            if (cancelled(token)) return null;
            int ia = lifted(longFirstA, longCountA, k, longN);
            int ib = lifted(longFirstB, longCountB, k, longN);
            if (!tonal(timeline.longFlagsAt(ia)) || !tonal(timeline.longFlagsAt(ib))) continue;
            longValid++;
            decodeTone(timeline, true, ia, toneA, 0);
            decodeTone(timeline, true, ib, toneB, 0);
            for (int r = 0; r < 12; r++) rotationMeans[r] += tonalDistance(toneA, 0, toneB, 0, r);
        }
        if (Math.multiplyExact(4L, longValid) < Math.multiplyExact(3L, longN))
            return rejected(SimilarityRejectionReason.INSUFFICIENT_VALID_BINS, 0, 0);
        for (int r = 0; r < 12; r++) rotationMeans[r] /= longValid;
        int rotation = 0;
        for (int r = 1; r < 12; r++)
            if (rotationMeans[r] < rotationMeans[rotation] - TIE) rotation = r;
        if (rotation != 0)
        {
            int support = 0;
            for (int k = 0; k < longN; k++)
            {
                if (cancelled(token)) return null;
                int ia = lifted(longFirstA, longCountA, k, longN);
                int ib = lifted(longFirstB, longCountB, k, longN);
                if (!tonal(timeline.longFlagsAt(ia)) || !tonal(timeline.longFlagsAt(ib))) continue;
                decodeTone(timeline, true, ia, toneA, 0);
                decodeTone(timeline, true, ib, toneB, 0);
                double chosen = tonalDistance(toneA, 0, toneB, 0, rotation), best = chosen;
                for (int r = 0; r < 12; r++) best = Math.min(best, tonalDistance(toneA, 0, toneB, 0, r));
                if (chosen <= best + TIE) support++;
            }
            if (Math.multiplyExact(5L, support) < Math.multiplyExact(4L, longValid))
                return rejected(SimilarityRejectionReason.AMBIGUOUS, 0, rotation);
        }

        int firstA = firstCell(a.startInclusive(), fs, 40), firstB = firstCell(b.startInclusive(), fs, 40);
        int countA = endCell(a.endExclusive(), fs, 40) - firstA;
        int countB = endCell(b.endExclusive(), fs, 40) - firstB;
        int n = Math.max(countA, countB);
        long[] validArea = new long[32];
        for (int k = 0; k < n; k++)
        {
            if (cancelled(token)) return null;
            int ia = lifted(firstA, countA, k, n), ib = lifted(firstB, countB, k, n);
            int la = nearestLong(timeline, ia), lb = nearestLong(timeline, ib);
            if (!validPair(timeline.shortFlagsAt(ia), timeline.longFlagsAt(la),
                    timeline.shortFlagsAt(ib), timeline.longFlagsAt(lb))) continue;
            long left = Math.multiplyExact(32L, k), right = Math.multiplyExact(32L, k + 1L);
            int firstBin = Math.toIntExact(left / n), lastBin = Math.toIntExact((right - 1L) / n);
            for (int bin = firstBin; bin <= lastBin; bin++)
                validArea[bin] += Math.min((bin + 1L) * n, right) - Math.max((long) bin * n, left);
        }
        int validBins = 0, invalidRun = 0, maximumRun = 0;
        for (int bin = 0; bin < 32; bin++)
        {
            if (Math.multiplyExact(4L, validArea[bin]) >= Math.multiplyExact(3L, n))
            {
                validBins++;
                invalidRun = 0;
            }
            else maximumRun = Math.max(maximumRun, ++invalidRun);
        }
        if (validBins < 24) return rejected(SimilarityRejectionReason.INSUFFICIENT_VALID_BINS, 0, 0);
        if (maximumRun > 4) return rejected(SimilarityRejectionReason.INVALID_BIN_RUN, 0, 0);

        long numerator = Math.multiplyExact((long) n, fs);
        long denominator = Math.multiplyExact(2L, Math.max(a.lengthFrames(), b.lengthFrames()));
        int width = Math.toIntExact(Math.min(n, Math.addExact(ceilDiv(numerator, denominator), 2L)));
        int capacity = Math.addExact(Math.multiplyExact(2, width), 1);
        double[] previous = new double[Math.multiplyExact(3, capacity)];
        double[] current = new double[previous.length];
        double[] columns = new double[Math.multiplyExact(DECODED, capacity)];
        long[] shortKeys = new long[capacity], longKeys = new long[capacity];
        byte[] shortFlags = new byte[capacity], longFlags = new byte[capacity];
        Arrays.fill(shortKeys, -1L);
        Arrays.fill(longKeys, -1L);
        double[] row = new double[DECODED], distances = new double[2];
        int previousStart = 0, previousEnd = -1;
        for (int i = 0; i <= n; i++)
        {
            if (cancelled(token)) return null;
            Arrays.fill(current, Double.POSITIVE_INFINITY);
            int currentStart = Math.max(0, i - width), currentEnd = Math.min(n, i + width);
            byte rowShort = 0, rowLong = 0;
            if (i > 0)
            {
                int ia = lifted(firstA, countA, i - 1, n), la = nearestLong(timeline, ia);
                decode(timeline, ia, la, row, 0);
                rowShort = timeline.shortFlagsAt(ia);
                rowLong = timeline.longFlagsAt(la);
            }
            for (int j = currentStart; j <= currentEnd; j++)
            {
                if (!eligible(i, j, numerator, a.lengthFrames(), b.lengthFrames())) continue;
                int destination = 3 * (j - currentStart);
                if (i == 0 && j == 0)
                {
                    current[destination] = current[destination + 1] = current[destination + 2] = 0.0d;
                    continue;
                }
                double best = Double.POSITIVE_INFINITY, bestH = best, bestT = best;
                if (i > 0 && j > 0 && j - 1 >= previousStart && j - 1 <= previousEnd)
                {
                    int predecessor = 3 * (j - 1 - previousStart);
                    if (Double.isFinite(previous[predecessor + 2]))
                    {
                        int ib = lifted(firstB, countB, j - 1, n), lb = nearestLong(timeline, ib);
                        int slot = (j - 1) % capacity, offset = slot * DECODED;
                        if (shortKeys[slot] != ib || longKeys[slot] != lb)
                        {
                            decode(timeline, ib, lb, columns, offset);
                            shortKeys[slot] = ib;
                            longKeys[slot] = lb;
                            shortFlags[slot] = timeline.shortFlagsAt(ib);
                            longFlags[slot] = timeline.longFlagsAt(lb);
                        }
                        distance(row, rowShort, rowLong, columns, offset,
                                shortFlags[slot], longFlags[slot], rotation, distances);
                        best = previous[predecessor + 2] + Math.max(distances[0], distances[1]);
                        bestH = previous[predecessor] + distances[0];
                        bestT = previous[predecessor + 1] + distances[1];
                    }
                }
                if (i > 0 && j >= previousStart && j <= previousEnd)
                {
                    int predecessor = 3 * (j - previousStart);
                    double candidate = previous[predecessor + 2] + 0.5d;
                    if (candidate < best - TIE)
                    {
                        best = candidate;
                        bestH = previous[predecessor] + 0.5d;
                        bestT = previous[predecessor + 1] + 0.5d;
                    }
                }
                if (j > currentStart)
                {
                    int predecessor = destination - 3;
                    double candidate = current[predecessor + 2] + 0.5d;
                    if (candidate < best - TIE)
                    {
                        best = candidate;
                        bestH = current[predecessor] + 0.5d;
                        bestT = current[predecessor + 1] + 0.5d;
                    }
                }
                current[destination] = bestH;
                current[destination + 1] = bestT;
                current[destination + 2] = best;
            }
            double[] swap = previous;
            previous = current;
            current = swap;
            previousStart = currentStart;
            previousEnd = currentEnd;
        }
        int terminal = 3 * (n - previousStart);
        if (!Double.isFinite(previous[terminal + 2])) return rejected(SimilarityRejectionReason.AMBIGUOUS, 0, rotation);
        double rho = validBins / 32.0d;
        double h = rho * clamp(1.0d - previous[terminal] / n - (rotation == 0 ? 0.0d : 0.05d));
        double t = rho * clamp(1.0d - previous[terminal + 1] / n);
        double alignment = rho * clamp(1.0d - previous[terminal + 2] / n);
        return new SimilarityScore(h, t, alignment, Math.min(h, Math.min(t + 0.05d, alignment + 0.03d)),
                rotation, validBins, SimilarityRejectionReason.NONE);
    }

    private static int firstCell(long start, int fs, int rate)
    {
        return Math.toIntExact(ceilDiv(Math.multiplyExact(Math.addExact(start, 1L), rate), fs) - 1L);
    }

    private static int endCell(long end, int fs, int rate)
    {
        return Math.toIntExact(ceilDiv(Math.multiplyExact(end, rate), fs));
    }

    private static long ceilDiv(long value, long divisor)
    {
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }

    private static int lifted(int first, int count, int k, int n)
    {
        return Math.addExact(first, Math.toIntExact(Math.multiplyExact((long) k, count) / n));
    }

    private static long center(int index, int rate, AudioFormat source)
    {
        long start = Math.multiplyExact((long) index, source.sampleRateHz()) / rate;
        long end = Math.min(source.frames(), Math.multiplyExact(index + 1L, source.sampleRateHz()) / rate);
        return start + (end - start - 1L) / 2L;
    }

    private static int nearestLong(ComparisonTimeline timeline, int shortIndex)
    {
        AudioFormat source = timeline.format();
        long shortCenter = center(shortIndex, 40, source);
        int containing = Math.toIntExact(Math.multiplyExact(shortCenter, 8L) / source.sampleRateHz());
        containing = Math.min(timeline.longCount() - 1, containing);
        int best = -1;
        long distance = Long.MAX_VALUE;
        for (int candidate = Math.max(0, containing - 1); candidate <= Math.min(timeline.longCount() - 1, containing + 1); candidate++)
        {
            long delta = StrictMath.abs(center(candidate, 8, source) - shortCenter);
            if (delta < distance)
            {
                distance = delta;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean eligible(int i, int j, long limit, long framesA, long framesB)
    {
        long lag = Math.multiplyExact(2L, StrictMath.abs((long) i - j));
        return Math.multiplyExact(lag, framesA) <= limit && Math.multiplyExact(lag, framesB) <= limit;
    }

    private static void decodeTone(ComparisonTimeline timeline, boolean longer, int index, double[] values, int offset)
    {
        long sum = 0L;
        for (int j = 0; j < 36; j++)
        {
            int value = (longer ? timeline.longValueAt(index, j) : timeline.shortValueAt(index, j)) & 0xFFFF;
            values[offset + j] = value;
            sum += value;
        }
        if (sum > 0L) for (int j = 0; j < 36; j++) values[offset + j] /= sum;
    }

    private static void decode(ComparisonTimeline timeline, int shortIndex, int longIndex, double[] values, int offset)
    {
        decodeTone(timeline, false, shortIndex, values, offset);
        for (int j = 0; j < 8; j++)
        {
            double shape = timeline.shortValueAt(shortIndex, 36 + j) / 256.0d;
            values[offset + 36 + j] = shape;
            values[offset + 44 + j] = timeline.shortValueAt(shortIndex, 44 + j) / 256.0d;
            values[offset + 52 + j] = StrictMath.pow(10.0d, shape / 10.0d);
        }
        decodeTone(timeline, true, longIndex, values, offset + 60);
    }

    private static double tonalDistance(double[] first, int a, double[] second, int b, int rotation)
    {
        double distance = 0.0d;
        for (int j = 0; j < 36; j++) distance += StrictMath.abs(first[a + j] - second[b + (j + 3 * rotation) % 36]);
        return 0.5d * distance;
    }

    private static boolean spectral(byte flags) { return (flags & 3) == 3; }
    private static boolean tonal(byte flags) { return (flags & 7) == 7; }

    private static boolean validPair(byte as, byte al, byte bs, byte bl)
    {
        return spectral(as) && spectral(bs) && ((tonal(as) && tonal(bs)) || (tonal(al) && tonal(bl)));
    }

    private static void distance(double[] first, byte as, byte al, double[] second, int offset,
                                  byte bs, byte bl, int rotation, double[] result)
    {
        if (!validPair(as, al, bs, bl))
        {
            result[0] = result[1] = 1.0d;
            return;
        }
        double shortDistance = tonal(as) && tonal(bs) ? tonalDistance(first, 0, second, offset, rotation)
                : tonal(as) || tonal(bs) ? 1.0d : 0.0d;
        double longDistance = tonal(al) && tonal(bl) ? tonalDistance(first, 60, second, offset + 60, rotation)
                : tonal(al) || tonal(bl) ? 1.0d : 0.0d;
        result[0] = Math.max(shortDistance, longDistance);
        double weightSum = 0.0d;
        for (int j = 0; j < 8; j++) weightSum += Math.max(first[52 + j], second[offset + 52 + j]);
        double squared = 0.0d;
        for (int j = 0; j < 8; j++)
        {
            double weight = Math.max(first[52 + j], second[offset + 52 + j]) / weightSum;
            double shape = first[36 + j] - second[offset + 36 + j];
            double contrast = first[44 + j] - second[offset + 44 + j];
            squared += 0.5d * weight * (shape * shape + contrast * contrast);
        }
        result[1] = 1.0d - StrictMath.exp(-squared / (2.0d * POWER_DOUBLING_DB * POWER_DOUBLING_DB));
    }

    private static boolean cancelled(CancellationToken token) { return token != null && token.isCancelled(); }
    private static double clamp(double value) { return Math.max(0.0d, Math.min(1.0d, value)); }
    private static SimilarityScore rejected(SimilarityRejectionReason reason, int count, int rotation)
    {
        return new SimilarityScore(0.0d, 0.0d, 0.0d, 0.0d, rotation, count, reason);
    }
}
