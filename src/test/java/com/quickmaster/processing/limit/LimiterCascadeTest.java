package com.quickmaster.processing.limit;

import com.dspark.analysis.TruePeak;
import com.quickmaster.processing.ProcessingPipeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LimiterCascadeTest {
    static float[] signal(int rate,int channels) {
        float[] x=new float[rate*2*channels];
        for(int f=0;f<rate*2;f++)for(int c=0;c<channels;c++)
            x[f*channels+c]=(float)(.12*Math.sin(2*Math.PI*67*f/rate+c*.3)
                    +(.12+.5*Math.exp(-(f%(rate/4))/80.0))*Math.sin(2*Math.PI*6300*f/rate+c*.9));
        return x;
    }
    @Test void multibandDriveCannotMoveBroadbandCeiling() {
        float[] input=signal(48000,2);
        double reference=TruePeak.measureMax(input,2);
        for(double push:new double[]{0,1,3,6}) {
            var mb=new MultibandLimiterProcessor();mb.setEnabled(true);
            for(int b=0;b<4;b++)mb.setPushDb(b,push);
            var bb=new BroadbandLimiterProcessor();bb.setEnabled(true);bb.setPushDb(3);
            var pipe=new ProcessingPipeline();pipe.addProcessor(mb);pipe.addProcessor(bb);pipe.prepare(48000,input.length);
            float[] output=pipe.analyzeAndRender(input,2,0,null,null,null);
            assertEquals(3,bb.getPushDb(),0,"Bands must never rewrite broadband Push");
            assertTrue(TruePeak.measureMax(output,2)<=reference*1.001,
                    "Multiband "+push+" dB must not raise the shared ceiling");
        }
    }
    @Test void zeroBroadbandDriveStillContainsMultibandPeaks() {
        float[] input=signal(48000,2);
        var mb=new MultibandLimiterProcessor();mb.setEnabled(true);for(int b=0;b<4;b++)mb.setPushDb(b,6);
        var bb=new BroadbandLimiterProcessor();bb.setEnabled(true);bb.setPushDb(0);
        var pipe=new ProcessingPipeline();pipe.addProcessor(mb);pipe.addProcessor(bb);pipe.prepare(48000,input.length);
        float[] output=pipe.analyzeAndRender(input,2,0,null,null,null);
        assertTrue(TruePeak.measureMax(output,2)<=TruePeak.measureMax(input,2)*1.001);
        assertTrue(bb.getGrDeepest(0,input.length/2-1)<-.1,"Zero input drive is not bypass");
    }
    @Test void nonFiniteBroadbandControlsAreRejected() {
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            var p=new BroadbandLimiterProcessor();
            assertThrows(IllegalArgumentException.class,()->p.setPushDb(value));
            assertThrows(IllegalArgumentException.class,()->p.requestPushDb(value));
        }
    }
    @Test void explicitStagedRenderMatchesCascadeAndMeterIncludesAllAttenuation() {
        float[] input=signal(48000,2);
        var mb=new MultibandLimiterProcessor();mb.setEnabled(true);for(int b=0;b<4;b++)mb.setPushDb(b,6);
        var bb=new BroadbandLimiterProcessor();bb.setEnabled(true);bb.setPushDb(9);
        var pipe=new ProcessingPipeline();pipe.addProcessor(mb);pipe.addProcessor(bb);pipe.prepare(48000,input.length);
        float[][] bandOutput={null};
        float[] actual=pipe.analyzeAndRender(input,2,0,null,null,(pcm,stage)->{if(stage==0)bandOutput[0]=pcm;});
        var standalone=new BroadbandLimiterProcessor();standalone.setEnabled(true);standalone.setPushDb(9);
        standalone.setCeilingReference(TruePeak.measureMax(input,2));standalone.prepare(48000,input.length);standalone.analyze(bandOutput[0],2);
        float[] expected=bandOutput[0].clone();standalone.process(expected,2);assertArrayEquals(expected,actual,0);
        assertTrue(bb.getGrDeepest(0,input.length/2-1)<-9,"Meter must not be capped at the Push control maximum");
        for(int f=0;f<input.length/2;f+=137)if(Math.abs(bandOutput[0][2*f])>1e-5) {
            double net=20*Math.log10(Math.abs(actual[2*f]/bandOutput[0][2*f]));
            assertEquals(net-9,bb.getGrAtPosition(f),2e-5,"Meter must describe applied PCM gain");
        }
    }
    @Test void sourceAndBypassChangesCannotReuseOldAnchor() {
        float[] input=signal(48000,2);
        var mb=new MultibandLimiterProcessor();mb.setEnabled(true);var bb=new BroadbandLimiterProcessor();bb.setEnabled(true);bb.setPushDb(3);
        var pipe=new ProcessingPipeline();pipe.addProcessor(mb);pipe.addProcessor(bb);pipe.prepare(48000,input.length);
        pipe.analyzeAndRender(input,2,0,null,null,null);
        assertEquals(TruePeak.measureMax(input,2),bb.getCeilingTruePeak(),0);
        mb.setEnabled(false);float[] next=input.clone();for(int i=0;i<next.length;i++)next[i]*=.2f;
        pipe.analyzeAndRender(next,2,0,null,null,null);
        assertEquals(TruePeak.measureMax(next,2),bb.getCeilingTruePeak(),0);
        mb.setEnabled(true);pipe.analyzeAndRender(next,2,0,null,null,null);
        assertEquals(TruePeak.measureMax(next,2),bb.getCeilingTruePeak(),0);
    }
    @Test void finalOutputCeilingAndAlignmentSurviveOversampling() {
        for(int rate:new int[]{44100,48000,96000})for(int ch:new int[]{1,2})for(int factor:new int[]{1,2,4,8,16}) {
            float[] input=java.util.Arrays.copyOf(signal(rate,ch),8193*ch);input[input.length-1]=.95f;
            var mb=new MultibandLimiterProcessor();mb.setEnabled(true);for(int b=0;b<4;b++)mb.setPushDb(b,6);
            var bb=new BroadbandLimiterProcessor();bb.setEnabled(true);bb.setPushDb(9);
            var norm=new com.quickmaster.processing.PeakNormalizer(-1);
            var pipe=new ProcessingPipeline();pipe.addProcessor(mb);pipe.addProcessor(bb);pipe.addProcessor(norm);
            var file=new com.quickmaster.audio.WavFile("test",rate,ch,input,32,true);
            pipe.processOversampled(file,factor,null);
            assertEquals(input.length,file.getSamples().length);
            assertEquals(-1,20*Math.log10(TruePeak.measureMax(file.getSamples(),ch)),2e-5,
                    "rate="+rate+", channels="+ch+", factor="+factor);
            assertEquals(TruePeak.measureMax(input,ch),bb.getCeilingTruePeak(),0);
        }
    }
}
