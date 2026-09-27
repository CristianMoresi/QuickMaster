import com.quickmaster.audio.WavFile;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.ui.MainController;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Slider;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.concurrent.Task;
import javafx.animation.PauseTransition;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Actual A/B controls, cache reuse and cancellation. No hardware device is opened. */
public class SlotPublicationAudit {
    static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    static void call(Object o,String n)throws Exception{Method m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(o);}
    static void fx(Callable<Void> action)throws Exception{
        CompletableFuture<Void> f=new CompletableFuture<>(); Platform.runLater(()->{try{action.call();f.complete(null);}catch(Throwable e){f.completeExceptionally(e);}});f.get(30,TimeUnit.SECONDS);
    }
    static void ready(MainController c)throws Exception{
        boolean[] ready={false};long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!ready[0]&&System.nanoTime()<deadline){Thread.sleep(20);fx(()->{ready[0]=get(c,"outputAnalysisGeneration").equals(get(c,"levelerReadyGeneration"))&&!(boolean)get(c,"exporting")&&(int)get(c,"analyzeJobs")==0;return null;});}
        if(!ready[0])throw new AssertionError("A/B analysis did not finish");
    }
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Platform.startup(()->{});MainController[] c={null};
        try{
            fx(()->{
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));loader.load();c[0]=loader.getController();
                float[] pcm=new float[48000*4];for(int i=0;i<pcm.length;i++)pcm[i]=(float)(.2*Math.sin(i*.1));
                WavFile file=new WavFile("generated",48000,1,pcm,32,true);set(c[0],"loadedFile",file);
                ((AudioPlayer)get(c[0],"player")).prepare(file);
                ((CheckBox)get(c[0],"peakEnabled")).setSelected(true);
                ((Slider)get(c[0],"peakTarget")).setValue(-1);
                if(get(c[0],"dynRefreshDebounce") instanceof PauseTransition pause)pause.stop();
                call(c[0],"syncLiveAnalysis");return null;
            });ready(c[0]);
            Object[] a={null},b={null};
            fx(()->{a[0]=get(c[0],"renderSlotA");check(a[0]!=null,"Initial approved render must populate A");call(c[0],"onSelectSlotB");return null;});ready(c[0]);
            fx(()->{
                ((Slider)get(c[0],"peakTarget")).setValue(-7);
                if(get(c[0],"dynRefreshDebounce") instanceof PauseTransition pause)pause.stop();
                call(c[0],"syncLiveAnalysis");return null;
            });ready(c[0]);
            fx(()->{
                b[0]=get(c[0],"renderSlotB");check(b[0]!=null,"B render missing");
                check(((Label)get(c[0],"meterPeak")).getText().equals("-7.0 dBTP"),"B meter must show B's measured output");
                call(c[0],"onSelectSlotA");check(get(c[0],"exportTask")==null,"Cached A must not render again");
                check(get(get(c[0],"player"),"publishedRender")==a[0],"A must publish its exact cached PCM");
                check(((Label)get(c[0],"meterPeak")).getText().equals("-1.0 dBTP"),"Cached A meter remained stale: "+((Label)get(c[0],"meterPeak")).getText());
                call(c[0],"onSelectSlotB");check(get(get(c[0],"player"),"publishedRender")==b[0],"B must publish its exact cached PCM");
                check(((Label)get(c[0],"meterPeak")).getText().equals("-7.0 dBTP"),"Cached B meter did not restore");
                // Force uncached A and cancel within the same FX turn, before any callback can win.
                set(c[0],"renderSlotA",null);set(c[0],"snapshotSlotA",null);
                call(c[0],"onSelectSlotA");Task<?> task=(Task<?>)get(c[0],"exportTask");check(task!=null,"Uncached comparison must create a worker");
                task.cancel(true);return null;
            });ready(c[0]);
            fx(()->{
                check((char)get(c[0],"activeSettingsSlot")=='B',"Cancelled comparison must restore B");
                check(((Slider)get(c[0],"peakTarget")).getValue()==-7,"Cancelled comparison must restore parameters");
                check(((AudioPlayer)get(c[0],"player")).hasPublishedRender(),"Cancellation must keep an approved render");
                System.out.println("SLOT_PUBLICATION_PASS cachedA=true cachedB=true metersFollowPcm=true cancelRestoresControls=true");return null;
            });
        }finally{fx(()->{if(c[0]!=null)c[0].shutdown();return null;});Platform.exit();}
    }
}
