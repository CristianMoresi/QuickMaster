import com.dspark.effects.MasterEqualizer;
import com.dspark.analysis.TruePeak;
import com.quickmaster.processing.*;
import com.quickmaster.processing.eq.EqualizerProcessor;
import java.util.*;

/** Routing, phase, dynamic bands, source boundaries and calibrated streaming. */
class EqAutoGainMatrixAudit {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static EqualizerProcessor eq(MasterEqualizer.Band b,boolean auto){var e=new EqualizerProcessor();e.setNumBands(1);e.setBand(0,b);e.setAutoGainEnabled(auto);return e;}
    static float[] offline(EqualizerProcessor e,float[] source,int rate,int ch) {
        var p=new ProcessingPipeline();p.addProcessor(e);p.prepare(rate,source.length);return p.analyzeAndRender(source,ch,0,null,null,null);
    }
    public static void main(String[] args) {
        int cases=0;double maxError=0,maxPeak=0;
        for(int rate:new int[]{44100,96000})for(int ch:new int[]{1,2})
        for(var route:MasterEqualizer.Channel.values())for(var phase:MasterEqualizer.BandPhase.values())
        for(var type:MasterEqualizer.BandType.values())for(boolean dynamic:new boolean[]{false,true}) {
            float[] source=new float[8193*ch];var random=new Random(19081);
            for(int f=0;f<source.length/ch;f++)for(int c=0;c<ch;c++)
                source[f*ch+c]=(float)(.12*Math.sin(f*.073+c*.3)+.1*Math.sin(f*.32)+.05*random.nextGaussian());
            float[] saved=source.clone();var b=new MasterEqualizer.Band();b.channel=route;b.phase=phase;b.type=type;b.dynamic=dynamic;
            b.frequency=1100;b.gainDb=24;b.q=4;b.slope=48;b.threshold=-25;b.aboveRatio=4;b.aboveRangeDb=12;b.belowRatio=2;b.belowRangeDb=6;
            float[] raw=offline(eq(b,false),source,rate,ch);var e=eq(b,true);float[] actual=offline(e,source,rate,ch);
            float gain=(float)Math.pow(10,e.getAutoGainDb()/20);double error=0;
            for(int i=0;i<raw.length;i++){check(Float.isFinite(actual[i]),"Non-finite output");error=Math.max(error,Math.abs(actual[i]-raw[i]*gain));}
            check(error<2e-7,"Not a static linked gain: "+route+" "+phase+" "+type+" dynamic="+dynamic+" error="+error);
            double peak=TruePeak.measureMax(actual,ch);check(peak<=1.000001,"Overload");check(Arrays.equals(saved,source),"Source mutation");
            // Reuse the prepared calibration in differently sized playback blocks.
            e.prepare(rate,source.length);int latency=e.getLatencyFrames();float[] stream=new float[source.length];int cursor=0;
            while(cursor<source.length/ch+latency) {
                int frames=Math.min(257,source.length/ch+latency-cursor);float[] block=new float[frames*ch];
                int copy=Math.max(0,Math.min(frames,source.length/ch-cursor));if(copy>0)System.arraycopy(source,cursor*ch,block,0,copy*ch);
                e.process(block,ch);for(int f=0;f<frames;f++){int target=cursor+f-latency;if(target>=0&&target<stream.length/ch)System.arraycopy(block,f*ch,stream,target*ch,ch);}cursor+=frames;
            }
            for(int i=0;i<stream.length;i++)check(Math.abs(stream[i]-actual[i])<2e-6,"Calibrated streaming mismatch");
            maxError=Math.max(maxError,error);maxPeak=Math.max(maxPeak,peak);cases++;
        }
        System.out.printf(Locale.US,"EQ_AUTOGAIN_MATRIX_PASS cases=%d routing=true dynamic=true phases=true streaming=true sourceUnchanged=true maxScalarError=%.9g maxDbtp=%.9f%n",cases,maxError,20*Math.log10(maxPeak));
    }
}
