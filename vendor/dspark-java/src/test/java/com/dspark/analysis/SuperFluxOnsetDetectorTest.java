package com.dspark.analysis;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SuperFluxOnsetDetectorTest {
    static float[] signal(int rate,int kind) {
        float[] x=new float[rate*3];int state=817;double phase=0;
        for(int i=0;i<x.length;i++) {
            state^=state<<13;state^=state>>>17;state^=state<<5;
            double white=Integer.toUnsignedLong(state)/4294967295.0*2-1,t=i/(double)rate;
            if(kind==0) {
                int local=i-rate/5;if(local<0)continue;int k=local/(rate/2),j=local%(rate/2);
                if(j<rate*.008)x[i]=(float)((k<3?.025:.9)*white*Math.exp(-j/(.003*rate)));
            } else if(kind==1)x[i]=(float)(.5*Math.sin(2*Math.PI*50*t));
            else {phase+=2*Math.PI*440*(1+.03*Math.sin(2*Math.PI*6*t))/rate;
                x[i]=(float)(.5*Math.sin(phase)*(.8+.2*Math.sin(2*Math.PI*5*t)));}
        }
        return x;
    }

    @Test void matchesActualNativeOfflineEventsAndNovelty() throws Exception {
        var input=getClass().getResourceAsStream("/superflux-cpp-474b7d1.txt");assertNotNull(input);
        Map<String,SuperFluxOnsetDetector.Result> results=new HashMap<>();int frames=0,events=0;
        double worst=0;
        try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                String[] fields=line.split(" ");int rate=Integer.parseInt(fields[1]),kind=Integer.parseInt(fields[2]);
                var result=results.computeIfAbsent(rate+":"+kind,key->new SuperFluxOnsetDetector().analyze(signal(rate,kind),1,rate,.01));
                if(fields[0].equals("ODF")) {
                    int row=Integer.parseInt(fields[3]);double expected=Double.parseDouble(fields[4]);
                    double error=Math.abs(expected-result.novelty()[row]);worst=Math.max(worst,error);
                    assertEquals(expected,result.novelty()[row],2e-6,line);frames++;
                } else {
                    int count=Integer.parseInt(fields[3]);assertEquals(count,result.times().length,line);
                    for(int k=0;k<count;k++)assertEquals(Long.parseLong(fields[k+4]),Math.round(result.times()[k]*rate),line);
                    events+=count;
                }
            }
        }
        assertEquals(9,results.size());assertTrue(frames>5300);assertEquals(18,events);
        System.out.printf(Locale.ROOT,"SUPERFLUX_NATIVE_PASS frames=%d events=%d maxOdfError=%.12g%n",frames,events,worst);
    }

    @Test void steadyCarriersAndVibratoHaveNoInteriorEvents() {
        for(int rate:new int[]{44100,48000,96000})for(double hz:new double[]{20,50,80,110,220,300,1000,10000}) {
            float[] x=new float[rate*3];for(int i=0;i<x.length;i++)x[i]=(float)(.5*Math.sin(2*Math.PI*hz*i/rate));
            var result=new SuperFluxOnsetDetector().analyze(x,1,rate,.01);
            assertEquals(0,Arrays.stream(result.times()).filter(t->t>.25&&t<2.75).count(),rate+" / "+hz);
        }
        for(int rate:new int[]{44100,48000,96000})assertEquals(0,
                new SuperFluxOnsetDetector().analyze(signal(rate,2),1,rate,.01).times().length,"FM/AM rate="+rate);
    }

    @Test void pooledStereoDoesNotCancelOrChangeMonoEvents() {
        float[] mono=signal(48000,0),stereo=new float[mono.length*2];
        var detector=new SuperFluxOnsetDetector();var expected=detector.analyze(mono,1,48000,.01);
        for(int sign:new int[]{1,-1}) {
            for(int i=0;i<mono.length;i++) {stereo[i*2]=mono[i];stereo[i*2+1]=mono[i]*sign;}
            var result=detector.analyze(stereo,2,48000,.01);
            assertArrayEquals(expected.times(),result.times());assertArrayEquals(expected.novelty(),result.novelty());
        }
    }

    @Test void validatesTailAndEmptyInputsAndHonoursCancellation() {
        var detector=new SuperFluxOnsetDetector();
        assertEquals(0,detector.analyze(new float[0],1,48000,.01).times().length);
        float[] tail=new float[241];tail[240]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->detector.analyze(tail,1,48000,.01));
        assertThrows(IllegalArgumentException.class,()->detector.analyze(new float[3],2,48000,.01));
        Thread.currentThread().interrupt();
        try {assertThrows(java.util.concurrent.CancellationException.class,()->detector.analyze(new float[1],1,48000,.01));}
        finally {Thread.interrupted();}
    }
}
