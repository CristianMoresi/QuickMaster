package com.quickmaster.processing.dynamics;

import com.dspark.analysis.TruePeak;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

/** Independent PCM oracles: no detector-selected regions or reference levels. */
class MacroLevelerProcessorTest {
    private static final int RATE = 8000;
    private static float[] song(int channels) {
        float[] pcm = new float[36 * RATE * channels];
        double[] amplitudes = {.04, .16, .08};
        double[] frequencies = {173, 511, 997};
        for (int f = 0; f < pcm.length / channels; f++) {
            int part = f / (12 * RATE);
            float value = (float)(amplitudes[part] * Math.sin(2 * Math.PI * frequencies[part] * f / RATE));
            for (int c = 0; c < channels; c++) pcm[f * channels + c] = c == 0 ? value : -value;
        }
        return pcm;
    }
    private static MacroLevelerProcessor processor(float[] pcm, int channels, double amount, double speed) {
        var p = new MacroLevelerProcessor(); p.setEnabled(true); p.setLeveling(amount); p.setSpeed(speed);
        p.prepare(RATE, pcm.length); p.analyze(pcm, channels); return p;
    }
    private static double rms(float[] pcm, int channels, double start, double end) {
        int a = (int)(start * RATE) * channels, b = (int)(end * RATE) * channels;
        double power = 0;
        for (int i = a; i < b; i++) power += (double)pcm[i] * pcm[i];
        return 10 * Math.log10(power / (b - a));
    }
    @Test void equalizesUnrelatedSectionsIncludingFirstAndLast() {
        for (int ch : new int[]{1, 2}) {
            float[] in = song(ch), original = in.clone();
            var p = processor(in, ch, 1, .5);
            assertArrayEquals(original, in, "analysis must not write source");
            float[] out = p.process(in.clone(), ch);
            assertEquals(rms(out,ch,3,9), rms(out,ch,15,21), .3);
            assertEquals(rms(out,ch,3,9), rms(out,ch,27,33), .3);
            assertTrue(rms(out,ch,3,9) - rms(in,ch,3,9) > 3);
            assertTrue(p.getGainDbAtPosition(6L * RATE) > 3);
            assertTrue(p.isAnalyzed());
        }
    }
    @Test void amountScalesDifferencesInDecibelsAndZeroIsBitExact() {
        float[] in = song(1);
        for (double amount : new double[]{0, .25, .5, .75, 1}) {
            var p = processor(in,1,amount,.5); float[] out = p.process(in.clone(),1);
            if (amount == 0) assertArrayEquals(in,out);
            double before = rms(in,1,15,21) - rms(in,1,3,9);
            double after = rms(out,1,15,21) - rms(out,1,3,9);
            assertEquals(before * (1-amount), after, .3);
        }
    }
    @Test void speedChangesTransitionsNotPlateauCorrection() {
        float[] in = song(1);
        var slow = processor(in,1,1,0); var fast = processor(in,1,1,1);
        float[] a = slow.process(in.clone(),1), b = fast.process(in.clone(),1);
        assertEquals(rms(a,1,4,8), rms(b,1,4,8), .1);
        assertEquals(rms(a,1,16,20), rms(b,1,16,20), .1);
        assertNotEquals(slow.getGainDbAtPosition(12L*RATE-RATE/4),fast.getGainDbAtPosition(12L*RATE-RATE/4));
    }
    @Test void stereoImageAndMicrodynamicCrestArePreservedInStablePassages() {
        float[] in = song(2); var p = processor(in,2,1,.5); float[] out = p.process(in.clone(),2);
        for (int f = 0; f < out.length/2; f++) assertEquals(out[f*2],-out[f*2+1],0);
        double reference = out[4*RATE*2+2] / (double)in[4*RATE*2+2];
        for (int i=4*RATE*2;i<8*RATE*2;i++) if(Math.abs(in[i])>.001)
            assertEquals(reference,out[i]/(double)in[i],1e-4);
    }
    @Test void silenceAndNoiseAreNotPromotedToMusic() {
        float[] in = song(1);
        Arrays.fill(in,0,3*RATE,0);
        for(int i=33*RATE;i<in.length;i++)in[i]=(float)(1e-6*Math.sin(i));
        var p=processor(in,1,1,.5);float[] out=p.process(in.clone(),1);
        for(int i=0;i<2*RATE;i++)assertEquals(0,out[i],0);
        assertTrue(rms(out,1,35,36)<=rms(in,1,35,36)+.01);
    }
    @Test void peakSafetyUsesCommonAttenuationNotASelectiveBoostVeto() {
        float[] in=song(1);for(int i=0;i<in.length;i++)in[i]*=8;
        var p=processor(in,1,1,.5);float[] out=p.process(in.clone(),1);
        assertTrue(TruePeak.measureMax(out,1)<=1);
        assertEquals(rms(out,1,3,9),rms(out,1,15,21),.3);
        assertTrue(p.getAnalysisReport().headroomOffsetDb()<0);
    }
    @Test void fullRenderBlocksAndSeekAreIdentical() {
        float[] in=song(2);var p=processor(in,2,1,.5);float[] full=p.process(in.clone(),2);
        p.prepare(RATE,in.length);float[] blockOut=new float[in.length];
        for(int i=0;i<in.length;i+=614){int n=Math.min(614,in.length-i);float[] block=Arrays.copyOfRange(in,i,i+n);p.process(block,2);System.arraycopy(block,0,blockOut,i,n);}
        assertArrayEquals(full,blockOut);
        p.setPlaybackPosition(7L*RATE);float[] sought=Arrays.copyOfRange(in,14*RATE,16*RATE);p.process(sought,2);
        assertArrayEquals(Arrays.copyOfRange(full,14*RATE,16*RATE),sought);
    }
    @Test void cancellationAndInvalidInputCannotLeaveStaleGain() {
        float[] in=song(1);var p=processor(in,1,1,.5);CancellationToken token=new CancellationToken();token.cancel();
        assertThrows(CancellationException.class,()->p.analyze(in,1,token));assertFalse(p.isAnalyzed());
        p.analyze(new float[]{Float.NaN},1);assertEquals("INVALID_INPUT",p.getAnalysisDiagnostic());
        assertThrows(IllegalArgumentException.class,()->p.setLeveling(Double.NaN));
        assertThrows(IllegalArgumentException.class,()->p.setSpeed(Double.POSITIVE_INFINITY));
    }
    @Test void adoptionIsImmutableAndAChangedControlRequiresReanalysis() {
        float[] in=song(1);var source=processor(in,1,1,.5);var live=new MacroLevelerProcessor();live.setEnabled(true);live.prepare(RATE,in.length);live.adoptEnvelope(source);
        double g=live.getGainDbAtPosition(6L*RATE);source.setLeveling(.2);
        assertFalse(source.isAnalyzed());assertEquals(g,live.getGainDbAtPosition(6L*RATE),0);
        live.setSpeed(.1);assertFalse(live.isAnalyzed());
    }
    @Test void continuousCrescendoIsCorrectedWithoutRequiringBoundaries() {
        float[] in=new float[30*RATE];
        for(int f=0;f<in.length;f++)in[f]=(float)(.02*Math.pow(10,12*(f/(double)in.length)/20)*Math.sin(2*Math.PI*173*f/RATE));
        for(double amount:new double[]{.25,.5,1}) {
            var p=processor(in,1,amount,.5);float[] out=p.process(in.clone(),1);
            double original=rms(in,1,24,27)-rms(in,1,3,6);
            assertEquals(original*(1-amount),rms(out,1,24,27)-rms(out,1,3,6),.15);
        }
    }
    @Test void rhythmTransientAttackIsNotCompressed() {
        float[] in=new float[24*RATE];
        for(int f=0;f<in.length;f++) {
            double phase=(f%(RATE/2))/(double)RATE;
            double envelope=.008+.15*Math.exp(-phase*50);
            in[f]=(float)(envelope*Math.sin(2*Math.PI*220*f/RATE));
        }
        for(double speed:new double[]{0,.5,1}) {
            var p=processor(in,1,1,speed);
            for(int beat=6;beat<42;beat++) {
                long start=(long)beat*RATE/2;
                double attack=p.getGainDbAtPosition(start),tail=p.getGainDbAtPosition(start+RATE/25);
                assertEquals(attack,tail,.12,"40 ms transients must not drive a fast compressor envelope");
            }
        }
    }
    @Test void globalInputGainDoesNotChangeMusicalCorrectionWithoutHeadroomPressure() {
        float[] a=song(1),b=a.clone();for(int i=0;i<b.length;i++)b[i]*=.25f;
        var p=processor(a,1,.7,.5);var q=processor(b,1,.7,.5);
        for(int sec=2;sec<34;sec++)assertEquals(p.getGainDbAtPosition((long)sec*RATE),q.getGainDbAtPosition((long)sec*RATE),1e-6);
    }
    @Test void sourceClockRemainsCorrectAtOversampledRate() {
        float[] in=song(1);var p=processor(in,1,1,.5);
        for(int factor:new int[]{2,4,8}) {
            p.prepare(RATE*factor,(long)in.length*factor);
            p.setPlaybackPosition(5L*RATE*factor);float[] block=new float[1000];Arrays.fill(block,.1f);
            p.process(block,1);
            for(int f=0;f<block.length;f++) {
                double timeFrame=5.0*RATE+f/(double)factor;
                // Plateau oracle avoids needing the engine's interpolation formula.
                double expected=.1*Math.pow(10,p.getGainDbAtPosition((long)timeFrame)/20);
                assertEquals(expected,block[f],1e-6);
            }
        }
    }
    @Test void emptyVeryShortAndNoiseOnlyHaveFiniteHonestOutcomes() {
        var p=new MacroLevelerProcessor();p.setEnabled(true);p.prepare(RATE,1);
        p.analyze(new float[0],1);assertEquals("INVALID_INPUT",p.getAnalysisDiagnostic());
        p.analyze(new float[]{.1f},1);assertTrue(p.isAnalyzed());
        p.analyze(new float[100],1);assertEquals("NO_MUSICAL_ACTIVITY",p.getAnalysisDiagnostic());
        float[] noise=new float[RATE];Arrays.fill(noise,1e-6f);p.analyze(noise,1);
        assertEquals("NO_MUSICAL_ACTIVITY",p.getAnalysisDiagnostic());
        p.prepare(RATE,noise.length);assertArrayEquals(noise,p.process(noise.clone(),1));
    }
    @Test void truePeakProofIncludesFinalSampleAndFiniteExtremeInputs() {
        for(float scale:new float[]{1,100,Float.MAX_VALUE/2}) {
            float[] in=new float[3*RATE];
            for(int f=0;f<in.length;f++)in[f]=(float)(scale*Math.sin(2*Math.PI*.249*f+.7));
            var p=processor(in,1,1,.5);float[] out=p.process(in.clone(),1);
            for(float x:out)assertTrue(Float.isFinite(x));
            assertTrue(TruePeak.measureMax(out,1)<=1);
        }
    }
    @Test void resetBypassAndMismatchedLayoutDoNotApplyOldGain() {
        float[] in=song(1);var p=processor(in,1,1,.5);
        p.setEnabled(false);assertArrayEquals(in,p.process(in.clone(),1));
        p.setEnabled(true);p.prepare(RATE,in.length);assertArrayEquals(in,p.process(in.clone(),2));
        p.clearAnalysis();assertFalse(p.isAnalyzed());assertEquals(0,p.getGainDbAtPosition(3L*RATE));
        assertThrows(IllegalArgumentException.class,()->p.adoptEnvelope(new LevelerProcessor()));
    }
    @Test void retainedAutomationIsBoundedByTimeNotAudioFrames() {
        float[] in=song(2);var p=processor(in,2,1,.5);
        assertTrue(p.getAnalysisReport().controlPoints()<=36*10+2);
        assertNull(p.gainEnv,"Never allocate or retain the legacy per-sample envelope");
        var fork=p.forkForAnalysis(.7,.2);assertFalse(fork.isAnalyzed());
        assertNull(fork.getAnalysisReport());
    }
    @Test void supportedMusicSampleRatesShareTheSameMacroContract() {
        for(int rate:new int[]{44100,48000,96000,192000}) {
            float[] in=new float[18*rate];
            for(int f=0;f<in.length;f++)in[f]=(float)((f<6*rate?.03:f<12*rate?.12:.06)*Math.sin(2*Math.PI*997*f/rate));
            var p=new MacroLevelerProcessor();p.setEnabled(true);p.setLeveling(1);p.prepare(rate,in.length);p.analyze(in,1);
            assertEquals(p.getGainDbAtPosition(3L*rate)-p.getGainDbAtPosition(9L*rate),20*Math.log10(4),.1);
            assertEquals(p.getGainDbAtPosition(15L*rate)-p.getGainDbAtPosition(9L*rate),20*Math.log10(2),.1);
        }
    }
    @Test void alreadyLeveledToneIsNotGivenPeriodicModulation() {
        float[] in=new float[20*RATE];
        for(int f=0;f<in.length;f++)in[f]=(float)(.1*Math.sin(2*Math.PI*997*f/RATE));
        var p=processor(in,1,1,1);
        for(int f=RATE;f<in.length-RATE;f+=67)assertEquals(0,p.getGainDbAtPosition(f),.001);
        assertEquals("WITHIN_TOLERANCE",p.getAnalysisDiagnostic());
    }
    @Test void realisticResidualNoiseDoesNotAcquireGainAfterMusic() {
        float[] pcm=new float[40*RATE];var random=new java.util.Random(715391);
        for(int f=0;f<pcm.length;f++)pcm[f]=f<30*RATE
                ?(float)(.2*Math.sin(2*Math.PI*440*f/RATE)):(float)(.001*random.nextGaussian());
        var p=processor(pcm,1,1,.5);float[] out=p.process(pcm.clone(),1);
        assertTrue(rms(out,1,35,39)<=rms(pcm,1,35,39)+.1,
                "A -60 dB residual tail is not a musical section to raise");
    }
    @Test void finalPartialAnalysisBlockHasItsActualSampleWeight() {
        float[] pcm=new float[8*RATE+1];
        for(int f=0;f<pcm.length-1;f++)pcm[f]=(float)(.1*Math.sin(2*Math.PI*440*f/RATE));
        pcm[pcm.length-1]=.1f;
        var p=processor(pcm,1,1,.5);
        assertEquals(p.getGainDbAtPosition(6L*RATE),p.getGainDbAtPosition(pcm.length-1),.005,
                "A final single sample must not be assigned the power weight of a full 100 ms block");
    }
}
