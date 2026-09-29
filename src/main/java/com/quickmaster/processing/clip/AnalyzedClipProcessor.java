package com.quickmaster.processing.clip;

import com.quickmaster.processing.AudioProcessor;
import com.quickmaster.processing.OfflineMetering;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import java.util.concurrent.CancellationException;

/**
 * Offline-calibrated zero-latency waveshaper. DSPark owns the curve and the
 * pipeline owns oversampling. No hidden slew, channel drift or body filter.
 * Solving in units of the source peak preserves float headroom and quiet audio.
 */
abstract class AnalyzedClipProcessor implements AudioProcessor, OfflineMetering {
    static final int HOP = 1024;
    private record Features(double peak, double positive, double negative,
                            float[] hopPositive, float[] hopNegative, int rate, int frames) { }
    private static final Features EMPTY = new Features(0,0,0,new float[0],new float[0],48000,0);
    private volatile Features features = EMPTY;
    private volatile float[] grEnv = new float[0];
    private volatile double reductionDb = 1;
    private volatile boolean enabled;
    private volatile double normalizedCeiling = 1;
    private int preparedRate = 48000;
    private long position;
    private float[] renderedInput, renderedOutput;

    /** Signed monotone contract: |shape(x,c)| <= |x|. */
    protected abstract double shape(double sample, double ceiling);

    protected final double reductionDb() { return reductionDb; }
    protected final void setReductionDb(double db) {
        if (!Double.isFinite(db)) return;
        reductionDb = Math.max(0,Math.min(12,db));
        recompute();
    }

    protected final void recompute() {
        Features f = features;
        if (f.peak == 0 || reductionDb == 0) {
            normalizedCeiling = 1;
            grEnv = new float[f.hopPositive.length];
            return;
        }
        double target = Math.pow(10,-reductionDb/20);
        double positive = f.positive/f.peak, negative = f.negative/f.peak;
        // Tanh needs an unbounded ceiling as reduction tends to zero. A fixed
        // 2*peak bracket introduces a discontinuity at bypass.
        double lo=0, hi=2;
        while (outputPeak(positive,negative,hi) < target && hi < 0x1p40) hi*=2;
        for (int i=0;i<64;i++) {
            double mid=(lo+hi)*.5;
            if (outputPeak(positive,negative,mid)>target) hi=mid; else lo=mid;
        }
        normalizedCeiling = (lo+hi)*.5;
        float[] envelope = new float[f.hopPositive.length];
        for(int h=0;h<envelope.length;h++) {
            double in=Math.max(f.hopPositive[h],f.hopNegative[h]);
            double out=outputPeak(f.hopPositive[h]/f.peak,f.hopNegative[h]/f.peak,
                    normalizedCeiling)*f.peak;
            envelope[h]=reduction(in,out);
        }
        grEnv=envelope;
    }

    private double outputPeak(double positive,double negative,double ceiling) {
        return Math.max(shape(positive,ceiling),-shape(-negative,ceiling));
    }
    public final double getCachedPeak() { return features.peak; }
    public final void setAnalyzedPeak(double peak) {
        if (!Double.isFinite(peak) || peak < 0) throw new IllegalArgumentException("Invalid clip peak.");
        // Peak alone cannot reconstruct polarity or waveform. Do not retain an
        // old track's meter. Full analysis adoption is preferred.
        features = new Features(peak,peak,peak,new float[0],new float[0],preparedRate,0);
        recompute();
    }
    protected final void adopt(AnalyzedClipProcessor other, boolean sameControls) {
        features=other.features;
        recompute();
        if(sameControls) grEnv=other.grEnv;
    }
    /** Peak input/output reduction of this stage, not a knob-capped estimate. */
    public final double getGrAtPosition(long baseFrame) {
        float[] env=grEnv;
        if(!enabled || reductionDb==0 || baseFrame<0 || baseFrame>=features.frames) return 0;
        long h=baseFrame/HOP;
        return h<env.length?env[(int)h]:0;
    }
    @Override public final void prepare(int sampleRate,long totalSamples) {
        if(sampleRate<=0 || totalSamples<0) throw new IllegalArgumentException("Invalid clip preparation.");
        preparedRate=sampleRate;position=0;
        // Keep the measured meter on the final base-rate reset after OS.
    }
    @Override public final boolean usesAnalysis() { return true; }
    @Override public final void setPlaybackPosition(long frame) { position=frame; }
    @Override public final void analyze(float[] input,int channels) {
        analyze(input,channels,null);
    }
    @Override public final void analyze(float[] input,int channels,CancellationToken cancellation) {
        features=EMPTY;grEnv=new float[0];
        validate(input,channels);
        int frames=input.length/channels;
        int hops=(int)(((long)frames+HOP-1)/HOP);
        float[] positive=new float[hops],negative=new float[hops];
        double maxPositive=0,maxNegative=0;
        for(int f=0;f<frames;f++) {
            if((f&16383)==0 && (Thread.currentThread().isInterrupted()
                    || cancellation!=null && cancellation.isCancelled())) throw new CancellationException();
            int h=f/HOP;
            for(int c=0;c<channels;c++) {
                float v=input[f*channels+c];
                if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite clip input.");
                if(v>positive[h])positive[h]=v;
                if(-v>negative[h])negative[h]=-v;
                maxPositive=Math.max(maxPositive,v);maxNegative=Math.max(maxNegative,-v);
            }
        }
        features=new Features(Math.max(maxPositive,maxNegative),maxPositive,maxNegative,
                positive,negative,preparedRate,frames);
        recompute();position=0;
    }
    @Override public final float[] process(float[] buffer,int channels) {
        validate(buffer,channels);
        Features f=features;
        int frames=buffer.length/channels;
        if(!enabled || reductionDb==0 || f.peak==0) { position+=frames;return buffer; }
        double ceiling=normalizedCeiling, scale=f.peak;
        double ratio=f.rate/(double)preparedRate;
        float[] meterIn=renderedInput,meterOut=renderedOutput;
        for(int frame=0;frame<frames;frame++) {
            double source=(position+frame)*ratio;
            int hop=(source>=0 && source<f.frames)?(int)(source/HOP):-1;
            float inPeak=0,outPeak=0;
            for(int c=0;c<channels;c++) {
                int index=frame*channels+c;
                float in=buffer[index];
                if(!Float.isFinite(in)) throw new IllegalArgumentException("Non-finite clip input.");
                float out=(float)(scale*shape(in/scale,ceiling));
                buffer[index]=out;
                inPeak=Math.max(inPeak,Math.abs(in));outPeak=Math.max(outPeak,Math.abs(out));
            }
            if(meterIn!=null && hop>=0 && hop<meterIn.length) {
                meterIn[hop]=Math.max(meterIn[hop],inPeak);
                meterOut[hop]=Math.max(meterOut[hop],outPeak);
            }
        }
        position+=frames;
        return buffer;
    }
    @Override public final void beginOfflineMetering(int channels) {
        renderedInput=new float[features.hopPositive.length];
        renderedOutput=new float[renderedInput.length];
    }
    @Override public final void endOfflineMetering() {
        if(renderedInput==null) return;
        float[] envelope=new float[renderedInput.length];
        for(int h=0;h<envelope.length;h++) envelope[h]=reduction(renderedInput[h],renderedOutput[h]);
        renderedInput=renderedOutput=null;
        grEnv=envelope;
    }
    @Override public final boolean isEnabled() { return enabled; }
    @Override public final void setEnabled(boolean enabled) { this.enabled=enabled; }
    private static float reduction(double in,double out) {
        return in>0 && out>0?(float)(20*Math.log10(out/in)):0;
    }
    private static void validate(float[] samples,int channels) {
        if(samples==null || (channels!=1 && channels!=2) || samples.length%channels!=0)
            throw new IllegalArgumentException("Require complete mono or stereo clip frames.");
    }
}
