package com.quickmaster.processing.clip;

import com.dspark.effects.Clipper;

/**
 * DSPark's hard clamp or tanh curve, calibrated to a base-rate program-peak
 * reduction. HARD is identity below threshold; SOFT bends the body too.
 * Zero is bit-exact bypass. The pipeline's explicit oversampling controls
 * aliasing; neither curve promises alias-free 1x audio.
 */
public final class HardClipProcessor extends AnalyzedClipProcessor {
    public static final double DEFAULT_CLIP_DB=1, MIN_CLIP_DB=0, MAX_CLIP_DB=12;
    public enum Curve { HARD, SOFT }
    private final Clipper kernel=new Clipper();
    private volatile Curve curve=Curve.HARD;

    public double getClipDb() { return reductionDb(); }
    public void setClipDb(double db) { setReductionDb(db); }
    public Curve getCurve() { return curve; }
    public void setCurve(Curve curve) {
        if(curve==null) return;
        this.curve=curve;
        kernel.setMode(curve==Curve.HARD?Clipper.Mode.HARD:Clipper.Mode.SOFT);
        recompute();
    }
    @Override protected double shape(double sample,double ceiling) { return kernel.shapeSample(sample,ceiling); }
    public void adoptAnalysis(HardClipProcessor other) {
        adopt(other,other.curve==curve && other.getClipDb()==getClipDb());
    }
}
