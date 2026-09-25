package com.quickmaster.processing.dynamics.leveler;

import java.nio.file.*;
import java.util.*;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Root-reserved functional long PCM adversary. Not a resource/performance benchmark. */
public final class MusicalPcmPersistentProbe
{
    static final int CYCLES=100, PERIOD_SECONDS=64, SECONDS=CYCLES*PERIOD_SECONDS;
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("rate channels NEW-output-path; root reservation required before launch");
        int rate=Integer.parseInt(args[0]),channels=Integer.parseInt(args[1]);Path out=Path.of(args[2]);
        if(Files.exists(out))throw new IllegalArgumentException("Existing evidence cannot be overwritten");
        Files.createDirectories(out);long frames=(long)SECONDS*rate,hop=Math.round(.5*rate),n=(frames+hop-1)/hop;
        MusicalPcmMatrixTest.write(out.resolve("manifest-before-observation.json"),MusicalPcmMatrixTest.m("key","A06_DENSE_NOVELTY","variant","persistent-cosine-crossfade-100x64s",
                "expected","More than 64 regions after P99 implies TOO_MANY_SEGMENTS and empty layout/null engine, no truncation. The fixture must genuinely reach >=64 selected P99 boundaries; otherwise report fixture detectability FAIL, not fallback PASS.",
                "seed",Long.toUnsignedString(MusicalPcmFixture.SEED),"rate",rate,"channels",channels,"seconds",SECONDS,"frames",frames,"structuralHops",n,
                "pcmBytes",frames*channels*4L,"synthesis","Two existing immutable multitraits musical cells, families 0 and 1, nominal -20 dB RMS; convex cosine crossfade w=(1-cos(2*pi*(hopIndex*.5)/64))/2 over 100 cycles. Weight held per musical cell, no detector labels or markers.",
                "analyticalOracle","N=12800; Q length=12799; strict upper one percent contains at most 127 entries. >=64 distinct separated local maxima is possible but not guaranteed by N. Independently enumerate all maxima/ties at initial, P97.5 and P99 with no cap; require persistent condition before crediting fallback.",
                "resourcePlan","Largest 48k stereo PCM is 2,457,600,000 bytes. Fixed Xmx4096m, one JVM only, timeout 240s. Heap cap is a reservation, not a measured or accepted bound; no throughput/RSS extrapolation."));
        float[] first=cell(rate,channels,0),second=cell(rate,channels,1);int h=first.length/channels;
        float[] pcm=new float[Math.toIntExact(frames*channels)];
        for(long cell=0;cell<n;cell++) {
            double weight=(1-StrictMath.cos(2*StrictMath.PI*(cell*.5)/PERIOD_SECONDS))/2;
            long start=cell*h;for(int j=0;j<h&&start+j<frames;j++)for(int channel=0;channel<channels;channel++)
                pcm[Math.toIntExact((start+j)*channels+channel)]=(float)((1-weight)*first[j*channels+channel]+weight*second[j*channels+channel]);
        }
        MusicalPcmMatrixTest.write(out.resolve("pcm-hash-before-engine.json"),MusicalPcmMatrixTest.m("sha256",MusicalPcmMatrixTest.hashPcm(pcm),"sampleCount",pcm.length));
        var format=new AudioFormat(rate,channels,frames);String baseline=null;List<String> failures=new ArrayList<>();
        for(int repeat=1;repeat<=3;repeat++) {
            long start=System.nanoTime();var engine=new LevelerAnalysisEngine().analyzeShadow(pcm,format,new CancellationToken());
            double elapsed=(System.nanoTime()-start)/1e9;
            var loudness=engine==null?new LoudnessAnalyzer().analyze(pcm,format,new CancellationToken()):engine.cache().loudness();
            var features=engine==null?new StructuralFeatureExtractor().extract(pcm,format,loudness,new CancellationToken()):engine.cache().features();
            double[] q=BoundaryDetector.originalNovelty(features,frames,LevelerCalibrationProfile.V1);double[] sorted=q.clone();Arrays.sort(sorted);
            double median=p(sorted,.5);double[] deviations=Arrays.stream(q).map(v->Math.abs(v-median)).sorted().toArray();double mad=p(deviations,.5);
            List<Integer> initial=select(q,mad>0?median+3*mad:p(sorted,.95)),p975=select(q,p(sorted,.975)),p99=select(q,p(sorted,.99));
            var layout=new BoundaryDetector().detect(features,frames,LevelerCalibrationProfile.V1);List<Object> ranges=new ArrayList<>();
            for(int i=0;i<layout.regions().size();i++){var r=layout.regions().get(i);ranges.add(List.of(r.startInclusive(),r.endExclusive()));}
            var observation=MusicalPcmMatrixTest.m("engine",engine==null?"null":engine.result().status().name(),"conformance",engine==null?"no snapshot":engine.diagnostics().standardValidation().state().name(),
                    "layout",layout.status().name(),"ranges",ranges,"initialCount",initial.size(),"p975Count",p975.size(),"p99Count",p99.size(),"p99Boundaries",p99,
                    "persistentConditionGenuinelyReached",p99.size()+1>64);
            String stable=MusicalPcmMatrixTest.JSON.toJson(observation);if(baseline==null)baseline=stable;else MusicalPcmMatrixTest.check(baseline.equals(stable),"bit-identical repeat "+repeat,failures);
            MusicalPcmMatrixTest.check(p99.size()+1>64,"persistent candidate precondition genuinely reached run "+repeat,failures);
            if(p99.size()+1>64)MusicalPcmMatrixTest.check(layout.status()==LayoutStatus.TOO_MANY_SEGMENTS&&layout.regions().size()==0&&engine==null,"empty no-truncation fallback run "+repeat,failures);
            MusicalPcmMatrixTest.write(out.resolve("run-"+repeat+".json"),MusicalPcmMatrixTest.m("repeat",repeat,"engineSecondsObserved",elapsed,"observed",observation));
            System.out.println("A06 persistent rate="+rate+" channels="+channels+" run="+repeat+" initial="+initial.size()+" p99="+p99.size()+" layout="+layout.status()+" secondsObserved="+elapsed);
        }
        MusicalPcmMatrixTest.write(out.resolve("result.json"),MusicalPcmMatrixTest.m("key","A06_DENSE_NOVELTY","variant","persistent-cosine-crossfade-100x64s","rate",rate,"channels",channels,
                "engineRuns",3,"status",failures.isEmpty()?"PROBED_PASS":"PROBED_FAIL","failures",failures,"benchmarkAcceptance",false));
        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
    }
    private static float[] cell(int rate,int channels,int family){var p=MusicalPcmFixture.p("cell",.5,family,-20);var c=MusicalPcmFixture.c("A06_CELL","unlabelled synthesis only",List.of(p),null,null,null,-1);return MusicalPcmFixture.generate(c,rate,channels).pcm();}
    private static double p(double[] sorted,double percentile){return sorted[Math.max(0,(int)Math.ceil(sorted.length*percentile)-1)];}
    private static List<Integer> select(double[] q,double threshold) {
        List<Integer> maxima=new ArrayList<>();for(int i=0;i<q.length;i++)if(q[i]>threshold&&q[i]>0) {
            boolean keep=true;for(int j=Math.max(0,i-4);j<=Math.min(q.length-1,i+4);j++)if(q[j]>q[i]||(Double.doubleToRawLongBits(q[j])==Double.doubleToRawLongBits(q[i])&&j<i))keep=false;
            if(keep)maxima.add(i+1);
        }
        maxima.sort(Comparator.<Integer>comparingDouble(i->-q[i-1]).thenComparingInt(i->i));List<Integer> selected=new ArrayList<>();
        for(int i:maxima)if(selected.stream().noneMatch(j->Math.abs(i-j)<4))selected.add(i);Collections.sort(selected);return selected;
    }
}
