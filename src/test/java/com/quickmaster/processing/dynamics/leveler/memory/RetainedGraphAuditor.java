package com.quickmaster.processing.dynamics.leveler.memory;

import java.lang.reflect.*;
import java.util.*;
import com.quickmaster.processing.dynamics.LevelerProcessor;
import com.quickmaster.processing.dynamics.SparseGainSchedule;
import com.quickmaster.processing.dynamics.leveler.model.*;
import static com.quickmaster.processing.dynamics.leveler.memory.RetainedGraphReport.*;

/** Identity BFS. Unknown objects are measured and traversed, never an implicit cut/default allow. */
public final class RetainedGraphAuditor
{
    public enum Control { NONE, ALLOW_UNKNOWN, OMIT_CACHE_ROOT, ZERO_TOTALS, OMIT_RECONCILIATION, ALLOW_ALIASES, OMIT_COMPARISON_ROOT }
    private record Pending(Object value,String path,String role,boolean forbidden,long source) { }
    private final RetentionWhitelistV1 whitelist;
    public RetainedGraphAuditor(RetentionWhitelistV1 whitelist) { this.whitelist=Objects.requireNonNull(whitelist); }
    public RetainedGraphReport audit(LevelerProcessor owner)
    {
        try
        {
            Field root=LevelerProcessor.class.getDeclaredField("shadowAnalysis");
            if (root.getModifiers()!=(Modifier.PRIVATE|Modifier.VOLATILE) || root.getType()!=ShadowAnalysisSnapshot.class)
                throw new IllegalStateException("ROOT_SCHEMA");
            root.setAccessible(true);
            return auditFixture((ShadowAnalysisSnapshot)root.get(owner), Control.NONE);
        }
        catch (ReflectiveOperationException ex) { throw new IllegalStateException("ROOT_UNAVAILABLE",ex); }
    }
    public RetainedGraphReport auditFixture(ShadowAnalysisSnapshot root, Control control)
    { return walk(root,null,Map.of(),control); }
    public RetainedGraphReport auditActive(LevelerProcessor owner,Control control) {
        if(!whitelist.active())throw new IllegalArgumentException("ACTIVE_SCHEMA_REQUIRED");
        return walk((ShadowAnalysisSnapshot)MemoryAccess.get(owner,"shadowAnalysis"),owner,Map.of(),control);
    }
    public RetainedGraphReport auditUnknownRoots(Map<String,Object> roots, Control control)
    { return walk(null,null,roots,control); }
    private RetainedGraphReport walk(ShadowAnalysisSnapshot root,LevelerProcessor owner,Map<String,Object> external,Control control)
    {
        MemoryAccess.probes();
        Map<String,Long> dimensions=root==null?new TreeMap<>():new TreeMap<>(dimensions(root));
        if(owner!=null) {
            Object schedule=MemoryAccess.get(MemoryAccess.get(owner,"published"),"schedule");
            dimensions.put("A",1L);dimensions.put("Z",schedule instanceof SparseGainSchedule?1L:0L);
            dimensions.put("T",schedule instanceof SparseGainSchedule s?(long)s.pieceCount():0L);
        }
        List<Node> nodes=new ArrayList<>(); List<Edge> edges=new ArrayList<>(); List<Violation> violations=new ArrayList<>();
        List<String> roots=new ArrayList<>(); IdentityHashMap<Object,Node> identities=new IdentityHashMap<>();
        Deque<Pending> queue=new ArrayDeque<>();
        if(owner!=null) {
            for(Field field:MemoryAccess.fields(owner.getClass()))if(!field.getType().isPrimitive())
                if(control!=Control.OMIT_CACHE_ROOT||!field.getName().equals("shadowAnalysis"))roots.add("owner."+field.getName());
            queue.add(new Pending(owner,"owner","activeOwner",false,0));
        } else if (root!=null) { roots.add("shadowAnalysis.result"); if(control!=Control.OMIT_CACHE_ROOT)roots.add("shadowAnalysis.cache"); roots.add("shadowAnalysis.diagnostics"); queue.add(new Pending(root,"shadowAnalysis","snapshot",false,0)); }
        external.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> { roots.add(e.getKey()); queue.add(new Pending(e.getValue(),e.getKey(),"",true,0)); });
        boolean complete=true; long total=0,accounted=0,unaccounted=0;
        while(!queue.isEmpty())
        {
            Pending p=queue.removeFirst();
            if(p.value==null) {
                if(whitelist.active()&&p.path.equals("owner.gainEnv"))edges.add(new Edge(p.source,0,p.path,true,false));
                else violations.add(new Violation("REQUIRED_NULL",p.path,"null is not an identity")); continue;
            }
            if(!p.forbidden && whitelist.shared(p.value,p.path)) { edges.add(new Edge(p.source,0,p.path,true,false)); continue; }
            Node seen=identities.get(p.value);
            if(seen!=null)
            {
                edges.add(new Edge(p.source,seen.ordinal(),p.path,false,true));
                if(control!=Control.ALLOW_ALIASES && !allowedAlias(seen,p))
                    violations.add(new Violation("UNLISTED_ALIAS",p.path,seen.path()));
                continue;
            }
            String error=classify(p,dimensions);
            if(p.role.equals("format")&&p.path.equals("owner.shadowAnalysis.cache.comparison.format"))error="COMPARISON_FORMAT_ALIAS_REQUIRED";
            if(control==Control.ALLOW_UNKNOWN && !error.isEmpty()) error="";
            boolean allowed=error.isEmpty();
            long size=ShadowReachabilityAgent.shallowSize(p.value);
            long ordinal=nodes.size()+1L; long length=p.value.getClass().isArray()?Array.getLength(p.value):-1L;
            Node node=new Node(ordinal,p.path,p.value.getClass().getName(),allowed?p.role:"UNKNOWN",length,size,allowed);
            identities.put(p.value,node); nodes.add(node);
            if(p.source!=0) edges.add(new Edge(p.source,ordinal,p.path,false,false));
            total=Math.addExact(total,size);
            if(allowed)accounted=Math.addExact(accounted,size); else { unaccounted=Math.addExact(unaccounted,size); violations.add(new Violation("MEMORY_OWNERSHIP_UNACCOUNTED",p.path,error)); }
            boolean forbidden=p.forbidden || !allowed;
            if(p.value.getClass().isArray())
            {
                if(!p.value.getClass().getComponentType().isPrimitive())
                    for(int i=0;i<Array.getLength(p.value);i++) queue.addLast(new Pending(Array.get(p.value,i),p.path+"["+i+"]",elementRole(p.role),forbidden,ordinal));
                continue;
            }
            List<Field> fields=MemoryAccess.fields(p.value.getClass());
            if(p.role.equals("snapshot")) fields.sort(Comparator.comparingInt(f -> List.of("result","cache","diagnostics").indexOf(f.getName())));
            for(Field field:fields)
            {
                if(field.getType().isPrimitive()) continue;
                if(control==Control.OMIT_CACHE_ROOT && p.role.equals("snapshot") && field.getName().equals("cache")) continue;
                if(control==Control.OMIT_CACHE_ROOT && p.role.equals("activeOwner") && field.getName().equals("shadowAnalysis")) continue;
                if(control==Control.OMIT_COMPARISON_ROOT && p.role.equals("cache") && field.getName().equals("comparison"))continue;
                String path=p.path+"."+field.getName();
                try
                {
                    field.setAccessible(true); Object next=field.get(p.value);
                    if(p.role.equals("cache")&&field.getName().equals("comparison")&&next==null&&whitelist.legacyComparisonTail(p.value))continue;
                    String role=childRole(p.role,field.getName());
                    if(p.role.equals("validityMasks")&&field.getName().equals("words")&&p.path.endsWith("shortTermValid"))role="shortTermWords";
                    queue.addLast(new Pending(next,path,role,forbidden,ordinal));
                }
                catch(ReflectiveOperationException|RuntimeException ex)
                { complete=false; violations.add(new Violation("REFERENCE_FIELD_INACCESSIBLE",path,ex.getClass().getName())); }
            }
        }
        if(control==Control.ZERO_TOTALS) total=accounted=unaccounted=0;
        return new RetainedGraphReport(whitelist.id(),whitelist.digest(),false,ShadowReachabilityAgent.present(),complete,List.copyOf(roots),
                List.copyOf(nodes),List.copyOf(edges),List.copyOf(violations),total,accounted,unaccounted,Map.copyOf(dimensions));
    }
    private String classify(Pending p,Map<String,Long> d)
    {
        if(p.forbidden) return "FORBIDDEN_EXTERNAL_OR_UNKNOWN_PARENT";
        if(p.role.isEmpty()) return "NO_CONTEXT_RULE";
        if(p.value instanceof String s)
        {
            boolean signalId=p.path.endsWith(".signalId");
            if(!p.role.equals("ownedStrings") || !(signalId?s.matches("[\\x20-\\x21\\x23-\\x5b\\x5d-\\x7e]{1,128}"):s.matches("[0-9a-f]{64}"))) return "OWNED_STRING_ROLE_OR_ASCII_CAP";
            if(((Byte)MemoryAccess.get(s,"coder"))!=0 || !(MemoryAccess.get(s,"value") instanceof byte[])) return "STRING_NOT_LATIN1";
            return "";
        }
        if(p.value instanceof BitSet b)
        {
            if(!p.role.equals("validityMasks")) return "BITSET_ROLE";
            long logical=d.get("M"); long[] words=(long[])MemoryAccess.get(b,"words");
            int used=(Integer)MemoryAccess.get(b,"wordsInUse"); int actual=words.length;
            while(actual>0&&words[actual-1]==0)actual--;
            if(words.length!=(logical+63)/64 || used!=actual || !(Boolean)MemoryAccess.get(b,"sizeIsSticky")
                    || b.length()>logical) return "BITSET_CAP";
            return "";
        }
        String expected=whitelist.rowOwner(p.role);
        if(!p.value.getClass().getName().equals(expected)) return "ROW_TYPE:"+p.role;
        if(p.value.getClass().isArray())
        {
            long cap=arrayCap(p.role,p.path,d);
            if(p.role.equals("ownedStringBytes")&&p.path.endsWith(".signalId.value")) {
                if(Array.getLength(p.value)<1||Array.getLength(p.value)>128)return "SIGNAL_ID_CAP";
            } else if(cap<0 || Array.getLength(p.value)!=cap) return "ARRAY_CAP:"+p.role;
            return "";
        }
        return whitelist.shapeError(p.value);
    }
    private static long arrayCap(String role,String path,Map<String,Long> d)
    {
        if(role.equals("fingerprint"))return 32;
        if(role.equals("frameChroma")||role.equals("binChroma"))return 12;
        if(role.equals("frameSpectral")||role.equals("binSpectral"))return 8;
        if(role.equals("momentaryPower"))return d.get("M");
        if(role.equals("shortTermLufs"))return d.get("Q");
        if(role.equals("momentaryWords")||role.equals("shortTermWords"))return (d.get("M")+63)/64;
        if(role.equals("sketchOuter"))return d.get("C");
        if(role.equals("sketchChannels"))return 2048;
        if(role.equals("weights"))return d.get("S");
        if(role.equals("ownedStringBytes"))return 64;
        if(role.equals("featureListBacking"))return d.get("N");
        if(Set.of("regionsBacking","descriptorsBacking","protectionsBacking","targetsBacking").contains(role))return d.get("S");
        if(role.equals("binsBacking"))return 32;
        if(role.equals("scoresBacking"))return d.get("P");
        if(role.equals("groupsBacking"))return d.get("G");
        if(role.equals("pairsBacking"))return d.get("R");
        if(role.equals("diagnosticEntriesBacking"))return d.get("D");
        if(role.equals("setsBacking"))return 2;
        if(role.equals("evidenceListsBacking"))return d.getOrDefault("E"+index(path),-1L);
        if(role.equals("groupOrdinals")||role.equals("groupQuality"))return d.getOrDefault("k"+index(path),-1L);
        if(role.equals("gainPieces"))return d.getOrDefault("T",-1L);
        if(role.equals("comparisonShortValues"))return Math.multiplyExact(52L,d.get("CS"));
        if(role.equals("comparisonLongValues"))return Math.multiplyExact(36L,d.get("CL"));
        if(role.equals("comparisonShortFlags"))return d.get("CS");
        if(role.equals("comparisonLongFlags"))return d.get("CL");
        return -1;
    }
    private static String childRole(String role,String field)
    {
        if(field.equals("elements"))return role+"Backing";
        if(field.equals("value")&&role.equals("ownedStrings"))return "ownedStringBytes";
        if(field.equals("words")&&role.equals("validityMasks"))return "momentaryWords";
        String key=role+"."+field;
        return switch(key)
        {
            case "activeOwner.shadowAnalysis"->"snapshot";case "activeOwner.published"->"publication";case "activeOwner.denseCursor"->"denseCursor";case "activeOwner.sparseCursor"->"sparseCursor";
            case "publication.schedule"->"sparseSchedule";case "sparseSchedule.pieces"->"gainPieces";
            case "snapshot.result"->"result"; case "snapshot.cache"->"cache"; case "snapshot.diagnostics"->"diagnostics";
            case "result.referencePlan","cache.referencePlan"->"oneAliasedReferencePlan";
            case "cache.format"->"format"; case "cache.pcmFingerprintSha256"->"fingerprint"; case "cache.loudness"->"loudness";
            case "cache.comparison"->"comparisonTimeline";case "comparisonTimeline.format"->"format";
            case "comparisonTimeline.shortValues"->"comparisonShortValues";case "comparisonTimeline.longValues"->"comparisonLongValues";
            case "comparisonTimeline.shortFlags"->"comparisonShortFlags";case "comparisonTimeline.longFlags"->"comparisonLongFlags";
            case "cache.features"->"features"; case "cache.layout"->"layout"; case "cache.descriptors"->"descriptors"; case "cache.protections"->"protections"; case "cache.similarity"->"matrix"; case "cache.grouping"->"grouping";
            case "diagnostics.entries"->"diagnosticEntries"; case "diagnostics.standardValidation"->"standardReport"; case "diagnostics.memoryCounters"->"counters";
            case "oneAliasedReferencePlan.targets"->"targets"; case "oneAliasedReferencePlan.weights"->"weights";
            case "targetObjects.segmentId","descriptorObjects.id","protectionDecisions.id"->"segmentIds";
            case "targetObjects.referenceLoudness"->"targetLoudness";
            case "loudness.integrated"->"integrated"; case "loudness.momentaryPower"->"momentaryPower"; case "loudness.shortTermLufs"->"shortTermLufs"; case "loudness.momentaryValid","loudness.shortTermValid"->"validityMasks";
            case "features.frames"->"featureList"; case "structuralFrames.chroma12"->"frameChroma"; case "structuralFrames.spectral8"->"frameSpectral";
            case "layout.regions"->"regions"; case "descriptorObjects.range"->"ranges"; case "descriptorObjects.bins"->"bins"; case "descriptorObjects.regionalLoudness"->"regionalLoudness"; case "descriptorObjects.context"->"contexts"; case "descriptorObjects.sketch"->"sketches";
            case "binObjects.chroma12"->"binChroma"; case "binObjects.spectral8"->"binSpectral"; case "sketches.values"->"sketchOuter";
            case "protectionDecisions.flags"->"protectionFlags"; case "matrix.scores"->"scores"; case "grouping.groups"->"groups"; case "grouping.pairs"->"pairs";
            case "standardReport.sets"->"sets"; case "twoSetReports.evidence"->"evidenceLists";
            case "standardReport.algorithmSha256","standardReport.profileSha256","standardReport.attestationSha256","standardReport.runnerSha256","twoSetReports.manifestSha256"->"ownedStrings";
            case "evidenceObjects.signalId","evidenceObjects.signalSha256"->"ownedStrings";
            case "groupObjects.memberOrdinals"->"groupOrdinals";case "groupObjects.memberQuality"->"groupQuality";
            default->"";
        };
    }
    private static String elementRole(String role)
    {
        return switch(role)
        {
            case "featureListBacking"->"structuralFrames"; case "regionsBacking"->"ranges"; case "descriptorsBacking"->"descriptorObjects"; case "protectionsBacking"->"protectionDecisions"; case "targetsBacking"->"targetObjects";
            case "gainPieces"->"gainPiece";
            case "binsBacking"->"binObjects"; case "scoresBacking"->"scoreObjects"; case "groupsBacking"->"groupObjects"; case "pairsBacking"->"pairObjects"; case "diagnosticEntriesBacking"->"diagnosticObjects"; case "setsBacking"->"twoSetReports"; case "evidenceListsBacking"->"evidenceObjects"; case "sketchOuter"->"sketchChannels";
            default->"";
        };
    }
    private static boolean allowedAlias(Node seen,Pending p)
    {
        if(seen.rule().equals("format")&&p.role.equals("format")) {
            Set<String> paths=Set.of("owner.shadowAnalysis.cache.format","owner.shadowAnalysis.cache.comparison.format");
            return paths.contains(seen.path())&&paths.contains(p.path());
        }
        if(seen.rule().equals("oneAliasedReferencePlan")&&p.role.equals("oneAliasedReferencePlan"))
            return Set.of("shadowAnalysis.result.referencePlan","shadowAnalysis.cache.referencePlan").contains(seen.path().replaceFirst("^owner\\.",""))
                    && Set.of("shadowAnalysis.result.referencePlan","shadowAnalysis.cache.referencePlan").contains(p.path.replaceFirst("^owner\\.",""));
        if(!seen.rule().equals(p.role)||!Set.of("segmentIds","ranges").contains(p.role))return false;
        return index(seen.path())==index(p.path()) && index(p.path())>=0;
    }
    private static int index(String path)
    { var matcher=java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(path); return matcher.find()?Integer.parseInt(matcher.group(1)):-1; }
    static Map<String,Long> dimensions(ShadowAnalysisSnapshot root)
    {
        var c=root.cache(); var v=root.diagnostics().standardValidation();
        long sr=c.format().sampleRateHz(),frames=c.format().frames(),s=c.descriptors().size(),e=0,j=0;
        for(int i=0;i<v.sets().size();i++){e=Math.addExact(e,v.sets().get(i).evidence().size());if(!v.sets().get(i).manifestSha256().isEmpty())j++;}
        long h=v.algorithmSha256().isEmpty()?0:4;
        long hop=Math.max(1,Math.round(.1*sr)), structural=Math.max(1,Math.round(.5*sr));
        Map<String,Long> d=new TreeMap<>();
        d.put("F",frames);d.put("M",frames==0?0:1+(frames-1)/hop);d.put("Q",d.get("M"));d.put("N",frames==0?0:1+(frames-1)/structural);d.put("S",s);d.put("P",Math.multiplyExact(s,s-1)/2);d.put("C",(long)c.format().channels());d.put("B",2048L);d.put("L",32L);d.put("d",22L);d.put("D",(long)root.diagnostics().entries().size());d.put("G",(long)c.grouping().groups().size());d.put("R",(long)c.grouping().pairs().size());d.put("E",e);d.put("J",j);d.put("H_b",h);d.put("U",Math.addExact(Math.addExact(h,j),Math.multiplyExact(2,e)));
        long members=2L*c.grouping().pairs().size();
        for(int i=0;i<c.grouping().groups().size();i++){long k=c.grouping().groups().get(i).size();d.put("k"+i,k);members=Math.addExact(members,k);}
        for(int i=0;i<v.sets().size();i++)d.put("E"+i,(long)v.sets().get(i).evidence().size());
        d.put("K",members);
        d.put("Fs",sr);d.put("CP",c.comparison()==null?0L:1L);
        long[] comparison=c.comparison()==null?new long[3]:RetentionReconciler.comparisonDimensions(frames,(int)sr);
        d.put("CS",comparison[0]);d.put("CL",comparison[1]);d.put("CB",comparison[2]);
        if(s<0||s>64||members>s||e>94||v.sets().size()!=2||j>2||d.get("D")>8*s+64)throw new IllegalArgumentException("DIMENSION_CAP");
        return d;
    }
}
