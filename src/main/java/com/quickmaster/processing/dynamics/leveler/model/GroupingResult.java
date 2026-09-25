package com.quickmaster.processing.dynamics.leveler.model;

/** Disjoint accepted complete-link groups and strict pairs. */
public final class GroupingResult
{
    private final FrozenList<ComparableGroup> groups;
    private final FrozenList<ComparablePair> pairs;

    public GroupingResult(FrozenList<ComparableGroup> groups, FrozenList<ComparablePair> pairs)
    {
        if (groups == null || pairs == null)
        {
            throw new IllegalArgumentException("Grouping lists must not be null.");
        }
        long used = 0L;
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++)
        {
            ComparableGroup group = groups.get(groupIndex);
            for (int memberIndex = 0; memberIndex < group.size(); memberIndex++)
            {
                int ordinal = group.memberOrdinalAt(memberIndex);
                long bit = 1L << ordinal;
                if ((used & bit) != 0L) throw new IllegalArgumentException("Grouping is not disjoint.");
                used |= bit;
            }
        }
        for (int pairIndex = 0; pairIndex < pairs.size(); pairIndex++)
        {
            ComparablePair pair = pairs.get(pairIndex);
            long firstBit = 1L << pair.firstOrdinal();
            long secondBit = 1L << pair.secondOrdinal();
            if ((used & firstBit) != 0L || (used & secondBit) != 0L)
            {
                throw new IllegalArgumentException("Grouping is not disjoint.");
            }
            used |= firstBit | secondBit;
        }
        this.groups = groups;
        this.pairs = pairs;
    }

    public FrozenList<ComparableGroup> groups() { return groups; }
    public FrozenList<ComparablePair> pairs() { return pairs; }
}
