import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Isolated old/new JAR comparison: PCM tolerance, latency and warm single-thread throughput. */
public class DsparkKernelComparison {
    static volatile double sink;
    static URLClassLoader loader(String path) throws Exception {
        return new URLClassLoader(new URL[]{Path.of(path).toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }
    static void fft(URLClassLoader loader, String label) throws Exception {
        Class<?> type = loader.loadClass("com.dspark.core.FFTReal");
        Method forward = type.getMethod("forward", float[].class, float[].class);
        Method inverse = type.getMethod("inverse", float[].class, float[].class);
        Random random = new Random(923);
        for (int size : new int[]{1024, 8192, 16384, 65536}) {
            Object fft = type.getConstructor(int.class).newInstance(size);
            float[] time = new float[size], freq = new float[size + 2], out = new float[size];
            for (int i = 0; i < size; i++) time[i] = random.nextFloat() - .5f;
            int repeats = Math.max(400, 20000000 / size);
            for (int i = 0; i < repeats / 2; i++) { forward.invoke(fft, time, freq); inverse.invoke(fft, freq, out); }
            long start = System.nanoTime();
            for (int i = 0; i < repeats; i++) { forward.invoke(fft, time, freq); inverse.invoke(fft, freq, out); }
            sink += out[17];
            System.out.printf(Locale.ROOT, "FFT %s size=%d roundTripUs=%.3f%n", label, size, (System.nanoTime()-start)/1000.0/repeats);
        }
    }
    static Object engine(URLClassLoader loader, int factor, int ch, int block, String quality) throws Exception {
        Class<?> type = loader.loadClass("com.dspark.core.OversamplingEngine");
        Class<?> q = loader.loadClass("com.dspark.core.OversamplingEngine$Quality");
        Object e = type.getConstructor().newInstance();
        Object qualityValue = Arrays.stream(q.getEnumConstants()).filter(x -> x.toString().equals(quality)).findFirst().orElseThrow();
        type.getMethod("prepare", int.class, int.class, int.class, q).invoke(e, factor, ch, block, qualityValue);
        return e;
    }
    static float[] render(Object eng, float[] src, int block, int ch) throws Exception {
        var type = eng.getClass();
        Method up = type.getMethod("upsample", float[].class, int.class);
        Method down = type.getMethod("downsample", float[].class, int.class, float[].class);
        int frames = src.length/ch;
        float[] result = new float[src.length];
        for (int pos = 0; pos < frames; pos += block) {
            int count = Math.min(block, frames-pos);
            float[] in = Arrays.copyOfRange(src, pos*ch, (pos+count)*ch), out = new float[in.length];
            float[] hi = (float[]) up.invoke(eng, in, count);
            // Exercise a nonlinear high-rate path, not just a cancelling identity.
            for (int i = 0; i < count*ch*(int)type.getMethod("getFactor").invoke(eng); i++) hi[i] = Math.max(-.3f, Math.min(.3f, hi[i]));
            down.invoke(eng, hi, count, out);
            System.arraycopy(out, 0, result, pos*ch, out.length);
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        try (var old = loader(args[0]); var next = loader(args[1])) {
            Method oldEnvelope=old.loadClass("com.dspark.effects.LimiterEnvelope").getMethod("computeFromPeaks",float[].class,double.class,int.class,int.class);
            Method nextEnvelope=next.loadClass("com.dspark.effects.LimiterEnvelope").getMethod("computeFromPeaks",float[].class,double.class,int.class,int.class);
            int envelopes=0;
            for(int frames:new int[]{0,1,17,200003}) for(double threshold:new double[]{0,.001,.3,1,Double.NaN})
                for(int attack:new int[]{1,64,1500}) {
                    float[] peaks=new float[frames]; Random random=new Random(402);
                    for(int i=0;i<frames;i++) peaks[i]=random.nextFloat();
                    float[] expected=(float[])oldEnvelope.invoke(null,peaks,threshold,attack,3840);
                    float[] actual=(float[])nextEnvelope.invoke(null,peaks,threshold,attack,3840);
                    if(!Arrays.equals(expected,actual)) throw new AssertionError("Limiter envelope changed");
                    envelopes++;
                }
            System.out.println("LIMITER_ENVELOPE_PASS cases="+envelopes+" bitExact=true");
            Class<?> oldTp=old.loadClass("com.dspark.analysis.TruePeak"),nextTp=next.loadClass("com.dspark.analysis.TruePeak");
            Object od=oldTp.getConstructor().newInstance(), nd=nextTp.getConstructor().newInstance();
            Method op=oldTp.getMethod("process",double.class),np=nextTp.getMethod("process",double.class);
            Random peakRandom=new Random(839);
            for(int i=0;i<30000;i++) {
                double sample=i%97==0?0:peakRandom.nextDouble()*2-1;
                if(!op.invoke(od,sample).equals(np.invoke(nd,sample))) throw new AssertionError("True peak streaming changed at "+i);
            }
            System.out.println("TRUE_PEAK_STREAM_PASS samples=30000 bitExact=true");
            double max = 0;
            for (String quality : new String[]{"LOW","MEDIUM","HIGH","MAXIMUM"})
                for (int factor : new int[]{1,2,4,8,16,32,64})
                    for (int block : new int[]{1,17,256}) {
                        int ch = 2;
                        float[] src = new float[521*ch]; Random random = new Random(197);
                        for (int i = 0; i < src.length; i++) src[i] = random.nextFloat()*2-1;
                        Object a = engine(old,factor,ch,block,quality), b = engine(next,factor,ch,block,quality);
                        float[] expected = render(a,src,block,ch), actual = render(b,src,block,ch);
                        for (int i = 0; i < src.length; i++) {
                            double error = Math.abs(actual[i]-expected[i]); max=Math.max(max,error);
                            if (!Float.isFinite(actual[i]) || error > 2e-6)
                                throw new AssertionError("Oversampling " + quality+"/"+factor+"/"+block+" error="+error);
                        }
                    }
            System.out.println("OVERSAMPLING_PCM_PASS combinations=84 stereo=true nonlinear=true maxAbsError="+max);
            fft(old,"old"); fft(next,"new");
            for (var entry : List.of(Map.entry("old",old),Map.entry("new",next))) {
                Object eng = engine(entry.getValue(),8,2,1024,"HIGH");
                Method up = eng.getClass().getMethod("upsample",float[].class,int.class);
                Method down = eng.getClass().getMethod("downsample",float[].class,int.class,float[].class);
                float[] src = new float[2048], out = new float[2048]; Arrays.fill(src,.2f);
                for (int i=0;i<300;i++) down.invoke(eng,up.invoke(eng,src,1024),1024,out);
                long start=System.nanoTime();
                for (int i=0;i<1000;i++) down.invoke(eng,up.invoke(eng,src,1024),1024,out);
                System.out.printf(Locale.ROOT,"OVERSAMPLING %s factor=8 stereoFrames=1024 us=%.3f%n",entry.getKey(),(System.nanoTime()-start)/1e6);
                sink+=out[19];
            }
        }
    }
}
