package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickmaster.processing.dynamics.LevelerProcessor;
import com.quickmaster.processing.dynamics.AnalysisDynamicsProcessor;
import com.quickmaster.processing.dynamics.leveler.model.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Matrix over real PCM and the unmodified productive engine; records failures before asserting. */
class MusicalPcmMatrixTest
{
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final Path OUT=reserveOutput();
    static final String FILTER=System.getProperty("musical.pcm.filter","");
    static final int RATE=Integer.getInteger("musical.pcm.rate",0), CHANNELS=Integer.getInteger("musical.pcm.channels",0);
    static final List<Map<String,Object>> SUMMARY=new ArrayList<>();

    private static Path reserveOutput() {
        try {
            String configured=System.getProperty("musical.pcm.output");
            if(configured!=null&&!configured.isBlank()) {
                Path path=Path.of(configured).toAbsolutePath().normalize();
                if(!Files.exists(path))Files.createDirectory(path);
                if(!Files.isDirectory(path))throw new IllegalStateException("Explicit musical output is not a directory: "+path);
                try(Stream<Path> entries=Files.list(path)) {
                    if(entries.findAny().isPresent())throw new IllegalStateException("Explicit musical output must be empty: "+path);
                }
                return path;
            }
            Path parent=Path.of("target/musical-pcm").toAbsolutePath().normalize();
            Files.createDirectories(parent);
            return Files.createTempDirectory(parent,"run-");
        }catch(java.io.IOException ex){throw new ExceptionInInitializerError(ex);}
    }

    @TestFactory Stream<DynamicTest> realMusicalMatrix() {
        List<DynamicTest> tests=new ArrayList<>();
        for(var c:MusicalPcmFixture.catalog()) if(FILTER.isEmpty()||c.key().matches(FILTER)) {
            int[] rates=c.key().equals("A07_ANTIPHASE_RATE")?new int[]{44100,48000,96000}:new int[]{44100,48000};
            for(int rate:rates) for(int channels:new int[]{1,2}) if((RATE==0||RATE==rate)&&(CHANNELS==0||CHANNELS==channels)) {
                String name=c.key()+"_"+c.variant()+"_"+rate+"_"+channels;
                tests.add(DynamicTest.dynamicTest(name,()->audit(name,c,rate,channels)));
            }
        }
        return tests.stream();
    }

    static void audit(String name,MusicalPcmFixture.Case c,int rate,int channels) throws Exception {
        Path directory=OUT.resolve(name); Files.createDirectory(directory);
        if(c.key().equals("A08_SHORT_EMPTY")&&c.variant().equals("0.0")){auditEmpty(name,c,rate,channels,directory);return;}
        var clip=MusicalPcmFixture.generate(c,rate,channels);
        Map<String,Object> manifest=m("key",c.key(),"variant",c.variant(),"expected",c.expected(),"rate",rate,"channels",channels,
                "frames",clip.format().frames(),"seed",Long.toUnsignedString(MusicalPcmFixture.SEED),"pcmSha256",hashPcm(clip.pcm()),
                "truth",clip.truth(),"mappingRule","largest overlap with truth coverage >=0.8 and detected purity >=0.8, earliest tie; short-break uses all overlaps",
                "scope","M004 analytic only; official NOT_RUN is not overridden; no rendered gain/TP or benchmark acceptance");
        write(directory.resolve("manifest-before-observation.json"),manifest);
        List<String> failures=new ArrayList<>(); List<Double> elapsed=new ArrayList<>();
        String expectedBits=null; Map<String,Object> firstObservation=null;
        for(int repeat=1;repeat<=3;repeat++) {
            long start=System.nanoTime();
            ShadowAnalysisSnapshot snapshot=new LevelerAnalysisEngine().analyzeShadow(clip.pcm(),clip.format(),new CancellationToken());
            elapsed.add((System.nanoTime()-start)/1e9);
            Map<String,Object> observed=observe(snapshot,clip);
            String bits=JSON.toJson(observed);
            write(directory.resolve("run-"+repeat+".json"),m("repeat",repeat,"elapsedSeconds",elapsed.get(elapsed.size()-1),"observed",observed));
            if(repeat==1) {expectedBits=bits;firstObservation=observed;verify(c,clip,snapshot,observed,failures);}
            else check(expectedBits.equals(bits),"bit-identical decisions/descriptors/scores/targets repeat "+repeat,failures);
            snapshot=null;
        }
        if(c.key().equals("A07_ANTIPHASE_RATE")) verifyPower(clip,failures,directory);
        if(c.key().equals("P01_ABA_LEVEL")&&rate==48000&&channels==2) integration(clip,failures,directory);
        Map<String,Object> result=m("name",name,"key",c.key(),"expected",c.expected(),"status",failures.isEmpty()?"PROBED_PASS":"PROBED_FAIL",
                "observed",firstObservation,"engineRunCount",3,"elapsedSeconds",elapsed,"failures",failures);
        write(directory.resolve("result.json"),result); SUMMARY.add(m("name",name,"key",c.key(),"status",result.get("status"),"failures",failures,"elapsedSeconds",elapsed));
        write(OUT.resolve("matrix-progress.json"),SUMMARY);
        System.out.println(name+" "+result.get("status")+" "+failures.size()+" assertions failed; seconds="+elapsed);
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }

    static Map<String,Object> observe(ShadowAnalysisSnapshot s,MusicalPcmFixture.Clip clip) throws Exception {
        if(s==null) return m("engine","null");
        var cache=s.cache(); int comparisonVersion=ComparisonV2Oracle.version(cache);
        List<Object> regions=new ArrayList<>(),pairs=new ArrayList<>(),groups=new ArrayList<>(),groupPairs=new ArrayList<>();
        for(int i=0;i<cache.descriptors().size();i++) {
            var d=cache.descriptors().get(i);var target=cache.referencePlan().targets().get(i);List<String> context=new ArrayList<>();
            for(int j=0;j<6;j++)context.add(hex(d.context().componentAt(j)));
            regions.add(m("id",i,"start",d.range().startInclusive(),"end",d.range().endExclusive(),"loudness",d.regionalLoudness().present()?hex(d.regionalLoudness().lufs()):"absent",
                    "protectionBits",cache.protections().get(i).flags().reasonBits(),"body",new BodyContextGate().evaluate(i,cache.descriptors(),cache.protections()).rejectionReason().name(),
                    "loudnessSlope",hex(d.loudnessSlopeLuPerSec()),"loudnessDelta",hex(d.loudnessDeltaLu()),"consistency",hex(d.loudnessConsistency()),
                    "activitySlope",hex(d.activitySlopePerSec()),"activitySpread",hex(d.activitySpread()),"foreground",hex(d.foregroundRatio()),"context",context,
                    "reference",target.referenceLoudness().present()?hex(target.referenceLoudness().lufs()):"absent","weight",hex(cache.referencePlan().weightAt(i)),
                    "reason",target.reason().name(),"rawDb",hex(target.rawDb()),"confidence",hex(target.gConf()),"weightedDb",hex(target.confidenceWeightedDb())));
        }
        for(int i=0;i<cache.similarity().segmentCount();i++) for(int j=i+1;j<cache.similarity().segmentCount();j++) {
            var score=cache.similarity().scoreAt(i,j);pairs.add(m("first",i,"second",j,"H",hex(score.h()),"T",hex(score.t()),"A",hex(score.a()),"C",hex(score.c()),
                    "rotation",score.chromaRotation(),"validBins",score.validBins(),"reason",score.rejectionReason().name()));
        }
        for(var group:list(cache.grouping().groups()))groups.add(m("members",group.copyMemberOrdinals(),"quality",group.copyMemberQuality(),"confidence",hex(group.confidence())));
        for(var pair:list(cache.grouping().pairs()))groupPairs.add(m("first",pair.firstOrdinal(),"second",pair.secondOrdinal(),"margin",hex(pair.margin()),"confidence",hex(pair.confidence())));
        List<String> diagnostics=new ArrayList<>();for(var entry:list(s.diagnostics().entries()))diagnostics.add(entry.code().name());
        Map<String,Object> mapping=new LinkedHashMap<>();for(var truth:clip.truth())mapping.put(truth.label(),mapTruth(cache,truth));
        Map<String,Object> result=m("engine",s.result().status().name(),"conformance",s.diagnostics().standardValidation().state().name(),
                "comparisonVersion",comparisonVersion,"algorithm",cache.algorithmId(),"profile",cache.profileId(),
                "diagnostics",diagnostics,"layout",cache.layout().status().name(),"pcmFingerprint",HexFormat.of().formatHex(cache.copyPcmFingerprintSha256()),
                "regions",regions,"similarity",pairs,"groups",groups,"pairs",groupPairs,"truthMapping",mapping);
        int a=find(cache,clip,clip.testCase().first()),b=find(cache,clip,clip.testCase().second());
        if(a>=0&&b>=0&&a!=b) {
            var da=cache.descriptors().get(a);var db=cache.descriptors().get(b);
            var comparison=ComparisonV2Oracle.observe(cache,da.range(),db.range());
            result.put("truthPairIndependentComparison",comparison.evidence());
            result.put("truthPairProductionBodyAssessment",new BodyContextGate().assess(da.sketch(),db.sketch(),da.context(),db.context()));
        }
        return result;
    }

    static void verify(MusicalPcmFixture.Case c,MusicalPcmFixture.Clip clip,ShadowAnalysisSnapshot s,Map<String,Object> observed,List<String> failures) throws Exception {
        if(c.key().equals("A08_SHORT_EMPTY")) {
            if(s!=null)for(int i=0;i<s.cache().descriptors().size();i++) {
                check(!s.cache().descriptors().get(i).regionalLoudness().present(),"short regional loudness absent",failures);unit(s.cache(),i,failures);
            }
            float[] invalid={Float.NaN};check(new LevelerAnalysisEngine().analyzeShadow(invalid,new AudioFormat(clip.format().sampleRateHz(),1,1),new CancellationToken())==null,"NaN rejects safely",failures);
            invalid[0]=Float.POSITIVE_INFINITY;check(new LevelerAnalysisEngine().analyzeShadow(invalid,new AudioFormat(clip.format().sampleRateHz(),1,1),new CancellationToken())==null,"Infinity rejects safely",failures);
            return;
        }
        if(c.key().equals("A06_DENSE_NOVELTY")) {verifyDense(clip,s,observed,failures);return;}
        check(s!=null,"real engine returns analyzable snapshot",failures);if(s==null)return;
        var cache=s.cache();check(s.diagnostics().standardValidation().state()==ConformanceState.NOT_RUN,"real conformance remains NOT_RUN",failures);
        check(cache.protections().get(0).flags().containsBit(1),"first detected region always INTRO_EDGE",failures);
        check(cache.protections().get(cache.descriptors().size()-1).flags().containsBit(2),"last detected region always OUTRO_EDGE",failures);
        boolean veto=false;for(var d:list(s.diagnostics().entries()))if(d.code()==DiagnosticCode.STANDARD_VALIDATION_FAILED)veto=true;
        check(veto,"real STANDARD_VALIDATION_FAILED diagnostic present",failures);
        check(hashPcm(clip.pcm()).equals(HexFormat.of().formatHex(cache.copyPcmFingerprintSha256())),"engine fingerprint matches original PCM",failures);
        check(cache.descriptors().size()==cache.referencePlan().size(),"one target per joint-channel region",failures);
        long end=0;for(int i=0;i<cache.descriptors().size();i++) {
            var d=cache.descriptors().get(i);check(d.range().startInclusive()==end,"contiguous region "+i,failures);end=d.range().endExclusive();
            check(d.id()==cache.referencePlan().targets().get(i).segmentId(),"shared segment ID target "+i,failures);
            if(cache.protections().get(i).isBlocked())unit(cache,i,failures);
        }check(end==clip.format().frames(),"partition covers EOF",failures);
        // Version dispatch follows actual cache identity; every executed content comparison is checked.
        var contentGate=new BodyContextGate();
        for(int i=0;i<cache.similarity().segmentCount();i++)for(int j=i+1;j<cache.similarity().segmentCount();j++) {
            var actual=cache.similarity().scoreAt(i,j);
            if(contentGate.evaluate(i,cache.descriptors(),cache.protections()).eligible()
                    &&contentGate.evaluate(j,cache.descriptors(),cache.protections()).eligible()
                    &&contentGate.compare(cache.descriptors().get(i),cache.descriptors().get(j))==SimilarityRejectionReason.NONE) {
                var first=cache.descriptors().get(i).range();var second=cache.descriptors().get(j).range();
                var oracle=ComparisonV2Oracle.observe(cache,first,second);
                ComparisonV2Oracle.verifyProduct(cache,first,second,actual,oracle,"pair "+i+":"+j,failures);
            }
        }
        int a=find(cache,clip,c.first()),b=find(cache,clip,c.second()),third=find(cache,clip,c.third());
        if(c.first()!=null&&!c.key().equals("N03_SHORT_BREAK"))check(a>=0,"semantic region "+c.first()+" detected with >=80% coverage AND purity",failures);
        if(c.second()!=null)check(b>=0&&b!=a,"distinct semantic region "+c.second()+" detected",failures);
        if(c.third()!=null)check(third>=0&&third!=a&&third!=b,"distinct semantic region "+c.third()+" detected",failures);
        if(c.key().startsWith("N")&&a>=0) {
            unit(cache,a,failures);
            if(c.protectionBit()>=0)check(cache.protections().get(a).flags().containsBit(c.protectionBit()),"expected protection bit "+c.protectionBit()+" genuinely reached",failures);
            if(c.key().equals("N06_UNIQUE_BRIDGE"))check(cache.referencePlan().targets().get(a).reason()==ReferenceReason.NOT_COMPARABLE,"unique bridge not comparable",failures);
        }
        if(c.key().equals("N03_SHORT_BREAK"))for(var truth:clip.truth())if(truth.label().equals("subject")) {
            // No requirement that a sub-minimum break become an independently detected section.
            for(int i=0;i<cache.descriptors().size();i++)if(overlap(cache.descriptors().get(i).range(),truth)>0)unit(cache,i,failures);
        }
        if(a<0||b<0||a==b)return;
        var da=cache.descriptors().get(a);var db=cache.descriptors().get(b);
        var comparison=ComparisonV2Oracle.observe(cache,da.range(),db.range());
        double[] body=new BodyContextGate().assess(da.sketch(),db.sketch(),da.context(),db.context());
        if(c.key().equals("N08_GAIN_SCALED_INTENT")) {
            check(body!=null&&body[2]<=1e-4&&body[3]<=.05,"gain-scaled positive-control R<=1e-4 and D<=.05",failures);
            check(comparison.central().h()>=.92&&comparison.central().t()>=.90&&comparison.central().a()>=.90,"gain-scaled content otherwise matches",failures);
            check(cache.similarity().scoreAt(a,b).rejectionReason()==SimilarityRejectionReason.INTENT_UNIDENTIFIABLE,"intent gate genuinely reached",failures);unit(cache,b,failures);
        }
        if(c.key().startsWith("P")) {
            check(body!=null&&body[2]>=.02,"genuine non-gain-scaled performance R>=.02",failures);
            if(c.key().equals("P01_ABA_LEVEL")) {
                check(body!=null&&body[3]>.05&&body[3]<=.25,"positive-control normative .05 < Dctx <= .25",failures);
                check(cache.referencePlan().targets().get(a).reason()==ReferenceReason.PAIR_REFERENCE&&cache.referencePlan().targets().get(b).reason()==ReferenceReason.PAIR_REFERENCE,"expected interior pair reference",failures);
                check(cache.referencePlan().targets().get(a).confidenceWeightedDb()<0,"louder repeat analytic cut",failures);
                check(cache.referencePlan().targets().get(b).confidenceWeightedDb()>0,"quieter repeat analytic boost",failures);
            }else if(third>=0) {
                boolean found=false;for(var group:list(cache.grouping().groups())) {
                    var set=new HashSet<Integer>();for(int ordinal:group.copyMemberOrdinals())set.add(ordinal);
                    if(set.containsAll(List.of(a,b,third)))found=true;
                }check(found,"three truth repeats share actual comparable group",failures);
                check(cache.referencePlan().targets().get(b).confidenceWeightedDb()>0,"outlier boosted in analytic plan",failures);
                near(cache.referencePlan().targets().get(a).rawDb(),0,0,"first normal repeat inside deadband",failures);
                near(cache.referencePlan().targets().get(third).rawDb(),0,0,"third normal repeat inside deadband",failures);
            }
            verifyReferences(cache,failures);
        }
        if(c.key().equals("A01_SHARED_ACCOMP")) {
            check(comparison.central().h()<.85||comparison.central().t()<.80||comparison.central().a()<.82||comparison.central().c()<.85,
                    "different lead discriminated by actual content thresholds, not just a contextual veto",failures);unit(cache,a,failures);unit(cache,b,failures);
        }
        if(c.key().equals("A02_SAME_CHROMA_TIMBRE")) {
            check(comparison.central().t()<.90,"radically different timbre genuinely below pair T gate",failures);unit(cache,a,failures);unit(cache,b,failures);
        }
        if(c.key().equals("A03_TRANSPOSED_REPEAT")) {
            check(comparison.central().rotation()==2,"independent fixed-transpose rotation two",failures);
            if(comparison.version()==1)
                check(comparison.central().h()<=.95+1e-12&&comparison.central().h()>.90,"historical V1 visible .05 transposition penalty with otherwise high harmonic match",failures);
            else {
                var full=(ComparisonV2Oracle.Comparison)comparison.evidence();
                check(full.central().longValid()>0&&full.central().support()*5L>=full.central().longValid()*4L,"independent long rotation support >=80%",failures);
                check(full.variants().size()==8,"A03 complete eight endpoint views",failures);
                observed.put("A03HarmonicIdentity",m("contract","A03-V2-FULL-PATH-PENALTY-1","historicalMixedHAbove90","historical uncalibrated hypothesis; not the V2 gate",
                        "H",full.central().h(),"T",full.central().t(),"A",full.central().a(),"C",full.central().c(),
                        "pathH",ComparisonV2Oracle.harmonicFromPath(full.central()),"longSupport",full.central().support(),"longValid",full.central().longValid(),
                        "firstReference",cache.referencePlan().targets().get(a).reason().name(),"secondReference",cache.referencePlan().targets().get(b).reason().name()));
            }
            var actual=ComparisonV2Oracle.product(cache,a,b);
            ComparisonV2Oracle.verifyProduct(cache,da.range(),db.range(),actual,comparison,"A03 diagnostic",failures);
            check(actual.chromaRotation()==comparison.central().rotation(),"real PCM product transpose tie/rotation equals oracle",failures);
        }
        if(c.key().equals("A04_BOUNDARY_JITTER")) {
            var actual=ComparisonV2Oracle.product(cache,a,b);
            ComparisonV2Oracle.verifyProduct(cache,da.range(),db.range(),actual,comparison,"A04 diagnostic",failures);
            check(actual.rejectionReason()==comparison.reason(),"real re-extracted endpoint stability equals independent oracle",failures);
            check(comparison.variants().size()==8,"all eight endpoint variants observed",failures);
            if(comparison.reason()==SimilarityRejectionReason.UNSTABLE_BOUNDARY) {unit(cache,a,failures);unit(cache,b,failures);}
        }
    }

    private static void verifyReferences(ShadowAnalysisCache cache,List<String> failures) {
        for(var pair:list(cache.grouping().pairs())) {
            int a=pair.firstOrdinal(),b=pair.secondOrdinal();double ref=(cache.descriptors().get(a).regionalLoudness().lufs()+cache.descriptors().get(b).regionalLoudness().lufs())/2;
            near(cache.referencePlan().weightAt(a),.5,0,"pair equal first weight",failures);near(cache.referencePlan().weightAt(b),.5,0,"pair equal second weight",failures);
            double bestOtherA=0,bestOtherB=0;
            for(int i=0;i<cache.descriptors().size();i++)if(i!=a&&i!=b){var sa=cache.similarity().scoreAt(a,i);var sb=cache.similarity().scoreAt(b,i);
                if(sa.isAccepted())bestOtherA=Math.max(bestOtherA,sa.c());if(sb.isAccepted())bestOtherB=Math.max(bestOtherB,sb.c());}
            double c=cache.similarity().scoreAt(a,b).c(),margin=Math.min(c-bestOtherA,c-bestOtherB);
            double confidence=smooth((c-.92)/.06)*smooth((margin-.12)/.12);
            target(cache,a,ref,confidence,failures);target(cache,b,ref,confidence,failures);
        }
        for(var group:list(cache.grouping().groups())) {
            int[] members=group.copyMemberOrdinals();double sum=0;List<Integer> order=new ArrayList<>();double cohesion=1,external=0;
            Set<Integer> set=new HashSet<>();for(int member:members)set.add(member);
            for(int member:members){double weight=cache.referencePlan().weightAt(member);sum+=weight;check(weight>=0&&weight<=.4,"group member weight cap",failures);order.add(member);
                for(int other=0;other<cache.descriptors().size();other++)if(other!=member){var score=cache.similarity().scoreAt(member,other);
                    if(set.contains(other))cohesion=Math.min(cohesion,score.c());else if(score.isAccepted())external=Math.max(external,score.c());}}
            near(sum,1,1e-12,"group normalized weights",failures);
            order.sort(Comparator.comparingDouble((Integer i)->cache.descriptors().get(i).regionalLoudness().lufs()).thenComparingInt(i->i));
            double cumulative=0,ref=0;for(int member:order){cumulative+=cache.referencePlan().weightAt(member);ref=cache.descriptors().get(member).regionalLoudness().lufs();if(cumulative>=.5)break;}
            double confidence=smooth((cohesion-.85)/.10)*smooth((cohesion-external-.08)/.12);
            for(int member:members)target(cache,member,ref,confidence,failures);
        }
    }
    private static double smooth(double value){value=Math.max(0,Math.min(1,value));return value*value*(3-2*value);}
    private static void target(ShadowAnalysisCache cache,int i,double ref,double conf,List<String> failures) {
        var t=cache.referencePlan().targets().get(i);double gap=ref-cache.descriptors().get(i).regionalLoudness().lufs();double raw=Math.abs(gap)<=1?0:gap-Math.copySign(1,gap);
        check(t.referenceLoudness().present(),"reference present "+i,failures);near(t.referenceLoudness().lufs(),ref,1e-12,"normative reference "+i,failures);
        near(t.rawDb(),raw,1e-12,"normative raw target "+i,failures);near(t.gConf(),conf,1e-12,"normative confidence "+i,failures);near(t.confidenceWeightedDb(),raw*conf,1e-12,"weighted target "+i,failures);
    }

    private static void verifyDense(MusicalPcmFixture.Clip clip,ShadowAnalysisSnapshot s,Map<String,Object> observed,List<String> failures) throws Exception {
        var loudness=s==null?new LoudnessAnalyzer().analyze(clip.pcm(),clip.format(),new CancellationToken()):s.cache().loudness();
        var features=s==null?new StructuralFeatureExtractor().extract(clip.pcm(),clip.format(),loudness,new CancellationToken()):s.cache().features();
        var q=BoundaryDetector.originalNovelty(features,clip.format().frames(),LevelerCalibrationProfile.V1);
        double[] sorted=q.clone();Arrays.sort(sorted);double median=percentile(sorted,.5);double[] dev=Arrays.stream(q).map(v->Math.abs(v-median)).sorted().toArray();
        double mad=percentile(dev,.5),initial=mad>0?median+3*mad:percentile(sorted,.95);
        int initialCount=candidates(q,initial),p975=candidates(q,percentile(sorted,.975)),p99=candidates(q,percentile(sorted,.99));
        var persistent=selectedCandidates(persistentReference(features),.02);
        List<Integer> truth=new ArrayList<>();for(int i=1;i<90;i++)truth.add(8*i);
        check(persistent.equals(truth),"independent lasting-content oracle identifies all89 unchanged truth transitions",failures);
        var mergedInitial=mergedCandidates(selectedCandidates(q,initial),persistent);
        var merged975=mergedCandidates(selectedCandidates(q,percentile(sorted,.975)),persistent);
        var merged99=mergedCandidates(selectedCandidates(q,percentile(sorted,.99)),persistent);
        observed.put("retryOracle",m("version","A06-COMPLETE-CANDIDATES-1","QCount",q.length,
                "qOnlyInitialCandidates",initialCount,"qOnlyP975Candidates",p975,"qOnlyP99Candidates",p99,
                "legacyQOnlyRetryPrecondition",initialCount+1>64,"persistentCandidates",persistent,
                "mergedInitialCandidates",mergedInitial.size(),"mergedP975Candidates",merged975.size(),"mergedP99Candidates",merged99.size(),
                "persistent6400sStatus","PENDING_SEPARATE_FUNCTIONAL_WINDOW; short lasting-content overflow is not Q-only persistent proof"));
        check(mergedInitial.size()+1>64,"bounded real PCM genuinely exercises complete-candidate cap retry",failures);
        var layout=new BoundaryDetector().detect(features,clip.format().frames(),LevelerCalibrationProfile.V1);
        if(merged99.size()+1>64) {
            check(layout.status()==LayoutStatus.TOO_MANY_SEGMENTS&&layout.regions().size()==0&&s==null,"complete-candidate fallback empty without prefix",failures);
            if(Boolean.getBoolean("musical.pcm.a06DiagnosticUnbound")) {
                var processor=new LevelerProcessor();processor.prepare(clip.format().sampleRateHz(),clip.pcm().length);
                processor.setEnabled(true);processor.setLeveling(1);processor.setSpeed(.5);processor.analyze(clip.pcm(),clip.format().channels());
                check(processor.getAnalysisDiagnostic().equals("INSUFFICIENT_ANALYSIS"),"diagnostic unbound overflow facade explicitly diagnoses whole-track unit",failures);
                float[] output=clip.pcm().clone();processor.process(output,clip.format().channels());long changed=0;
                for(int i=0;i<output.length;i++)if(Float.floatToRawIntBits(output[i])!=Float.floatToRawIntBits(clip.pcm()[i]))changed++;
                check(changed==0,"diagnostic unbound overflow preserves all original samples through EOF",failures);
                check(ConformanceArtifactLoader.loadCurrent().state()==ConformanceState.NOT_RUN,"diagnostic has no current official authority",failures);
                observed.put("fallbackRender",m("samples",output.length,"changedBits",changed,"diagnostic",processor.getAnalysisDiagnostic(),"actualOwnJar",false,"conformance","NOT_RUN"));
            } else
            try(var processor=RuntimeLevelerAcceptanceTest.processor()) {
                processor.controls(1,.5);processor.analyze(clip.pcm(),clip.format().sampleRateHz(),clip.format().channels());
                check(processor.status().equals("INSUFFICIENT_ANALYSIS"),"overflow facade explicitly diagnoses whole-track unit",failures);
                float[] output=processor.render(clip.pcm(),clip.format().channels(),0);long changed=0;
                for(int i=0;i<output.length;i++)if(Float.floatToRawIntBits(output[i])!=Float.floatToRawIntBits(clip.pcm()[i]))changed++;
                check(output.length==clip.pcm().length&&changed==0,"overflow fallback renders every original sample through EOF bit-exact",failures);
                observed.put("fallbackRender",m("samples",output.length,"changedBits",changed,"diagnostic",processor.status(),"actualOwnJar",true));
            }
        }
        else {check(layout.status()==LayoutStatus.READY&&layout.regions().size()<=64,"retry produces bounded valid layout",failures);
            long end=0;for(var r:list(layout.regions())){check(r.startInclusive()==end,"dense partition continuous",failures);end=r.endExclusive();}check(end==clip.format().frames(),"dense EOF retained",failures);}
    }
    /** Independent uncapped sustained-content reference, not a call to a product private helper. */
    static double[] persistentReference(FeatureTimeline features) {
        double[] scores=new double[features.size()-1];
        for(int boundary=8;boundary<=features.size()-8;boundary++) {
            double[][] mean=new double[4][20];
            for(int row=0;row<4;row++)for(int frame=boundary-8+4*row;frame<boundary-4+4*row;frame++) {
                for(int c=0;c<12;c++)mean[row][c]+=features.frame(frame).chromaAt(c)/4;
                for(int c=0;c<8;c++)mean[row][12+c]+=features.frame(frame).spectralAt(c)/4;
            }
            double cross=Math.min(referenceContentDistance(mean[1],mean[2]),referenceContentDistance(mean[0],mean[3]));
            double within=Math.max(referenceContentDistance(mean[0],mean[1]),referenceContentDistance(mean[2],mean[3]));
            scores[boundary-1]=Math.max(0,cross-4*within);
        }
        return scores;
    }
    private static double referenceContentDistance(double[] a,double[] b) {
        double result=0;
        for(int branch=0;branch<2;branch++) {
            int start=branch==0?0:12,end=branch==0?12:20;
            double dot=0,aa=0,bb=0;
            for(int i=start;i<end;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
            double na=StrictMath.sqrt(aa),nb=StrictMath.sqrt(bb),distance;
            if(na<=1e-12&&nb<=1e-12)distance=0;
            else if(na<=1e-12||nb<=1e-12)distance=1;
            else {double cosine=Math.max(branch==0?0:-1,Math.min(1,dot/(na*nb)));distance=(1-cosine)/(branch==0?1:2);}
            result+=(branch==0?.5:.3)*distance;
        }
        return result;
    }
    static List<Integer> selectedCandidates(double[] q,double threshold) {
        List<Integer> maxima=new ArrayList<>();
        for(int i=0;i<q.length;i++)if(q[i]>threshold&&q[i]>0) {
            boolean largest=true;
            for(int j=Math.max(0,i-4);j<=Math.min(q.length-1,i+4);j++)
                if(q[j]>q[i]||(Double.doubleToRawLongBits(q[j])==Double.doubleToRawLongBits(q[i])&&j<i))largest=false;
            if(largest)maxima.add(i+1);
        }
        maxima.sort(Comparator.<Integer>comparingDouble(i->-q[i-1]).thenComparingInt(i->i));
        List<Integer> accepted=new ArrayList<>();
        for(int candidate:maxima)if(accepted.stream().noneMatch(prior->Math.abs(prior-candidate)<4))accepted.add(candidate);
        Collections.sort(accepted);return accepted;
    }
    private static List<Integer> mergedCandidates(List<Integer> original,List<Integer> persistent) {
        List<Integer> result=new ArrayList<>(original);
        for(int candidate:persistent) {
            int nearby=-1;
            for(int i=0;i<result.size();i++)if(Math.abs(candidate-result.get(i))<4){nearby=i;break;}
            if(nearby<0)result.add(candidate);
            else {
                boolean isolated=true;
                for(int i=0;i<result.size();i++)if(i!=nearby&&Math.abs(candidate-result.get(i))<4)isolated=false;
                if(isolated)result.set(nearby,candidate);
            }
        }
        Collections.sort(result);return result;
    }
    private static int candidates(double[] q,double threshold) {
        List<Integer> maxima=new ArrayList<>();for(int i=0;i<q.length;i++)if(q[i]>threshold&&q[i]>0){boolean keep=true;
            for(int j=Math.max(0,i-4);j<=Math.min(q.length-1,i+4);j++)if(q[j]>q[i]||(Double.doubleToRawLongBits(q[j])==Double.doubleToRawLongBits(q[i])&&j<i))keep=false;
            if(keep)maxima.add(i);}
        maxima.sort(Comparator.<Integer>comparingDouble(i->-q[i]).thenComparingInt(i->i));List<Integer> selected=new ArrayList<>();
        for(int i:maxima)if(selected.stream().noneMatch(j->Math.abs(i-j)<4))selected.add(i);return selected.size();
    }
    private static double percentile(double[] sorted,double p){return sorted[Math.max(0,(int)Math.ceil(sorted.length*p)-1)];}

    private static void verifyPower(MusicalPcmFixture.Clip clip,List<String> failures,Path directory) throws Exception {
        if(clip.format().channels()!=2)return;
        int n=Math.toIntExact(clip.format().frames());float[] left=new float[n],right=new float[n],sum=new float[n];
        for(int i=0;i<n;i++){left[i]=clip.pcm()[2*i];right[i]=clip.pcm()[2*i+1];sum[i]=(left[i]+right[i])*.5f;}
        var fmt=new AudioFormat(clip.format().sampleRateHz(),1,n);var analyzer=new LoudnessAnalyzer();
        var l=analyzer.analyze(left,fmt,new CancellationToken());var r=analyzer.analyze(right,fmt,new CancellationToken());
        var stereo=analyzer.analyze(clip.pcm(),clip.format(),new CancellationToken());var folded=analyzer.analyze(sum,fmt,new CancellationToken());
        double maxError=0;for(int i=0;i<stereo.momentaryCount();i++)if(stereo.momentaryValidAt(i))maxError=Math.max(maxError,Math.abs(stereo.momentaryPowerAt(i)-l.momentaryPowerAt(i)-r.momentaryPowerAt(i)));
        near(maxError,0,1e-12,"stereo K power equals left power + right power",failures);
        double difference=stereo.integrated().lufs()-folded.integrated().lufs();check(difference>6,"antiphase energy retained above cancelled fold-down",failures);
        write(directory.resolve("power-summation.json"),m("maxMomentaryPowerAdditivityError",maxError,"stereoMinusFoldDownLu",difference,"scope","analysis only, not post-render stereo"));
    }

    private static void integration(MusicalPcmFixture.Clip clip,List<String> failures,Path directory) throws Exception {
        LevelerProcessor processor=new LevelerProcessor();processor.prepare(clip.format().sampleRateHz(),clip.pcm().length);processor.analyze(clip.pcm(),2);
        // Read-only reflection bridges an existing package-private publication API; never changes fields/gates.
        Method read=AnalysisDynamicsProcessor.class.getDeclaredMethod("publishedGain");read.setAccessible(true);Object publication=read.invoke(processor);
        Method status=publication.getClass().getDeclaredMethod("status");status.setAccessible(true);Method schedule=publication.getClass().getDeclaredMethod("schedule");schedule.setAccessible(true);
        String state=status.invoke(publication).toString(),scheduleClass=schedule.invoke(publication).getClass().getSimpleName();
        var shadow=processor.getShadowAnalysis();check(shadow!=null,"real LevelerProcessor shadow exists",failures);
        check(state.equals("STANDARD_VALIDATION_FAILED")&&scheduleClass.equals("DenseGainSchedule")&&!processor.isAnalyzed(),
                "NOT_RUN cannot authorize active Leveler audio",failures);
        processor.setEnabled(true);processor.setPlaybackPosition(0);
        float[] input=Arrays.copyOfRange(clip.pcm(),0,8192),rendered=input.clone();processor.process(rendered,2);
        for(int i=0;i<input.length;i++)check(Float.floatToRawIntBits(input[i])==Float.floatToRawIntBits(rendered[i]),"unbound publication raw unit sample "+i,failures);
        check(shadow!=null&&shadow.diagnostics().standardValidation().state()==ConformanceState.NOT_RUN,"integrated unmodified NOT_RUN",failures);
        write(directory.resolve("leveler-integration.json"),m("publicationStatus",state,"scheduleClass",scheduleClass,"conformance",shadow==null?"null":shadow.diagnostics().standardValidation().state(),"bindingFabricated",false));
    }

    private static void auditEmpty(String name,MusicalPcmFixture.Case c,int rate,int channels,Path directory)throws Exception {
        List<String> failures=new ArrayList<>();List<Object> runs=new ArrayList<>();float[] empty=new float[0];
        write(directory.resolve("manifest-before-observation.json"),m("key",c.key(),"variant",c.variant(),"expected",c.expected(),"frames",0,"rate",rate,"channels",channels,
                "pcmSha256",hashPcm(empty),"contract","AudioFormat forbids F=0. Verify explicit constructor rejection, engine null/mismatched format rejection, and real facade empty no-op; do not fabricate a valid zero-length DTO."));
        for(int repeat=1;repeat<=3;repeat++) {
            boolean formatRejected=false;try{new AudioFormat(rate,channels,0);}catch(IllegalArgumentException expected){formatRejected=true;}
            var engine=new LevelerAnalysisEngine();boolean nullFormatRejected=engine.analyzeShadow(empty,null,new CancellationToken())==null;
            boolean mismatchedExtentRejected=engine.analyzeShadow(empty,new AudioFormat(rate,channels,1),new CancellationToken())==null;
            var processor=new LevelerProcessor();processor.prepare(rate,0);processor.analyze(empty,channels);
            boolean facadeNoop=processor.getShadowAnalysis()==null&&!processor.isAnalyzed();
            check(formatRejected&&nullFormatRejected&&mismatchedExtentRejected&&facadeNoop,"empty input explicit guards and real facade no-op run "+repeat,failures);
            var observed=m("formatF0Rejected",formatRejected,"nullFormatEngineRejected",nullFormatRejected,"mismatchedExtentEngineRejected",mismatchedExtentRejected,"facadeEmptyNoop",facadeNoop);
            runs.add(observed);write(directory.resolve("run-"+repeat+".json"),observed);
        }
        var result=m("name",name,"key",c.key(),"expected",c.expected(),"status",failures.isEmpty()?"PROBED_PASS":"PROBED_FAIL","engineRunCount",6,"facadeRunCount",3,"observed",runs,"failures",failures);
        write(directory.resolve("result.json"),result);SUMMARY.add(m("name",name,"key",c.key(),"status",result.get("status"),"failures",failures));write(OUT.resolve("matrix-progress.json"),SUMMARY);
        System.out.println(name+" "+result.get("status")+" empty guards; no valid zero-extent format invented");assertTrue(failures.isEmpty(),String.join("\n",failures));
    }

    private static int find(ShadowAnalysisCache cache,MusicalPcmFixture.Clip clip,String label){if(label==null)return -1;for(var t:clip.truth())if(t.label().equals(label))return (int)mapTruth(cache,t).get("detectedOrdinal");return -1;}
    private static Map<String,Object> mapTruth(ShadowAnalysisCache cache,MusicalPcmFixture.Truth truth) {
        int best=-1;long area=0;double coverage=0,purity=0;for(int i=0;i<cache.descriptors().size();i++){var range=cache.descriptors().get(i).range();long overlap=overlap(range,truth);
            if(overlap>area){area=overlap;coverage=overlap/(double)Math.max(1,truth.end()-truth.start());purity=overlap/(double)range.lengthFrames();best=i;}}
        return m("detectedOrdinal",coverage>=.8&&purity>=.8?best:-1,"bestOverlapOrdinal",best,"truthCoverage",coverage,"detectedPurity",purity);
    }
    private static long overlap(FrameRange range,MusicalPcmFixture.Truth truth){return Math.max(0,Math.min(range.endExclusive(),truth.end())-Math.max(range.startInclusive(),truth.start()));}
    private static void unit(ShadowAnalysisCache cache,int index,List<String> failures){var target=cache.referencePlan().targets().get(index);
        check(Double.doubleToRawLongBits(target.rawDb())==0L,"raw target exact +0 region "+index,failures);
        check(Double.doubleToRawLongBits(target.confidenceWeightedDb())==0L,"weighted target exact +0 region "+index,failures);}
    static void check(boolean condition,String expectation,List<String> failures){if(!condition)failures.add(expectation);}
    static void near(double actual,double expected,double tolerance,String what,List<String> failures){check(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,what+" expected="+expected+" actual="+actual,failures);}
    static String hex(double v){return Double.toHexString(v);}
    static <T> List<T> list(FrozenList<T> source){return java.util.stream.IntStream.range(0,source.size()).mapToObj(source::get).toList();}
    static String hashPcm(float[] pcm)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[16384];int p=0;for(float value:pcm){int bits=Float.floatToRawIntBits(value);block[p++]=(byte)(bits>>>24);block[p++]=(byte)(bits>>>16);block[p++]=(byte)(bits>>>8);block[p++]=(byte)bits;if(p==block.length){digest.update(block);p=0;}}digest.update(block,0,p);return HexFormat.of().formatHex(digest.digest());}
    static Map<String,Object> m(Object...kv){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)map.put((String)kv[i],kv[i+1]);return map;}
    static void write(Path path,Object value)throws Exception{Files.writeString(path,JSON.toJson(value)+"\n",StandardCharsets.UTF_8);}
}
