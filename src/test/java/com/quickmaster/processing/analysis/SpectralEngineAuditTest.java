package com.quickmaster.processing.analysis;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class SpectralEngineAuditTest {
    @Test void arbitraryOverlapReconstructsEveryBoundaryAndFrameCountIsBounded() {
        for (int hop : new int[]{64, 256, 300, 512}) {
            var engine = new SpectralEngine(1024, hop);
            for (int length : new int[]{0,1,31,1023,1024,1025,7001}) {
                float[] x = new float[length];
                for(int i=0;i<length;i++) x[i]=(float)Math.sin(i*.57);
                if(length>0) {x[0]=.7f; x[length-1]=-.8f;}
                int[] frames = {0}; engine.analyze(x,(m,n)->frames[0]++);
                assertEquals(engine.frameCount(length),frames[0]);
                assertArrayEquals(x,engine.render(x,(f,n)->{}),.000001f);
            }
            assertTrue(engine.frameCount(Integer.MAX_VALUE)>0);
        }
    }
    @Test void invalidHopAndCancelledWorkFailPromptly() {
        assertThrows(IllegalArgumentException.class,()->new SpectralEngine(1024,0));
        assertThrows(IllegalArgumentException.class,()->new SpectralEngine(1024,1024));
        var e = new SpectralEngine(1024,256);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,()->e.render(new float[48000],(f,n)->{}));
            assertThrows(CancellationException.class,()->e.analyze(new float[48000],(f,n)->{}));
        } finally {Thread.interrupted();}
    }
}
