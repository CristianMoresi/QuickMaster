import com.quickmaster.processing.*;
import com.quickmaster.processing.analysis.TrackAnalysis;
import com.quickmaster.processing.dynamics.*;
import com.quickmaster.processing.clip.*;
import com.dspark.effects.Saturation;
import java.util.*;

/** Pipeline versus independently staged, ragged-block cumulative processing. */
class DynamicsClipsSerialAudit {
    static List<AudioProcessor> stages(TrackAnalysis analysis,Saturation.Algorithm algorithm,HardClipProcessor.Curve curve) {
        var peak=new PeakCompProcessor();peak.setTrackAnalysis(analysis);peak.setTargetDb(-2);
        var beat=new BeatCompProcessor();beat.setTrackAnalysis(analysis);beat.setTargetDb(-1);
        var punch=new PunchProcessor();punch.setTrackAnalysis(analysis);punch.setAmountDb(3);
        var soft=new SoftClipProcessor();soft.setAlgorithm(algorithm);soft.setSatDb(3);
        var hard=new HardClipProcessor();hard.setCurve(curve);hard.setClipDb(2);
        List<AudioProcessor> stages=List.of(peak,beat,punch,soft,hard);
        stages.forEach(s->s.setEnabled(true));return stages;
    }
    static double peak(float[] samples) {double max=0;for(float v:samples)max=Math.max(max,Math.abs(v));return max;}
    public static void main(String[] args) {
        int cases=0;
        for(int rate:new int[]{44100,48000,96000})for(int channels:new int[]{1,2}) {
            float[] source=new float[rate*4*channels];Random random=new Random(7317);
            for(int f=0;f<rate*4;f++) {
                int age=(f-rate/5)%(rate/4),event=(f-rate/5)/(rate/4);
                // One accent must remain above the median after Peak Comp;
                // identical already-shaved loud peaks correctly need no Beat GR.
                double amplitude=event==11?1.8:f<rate*2?.25:.9;
                double pulse=f>=rate/5&&age<rate*.015?
                        amplitude*(2*random.nextDouble()-1)*Math.exp(-age/(.003*rate)):0;
                float v=(float)(.015*Math.sin(2*Math.PI*90*f/rate)+pulse);
                source[f*channels]=v;if(channels==2)source[f*channels+1]=-v*.7f;
            }
            float[] untouched=source.clone();var analysis=new TrackAnalysis();analysis.analyze(source,channels,rate);
            analysis.setManualBpm(120);
            if(analysis.getTransientTimesSec().length<10)throw new AssertionError("Punch not exercised");
            for(var algorithm:Saturation.Algorithm.values())for(var curve:HardClipProcessor.Curve.values()) {
                var pipeline=new ProcessingPipeline();for(var stage:stages(analysis,algorithm,curve))pipeline.addProcessor(stage);
                pipeline.prepare(rate,source.length);
                float[] actual=pipeline.analyzeAndRender(source,channels,0,null,null,null);
                float[] reference=source.clone();
                for(var stage:stages(analysis,algorithm,curve)) {
                    stage.prepare(rate,source.length);stage.analyze(reference,channels);stage.prepare(rate,source.length);
                    float[] before=reference.clone();int offset=0,block=0;int[] sizes={1,7,257,1024,73};
                    while(offset<reference.length) {
                        int count=Math.min(sizes[block++%sizes.length]*channels,reference.length-offset);
                        float[] piece=Arrays.copyOfRange(reference,offset,offset+count);stage.process(piece,channels);
                        System.arraycopy(piece,0,reference,offset,count);offset+=count;
                    }
                    if(Arrays.equals(before,reference))throw new AssertionError("Unexercised "+stage.getClass().getSimpleName()
                            +" rate="+rate+" channels="+channels+" pulseMap="+Arrays.toString(analysis.getOnsetTimesSec())
                            +(stage instanceof BeatCompProcessor b?" maxExcess="+b.getMaxExcessDb():""));
                    if(stage instanceof SoftClipProcessor || stage instanceof HardClipProcessor) {
                        double reduction=20*Math.log10(peak(reference)/peak(before));
                        double expected=stage instanceof SoftClipProcessor?-3:-2;
                        if(Math.abs(reduction-expected)>.00001)throw new AssertionError("Clip used original instead of cumulative peak");
                    }
                }
                if(!Arrays.equals(actual,reference))throw new AssertionError("Cumulative or block mismatch "+rate+"/"+channels+"/"+algorithm+"/"+curve);
                if(!Arrays.equals(source,untouched))throw new AssertionError("Source mutated");
                for(float v:actual)if(!Float.isFinite(v))throw new AssertionError("Nonfinite chain");
                cases++;
            }
        }
        System.out.println("DYNAMICS_CLIPS_SERIAL_PASS cases="+cases+" allStagesActive=true cumulativePeak=true raggedExact=true sourceUnchanged=true");
    }
}
