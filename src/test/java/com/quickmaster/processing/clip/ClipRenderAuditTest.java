package com.quickmaster.processing.clip;

import com.dspark.effects.Saturation;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.ProcessingPipeline;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ClipRenderAuditTest {
    private static AnalyzedClipProcessor clip(int kind,double db) {
        if(kind<2) {
            HardClipProcessor p=new HardClipProcessor();p.setCurve(HardClipProcessor.Curve.values()[kind]);p.setClipDb(db);p.setEnabled(true);return p;
        }
        SoftClipProcessor p=new SoftClipProcessor();p.setAlgorithm(Saturation.Algorithm.values()[kind-2]);p.setSatDb(db);p.setEnabled(true);return p;
    }
    private static double peak(float[] x,int start,int end) {
        double peak=0;for(int i=start;i<end;i++)peak=Math.max(peak,Math.abs(x[i]));return peak;
    }

    @Test void exactBaseRatePeakAndActualMeterAcrossPolarityScaleAndControls() {
        Random random=new Random(881);
        for(int kind=0;kind<5;kind++)for(double db:new double[]{0,.001,.1,1,6,12})
            for(double scale:new double[]{1e-8,.9,8})for(int polarity:new int[]{-1,1}) {
                float[] source=new float[2053];
                for(int i=0;i<source.length;i++)source[i]=(float)(scale*polarity*random.nextDouble());
                source[111]=(float)(scale*polarity);
                var p=clip(kind,db);p.prepare(48000,source.length);p.analyze(source,1);
                p.beginOfflineMetering(1);float[] out=source.clone();p.process(out,1);p.endOfflineMetering();
                double actual=20*Math.log10(peak(out,0,out.length)/peak(source,0,source.length));
                assertEquals(-db,actual,.000003,"kind="+kind+" scale="+scale+" polarity="+polarity);
                for(int start=0;start<source.length;start+=1024) {
                    int end=Math.min(source.length,start+1024);
                    double expected=20*Math.log10(peak(out,start,end)/peak(source,start,end));
                    assertEquals(expected,p.getGrAtPosition(start),.000003,"actual hop meter");
                }
                if(db==0)assertArrayEquals(source,out);
            }
    }

    @Test void raggedBlocksAndSeekAreBitExactAtAllSupportedRates() {
        Random random=new Random(244);
        for(int kind=0;kind<5;kind++)for(int rate:new int[]{44100,48000,96000}) {
            float[] source=new float[8194];for(int i=0;i<source.length;i++)source[i]=random.nextFloat()*2-1;
            var p=clip(kind,4);p.prepare(rate,source.length);p.analyze(source,2);
            float[] whole=source.clone();p.process(whole,2);p.prepare(rate,source.length);
            float[] split=new float[source.length];int at=0,block=0;int[] pattern={1,17,257,1024};
            p.beginOfflineMetering(2);
            while(at<source.length/2) {
                int count=Math.min(pattern[block++%pattern.length],source.length/2-at);
                float[] b=Arrays.copyOfRange(source,at*2,(at+count)*2);p.process(b,2);
                System.arraycopy(b,0,split,at*2,b.length);at+=count;
            }
            p.endOfflineMetering();assertArrayEquals(whole,split);
            p.setPlaybackPosition(132);float[] seek=Arrays.copyOfRange(source,264,900);
            p.process(seek,2);assertArrayEquals(Arrays.copyOfRange(whole,264,900),seek);
        }
    }

    @Test void oversampledMeterUsesActualHighRateAudioAndSourceClock() {
        for(int factor:new int[]{1,2,4,8,16})for(int kind=0;kind<5;kind++) {
            var p=clip(kind,1);float[] source=new float[4096];Arrays.fill(source,.5f);
            p.prepare(48000,source.length);p.analyze(source,1);
            p.prepare(48000*factor,(long)source.length*factor);p.beginOfflineMetering(1);
            int preroll=113*factor;p.setPlaybackPosition(-preroll);
            p.process(new float[preroll],1);
            float[] high=new float[4096*factor];
            for(int i=0;i<high.length;i++)high[i]=i<1024*factor?.25f:.8f;
            float[] out=high.clone();p.process(out,1);p.endOfflineMetering();
            // Same ceiling applied to a different actual OS/upstream signal.
            // The GR must not be silently clamped to the original 1 dB knob.
            double expected=20*Math.log10(peak(out,1024*factor,2048*factor)/.8f);
            assertTrue(expected < -2,"fixture must exceed static estimate");
            assertEquals(expected,p.getGrAtPosition(1500),.000003);
            p.prepare(48000,source.length);
            assertEquals(expected,p.getGrAtPosition(1500),.000003,"final rate reset retains measured meter");
            assertEquals(0,p.getGrAtPosition(-1));assertEquals(0,p.getGrAtPosition(4096));
        }
    }

    @Test void explicitOversamplingReducesFoldbackWithoutAHiddenBodyFilter() {
        int rate=48000,frames=rate;float[] source=new float[frames];
        for(int i=0;i<frames;i++)source[i]=(float)(.9*Math.sin(2*Math.PI*7000*i/rate));
        for(int kind=0;kind<5;kind++) {
            double[] alias=new double[2];int n=0;
            for(int factor:new int[]{1,8}) {
                var p=clip(kind,6);var pipeline=new ProcessingPipeline();pipeline.addProcessor(p);
                WavFile file=new WavFile("generated.wav",rate,1,source.clone(),32,true);
                pipeline.processOversampled(file,factor,null);
                alias[n++]=amplitude(file.getSamples(),13000,rate);
            }
            double improvement=20*Math.log10(alias[0]/alias[1]);
            System.out.printf("CLIP_ALIAS kind=%d fold13k_1x=%.9g fold13k_8x=%.9g improvementDb=%.6f%n",kind,alias[0],alias[1],improvement);
            assertTrue(improvement>30,"8x must attenuate folded fifth harmonic by >30 dB: "+improvement);
        }
    }

    private static double amplitude(float[] input,int frequency,int rate) {
        double re=0,im=0;int start=4800,end=43200;
        for(int i=start;i<end;i++) {double phase=2*Math.PI*frequency*i/rate;re+=input[i]*Math.cos(phase);im+=input[i]*Math.sin(phase);}
        return 2*Math.hypot(re,im)/(end-start);
    }
}
