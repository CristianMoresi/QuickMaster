package com.quickmaster.processing.dynamics.leveler;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Explicit version dispatcher and unchanged frozen causal assertions; no absent V2 symbol is a RED. */
public final class MusicalComparisonV2Test {
    record Observation(double h,double t,double a,double c,int rotation,int count,String reason,int variants) { }
    static int checks,failed;
    static void check(boolean value,String cause){checks++;if(!value){failed++;System.out.println("QM_V2_BASELINE_SEMANTIC "+cause);}}
    static Observation observe(ShadowAnalysisCache cache,FrameRange a,FrameRange b,ComparisonV2Oracle.Source comparison) {
        int version=ComparisonV2Oracle.version(cache);
        if(version==1) {
            if(comparison!=null)throw new IllegalArgumentException("V2 timeline passed to V1 observer");
            var o=MusicalContextOracle.compare(cache.features(),cache.format(),a,b);var s=o.central();
            return new Observation(s.h(),s.t(),s.a(),s.c(),s.rotation(),s.count(),o.reason().name(),o.variants().size());
        }
        if(comparison==null||comparison.format()!=cache.format()||comparison.retainedTimeline()!=cache.comparison())throw new IllegalArgumentException("V2 observation requires explicit matching retained comparison source adapter");
        var o=ComparisonV2Oracle.compare(comparison,new ComparisonV2Oracle.Range(a.startInclusive(),a.endExclusive()),new ComparisonV2Oracle.Range(b.startInclusive(),b.endExclusive()));var s=o.central();
        return new Observation(s.h(),s.t(),s.a(),s.c(),s.rotation(),s.count(),o.reason().name(),o.variants().size());
    }
    static String hash(Object value)throws ReflectiveOperationException,NoSuchAlgorithmException {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");append(digest,value,new IdentityHashMap<>());return HexFormat.of().formatHex(digest.digest());
    }
    static void bytes(MessageDigest d,long v){for(int i=7;i>=0;i--)d.update((byte)(v>>>(i*8)));}
    static void string(MessageDigest d,String s){byte[] b=s.getBytes(StandardCharsets.UTF_8);bytes(d,b.length);d.update(b);}
    static void append(MessageDigest d,Object value,IdentityHashMap<Object,Integer> identities)throws ReflectiveOperationException {
        if(value==null){string(d,"null");return;}Class<?> type=value.getClass();string(d,type.getName());
        if(value instanceof Double v){bytes(d,Double.doubleToRawLongBits(v));return;}
        if(value instanceof Float v){bytes(d,Float.floatToRawIntBits(v));return;}
        if(value instanceof Number v){bytes(d,v.longValue());return;}
        if(value instanceof Boolean v){bytes(d,v?1:0);return;}
        if(value instanceof Character v){bytes(d,v);return;}
        if(value instanceof String v){string(d,v);return;}
        if(value instanceof Enum<?> v){string(d,v.name());return;}
        Integer earlier=identities.get(value);if(earlier!=null){string(d,"alias");bytes(d,earlier);return;}identities.put(value,identities.size());
        if(value instanceof BitSet bits){bytes(d,bits.size());append(d,bits.toLongArray(),identities);return;}
        if(type.isArray()){int n=Array.getLength(value);bytes(d,n);for(int i=0;i<n;i++)append(d,Array.get(value,i),identities);return;}
        if(!type.getName().startsWith("com.quickmaster.processing.dynamics.leveler.model."))throw new IllegalArgumentException("unexpected structural witness type "+type);
        List<Field> fields=new ArrayList<>();for(Class<?> t=type;t!=Object.class;t=t.getSuperclass())for(Field f:t.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers()))fields.add(f);
        fields.sort(Comparator.comparing(f->f.getDeclaringClass().getName()+"."+f.getName()));bytes(d,fields.size());
        for(Field f:fields){string(d,f.getDeclaringClass().getName()+"."+f.getName());f.setAccessible(true);append(d,f.get(value),identities);}
    }
    static void witnesses(ShadowAnalysisCache cache)throws ReflectiveOperationException,NoSuchAlgorithmException {
        for(Object[] row:new Object[][]{{"loudness",cache.loudness()},{"features",cache.features()},{"layout",cache.layout()},{"descriptors_context_sketch",cache.descriptors()},{"protections",cache.protections()},
                {"originalQ",BoundaryDetector.originalNovelty(cache.features(),cache.format().frames(),LevelerCalibrationProfile.V1)}})
            System.out.println("STRUCTURAL_WITNESS "+row[0]+" "+hash(row[1]));
    }
    static int mapping(MusicalPcmFixture.Clip clip,ShadowAnalysisCache cache,String label) {
        var truth=clip.truth().stream().filter(t->t.label().equals(label)).findFirst().orElseThrow();int best=-1;long overlap=-1;
        for(int i=0;i<cache.descriptors().size();i++){var r=cache.descriptors().get(i).range();long x=Math.max(0,Math.min(truth.end(),r.endExclusive())-Math.max(truth.start(),r.startInclusive()));if(x>overlap){overlap=x;best=i;}}
        var r=cache.descriptors().get(best).range();check(overlap/(double)(truth.end()-truth.start())>=.8,"truth coverage "+label);check(overlap/(double)r.lengthFrames()>=.8,"detected purity "+label);return best;
    }
    static String classHash(Class<?> type)throws Exception {try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){if(in==null)throw new IllegalStateException("class resource");return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));}}
    public static void main(String[] args)throws Exception {
        checks=0;failed=0;
        if(args.length!=3)throw new IllegalArgumentException("A01/A02 key rate channels");
        String key=args[0];if(!key.equals("A01_SHARED_ACCOMP")&&!key.equals("A02_SAME_CHROMA_TIMBRE"))throw new IllegalArgumentException("bounded preparation keys only");
        var definition=MusicalPcmFixture.catalog().stream().filter(c->c.key().equals(key)).findFirst().orElseThrow();
        var clip=MusicalPcmFixture.generate(definition,Integer.parseInt(args[1]),Integer.parseInt(args[2]));
        var snapshot=new LevelerAnalysisEngine().analyzeShadow(clip.pcm(),clip.format(),new CancellationToken());
        if(snapshot==null)throw new IllegalStateException("baseline infrastructure: no snapshot");var cache=snapshot.cache();
        System.out.println("IDENTITY key="+key+" fs="+clip.format().sampleRateHz()+" channels="+clip.format().channels()+" algorithm="+cache.algorithmId()+" profile="+cache.profileId()+" pcmSha="+HexFormat.of().formatHex(cache.copyPcmFingerprintSha256()));
        for(Class<?> type:new Class<?>[]{LevelerAnalysisEngine.class,StructuralFeatureExtractor.class,BoundaryDetector.class,SegmentDescriptorBuilder.class,SegmentComparator.class,BodyContextGate.class,MusicalPcmFixture.class,MusicalContextOracle.class,MusicalComparisonV2Test.class,ComparisonV2Oracle.class})System.out.println("CLASS "+type.getName()+" "+classHash(type));
        witnesses(cache);int first=mapping(clip,cache,definition.first()),second=mapping(clip,cache,definition.second());check(first!=second,"distinct intended contents");
        ComparisonV2Oracle.Source comparison=ComparisonV2Oracle.version(cache.algorithmId(),cache.profileId())==2?ComparisonV2Test.source(cache.comparison()):null;
        var observed=observe(cache,cache.descriptors().get(first).range(),cache.descriptors().get(second).range(),comparison);System.out.println("OBSERVATION "+observed);
        check(observed.variants==8,"all eight endpoint variants independently observed");
        if(key.equals("A01_SHARED_ACCOMP"))check(observed.h<.85||observed.t<.80||observed.a<.82||observed.c<.85,"A01 frozen group-floor content discrimination");
        else check(observed.t<.90,"A02 frozen timbral pair-floor discrimination");
        for(int i:new int[]{first,second}){var target=cache.referencePlan().targets().get(i);System.out.println("TARGET "+i+" raw="+target.rawDb()+" conf="+target.gConf()+" weighted="+target.confidenceWeightedDb());check(Double.doubleToRawLongBits(target.rawDb())==0&&Double.doubleToRawLongBits(target.confidenceWeightedDb())==0,"negative target stays unit "+i);}
        System.out.println("COUNTS checks="+checks+" failed="+failed+" productV2Executed="+(comparison!=null));if(failed>0)throw new AssertionError("QM_V2_BASELINE_SEMANTIC failures="+failed);
    }
    @org.junit.jupiter.api.Test void frozenSharedAccompanimentDiscriminatesAllFormats()throws Exception {
        for(int rate:new int[]{44100,48000})for(int channels:new int[]{1,2})main(new String[]{"A01_SHARED_ACCOMP",Integer.toString(rate),Integer.toString(channels)});
    }
    @org.junit.jupiter.api.Test void frozenSameChromaDifferentTimbreDiscriminatesAllFormats()throws Exception {
        for(int rate:new int[]{44100,48000})for(int channels:new int[]{1,2})main(new String[]{"A02_SAME_CHROMA_TIMBRE",Integer.toString(rate),Integer.toString(channels)});
    }
    @org.junit.jupiter.api.Test void frozenTransposedRepeatRetainsRequiredHarmonicEvidence()throws Exception {
        checks=0;failed=0;
        for(int rate:new int[]{44100,48000})for(int channels:new int[]{1,2})auditTransposition(rate,channels);
        if(failed>0)throw new AssertionError("QM_V2_FROZEN_TRANSPOSITION failures="+failed);
    }
    static void auditTransposition(int rate,int channels)throws Exception {
            var definition=MusicalPcmFixture.catalog().stream().filter(c->c.key().equals("A03_TRANSPOSED_REPEAT")).findFirst().orElseThrow();var clip=MusicalPcmFixture.generate(definition,rate,channels);
            var snapshot=new LevelerAnalysisEngine().analyzeShadow(clip.pcm(),clip.format(),new CancellationToken());if(snapshot==null)throw new IllegalStateException("missing actual V2 snapshot");var cache=snapshot.cache();
            check(ComparisonV2Oracle.version(cache)==2,"actual V2 transpose identity");int a=mapping(clip,cache,definition.first()),b=mapping(clip,cache,definition.second());
            var first=cache.descriptors().get(a).range();var second=cache.descriptors().get(b).range();
            var observed=ComparisonV2Oracle.observe(cache,first,second);var full=(ComparisonV2Oracle.Comparison)observed.evidence();
            check(observed.central().rotation()==2,"frozen global transposition2");
            check(full.central().longValid()>0&&full.central().support()*5L>=4L*full.central().longValid(),"independent long rotation support >=80%");
            check(full.variants().size()==8,"all A03 endpoint views complete");
            List<String> differences=new ArrayList<>();var actual=ComparisonV2Oracle.product(cache,a,b);
            ComparisonV2Oracle.verifyProduct(cache,first,second,actual,observed,"A03 "+rate+"/"+channels,differences);
            for(String difference:differences)check(false,difference);
            check(differences.isEmpty(),"exact full-grid central/endpoint scores and harmonic penalty identity");
            System.out.println("A03_V2_IDENTITY fs="+rate+" channels="+channels+" H="+full.central().h()+" T="+full.central().t()+" A="+full.central().a()+" C="+full.central().c()+" rotation="+full.central().rotation()+" support="+full.central().support()+"/"+full.central().longValid()+" firstReference="+cache.referencePlan().targets().get(a).reason()+" secondReference="+cache.referencePlan().targets().get(b).reason());
    }
}
