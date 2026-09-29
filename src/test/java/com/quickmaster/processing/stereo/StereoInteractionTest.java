package com.quickmaster.processing.stereo;

import com.google.gson.Gson;
import com.quickmaster.config.PresetValidation;
import com.quickmaster.processing.PreviewWindowRenderer;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import com.quickmaster.ui.StereoImagePane;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StereoInteractionTest {
    static StereoImageSettings settings(double amount,double cut,boolean level,boolean guard,double gain) {
        return new StereoImageSettings(true,amount,cut,level,.6,guard,3,StereoProfile.REFERENCE,.12,gain);
    }
    @Test void defaultsAndLegacyPresetsPreserveTheirIntendedBass() {
        assertEquals(.25,StereoImageSettings.DEFAULT.generationAmount());
        assertFalse(StereoImageSettings.DEFAULT.generation());
        assertEquals(0,StereoImageSettings.DEFAULT.generatedLowCutHz());
        assertEquals(0,StereoImageSettings.DEFAULT.sideGainDb());
        Gson gson=new Gson();
        for(boolean oldLow:new boolean[]{false,true}) {
            var s=gson.fromJson("{\"generation\":true,\"generationAmount\":0.5,\"generateLowFrequencies\":"+oldLow+",\"profile\":\"AUTO\",\"referenceSideShare\":0.12}",StereoImageSettings.class);
            assertEquals(oldLow?0:175,s.generatedLowCutHz());
        }
        var s=settings(.5,2437.12,true,true,-3.5);
        assertEquals(s,gson.fromJson(gson.toJson(s),StereoImageSettings.class));
        assertEquals(List.of("EQ","Stereo Image","Dynamics","Clip","Limit"),PresetValidation.chainOrderWithStereo(null));
        for(double cut:new double[]{-1,1,19.99,5001,Double.NaN})assertThrows(IllegalArgumentException.class,()->settings(1,cut,false,false,0));
        for(double gain:new double[]{-13,13,Double.NaN})assertThrows(IllegalArgumentException.class,()->settings(1,0,false,false,gain));
        for(double cut:new double[]{0,20,175,500,2437.12,5000})assertEquals(cut,StereoImagePane.lowCutHz(StereoImagePane.lowCutPosition(cut)),.005);
    }
    @Test void adjustableFilterIsLinearPhaseAndOnlyRemovesGeneratedContent() {
        for(int rate:new int[]{8000,44100,48000,96000}) {
            assertArrayEquals(new double[]{1},PartitionedFir.lowCut(rate,0));
            for(double hz:new double[]{20,175,1000,5000}) {
                double[] h=PartitionedFir.lowCut(rate,hz);
                for(int i=0;i<h.length;i++)assertEquals(h[i],h[h.length-1-i],1e-15);
                if(hz>=rate/2.0){assertArrayEquals(new double[]{0},h);continue;}
                assertTrue(StereoImageTest.response(h,0,rate)<1e-10);
                assertEquals(.5,StereoImageTest.response(h,hz,rate),.015);
                assertEquals(1,StereoImageTest.response(h,Math.min(rate*.45,hz*2+100),rate),.001);
            }
        }
        int rate=48000;float[] x=StereoImageTest.signal(rate,1,.4);
        var p=StereoImageTest.processor(settings(1,5000,false,false,0));
        float[] y=StereoImageTest.render(x,rate,p);StereoImageTest.assertMono(x,y);
        // No side tilt / cutoff is applied to the original source when Generation is zero.
        p.setSettings(settings(0,5000,false,false,0));assertArrayEquals(x,StereoImageTest.render(x,rate,p));
    }
    @Test void manualSideGainIsIndependentFinalGainAndPreservesMid() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,2,.7);
        for(boolean regulate:new boolean[]{false,true}) {
            var p=StereoImageTest.processor(settings(0,0,regulate,regulate,0));float[] base=StereoImageTest.render(x,rate,p);
            for(double db:new double[]{-12,-3,6,12}) {
                p.setSettings(settings(0,0,regulate,regulate,db));float[] y=StereoImageTest.render(x,rate,p);
                StereoImageTest.assertMono(x,y);double gain=Math.pow(10,db/20);
                for(int f=0;f<x.length/2;f++)assertEquals((base[2*f]-(double)base[2*f+1])*gain,y[2*f]-(double)y[2*f+1],4e-7);
            }
        }
    }
    @Test void warmEditsReuseSynthesisAndAreBitExactToColdRenders() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,2,.4), saved=x.clone();
        var p=StereoImageTest.processor(settings(1,0,false,false,0));
        for(var s:new StereoImageSettings[]{settings(1,0,false,false,0),settings(.5,0,false,false,0),settings(.5,0,true,true,2),settings(.8,1000,true,false,-2),settings(.8,1000,false,true,0),settings(.8,0,false,false,0)}) {
            var fork=p.fork();fork.setSettings(s);
            float[] warm=StereoImageTest.render(x,rate,fork),cold=StereoImageTest.render(x,rate,StereoImageTest.processor(s));
            assertArrayEquals(cold,warm);assertEquals(1,p.synthesisCount());
        }
        assertEquals(3,p.filterCount());assertArrayEquals(saved,x);
        StereoImageTest.render(x.clone(),rate,p);assertEquals(2,p.synthesisCount());
        StereoImageTest.render(x,16000,p);assertEquals(3,p.synthesisCount());
        p.clearGenerationCache();assertEquals(0,p.cachedBytes());StereoImageTest.render(x,rate,p);assertEquals(4,p.synthesisCount());
        assertFalse(StereoGenerationCache.fits(Integer.MAX_VALUE,48000));assertTrue(p.cachedBytes()<=256L*1024*1024);
    }
    @Test void cancelledOrInvalidSynthesisCannotPoisonCache() {
        var cache=new StereoGenerationCache();float[] x=StereoImageTest.signal(8000,1,.2);
        CancellationToken token=new CancellationToken();token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,()->cache.delta(x,8000,0,token));assertEquals(0,cache.bytes());
        float[] invalid=x.clone();invalid[100]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->cache.delta(invalid,8000,0,null));assertEquals(0,cache.bytes());
        assertArrayEquals(new StereoGenerationCache().delta(x,8000,175,null),cache.delta(x,8000,175,null));
    }
    @Test void filteredCacheAndStreamingAgreeIncludingBoundaryTail() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,.7,.5);
        for(double cut:new double[]{0,20,175,1000,5000}) {
            var p=StereoImageTest.processor(settings(.72,cut,true,true,2.3));float[] expected=StereoImageTest.render(x,rate,p);
            p.prepare(rate,x.length);int latency=p.getLatencyFrames();float[] stream=new float[x.length+latency*2];System.arraycopy(x,0,stream,0,x.length);
            p.process(stream,2);
            for(int i=0;i<x.length;i++)assertEquals(expected[i],stream[i+latency*2],0,"cut="+cut+" sample="+i);
        }
    }
    @Test void adjustableLowCutPreviewMatchesFinalWithoutHiddenGain() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,4,.4);
        for(double cut:new double[]{0,20,1000,5000}) {
            var s=settings(.7,cut,false,false,-2);var p=StereoImageTest.processor(s);
            float[] full=StereoImageTest.render(x,rate,p);var chain=new ProcessingPipeline();chain.addProcessor(p);
            var preview=new PreviewWindowRenderer(chain,x,rate,2,1);
            float[] part=preview.render(rate*2,rate/4,new CancellationToken());
            for(int i=0;i<part.length;i++)assertEquals(full[rate*4+i],part[i],5e-6);
            assertEquals(0,preview.getOutputGainDb());
        }
    }
    @Test void fusedPreviewMatchesTwoPassOracleWithRegulationAndAbsoluteModulation() {
        int rate=8000,start=11351;float[] x=StereoImageTest.signal(rate,1.7,.6);
        for(double cut:new double[]{0,20,175,1000,5000}) {
            var s=settings(.65,cut,true,true,-2.4);
            var p=StereoImageTest.processor(s);p.prepare(rate,x.length);
            float[] fast=p.renderPreview(x,2,start,new CancellationToken());
            var old=StereoImageTest.processor(s);old.prepare(rate,x.length);old.analyzePreview(x,2,start,new CancellationToken());
            old.setPlaybackPosition(start);int latency=old.getLatencyFrames();
            float[] streamed=new float[x.length+2*latency];System.arraycopy(x,0,streamed,0,x.length);old.process(streamed,2);
            for(int i=0;i<x.length;i++)assertEquals(streamed[2*latency+i],fast[i],0,"Preview cut="+cut+" sample="+i);
        }
    }
}
