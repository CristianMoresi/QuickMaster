package com.quickmaster.processing.limit;

import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Source-aligned offline form of a minimum-hold / double-box lookahead limiter.
 * For an attack span L, hold[n] is min(required[n-L..n]). A one-pole release
 * never exceeds that hold. Two moving averages span exactly L, so their output
 * at n+L cannot exceed required[n]. This gives a smooth, full-duration attack
 * without clipping, and a release time constant measured in samples, not dB.
 * O(frames) time and O(lookahead) scratch, in addition to the returned envelope.
 */
public final class OfflineLimiterEnvelope {
    private OfflineLimiterEnvelope() { }

    public static float[] compute(float[] peaks, double threshold, int attack, int release) {
        if (!Double.isFinite(threshold) || threshold <= 0)
            throw new IllegalArgumentException("Limiter threshold must be finite and positive.");
        int frames=peaks.length;
        float[] result=new float[frames];
        if(frames==0)return result;
        int span=Math.max(1,attack), a=span/2+1, b=span-(a-1)+1;
        double[] boxA=new double[a], boxB=new double[b];
        Arrays.fill(boxA,1);Arrays.fill(boxB,1);
        int capacity=span+2, head=0, tail=0;
        int[] indices=new int[capacity];double[] values=new double[capacity];
        double sumA=a,sumB=b,envelope=1;
        double recovery=-Math.expm1(-1.0/Math.max(1,release));
        int end=Math.addExact(frames,span);
        for(int n=0;n<end;n++) {
            if((n&16383)==0 && Thread.currentThread().isInterrupted())throw new CancellationException();
            double peak=n<frames?peaks[n]:0;
            if(!Double.isFinite(peak)||peak<0)throw new IllegalArgumentException("Invalid limiter peak.");
            double required=peak>threshold?threshold/peak:1;
            while(head!=tail && indices[head]<n-span)head=(head+1)%capacity;
            while(head!=tail && values[(tail+capacity-1)%capacity]>=required)tail=(tail+capacity-1)%capacity;
            indices[tail]=n;values[tail]=required;tail=(tail+1)%capacity;
            double held=values[head];
            envelope=Math.min(held,envelope+recovery*(held-envelope));
            int ia=n%a,ib=n%b;
            sumA+=envelope-boxA[ia];boxA[ia]=envelope;
            double first=sumA/a;
            sumB+=first-boxB[ib];boxB[ib]=first;
            if((n&65535)==65535) {
                sumA=0;for(double v:boxA)sumA+=v;
                sumB=0;for(double v:boxB)sumB+=v;
            }
            int frame=n-span;
            if(frame>=0) {
                double bound=peaks[frame]>threshold?threshold/peaks[frame]:1;
                // Only floating-point drift is projected; the window proof is
                // independently tested before this rounding backstop.
                result[frame]=(float)Math.max(0,Math.min(bound,Math.min(1,sumB/b)));
            }
        }
        return result;
    }
}
