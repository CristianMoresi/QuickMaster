package com.quickmaster.processing.clip;

import com.dspark.effects.Clipper;

/**
 * DSPark's memoryless soft-clipping curves, calibrated to a reduction of the
 * song's peak: the sine knee, the tanh and the golden knee (a linear body up
 * to the golden ratio of the ceiling). No body filter, no channel drift;
 * oversampling is owned by the pipeline. Zero is a bit-exact bypass.
 */
public final class SoftClipProcessor extends AnalyzedClipProcessor {
    public static final double DEFAULT_SAT_DB=1, MIN_SAT_DB=0, MAX_SAT_DB=12;
    private final Clipper kernel=new Clipper();
    private volatile SaturationType curve=SaturationType.SINE;

    public SoftClipProcessor() { kernel.setMode(Clipper.Mode.ANALOG); }

    public double getSatDb() { return reductionDb(); }
    public void setSatDb(double db) { setReductionDb(db); }
    public SaturationType getCurve() { return curve; }
    /** One of the curve types; an analog model is ignored. */
    public void setCurve(SaturationType type) {
        if(type==null || !type.isCurve()) return;
        this.curve=type;
        kernel.setMode(switch(type) {
            case TANH -> Clipper.Mode.SOFT;
            case GOLDEN -> Clipper.Mode.GOLDEN_RATIO;
            default -> Clipper.Mode.ANALOG;
        });
        recompute();
    }
    @Override protected double shape(double sample,double ceiling) { return kernel.shapeSample(sample,ceiling); }
    public void adoptAnalysis(SoftClipProcessor other) {
        adopt(other,other.curve==curve && other.getSatDb()==getSatDb());
    }
}
