package com.dspark.analysis;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TruePeakTailTest {
    @Test void finiteFileMeasureIncludesTheSameTailAsAnExplicitlyPaddedStream() {
        Random random=new Random(3901); int demonstrablyMissed=0;
        for(int channels:new int[]{1,2}) for(int frames:new int[]{1,2,5,11,29})
            for(int trial=0;trial<20;trial++) {
                float[] input=new float[frames*channels];
                for(int i=0;i<input.length;i++) input[i]=random.nextFloat()*2-1;
                TruePeak[] detector=new TruePeak[channels]; Arrays.setAll(detector,i->new TruePeak());
                double reference=0,unpadded=0;
                for(int f=0;f<frames+64;f++) for(int c=0;c<channels;c++) {
                    double value=detector[c].process(f<frames?input[f*channels+c]:0);
                    reference=Math.max(reference,value);
                    if(f<frames) unpadded=Math.max(unpadded,value);
                }
                if(reference>unpadded+1e-5) demonstrablyMissed++;
                assertEquals(reference,TruePeak.measureMax(input,channels),0);
            }
        assertTrue(demonstrablyMissed>20,"Fixture must expose a missing tail, not merely pass both implementations");
    }
}
