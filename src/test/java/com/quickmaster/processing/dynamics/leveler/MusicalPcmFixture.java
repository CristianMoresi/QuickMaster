package com.quickmaster.processing.dynamics.leveler;

import java.util.*;
import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;

/** Own synthetic musical corpus. Truth is declared here, never supplied to the engine. */
final class MusicalPcmFixture
{
    static final long SEED = 0x4D30303450434D04L;
    record Part(String label, double seconds, int family, double rmsDb, double endDb,
                double performance, double duty, int transpose, double pan, int melody) { }
    record Case(String key, String variant, String expected, List<Part> parts,
                String first, String second, String third, int protectionBit, double attackOffset,
                boolean antiphase, boolean persistent) { }
    record Truth(String label, long start, long end, Part synthesis) { }
    record Clip(Case testCase, AudioFormat format, float[] pcm, List<Truth> truth) { }

    static Part p(String label, double seconds, int family, double db) {
        return new Part(label, seconds, family, db, db, 0, 1, 0, .15, 0);
    }
    static Part performance(Part p, double variation, double pan) {
        return new Part(p.label, p.seconds, p.family, p.rmsDb, p.endDb, variation, p.duty, p.transpose, pan, p.melody);
    }
    static Part ramp(Part p, double end) {
        return new Part(p.label, p.seconds, p.family, p.rmsDb, end, p.performance, p.duty, p.transpose, p.pan, p.melody);
    }
    static Part duty(Part p, double duty) {
        return new Part(p.label, p.seconds, p.family, p.rmsDb, p.endDb, p.performance, duty, p.transpose, p.pan, p.melody);
    }
    static List<Part> pair(Part a, Part b) {
        return List.of(p("I", 20, 3, -42), a, p("X", 20, 1, -42), b, p("O", 20, 3, -42));
    }
    static Case c(String key, String expected, List<Part> parts, String a, String b, String d, int bit) {
        return new Case(key, "base", expected, parts, a, b, d, bit, 0, false, false);
    }
    static List<Case> catalog() {
        List<Case> cases = new ArrayList<>();
        cases.add(c("P01_ABA_LEVEL", "Two distinct interior performances: R>=.02; comparable pair; lower section boosted, higher cut by normative pair reference, not rendered audio.",
                List.of(p("I",20,3,-20),p("V1",24,1,-20),p("C1",24,0,-18),p("V2",24,1,-24),
                        performance(p("C2",24,0,-22),.09,.18),p("O",20,3,-28)),"C1","C2",null,-1));
        List<Part> repeats = List.of(p("I",20,3,-42),p("R1",24,0,-18),p("X",20,1,-42),
                performance(p("R2",24,0,-24),.08,.18),p("Y",20,1,-42),
                performance(p("R3",24,0,-18.25),.14,.12),p("O",20,3,-42));
        cases.add(c("P02_REPEAT_OUTLIER", "Three interior performances form one group; R2 ~6 dB low; capped weights sum one; weighted median reference; only R2 nonzero boost.",repeats,"R1","R2","R3",-1));
        cases.add(c("P03_STEREO_LINKED", "Same three-repeat decision over jointly analyzed channels; one descriptor/target per region. Pan differs realistically. No post-render claim.",
                List.of(p("I",20,3,-42),performance(p("R1",24,0,-18),0,-.18),p("X",20,1,-42),
                        performance(p("R2",24,0,-24),.08,.28),p("Y",20,1,-42),
                        performance(p("R3",24,0,-18.25),.14,-.12),p("O",20,3,-42)),"R1","R2","R3",-1));
        cases.add(c("N01_LONG_INTRO", "Unique twelve-second quiet intro: INTRO_EDGE and exact positive-zero target.",List.of(p("subject",12,3,-24),p("B",24,0,-18),p("O",20,1,-18)),"subject",null,null,1));
        cases.add(c("N02_LONG_BREAK", "Twelve-second same-key break >=3 dB quieter and at least 35% less active than neighbors: BREAK_OR_BREAKDOWN and +0.",
                List.of(p("I",20,3,-18),p("B1",24,0,-18),duty(p("subject",12,0,-24),.2),p("B2",24,0,-18),p("O",20,1,-18)),"subject",null,null,4));
        cases.add(c("N03_SHORT_BREAK", "Half-second break cannot acquire an intervention; all overlapping descriptors target +0, independently of whether it becomes a region.",
                List.of(p("I",20,3,-18),p("B1",24,0,-18),duty(p("subject",.5,0,-30),0),p("B2",24,0,-18),p("O",20,1,-18)),"subject",null,null,-1));
        cases.add(c("N04_OUTRO_FADE", "Repeated music fading 9 dB over eighteen seconds: FADE_OR_CRESCENDO plus OUTRO_EDGE; +0.",List.of(p("I",20,3,-18),p("B",24,0,-18),ramp(p("subject",18,0,-18),-27)),"subject",null,null,3));
        cases.add(c("N05_CRESCENDO", "Interior sixteen-second crescendo +8 dB: FADE_OR_CRESCENDO and +0.",List.of(p("I",20,3,-26),ramp(p("subject",16,0,-26),-18),p("O",20,1,-18)),"subject",null,null,3));
        cases.add(c("N06_UNIQUE_BRIDGE", "Unique twenty-four-second bridge five dB lower: no comparable reference and +0.",List.of(p("I",20,3,-18),p("B1",24,0,-18),p("subject",24,2,-23),p("B2",24,0,-18),p("O",20,1,-18)),"subject",null,null,-1));
        cases.add(c("N07_SILENCE_SPARSE", "Twelve-second sparse breaths/reverb separated by silence: SILENCE_OR_SPARSE; finite values and +0.",List.of(p("I",20,3,-18),p("B",24,0,-18),duty(p("subject",12,5,-48),.05),p("O",20,1,-18)),"subject",null,null,0));
        cases.add(c("N08_GAIN_SCALED_INTENT", "Exact repeated multitraits PCM with only -6 dB gain: high content similarity, R<=1e-4 and Dctx<=.05; INTENT_UNIDENTIFIABLE; +0 for both.",pair(p("A",24,0,-18),p("B",24,0,-24)),"A","B",null,-1));
        cases.add(c("N09_FLAT_INTRO_REPEAT", "A repeated but flat first region remains INTRO_EDGE with +0 despite its level gap.",List.of(p("subject",24,0,-22),p("X",20,1,-20),performance(p("B",24,0,-18),.09,.18),p("O",20,3,-22)),"subject","B",null,1));
        cases.add(c("N10_FLAT_OUTRO_REPEAT", "A repeated but flat last region remains OUTRO_EDGE with +0 despite its level gap.",List.of(p("I",20,3,-22),performance(p("B",24,0,-18),.09,.18),p("X",20,1,-20),p("subject",24,0,-22)),"subject","B",null,2));
        cases.add(c("N11_REPEAT_IN_BUILDUP", "Same musical phrase in a four-interval rising neighborhood totaling six dB: MACRO_BUILDUP prevents correction of the central repeat.",
                List.of(p("I",20,3,-25),p("R1",20,0,-23.5),p("subject",20,1,-22),p("R2",20,0,-20.5),p("O",20,3,-19)),"subject",null,null,7));
        Part shared = new Part("B",24,0,-22,-22,.08,1,0,.18,1);
        cases.add(c("A01_SHARED_ACCOMP", "Shared bass/chord accompaniment but substantially different lead arrangement must not create a corrective reference.",pair(p("A",24,0,-18),shared),"A","B",null,-1));
        cases.add(c("A02_SAME_CHROMA_TIMBRE", "Same pitch classes with radically brighter instrumentation: timbre gate or higher-priority protection excludes correction; actual T recorded independently.",pair(p("A",24,0,-18),p("B",24,4,-22)),"A","B",null,-1));
        Part transposed = new Part("B",24,0,-22,-22,.08,1,2,.18,0);
        cases.add(c("A03_TRANSPOSED_REPEAT", "A genuine two-semitone transpose must choose rotation two (>=80% bin support) with visible .05 harmonic penalty; correction remains conditional on pair C gate.",pair(p("A",24,0,-18),transposed),"A","B",null,-1));
        for (int offset=-1; offset<=1; offset++) cases.add(new Case("A04_BOUNDARY_JITTER", "attack"+offset,
                "Real PCM attack at the boundary +/-0.5s: compare central plus eight re-extracted endpoint variants to independent bin-area/DTW oracle; instability must veto grouping.",
                pair(p("A",24,0,-18),performance(p("B",24,0,-22),.09,.18)),"A","B",null,-1,offset*.5,false,false));
        List<Part> dense = new ArrayList<>();
        for(int i=0;i<90;i++) dense.add(p("D"+i,4,i%3,-20));
        cases.add(c("A06_DENSE_NOVELTY", "Bounded retry subcase: >64 initial candidate regions must be retried, with exact whole-track partition or empty fallback, never a truncated prefix. Does not close persistent P99 subcase.",dense,null,null,null,-1));
        cases.add(new Case("A07_ANTIPHASE_RATE","partial-antiphase","All source rates including 96k: summed channel power survives partial antiphase; finite features; one common target per region, not an L+R amplitude fold-down.",
                pair(p("A",24,0,-18),performance(p("B",24,0,-22),.09,.18)),"A","B",null,-1,0,true,false));
        for(double duration:new double[]{0, -1, .1,2.99}) cases.add(new Case("A08_SHORT_EMPTY",duration==-1?"one-frame":Double.toString(duration),
                "Empty, one-frame, 0.1s, and 2.99s inputs: no crash; absent regional loudness/no corrective target; empty input may return null.",
                List.of(p("short",duration,0,-18)),"short",null,null,-1,0,false,false));
        return List.copyOf(cases);
    }

    static Clip generate(Case test, int rate, int channels) {
        long frames=0; List<Truth> truth=new ArrayList<>();
        for(Part part:test.parts) {
            long length=part.seconds<0?1:Math.round(part.seconds*rate);
            truth.add(new Truth(part.label,frames,frames+length,part)); frames+=length;
        }
        AudioFormat format=new AudioFormat(rate,channels,frames);
        float[] pcm=new float[Math.toIntExact(frames*channels)];
        Map<Part,float[]> tiles=new HashMap<>();
        for(Truth region:truth) {
            Part part=region.synthesis; float[] tile=tiles.computeIfAbsent(part,p->tile(p,rate,channels,test.antiphase));
            int tileFrames=tile.length/channels;
            for(long f=region.start;f<region.end;f++) {
                long local=f-region.start; double t=local/(double)rate;
                double db=part.rmsDb+(part.endDb-part.rmsDb)*local/Math.max(1d,region.end-region.start-1);
                double gain=StrictMath.pow(10,db/20);
                // Sparse musical phrases use 2.5-second breaths; normal loops sustain continuously.
                double gate=part.duty>=1?1:((t%2.5)<2.5*part.duty?1:0);
                for(int c=0;c<channels;c++) pcm[Math.toIntExact(f*channels+c)]=(float)(tile[(int)(local%tileFrames)*channels+c]*gain*gate);
            }
            if(test.key.equals("A04_BOUNDARY_JITTER") && (part.label.equals("A")||part.label.equals("B"))) {
                long attack=region.start+Math.round(test.attackOffset*rate);
                for(int j=0;j<Math.round(.12*rate);j++) {
                    long f=attack+j; if(f<0||f>=frames)continue;
                    double burst=.08*StrictMath.exp(-j/(.025*rate))*StrictMath.sin(2*StrictMath.PI*75*j/rate);
                    for(int c=0;c<channels;c++)pcm[Math.toIntExact(f*channels+c)]+=(float)burst;
                }
            }
        }
        return new Clip(test,format,pcm,List.copyOf(truth));
    }

    private static float[] tile(Part p,int rate,int channels,boolean antiphase) {
        int frames=Math.round(rate*.5f); float[] tile=new float[frames*channels];
        long rng=SEED ^ (p.family==4?0:p.family)*0x9E3779B97F4A7C15L;
        double root=switch(p.family){case 1->185.0;case 2->146.832;case 3->164.814;default->130.813;};
        root*=StrictMath.pow(2,p.transpose/12d);
        double sum=0;
        for(int f=0;f<frames;f++) {
            double t=f/(double)rate, beat=t% .25, note=t% .125;
            rng^=rng<<13;rng^=rng>>>7;rng^=rng<<17;
            double noise=((rng>>>11)*0x1.0p-53)*2-1;
            double bass=.50*StrictMath.sin(2*StrictMath.PI*root*.5*t)+.10*StrictMath.sin(2*StrictMath.PI*root*t);
            double chord=0;
            int[] pitches=p.family==2||p.family==3?new int[]{0,3,7}:new int[]{0,4,7};
            for(int pitch:pitches) {
                double hz=root*StrictMath.pow(2,pitch/12d);
                chord+=.19*StrictMath.sin(2*StrictMath.PI*hz*t+p.performance);
                chord+=(p.family==4?.25:.035)*StrictMath.sin(2*StrictMath.PI*hz*8*t+p.performance*2);
            }
            int[] melody=p.melody==0?new int[]{12,16,19,16}:new int[]{23,13,18,14};
            double leadHz=root*StrictMath.pow(2,melody[Math.min(3,(int)(t/.125))]/12d);
            double lead=(p.melody==0?.24:.65)*StrictMath.exp(-note/ .11)*
                    (StrictMath.sin(2*StrictMath.PI*leadHz*t+p.performance*3)+.18*StrictMath.sin(2*StrictMath.PI*3*leadHz*t));
            double kick=.45*StrictMath.exp(-beat/.035)*StrictMath.sin(2*StrictMath.PI*(52*beat+24*.025*(1-StrictMath.exp(-beat/.025))));
            double snare=.08*noise*StrictMath.exp(-((t+.125)%.25)/.025);
            double air=(p.family==4?.12:.012)*noise + .018*StrictMath.sin(2*StrictMath.PI*root*2*(t-.031))*StrictMath.exp(-note/.12);
            double soften=Math.min(1,Math.min(t/.003,(.5-t)/.003));
            double mid=(bass+chord+lead+kick+snare+air)*soften;
            double side=(.32*chord+.24*lead+.3*air)*soften;
            for(int c=0;c<channels;c++) {
                double value=channels==1?mid:(c==0?mid*(1-p.pan)+side:(antiphase?-.8:1)*mid*(1+p.pan)-side);
                tile[f*channels+c]=(float)value;sum+=value*value;
            }
        }
        double scale=1/StrictMath.sqrt(sum/tile.length);
        for(int i=0;i<tile.length;i++)tile[i]=(float)(tile[i]*scale);
        return tile;
    }
}
