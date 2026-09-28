package com.quickmaster.processing.limit;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfflineLimiterEnvelopeTest {
    @Test void matchesIndependentBruteForceWindowOracle() {
        Random random=new Random(9028);
        for(int frames:new int[]{1,37,2000})for(int attack:new int[]{1,2,17,72}) {
            float[] peaks=new float[frames];for(int i=0;i<frames;i++)peaks[i]=random.nextFloat();
            float[] actual=OfflineLimiterEnvelope.compute(peaks,.4,attack,200);
            double[] expected=oracle(peaks,.4,attack,200);
            for(int i=0;i<frames;i++) {
                assertEquals(expected[i],actual[i],8e-8,"frame="+i+", attack="+attack);
                assertTrue(peaks[i]*actual[i]<=.40000005);
            }
        }
    }
    @Test void attackUsesFullLookaheadAndReleaseHasDeclaredTimeConstant() {
        for(int rate:new int[]{44100,48000,96000})for(double depth:new double[]{1,3,9,18}) {
            int attack=(int)(rate*.0015),release=(int)(rate*.080),peakAt=attack*3;
            float[] peaks=new float[peakAt+release*3];peaks[peakAt]=1;
            double threshold=Math.pow(10,-depth/20);
            float[] gain=OfflineLimiterEnvelope.compute(peaks,threshold,attack,release);
            assertEquals(1,gain[peakAt-attack-1],1e-7);
            assertTrue(gain[peakAt-attack/2]<1-(1-threshold)*.1,"Attack must not collapse to a few samples");
            assertEquals(threshold,gain[peakAt],1e-7);
            double normalizedRecovery=(gain[peakAt+release]-threshold)/(1-threshold);
            assertEquals(1-Math.exp(-1),normalizedRecovery,.012,"80 ms must mean one time constant at every depth/rate");
        }
    }
    @Test void silenceIsUnityAndNonFiniteDataCannotDisableLimiting() {
        for(float g:OfflineLimiterEnvelope.compute(new float[200],.5,72,3840))assertEquals(1,g,0);
        assertThrows(IllegalArgumentException.class,()->OfflineLimiterEnvelope.compute(new float[]{Float.NaN},.5,72,3840));
        assertThrows(IllegalArgumentException.class,()->OfflineLimiterEnvelope.compute(new float[]{1},Double.NaN,72,3840));
    }
    @Test void denseTransientTrainStillObeysExactOracleRatherThanArbitraryMakeupGain() {
        float[] peaks=new float[4800];for(int i=0;i<peaks.length;i++)peaks[i]=i%1200==0?.9f:.3f;
        float[] gain=OfflineLimiterEnvelope.compute(peaks,.9*Math.pow(10,-4.0/20),72,3840);
        double[] reference=oracle(peaks,.9*Math.pow(10,-4.0/20),72,3840);
        for(int i=0;i<peaks.length;i++)assertEquals(reference[i],gain[i],1e-7);
    }
    // Deliberately O(N*L): materialize every held/released frame and convolve
    // directly, independently of the production deque and running sums.
    static double[] oracle(float[] peaks,double threshold,int span,int release) {
        int count=peaks.length+span,a=span/2+1,b=span-(a-1)+1;
        double[] held=new double[count],first=new double[count],out=new double[peaks.length];
        double envelope=1,coefficient=1-Math.exp(-1.0/release);
        for(int n=0;n<count;n++) {
            double minimum=1;
            for(int k=Math.max(0,n-span);k<=n && k<peaks.length;k++)minimum=Math.min(minimum,peaks[k]>threshold?threshold/peaks[k]:1);
            envelope=Math.min(minimum,envelope+coefficient*(minimum-envelope));held[n]=envelope;
            double sum=0;for(int j=0;j<a;j++)sum+=n-j<0?1:held[n-j];first[n]=sum/a;
            if(n>=span){sum=0;for(int j=0;j<b;j++)sum+=n-j<0?1:first[n-j];out[n-span]=sum/b;}
        }
        return out;
    }
}
