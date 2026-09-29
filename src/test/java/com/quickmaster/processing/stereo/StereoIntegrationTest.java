package com.quickmaster.processing.stereo;

import com.google.gson.Gson;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.config.PresetValidation;
import com.quickmaster.processing.*;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StereoIntegrationTest {
    @Test void bypassDoesNotRestrictLegacySampleRates() {
        var p=new StereoImageProcessor();p.prepare(4000,20);
        float[] input={.2f,-.3f};assertSame(input,p.process(input,2));assertEquals(0,p.getLatencyFrames());
    }
    @Test void stereoOnlyChangesOutputGainWhenNormalizerIsExplicitlyEnabled() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,1,1.5);
        for(int i=0;i<x.length;i++)x[i]*=5;
        var settings=StereoImageTest.settings(true,false,false,0,false);
        float[] raw=StereoImageTest.render(x,rate,StereoImageTest.processor(settings));
        var chain=new ProcessingPipeline();chain.addProcessor(StereoImageTest.processor(settings));
        var n=new PeakNormalizer();n.setEnabled(false);chain.addProcessor(n);chain.prepare(rate,x.length);
        float[] untouched=chain.analyzeAndRender(x,2,0,null,null,null);
        assertFalse(n.isSafetyEnabled());assertEquals(0,n.getGainDb());assertArrayEquals(raw,untouched);
        n.setEnabled(true);chain.prepare(rate,x.length);
        float[] safe=chain.analyzeAndRender(x,2,0,null,null,null);
        double gain=Math.pow(10,n.getGainDb()/20);assertTrue(gain<1);
        for(int i=0;i<raw.length;i++)assertEquals(raw[i]*gain,safe[i],3e-7);
        assertTrue(com.dspark.analysis.TruePeak.measureMax(safe,2)<1);
    }
    @Test void inactiveSectionsStillMeasureThePassingStereoAudio() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,2,.7);
        var p=StereoImageTest.processor(StereoImageSettings.DEFAULT);
        assertArrayEquals(x,StereoImageTest.render(x,rate,p));
        assertEquals(StereoImageTest.share(x,rate,rate+rate/50),p.measuredSideShare(1),2e-7);
    }
    @Test void upstreamEqAutoGainCannotNormalizeADownstreamStereoEdit() {
        var eq=new com.quickmaster.processing.eq.EqualizerProcessor();eq.setEnabled(true);eq.setAutoGainEnabled(true);eq.setNumBands(1);
        var band=new com.dspark.effects.MasterEqualizer.Band();band.enabled=true;band.gainDb=3;eq.setBand(0,band);
        var stereo=StereoImageTest.processor(StereoImageTest.settings(true,true,true,0,false));
        var n=new PeakNormalizer();n.setEnabled(false);
        var chain=new ProcessingPipeline();chain.addProcessor(eq);chain.addProcessor(stereo);chain.addProcessor(n);chain.prepare(8000,16000);
        assertFalse(n.isSafetyEnabled());assertEquals(0,n.getGainDb());
        var reversed=new ProcessingPipeline();reversed.addProcessor(stereo);reversed.addProcessor(eq);reversed.addProcessor(n);reversed.prepare(8000,16000);
        assertTrue(n.isSafetyEnabled(),"An explicitly active EQ after stereo still owns its own compensation");
    }
    @Test void lowSwitchOnlyChangesGeneratedLowFrequenciesAndNeverOriginalMid() {
        int rate=48000;float[] x=new float[rate*4];
        for(int f=0;f<x.length/2;f++)x[f*2]=x[f*2+1]=(float)(.4*Math.sin(2*Math.PI*1000*f/rate));
        float[] a=StereoImageTest.render(x,rate,StereoImageTest.processor(StereoImageTest.settings(true,false,true,0,false)));
        float[] b=StereoImageTest.render(x,rate,StereoImageTest.processor(StereoImageTest.settings(true,true,true,0,false)));
        StereoImageTest.assertMono(x,a);StereoImageTest.assertMono(x,b);
        double mismatch=0,delta=0;
        for(int f=rate/2;f<rate*3/2;f++){mismatch+=Math.pow(a[2*f]-(double)b[2*f],2);delta+=Math.pow(b[2*f]-(double)x[2*f],2);}
        assertTrue(mismatch/delta<1e-5,"Low checkbox revoiced the upper spectrum: "+mismatch/delta);
        var chain=new ProcessingPipeline();chain.addProcessor(StereoImageTest.processor(StereoImageTest.settings(true,true,true,0,false)));
        var n=new PeakNormalizer();n.setEnabled(false);chain.addProcessor(n);
        var preview=new PreviewWindowRenderer(chain,x,rate,2,1);
        float[] window=preview.render(rate,rate/4,new CancellationToken());
        for(int i=0;i<window.length;i++)assertEquals(b[rate*2+i],window[i],5e-6,"Hidden preview gain");
        assertEquals(0,preview.getOutputGainDb());
    }
    @Test void oversampledPreviewReplansAfterSeekInsteadOfHoldingFirstWindowGain() {
        int rate=8000;float[] x=new float[rate*12*2];
        System.arraycopy(StereoImageTest.signal(rate,6,.1),0,x,0,rate*12);
        System.arraycopy(StereoImageTest.signal(rate,6,1),0,x,rate*12,rate*12);
        var chain=new ProcessingPipeline();chain.addProcessor(StereoImageTest.processor(StereoImageTest.settings(false,false,false,1,false)));
        var preview=new PreviewWindowRenderer(chain,x,rate,2,4);
        float[] a=preview.render(rate*3,rate/4,new CancellationToken());
        float[] b=preview.render(rate*9,rate/4,new CancellationToken());
        assertEquals(.12,StereoImageTest.share(a,0,a.length/2),.005);
        assertEquals(.12,StereoImageTest.share(b,0,b.length/2),.005);
    }
    @Test void presetRoundTripLegacyOrdersAndMalformedSettings() {
        Gson gson = new Gson();
        ChainPreset old = gson.fromJson("{\"version\":1,\"chainOrder\":[\"Limit\",\"Clip\",\"Dynamics\",\"EQ\"]}", ChainPreset.class);
        PresetValidation.validate(old); assertFalse(old.stereoOn); assertEquals(StereoImageSettings.DEFAULT, old.stereoImage);
        assertEquals(List.of("Limit","Clip","Stereo Image","Dynamics","EQ"), PresetValidation.chainOrderWithStereo(old.chainOrder));
        ChainPreset p = new ChainPreset(); p.stereoOn = true;
        p.stereoImage = StereoImageTest.settings(true,true,true,.75,true);
        p.chainOrder = List.of("Limit","Stereo Image","Clip","Dynamics","EQ");
        ChainPreset back = gson.fromJson(gson.toJson(p), ChainPreset.class); PresetValidation.validate(back);
        assertEquals(p.stereoImage, back.stereoImage); assertEquals(p.chainOrder,back.chainOrder); assertTrue(back.stereoOn);
        p.stereoImage = null; assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
        assertThrows(RuntimeException.class, () -> gson.fromJson("{\"stereoImage\":{\"generationAmount\":3}}", ChainPreset.class));
    }
    @Test void independentSnapshotsDoNotChangeEachOther() {
        var original = StereoImageTest.processor(StereoImageSettings.DEFAULT); var fork = original.fork();
        fork.setSettings(StereoImageTest.settings(false,true,false,1,true)); fork.setEnabled(false);
        assertTrue(original.isEnabled()); assertEquals(StereoImageSettings.DEFAULT, original.settings());
        original.prepare(8000,16000); original.analyze(StereoImageTest.signal(8000,1,.3),2);
        fork.adoptPlan(original); var saved = fork.plan();
        original.analyze(new float[16000],2); assertSame(saved,fork.plan()); assertNotSame(saved,original.plan());
    }
    @Test void previewsRespectAbsoluteModulationTimeAndMatchFinalGeneration() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,4,.3);
        {
            boolean harmonic=true;
            var settings=StereoImageTest.settings(true,false,harmonic,0,false);
            float[] full=StereoImageTest.render(x,rate,StereoImageTest.processor(settings));
            var chain=new ProcessingPipeline();chain.addProcessor(StereoImageTest.processor(settings));
            var preview=new PreviewWindowRenderer(chain,x,rate,2,1);
            long start=System.nanoTime();float[] part=preview.render(rate*2,rate/4,new CancellationToken());
            double error=0;for(int i=0;i<part.length;i++)error=Math.max(error,Math.abs(full[rate*4+i]-part[i]));
            assertTrue(error<5e-6,"Preview discontinuity "+error);
            System.out.printf(Locale.US,"STEREO_PREVIEW rate=%d harmonics=%s ms=%.2f error=%.8g%n",rate,harmonic,(System.nanoTime()-start)/1e6,error);
        }
    }
    @Test void stageMeterMeasuresRenderedAudioNotRequestedTarget() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,2,.9);
        var p=StereoImageTest.processor(StereoImageTest.settings(false,false,false,1,true));
        float[] y=StereoImageTest.render(x,rate,p);
        for(int f=rate/2;f<rate;f+=rate/50)
            assertEquals(StereoImageTest.share(y,f,f+rate/50),p.measuredSideShare(f/(double)rate),2e-7);
    }
    @Test void highRatePreparationRetainsSecondsBasedAutomationAndOversamplingIsFinite() {
        int rate=8000;float[] x=StereoImageTest.signal(rate,1,.6);
        for(int factor:new int[]{1,2,4,8,16}) {
            var p=StereoImageTest.processor(StereoImageTest.settings(true,false,false,.5,true));
            var chain=new ProcessingPipeline();chain.addProcessor(p);
            var file=new com.quickmaster.audio.WavFile("fixture",rate,2,x.clone(),32,true);
            chain.processOversampled(file,factor,null);
            assertEquals(x.length,file.getSamples().length);
            for(float sample:file.getSamples())assertTrue(Float.isFinite(sample));
            assertTrue(StereoImageTest.share(file.getSamples(),rate/4,rate*3/4)>0);
        }
    }
}
