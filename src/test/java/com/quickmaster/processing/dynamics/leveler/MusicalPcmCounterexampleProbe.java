package com.quickmaster.processing.dynamics.leveler;

import java.nio.file.*;
import java.util.*;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Extra causal observation of the unchanged failed PCM; never changes truth or calibration. */
public final class MusicalPcmCounterexampleProbe
{
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("New output required");Files.createDirectories(out);
        Set<String> keys=Set.of("P01_ABA_LEVEL","P02_REPEAT_OUTLIER","P03_STEREO_LINKED","N04_OUTRO_FADE","N08_GAIN_SCALED_INTENT","A03_TRANSPOSED_REPEAT");
        List<Object> results=new ArrayList<>();
        for(var test:MusicalPcmFixture.catalog())if(keys.contains(test.key()))for(int rate:new int[]{44100,48000}) {
            var clip=MusicalPcmFixture.generate(test,rate,2);var snapshot=new LevelerAnalysisEngine().analyzeShadow(clip.pcm(),clip.format(),new CancellationToken());
            if(snapshot==null){results.add(MusicalPcmMatrixTest.m("key",test.key(),"rate",rate,"engine","null"));continue;}
            var cache=snapshot.cache();double[] q=BoundaryDetector.originalNovelty(cache.features(),clip.format().frames(),LevelerCalibrationProfile.V1);
            double[] sorted=q.clone();Arrays.sort(sorted);double median=percentile(sorted,.5);double[] dev=Arrays.stream(q).map(v->Math.abs(v-median)).sorted().toArray();
            double mad=percentile(dev,.5),threshold=mad>0?median+3*mad:percentile(sorted,.95);
            List<Object> boundaries=new ArrayList<>();
            for(int truth=1;truth<clip.truth().size();truth++) {
                var part=clip.truth().get(truth);long frame=part.start();int index=(int)(frame/cache.features().hopFrames())-1;
                int peak=Math.max(0,Math.min(q.length-1,index));for(int i=Math.max(0,index-4);i<=Math.min(q.length-1,index+4);i++)if(q[i]>q[peak])peak=i;
                boolean exists=false;for(int i=1;i<cache.layout().regions().size();i++)if(Math.abs(cache.layout().regions().get(i).startInclusive()-frame)<=2L*rate)exists=true;
                boundaries.add(MusicalPcmMatrixTest.m("beforeTruthLabel",part.label(),"truthFrame",frame,"QatTruth",index>=0&&index<q.length?q[index]:null,
                        "strongestQwithin2s",q[peak],"strongestQFrame",(peak+1L)*cache.features().hopFrames(),"strictlyAboveInitialThreshold",q[peak]>threshold,"selectedWithin2s",exists));
            }
            List<Object> regions=new ArrayList<>();for(int i=0;i<cache.descriptors().size();i++){var d=cache.descriptors().get(i);var t=cache.referencePlan().targets().get(i);
                regions.add(MusicalPcmMatrixTest.m("id",i,"rangeFrames",List.of(d.range().startInclusive(),d.range().endExclusive()),"reasonBits",cache.protections().get(i).flags().reasonBits(),
                        "rawDb",t.rawDb(),"weightedDb",t.confidenceWeightedDb(),"referenceReason",t.reason().name()));}
            results.add(MusicalPcmMatrixTest.m("key",test.key(),"rate",rate,"channels",2,"pcmSha256",MusicalPcmMatrixTest.hashPcm(clip.pcm()),"threshold",threshold,"median",median,"mad",mad,
                    "trueBoundaries",boundaries,"regions",regions,"conformance",snapshot.diagnostics().standardValidation().state().name(),"scope","causal observation only; no altered PCM, gates or fabricated PASSED"));
        }
        MusicalPcmMatrixTest.write(out.resolve("boundary-causes.json"),results);
    }
    private static double percentile(double[] a,double p){return a[Math.max(0,(int)Math.ceil(a.length*p)-1)];}
}
