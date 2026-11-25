import com.dspark.analysis.LoudnessMeter;
import com.dspark.analysis.TruePeak;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.analysis.SpectrumAnalysis;
import com.quickmaster.processing.eq.AutoEqProcessor;
import java.nio.file.*;
import java.util.*;

/**
 * Whole-song Auto EQ measurements on a real track; never writes source or
 * rendered private audio. Arguments: the WAV, then optionally amount= from 0
 * to 1 and cutBoost= from -1 to 1 (the defaults otherwise).
 */
class AutoEqTrackAudit {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static String hash(Path path)throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(path)){byte[] block=new byte[65536];for(int n;(n=in.read(block))>=0;)digest.update(block,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    static double rmsDb(float[] x){double s=0;for(float v:x)s+=(double)v*v;return 10*Math.log10(s/x.length+1e-30);}
    static double rms(float[] a,float[] b,int from,int to){double s=0;for(int i=from;i<to;i++){double d=a[i]-b[i];s+=d*d;}return Math.sqrt(s/Math.max(1,to-from));}
    public static void main(String[] args)throws Exception {
        Path path=Path.of(args[0]);String before=hash(path);var f=new WavFile(path.toString());f.load();
        float[] source=f.getSamples();int channels=f.getChannels(),rate=f.getSampleRate(),frames=source.length/channels;
        var eq=new AutoEqProcessor();eq.setEnabled(true);
        for(int i=1;i<args.length;i++){String[] kv=args[i].split("=");double v=Double.parseDouble(kv[1]);
            switch(kv[0]){case "amount"->eq.setAmount(v);case "cutBoost"->eq.setCutBoost(v);default->throw new IllegalArgumentException(args[i]);}}
        // A first render of other settings warms the JVM up, as a session that has already rendered once is.
        double asked=eq.getAmount();eq.setAmount(asked>0.5?asked-0.1:asked+0.1);eq.prepare(rate,source.length);
        long t0=System.nanoTime();eq.analyze(source,channels);double cold=(System.nanoTime()-t0)*1e-9;
        eq.setAmount(asked);eq.prepare(rate,source.length);
        t0=System.nanoTime();eq.analyze(source,channels);double seconds=(System.nanoTime()-t0)*1e-9;
        eq.prepare(rate,source.length);float[] out=eq.process(source.clone(),channels);
        long changed=0;double worst=0;int worstAt=0,peakAt=0;float peak=0;
        for(int i=0;i<out.length;i++){check(Float.isFinite(out[i]),"Non-finite output");
            if(out[i]!=source[i])changed++;double d=Math.abs(out[i]-source[i]);if(d>worst){worst=d;worstAt=i;}
            if(Math.abs(out[i])>peak){peak=Math.abs(out[i]);peakAt=i;}}
        // Aligned with the source: the lag of the best match of the two first differences, on the left channel.
        // (The plain waveforms of bass-heavy music match almost as well a sample away, so a minimum-phase
        // correction of a few degrees is enough to move their best match; their differences do not.)
        int best=0;double bestValue=Double.NEGATIVE_INFINITY;int from=Math.min(frames/4,10*rate),span=Math.min(frames/2,20*rate);
        for(int lag=-64;lag<=64;lag++){double s=0;for(int k=from;k<from+span;k++)
            s+=(double)(source[k*channels]-source[(k-1)*channels])*(out[(k+lag)*channels]-out[(k+lag-1)*channels]);
            if(s>bestValue){bestValue=s;best=lag;}}
        double lufsIn=LoudnessMeter.measureIntegrated(source,rate,channels),lufsOut=LoudnessMeter.measureIntegrated(out,rate,channels);
        double tpIn=20*Math.log10(TruePeak.measureMax(source,channels)),tpOut=20*Math.log10(TruePeak.measureMax(out,channels));
        var sIn=new SpectrumAnalysis();sIn.analyze(source,channels,rate);var sOut=new SpectrumAnalysis();sOut.analyze(out,channels,rate);
        // The curve in play along the track: its mean and its extremes at a few frequencies, and its largest move.
        double[] freqs={40,63,100,160,250,400,630,1000,1600,2500,4000,6300,10000,14000};
        double[] mean=new double[freqs.length],least=new double[freqs.length],most=new double[freqs.length],db=new double[freqs.length];
        Arrays.fill(least,1e9);Arrays.fill(most,-1e9);int steps=0;double largest=0,lowest=0,highest=0;
        double[] dense=new double[240],denseDb=new double[240];for(int i=0;i<240;i++)dense[i]=20*Math.pow(1000,i/239.0);
        for(long pos=0;pos<frames;pos+=rate/4){eq.fillCorrection(freqs,pos,db);steps++;
            for(int i=0;i<freqs.length;i++){mean[i]+=db[i];least[i]=Math.min(least[i],db[i]);most[i]=Math.max(most[i],db[i]);}
            eq.fillCorrection(dense,pos,denseDb);for(double v:denseDb){largest=Math.max(largest,Math.abs(v));lowest=Math.min(lowest,v);highest=Math.max(highest,v);}}
        // The stretches of the render against the same track rendered in one piece on one thread: the
        // whole of it, and the half second after each join, where a stretch still lacks an older past.
        Class<?> engine=Class.forName("com.quickmaster.processing.eq.TonalBalanceRender"),settingsType=Class.forName(engine.getName()+"$Settings");
        Class<?> planType=Class.forName(engine.getName()+"$Plan");
        var standard=planType.getDeclaredField("DEFAULT");standard.setAccessible(true);Object plan=standard.get(null);
        var single=planType.getDeclaredField("ONE_PIECE");single.setAccessible(true);
        var stretchOf=planType.getDeclaredMethod("stretchSeconds");stretchOf.setAccessible(true);
        int count=(int)Math.max(1,Math.round(frames/((double)stretchOf.invoke(plan)*rate)));
        var asks=AutoEqProcessor.class.getDeclaredMethod("settings");asks.setAccessible(true);Object settings=asks.invoke(eq);
        var render=engine.getDeclaredMethod("render",float[].class,int.class,int.class,settingsType,int.class,java.util.function.BooleanSupplier.class,planType);
        render.setAccessible(true);
        java.util.function.BooleanSupplier never=()->false;
        // The same render again, for what it found at its joins.
        Object again=render.invoke(null,source,channels,rate,settings,Math.max(1,Runtime.getRuntime().availableProcessors()-2),never,plan);
        var samplesOf=again.getClass().getDeclaredMethod("samples");samplesOf.setAccessible(true);
        var mismatchOf=again.getClass().getDeclaredMethod("joinMismatchDb");mismatchOf.setAccessible(true);
        var carriedOf=again.getClass().getDeclaredMethod("carriedOn");carriedOf.setAccessible(true);
        var roundsOf=again.getClass().getDeclaredMethod("rounds");roundsOf.setAccessible(true);
        check(Arrays.equals((float[])samplesOf.invoke(again),out),"Two renders of the same settings differ");
        double[] mismatch=(double[])mismatchOf.invoke(again);int carriedOn=(int)carriedOf.invoke(again),rounds=(int)roundsOf.invoke(again);
        double[] sortedMismatch=mismatch.clone();Arrays.sort(sortedMismatch);
        t0=System.nanoTime();
        Object whole=render.invoke(null,source,channels,rate,settings,1,never,single.get(null));
        double onePieceSeconds=(System.nanoTime()-t0)*1e-9;
        float[] onePiece=(float[])samplesOf.invoke(whole);
        double programme=Math.sqrt(Math.pow(10,rmsDb(onePiece)/10)),apart=rms(out,onePiece,0,out.length),worstApart=0,afterJoin=0;
        for(int i=0;i<out.length;i++)worstApart=Math.max(worstApart,Math.abs(out[i]-onePiece[i]));
        for(int k=1;k<count;k++){int at=(int)((long)k*frames/count)*channels;
            afterJoin=Math.max(afterJoin,rms(out,onePiece,at,Math.min(out.length,at+rate/2*channels)));}
        int firstEnd=(int)((long)frames/count)*channels;boolean firstExact=true;
        for(int i=0;i<firstEnd&&firstExact;i++)firstExact=out[i]==onePiece[i];
        System.out.printf(Locale.US,"AUTO_EQ_TRACK file=\"%s\" rate=%d channels=%d seconds=%.1f%n",path.getFileName(),rate,channels,(double)frames/rate);
        System.out.printf(Locale.US,"AUTO_EQ_SETTINGS amount=%.2f cutBoost=%+.2f rangeDb=%.1f asks=%s%n",eq.getAmount(),eq.getCutBoost(),eq.getRangeDb(),settings);
        System.out.printf(Locale.US,"AUTO_EQ_RENDER seconds=%.2f realtime=x%.1f firstRenderOfTheSession=%.2f stretches=%d workers=%d%n",
                seconds,frames/(double)rate/seconds,cold,count,Math.min(count,Math.max(1,Runtime.getRuntime().availableProcessors()-2)));
        System.out.printf(Locale.US,"AUTO_EQ_AUDIO changedSamples=%d/%d largestSampleChange=%.5f at=%.2fs samplePeak=%.4f at=%.2fs bestLag=%d%n",
                changed,out.length,worst,(double)(worstAt/channels)/rate,peak,(double)(peakAt/channels)/rate,best);
        System.out.printf(Locale.US,"AUTO_EQ_LEVEL lufsIn=%.2f lufsOut=%.2f deltaLu=%+.2f rmsIn=%.2f rmsOut=%.2f truePeakIn=%+.2f truePeakOut=%+.2f deltaPeak=%+.2f%n",
                lufsIn,lufsOut,lufsOut-lufsIn,rmsDb(source),rmsDb(out),tpIn,tpOut,tpOut-tpIn);
        System.out.printf(Locale.US,"AUTO_EQ_TILT in8kMinus200=%.2f out8kMinus200=%.2f in4kMinus100=%.2f out4kMinus100=%.2f%n",
                sIn.levelDbAt(8000)-sIn.levelDbAt(200),sOut.levelDbAt(8000)-sOut.levelDbAt(200),sIn.levelDbAt(4000)-sIn.levelDbAt(100),sOut.levelDbAt(4000)-sOut.levelDbAt(100));
        StringBuilder row=new StringBuilder("AUTO_EQ_CURVE_DB hz:mean[min..max]");
        for(int i=0;i<freqs.length;i++)row.append(String.format(Locale.US," %.0f:%+.1f[%+.1f..%+.1f]",freqs[i],mean[i]/steps,least[i],most[i]));
        System.out.println(row);
        System.out.printf(Locale.US,"AUTO_EQ_CORRECTION_SPAN_DB lowest=%.2f highest=%.2f largest=%.2f%n",lowest,highest,largest);
        var toleranceOf=planType.getDeclaredMethod("toleranceDb");toleranceOf.setAccessible(true);double tolerance=(double)toleranceOf.invoke(plan);
        StringBuilder over=new StringBuilder();double within=0;
        for(int k=0;k<mismatch.length;k++){if(mismatch[k]>tolerance)over.append(String.format(Locale.US," %d:%.4f",k+1,mismatch[k]));else within=Math.max(within,mismatch[k]);}
        System.out.printf(Locale.US,"AUTO_EQ_JOINS joins=%d carriedOn=%d passes=%d curveMismatchDb: median=%.5f largestWithinTolerance=%.5f overTolerance:%s%n",
                mismatch.length,carriedOn,rounds,mismatch.length==0?0:sortedMismatch[mismatch.length/2],within,over.length()==0?" none":over);
        System.out.printf(Locale.US,"AUTO_EQ_STRETCHES againstOnePieceDb: wholeTrack=%.1f worstHalfSecondAfterAJoin=%.1f worstSample=%.1f firstStretchBitExact=%b onePieceSeconds=%.1f%n",
                20*Math.log10(apart/programme+1e-15),20*Math.log10(afterJoin/programme+1e-15),20*Math.log10(worstApart/programme+1e-15),firstExact,onePieceSeconds);
        check(best==0,"Render not aligned with the source");
        check(largest<=eq.getRangeDb()+1e-6,"Correction over the range");
        check(changed>0,"Unchanged render");
        // In the middle of Cut/Boost the correction redistributes; at Cut nothing is raised and the
        // level goes down, at Boost nothing is lowered.
        double lean=eq.getCutBoost();
        if(lean==0)check(Math.abs(lufsOut-lufsIn)<1.5,"Loudness moved by more than 1.5 LU");
        if(lean==-1)check(highest==0&&lufsOut<lufsIn,"Cut raised something or did not lower the level");
        if(lean==1)check(lowest==0,"Boost lowered something");
        check(firstExact,"The first stretch is not the one-piece render's beginning");
        check(apart<1e-4*programme,"Stretches differ from the one-piece render by more than -80 dB");
        check(afterJoin<3e-4*programme&&worstApart<3e-3*programme,"A join of the render stands out against the one-piece render");
        check(before.equals(hash(path)),"Source changed");
        System.out.println("AUTO_EQ_TRACK_PASS aligned=true withinRange=true levelAsCutBoostSays=true stretchesAsOnePiece=true sourceSha256="+before);
    }
}
