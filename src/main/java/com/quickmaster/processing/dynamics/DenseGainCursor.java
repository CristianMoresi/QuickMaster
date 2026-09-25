package com.quickmaster.processing.dynamics;

/** Mutable dense render cursor, constructed once and confined to the audio thread. */
final class DenseGainCursor
{
    private long generation = Long.MIN_VALUE;
    private long nextPreparedFrame = 0L;

    void invalidate(long preparedFrame)
    {
        generation = Long.MIN_VALUE;
        nextPreparedFrame = preparedFrame;
    }

    void align(long publicationGeneration, long preparedFrame)
    {
        if (generation != publicationGeneration || nextPreparedFrame != preparedFrame)
        {
            generation = publicationGeneration;
            nextPreparedFrame = preparedFrame;
        }
    }

    void advanceTo(long preparedFrame)
    {
        nextPreparedFrame = preparedFrame;
    }
}
