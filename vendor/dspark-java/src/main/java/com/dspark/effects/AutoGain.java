package com.dspark.effects;

import java.util.Arrays;

/**
 * DSPark C++ Effects/AutoGain.h (474b7d1): K-weighted/flat measurement,
 * 400 ms integration, block-adaptive smoothing and stereo-linked gain.
 * Interleaved float audio; filter and control arithmetic follow the double
 * specialization. Setup/reset and processing have one owner; setters and
 * published gain use volatile publication for the control/UI thread.
 *
 * <p>{@link #offlineGainDb} is the explicit offline extension: the same
 * weighting and matching rule over a complete, time-aligned pair, yielding
 * ONE gain rather than a time-varying envelope. It is not a peak limiter.
 */
public final class AutoGain {
    public enum Weighting { K_WEIGHTED, FLAT }
    private double sampleRate=44100;
    private int channels;
    private volatile double smoothTimeSecs=.1, maxCompensationDb=12, publishedCompensationDb;
    private volatile Weighting weighting=Weighting.K_WEIGHTED;
    private double refMeanSquare, outMeanSquare, compensationDb;
    private Meter reference, output;

    public void prepare(double rate,int channels) {
        if(!Double.isFinite(rate)||rate<=0||channels<=0)return;
        sampleRate=rate;this.channels=channels;
        reference=new Meter(rate,channels);output=new Meter(rate,channels);reset();
    }
    public void reset() {
        refMeanSquare=outMeanSquare=compensationDb=publishedCompensationDb=0;
        if(reference!=null){reference.reset();output.reset();}
    }
    public void setWeighting(Weighting value){if(value!=null)weighting=value;}
    public Weighting getWeighting(){return weighting;}
    public void setMaxCompensationDb(double value){if(Double.isFinite(value))maxCompensationDb=Math.abs(value);}
    public double getMaxCompensationDb(){return maxCompensationDb;}
    public void setSmoothingTimeMs(double value){if(Double.isFinite(value))smoothTimeSecs=Math.max(value*.001,.001);}
    public double getSmoothingTimeMs(){return smoothTimeSecs*1000;}
    public double getCompensationDb(){return publishedCompensationDb;}

    public void pushReference(float[] buffer,int ch) {
        if(!valid(buffer,ch))return;
        refMeanSquare=integrate(refMeanSquare,reference,buffer,ch);
    }
    public void compensate(float[] buffer,int ch) {
        if(!valid(buffer,ch))return;
        outMeanSquare=integrate(outMeanSquare,output,buffer,ch);
        double target=match(refMeanSquare,outMeanSquare,maxCompensationDb);
        int frames=buffer.length/ch;
        double alpha=Math.exp(-frames/(sampleRate*smoothTimeSecs));
        double end=target+(compensationDb-target)*alpha;
        double startGain=Math.pow(10,compensationDb/20),endGain=Math.pow(10,end/20);
        double step=(endGain-startGain)/frames;
        for(int f=0;f<frames;f++)for(int c=0;c<Math.min(ch,channels);c++)
            buffer[f*ch+c]=(float)(buffer[f*ch+c]*(startGain+f*step));
        compensationDb=end;publishedCompensationDb=end;
    }
    private boolean valid(float[] pcm,int ch){return channels>0&&pcm!=null&&ch>0&&pcm.length>=ch;}
    private double integrate(double previous,Meter meter,float[] pcm,int ch) {
        double ms=meter.meanSquare(pcm,ch,weighting,null);
        if(!Double.isFinite(ms))return previous;
        double a=Math.exp(-(pcm.length/ch)/(sampleRate*.4));
        return ms+(previous-ms)*a;
    }
    private static double match(double ref,double out,double max) {
        double a=10*Math.log10(Math.max(ref,1e-15)),b=10*Math.log10(Math.max(out,1e-15));
        return a< -90&&b< -90?0:Math.max(-max,Math.min(max,a-b));
    }

    /**
     * Fixed full-file compensation. Inputs must be aligned and have equal
     * lengths; both are read-only. Silence rules and range match the C++ core.
     * checkCancelled is optional and invoked at bounded intervals.
     */
    public double offlineGainDb(float[] dry,float[] wet,int ch,double rate,Runnable checkCancelled) {
        if(dry==null||wet==null||dry.length!=wet.length||ch<1||dry.length%ch!=0||!Double.isFinite(rate)||rate<=0)
            throw new IllegalArgumentException("Require aligned complete audio and a positive rate");
        if(dry.length==0)return 0;
        Weighting w=weighting;
        double a=new Meter(rate,ch).meanSquare(dry,ch,w,checkCancelled);
        double b=new Meter(rate,ch).meanSquare(wet,ch,w,checkCancelled);
        if(!Double.isFinite(a)||!Double.isFinite(b))throw new IllegalArgumentException("Non-finite auto-gain audio");
        return match(a,b,maxCompensationDb);
    }

    /** Exact C++ K-weighting coefficients and double TDF-II state. */
    private static final class Meter {
        final int channels;final double[] shelf,highPass;final double[][] states;
        Meter(double rate,int channels) {
            this.channels=channels;states=new double[channels][4];
            double k=Math.tan(Math.PI*1681.9744509555319/rate),q=.7071752369554196;
            double vh=Math.pow(10,3.999843853973347/20),vb=Math.pow(vh,.4996667741545416),a=1+k/q+k*k;
            shelf=new double[]{(vh+vb*k/q+k*k)/a,2*(k*k-vh)/a,(vh-vb*k/q+k*k)/a,2*(k*k-1)/a,(1-k/q+k*k)/a};
            k=Math.tan(Math.PI*38.13547087602444/rate);q=.5003270373238773;a=1+k/q+k*k;
            highPass=new double[]{1,-2,1,2*(k*k-1)/a,(1-k/q+k*k)/a};
        }
        void reset(){for(double[] s:states)Arrays.fill(s,0);}
        static double filter(double x,double[] c,double[] s,int i) {
            double y=c[0]*x+s[i];s[i]=c[1]*x-c[3]*y+s[i+1];s[i+1]=c[2]*x-c[4]*y;return y;
        }
        double meanSquare(float[] pcm,int ch,Weighting w,Runnable check) {
            int n=Math.min(ch,channels),frames=pcm.length/ch;double sum=0;
            for(int c=0;c<n;c++)for(int f=0;f<frames;f++) {
                if(check!=null&&(f&8191)==0)check.run();
                double x=pcm[f*ch+c];if(w==Weighting.K_WEIGHTED)x=filter(filter(x,shelf,states[c],0),highPass,states[c],2);
                sum+=x*x;
            }
            double result=sum/((double)frames*n);if(!Double.isFinite(result))reset();return result;
        }
    }
}
