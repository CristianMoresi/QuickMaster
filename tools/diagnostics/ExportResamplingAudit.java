import com.quickmaster.ui.MainController;
import com.quickmaster.processing.dynamics.leveler.FiniteTruePeakStream;
import java.lang.reflect.Method;
import java.util.*;

/** Calls the actual export SRC/reclamp path; independent sine and true-peak oracles. */
public class ExportResamplingAudit {
    static Method resample, reclamp;
    static float[] convert(float[] pcm,int channels,int from,int to)throws Exception{
        return (float[])resample.invoke(null,pcm,channels,from,to);
    }
    static double rms(float[] pcm,int channels,int rate){
        int first=rate/25, end=pcm.length/channels-first;double sum=0;
        for(int f=first;f<end;f++)for(int c=0;c<channels;c++)sum+=(double)pcm[f*channels+c]*pcm[f*channels+c];
        return Math.sqrt(sum/((end-first)*channels));
    }
    public static void main(String[] args)throws Exception{
        resample=MainController.class.getDeclaredMethod("resampleForExport",float[].class,int.class,int.class,int.class);
        reclamp=MainController.class.getDeclaredMethod("reclampTruePeak",float[].class,int.class,double.class);
        resample.setAccessible(true);reclamp.setAccessible(true);int cases=0;double worstGain=0,peak=Double.NEGATIVE_INFINITY;
        for(int from:new int[]{8000,44100,48000,96000,192000})for(int to:new int[]{8000,44100,48000,96000,192000})for(int channels:new int[]{1,2}){
            int frames=from/4+17;float[] pcm=new float[frames*channels];
            for(int f=0;f<frames;f++)for(int c=0;c<channels;c++)pcm[f*channels+c]=(float)((c==0?1:-1)*1.2*Math.sin(2*Math.PI*997*f/from));
            float[] original=pcm.clone();float[] out=convert(pcm,channels,from,to);
            // Cover the finite source duration: ceil, with less than one extra target frame.
            if(out.length!=(long)Math.ceil(frames*(double)to/from)*channels)throw new AssertionError("SRC length "+from+"->"+to+": "+out.length);
            if(!Arrays.equals(pcm,original))throw new AssertionError("SRC changed its input");
            double gain=Math.abs(20*Math.log10(rms(out,channels,to)/rms(pcm,channels,from)));worstGain=Math.max(worstGain,gain);
            if(gain>.04)throw new AssertionError("Passband gain error "+gain+" dB at "+from+"->"+to+" ch="+channels);
            if(from==to)out=out.clone(); // Equal-rate alias is the explicitly documented fast path.
            reclamp.invoke(null,out,channels,-1.0);
            FiniteTruePeakStream tp=new FiniteTruePeakStream(channels,true);
            for(int f=0;f<out.length/channels;f+=4096)tp.accept(out,f,Math.min(4096,out.length/channels-f));
            double db=20*Math.log10(tp.finish());peak=Math.max(peak,db);
            if(db>-.99999)throw new AssertionError("Delivery ceiling "+db);
            if(channels==2)for(int f=0;f<out.length/2;f++)if(out[f*2]!=-out[f*2+1])throw new AssertionError("Stereo link/polarity changed");
            cases++;
        }
        float[] aboveNyquist=new float[48000/2];for(int f=0;f<aboveNyquist.length;f++)aboveNyquist[f]=(float)(.8*Math.sin(2*Math.PI*12000*f/48000));
        double rejection=20*Math.log10(rms(convert(aboveNyquist,1,48000,16000),1,16000)/rms(aboveNyquist,1,48000));
        if(rejection> -70)throw new AssertionError("Insufficient anti-alias rejection "+rejection);
        System.out.printf(Locale.ROOT,"EXPORT_SRC_PASS cases=%d maxPassbandErrorDb=%.9f maxDbtp=%.9f aliasRejectionDb=%.3f%n",cases,worstGain,peak,rejection);
    }
}
