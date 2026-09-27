import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.dynamics.MacroLevelerProcessor;
import com.dspark.analysis.TruePeak;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Fixed-time PCM measurement, independent of the Leveler's feature/section decisions. No audio written. */
public final class MacroLevelerAcceptance {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        byte[] sourceHash=hash(Path.of(args[0]));
        WavFile song=new WavFile(args[0]);song.load();float[] source=song.getSamples();
        int ch=song.getChannels(),rate=song.getSampleRate(),frames=source.length/ch;
        double amount=args.length>1?Double.parseDouble(args[1]):1;
        double speed=args.length>2?Double.parseDouble(args[2]):.5;
        var p=new MacroLevelerProcessor();p.setEnabled(true);p.setLeveling(amount);p.setSpeed(speed);p.prepare(rate,source.length);
        long t=System.nanoTime();p.analyze(source,ch);double analysis=(System.nanoTime()-t)/1e9;
        float[] out=p.process(source.clone(),ch);
        System.out.println("CODE "+p.getClass().getProtectionDomain().getCodeSource().getLocation());
        System.out.printf("INPUT name=%s seconds=%.6f channels=%d rate=%d amount=%.2f speed=%.2f analysisSeconds=%.3f%n",args[0],frames/(double)rate,ch,rate,amount,speed,analysis);
        System.out.println("REPORT "+p.getAnalysisReport());
        List<Double> before=new ArrayList<>(),after=new ArrayList<>();int positive=0;
        for(int sec=0;sec+3<=frames/rate;sec++) {
            double a=rms(source,sec*rate,(sec+3)*rate,ch),b=rms(out,sec*rate,(sec+3)*rate,ch);
            double gain=b-a;
            // A declared fixed RMS floor, not the algorithm's activity mask or regions.
            if(a>-50){before.add(a);after.add(b);if(gain>1)positive++;}
            System.out.printf("WINDOW start=%d inputRms=%.6f outputRms=%.6f delta=%.6f meter=%.6f%n",sec,a,b,gain,p.getGainDbAtPosition((long)((sec+1.5)*rate)));
        }
        Collections.sort(before);Collections.sort(after);
        double inSpread=quantile(before,.9)-quantile(before,.1),outSpread=quantile(after,.9)-quantile(after,.1);
        System.out.printf("SUMMARY windows=%d positiveOver1dB=%d inputP10P90=%.6f outputP10P90=%.6f inputP05P95=%.6f outputP05P95=%.6f inputRange=%.6f outputRange=%.6f outputTruePeak=%.6f%n",before.size(),positive,inSpread,outSpread,quantile(before,.95)-quantile(before,.05),quantile(after,.95)-quantile(after,.05),quantile(before,1)-quantile(before,0),quantile(after,1)-quantile(after,0),20*Math.log10(TruePeak.measureMax(out,ch)));
        if(args.length>3&&args[3].equals("--assert")) {
            if(before.size()<10)throw new AssertionError("No sufficient active windows for corpus acceptance");
            if(amount==1&&(outSpread>1.5||outSpread>inSpread*.35))throw new AssertionError("Macro spread not sufficiently reduced");
            if(amount>0&&amount<1&&(outSpread>inSpread*(1-.6*amount)
                    || Math.abs(outSpread-inSpread*(1-amount))>1.5))
                throw new AssertionError("Partial Amount does not proportionally reduce macro contrast");
            if(args[0].endsWith("By Now.wav")&&amount>=.5&&positive<5)throw new AssertionError("Several positive musical corrections required");
            for(int i=0;i<out.length;i++)if(!Float.isFinite(out[i])||Math.abs(out[i])+1e-30<Math.abs(source[i]))
                throw new AssertionError("Upward-only/finite contract violated at sample "+i);
            double maximumInput=maxMacro(source,ch,rate),maximumOutput=maxMacro(out,ch,rate);
            if(maximumOutput>maximumInput+.00001)throw new AssertionError("Strongest input macro RMS exceeded");
            System.out.printf("UPWARD_RMS_PASS maximumInput=%.6f maximumOutput=%.6f globalTrimDb=%.6f%n",maximumInput,maximumOutput,p.getAnalysisReport().headroomOffsetDb());
            if(!Arrays.equals(sourceHash,hash(Path.of(args[0]))))throw new AssertionError("Source file changed");
            System.out.println("MACRO_ACCEPTANCE_PASS fixedTimeGrid=true sourceUnchanged=true");
        }
    }
    private static double rms(float[] pcm,int from,int to,int ch){double e=0;for(int i=from*ch;i<to*ch;i++)e+=(double)pcm[i]*pcm[i];return 10*Math.log10(Math.max(1e-100,e/((to-from)*(double)ch)));}
    private static double maxMacro(float[] pcm,int ch,int rate) {
        int window=3*rate*ch,hop=rate/10*ch;
        double energy=0;
        for(int i=0;i<window;i++)energy+=(double)pcm[i]*pcm[i];
        double max=energy;
        for(int start=hop;start+window<=pcm.length;start+=hop) {
            for(int i=start-hop;i<start;i++)energy-=(double)pcm[i]*pcm[i];
            for(int i=start+window-hop;i<start+window;i++)energy+=(double)pcm[i]*pcm[i];
            max=Math.max(max,energy);
        }
        return 10*Math.log10(max/window);
    }
    private static double quantile(List<Double> values,double q){return values.isEmpty()?Double.NaN:values.get((int)((values.size()-1)*q));}
    private static byte[] hash(Path path)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1)digest.update(block,0,n);}return digest.digest();}
}
