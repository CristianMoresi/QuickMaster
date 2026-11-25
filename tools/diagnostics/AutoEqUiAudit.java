import com.quickmaster.audio.AudioFile;
import com.quickmaster.config.*;
import com.quickmaster.processing.*;
import com.quickmaster.processing.eq.AutoEqProcessor;
import com.quickmaster.ui.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import java.nio.file.*;
import java.util.*;

/**
 * Actual FXML/controller with a real track: the two Auto EQ controls (Amount
 * and Cut/Boost), what they publish and draw, typed values, presets, undo and
 * snapshots. Silent scene snapshots, no audio device. Arguments: the WAV and
 * an output directory for the screenshots.
 */
public final class AutoEqUiAudit extends StereoUiAudit {
    static AutoEqProcessor live()throws Exception{return (AutoEqProcessor)get("autoEq");}
    static Knob knob(String field)throws Exception{return (Knob)get(field);}

    /** The published PCM against an independent render of the Auto EQ alone with the same settings. */
    static void exactAutoEq()throws Exception {
        record State(float[] source,float[] actual,int rate,int channels,ChainPreset preset){}
        State s=fx(()->{var f=(AudioFile)get("loadedFile");return new State(f.getSamples(),pcm(),f.getSampleRate(),f.getChannels(),(ChainPreset)call("capturePreset"));});
        var eq=new AutoEqProcessor();eq.setEnabled(s.preset.autoEqOn);eq.setAmount(s.preset.autoEqAmount);eq.setCutBoost(s.preset.autoEqCutBoost);
        var pipe=new ProcessingPipeline();pipe.addProcessor(eq);var normalizer=new PeakNormalizer();normalizer.setEnabled(false);pipe.addProcessor(normalizer);
        pipe.prepare(s.rate,s.source.length);float[] expected=pipe.analyzeAndRender(s.source,s.channels,0,null,null,null);
        check(Arrays.equals(expected,s.actual),"UI published PCM differs from an independent Auto EQ render");
        System.out.printf(Locale.US,"AUTO_EQ_UI_PCM_PASS bitExact=true amount=%.2f cutBoost=%+.2f%n",s.preset.autoEqAmount,s.preset.autoEqCutBoost);
    }
    /** The lowest and the highest correction the interface draws along the track, from 20 Hz to 20 kHz. */
    static double[] drawn()throws Exception {
        return fx(()->{
            var audible=(AutoEqProcessor)call("audibleProcessor",new Class<?>[]{AudioProcessor.class},get("autoEq"));
            var file=(AudioFile)get("loadedFile");
            double[] freqs=new double[240],db=new double[240];for(int i=0;i<240;i++)freqs[i]=20*Math.pow(1000,i/239.0);
            double least=0,most=0;long frames=file.getSamples().length/file.getChannels();
            for(long pos=0;pos<frames;pos+=file.getSampleRate()/4){audible.fillCorrection(freqs,pos,db);for(double v:db){least=Math.min(least,v);most=Math.max(most,v);}}
            return new double[]{least,most};
        });
    }
    /** Types a value into a knob as a double click on it does. */
    static void type(Knob k,String text)throws Exception {
        var show=Knob.class.getDeclaredMethod("showEditor");show.setAccessible(true);show.invoke(k);
        var field=Knob.class.getDeclaredField("editor");field.setAccessible(true);((TextField)field.get(k)).setText(text);
        var commit=Knob.class.getDeclaredMethod("commitEditor");commit.setAccessible(true);commit.invoke(k);
    }
    /** What the editor of a knob opens with. */
    static String editorText(Knob k)throws Exception {
        var show=Knob.class.getDeclaredMethod("showEditor");show.setAccessible(true);show.invoke(k);
        var field=Knob.class.getDeclaredField("editor");field.setAccessible(true);String text=((TextField)field.get(k)).getText();
        var hide=Knob.class.getDeclaredMethod("hideEditor");hide.setAccessible(true);hide.invoke(k);return text;
    }
    static void edit(String what,java.util.concurrent.Callable<Void> change)throws Exception {
        long start=System.nanoTime();fx(change);ready();
        System.out.printf(Locale.US,"AUTO_EQ_UI_EDIT %s readyMs=%.0f%n",what,(System.nanoTime()-start)/1e6);
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
                // The defaults a new session starts from: off, a quarter of the amount, lowering and raising alike.
                check(!live().isEnabled(),"Auto EQ must start off");
                check(knob("autoEqAmountKnob").getValue()==AutoEqProcessor.DEFAULT_AMOUNT,"Amount default");
                check(knob("autoEqCutBoostKnob").getValue()==0.0,"Cut/Boost default");
                check(((Object[])get("autoEqControls")).length==2,"Auto EQ must have exactly two controls");
                check(knob("autoEqAmountKnob").isDisable()&&knob("autoEqCutBoostKnob").isDisable(),"Controls must be dimmed while Auto EQ is off");
                var p=(ChainPreset)call("capturePreset");p.autoEqOn=p.eqOn=p.dynamicsOn=p.clipOn=p.limitOn=p.normalizerOn=p.osOn=p.stereoOn=false;
                p.fadeInSec=p.fadeOutSec=0;
                call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},p,false);
                for(Object module:(List<?>)get("chainModules"))if(get(module,"name").equals("EQ"))call("selectModule",new Class<?>[]{module.getClass()},module);
                call("loadAudioFile",new Class<?>[]{java.io.File.class},audio.toFile());return null;
            });
            ready();
            float[] source=fx(()->((AudioFile)get("loadedFile")).getSamples());
            check(Arrays.equals(source,fx(()->pcm())),"With every module off the published PCM must be the source");
            fx(()->{call("drawEqCurve");shot(out.resolve("auto-eq-off.png"),1360,900);return null;});

            edit("enable",()->{((CheckBox)root.lookup("#autoEqOn")).setSelected(true);return null;});
            check(fx(()->live().isEnabled()&&!knob("autoEqAmountKnob").isDisable()),"Enabling did not reach the processor or its controls");
            float[] defaults=fx(()->pcm());check(!Arrays.equals(source,defaults),"Auto EQ on published the unchanged source");
            exactAutoEq();
            double[] atDefault=drawn();
            fx(()->{var player=get("player");player.getClass().getMethod("seekTo",double.class).invoke(player,60.0);
                call("drawEqCurve");shot(out.resolve("auto-eq-defaults.png"),1360,900);shot(out.resolve("auto-eq-defaults-compact.png"),1100,740);return null;});

            // Amount: from nothing to all of it. At zero nothing moves and the source is published to the bit,
            // wherever Cut/Boost stands; the correction drawn grows with it, a share of the whole.
            edit("amount=1",()->{knob("autoEqAmountKnob").setValue(1.0);return null;});
            check(fx(()->live().getAmount())==1.0,"Amount knob did not reach the processor");exactAutoEq();
            float[] full=fx(()->pcm());check(!Arrays.equals(defaults,full),"Amount is inert");
            double[] atFull=drawn();
            fx(()->{call("drawEqCurve");shot(out.resolve("auto-eq-full.png"),1360,900);return null;});
            edit("amount=0.5",()->{knob("autoEqAmountKnob").setValue(0.5);return null;});exactAutoEq();
            double[] atHalf=drawn();
            System.out.printf(Locale.US,"AUTO_EQ_UI_DRAWN_DB amount25=[%.2f, %.2f] amount50=[%.2f, %.2f] amount100=[%.2f, %.2f]%n",
                    atDefault[0],atDefault[1],atHalf[0],atHalf[1],atFull[0],atFull[1]);
            check(atFull[0]<atHalf[0]&&atHalf[0]<atDefault[0]&&atDefault[0]<-0.5,"The cuts drawn do not grow with Amount");
            check(atFull[1]>atHalf[1]&&atHalf[1]>atDefault[1]&&atDefault[1]>0.3,"The lifts drawn do not grow with Amount");
            check(Math.max(-atDefault[0],atDefault[1])<=3.0+1e-6&&Math.max(-atHalf[0],atHalf[1])<=6.0+1e-6&&Math.max(-atFull[0],atFull[1])<=12.0+1e-6,
                    "A correction drawn passes 12 dB times the amount");
            for(double lean:new double[]{0.0,-1.0,1.0}) {
                edit("amount=0 cutBoost="+lean,()->{knob("autoEqCutBoostKnob").setValue(lean);knob("autoEqAmountKnob").setValue(0.0);return null;});
                check(Arrays.equals(source,fx(()->pcm())),"Amount at zero must publish the source to the bit");
                double[] none=drawn();check(none[0]==0.0&&none[1]==0.0,"Amount at zero must draw no correction");
            }
            System.out.println("AUTO_EQ_UI_AMOUNT_PASS zeroIsTheSource=true zeroDrawsNothing=true growsWithAmount=true");

            // Cut/Boost: what the correction is made of. At Cut nothing is raised, at Boost nothing lowered.
            edit("cut",()->{knob("autoEqAmountKnob").setValue(1.0);knob("autoEqCutBoostKnob").setValue(-1.0);return null;});
            check(fx(()->live().getCutBoost())==-1.0,"Cut/Boost knob did not reach the processor");exactAutoEq();
            double[] cut=drawn();
            fx(()->{call("drawEqCurve");shot(out.resolve("auto-eq-cut.png"),1360,900);return null;});
            edit("half-cut",()->{knob("autoEqCutBoostKnob").setValue(-0.5);return null;});exactAutoEq();double[] halfCut=drawn();
            edit("boost",()->{knob("autoEqCutBoostKnob").setValue(1.0);return null;});exactAutoEq();double[] boost=drawn();
            fx(()->{call("drawEqCurve");shot(out.resolve("auto-eq-boost.png"),1360,900);return null;});
            edit("half-boost",()->{knob("autoEqCutBoostKnob").setValue(0.5);return null;});exactAutoEq();double[] halfBoost=drawn();
            System.out.printf(Locale.US,"AUTO_EQ_UI_DRAWN_DB cut=[%.2f, %.2f] halfCut=[%.2f, %.2f] middle=[%.2f, %.2f] halfBoost=[%.2f, %.2f] boost=[%.2f, %.2f]%n",
                    cut[0],cut[1],halfCut[0],halfCut[1],atFull[0],atFull[1],halfBoost[0],halfBoost[1],boost[0],boost[1]);
            check(cut[1]==0.0&&cut[0]<-3.0,"Cut raised something, or lowered nothing");
            check(boost[0]==0.0&&boost[1]>1.0,"Boost lowered something, or raised nothing");
            check(halfCut[1]<atFull[1]&&halfCut[1]>cut[1],"Half way to Cut the raising is not between the two");
            check(halfBoost[0]>atFull[0]&&halfBoost[0]<boost[0],"Half way to Boost the lowering is not between the two");
            System.out.println("AUTO_EQ_UI_CUT_BOOST_PASS cutRaisesNothing=true boostLowersNothing=true fades=true");

            // A knob moved is heard at once: with the whole render held back, a provisional
            // window of the new settings must reach the player on its own.
            long moved=System.nanoTime();
            fx(()->{knob("autoEqAmountKnob").setValue(0.6);knob("autoEqCutBoostKnob").setValue(-0.4);
                ((javafx.animation.PauseTransition)get("dynRefreshDebounce")).stop();call("requestInteractiveEqPreview");return null;});
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(fx(()->get("player").getClass().getMethod("getPreviewWindow").invoke(get("player")))==null) {
                if(System.nanoTime()>deadline)throw new AssertionError("Auto EQ preview not published");Thread.sleep(5);
            }
            System.out.printf(Locale.US,"AUTO_EQ_UI_PREVIEW publicationMs=%.0f noAudioDevice=true%n",(System.nanoTime()-moved)/1e6);
            edit("after-preview",()->{call("syncLiveAnalysis");return null;});
            check(fx(()->live().getAmount()==0.6&&live().getCutBoost()==-0.4),"Previewed settings were lost");exactAutoEq();

            // Typed values: Amount in percent; Cut/Boost as its two shares, as the face shows them, or as a
            // lean in percent. Both are clamped to their ranges, and the editor opens with what the face shows.
            check(fx(()->editorText(knob("autoEqCutBoostKnob"))).equals("100/60"),"The Cut/Boost editor does not open with its two shares");
            check(fx(()->editorText(knob("autoEqAmountKnob"))).equals("60"),"The Amount editor does not open in percent");
            edit("typed",()->{type(knob("autoEqAmountKnob"),"70");type(knob("autoEqCutBoostKnob"),"50/100");type(knob("levelingKnob"),"40");return null;});
            check(fx(()->live().getAmount()==0.70&&live().getCutBoost()==0.5),"Typed values did not reach the processor");
            check(fx(()->knob("levelingKnob").getValue())==0.40,"Typed Leveling is not read as percent");
            edit("typed-over",()->{type(knob("autoEqAmountKnob"),"250");type(knob("autoEqCutBoostKnob"),"-25");return null;});
            check(fx(()->live().getAmount()==1.0&&live().getCutBoost()==-0.25),"Typed values are not clamped or not read as a lean");
            exactAutoEq();
            System.out.println("AUTO_EQ_UI_TYPED_PASS percent=true shares=true clamped=true");

            // Undo takes the last edit back.
            edit("cutBoost=0.8",()->{knob("autoEqCutBoostKnob").setValue(0.8);return null;});
            edit("undo",()->{call("onUndo");return null;});
            check(fx(()->knob("autoEqCutBoostKnob").getValue()==-0.25&&live().getCutBoost()==-0.25),"Undo did not restore Cut/Boost");
            exactAutoEq();

            // A preset saved now comes back as it went, and holds the two controls alone.
            ChainPreset saved=fx(()->(ChainPreset)call("capturePreset"));
            var gson=new com.google.gson.Gson();String json=gson.toJson(saved);
            check(json.contains("\"autoEqAmount\"")&&json.contains("\"autoEqCutBoost\""),"A preset must hold the two controls");
            check(!json.contains("autoEqTarget")&&!json.contains("autoEqBalance")&&!json.contains("autoEqTame"),"A preset must not hold fields of earlier Auto EQs");
            // A preset of the slope-matching Auto EQ: on, amount 35 %, Brown. Its amount is the amount.
            ChainPreset legacy=gson.fromJson("{\"version\":1,\"autoEqOn\":true,\"autoEqAmount\":0.35,\"autoEqTarget\":\"BROWN\",\"autoEqAttackSec\":0.1,"
                    +"\"autoEqReleaseSec\":0.4,\"normalizerTargetDbtp\":-1.0,\"osFactor\":4,\"mbPushDb\":[0,0,0,0]}",ChainPreset.class);
            edit("legacy-preset",()->{call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},legacy,true);call("syncLiveAnalysis");return null;});
            check(fx(()->live().isEnabled()&&live().getAmount()==0.35&&live().getCutBoost()==0.0),"Old preset not read: its amount, in the middle of Cut/Boost");
            check(fx(()->knob("autoEqAmountKnob").getValue()==0.35&&knob("autoEqCutBoostKnob").getValue()==0.0),"Knobs do not show the old preset");
            System.out.println("AUTO_EQ_UI_PRESET_PASS roundTrip=true oldAmountKept=true");
            edit("restore-preset",()->{call("applyPreset",new Class<?>[]{ChainPreset.class,boolean.class},saved,true);call("syncLiveAnalysis");return null;});
            check(gson.toJson(fx(()->call("capturePreset"))).equals(json),"Preset round trip changed the settings");exactAutoEq();

            // Off again: the source, to the bit.
            edit("disable",()->{((CheckBox)root.lookup("#autoEqOn")).setSelected(false);return null;});
            check(Arrays.equals(source,fx(()->pcm())),"Auto EQ off must publish the source");
            check(identity.equals(LevelerExclusionStore.identity(audio)),"Private source changed");
            System.out.println("AUTO_EQ_UI_PASS twoControls=true amountZeroIsTheSource=true cutBoost=true publishedBitExact=true typed=true presets=true undo=true sourceUnchanged=true noAudioDevice=true");
        } finally {fx(()->{if(c!=null)c.shutdown();return null;});Platform.exit();}
    }
}
