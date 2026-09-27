package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.FeatureTimeline;
import com.quickmaster.processing.dynamics.leveler.model.ComparisonTimeline;

/** Locates deterministic discriminating PCM for the exact Hann mutation oracles. */
public final class HannMutationProbe {
    public static void main(String[] args) throws Exception {
        String source = HannMutant.source("StructuralFeatureExtractor");
        String changed = HannMutant.replaceOnce(source,"double[] hannWindow = new double[fftSize];","float[] hannWindow = new float[fftSize];");
        changed = HannMutant.replaceOnce(changed,"hannWindow[n] = 0.5d - 0.5d * StrictMath.cos(","hannWindow[n] = (float) (0.5d - 0.5d * StrictMath.cos(");
        changed = HannMutant.replaceOnce(changed,"2.0d * StrictMath.PI * n / (fftSize - 1.0d));","2.0d * StrictMath.PI * n / (fftSize - 1.0d)));");
        try(var mutant = HannMutant.compile("StructuralFeatureExtractor","ProbeHannFloatStructural",changed)) {
            boolean killed = false;
            for(int rate : new int[]{8000,44100,48000,96000,192000})
                for(int ch : new int[]{1,2}) for(int signal=0;signal<3;signal++) {
                    var f = HannBitIdentityTest.fixture(rate,ch,rate*2+7,signal);
                    var expected = new HannBaselineStructuralExtractor().extract(f.pcm(),f.format(),f.loudness(),null);
                    var actual = (FeatureTimeline)mutant.extract(f.pcm(),f.format(),f.loudness(),null);
                    try { HannBitIdentityTest.assertStructural(expected,actual,"rate="+rate+" ch="+ch+" signal="+signal); }
                    catch(AssertionError found) { System.out.println("STRUCTURAL_DISCRIMINATOR "+found.getMessage()); killed=true; }
                }
            if(!killed) throw new AssertionError("No fixture kills structural float mutation");
        }
        String comparison=HannMutant.source("ComparisonFeatureExtractor");
        comparison=HannMutant.replaceOnce(comparison,"double[] hannWindow = new double[support];","float[] hannWindow = new float[support];");
        comparison=HannMutant.replaceOnce(comparison,"hannWindow[n] = hann;","hannWindow[n] = (float) hann;");
        try(var mutant = HannMutant.compile("ComparisonFeatureExtractor","ProbeHannFloatComparison",comparison)) {
            for(int rate : new int[]{8000,44100,48000,96000})
                for(int ch : new int[]{1,2}) for(int signal=0;signal<3;signal++) {
                    var f=HannBitIdentityTest.fixture(rate,ch,rate*3+7,signal);
                    var expected=new HannBaselineComparisonExtractor().extract(f.pcm(),f.format(),null);
                    var actual=(ComparisonTimeline)mutant.extract(f.pcm(),f.format(),null);
                    try { HannBitIdentityTest.assertComparison(expected,actual,"rate="+rate+" ch="+ch+" signal="+signal); }
                    catch(AssertionError found) { System.out.println("COMPARISON_DISCRIMINATOR "+found.getMessage()); return; }
                }
            throw new AssertionError("No fixture kills comparison float mutation");
        }
    }
}
