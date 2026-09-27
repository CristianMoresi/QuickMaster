package com.quickmaster.processing.limit;

import com.dspark.effects.MultibandCrossover;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class LimiterPublicationTest {
    @Test void bypassedWorkerPublicationNeverRemapsCachedPeaks() throws Exception {
        float[] source=signal(10000,2);
        var multi=new MultibandLimiterProcessor(); multi.prepare(48000,source.length); multi.analyze(source,2);
        var bypass=new MultibandLimiterProcessor(); bypass.reuseAnalysisFeatures(multi); bypass.requestPushDb(0,5);
        multi.requestPushDb(0,5); multi.adoptPreparedAnalysis(bypass);
        assertNull(field(multi,"bandEnv")); assertSame(field(bypass,"bandPeakMap"),field(multi,"bandPeakMap"));
        var broad=new BroadbandLimiterProcessor(); broad.setPushDb(2); broad.prepare(48000,source.length); broad.analyze(source,2);
        var off=new BroadbandLimiterProcessor(); off.reuseAnalysisFeatures(broad); off.requestPushDb(7);
        broad.requestPushDb(7); broad.adoptPreparedAnalysis(off);
        assertNull(field(broad,"env")); assertSame(field(off,"peakMapTp"),field(broad,"peakMapTp"));
    }
    @Test void broadbandIncludesFiniteTailAndDoesNotShiftAwayLastSamplePeaks() throws Exception {
        float[] source=signal(37,2);
        var limiter=new BroadbandLimiterProcessor(); limiter.setPushDb(3); limiter.prepare(48000,source.length); limiter.analyze(source,2);
        assertEquals(com.dspark.analysis.TruePeak.measureMax(source,2),limiter.getTruePeak(),0);
        float[] map=(float[])field(limiter,"peakMapTp");
        for(int f=0;f<map.length;f++) for(int c=0;c<2;c++) assertTrue(map[f]>=Math.abs(source[2*f+c]));
    }

    @Test void featureCacheIsContentRateAndChannelBoundAndKeepsOnlyOneMap() throws Exception {
        float[] source=signal(8003,2);
        var multi=new MultibandLimiterProcessor(); multi.prepare(48000,source.length); multi.analyze(source,2);
        var fork=new MultibandLimiterProcessor(); fork.requestPushDb(2,4); fork.reuseAnalysisFeatures(multi); fork.prepare(48000,source.length);
        Object map=field(multi,"bandPeakMap"); fork.analyze(source.clone(),2);
        assertSame(map,field(fork,"bandPeakMap"));
        source[13]*=.7f; fork.analyze(source,2); assertNotSame(map,field(fork,"bandPeakMap"));
        var cold=new MultibandLimiterProcessor(); cold.requestPushDb(2,4); cold.prepare(48000,source.length); cold.analyze(source,2);
        float[][] expected=(float[][])field(cold,"bandPeakMap"), actual=(float[][])field(fork,"bandPeakMap");
        for(int b=0;b<4;b++) assertArrayEquals(expected[b],actual[b]);
        Object changed=field(fork,"bandPeakMap"); fork.prepare(44100,source.length); fork.analyze(source,2);
        assertNotSame(changed,field(fork,"bandPeakMap"));
        changed=field(fork,"bandPeakMap"); fork.analyze(source,1); assertNotSame(changed,field(fork,"bandPeakMap"));
        var broad=new BroadbandLimiterProcessor(); broad.prepare(48000,source.length); broad.analyze(source,2);
        var broadFork=new BroadbandLimiterProcessor(); broadFork.reuseAnalysisFeatures(broad); broadFork.prepare(48000,source.length);
        broadFork.analyze(source.clone(),2); assertSame(field(broad,"peakMapTp"),field(broadFork,"peakMapTp"));
        source[17]*=.7f; broadFork.analyze(source,2); assertNotSame(field(broad,"peakMapTp"),field(broadFork,"peakMapTp"));
    }
    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    static float[] signal(int frames, int channels) {
        Random random = new Random(4048); float[] signal = new float[frames * channels];
        for (int i=0;i<signal.length;i++) signal[i]=(random.nextFloat()-.5f)*.8f;
        return signal;
    }
    @Test void multibandRequestIsConstantWorkAndAdoptionSharesWorkerEnvelope() throws Exception {
        float[] source=signal(20000,2);
        var live=new MultibandLimiterProcessor(); live.setEnabled(true); live.prepare(48000,source.length); live.analyze(source,2);
        Object envelope=field(live,"bandEnv"); double gr=live.getBandGrAtPosition(0,12000);
        live.requestPushDb(0,5);
        assertSame(envelope,field(live,"bandEnv")); assertEquals(gr,live.getBandGrAtPosition(0,12000));
        var worker=new MultibandLimiterProcessor(); worker.setEnabled(true); worker.setPushDb(0,5);
        worker.prepare(48000,source.length); worker.analyze(source,2); live.adoptAnalysis(worker);
        assertSame(field(worker,"bandEnv"),field(live,"bandEnv"));
        assertEquals(worker.getBandGrAtPosition(0,12000),live.getBandGrAtPosition(0,12000));
    }
    @Test void broadbandRequestKeepsActualMeterAndAdoptsWithoutReallocation() throws Exception {
        float[] source=signal(20000,2);
        var live=new BroadbandLimiterProcessor(); live.setEnabled(true); live.setPushDb(2); live.prepare(48000,source.length); live.analyze(source,2);
        Object envelope=field(live,"env"); double gr=live.getGrAtPosition(12000);
        live.requestPushDb(6);
        assertSame(envelope,field(live,"env")); assertEquals(gr,live.getGrAtPosition(12000));
        var worker=new BroadbandLimiterProcessor(); worker.setEnabled(true); worker.setPushDb(6);
        worker.prepare(48000,source.length); worker.analyze(source,2); live.adoptAnalysis(worker);
        assertSame(field(worker,"env"),field(live,"env"));
        assertEquals(worker.getGrAtPosition(12000),live.getGrAtPosition(12000));
    }
    @Test void streamedLinkedPeakMapsMatchIndependentWholeBufferSplit() throws Exception {
        for(int rate:new int[]{44100,48000,96000}) for(int channels:new int[]{1,2}) {
            float[] source=signal(18007,channels);
            var crossover=new MultibandCrossover(); crossover.prepare(rate,channels,MultibandLimiterProcessor.CROSSOVERS);
            int latency=crossover.getLatency();
            float[][] bands=crossover.splitWhole(java.util.Arrays.copyOf(source,source.length+latency*channels),channels);
            var limiter=new MultibandLimiterProcessor(); limiter.prepare(rate,source.length); limiter.analyze(source,channels);
            float[][] maps=(float[][])field(limiter,"bandPeakMap");
            for(int band=0;band<4;band++) for(int f=0;f<18007;f++) {
                float expected=0; for(int c=0;c<channels;c++) expected=Math.max(expected,Math.abs(bands[band][(f+latency)*channels+c]));
                assertEquals(expected,maps[band][f],2e-7f,"rate="+rate+", ch="+channels+", frame="+f);
            }
        }
    }
}
