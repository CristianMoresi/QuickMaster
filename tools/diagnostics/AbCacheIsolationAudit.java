import com.quickmaster.audio.WavFile;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.dynamics.MacroLevelerProcessor;
import com.quickmaster.processing.dynamics.macro.LevelerExclusions;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Cache hits/misses and oversampling use the exact full-chain PCM, not tolerances. */
public final class AbCacheIsolationAudit {
    static MainController c;
    static Object get(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object get(String n)throws Exception{return get(c,n);}
    static void set(Object o,String n,Object v)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    static Object call(Object o,String n,Class<?>[] types,Object... args)throws Exception{var m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
    static Object call(String n)throws Exception{return call(c,n,new Class<?>[0]);}
    static <T>T fx(Callable<T> action)throws Exception{var f=new CompletableFuture<T>();Platform.runLater(()->{try{f.complete(action.call());}catch(Throwable e){f.completeExceptionally(e);}});return f.get(30,TimeUnit.SECONDS);}
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static void ready()throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<end){if(fx(()->get("outputAnalysisGeneration").equals(get("levelerReadyGeneration"))&&!(boolean)get("exporting")&&(int)get("analyzeJobs")==0))return;Thread.sleep(10);}
        throw new AssertionError("A/B not ready");
    }
    static void replaceSource(float[] pcm)throws Exception{
        var file=new WavFile("generated",24000,2,pcm,32,true);set(c,"loadedFile",file);
        ((AudioPlayer)get("player")).prepare(file);call("invalidateAllSlotRenders");call("invalidateOutputAnalysis");
    }
    static void renderVariant(String name,java.util.function.Consumer<ChainPreset> change,boolean prefixExpected)throws Exception{
        fx(()->{
            ChainPreset preset=(ChainPreset)call("capturePreset");change.accept(preset);
            char target=(char)get("activeSettingsSlot")=='A'?'B':'A';
            set(c,"settingsSlot"+target,preset);set(c,"renderSlot"+target,null);set(c,"snapshotSlot"+target,null);
            call(target=='A'?"onSelectSlotA":"onSelectSlotB");
            check(get("exportTask")!=null,"Variant must require rendering: "+name);
            Object task=get("exportTask"),preparation=null;
            for(var f:task.getClass().getDeclaredFields())if(f.getType().getSimpleName().equals("AuditionRender")){f.setAccessible(true);preparation=f.get(task);}
            check(preparation!=null,"Missing shared rendering path");
            check((get(preparation,"prefix")!=null)==prefixExpected,"Incorrect prefix hit/miss: "+name);return null;
        });ready();
        record Cold(ProcessingPipeline pipeline,float[] source,float[] actual,int factor){}
        Cold cold=fx(()->{
            FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));loader.load();MainController fresh=loader.getController();
            try {
                call(fresh,"applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},call("capturePreset"),false);
                ((MacroLevelerProcessor)get(fresh,"leveler")).setExclusions(((MacroLevelerProcessor)get("leveler")).getExclusions(),24000);
                ProcessingPipeline pipeline=(ProcessingPipeline)get(call(fresh,"buildSnapshot",new Class<?>[0]),"pipeline");
                return new Cold(pipeline,((WavFile)get("loadedFile")).getSamples(),(float[])get(get("player"),"publishedRender"),(int)get("oversampling"));
            }finally{fresh.shutdown();}
        });
        cold.pipeline.prepare(24000,cold.source.length);
        float[] expected=cold.pipeline.analyzeAndRender(cold.source,2,0,null,null,null);
        if(cold.factor>1)expected=cold.pipeline.renderAnalyzedOversampled(cold.source,2,cold.factor,null,null);
        for(int i=0;i<expected.length;i++)check(Float.floatToRawIntBits(expected[i])==Float.floatToRawIntBits(cold.actual[i]),"Cold PCM mismatch "+name+" sample="+i);
        System.out.printf("AB_ISOLATION_CASE %s prefixHit=%s factor=%d bitExact=true%n",name,prefixExpected,cold.factor);
    }
    public static void main(String[] args)throws Exception{
        Platform.startup(()->{});
        try {
            fx(()->{
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));loader.load();c=loader.getController();
                ChainPreset p=(ChainPreset)call("capturePreset");p.autoEqOn=true;p.autoEqAmount=.6;p.eqOn=true;p.dynamicsOn=true;p.levelerOn=true;p.leveling=.4;p.normalizerOn=true;
                call(c,"applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,false);
                float[] pcm=new float[24000*2*12];for(int f=0;f<pcm.length/2;f++){
                    double amp=f<24000*6?.03:.2;pcm[2*f]=(float)(amp*Math.sin(f*.074));pcm[2*f+1]=(float)(amp*.8*Math.sin(f*.074+.1));
                }
                replaceSource(pcm);call("syncLiveAnalysis");return null;
            });ready();
            renderVariant("dynamics-only",p->p.leveling=.8,true);
            renderVariant("auto-eq-amount",p->p.autoEqAmount=.2,false);
            renderVariant("eq-gain",p->{
                var b=new ChainPreset.BandPreset();b.frequency=800;b.gainDb=3;b.q=.7;
                b.aboveRatio=b.belowRatio=2;b.aboveAttackMs=b.belowAttackMs=10;b.aboveReleaseMs=b.belowReleaseMs=100;
                p.bands.add(b);
            },false);
            renderVariant("fade",p->p.fadeInSec=.7,false);
            renderVariant("oversampling-2x",p->{p.osOn=true;p.osFactor=2;},true);
            renderVariant("oversampling-8x",p->p.osFactor=8,true);
            fx(()->{
                ((MacroLevelerProcessor)get("leveler")).setExclusions(LevelerExclusions.EMPTY.add(24000,24000*3),24000);
                call("invalidateAllSlotRenders");call("invalidateOutputAnalysis");return null;
            });
            renderVariant("exclusions",p->p.osFactor=2,true);
            fx(()->{
                float[] changed=((WavFile)get("loadedFile")).getSamples().clone();changed[1111]+=.05f;replaceSource(changed);return null;
            });
            renderVariant("source-same-length",p->p.leveling=.6,false);
            renderVariant("chain-reorder",p->Collections.swap(p.chainOrder,0,1),false);
            System.out.println("AB_CACHE_ISOLATION_PASS cases=9 allRawBitExact=true");
        }finally{fx(()->{if(c!=null)c.shutdown();return null;});Platform.exit();}
    }
}
