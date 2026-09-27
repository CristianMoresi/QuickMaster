import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Old/new packaged DSP comparison; generated PCM only, no audio files written.
 * Excludes broadband's intentionally corrected endpoint/time-alignment analysis.
 * Fixed tolerances: maximum absolute sample error 1e-5, RMS error 1e-6.
 */
public final class PackagedDspCompatibilityProbe {
    static URLClassLoader loader(Path directory) throws Exception {
        try(var files=Files.list(directory)) {
            URL[] urls=files.filter(p->p.toString().endsWith(".jar")).sorted().map(p->{
                try{return p.toUri().toURL();}catch(Exception e){throw new IllegalStateException(e);}
            }).toArray(URL[]::new);
            return new URLClassLoader(urls,ClassLoader.getPlatformClassLoader());
        }
    }
    static Object make(ClassLoader loader,String name) throws Exception {
        return loader.loadClass(name).getConstructor().newInstance();
    }
    static void set(Object owner,String name,double value) throws Exception {
        owner.getClass().getMethod(name,double.class).invoke(owner,value);
    }
    static float[] render(ClassLoader loader,float[] input,int rate,int channels,boolean linear) throws Exception {
        String base="com.quickmaster.processing.";
        Class<?> processor=loader.loadClass(base+"AudioProcessor");
        Object pipe=make(loader,base+"ProcessingPipeline");
        Method add=pipe.getClass().getMethod("addProcessor",processor);
        Object track=make(loader,base+"analysis.TrackAnalysis");
        track.getClass().getMethod("analyze",float[].class,int.class,double.class).invoke(track,input,channels,(double)rate);
        set(track,"setManualBpm",120);
        for(String name:List.of("eq.AutoEqProcessor","eq.EqualizerProcessor","FadeProcessor",
                "dynamics.PeakCompProcessor","dynamics.BeatCompProcessor","dynamics.LevelerProcessor",
                "dynamics.PunchProcessor","clip.SoftClipProcessor","clip.HardClipProcessor",
                "limit.MultibandLimiterProcessor","PeakNormalizer")) {
            Object p=make(loader,base+name);
            processor.getMethod("setEnabled",boolean.class).invoke(p,true);
            if(name.equals("eq.EqualizerProcessor")) {
                Class<?> bandType=loader.loadClass("com.dspark.effects.MasterEqualizer$Band");
                Object band=bandType.getConstructor().newInstance();
                bandType.getField("gainDb").setDouble(band,2.5);
                bandType.getField("frequency").setDouble(band,1350);
                bandType.getField("q").setDouble(band,1.15);
                Field phase=bandType.getField("phase");
                phase.set(band,Arrays.stream(phase.getType().getEnumConstants())
                        .filter(x->x.toString().equals(linear?"LINEAR":"MINIMUM")).findFirst().orElseThrow());
                p.getClass().getMethod("setNumBands",int.class).invoke(p,1);
                p.getClass().getMethod("setBand",int.class,bandType).invoke(p,0,band);
            }
            if(name.equals("FadeProcessor")){set(p,"setFadeInSec",.2);set(p,"setFadeOutSec",.25);}
            if(name.equals("dynamics.PeakCompProcessor")||name.equals("dynamics.BeatCompProcessor")) {
                set(p,"setTargetDb",-1);
                p.getClass().getMethod("setTrackAnalysis",track.getClass()).invoke(p,track);
            }
            if(name.equals("dynamics.PunchProcessor")) {
                set(p,"setAmountDb",1);
                p.getClass().getMethod("setTrackAnalysis",track.getClass()).invoke(p,track);
            }
            if(name.equals("PeakNormalizer"))set(p,"setTargetDbfs",-1);
            add.invoke(pipe,p);
        }
        pipe.getClass().getMethod("prepare",int.class,long.class).invoke(pipe,rate,(long)input.length);
        return (float[])pipe.getClass().getMethod("analyzeAndRender",float[].class,int.class,int.class,
                float[].class,java.util.function.DoubleConsumer.class,java.util.function.ObjIntConsumer.class)
                .invoke(pipe,input,channels,0,null,null,null);
    }
    static float[] fixture(int rate,int channels) {
        float[] pcm=new float[(rate*9+17)*channels]; Random random=new Random(82631);
        for(int f=0;f<pcm.length/channels;f++) {
            double t=f/(double)rate;
            double amplitude=(t<1?.3:1)*Math.min(1,Math.max(0,(9-t)/.15));
            double beat=t%.5;
            double signal=.14*Math.sin(2*Math.PI*233.4*t)+.07*Math.sin(2*Math.PI*1372.1*t)
                    +.3*Math.sin(2*Math.PI*(65*beat+30*beat*beat))*Math.exp(-beat*28)
                    +.05*(random.nextDouble()-.5)*Math.exp(-(t%.25)*60);
            pcm[f*channels]=(float)(amplitude*signal);
            if(channels==2)pcm[f*channels+1]=(float)(amplitude*(-.78*signal+.03*Math.sin(2*Math.PI*383*t)));
        }
        return pcm;
    }
    public static void main(String[] args) throws Exception {
        try(var old=loader(Path.of(args[0]));var next=loader(Path.of(args[1]))) {
            for(int rate:new int[]{44100,48000,96000}) for(int channels:new int[]{1,2})
                for(boolean linear:new boolean[]{false,true}) {
                    float[] input=fixture(rate,channels), copy=input.clone();
                    float[] expected=render(old,input,rate,channels,linear),actual=render(next,input,rate,channels,linear);
                    if(!Arrays.equals(input,copy))throw new AssertionError("Input mutated");
                    if(actual.length!=expected.length)throw new AssertionError("Output length changed");
                    double peakError=0,squaredError=0;long changed=0;
                    for(int i=0;i<input.length;i++) {
                        double error=Math.abs((double)actual[i]-expected[i]);
                        if(!Float.isFinite(actual[i]))throw new AssertionError("Non-finite PCM");
                        peakError=Math.max(peakError,error);squaredError+=error*error;
                        if(actual[i]!=input[i])changed++;
                    }
                    double rmsError=Math.sqrt(squaredError/input.length);
                    if(changed==0||peakError>1e-5||rmsError>1e-6)
                        throw new AssertionError("Compatibility failure rate="+rate+" ch="+channels+" linear="+linear+" peak="+peakError+" rms="+rmsError);
                    System.out.printf(Locale.ROOT,"PACKAGED_DSP_COMPAT_PASS rate=%d channels=%d linear=%s peakError=%.9g rmsError=%.9g changed=%d%n",
                            rate,channels,linear,peakError,rmsError,changed);
                }
        }
    }
}
