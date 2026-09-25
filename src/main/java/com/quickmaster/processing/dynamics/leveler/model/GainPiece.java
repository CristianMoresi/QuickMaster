package com.quickmaster.processing.dynamics.leveler.model;

/** One immutable half-open piece in source-frame coordinates and dB. */
public final class GainPiece {
    private final double startFrame,endFrame,startDb,endDb;
    private final GainPieceShape shape;
    public GainPiece(double start,double end,double g0,double g1,GainPieceShape shape) {
        if(!Double.isFinite(start)||!Double.isFinite(end)||!Double.isFinite(g0)||!Double.isFinite(g1)
                ||start<0||end<=start||g0< -6||g0>3||g1< -6||g1>3||shape==null
                ||(shape==GainPieceShape.HOLD&&g0!=g1))throw new IllegalArgumentException("Invalid gain piece.");
        startFrame=start==0?0:start;endFrame=end;startDb=g0==0?0:g0;endDb=g1==0?0:g1;this.shape=shape;
    }
    public double startFrame(){return startFrame;}
    public double endFrame(){return endFrame;}
    public double startDb(){return startDb;}
    public double endDb(){return endDb;}
    public GainPieceShape shape(){return shape;}
}
