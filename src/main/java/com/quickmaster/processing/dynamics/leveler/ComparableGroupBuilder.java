package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.ComparableGroup;
import com.quickmaster.processing.dynamics.leveler.model.ComparablePair;
import com.quickmaster.processing.dynamics.leveler.model.FrozenList;
import com.quickmaster.processing.dynamics.leveler.model.GroupingResult;
import com.quickmaster.processing.dynamics.leveler.model.SegmentDescriptor;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityMatrix;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason;
import com.quickmaster.processing.dynamics.leveler.model.SimilarityScore;

/** Complete-link grouping plus a separate strict mutual-best pair route. */
public final class ComparableGroupBuilder
{
    public GroupingResult build(FrozenList<SegmentDescriptor> eligible,
                                SimilarityMatrix scores,
                                LevelerCalibrationProfile profile)
    {
        if (eligible == null || scores == null || profile == null
                || eligible.size() != scores.segmentCount())
        {
            throw new IllegalArgumentException("Invalid grouping input.");
        }
        int count = eligible.size();
        // Canonicalize IDs and the fixed matrix together; input position is not a SegmentId.
        int[] order = new int[count];
        for (int i = 0; i < count; i++)
        {
            int position = i;
            while (position > 0 && eligible.get(order[position - 1]).id().ordinal()
                    > eligible.get(i).id().ordinal())
            {
                order[position] = order[position - 1];
                position--;
            }
            order[position] = i;
        }
        Object[] orderedDescriptors = new Object[count];
        Object[] orderedScores = new Object[count * (count - 1) / 2];
        int scoreIndex = 0;
        for (int i = 0; i < count; i++)
        {
            orderedDescriptors[i] = eligible.get(order[i]);
            if (i > 0 && eligible.get(order[i - 1]).id().ordinal()
                    == eligible.get(order[i]).id().ordinal())
            {
                throw new IllegalArgumentException("Duplicate segment ID.");
            }
            for (int j = i + 1; j < count; j++) orderedScores[scoreIndex++] = scores.scoreAt(order[i], order[j]);
        }
        eligible = new FrozenList<SegmentDescriptor>(orderedDescriptors);
        scores = new SimilarityMatrix(count, new FrozenList<SimilarityScore>(orderedScores));
        int[][] members = new int[count][];
        int[] sizes = new int[count];
        boolean[] active = new boolean[count];
        for (int i = 0; i < count; i++)
        {
            members[i] = new int[count];
            members[i][0] = i;
            sizes[i] = 1;
            active[i] = true;
        }

        while (true)
        {
            int bestFirst = -1;
            int bestSecond = -1;
            double bestCohesion = -1.0d;
            int bestSize = -1;
            for (int first = 0; first < count; first++)
            {
                if (!active[first]) continue;
                for (int second = first + 1; second < count; second++)
                {
                    if (!active[second] || !canMerge(members[first], sizes[first],
                            members[second], sizes[second], scores, profile)) continue;
                    double cohesion = mergedCohesion(members[first], sizes[first],
                            members[second], sizes[second], scores);
                    int mergedSize = sizes[first] + sizes[second];
                    if (bestFirst < 0 || cohesion > bestCohesion + 1.0e-12d
                            || (StrictMath.abs(cohesion - bestCohesion) <= 1.0e-12d
                                && (mergedSize > bestSize
                                    || (mergedSize == bestSize && lexicographicallyBefore(
                                        members[first], sizes[first], members[second], sizes[second],
                                        members[bestFirst], sizes[bestFirst],
                                        members[bestSecond], sizes[bestSecond])))))
                    {
                        bestFirst = first;
                        bestSecond = second;
                        bestCohesion = cohesion;
                        bestSize = mergedSize;
                    }
                }
            }
            if (bestFirst < 0) break;
            merge(members[bestFirst], sizes[bestFirst], members[bestSecond], sizes[bestSecond]);
            sizes[bestFirst] += sizes[bestSecond];
            active[bestSecond] = false;
        }

        Object[] groupObjects = new Object[count];
        boolean[] used = new boolean[count];
        int groupCount = 0;
        for (int cluster = 0; cluster < count; cluster++)
        {
            if (!active[cluster] || sizes[cluster] < 3) continue;
            double cohesion = cohesion(members[cluster], sizes[cluster], scores);
            double external = external(members[cluster], sizes[cluster], count, scores);
            double separation = cohesion - external;
            if (separation < profile.groupSeparation()) continue;
            int[] ordinals = new int[sizes[cluster]];
            double[] quality = new double[sizes[cluster]];
            for (int i = 0; i < sizes[cluster]; i++)
            {
                int member = members[cluster][i];
                ordinals[i] = eligible.get(member).id().ordinal();
                quality[i] = clamp((minimumInternal(member, members[cluster], sizes[cluster], scores)
                        - profile.groupC()) / 0.10d, 0.0d, 1.0d);
                used[member] = true;
            }
            double confidence = smooth(clamp((cohesion - profile.groupC()) / 0.10d, 0.0d, 1.0d))
                    * smooth(clamp((separation - profile.groupSeparation()) / 0.12d, 0.0d, 1.0d));
            groupObjects[groupCount] = new ComparableGroup(groupCount, ordinals, quality, confidence);
            groupCount++;
        }

        int[] bestPartner = new int[count];
        boolean[] tied = new boolean[count];
        for (int i = 0; i < count; i++)
        {
            bestPartner[i] = -1;
            if (used[i]) continue;
            double best = -1.0d;
            for (int j = 0; j < count; j++)
            {
                if (j == i) continue;
                SimilarityScore score = scores.scoreAt(i, j);
                if (score.rejectionReason() != SimilarityRejectionReason.NONE) continue;
                if (score.c() > best + 1.0e-12d)
                {
                    best = score.c();
                    bestPartner[i] = j;
                    tied[i] = false;
                }
                else if (StrictMath.abs(score.c() - best) <= 1.0e-12d)
                {
                    tied[i] = true;
                }
            }
        }
        Object[] pairObjects = new Object[count / 2];
        int pairCount = 0;
        for (int first = 0; first < count; first++)
        {
            if (used[first] || tied[first]) continue;
            int second = bestPartner[first];
            if (second <= first || used[second] || tied[second] || bestPartner[second] != first) continue;
            SimilarityScore score = scores.scoreAt(first, second);
            if (score.h() < profile.pairH() || score.t() < profile.pairT()
                    || score.a() < profile.pairA() || score.c() < profile.pairC()) continue;
            double secondFirst = secondBest(first, second, count, scores);
            double secondSecond = secondBest(second, first, count, scores);
            double margin = Math.min(score.c() - secondFirst, score.c() - secondSecond);
            if (margin < profile.pairMargin()) continue;
            double confidence = smooth(clamp((score.c() - profile.pairC()) / 0.06d, 0.0d, 1.0d))
                    * smooth(clamp((margin - profile.pairMargin()) / 0.12d, 0.0d, 1.0d));
            pairObjects[pairCount++] = new ComparablePair(eligible.get(first).id().ordinal(),
                    eligible.get(second).id().ordinal(), confidence, margin);
            used[first] = true;
            used[second] = true;
        }

        Object[] compactGroups = new Object[groupCount];
        Object[] compactPairs = new Object[pairCount];
        System.arraycopy(groupObjects, 0, compactGroups, 0, groupCount);
        System.arraycopy(pairObjects, 0, compactPairs, 0, pairCount);
        return new GroupingResult(new FrozenList<ComparableGroup>(compactGroups),
                new FrozenList<ComparablePair>(compactPairs));
    }

    private static boolean canMerge(int[] first, int firstSize, int[] second, int secondSize,
                                    SimilarityMatrix matrix, LevelerCalibrationProfile profile)
    {
        for (int i = 0; i < firstSize; i++)
        {
            for (int j = 0; j < secondSize; j++)
            {
                SimilarityScore score = matrix.scoreAt(first[i], second[j]);
                if (score.rejectionReason() != SimilarityRejectionReason.NONE
                        || score.h() < profile.groupH() || score.t() < profile.groupT()
                        || score.a() < profile.groupA() || score.c() < profile.groupC()) return false;
            }
        }
        return true;
    }

    private static double mergedCohesion(int[] first, int firstSize, int[] second, int secondSize,
                                         SimilarityMatrix matrix)
    {
        double minimum = 1.0d;
        for (int i = 0; i < firstSize; i++)
        {
            for (int j = 0; j < secondSize; j++)
            {
                minimum = Math.min(minimum, matrix.scoreAt(first[i], second[j]).c());
            }
        }
        if (firstSize > 1) minimum = Math.min(minimum, cohesion(first, firstSize, matrix));
        if (secondSize > 1) minimum = Math.min(minimum, cohesion(second, secondSize, matrix));
        return minimum;
    }

    private static double cohesion(int[] members, int size, SimilarityMatrix matrix)
    {
        double minimum = 1.0d;
        for (int i = 0; i < size; i++)
        {
            for (int j = i + 1; j < size; j++) minimum = Math.min(minimum,
                    matrix.scoreAt(members[i], members[j]).c());
        }
        return minimum;
    }

    private static double external(int[] members, int size, int count, SimilarityMatrix matrix)
    {
        double maximum = 0.0d;
        for (int memberIndex = 0; memberIndex < size; memberIndex++)
        {
            int member = members[memberIndex];
            for (int other = 0; other < count; other++)
            {
                if (contains(members, size, other) || other == member) continue;
                SimilarityScore score = matrix.scoreAt(member, other);
                if (score.rejectionReason() == SimilarityRejectionReason.NONE)
                {
                    maximum = Math.max(maximum, score.c());
                }
            }
        }
        return maximum;
    }

    private static double minimumInternal(int member, int[] members, int size, SimilarityMatrix matrix)
    {
        double minimum = 1.0d;
        for (int i = 0; i < size; i++)
        {
            if (members[i] != member) minimum = Math.min(minimum, matrix.scoreAt(member, members[i]).c());
        }
        return minimum;
    }

    private static double secondBest(int member, int excluded, int count,
                                     SimilarityMatrix matrix)
    {
        double maximum = 0.0d;
        for (int other = 0; other < count; other++)
        {
            if (other == member || other == excluded) continue;
            SimilarityScore score = matrix.scoreAt(member, other);
            if (score.rejectionReason() == SimilarityRejectionReason.NONE)
            {
                maximum = Math.max(maximum, score.c());
            }
        }
        return maximum;
    }

    private static void merge(int[] first, int firstSize, int[] second, int secondSize)
    {
        int i = firstSize - 1;
        int j = secondSize - 1;
        int output = firstSize + secondSize - 1;
        while (i >= 0 && j >= 0)
        {
            if (first[i] > second[j]) first[output--] = first[i--];
            else first[output--] = second[j--];
        }
        while (j >= 0) first[output--] = second[j--];
    }

    private static boolean lexicographicallyBefore(int[] firstA, int sizeA, int[] secondA, int sizeSecondA,
                                                   int[] firstB, int sizeB, int[] secondB, int sizeSecondB)
    {
        int[] mergedA = new int[sizeA + sizeSecondA];
        int[] mergedB = new int[sizeB + sizeSecondB];
        System.arraycopy(firstA, 0, mergedA, 0, sizeA);
        merge(mergedA, sizeA, secondA, sizeSecondA);
        System.arraycopy(firstB, 0, mergedB, 0, sizeB);
        merge(mergedB, sizeB, secondB, sizeSecondB);
        int length = Math.min(mergedA.length, mergedB.length);
        for (int i = 0; i < length; i++)
        {
            if (mergedA[i] != mergedB[i]) return mergedA[i] < mergedB[i];
        }
        return mergedA.length < mergedB.length;
    }

    private static boolean contains(int[] members, int size, int value)
    {
        for (int i = 0; i < size; i++) if (members[i] == value) return true;
        return false;
    }

    private static double smooth(double value)
    {
        return value * value * (3.0d - 2.0d * value);
    }

    private static double clamp(double value, double minimum, double maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
