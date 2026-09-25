package com.quickmaster.processing.dynamics.leveler;
import com.quickmaster.processing.dynamics.leveler.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TruePeakSafetyTest {
    @Test void noHeadroomReducesOnlyBoostAndKeepsFloatOutputUnderCeiling() {
        int rate=1000,frames=24000;float[] pcm=new float[frames];
        for(int i=0;i<frames;i++)pcm[i]=(float)(.9*StrictMath.sin(2*StrictMath.PI*.21*i));
        var fmt=new AudioFormat(rate,1,frames);var plan=new RampAllocator().allocate(fmt,
                new RampRegion[]{new RampRegion(0,12000,"boost",false,3),new RampRegion(12000,24000,"cut",false,-3)},new ControlState(1,1));
        var result=new TruePeakSafety().constrain(pcm,fmt,plan,new CancellationToken());
        assertTrue(result.proof().proven());assertTrue(result.proof().boostScale()<1);assertTrue(result.proof().candidateTp()<=result.proof().ceilingLinear());
        assertEquals(-3,result.schedule().gainDbAt(18000),1e-12);assertTrue(result.proof().candidatePasses()<=24);
        var check=new FiniteTruePeakStream(1);float[] rendered=pcm.clone();
        for(int i=0;i<frames;i++){double db=result.schedule().gainDbAt(i);if(db!=0)rendered[i]=(float)(rendered[i]*StrictMath.exp(db*StrictMath.log(10)/20));}
        check.accept(rendered,0,frames);assertEquals(check.finish(),result.proof().candidateTp(),0);
    }
    @Test void protectedImpossibleInputIsDiagnosedAndNotClaimedSafe() {
        float[] pcm={1.2f,-1.2f};var fmt=new AudioFormat(48000,1,2);
        var plan=new RampAllocator().allocate(fmt,new RampRegion[]{new RampRegion(0,2,"protected",true,0)},new ControlState(1,.5));
        var proof=new TruePeakSafety().constrain(pcm,fmt,plan,new CancellationToken()).proof();
        assertFalse(proof.proven());assertEquals(SafetyStatus.INFEASIBLE_INPUT_BASELINE,proof.status());
    }
    @Test void protectedEofIntersampleOvershootIsInfeasibleEvenWithAnUnprotectedRegion() {
        float[] pcm=new float[32];pcm[30]=-.7042242288589478f*1.4f;pcm[31]=-.41123709082603455f*1.4f;
        for(float sample:pcm)assertTrue(StrictMath.abs(sample)<1);
        var fmt=new AudioFormat(48000,1,32);
        var plan=new RampAllocator().allocate(fmt,new RampRegion[]{new RampRegion(0,16,"modifiable",false,3),new RampRegion(16,32,"protected",true,0)},new ControlState(1,1));
        var proof=new TruePeakSafety().constrain(pcm,fmt,plan,new CancellationToken()).proof();
        assertTrue(proof.inputTp()>1);assertFalse(proof.proven());assertEquals(SafetyStatus.INFEASIBLE_INPUT_BASELINE,proof.status());
    }
    @Test void unitStillMeasuresEofAndCancellationDoesNotPublishProof() {
        float[] pcm={-.7042242288589478f,-.41123709082603455f};var fmt=new AudioFormat(48000,1,2);
        var plan=new RampAllocator().allocate(fmt,new RampRegion[]{new RampRegion(0,2,"protected",true,0)},new ControlState(0,.5));
        var proof=new TruePeakSafety().constrain(pcm,fmt,plan,new CancellationToken()).proof();
        assertTrue(proof.proven());assertEquals(.7410990583266539,proof.inputTp(),1e-12);assertEquals(.7496805809746013,proof.ceilingLinear(),1e-12);
        var token=new CancellationToken();token.cancel();assertEquals(SafetyStatus.CANCELLED,new TruePeakSafety().constrain(pcm,fmt,plan,token).proof().status());
        assertFalse(new TruePeakSafety().constrain(new float[]{Float.NaN,0},fmt,plan,new CancellationToken()).proof().proven());
    }
}
