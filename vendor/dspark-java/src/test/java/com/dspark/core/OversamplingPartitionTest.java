package com.dspark.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OversamplingPartitionTest {
    private static float[] run(OversamplingEngine engine, float[] input, int block, int ch) {
        float[] output=new float[input.length];
        for(int offset=0;offset<input.length/ch;offset+=block) {
            int count=Math.min(block,input.length/ch-offset);
            float[] source=Arrays.copyOfRange(input,offset*ch,(offset+count)*ch), result=new float[count*ch];
            engine.downsample(engine.upsample(source,count),count,result);
            System.arraycopy(result,0,output,offset*ch,result.length);
        }
        return output;
    }
    @Test void tinyAndOddBlocksPreservePhaseStateLatencyAndStereoIsolation() {
        float[] input=new float[1031*2];
        Random random=new Random(8091);
        for(int i=0;i<input.length;i+=2) input[i]=random.nextFloat()-.5f;
        for(var quality:OversamplingEngine.Quality.values()) for(int factor:new int[]{1,2,4,8,16,32,64}) {
            var whole=new OversamplingEngine(); whole.prepare(factor,2,2048,quality);
            float[] expected=run(whole,input,2048,2);
            for(int block:new int[]{1,17,64}) {
                var split=new OversamplingEngine(); split.prepare(factor,2,block,quality);
                assertEquals(whole.getLatencyBaseFrames(),split.getLatencyBaseFrames());
                assertEquals(whole.getUpsampleLatencyHiFrames(),split.getUpsampleLatencyHiFrames());
                assertArrayEquals(expected,run(split,input,block,2),0f);
                split.reset(); assertArrayEquals(expected,run(split,input,block,2),0f);
            }
        }
    }
}
