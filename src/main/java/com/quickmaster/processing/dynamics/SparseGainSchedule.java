package com.quickmaster.processing.dynamics;

import com.quickmaster.processing.dynamics.leveler.model.GainPiece;
import com.quickmaster.processing.dynamics.leveler.model.GainPieceShape;

/** Immutable bounded source-clock gain; uncovered source frames are exactly unity. */
public final class SparseGainSchedule implements GainSchedule {
    private final int sourceRateHz;
    private final long sourceFrames;
    private final GainPiece[] pieces;

    public SparseGainSchedule(int sourceRateHz, long sourceFrames, GainPiece[] pieces) {
        if (sourceRateHz <= 0 || sourceFrames <= 0 || pieces == null || pieces.length > 258)
            throw new IllegalArgumentException("Invalid sparse gain format or capacity.");
        double previousEnd = 0;
        for (GainPiece piece : pieces) {
            if (piece == null || !Double.isFinite(piece.startFrame()) || !Double.isFinite(piece.endFrame())
                    || piece.startFrame() < previousEnd || piece.endFrame() <= piece.startFrame()
                    || piece.endFrame() > sourceFrames || !validGain(piece.startDb()) || !validGain(piece.endDb())
                    || piece.shape() == null || (piece.shape() == GainPieceShape.HOLD && piece.startDb() != piece.endDb()))
                throw new IllegalArgumentException("Invalid, unordered or overlapping sparse gain piece.");
            previousEnd = piece.endFrame();
        }
        this.sourceRateHz = sourceRateHz;
        this.sourceFrames = sourceFrames;
        this.pieces = pieces.clone();
    }

    public static SparseGainSchedule unit(int sourceRateHz, long sourceFrames) {
        return new SparseGainSchedule(sourceRateHz, sourceFrames, new GainPiece[0]);
    }

    @Override public GainDomain domain() { return GainDomain.SPARSE_DB; }
    @Override public long sourceFrames() { return sourceFrames; }
    public int sourceRateHz() { return sourceRateHz; }
    public int pieceCount() { return pieces.length; }
    public GainPiece pieceAt(int index) { return pieces[index]; }
    public boolean isUnit() { return pieces.length == 0; }

    /** First piece whose right edge is strictly after this position, or pieceCount(). */
    public int pieceIndexAt(double sourcePosition) {
        if (Double.isNaN(sourcePosition)) return pieces.length;
        int low = 0, high = pieces.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (pieces[middle].endFrame() <= sourcePosition) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    public double gainDbAt(double sourcePosition) {
        return gainDbAtPiece(sourcePosition, pieceIndexAt(sourcePosition));
    }

    double gainDbAtPiece(double sourcePosition, int index) {
        if (!Double.isFinite(sourcePosition) || sourcePosition < 0 || sourcePosition >= sourceFrames
                || index < 0 || index >= pieces.length) return 0;
        GainPiece piece = pieces[index];
        if (sourcePosition < piece.startFrame() || sourcePosition >= piece.endFrame()) return 0;
        double gain;
        if (piece.shape() == GainPieceShape.HOLD) gain = piece.startDb();
        else {
            double u = (sourcePosition - piece.startFrame()) / (piece.endFrame() - piece.startFrame());
            gain = piece.startDb() + (piece.endDb() - piece.startDb()) * (3 * u * u - 2 * u * u * u);
        }
        return gain == 0 ? 0 : gain;
    }

    private static boolean validGain(double gain) {
        return Double.isFinite(gain) && gain >= -6 && gain <= 3;
    }
}
