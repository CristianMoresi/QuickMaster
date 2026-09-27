import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckBox;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Real settings-slot actions on read-only audio; compares published PCM to a cold controller. */
public final class AbPreparationProbe {
    static MainController c;
    static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object get(String n)throws Exception{return get(c,n);}
    static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    static Object call(Object o,String n,Class<?>[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
    static Object call(String n)throws Exception{return call(c,n,new Class<?>[0]);}
    static <T>T fx(Callable<T> action)throws Exception{CompletableFuture<T> f=new CompletableFuture<>();Platform.runLater(()->{try{f.complete(action.call());}catch(Throwable e){f.completeExceptionally(e);}});return f.get(45,TimeUnit.SECONDS);}
    static void check(boolean b,String text){if(!b)throw new AssertionError(text);}
    static void ready()throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(System.nanoTime()<deadline){
            if(fx(()->get("loadedFile")!=null&&!(boolean)get("fileLoadPending")&&!(boolean)get("exporting")
                    &&get("outputAnalysisGeneration").equals(get("levelerReadyGeneration"))&&(int)get("analyzeJobs")==0))return;
            Thread.sleep(10);
        }
        throw new AssertionError("A/B preparation timed out");
    }
    static void amount(double v)throws Exception{
        Object knob=get("levelingKnob");knob.getClass().getMethod("setValue",double.class).invoke(knob,v);
        ((PauseTransition)get("dynRefreshDebounce")).stop();call("syncLiveAnalysis");
    }
    static void select(char slot)throws Exception{call(slot=='A'?"onSelectSlotA":"onSelectSlotB");}
    record Reference(float[] source,float[] actual,ProcessingPipeline pipeline,int rate,int channels,int os){}
    static void coldExact(String label)throws Exception{
        Reference r=fx(()->{
            AudioFile audio=(AudioFile)get("loadedFile");
            FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));loader.load();MainController fresh=loader.getController();
            try {
                set(fresh,"trackAnalysis",get("trackAnalysis"));
                ChainPreset preset=(ChainPreset)call("capturePreset");
                call(fresh,"applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},preset,false);
                var live=(com.quickmaster.processing.dynamics.MacroLevelerProcessor)get("leveler");
                var copy=(com.quickmaster.processing.dynamics.MacroLevelerProcessor)get(fresh,"leveler");
                copy.setExclusions(live.getExclusions(),audio.getSampleRate());
                Object snap=call(fresh,"buildSnapshot",new Class<?>[0]);
                return new Reference(audio.getSamples(),(float[])get(get("player"),"publishedRender"),
                        (ProcessingPipeline)get(snap,"pipeline"),audio.getSampleRate(),audio.getChannels(),(int)get("oversampling"));
            } finally {fresh.shutdown();}
        });
        long start=System.nanoTime();r.pipeline.prepare(r.rate,r.source.length);
        float[] expected=r.pipeline.analyzeAndRender(r.source,r.channels,0,null,null,null);
        if(r.os>1)expected=r.pipeline.renderAnalyzedOversampled(r.source,r.channels,r.os,null,null);
        double elapsed=(System.nanoTime()-start)/1e9;check(r.actual!=null&&expected.length==r.actual.length,"Missing published PCM");
        long different=0,changed=0;
        for(int i=0;i<expected.length;i++){
            if(Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(r.actual[i]))different++;
            if(Float.floatToRawIntBits(r.source[i])!=Float.floatToRawIntBits(r.actual[i]))changed++;
        }
        check(different==0,"A/B differs from full cold rendering: "+different+" samples");check(changed>0,"Unexpected bypass");
        System.out.printf(Locale.ROOT,"AB_COLD_EXACT %s coldSec=%.6f rawBitDifferences=%d changedSamples=%d%n",label,elapsed,different,changed);
    }
    static double switchTimed(char slot,String label,boolean invalidate)throws Exception{
        boolean[] worker={false};long start=System.nanoTime();
        fx(()->{if(invalidate){set(c,"renderSlot"+slot,null);set(c,"snapshotSlot"+slot,null);}select(slot);worker[0]=get("exportTask")!=null;return null;});
        long audioDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(!fx(()->get("outputAnalysisGeneration").equals(get("levelerReadyGeneration")))){
            check(System.nanoTime()<audioDeadline,"A/B audio timeout");Thread.sleep(10);
        }
        double elapsed=(System.nanoTime()-start)/1e9;ready();
        System.out.printf(Locale.ROOT,"AB_READY %s audioSec=%.6f rendered=%s%n",label,elapsed,worker[0]);return elapsed;
    }
    public static void main(String[] args)throws Exception{
        Locale.setDefault(Locale.ROOT);boolean fast=Arrays.asList(args).contains("--assert-fast");Platform.startup(()->{});
        System.out.println("JAR "+MainController.class.getProtectionDomain().getCodeSource().getLocation());
        try {
            fx(()->{
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));loader.load();c=loader.getController();
                for(Object module:(List<?>)get("chainModules"))((CheckBox)get(module,"enableBox")).setSelected(true);
                for(Object card:(List<?>)get("dynCards"))((CheckBox)get(card,"on")).setSelected(true);
                for(Object card:(List<?>)get("clipCardList"))((CheckBox)get(card,"on")).setSelected(true);
                ((CheckBox)get("peakEnabled")).setSelected(true);
                Object knob=get("levelingKnob");knob.getClass().getMethod("setValue",double.class).invoke(knob,.8);
                if(get("dynRefreshDebounce") instanceof PauseTransition pause)pause.stop();
                call(c,"loadAudioFile",new Class<?>[]{java.io.File.class},Path.of(args[0]).toFile());return null;
            });ready();
            System.out.println("AB_CONFIGURATION "+fx(()->new com.google.gson.Gson().toJson(call("capturePreset"))));
            Object a=fx(()->get("renderSlotA"));
            double first=switchTimed('B',"first-identical-B",false);
            if(fast){check(fx(()->get("renderSlotB"))==a,"Identical slots must share approved immutable PCM");check(first<.5,"Identical B was not immediate");}
            fx(()->{amount(.4);return null;});ready();coldExact("edited-B");
            switchTimed('A',"cached-A",false);
            for(int i=0;i<3;i++){
                switchTimed('B',"uncached-B-"+i,true);if(i==0)coldExact("uncached-B");
                switchTimed('A',"cached-A-"+i,false);
            }
            System.out.println("AB_PREPARATION_PASS coldFreshController=true bitExact=true");
        }finally{fx(()->{if(c!=null)c.shutdown();return null;});Platform.exit();}
    }
}
