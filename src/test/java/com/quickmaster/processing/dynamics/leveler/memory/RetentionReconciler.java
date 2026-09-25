package com.quickmaster.processing.dynamics.leveler.memory;

import java.util.*;
import com.google.gson.*;
import static com.quickmaster.processing.dynamics.leveler.memory.RetainedGraphReport.*;

/** Parent-side reconstruction from rows; never trusts self-reported zero totals or counters. */
public final class RetentionReconciler
{
    private RetentionReconciler() { }
    public static long[] comparisonDimensions(long frames,int sourceRate) {
        if(frames<0||sourceRate<40)throw new IllegalArgumentException("COMPARISON_SOURCE_DIMENSION");
        long shortCount=Math.addExact(Math.multiplyExact(40L,frames),sourceRate-1L)/sourceRate;
        long longCount=Math.addExact(Math.multiplyExact(8L,frames),sourceRate-1L)/sourceRate;
        Math.toIntExact(Math.multiplyExact(52L,shortCount));Math.toIntExact(Math.multiplyExact(36L,longCount));
        return new long[]{shortCount,longCount,Math.addExact(Math.multiplyExact(105L,shortCount),Math.multiplyExact(73L,longCount))};
    }
    public static void verify(RetainedGraphReport report,RetentionWhitelistV1 whitelist,boolean requireGraph)
    {
        if(!report.agentPresent())throw new AssertionError("AGENT_MISSING");
        if(!report.candidateSha256().equals(whitelist.digest()) || !whitelist.id().equals(report.normativeSchemaId())||report.approvedGlobalSchema())
            throw new AssertionError("SCHEMA_BINDING");
        long total=0,allowed=0,unknown=0,arrays=0,ordinal=0;
        Set<String> paths=new HashSet<>();
        for(Node row:report.nodes())
        {
            if(row.ordinal()!=++ordinal || row.shallowBytes()<=0 || !paths.add(row.path()))throw new AssertionError("ROW_IDENTITY");
            total=Math.addExact(total,row.shallowBytes());
            if(row.accounted())allowed=Math.addExact(allowed,row.shallowBytes());else unknown=Math.addExact(unknown,row.shallowBytes());
            if(row.length()>=0)arrays++;
        }
        if(total!=Math.addExact(allowed,unknown) || total!=report.totalRetainedBytes()
                || allowed!=report.accountedAllowedBytes() || unknown!=report.unaccountedRetainedBytes())
            throw new AssertionError("BYTE_RECONCILIATION");
        if(!report.traversalComplete())throw new AssertionError("TRAVERSAL_INCOMPLETE");
        Set<String> edgePaths=new HashSet<>();Set<Long> incoming=new HashSet<>();
        for(Edge edge:report.edges()) {
            if(edge.sourceOrdinal()<0||edge.sourceOrdinal()>ordinal||edge.targetOrdinal()<0||edge.targetOrdinal()>ordinal||!edgePaths.add(edge.path())
                    ||edge.shared()&&edge.targetOrdinal()!=0||!edge.shared()&&edge.targetOrdinal()==0)throw new AssertionError("EDGE_IDENTITY");
            if(!edge.shared()&&!edge.repeated()&&!incoming.add(edge.targetOrdinal()))throw new AssertionError("MULTIPLE_FIRST_EDGES");
        }
        if(requireGraph)
        {
            if(!report.inspectedRoots().equals(whitelist.roots()))
                throw new AssertionError("ROOT_OMITTED");
            if(unknown!=0 || !report.violations().isEmpty())throw new AssertionError("MEMORY_OWNERSHIP_UNACCOUNTED:"+report.violations());
            Map<String,Long> d=report.dimensions();
            if(d.get("F")<=0||d.get("S")<0||d.get("S")>64||d.get("P")!=Math.multiplyExact(d.get("S"),d.get("S")-1)/2||d.get("E")>94
                    ||d.get("E")!=Math.addExact(d.get("E0"),d.get("E1"))||d.get("J")>2||d.get("D")>8*d.get("S")+64
                    ||d.get("K")>d.get("S")||d.get("B")!=2048||d.get("L")!=32||d.get("d")!=22
                    ||d.get("U")!=d.get("H_b")+d.get("J")+2*d.get("E")||!(d.get("H_b")==0||d.get("H_b")==4))throw new AssertionError("DIMENSION_CAP");
            if(whitelist.active()&&(d.get("A")!=1||d.get("Z")<0||d.get("Z")>1||d.get("T")<0||d.get("T")>258||d.get("Z")==0&&d.get("T")!=0))throw new AssertionError("ACTIVE_DIMENSION_CAP");
            if(whitelist.comparisonV2()) {
                long[] c=comparisonDimensions(d.get("F"),Math.toIntExact(d.get("Fs")));
                if(d.get("CP")!=1||d.get("CS")!=c[0]||d.get("CL")!=c[1]||d.get("CB")!=c[2])throw new AssertionError("COMPARISON_DIMENSION_CAP");
                long payload=0;
                for(Node row:report.nodes())if(row.rule().startsWith("comparison")&&row.length()>=0) {
                    long expected=switch(row.rule()){case "comparisonShortValues"->Math.multiplyExact(52L,c[0]);case "comparisonLongValues"->Math.multiplyExact(36L,c[1]);case "comparisonShortFlags"->c[0];case "comparisonLongFlags"->c[1];default->throw new AssertionError("COMPARISON_ARRAY_RULE");};
                    if(row.length()!=expected)throw new AssertionError("COMPARISON_ARRAY_LENGTH");
                    payload=Math.addExact(payload,Math.multiplyExact(row.length(),row.type().equals("[S")?2L:1L));
                }
                if(payload!=c[2])throw new AssertionError("COMPARISON_PAYLOAD_TOTAL");
            }
            long members=2*d.get("R");for(int i=0;i<d.get("G");i++){long k=d.get("k"+i);if(k<3)throw new AssertionError("GROUP_CAP");members=Math.addExact(members,k);}if(members!=d.get("K"))throw new AssertionError("GROUP_MEMBERSHIP_TOTAL");
            for(Node row:report.nodes()) {
                if(!row.type().equals(whitelist.rowOwner(row.rule())))throw new AssertionError("ROW_TYPE");
                if(row.ordinal()!=1&&!incoming.contains(row.ordinal()))throw new AssertionError("UNREACHABLE_ROW");
            }
            JsonObject schema=whitelist.semanticCopy(); long expectedNodes=0,expectedArrays=0;
            Map<String,Long> observed=new HashMap<>();
            for(Node row:report.nodes())observed.merge(row.rule(),1L,Math::addExact);
            for(JsonElement e:schema.getAsJsonArray("retentionRows"))
            {
                JsonObject row=e.getAsJsonObject(); long expected=product(row.get("quantity").getAsString(),report.dimensions());
                String id=row.get("id").getAsString();
                if(observed.getOrDefault(id,0L)!=expected)throw new AssertionError("ROW_CARDINALITY:"+id+":"+observed.getOrDefault(id,0L)+"!="+expected);
                expectedNodes=Math.addExact(expectedNodes,expected);
                if(row.get("array").getAsBoolean())expectedArrays=Math.addExact(expectedArrays,expected);
            }
            JsonObject formula=schema.getAsJsonObject("formulas");
            if(expectedNodes!=sum(formula.getAsJsonObject("identities"),report.dimensions()) || expectedArrays!=sum(formula.getAsJsonObject("arrays"),report.dimensions()))
                throw new AssertionError("FORMULA_DERIVATION");
            if(report.nodes().size()!=expectedNodes || arrays!=expectedArrays
                    || report.edges().size()!=sum(formula.getAsJsonObject("edges"),report.dimensions()))throw new AssertionError("GRAPH_CARDINALITY");
        }
    }
    private static long product(String term,Map<String,Long> dimensions)
    { long value=1; for(String part:term.split("\\*"))value=Math.multiplyExact(value,part.matches("[0-9]+")?Long.parseLong(part):Objects.requireNonNull(dimensions.get(part),part));return value; }
    private static long sum(JsonObject expression,Map<String,Long> dimensions)
    { long total=0;for(var e:expression.entrySet())total=Math.addExact(total,Math.multiplyExact(e.getValue().getAsLong(),product(e.getKey(),dimensions)));return total; }
}
