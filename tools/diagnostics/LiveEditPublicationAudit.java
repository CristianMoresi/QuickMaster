import com.quickmaster.audio.WavFile;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.ui.MainController;
import com.quickmaster.ui.Knob;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.animation.PauseTransition;
import javafx.scene.control.Slider;
import javafx.scene.control.CheckBox;
import javax.sound.sampled.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Captures the real controller/player path while an edit awaits analysis. No hardware device. */
public class LiveEditPublicationAudit {
    static Object get(Object o, String name) throws Exception {
        Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static void set(Object o,String name,Object value)throws Exception{
        Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o,value);
    }
    static void call(Object o,String name)throws Exception{
        Method m=o.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(o);
    }
    static void fx(Callable<Void> action)throws Exception{
        CompletableFuture<Void> f=new CompletableFuture<>();
        Platform.runLater(()->{try{action.call();f.complete(null);}catch(Throwable e){f.completeExceptionally(e);}});
        f.get(30,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception{
        Platform.startup(()->{});
        MainController[] c={null}; AudioPlayer[] p={null};
        CountDownLatch first=new CountDownLatch(1), resume=new CountDownLatch(1), closed=new CountDownLatch(1);
        List<Throwable> errors=new CopyOnWriteArrayList<>();
        double[] peak={0}, initialPeak={0}; long[] position={0}; int[] writes={0};
        SourceDataLine device=(SourceDataLine)Proxy.newProxyInstance(LiveEditPublicationAudit.class.getClassLoader(),
            new Class[]{SourceDataLine.class},(proxy,m,a)->{
                switch(m.getName()){
                    case "write": {
                        byte[] bytes=(byte[])a[0]; int offset=(int)a[1], n=(int)a[2];
                        if(writes[0]++==0){
                            for(int i=offset;i<offset+n;i+=2)initialPeak[0]=Math.max(initialPeak[0],Math.abs((short)((bytes[i]&255)|(bytes[i+1]<<8))/32768.0));
                            first.countDown();resume.await(10,TimeUnit.SECONDS);
                        }
                        if(writes[0]>3)for(int i=offset;i<offset+n;i+=2){
                            int value=(short)((bytes[i]&255)|(bytes[i+1]<<8));
                            peak[0]=Math.max(peak[0],Math.abs(value/32768.0));
                        }
                        position[0]+=n/2;return n;
                    }
                    case "getLongFramePosition":return position[0];
                    case "close":closed.countDown();return null;
                    default:return m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;
                }
            });
        try{
            fx(()->{
                FXMLLoader loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));loader.load();c[0]=loader.getController();
                ((AudioPlayer)get(c[0],"player")).close();
                Class<?> factory=Class.forName("com.quickmaster.playback.AudioPlayer$LineFactory");
                Object lineFactory=Proxy.newProxyInstance(factory.getClassLoader(),new Class[]{factory},(o,m,a)->device);
                Constructor<AudioPlayer> ctor=AudioPlayer.class.getDeclaredConstructor(ProcessingPipeline.class,factory,Consumer.class,Consumer.class);
                ctor.setAccessible(true);p[0]=ctor.newInstance(get(c[0],"pipeline"),lineFactory,(Consumer<Runnable>)Platform::runLater,(Consumer<Throwable>)errors::add);
                set(c[0],"player",p[0]);
                float[] samples=new float[32000]; for(int i=0;i<samples.length;i++)samples[i]=(float)(.1*Math.sin(2*Math.PI*700*i/8000));
                WavFile file=new WavFile("generated",8000,1,samples,32,true);
                set(c[0],"loadedFile",file); p[0].prepare(file);
                call(c[0],"onAddBand"); ((Knob)get(c[0],"kFreq")).setValue(700);
                ((Slider)get(c[0],"peakTarget")).setValue(-6);
                ((CheckBox)get(c[0],"peakEnabled")).setSelected(true);
                if(get(c[0],"dynRefreshDebounce") instanceof PauseTransition pause)pause.stop();
                call(c[0],"syncLiveAnalysis");return null;
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30); boolean[] ready={false};
            while(!ready[0]&&System.nanoTime()<deadline){Thread.sleep(20);fx(()->{ready[0]=get(c[0],"outputAnalysisGeneration").equals(get(c[0],"levelerReadyGeneration"));return null;});}
            if(!ready[0])throw new AssertionError("Initial analysis did not become ready");
            fx(()->{p[0].setAnalysisValid(true);p[0].play();return null;});
            if(!first.await(5,TimeUnit.SECONDS))throw new AssertionError("Player did not submit audio");
            fx(()->{
                ((Knob)get(c[0],"kGain")).setValue(18);
                if(get(c[0],"dynRefreshDebounce") instanceof PauseTransition pause)pause.stop();
                return null;
            });
            resume.countDown();if(!closed.await(10,TimeUnit.SECONDS))throw new AssertionError("Playback did not finish");
            double db=20*Math.log10(peak[0]);
            double initialDb=20*Math.log10(initialPeak[0]);
            System.out.printf(Locale.ROOT,"LIVE_EDIT_PUBLICATION initialPeakDb=%.6f pendingPeakDb=%.6f writes=%d errors=%s%n",initialDb,db,writes[0],errors);
            // Sample peak can sit below dBTP (the finite sine has a start boundary).
            if(initialDb> -5.99||initialDb< -7)throw new AssertionError("Harness did not start with the normalized master");
            if(!errors.isEmpty()||Math.abs(db-initialDb)>.01)throw new AssertionError("Pending controls changed the approved -6 dBTP master");
            System.out.println("LIVE_EDIT_PUBLICATION_PASS");
        }finally{
            resume.countDown();fx(()->{if(c[0]!=null)c[0].shutdown();return null;});Platform.exit();
        }
    }
}
