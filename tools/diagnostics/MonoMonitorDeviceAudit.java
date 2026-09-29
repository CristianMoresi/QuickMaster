package com.quickmaster.playback;

import com.quickmaster.audio.*;
import com.quickmaster.config.LevelerExclusionStore;
import com.quickmaster.processing.ProcessingPipeline;
import javax.sound.sampled.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Unattenuated private music through the actual PCM16 transport to a silent timed sink. */
public final class MonoMonitorDeviceAudit {
    static final class Sink {
        final float[] expected;
        final int rate;
        AudioPlayer player;boolean desired,previous,closed;int age=100,writes,toggles;long frames,checked;
        double maxError,maxMidError;
        final SourceDataLine line;
        Sink(float[] expected,int rate){this.expected=expected;this.rate=rate;line=createLine();}
        double sample(byte[] pcm,int offset){return (short)((pcm[offset]&255)|pcm[offset+1]<<8)/32768.0;}
        double clamp(double sample){return Math.max(-1,Math.min(1,sample));}
        SourceDataLine createLine(){return (SourceDataLine)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SourceDataLine.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "write":
                    byte[] pcm=(byte[])args[0];int offset=(int)args[1],length=(int)args[2],n=length/4;
                    if(desired!=previous)age=0;
                    for(int f=0;f<n;f++) {
                        int input=2*((int)frames+f);double l=sample(pcm,offset+4*f),r=sample(pcm,offset+4*f+2);
                        double mid=.5*(double)expected[input]+.5*(double)expected[input+1];
                        if(Math.abs(expected[input])<=1 && Math.abs(expected[input+1])<=1)
                            maxMidError=Math.max(maxMidError,Math.abs(.5*l+.5*r-mid));
                        // Skip only the 20 ms switching interval; check every
                        // frame's Mid independently, including those transitions.
                        if(age>=Math.ceil(rate*.020/AudioPlayer.BUFFER_FRAMES)) {
                            maxError=Math.max(maxError,Math.abs(l-clamp(desired?mid:expected[input])));
                            maxError=Math.max(maxError,Math.abs(r-clamp(desired?mid:expected[input+1])));checked+=2;
                        }
                    }
                    previous=desired;age++;writes++;
                    if(writes%6==1){desired=!desired;player.setListenInMono(desired);toggles++;}
                    Thread.sleep(Math.max(1,Math.round(1000.0*n/rate)));
                    frames+=n;return length;
                case "getLongFramePosition":return frames;
                case "getFramePosition":return (int)frames;
                case "close":closed=true;return null;
                case "getControls":return new Control[0];
                case "isOpen":case "isActive":case "isRunning":return !closed;
                case "toString":return "SilentMonoMonitorDevice";
                default:return method.getReturnType()==boolean.class?false:method.getReturnType()==int.class?0:null;
            }
        });}
    }
    public static void main(String[] args)throws Exception {
        System.out.println("AUDIO_PLAYER_CLASSES "+AudioPlayer.class.getProtectionDomain().getCodeSource().getLocation());
        for(String name:args) {
            Path path=Path.of(name);String identity=LevelerExclusionStore.identity(path);
            AudioFile source=AudioFormatDetector.loadAuto(name);source.load();
            if(source.getChannels()!=2)throw new AssertionError("Expected stereo test input");
            int rate=source.getSampleRate(),frames=Math.min(131073,source.getSamples().length/2);
            int start=Math.min(60*rate,source.getSamples().length/2-frames);
            float[] pcm=Arrays.copyOfRange(source.getSamples(),2*start,2*(start+frames)),saved=pcm.clone();
            int overrange=0;for(float v:pcm)if(Math.abs(v)>1)overrange++;
            Sink sink=new Sink(pcm,rate);var errors=new CopyOnWriteArrayList<Throwable>();
            try(var player=new AudioPlayer(new ProcessingPipeline(),f->sink.line,Runnable::run,errors::add)) {
                sink.player=player;player.prepare(new WavFile("private excerpt, memory only",rate,2,pcm,32,true));
                player.publishRender(pcm,pcm);player.play();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(player.getState()!=AudioPlayer.State.STOPPED && System.nanoTime()<deadline)Thread.sleep(5);
                if(!errors.isEmpty()||!sink.closed||sink.frames!=frames||sink.checked<100000||sink.toggles<10
                        ||sink.maxError>.00005||sink.maxMidError>.00005||!Arrays.equals(saved,pcm))
                    throw new AssertionError("Monitor mismatch: "+errors+" frames="+sink.frames+" checked="+sink.checked+" error="+sink.maxError);
            }
            if(!identity.equals(LevelerExclusionStore.identity(path)))throw new AssertionError("Private source changed");
            System.out.printf(Locale.US,"MONO_DEVICE_PASS track=%s toggles=%d frames=%d checked=%d error=%.9g midError=%.9g sourceOverrangeSamples=%d pcm16RangeAccounted=true sourceUnchanged=true inputTrimDb=0 noWindowsAudio=true%n",
                    path.getFileName(),sink.toggles,sink.frames,sink.checked,sink.maxError,sink.maxMidError,overrange);
        }
    }
}
