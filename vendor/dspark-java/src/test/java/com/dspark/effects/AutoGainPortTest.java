package com.dspark.effects;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AutoGainPortTest {
    @Test void nativeCppVectorsMatchGainAndSamples()throws Exception {
        try(var reader=new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/autogain-cpp-474b7d1.txt")))) {
            AutoGain ag=null;int frame=0,rows=0;
            for(String line;(line=reader.readLine())!=null;) {
                String[] v=line.split(" ");int rate=Integer.parseInt(v[0]),w=Integer.parseInt(v[1]),scenario=Integer.parseInt(v[2]),b=Integer.parseInt(v[3]),n=Integer.parseInt(v[4]);
                if(b==0){ag=new AutoGain();ag.prepare(rate,2);ag.setWeighting(AutoGain.Weighting.values()[w]);frame=0;}
                float[] pcm=new float[n*2];
                for(int i=0;i<n;i++){double t=(frame+i)/(double)rate;pcm[i*2]=(float)(.13*Math.sin(2*Math.PI*1000*t)+.3*Math.sin(2*Math.PI*40*t));pcm[i*2+1]=(float)(-.7*pcm[i*2]);}
                ag.pushReference(pcm,2);
                for(int i=0;i<n;i++){double t=(frame+i)/(double)rate;
                    if(scenario==0){double gain=b<20?4:b<40?.25:1;pcm[i*2]*=(float)gain;pcm[i*2+1]*=(float)gain;}
                    else {pcm[i*2]=(float)(pcm[i*2]+.6*Math.sin(2*Math.PI*40*t));pcm[i*2+1]=(float)(pcm[i*2+1]-.42*Math.sin(2*Math.PI*40*t));}}
                ag.compensate(pcm,2);assertEquals(Double.parseDouble(v[5]),ag.getCompensationDb(),2e-9,line);
                assertEquals(Float.parseFloat(v[6]),pcm[0],1e-7);assertEquals(Float.parseFloat(v[7]),pcm[(n/2)*2+1],1e-7);assertEquals(Float.parseFloat(v[8]),pcm[(n-1)*2],1e-7);
                frame+=n;rows++;
            }assertEquals(720,rows);
        }
    }
    @Test void flatAndKWeightedMatchesDifferOnSubBassButNotUniformGain() {
        float[] dry=new float[48000],wet=new float[48000];
        for(int i=0;i<dry.length;i++){dry[i]=(float)(.1*Math.sin(2*Math.PI*1000*i/48000));wet[i]=dry[i]+(float)(.2*Math.sin(2*Math.PI*20*i/48000));}
        var a=new AutoGain();double k=a.offlineGainDb(dry,wet,1,48000,null);a.setWeighting(AutoGain.Weighting.FLAT);
        assertTrue(k> -1.5);assertEquals(-7,a.offlineGainDb(dry,wet,1,48000,null),.05);
        for(int i=0;i<dry.length;i++)wet[i]=dry[i]*4;
        for(var w:AutoGain.Weighting.values()){a.setWeighting(w);a.setMaxCompensationDb(24);assertEquals(-12.0411998266,a.offlineGainDb(dry,wet,1,48000,null),1e-8);}
    }
    @Test void lifecycleSilenceChannelsFiniteSettersAndRecovery() {
        var a=new AutoGain();float[] x={.2f,-.3f};a.pushReference(x,2);a.compensate(x,2);assertArrayEquals(new float[]{.2f,-.3f},x);
        a.prepare(48000,1);a.setMaxCompensationDb(Double.NaN);a.setSmoothingTimeMs(Double.POSITIVE_INFINITY);
        assertEquals(12,a.getMaxCompensationDb());assertEquals(100,a.getSmoothingTimeMs());
        a.setSmoothingTimeMs(-1);assertEquals(1,a.getSmoothingTimeMs());
        a.pushReference(new float[]{Float.NaN},1);a.compensate(new float[]{Float.NaN},1);
        for(int i=0;i<20;i++){a.pushReference(new float[]{.2f,-.3f},2);float[] out={.1f,.7f};a.compensate(out,2);assertEquals(.7f,out[1]);assertTrue(Double.isFinite(out[0]));}
        a.reset();assertEquals(0,a.getCompensationDb());assertEquals(0,a.offlineGainDb(new float[8],new float[8],1,48000,null));
        assertThrows(IllegalArgumentException.class,()->a.offlineGainDb(new float[]{Float.NaN},new float[1],1,48000,null));
        assertThrows(java.util.concurrent.CancellationException.class,()->a.offlineGainDb(new float[8],new float[8],1,48000,()->{throw new java.util.concurrent.CancellationException();}));
    }
}
