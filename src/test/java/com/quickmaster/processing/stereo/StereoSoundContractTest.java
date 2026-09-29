package com.quickmaster.processing.stereo;

import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.audio.WavFile;
import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

/** Signal-level quality contracts, separate from subjective listening. */
class StereoSoundContractTest {
    @Test void oversampledDryBranchRemainsSampleAlignedWithIdentityResampler() {
        for(int rate:new int[]{44100,48000}) for(int factor:new int[]{2,4,8,16}) {
            float[] x=StereoImageTest.signal(rate,.15,.3);
            var p=StereoImageTest.processor(StereoImageTest.settings(true,false,false,0,false));
            var actual=new WavFile("stereo",rate,2,x.clone(),32,true);
            var reference=new WavFile("identity",rate,2,x.clone(),32,true);
            var chain=new ProcessingPipeline();chain.addProcessor(p);chain.processOversampled(actual,factor,null);
            new ProcessingPipeline().processOversampled(reference,factor,null);
            double error=0;
            for(int i=0;i<x.length;i+=2) error=Math.max(error,Math.abs(actual.getSamples()[i]+(double)actual.getSamples()[i+1]
                    -reference.getSamples()[i]-reference.getSamples()[i+1]));
            assertTrue(error<2e-6,"Dry branch delay at "+rate+"/"+factor+" error="+error);
        }
    }
    @Test void finiteImpulseKeepsOriginalAttackAndLimitsGeneratedPreRinging() {
        int rate=48000;float[] x=new float[rate*2];x[rate]=x[rate+1]=.9f;
        {
            boolean harmonic=true;
            float[] y=StereoImageTest.render(x,rate,StereoImageTest.processor(StereoImageTest.settings(true,false,harmonic,0,false)));
            StereoImageTest.assertMono(x,y);
            double pre=0,late=0;
            for(int f=0;f<rate/2;f++)pre=Math.max(pre,Math.abs(y[2*f]));
            for(int f=rate*3/4;f<rate;f++)late=Math.max(late,Math.abs(y[2*f]));
            System.out.printf(Locale.US,"STEREO_IMPULSE harmonics=%s prePeakDb=%.3f latePeakDb=%.3f%n",harmonic,20*Math.log10(Math.max(1e-20,pre)),20*Math.log10(Math.max(1e-20,late)));
            assertTrue(pre<.009,"Generated pre-ringing exceeds 1% of the transient peak");
            assertTrue(late<1e-5,"Unbounded ringing tail");
        }
    }
    @Test void deltaHarmonicsHaveLowFoldbackAndNoPersistentDc() {
        int rate=48000;float[] x=new float[rate*4];
        for(int f=0;f<x.length/2;f++)x[2*f]=x[2*f+1]=(float)(.95*Math.sin(2*Math.PI*7000*f/rate));
        float[] y=StereoImageTest.render(x,rate,StereoImageTest.processor(StereoImageTest.settings(true,false,true,0,false)));
        StereoImageTest.assertMono(x,y);
        double alias=projection(y,13000,rate,rate/2,rate),dc=0;
        for(int f=rate/2;f<rate*3/2;f++)dc+=.5*(y[2*f]-(double)y[2*f+1])/rate;
        System.out.printf(Locale.US,"STEREO_HARMONICS alias13kDbfs=%.3f dc=%.9g%n",20*Math.log10(Math.max(1e-20,alias)),dc);
        assertTrue(alias<.0001,"5th-harmonic foldback exceeds -80 dBFS");
        assertTrue(Math.abs(dc)<1e-5,"Persistent delta DC");
    }
    static double projection(float[] x,double hz,int rate,int from,int count) {
        double re=0,im=0,weight=0;
        for(int i=0;i<count;i++) {
            double w=.5-.5*Math.cos(2*Math.PI*i/(count-1)),v=.5*(x[2*(from+i)]-(double)x[2*(from+i)+1]);
            re+=w*v*Math.cos(2*Math.PI*hz*(from+i)/rate);im+=w*v*Math.sin(2*Math.PI*hz*(from+i)/rate);weight+=w;
        }
        return 2*Math.hypot(re,im)/weight;
    }
}
