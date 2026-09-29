package com.quickmaster.processing.dynamics;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class DynamicsAuditTest {
    @Test void punchDoesNotDiscardDetectedAttacksInQuietSections() {
        int rate=48000;float[] input=new float[rate*12];Random random=new Random(817);
        for(int k=0;k<24;k++) {
            int start=rate/5+k*rate/2;
            double amplitude=k<12?.025:.9;
            for(int j=0;j<384;j++)input[start+j]=(float)(amplitude*(2*random.nextDouble()-1)*Math.exp(-j/144.0));
        }
        var analysis=new com.quickmaster.processing.analysis.TrackAnalysis();analysis.analyze(input,1,rate);
        PunchProcessor punch=new PunchProcessor();punch.setTrackAnalysis(analysis);punch.setAmountDb(6);punch.setEnabled(true);
        punch.prepare(rate,input.length);punch.analyze(input,1);
        double[] times=analysis.getTransientTimesSec();
        assertTrue(Arrays.stream(times).filter(t->t<6).count()>=8,"quiet attacks must be detected");
        for(double t:times)assertEquals(6,punch.getGainDbAtPosition((long)(t*rate)),.00001,"onset "+t);
        float[] output=input.clone();punch.process(output,1);
        // Independent annotations are the actual generated bursts, not the
        // algorithm's own detections. Verify audible attack energy too.
        for(int k=0;k<24;k++) {
            int start=rate/5+k*rate/2;double inPower=0,outPower=0;
            for(int j=0;j<384;j++) {inPower+=(double)input[start+j]*input[start+j];outPower+=(double)output[start+j]*output[start+j];}
            assertEquals(6,10*Math.log10(outPower/inPower),.15,"known burst "+k);
        }
    }

    @Test void forwardMinimumMatchesBruteForceAtEveryBoundary() {
        Random random=new Random(314159);
        for(int n:new int[]{0,1,2,3,16,137})for(int look:new int[]{0,1,2,9,200}) {
            float[] a=new float[n];for(int i=0;i<n;i++)a[i]=random.nextFloat();
            float[] result=LookaheadGainSmoother.forwardSlidingMin(a,look);
            for(int i=0;i<n;i++) {
                float expected=a[i];for(int j=i+1;j<n&&j<=i+look;j++)expected=Math.min(expected,a[j]);
                assertEquals(expected,result[i],"n="+n+" look="+look+" index="+i);
            }
        }
    }

    @Test void peakCompContainsAnIsolatedPeakAtStartMiddleAndEnd() {
        for(int rate:new int[]{44100,48000,96000})for(int at:new int[]{0,1,rate/2,rate-1}) {
            float[] input=new float[rate];Arrays.fill(input,.01f);input[at]=1;
            PeakCompProcessor p=new PeakCompProcessor();p.setEnabled(true);p.setTargetDb(-6);
            p.prepare(rate,input.length);p.analyze(input,1);
            float[] out=input.clone();p.process(out,1);
            assertEquals(Math.pow(10,-6.0/20),out[at],1e-6,"rate="+rate+" peak="+at);
        }
    }

    @Test void dynamicsControlsIgnoreNonFiniteValues() {
        PeakCompProcessor peak=new PeakCompProcessor();peak.setTargetDb(-3);peak.setTargetDb(Double.NaN);
        PunchProcessor punch=new PunchProcessor();punch.setAmountDb(3);punch.setAmountDb(Double.NaN);
        assertEquals(-3,peak.getTargetDb());assertEquals(3,punch.getAmountDb());
    }

    @Test void peakGainHasCorrectReleaseStereoLinkAndLevelInvariance() {
        for(int rate:new int[]{44100,48000,96000})for(double scale:new double[]{1e-8,1,10}) {
            int peakFrame=rate/4;float[] source=new float[rate*2];
            for(int f=0;f<rate;f++) {source[f*2]=(float)(.01*scale);source[f*2+1]=-source[f*2];}
            source[peakFrame*2]=(float)scale;source[peakFrame*2+1]=(float)(-scale*.3);
            PeakCompProcessor p=new PeakCompProcessor();p.setTargetDb(-6);p.setEnabled(true);
            p.prepare(rate,source.length);p.analyze(source,2);float[] out=source.clone();p.process(out,2);
            double ceiling=Math.pow(10,-6.0/20);
            assertEquals(ceiling,out[peakFrame*2]/scale,1e-6);
            int sample=peakFrame+(int)Math.round(p.getReleaseMs()*rate/1000);
            double gain=out[2*sample]/(double)source[2*sample];
            // The 2 ms double-box adds <=2 ms to one time constant, independently
            // of block or sample rate. A wrong 60-sample/60-dB release cannot pass.
            double expected=1-(1-ceiling)/Math.E;
            assertEquals(expected,gain,.007,"measured 63.2% release rate="+rate);
            for(int f=0;f<rate;f++) {
                double left=out[f*2]/(double)source[f*2],right=out[f*2+1]/(double)source[f*2+1];
                assertEquals(left,right,2e-7);assertTrue(left>=ceiling-2e-7 && left<=1.0000001);
            }
            p.setTargetDb(-1);p.prepare(rate,source.length);out=source.clone();p.process(out,2);
            assertTrue(out[peakFrame*2]/scale>=Math.pow(10,-1.0/20)-1e-7,"live max bound");
        }
    }

    @Test void punchNeverAddsOverlappingGainsAndPreservesStereoAtEveryFrame() {
        int rate=48000;float[] source=new float[rate*4*2];Random random=new Random(59);
        for(int k=0;k<45;k++) {
            int start=rate/5+k*3800;
            for(int j=0;j<600;j++) {float v=(float)((2*random.nextDouble()-1)*Math.exp(-j/140.0));source[(start+j)*2]=v;source[(start+j)*2+1]=-v;}
        }
        var analysis=new com.quickmaster.processing.analysis.TrackAnalysis();analysis.analyze(source,2,rate);
        assertTrue(analysis.getTransientTimesSec().length>30);
        PunchProcessor p=new PunchProcessor();p.setTrackAnalysis(analysis);p.setAmountDb(12);p.setEnabled(true);
        p.prepare(rate,source.length);p.analyze(source,2);float[] out=source.clone();p.process(out,2);
        double upper=Math.pow(10,12.0/20);
        for(int f=0;f<source.length/2;f++) {
            assertEquals(out[f*2],-out[f*2+1],0.0f);
            if(source[f*2]!=0) {
                double gain=out[f*2]/(double)source[f*2];
                assertTrue(gain>=1-2e-7 && gain<=upper+5e-7,"overlap gain="+gain);
            }
        }
    }
}
