import com.quickmaster.audio.WavFile;
import com.quickmaster.config.*;
import com.quickmaster.ui.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ComboBox;
import javafx.scene.input.*;
import javafx.scene.layout.Region;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Actual EQ mouse handlers and preset representability of every exposed knob. */
public class EqControlAudit {
    static Object field(Object owner,String name) throws Exception {
        var f=owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    public static void main(String[] args) throws Exception {
        var done=new CompletableFuture<Integer>();
        Platform.startup(()->{
            int failures=0; MainController controller=null;
            try {
                var loader=new FXMLLoader(MainController.class.getResource("main-view.fxml"));
                Region root=loader.load(); controller=loader.getController(); new Scene(root,1360,830);
                root.applyCss(); root.resize(1360,830); root.layout();
                var loaded=MainController.class.getDeclaredField("loadedFile"); loaded.setAccessible(true);
                loaded.set(controller,new WavFile("generated",48000,1,new float[48000],32,true));
                Canvas canvas=(Canvas)field(controller,"eqCanvas");
                double x=canvas.getWidth()*.4,y=canvas.getHeight()*.3;
                long before=(long)field(controller,"outputAnalysisGeneration");
                var event=new MouseEvent(MouseEvent.MOUSE_PRESSED,x,y,x,y,MouseButton.PRIMARY,2,
                        false,false,false,false,true,false,false,false,false,true,new PickResult(canvas,x,y));
                var click=MainController.class.getDeclaredMethod("onEqCanvasPressed",MouseEvent.class); click.setAccessible(true);
                click.invoke(controller,event);
                long after=(long)field(controller,"outputAnalysisGeneration");
                System.out.println("EQ_DOUBLE_CLICK generation="+before+"->"+after+" bands="+field(controller,"eqBandCount"));
                if(after<=before || (int)field(controller,"eqBandCount")!=1) failures++;
                var capture=MainController.class.getDeclaredMethod("capturePreset"); capture.setAccessible(true);
                for(var f:MainController.class.getDeclaredFields()) if(f.getType()==Knob.class) {
                    f.setAccessible(true); Knob knob=(Knob)f.get(controller); if(knob==null)continue;
                    double original=knob.getValue();
                    for(double extreme:new double[]{knob.getMin(),knob.getMax()}) {
                        try { knob.setValue(extreme); PresetValidation.validate((ChainPreset)capture.invoke(controller)); }
                        catch(Throwable error) {failures++;System.out.println("EQ_CONTROL_FAILURE "+f.getName()+"="+extreme+" "+error);}
                    }
                    knob.setValue(original);
                }
                var slopes=(ComboBox<Integer>)field(controller,"eqSlope");
                for(Integer slope:slopes.getItems()) {
                    slopes.setValue(slope);
                    try {PresetValidation.validate((ChainPreset)capture.invoke(controller));}
                    catch(Throwable e){failures++;System.out.println("EQ_SLOPE_FAILURE slope="+slope+" "+e);}
                }
            } catch(Throwable error){error.printStackTrace();failures++;}
            finally {if(controller!=null)controller.shutdown();}
            done.complete(failures);
        });
        int failures=done.get(40,TimeUnit.SECONDS);Platform.exit();
        System.out.println("EQ_CONTROL_AUDIT failures="+failures);System.exit(failures==0?0:1);
    }
}
