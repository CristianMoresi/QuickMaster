import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.LevelerExclusionStore;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.processing.dynamics.MacroLevelerProcessor;
import com.quickmaster.processing.dynamics.macro.LevelerExclusions;
import com.quickmaster.playback.AudioPlayer;
import com.quickmaster.ui.MainController;
import com.quickmaster.ui.waveform.WaveformViewport;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.scene.layout.Region;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real FXML controls, mouse dispatch, async publication and screenshots. No source writes or DAC claims. */
public class LevelerExclusionUiAudit {
    static MainController c;static Region root;static Canvas wave;static Path output;
    static javafx.stage.Stage stage;
    static Object field(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static Object field(String n)throws Exception{return field(c,n);}
    static void set(String n,Object v)throws Exception{var f=MainController.class.getDeclaredField(n);f.setAccessible(true);f.set(c,v);}
    static Object call(String n)throws Exception{return call(n,new Class<?>[0]);}
    static Object call(String n,Class<?>[] types,Object... args)throws Exception{var m=MainController.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(c,args);}
    static <T>T fx(Callable<T> action)throws Exception{var f=new CompletableFuture<T>();Platform.runLater(()->{try{f.complete(action.call());}catch(Throwable e){f.completeExceptionally(e);}});return f.get(30,TimeUnit.SECONDS);}
    static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
    static MacroLevelerProcessor leveler()throws Exception{return (MacroLevelerProcessor)field("leveler");}
    static double x(double seconds)throws Exception{return ((WaveformViewport)field("waveformViewport")).xAtTime(seconds,wave.getWidth()).orElseThrow();}
    static void mouse(javafx.event.EventType<MouseEvent> type,double x,double y) {
        var point=wave.localToScene(x,y);
        wave.fireEvent(new MouseEvent(type,point.getX(),point.getY(),0,0,MouseButton.PRIMARY,1,false,false,false,false,
                type!=MouseEvent.MOUSE_RELEASED,false,false,false,false,true,new PickResult(wave,point.getX(),point.getY())));
    }
    static void drag(double start,double end)throws Exception{
        mouse(MouseEvent.MOUSE_PRESSED,x(start),70);mouse(MouseEvent.MOUSE_DRAGGED,x(end),70);mouse(MouseEvent.MOUSE_RELEASED,x(end),70);
    }
    static void ready()throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(System.nanoTime()<deadline){
            if(fx(()->!(boolean)field("fileLoadPending")&&field("loadedFile")!=null&&field("outputAnalysisGeneration").equals(field("levelerReadyGeneration"))&&(int)field("analyzeJobs")==0))return;
            Thread.sleep(40);
        }
        throw new AssertionError("Timed out waiting for current published PCM");
    }
    static void screenshot(String name)throws Exception{
        root.applyCss();root.layout();call("drawWaveform");
        WritableImage image=name.equals("exclusions-compact")?root.getScene().snapshot(null):root.snapshot(null,null);
        var png=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        ImageIO.write(png,"png",output.resolve(name+".png").toFile());
    }
    static void verifyPcm()throws Exception{
        AudioFile audio=(AudioFile)field("loadedFile");float[] source=audio.getSamples();
        float[] actual=(float[])field(field("player"),"publishedRender");int ch=audio.getChannels();
        require(actual!=null&&actual.length==source.length,"Missing current PCM");
        long protectedSamples=0,boosted=0;
        for(var r:leveler().getExclusions().regions())for(int i=(int)r.start()*ch;i<(int)r.end()*ch;i++){
            require(Float.floatToRawIntBits(source[i])==Float.floatToRawIntBits(actual[i]),"Protected PCM changed at "+i);protectedSamples++;
        }
        for(int i=0;i<actual.length;i++){
            require(Float.isFinite(actual[i])&&Math.abs(actual[i])>=Math.abs(source[i]),"Non-finite or negative gain");
            if(Math.abs(actual[i])>Math.abs(source[i])*1.12)boosted++;
        }
        require(protectedSamples>0&&boosted>1000,"No useful protection/boost");
        System.out.printf("EXCLUSION_PCM_PASS protectedRawExact=%d boostedSamples=%d gainNeverNegative=true%n",protectedSamples,boosted);
    }
    public static void main(String[] args)throws Exception{
        Locale.setDefault(Locale.ROOT);Path audio=Path.of(args[0]);output=Path.of(args[1]);Files.createDirectories(output);
        String identity=LevelerExclusionStore.identity(audio);
        Platform.startup(()->{});int code=0;
        try{
            fx(()->{
                var loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));root=loader.load();c=loader.getController();
                var scene=new Scene(root,1360,830);scene.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                root.setMinSize(1360,830);root.setPrefSize(1360,830);root.setMaxSize(1360,830);root.resize(1360,830);root.applyCss();root.layout();wave=(Canvas)field("waveformCanvas");
                stage=new javafx.stage.Stage();stage.setTitle("QuickMaster — Leveler UI verification");stage.setScene(scene);stage.show();
                var modules=(List<?>)field("chainModules");
                for(Object module:modules)((CheckBox)field(module,"enableBox")).setSelected(false);
                for(var p:((ProcessingPipeline)field("pipeline")).getProcessors())p.setEnabled(false);
                for(Object card:(List<?>)field("dynCards"))((CheckBox)field(card,"on")).setSelected(false);
                ((CheckBox)field("dynMasterEnabled")).setSelected(true);
                for(Object card:(List<?>)field("dynCards"))if(field(card,"name").equals("Leveler"))((CheckBox)field(card,"on")).setSelected(true);
                Object knob=field("levelingKnob");knob.getClass().getMethod("setValue",double.class).invoke(knob,1.0);
                if(field("dynRefreshDebounce") instanceof PauseTransition p)p.stop();
                call("selectModule",new Class<?>[]{modules.get(1).getClass()},modules.get(1));
                call("loadAudioFile",new Class<?>[]{java.io.File.class},audio.toFile());return null;
            });ready();
            fx(()->{
                require(leveler().getExclusions().regions().isEmpty(),"Use isolated APPDATA");
                var edit=(ToggleButton)root.lookup("#leveler-exclude-regions");require(edit!=null&&!edit.isDisabled(),"Missing enabled edit control");edit.fire();
                long generation=(long)field("outputAnalysisGeneration"),position=((AudioPlayer)field("player")).getPositionSamples();
                mouse(MouseEvent.MOUSE_PRESSED,x(0),70);mouse(MouseEvent.MOUSE_DRAGGED,x(24),70);
                require((long)field("outputAnalysisGeneration")==generation,"DSP ran during preview drag");
                require(((AudioPlayer)field("player")).getPositionSamples()==position,"Exclusion drag sought transport");
                require((double)field("selStartSec")==-1,"Exclusion changed crop/loop selection");
                mouse(MouseEvent.MOUSE_RELEASED,x(24),70);return null;
            });ready();fx(()->{verifyPcm();drag(100,112);return null;});ready();
            LevelerExclusions two=fx(()->leveler().getExclusions());
            fx(()->{require(two.regions().size()==2,"Expected two regions");screenshot("exclusions-full");drag(24,30);return null;});ready();
            fx(()->{require(Math.abs(leveler().getExclusions().regions().get(0).end()-30L*48000)<=1,"Resize failed");verifyPcm();call("onUndo");return null;});ready();
            fx(()->{require(leveler().getExclusions().equals(two),"Undo lost regions");call("onRedo");return null;});ready();
            LevelerExclusions resized=fx(()->leveler().getExclusions());
            fx(()->{
                var edit=(ToggleButton)root.lookup("#leveler-exclude-regions");if(!edit.isSelected())edit.fire();
                long generation=(long)field("outputAnalysisGeneration");
                mouse(MouseEvent.MOUSE_PRESSED,x(150),70);mouse(MouseEvent.MOUSE_DRAGGED,x(160),70);
                root.getScene().getRoot().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ESCAPE,false,false,false,false));
                mouse(MouseEvent.MOUSE_RELEASED,x(160),70);
                require(leveler().getExclusions().equals(resized)&&(long)field("outputAnalysisGeneration")==generation,"Escape committed cancelled gesture");
                long position=((AudioPlayer)field("player")).getPositionSamples();
                var point=wave.localToScene(x(22),70);
                wave.fireEvent(new ScrollEvent(ScrollEvent.SCROLL,point.getX(),point.getY(),0,0,false,true,false,false,false,false,0,120,0,120,
                        ScrollEvent.HorizontalTextScrollUnits.NONE,0,ScrollEvent.VerticalTextScrollUnits.NONE,0,0,new PickResult(wave,point.getX(),point.getY())));
                require(((AudioPlayer)field("player")).getPositionSamples()==position,"Zoom sought transport");
                require(leveler().getExclusions().equals(resized),"Zoom changed region times");screenshot("exclusions-zoom");
                Object batch=call("buildSnapshot",new Class<?>[]{TrackAnalysis.class},new TrackAnalysis());
                for(var p:((ProcessingPipeline)field(batch,"pipeline")).getProcessors())if(p instanceof MacroLevelerProcessor m)require(m.getExclusions().regions().isEmpty(),"Batch inherited current track mask");
                require(!new com.google.gson.Gson().toJson(call("capturePreset")).contains("regions"),"Generic preset contains track regions");
                call("loadAudioFile",new Class<?>[]{java.io.File.class},audio.toFile());return null;
            });ready();
            fx(()->{require(leveler().getExclusions().equals(resized),"Reload did not restore saved source regions");verifyPcm();
                root.getScene().setRoot(new javafx.scene.layout.StackPane());
                var group=new Group(root);group.setScaleX(.7);group.setScaleY(.7);
                var small=new Scene(new javafx.scene.layout.StackPane(group),952,581);
                small.getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                stage.setScene(small);
                screenshot("exclusions-design-after-reload");
                screenshot("exclusions-compact");
                ((Button)root.lookup("#leveler-clear-regions")).fire();return null;});ready();
            fx(()->{require(leveler().getExclusions().regions().isEmpty(),"Clear all failed");call("onUndo");return null;});ready();
            fx(()->{require(leveler().getExclusions().equals(resized),"Clear all undo failed");verifyPcm();call("onSelectSlotB");return null;});ready();
            fx(()->{require(leveler().getExclusions().equals(resized),"A/B lost track regions");
                require(!root.lookup("#leveler-exclude-regions").isDisabled(),"Editor remained disabled after A/B");
                verifyPcm();call("onSelectSlotA");return null;});ready();
            fx(()->{
                require(leveler().getExclusions().equals(resized),"Returning to cached A lost mask");
                var currentScene=root.getScene();currentScene.setRoot(new javafx.scene.layout.StackPane());
                if(root.getParent() instanceof Group group)group.getChildren().remove(root);
                stage.setScene(new Scene(root,1360,830));stage.getScene().getStylesheets().add(MainController.class.getResource("app.css").toExternalForm());
                root.applyCss();root.layout();
                Object editor=field("levelerRegionEditor");
                var position=wave.localToScene(x(105),70);
                wave.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED,position.getX(),position.getY(),50,50,MouseButton.SECONDARY,1,
                    false,false,false,false,false,false,true,false,true,true,new PickResult(wave,position.getX(),position.getY())));
                ContextMenu menu=(ContextMenu)field(editor,"menu");require(menu!=null&&menu.isShowing(),"No exclusion context menu");
                menu.getItems().get(1).fire();menu.hide();return null;
            });ready();
            fx(()->{require(leveler().getExclusions().regions().size()==1,"Context removal failed");call("onUndo");return null;});ready();
            fx(()->{require(leveler().getExclusions().equals(resized),"Context removal undo failed");
                // Test numeric dialog through its real menu action, including validation.
                var position=wave.localToScene(x(105),70);
                wave.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED,position.getX(),position.getY(),50,50,MouseButton.SECONDARY,1,
                    false,false,false,false,false,false,true,false,true,true,new PickResult(wave,position.getX(),position.getY())));
                ContextMenu menu=(ContextMenu)field(field("levelerRegionEditor"),"menu");
                var fill=new PauseTransition(javafx.util.Duration.millis(150));
                fill.setOnFinished(event->{
                    for(var window:javafx.stage.Window.getWindows())if(window instanceof javafx.stage.Stage dialog&&dialog.getTitle().equals("Edit Leveler exclusion")) {
                        DialogPane pane=(DialogPane)dialog.getScene().getRoot();
                        var box=(javafx.scene.layout.VBox)pane.getContent();
                        TextField start=(TextField)box.getChildren().get(1);TextField end=(TextField)box.getChildren().get(3);
                        start.setText("NaN");((Button)pane.lookupButton(ButtonType.APPLY)).fire();
                        require(dialog.isShowing(),"Invalid bounds accepted");
                        start.setText("101.25");end.setText("111.75");((Button)pane.lookupButton(ButtonType.APPLY)).fire();break;
                    }
                });fill.play();menu.hide();menu.getItems().get(0).fire();return null;
            });ready();
            fx(()->{var r=leveler().getExclusions().regions().get(1);require(r.start()==101.25*48000&&r.end()==111.75*48000,"Numeric bounds not committed");verifyPcm();return null;});
            LevelerExclusions numeric=fx(()->leveler().getExclusions());
            fx(()->{set("selStartSec",10.0);set("selEndSec",120.0);call("onCropSelection");return null;});ready();
            fx(()->{
                require(leveler().getExclusions().equals(numeric.crop(480000,5760000)),"Crop did not remap regions");
                require(field("exclusionSourceKey")==null,"Edited timeline overwrites original metadata");verifyPcm();call("onUndo");return null;
            });ready();
            fx(()->{
                require(leveler().getExclusions().equals(numeric),"Crop undo did not restore source regions");
                set("selStartSec",10.0);set("selEndSec",110.0);call("onDeleteSelection");return null;
            });ready();
            fx(()->{require(leveler().getExclusions().equals(numeric.delete(480000,5280000)),"Delete did not remap regions");verifyPcm();call("onUndo");return null;});ready();
            fx(()->{require(leveler().getExclusions().equals(numeric),"Delete undo lost regions");verifyPcm();return null;});
            require(identity.equals(LevelerExclusionStore.identity(audio)),"Source audio changed");
            System.out.println("EXCLUSION_UI_PASS englishControls=true dragResizeUndoRedo=true cancel=true zoom=true persistence=true slots=true contextMenu=true numericBounds=true cropDeleteUndo=true batchIsolated=true sourceUnchanged=true");
        }catch(Throwable e){e.printStackTrace();code=1;}
        finally{if(c!=null)fx(()->{c.shutdown();if(stage!=null)stage.close();return null;});Platform.exit();}
        System.exit(code);
    }
}
