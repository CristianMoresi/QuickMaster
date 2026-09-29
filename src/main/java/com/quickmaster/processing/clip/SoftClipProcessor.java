package com.quickmaster.processing.clip;

import com.dspark.effects.Saturation;
import com.dspark.effects.Clipper;

/**
 * DSPark's symmetric soft-clipping curves, with no unrequested body filter or
 * channel drift. The old Java asymmetric rational voicings had unequal knees:
 * even an infinitesimal reduction on one polarity clipped the other noticeably.
 * Use the existing C++/Java sine, tanh and golden-ratio clipping kernels instead.
 * Legacy preset IDs are retained and explicitly mapped to the UI curve names;
 * these are not physical tube/tape/transformer emulations. Oversampling is owned
 * by the pipeline. Only GOLDEN_RATIO has a finite exactly-linear body region.
 */
public final class SoftClipProcessor extends AnalyzedClipProcessor {
    public static final double DEFAULT_SAT_DB=1, MIN_SAT_DB=0, MAX_SAT_DB=12;
    private final Clipper kernel=new Clipper();
    private volatile Saturation.Algorithm algorithm=Saturation.Algorithm.TUBE;

    public SoftClipProcessor() { kernel.setMode(Clipper.Mode.ANALOG); }

    public double getSatDb() { return reductionDb(); }
    public void setSatDb(double db) { setReductionDb(db); }
    public Saturation.Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Saturation.Algorithm algorithm) {
        if(algorithm==null) return;
        this.algorithm=algorithm;
        kernel.setMode(switch(algorithm) {
            case TUBE -> Clipper.Mode.ANALOG;
            case TAPE -> Clipper.Mode.SOFT;
            case TRANSFORMER -> Clipper.Mode.GOLDEN_RATIO;
        });
        recompute();
    }
    @Override protected double shape(double sample,double ceiling) { return kernel.shapeSample(sample,ceiling); }
    public void adoptAnalysis(SoftClipProcessor other) {
        adopt(other,other.algorithm==algorithm && other.getSatDb()==getSatDb());
    }
}
