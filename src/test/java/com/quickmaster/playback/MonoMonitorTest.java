package com.quickmaster.playback;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonoMonitorTest {
    @Test void settledEndpointsAreExactAndRepeatedRequestsDoNotRestartTheRamp() {
        for(int rate:new int[]{8000,44100,48000,96000,192000}) {
            var monitor=new MonoMonitor(rate,false);assertEquals(1,monitor.nextSideGain());
            int length=(int)Math.round(rate*.020);double previous=1;
            for(int i=0;i<length;i++) {
                monitor.setMono(true);double gain=monitor.nextSideGain();
                assertTrue(gain>=0&&gain<=previous);previous=gain;
            }
            assertEquals(0,previous);assertEquals(0,monitor.nextSideGain());
            monitor.setMono(false);
            for(int i=0;i<length;i++) {
                double gain=monitor.nextSideGain();assertTrue(gain>=previous&&gain<=1);previous=gain;
            }
            assertEquals(1,previous);assertEquals(1,new MonoMonitor(rate,false).nextSideGain());
            assertEquals(0,new MonoMonitor(rate,true).nextSideGain());
        }
    }

    @Test void rapidReversalsStartAtCurrentGainWithoutAnOvershootOrStep() {
        var monitor=new MonoMonitor(48000,false);double previous=1;
        for(int i=0;i<8000;i++) {
            monitor.setMono((i/113)%2==0);
            double gain=monitor.nextSideGain();
            assertTrue(gain>=0&&gain<=1);assertTrue(Math.abs(gain-previous)<.0016);
            previous=gain;
        }
        monitor.setMono(true);for(int i=0;i<960;i++)monitor.nextSideGain();
        assertEquals(0,monitor.nextSideGain());
    }
}
