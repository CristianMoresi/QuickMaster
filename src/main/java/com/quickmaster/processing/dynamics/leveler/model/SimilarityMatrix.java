package com.quickmaster.processing.dynamics.leveler.model;

/** Upper-triangle pair scores in (i,j), i&lt;j order. */
public final class SimilarityMatrix
{
    private final int segmentCount;
    private final FrozenList<SimilarityScore> scores;

    public SimilarityMatrix(int segmentCount, FrozenList<SimilarityScore> scores)
    {
        if (segmentCount < 0 || segmentCount > 64 || scores == null)
        {
            throw new IllegalArgumentException("Invalid similarity matrix dimensions.");
        }
        int expected = Math.multiplyExact(segmentCount, segmentCount - 1) / 2;
        if (scores.size() != expected)
        {
            throw new IllegalArgumentException("Similarity matrix triangle is incomplete.");
        }
        this.segmentCount = segmentCount;
        this.scores = scores;
    }

    public int segmentCount() { return segmentCount; }
    public FrozenList<SimilarityScore> scores() { return scores; }

    public SimilarityScore scoreAt(int first, int second)
    {
        if (first < 0 || second < 0 || first >= segmentCount || second >= segmentCount
                || first == second)
        {
            throw new IllegalArgumentException("Matrix pair must be distinct and in range.");
        }
        int i = first;
        int j = second;
        if (i > j)
        {
            int temporary = i;
            i = j;
            j = temporary;
        }
        int index = i * (2 * segmentCount - i - 1) / 2 + (j - i - 1);
        return scores.get(index);
    }
}
