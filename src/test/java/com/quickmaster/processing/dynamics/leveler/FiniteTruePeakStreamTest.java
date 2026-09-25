package com.quickmaster.processing.dynamics.leveler;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FiniteTruePeakStreamTest {
    private static final float[] EOF={-.7042242288589478f,-.41123709082603455f};
    @Test void sixFrameTailGoldenAndAllPartitionsForBothKernels() {
        assertEquals(.7042242288589478,com.dspark.analysis.TruePeak.measureMax(EOF,1),1e-12);
        for(boolean fallback:new boolean[]{false,true})for(int channels:new int[]{1,2})for(int lane=0;lane<channels;lane++)for(int split=0;split<=2;split++) {
            float[] pcm=new float[2*channels];pcm[lane]=EOF[0];pcm[channels+lane]=EOF[1];
            var stream=new FiniteTruePeakStream(channels,fallback);stream.accept(pcm,0,split);stream.accept(pcm,split,2-split);
            double value=stream.finish();assertEquals(.7410990583266539,value,1e-12);assertEquals(6,stream.tailFrames());assertEquals(2,stream.framesAccepted());
            assertEquals(Double.doubleToRawLongBits(value),Double.doubleToRawLongBits(stream.finish()));assertEquals(6,stream.tailFrames());
            assertThrows(IllegalStateException.class,()->stream.accept(pcm,0,0));
        }
    }
    @Test void floatQuantizedGainGoldens() {
        for(double db:new double[]{1,.1})for(boolean fallback:new boolean[]{false,true}) {
            float[] candidate=EOF.clone();for(int i=0;i<candidate.length;i++)candidate[i]=(float)(candidate[i]*StrictMath.exp(db*StrictMath.log(10)/20));
            var stream=new FiniteTruePeakStream(1,fallback);stream.accept(candidate,0,2);
            assertEquals(db==1?.8315268495898636:.7496805612245225,stream.finish(),1e-12);
        }
    }
    @Test void everySeededPartitionMatchesOneChunkAndChannelsNeverShareHistory() {
        float[] pcm=new float[82];Random random=new Random(31);for(int i=0;i<pcm.length;i++)pcm[i]=(float)(random.nextDouble()-.5);
        var reference=new FiniteTruePeakStream(2,false);reference.accept(pcm,0,41);long expected=Double.doubleToRawLongBits(reference.finish());
        for(boolean fallback:new boolean[]{false,true})for(int split=0;split<=41;split++) {
            var stream=new FiniteTruePeakStream(2,fallback);stream.accept(pcm,0,split);stream.accept(pcm,split,41-split);assertEquals(expected,Double.doubleToRawLongBits(stream.finish()));
        }
    }
    @Test void invalidProtocolCannotProduceAProof() {
        assertThrows(IllegalArgumentException.class,()->new FiniteTruePeakStream(0));
        assertThrows(IllegalArgumentException.class,()->new FiniteTruePeakStream(2).accept(new float[3],0,1));
        assertThrows(IllegalArgumentException.class,()->new FiniteTruePeakStream(1).accept(new float[]{Float.NaN},0,1));
        assertThrows(IllegalArgumentException.class,()->new FiniteTruePeakStream(1).accept(new float[2],1,2));
    }
}
