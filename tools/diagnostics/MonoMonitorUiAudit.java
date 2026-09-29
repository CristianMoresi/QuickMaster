import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.ChainPreset;
import com.quickmaster.config.LevelerExclusionStore;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.processing.stereo.StereoImageSettings;
import com.quickmaster.ui.*;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import java.nio.file.*;
import java.util.*;

/** Real FXML/controller regression. Snapshots only; never opens a hardware device. */
public final class MonoMonitorUiAudit extends StereoUiAudit {
    static void click(javafx.scene.Node node,int count) {
        Event.fireEvent(node,new MouseEvent(MouseEvent.MOUSE_CLICKED,4,4,4,4,MouseButton.PRIMARY,count,
                false,false,false,false,false,false,false,false,false,true,null));
    }
    public static void main(String[] args)throws Exception {
        Path audio=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
        String identity=LevelerExclusionStore.identity(audio);
        System.out.println("APPLICATION_CLASSES "+MainController.class.getProtectionDomain().getCodeSource().getLocation());
        Platform.startup(()->{});
        try {
            fx(()->{
                var loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));root=loader.load();c=loader.getController();
                var scene=new Scene(root,1360,900);scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                var knob=(Knob)root.lookup("#stereoGenerationAmount");
                check(knob.getValue()==25 && (double)get(knob,"defaultValue")==25,"Generation/reset default is not 25%");
                for(String id:new String[]{"stereoGeneration","stereoLeveler","stereoGuard"})
                    check(!((ToggleButton)root.lookup("#"+id)).isSelected(),"Section starts enabled: "+id);
                for(String id:new String[]{"stereoLowCutValue","stereoSideGainValue"})
                    check(root.lookup("#"+id) instanceof Label,"Slider value is still interactive: "+id);
                var mono=(ToggleButton)root.lookup("#listenMono");
                check(!mono.isSelected()&&mono.getText().equals("Listen in mono"),"Incorrect monitor default/text");
                var p=(ChainPreset)call("capturePreset");p.autoEqOn=p.eqOn=p.dynamicsOn=p.clipOn=p.limitOn=p.normalizerOn=p.osOn=false;
                p.fadeInSec=p.fadeOutSec=0;p.stereoOn=false;p.stereoImage=StereoImageSettings.DEFAULT;
                call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,false);
                for(Object module:(List<?>)get("chainModules"))if(get(module,"name").equals("Stereo Image"))call("selectModule",new Class<?>[]{module.getClass()},module);
                call("loadAudioFile",new Class<?>[]{java.io.File.class},audio.toFile());return null;
            });
            ready();fx(()->{shot(out.resolve("stereo-default-25.png"),1360,900);return null;});
            fx(()->{((ToggleButton)root.lookup("#stereoGeneration")).fire();return null;});ready();exact();
            record Before(Object pcm,long generation,String preset,float[] source){}
            Before before=fx(()->new Before(pcm(),(long)get("outputAnalysisGeneration"),new com.google.gson.Gson().toJson(call("capturePreset")),((AudioFile)get("loadedFile")).getSamples().clone()));
            fx(()->{
                int windows=Window.getWindows().size();
                for(String id:new String[]{"stereoLowCutValue","stereoSideGainValue"}) {
                    click(root.lookup("#"+id),1);click(root.lookup("#"+id),2);
                    check(Window.getWindows().size()==windows,"Readout opened a window");
                }
                var mono=(ToggleButton)root.lookup("#listenMono");var player=(AudioPlayer)get("player");
                long position=player.getPositionSamples();
                for(int i=0;i<41;i++){mono.fire();check(player.isListenInMono()==mono.isSelected(),"Button did not reach player");}
                check(player.isListenInMono(),"Final requested monitor state lost");
                check(player.getPositionSamples()==position,"Monitor changed transport position");
                check(before.preset().equals(new com.google.gson.Gson().toJson(call("capturePreset"))),"Monitor changed master/preset/export settings");
                check(pcm()==before.pcm(),"Monitor replaced render");
                check((long)get("outputAnalysisGeneration")==before.generation(),"Monitor invalidated analysis");
                check((int)get("analyzeJobs")==0,"Monitor scheduled analysis");
                shot(out.resolve("stereo-mono-on.png"),1360,900);
                var bounds=mono.localToScene(mono.getBoundsInLocal());
                var side=root.lookup("#sideRow");var sideBounds=side.localToScene(side.getBoundsInLocal());
                check(bounds.getMinY()>=sideBounds.getMaxY(),"Monitor is not below Mid/Side");
                check(bounds.getMaxX()<=1360 && bounds.getMaxY()<=900,"Monitor outside scene");
                shot(out.resolve("stereo-compact-mono.png"),1100,760);
                player.toggleAB();check(player.isListenInMono(),"Bypass lost mono state");player.toggleAB();
                mono.fire();check(!player.isListenInMono(),"Monitor cannot turn off");
                return null;
            });
            Thread.sleep(750);
            fx(()->{
                check((long)get("outputAnalysisGeneration")==before.generation() && (int)get("analyzeJobs")==0,"Delayed monitor analysis");
                check(pcm()==before.pcm(),"Delayed monitor render replacement");
                check(Arrays.equals(before.source(),((AudioFile)get("loadedFile")).getSamples()),"Source buffer changed");
                return null;
            });
            check(identity.equals(LevelerExclusionStore.identity(audio)),"Private file changed");
            System.out.println("MONO_UI_PASS defaults25=true dialogs=false monitorOnly=true renderIdentity=true noAnalysis=true sourceUnchanged=true noAudioDevice=true");
        } finally {fx(()->{if(c!=null)c.shutdown();return null;});Platform.exit();}
    }
}
