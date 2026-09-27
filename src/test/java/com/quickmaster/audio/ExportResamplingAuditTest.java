package com.quickmaster.audio;

import com.quickmaster.ui.MainController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Protects the actual UI export path, including the packaged dependency choice. */
class ExportResamplingAuditTest {
    @Test void highRateExportPreserves20kAndRejectsAliasing() throws Exception {
        Method convert = MainController.class.getDeclaredMethod("resampleForExport", float[].class, int.class, int.class, int.class);
        convert.setAccessible(true);
        for (int from : new int[]{96000, 176400, 192000})
            for (int to : new int[]{44100, 48000})
                for (int hz : new int[]{1000, 18000, 20000, 28000, 35000}) {
                    float[] input = new float[from];
                    for (int f=0;f<from;f++) input[f]=(float)(.5*Math.sin(2*Math.PI*hz*f/from));
                    float[] original=input.clone();
                    float[] output=(float[])convert.invoke(null,input,1,from,to);
                    assertArrayEquals(original,input);
                    assertEquals(to,output.length);
                    double power=0; int first=to/4,last=3*to/4;
                    for(int f=first;f<last;f++) power+=(double)output[f]*output[f];
                    double db=20*Math.log10(Math.sqrt(2*power/(last-first))/.5);
                    assertTrue(hz<to/2 ? Math.abs(db)<.1 : db< -90, from+" -> "+to+" Hz="+hz+" gain="+db);
                }
    }
}
