package com.quickmaster.processing;

import com.dspark.effects.MasterEqualizer;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.eq.EqualizerProcessor;
import com.quickmaster.processing.dynamics.leveler.FiniteTruePeakStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent signal contracts, including exact bypass and inter-stage headroom. */
class EqAutoGainTest {
    static double peak(float[] pcm,int ch) {var m=new FiniteTruePeakStream(ch,true);m.accept(pcm,0,pcm.length/ch);return m.finish();}
    static EqualizerProcessor eq(boolean auto,double db,MasterEqualizer.BandType type) {
        var eq=new EqualizerProcessor();eq.setNumBands(1);eq.setAutoGainEnabled(auto);
        var b=new MasterEqualizer.Band();b.frequency=857;b.gainDb=db;b.q=.71;b.type=type;eq.setBand(0,b);return eq;
    }
    static float[] signal(int rate,int ch) {
        float[] input=new float[rate/4*ch];for(int f=0;f<input.length/ch;f++)for(int c=0;c<ch;c++)
            input[f*ch+c]=(float)((f<rate/8?.25:.5)*Math.sin(2*Math.PI*857*f/rate)*(c==0?1:-.7));return input;
    }
    static float[] render(EqualizerProcessor eq,float[] input,int rate,int ch,int factor,boolean norm) {
        var p=new ProcessingPipeline();p.addProcessor(eq);var n=new PeakNormalizer(-1);n.setEnabled(norm);p.addProcessor(n);
        var file=new WavFile("fixture",rate,ch,input,32,true);p.processOversampled(file,factor,null);return file.getSamples();
    }
    @Test void uniformEqGainIsUndoneExactlyWithoutChangingMusicalDynamics() {
        for(int ch:new int[]{1,2})for(double db:new double[]{-18,0,18}) {
            float[] source=signal(48000,ch),saved=source.clone();var e=eq(true,db,MasterEqualizer.BandType.GAIN);
            float[] out=render(e,source,48000,ch,1,false);
            assertEquals(-db,e.getAutoGainDb(),.000003);for(int i=0;i<out.length;i++)assertEquals(source[i],out[i],1e-7);
            assertArrayEquals(saved,source);
        }
    }
    @Test void aggressiveBellHasHeadroomOnlyWhenAutoGainIsOffAcrossRatesAndOversampling() {
        for(int rate:new int[]{44100,48000,96000})for(int ch:new int[]{1,2})for(int factor:new int[]{1,2,4,8,16}) {
            float[] source=signal(rate,ch);float[] raw=render(eq(false,18.8,MasterEqualizer.BandType.BELL),source,rate,ch,factor,false);
            var e=eq(true,18.8,MasterEqualizer.BandType.BELL);float[] out=render(e,source,rate,ch,factor,false);
            assertTrue(peak(raw,ch)>3);assertTrue(peak(out,ch)<=1.000001);assertTrue(e.getAutoGainDb()< -12);
            // Static EQ plus autogain remains a single scalar even after SRC;
            // no clipped peaks, pumping, channel drift or changed spectral shape.
            int index=0;for(int i=1;i<raw.length;i++)if(Math.abs(raw[i])>Math.abs(raw[index]))index=i;
            double gain=out[index]/(double)raw[index];
            for(int i=0;i<raw.length;i++)assertEquals(raw[i]*gain,out[i],4e-7,"rate="+rate+" factor="+factor);
        }
    }
    @Test void noEqOrDisabledAutoGainDoesNotEnableHiddenOutputProcessing() {
        var e=eq(false,18,MasterEqualizer.BandType.GAIN);float[] source=signal(48000,2);
        float[] first=render(e,source,48000,2,1,false);assertTrue(peak(first,2)>3);
        e.setAutoGainEnabled(true);render(e,source,48000,2,1,false);e.setAutoGainEnabled(false);
        assertArrayEquals(first,render(e,source,48000,2,1,false));
        e.setAutoGainEnabled(true);e.setEnabled(false);assertArrayEquals(source,render(e,source,48000,2,1,false));
        e.setEnabled(true);e.setNumBands(0);assertArrayEquals(source,render(e,source,48000,2,1,false));
    }
    @Test void outputSafetyIsOptInAndDoesNotCancelNormalizerTarget() {
        var n=new PeakNormalizer();n.setEnabled(false);n.setAnalyzedPeak(2);
        assertEquals(1,n.getGain());n.setSafetyEnabled(true);assertEquals(-6.120599913,n.getGainDb(),1e-8);
        n.setTargetDbfs(-24);assertEquals(-6.120599913,n.getGainDb(),1e-8);
        n.setEnabled(true);assertEquals(-30.020599913,n.getGainDb(),1e-8);
        n.setEnabled(false);n.setSafetyEnabled(false);assertEquals(1,n.getGain());
        for(int os:new int[]{1,4})assertEquals(-1,20*Math.log10(peak(render(eq(true,18.8,MasterEqualizer.BandType.BELL),signal(48000,2),48000,2,os,true),2)),.000003);
    }
    @Test void intersampleOnlyOverloadAndSilenceAreSafe() {
        float[] source=new float[4800];for(int i=0;i<source.length;i++)source[i]=(float)(1.12*Math.sin(Math.PI/2*i+Math.PI/4));
        for(float v:source)assertTrue(Math.abs(v)<1);assertTrue(peak(source,1)>1.05);
        var e=eq(true,0,MasterEqualizer.BandType.GAIN);float[] out=render(e,source,48000,1,1,false);
        assertEquals(-.1,20*Math.log10(peak(out,1)),.000003);
        assertArrayEquals(new float[80],render(e,new float[80],48000,1,1,false));assertEquals(0,e.getAutoGainDb());
    }
    @Test void upstreamReferenceAndDownstreamAnalysisUseTheCorrectSideOfAutoGain() {
        float[] source=signal(48000,2);var p=new ProcessingPipeline();
        p.addProcessor(eq(false,6,MasterEqualizer.BandType.GAIN));var auto=eq(true,18,MasterEqualizer.BandType.GAIN);p.addProcessor(auto);
        var n=new PeakNormalizer(-1);p.addProcessor(n);p.prepare(48000,source.length);
        float[][] taps=new float[3][];p.analyzeAndRender(source,2,0,null,null,(pcm,index)->taps[index]=pcm);
        assertTrue(peak(taps[0],2)>.99);assertTrue(peak(taps[1],2)<=1.000001);
        assertEquals(peak(taps[1],2),n.getAnalyzedPeak(),1e-9);
        assertEquals(-1,20*Math.log10(peak(taps[2],2)),.000003);
    }
    @Test void legacyPresetsDefaultOnButExplicitFalseSurvivesJsonRoundTrip() {
        var gson=new com.google.gson.Gson();var p=gson.fromJson("{}",com.quickmaster.config.ChainPreset.class);assertTrue(p.eqAutoGain);
        p.eqAutoGain=false;assertFalse(gson.fromJson(gson.toJson(p),com.quickmaster.config.ChainPreset.class).eqAutoGain);
    }
}
