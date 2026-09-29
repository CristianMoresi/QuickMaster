import com.dspark.analysis.TruePeak;
import com.dspark.effects.MasterEqualizer;
import com.quickmaster.audio.*;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.processing.*;
import com.quickmaster.processing.eq.EqualizerProcessor;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.Region;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Reproduces the reported EQ-only overload in the real packaged controller. */
class EqOutputGainAudit {
    static MainController c; static Region root; static javafx.stage.Stage stage;
    static Object get(Object o,String n)throws Exception {var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object get(String n)throws Exception {return get(c,n);}
    static Object call(String n,Class<?>[] types,Object...args)throws Exception {var m=MainController.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(c,args);}
    static Object call(String n)throws Exception {return call(n,new Class<?>[0]);}
    static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
    static <T>T fx(Callable<T> action)throws Exception {var f=new CompletableFuture<T>();Platform.runLater(()->{try{f.complete(action.call());}catch(Throwable t){f.completeExceptionally(t);}});return f.get(60,TimeUnit.SECONDS);}
    static void ready()throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(System.nanoTime()<end){if(fx(()->get("loadedFile")!=null && !(boolean)get("fileLoadPending") && get("outputAnalysisGeneration").equals(get("levelerReadyGeneration")) && (int)get("analyzeJobs")==0))return;Thread.sleep(25);}
        throw new AssertionError("EQ publication timed out");
    }
    static float[] pcm()throws Exception{return (float[])get(get("player"),"publishedRender");}
    static ChainPreset preset()throws Exception{return (ChainPreset)call("capturePreset");}
    static void apply(ChainPreset p)throws Exception {
        call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,false);
        if(get("loadedFile")!=null){call("invalidateActiveSlotRender");call("scheduleDynamicsRefreshAfterPresetApply");}
    }
    static float[] rawEq(float[] source,int rate,int ch,double db,int factor) {
        var eq=new EqualizerProcessor();var band=new MasterEqualizer.Band();
        band.frequency=857;band.gainDb=db;band.q=.71;band.type=MasterEqualizer.BandType.BELL;
        band.phase=MasterEqualizer.BandPhase.LINEAR;eq.setNumBands(1);eq.setBand(0,band);
        var p=new ProcessingPipeline();p.addProcessor(eq);
        var file=new WavFile("private-read-only",rate,ch,source,32,true);p.processOversampled(file,factor,null);return file.getSamples();
    }
    static void shot(Path file)throws Exception {
        root.applyCss();root.layout();call("drawWaveform");var img=root.snapshot(null,null);
        var png=new BufferedImage((int)img.getWidth(),(int)img.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,img.getPixelReader().getArgb(x,y));
        ImageIO.write(png,"png",file.toFile());
    }
    static void exportCheck(Path directory)throws Exception {
        float[] audible=fx(()->pcm());var file=fx(()->(AudioFile)get("loadedFile"));
        for(int bits:new int[]{32,24}) {
            Path target=directory.resolve("temporary-eq-export-"+bits+".wav");
            check(!Files.exists(target),"Refuse to overwrite export evidence");
            try {
                new WavFile(target.toString(),file.getSampleRate(),file.getChannels(),audible,bits,bits==32).save(target.toString());
                var read=new WavFile(target.toString());read.load();float[] actual=read.getSamples();
                check(actual.length==audible.length,"Export duration changed");double error=0;
                for(int i=0;i<audible.length;i++)error=Math.max(error,Math.abs(audible[i]-actual[i]));
                check(bits==32?error==0:error<4e-7,"Export clipped or differs beyond quantization/dither");
                check(TruePeak.measureMax(actual,file.getChannels())<1,"Export overload");
                System.out.printf(Locale.US,"EQ_EXPORT_PASS bits=%d maxPcmError=%.9g%n",bits,error);
            }finally{Files.deleteIfExists(target);}
        }
        var convert=MainController.class.getDeclaredMethod("resampleForExport",float[].class,int.class,int.class,int.class);convert.setAccessible(true);
        var protect=MainController.class.getDeclaredMethod("reclampTruePeak",float[].class,int.class,double.class);protect.setAccessible(true);
        float[] converted=(float[])convert.invoke(null,audible,file.getChannels(),file.getSampleRate(),44100);
        float[] raw=converted.clone();protect.invoke(null,converted,file.getChannels(),-.1);
        check(TruePeak.measureMax(converted,file.getChannels())<1,"Export SRC overload");
        int max=0;for(int i=1;i<raw.length;i++)if(Math.abs(raw[i])>Math.abs(raw[max]))max=i;
        double gain=converted[max]/(double)raw[max];for(int i=0;i<raw.length;i++)check(Math.abs(converted[i]-raw[i]*gain)<2e-7,"SRC protection is not a scalar");
        System.out.println("EQ_EXPORT_SRC_PASS deliveryRate=44100 scalar=true");
    }
    static void verify(String label,boolean baseline,int factor)throws Exception {
        var file=fx(()->(AudioFile)get("loadedFile"));float[] actual=fx(()->pcm());
        ChainPreset settings=fx(()->preset());
        float[] raw=rawEq(file.getSamples(),file.getSampleRate(),file.getChannels(),settings.bands.get(0).gainDb,factor);
        double rawTp=TruePeak.measureMax(raw,file.getChannels()), tp=TruePeak.measureMax(actual,file.getChannels());
        boolean auto=!baseline&&(boolean)ChainPreset.class.getField("eqAutoGain").get(settings);
        double eqGain=1;
        if(auto) {
            float[] base=factor==1?raw:rawEq(file.getSamples(),file.getSampleRate(),file.getChannels(),settings.bands.get(0).gainDb,1);
            var matcher=new com.dspark.effects.AutoGain();matcher.setMaxCompensationDb(24);
            // Reflection keeps the baseline mode runnable against pre-port JARs.
            double match=(double)matcher.getClass().getMethod("offlineGainDb",float[].class,float[].class,int.class,double.class,Runnable.class)
                .invoke(matcher,file.getSamples(),base,file.getChannels(),(double)file.getSampleRate(),null);
            eqGain=Math.pow(10,match/20);double basePeak=TruePeak.measureMax(base,file.getChannels());
            if(basePeak*eqGain>1)eqGain=Math.pow(10,-.1/20)/basePeak;
        }
        double target=Math.pow(10, (settings.normalizerOn?settings.normalizerTargetDbtp:-.1)/20);
        float expectedGain=(float)(settings.normalizerOn?target/rawTp:auto&&rawTp*eqGain>1?target/rawTp:eqGain);
        long over=0,rails=0;double residual=0,residualPower=0;
        for(int i=0;i<actual.length;i++){if(Math.abs(actual[i])>1)over++;if(Math.abs(actual[i])==1)rails++;
            double error=actual[i]-(float)(raw[i]*expectedGain);residual=Math.max(residual,Math.abs(error));residualPower+=error*error;}
        double residualRms=Math.sqrt(residualPower/actual.length);
        String gain=baseline?"unavailable":fx(()->((Label)get("eqAutoGainLabel")).getText());
        System.out.printf(Locale.US,"EQ_OUTPUT %s rawDbtp=%.9f outputDbtp=%.9f scalarDb=%.9f over=%d rails=%d residual=%.9g residualRms=%.9g visibleGain=%s%n",label,20*Math.log10(rawTp),20*Math.log10(tp),20*Math.log10(expectedGain),over,rails,residual,residualRms,gain);
        if(baseline){check(over>0,"Baseline no longer reproduces");check(Arrays.equals(raw,actual),"Unexpected EQ saturation before output");return;}
        if(auto)check(over==0 && tp<=1.000001,"EQ output still overloads");else check(Arrays.equals(raw,actual),"Manual gain is not exact");
        // At >1x the scalar is BEFORE cascaded float decimators. Moving it
        // after their sum is equal mathematically, not bitwise. Bound both the
        // worst error (-110 dBFS) and RMS (-130 dBFS), never change signal data.
        check(residual<(factor==1?4e-7:3e-6) && residualRms<3e-7,"Output is not the expected single scalar (or stale PCM)");
        double gainDb=20*Math.log10(eqGain);
        check(gain.equals(auto&&Math.abs(gainDb)>=.05?String.format(Locale.US,"%+.1f dB",gainDb):""),"Applied EQ gain hidden or stale");
        fx(()->{if(auto)for(float peak:(float[])get("waveformDownsampled"))check(peak<=1,"Red waveform overload remains");
            check(((Label)get("meterPeak")).getText().equals(String.format(Locale.US,"%.1f dBTP",20*Math.log10(tp))),"Output meter does not describe audible PCM");return null;});
    }
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]), out=Path.of(args[1]);boolean baseline=args.length>2&&args[2].equals("--baseline");Files.createDirectories(out);
        String before=com.quickmaster.config.LevelerExclusionStore.identity(source);Platform.startup(()->{});
        try {
            fx(()->{var loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));root=loader.load();c=loader.getController();
                if(!baseline)check(((Label)get("eqAutoGainLabel")).getText().isEmpty(),"Initial Auto Gain readout is not blank");
                var scene=new Scene(root,1360,900);scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                root.setMinSize(1360,900);root.setPrefSize(1360,900);root.setMaxSize(1360,900);
                stage=new javafx.stage.Stage();stage.setTitle("QuickMaster — EQ output verification");stage.setScene(scene);stage.show();
                var p=preset();p.autoEqOn=p.dynamicsOn=p.clipOn=p.limitOn=p.normalizerOn=p.osOn=false;p.eqOn=true;p.fadeInSec=p.fadeOutSec=0;
                var b=new ChainPreset.BandPreset();b.type="BELL";b.channel="STEREO";b.phase="LINEAR";b.frequency=857;b.gainDb=18.8;b.q=.71;
                b.aboveRatio=b.belowRatio=1;b.aboveAttackMs=b.belowAttackMs=10;b.aboveReleaseMs=b.belowReleaseMs=100;
                p.bands=List.of(b);apply(p);
                call("loadAudioFile",new Class<?>[]{java.io.File.class},source.toFile());return null;});
            ready();verify("boost18.8-1x",baseline,1);fx(()->{shot(out.resolve(baseline?"eq-before.png":"eq-after.png"));return null;});
            if(!baseline){
                fx(()->{
                    String approved=((Label)get("eqAutoGainLabel")).getText();
                    var type=com.quickmaster.playback.PreviewWindow.class;
                    for(double db:new double[]{0,-0.001,-6}) {
                        var preview=new com.quickmaster.playback.PreviewWindow(new float[2],0,0,2,new float[2],db,0);
                        call("showPreviewGain",new Class<?>[]{type},preview);
                        check(((Label)get("eqAutoGainLabel")).getText().equals(db==-6?"≈ -6.0 dB":""),"Preview compensation readout incorrect");
                    }
                    call("cancelInteractiveEqPreview");
                    check(((Label)get("eqAutoGainLabel")).getText().equals(approved),"Approved gain was not restored");
                    return null;
                });
                exportCheck(out);
                float[] a=fx(()->pcm());
                // The actual checkbox, not just DSP setters: undo/redo and preset persistence.
                fx(()->{((CheckBox)get("eqAutoGain")).fire();return null;});ready();verify("manual-off",false,1);
                fx(()->{check(!((CheckBox)get("eqAutoGain")).isSelected(),"Checkbox did not turn off");
                    var gson=new com.google.gson.Gson();var round=gson.fromJson(gson.toJson(preset()),ChainPreset.class);
                    check(!(boolean)ChainPreset.class.getField("eqAutoGain").get(round),"Preset lost off setting");call("onUndo");return null;});
                ready();verify("undo-on",false,1);fx(()->{call("onRedo");return null;});ready();verify("redo-off",false,1);
                fx(()->{call("onUndo");return null;});ready();a=fx(()->pcm());
                fx(()->{call("onSelectSlotB");var p=preset();p.bands.get(0).gainDb=-6;apply(p);return null;});ready();verify("cut-B",false,1);float[] b=fx(()->pcm());
                final float[] cachedA=a;
                fx(()->{call("onSelectSlotA");check(pcm()==cachedA,"A cache missed");call("onSelectSlotB");check(pcm()==b,"B cache missed");return null;});
                fx(()->{var p=preset();p.bands.get(0).gainDb=0;apply(p);return null;});ready();verify("neutral-blank",false,1);
                fx(()->{check(((CheckBox)get("eqAutoGain")).isSelected(),"Blank readout disabled Auto Gain");shot(out.resolve("eq-neutral-blank.png"));return null;});
                fx(()->{var p=preset();p.bands.get(0).gainDb=18.8;p.osOn=true;p.osFactor=4;apply(p);return null;});ready();verify("boost18.8-4x",false,4);
                fx(()->{var p=preset();p.normalizerOn=true;p.normalizerTargetDbtp=-1;apply(p);return null;});ready();verify("normalize-1-4x",false,4);
            }
            check(before.equals(com.quickmaster.config.LevelerExclusionStore.identity(source)),"Source changed");
            System.out.println(baseline?"EQ_OUTPUT_BASELINE_REPRODUCED":"EQ_OUTPUT_PASS scalar=true noOverload=true waveform=true meters=true cachedAB=true oversampling=true sourceUnchanged=true inactiveReadoutBlank=true");
        }finally{fx(()->{if(c!=null)c.shutdown();if(stage!=null)stage.close();return null;});Platform.exit();}
    }
}
