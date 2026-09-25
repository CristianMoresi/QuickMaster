package com.quickmaster.processing.dynamics;

/** Audio-thread-owned seek cursor: one binary search on alignment, then sequential advancement. */
final class SparseGainCursor {
    private long generation = Long.MIN_VALUE;
    private long nextPreparedFrame;
    private int pieceIndex;

    void invalidate(long preparedFrame) {
        generation = Long.MIN_VALUE;
        nextPreparedFrame = preparedFrame;
        pieceIndex = 0;
    }

    void align(long publicationGeneration, long preparedFrame, SparseGainSchedule schedule, double sourcePosition) {
        if (generation != publicationGeneration || nextPreparedFrame != preparedFrame) {
            generation = publicationGeneration;
            nextPreparedFrame = preparedFrame;
            pieceIndex = schedule.pieceIndexAt(sourcePosition);
        }
    }

    double gainDbAt(SparseGainSchedule schedule, double sourcePosition) {
        if (!Double.isFinite(sourcePosition) || sourcePosition < 0 || sourcePosition >= schedule.sourceFrames()) return 0;
        while (pieceIndex < schedule.pieceCount() && schedule.pieceAt(pieceIndex).endFrame() <= sourcePosition)
            pieceIndex++;
        return schedule.gainDbAtPiece(sourcePosition, pieceIndex);
    }

    void advanceTo(long preparedFrame) { nextPreparedFrame = preparedFrame; }
}
