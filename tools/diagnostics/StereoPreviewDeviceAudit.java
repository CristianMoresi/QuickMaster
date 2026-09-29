package com.quickmaster.playback;

import com.quickmaster.audio.*;
import com.quickmaster.processing.*;
import com.quickmaster.processing.stereo.*;
import javax.sound.sampled.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;

/** Real PCM through transport, crossfade and PCM16 device writes, to a silent timed sink. */
public class StereoPreviewDeviceAudit {
    static ProcessingPipeline chain(double amount) {
        return chain(amount,false);
    }
    static ProcessingPipeline chain(double amount,boolean lows) {
        return chain(amount,lows?0:175,0,false);
    }
    static ProcessingPipeline chain(double amount,double lowCut,double sideGain,boolean regulation) {
        var stereo=new StereoImageProcessor();stereo.setEnabled(true);
        stereo.setSettings(new StereoImageSettings(true,amount,lowCut,regulation,.5,regulation,3,StereoProfile.AUTO,.12,sideGain));
        var chain=new ProcessingPipeline();chain.addProcessor(stereo);
        var normalizer=new PeakNormalizer();normalizer.setEnabled(false);chain.addProcessor(normalizer);
        return chain;
    }
    static class Sink {
        AudioPlayer player; AudioFormat format; long frames; volatile long checked, changed;
        PreviewWindow previous; int stable; volatile double error; volatile boolean closed;
        final SourceDataLine line=(SourceDataLine)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SourceDataLine.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "open":format=(AudioFormat)args[0];return null;
                case "close":closed=true;return null;
                case "write":
                    int count=(int)args[2], n=count/format.getFrameSize(), offset=(int)args[1];byte[] pcm=(byte[])args[0];
                    PreviewWindow w=player.getActivePreviewWindow();long start=player.getPreviewRequestFrame();
                    if(w==previous)stable++;else stable=0;previous=w;
                    if(w!=null&&stable>=1&&w.covers(start,n)) {
                        for(int i=0;i<n*2;i++) {
                            double sample=(short)((pcm[offset+2*i]&255)|(pcm[offset+2*i+1]<<8))/32768.0;
                            error=Math.max(error,Math.abs(sample-w.sample(start+i/2,i%2)));checked++;
                        }
                        if(w.generation()>0)changed++;
                    }
                    Thread.sleep(Math.max(1,Math.round(1000.0*n/format.getSampleRate())));
                    frames+=n;return count;
                case "getLongFramePosition":return frames;
                case "getFramePosition":return (int)frames;
                case "getFormat":return format;
                case "getBufferSize":return 4096;
                case "getControls":return new Control[0];
                case "isOpen":case "isActive":case "isRunning":return !closed;
                case "toString":return "SilentTimedStereoSink";
                default:return method.getReturnType()==boolean.class?false:method.getReturnType()==int.class?0:null;
            }
        });
    }
    public static void main(String[] args)throws Exception {
        var file=AudioFormatDetector.loadAuto(args[0]);file.load();int rate=file.getSampleRate();float[] source=file.getSamples();
        // Deliberate input headroom for the PCM16 transport probe. Production
        // Stereo Image must NOT attenuate the mix when normalization is off.
        // Unscaled real-song gain ownership is checked by StereoPowerAudit.
        for(int i=0;i<source.length;i++)source[i]*=.125f;
        System.out.println("STEREO_DEVICE_INPUT inputTrimDb=-18.0618 intentionalTestHeadroom=true");
        var errors=new CopyOnWriteArrayList<Throwable>();Sink sink=new Sink();
        boolean background=Arrays.asList(args).contains("--background");
        try(var worker=Executors.newSingleThreadExecutor();
            var player=new AudioPlayer(new ProcessingPipeline(),f->sink.line,Runnable::run,errors::add);
            var preview=new InteractivePreview(player,errors::add)) {
            sink.player=player;player.prepare(file);player.publishRender(source,source.clone());player.seekTo(90);player.play();
            var beganBackground=new CountDownLatch(1);
            Future<Integer> full=background?worker.submit(()->{
                var chain=chain(.75);chain.prepare(rate,source.length);
                beganBackground.countDown();
                float[] pcm=chain.analyzeAndRender(source,2,0,null,null,null);
                for(float sample:pcm)if(!Float.isFinite(sample))throw new AssertionError("Invalid background render");
                return pcm.length;
            }):null;
            if(background&&!beganBackground.await(2,TimeUnit.SECONDS))throw new AssertionError("Background render did not start");
            Thread.sleep(100);List<Double> times=new ArrayList<>();
            double[] amounts={.25,.5,1,.5,1,1,1,0,.5,.5,.5,.5};
            boolean extended=Arrays.asList(args).contains("--extended");
            for(int edit=0;edit<amounts.length;edit++) {
                if(!extended&&edit==7)break;
                double amount=amounts[edit];boolean lows=edit==5;
                double cut=edit==8?20:edit==9?1000:edit==10?5000:lows?0:175;
                double gain=edit==10?-3:edit==11?3:0;boolean regulation=edit==11;
                long before=player.getConsumedPreviewGeneration(),began=System.nanoTime();
                preview.request(source,rate,2,()->new PreviewWindowRenderer(chain(amount,cut,gain,regulation),source,rate,2,1));
                while(player.getConsumedPreviewGeneration()<=before) {
                    if(System.nanoTime()-began>TimeUnit.SECONDS.toNanos(2))throw new AssertionError("Generation never reached the device write path: "+errors);
                    Thread.sleep(2);
                }
                double ms=(System.nanoTime()-began)/1e6;times.add(ms);
                System.out.printf(Locale.US,"STEREO_DEVICE amount=%.2f lowCut=%.0f sideGain=%.1f regulation=%s consumedMs=%.3f generation=%d%n",amount,cut,gain,regulation,ms,player.getConsumedPreviewGeneration());
                Thread.sleep(220);
            }
            Thread.sleep(600);player.pause();
            if(full!=null&&full.isDone())throw new AssertionError("Background render did not overlap the complete interactive exercise");
            if(!errors.isEmpty()||sink.checked<50000||sink.error>.00008)throw new AssertionError("Device mismatch: samples="+sink.checked+" error="+sink.error+" "+errors);
            Collections.sort(times);double p95=times.get(times.size()-1);
            if(p95>(extended?500:250))throw new AssertionError("Device-path latency budget exceeded: "+p95);
            if(full!=null&&full.get(90,TimeUnit.SECONDS)!=source.length)throw new AssertionError("Background length changed");
            System.out.printf(Locale.US,"STEREO_DEVICE_PASS checkedPcm16Samples=%d maxError=%.9g p95Ms=%.3f backgroundRender=%s noWindowsAudio=true%n",sink.checked,sink.error,p95,background);
        }
    }
}
