import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.*;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.processing.eq.*;
import com.quickmaster.processing.dynamics.*;
import com.quickmaster.processing.clip.*;
import com.quickmaster.processing.limit.*;
import com.quickmaster.processing.dynamics.leveler.FiniteTruePeakStream;
import com.dspark.effects.MasterEqualizer;
import java.util.*;

/** Short generated boundary signals; order/rate/OS/format and final-ceiling invariants. */
public class FullChainAdversarialAudit {
    public static void main(String[] args) {
        int cases=0;double highest=Double.NEGATIVE_INFINITY;
        for(int rate:new int[]{8000,22050,32000,44100,48000,96000})for(int channels:new int[]{1,2})
            for(int factor:new int[]{1,2,4,8,16})for(int order=0;order<2;order++){
                float[] input=new float[(rate/8+17)*channels];Random rng=new Random(927);
                for(int f=0;f<input.length/channels;f++)for(int c=0;c<channels;c++)
                    input[f*channels+c]=(float)((c==0?1:-1)*(.23*Math.sin(2*Math.PI*731*f/rate)+.06*(rng.nextDouble()-.5)));
                input[input.length-channels]=.9f;float[] untouched=input.clone();
                ProcessingPipeline pipeline=new ProcessingPipeline();TrackAnalysis analysis=new TrackAnalysis();
                analysis.setManualBpm(127);
                EqualizerProcessor eq=new EqualizerProcessor();eq.setNumBands(2);
                MasterEqualizer.Band band=eq.getBand(0);band.enabled=true;band.gainDb=12;band.frequency=1000;eq.setBand(0,band);
                band=eq.getBand(1);band.enabled=true;band.dynamic=true;band.frequency=1900;band.aboveRangeDb=12;eq.setBand(1,band);
                AutoEqProcessor auto=new AutoEqProcessor();auto.setAmount(1);
                FadeProcessor fade=new FadeProcessor();fade.setFadeInSec(.015);fade.setFadeOutSec(.01);
                PeakCompProcessor peak=new PeakCompProcessor();peak.setTrackAnalysis(analysis);peak.setTargetDb(-3);
                BeatCompProcessor beat=new BeatCompProcessor();beat.setTrackAnalysis(analysis);beat.setTargetDb(-1);
                PunchProcessor punch=new PunchProcessor();punch.setTrackAnalysis(analysis);punch.setAmountDb(3);
                SoftClipProcessor soft=new SoftClipProcessor();soft.setSatDb(6);
                HardClipProcessor hard=new HardClipProcessor();hard.setClipDb(6);
                MultibandLimiterProcessor mb=new MultibandLimiterProcessor();for(int b=0;b<4;b++)mb.setPushDb(b,6);
                BroadbandLimiterProcessor bb=new BroadbandLimiterProcessor();bb.setPushDb(6);
                List<AudioProcessor> stages=new ArrayList<>(List.of(auto,eq,fade,peak,beat,punch,soft,hard,mb,bb));
                if(order==1)Collections.rotate(stages,4);
                for(AudioProcessor stage:stages){stage.setEnabled(true);pipeline.addProcessor(stage);}
                PeakNormalizer norm=new PeakNormalizer();norm.setEnabled(true);norm.setTargetDbfs(-1);pipeline.addProcessor(norm);
                WavFile file=new WavFile("generated",rate,channels,input,32,true);pipeline.processOversampled(file,factor,null);
                float[] actual=file.getSamples();
                if(actual.length!=input.length||!Arrays.equals(input,untouched))throw new AssertionError("Source/length changed");
                for(float sample:actual)if(!Float.isFinite(sample))throw new AssertionError("Non-finite output");
                FiniteTruePeakStream meter=new FiniteTruePeakStream(channels,true);
                meter.accept(actual,0,actual.length/channels);double db=20*Math.log10(meter.finish());highest=Math.max(highest,db);
                if(db>-.99999)throw new AssertionError("Ceiling violation rate="+rate+" channels="+channels+" factor="+factor+" order="+order+" dBTP="+db);
                System.out.printf(Locale.ROOT,"CHAIN_BOUNDARY_PASS rate=%d channels=%d os=%d order=%d dBTP=%.8f%n",rate,channels,factor,order,db);cases++;
            }
        System.out.printf(Locale.ROOT,"CHAIN_BOUNDARY_SUMMARY cases=%d highestDbtp=%.9f%n",cases,highest);
    }
}
