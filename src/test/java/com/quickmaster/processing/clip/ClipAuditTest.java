package com.quickmaster.processing.clip;

import com.dspark.effects.Saturation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent PCM oracles, not the processor's transfer probe or meter. */
class ClipAuditTest {
    @Test void hardClipIsPointwiseAndDoesNotSlewUnclippedHighFrequencies() {
        float[] input = new float[4096];
        for (int i=0;i<input.length;i++) input[i]=(i%2==0)?.6f:-.6f;
        input[0]=1;
        HardClipProcessor p=hard(input, 1, 1, HardClipProcessor.Curve.HARD);
        float[] output=input.clone();p.process(output,1);
        double ceiling=Math.pow(10,-1.0/20);
        for(int i=0;i<input.length;i++)
            assertEquals(Math.max(-ceiling,Math.min(ceiling,input[i])),output[i],1e-7,"frame "+i);
    }

    @Test void softCurveApproachesBypassContinuously() {
        float[] input=new float[1024];java.util.Arrays.fill(input,.9f);
        HardClipProcessor p=hard(input,1,.001,HardClipProcessor.Curve.SOFT);
        float[] output=input.clone();p.process(output,1);
        assertEquals(-.001,20*Math.log10(output[100]/input[100]),.00001);
    }

    @Test void clippingIsInvariantToInputScale() {
        for(HardClipProcessor.Curve curve:HardClipProcessor.Curve.values()) {
            float[] input={.2f,.9f,.4f,-.9f,-.2f};
            float[] reference=input.clone();hard(input,1,3,curve).process(reference,1);
            for(double scale:new double[]{1e-8,1e-4,10,1000}) {
                float[] scaled=input.clone();for(int i=0;i<scaled.length;i++)scaled[i]*=(float)scale;
                HardClipProcessor p=hard(scaled,1,3,curve);p.process(scaled,1);
                for(int i=0;i<scaled.length;i++)assertEquals(reference[i],scaled[i]/scale,2e-7,
                        curve+" scale="+scale+" sample="+i);
            }
        }
    }

    @Test void softClipMustNotDecorrelateDualMonoWithoutAUserControl() {
        float[] input=new float[96000];
        for(int f=0;f<input.length/2;f++)input[2*f]=input[2*f+1]=(float)(.9*Math.sin(f*.07));
        SoftClipProcessor p=soft(input,2,3,Saturation.Algorithm.TUBE);
        p.process(input,2);
        for(int f=0;f<input.length/2;f++)assertEquals(input[2*f],input[2*f+1],"frame "+f);
    }

    @Test void aSoftClipMustNotHighPassAnUnclippedBassPassage() {
        float[] input=new float[48000];input[input.length-1]=1;
        for(int i=0;i<input.length-1;i++)input[i]=(float)(.1*Math.sin(2*Math.PI*20*i/48000));
        SoftClipProcessor p=soft(input,1,1,Saturation.Algorithm.TRANSFORMER);
        float[] output=input.clone();p.process(output,1);
        for(int i=0;i<input.length-1;i++)assertEquals(input[i],output[i],1e-7,"frame "+i);
    }

    @Test void invalidKnobValuesDoNotPoisonTheNextRender() {
        HardClipProcessor hard=new HardClipProcessor();hard.setClipDb(2);hard.setClipDb(Double.NaN);
        SoftClipProcessor soft=new SoftClipProcessor();soft.setSatDb(2);soft.setSatDb(Double.NaN);
        assertEquals(2,hard.getClipDb());assertEquals(2,soft.getSatDb());
    }

    @Test void softVoicingsHaveNoAsymmetricNearZeroJumpOrGeneratedDcOnSymmetricAudio() {
        for(Saturation.Algorithm algorithm:Saturation.Algorithm.values())for(double db:new double[]{.001,1,6,12}) {
            float[] source=new float[48000];
            for(int i=0;i<source.length;i++)source[i]=(float)(.9*Math.sin(2*Math.PI*100*i/48000));
            var p=soft(source,1,db,algorithm);float[] out=source.clone();p.process(out,1);
            double mean=0,maxDifference=0;
            for(int i=0;i<source.length;i++) {mean+=out[i];maxDifference=Math.max(maxDifference,Math.abs(out[i]-source[i]));}
            assertEquals(0,mean/source.length,1e-9,algorithm+" generated DC");
            if(db==.001)assertTrue(maxDifference<.00011,"near-zero discontinuity: "+algorithm+" "+maxDifference);
        }
    }

    static HardClipProcessor hard(float[] input,int channels,double db,HardClipProcessor.Curve curve) {
        HardClipProcessor p=new HardClipProcessor();p.setEnabled(true);p.setClipDb(db);p.setCurve(curve);
        p.prepare(48000,input.length);p.analyze(input,channels);return p;
    }
    static SoftClipProcessor soft(float[] input,int channels,double db,Saturation.Algorithm algorithm) {
        SoftClipProcessor p=new SoftClipProcessor();p.setEnabled(true);p.setSatDb(db);p.setAlgorithm(algorithm);
        p.prepare(48000,input.length);p.analyze(input,channels);return p;
    }
}
