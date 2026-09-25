package com.quickmaster.processing.dynamics.leveler.memory;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.lang.reflect.*;

/** Historical class name, V2 data. Provisional input digest is NOT approval of a global whitelist. */
public final class RetentionWhitelistV1
{
    public static final String ID = "QM-M004-RETENTION-V2-CONFORMANCE";
    private final JsonObject schema;
    private final String digest;
    private String schemaId=ID;
    private JsonObject activeDelta;
    private String activeDigest;
    private final Map<String,JsonObject> classes = new TreeMap<>();
    private final Map<String,JsonObject> rows = new TreeMap<>();
    private final Set<String> owned = new HashSet<>();
    public RetentionWhitelistV1(Path candidate, String expectedDigest) throws Exception
    {
        byte[] bytes = Files.readAllBytes(candidate); digest = sha(bytes);
        if (!digest.equals(expectedDigest)) throw new IllegalArgumentException("WHITELIST_HASH_MISMATCH");
        schema = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (!ID.equals(schema.get("normativeSchemaId").getAsString())) throw new IllegalArgumentException("WHITELIST_ID_MISMATCH");
        if (!schema.getAsJsonObject("unknownRule").get("continueAfterUnknown").getAsBoolean()
                || schema.getAsJsonObject("externalBoundaries").get("shadowTraversalMayCutByType").getAsBoolean())
            throw new IllegalArgumentException("WHITELIST_ACCOUNTING_SEMANTICS");
        if (!schema.getAsJsonObject("dimensions").has("d") || !schema.getAsJsonObject("dimensions").has("D"))
            throw new IllegalArgumentException("DIMENSION_CASE_LOSS");
        for (JsonElement e : schema.getAsJsonArray("classes"))
        {
            JsonObject c = e.getAsJsonObject(); String name = c.get("owner").getAsString().replace('/', '.');
            if (classes.put(name,c) != null) throw new IllegalArgumentException("DUPLICATE_CLASS_RULE");
            if (c.get("ownership").getAsString().equals("owned_snapshot")) owned.add(name);
        }
        for (JsonElement e : schema.getAsJsonArray("retentionRows"))
        {
            JsonObject row=e.getAsJsonObject();
            if (rows.put(row.get("id").getAsString(),row) != null) throw new IllegalArgumentException("DUPLICATE_ROW_RULE");
        }
    }
    public String digest() { return activeDigest==null?digest:activeDigest; }
    public String id() { return schemaId; }
    public boolean active() { return activeDelta!=null; }
    public boolean contextAvailability() { return "QM-S001-ACTIVE-RETENTION-3-CONTEXT".equals(schemaId); }
    public boolean comparisonV2() { return "QM-S001-ACTIVE-RETENTION-2-COMPARISON".equals(schemaId)||contextAvailability(); }
    JsonObject activeCopy() { return activeDelta.deepCopy(); }
    public List<String> roots() { return active()?activeDelta.getAsJsonArray("roots").asList().stream().map(JsonElement::getAsString).toList():List.of("shadowAnalysis.result","shadowAnalysis.cache","shadowAnalysis.diagnostics"); }
    public RetentionWhitelistV1(Path candidate,String expectedDigest,Path successor,String successorDigest) throws Exception {
        this(candidate,expectedDigest);
        byte[] bytes=Files.readAllBytes(successor);
        if(!sha(bytes).equals(successorDigest))throw new IllegalArgumentException("ACTIVE_SCHEMA_HASH_MISMATCH");
        activeDigest=successorDigest;
        activeDelta=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if(!expectedDigest.equals(activeDelta.get("baseSha256").getAsString())||activeDelta.get("approvedGlobalSchema").getAsBoolean())throw new IllegalArgumentException("ACTIVE_SCHEMA_AUTHORITY");
        schemaId=activeDelta.get("normativeSchemaId").getAsString();
        if(!schemaId.equals("QM-S001-ACTIVE-RETENTION-1")&&!comparisonV2())throw new IllegalArgumentException("ACTIVE_SCHEMA_ID");
        if(comparisonV2()) {
            String parent=contextAvailability()?"comparisonParentSha256":"parentSha256",parentStatic=contextAvailability()?"comparisonParentStaticSha256":"parentStaticSha256";
            if(!activeDelta.get(parent).getAsString().equals("2795e24224149d8a4712326655459ce139307b69fddbe0acdd1a5be5ab84d06d")
                    ||!activeDelta.get(parentStatic).getAsString().equals("93a9a36c0fa09fc3ef0e0935b012817a13471102866a5aacc3b4fbe4098f31c5"))throw new IllegalArgumentException("COMPARISON_SCHEMA_PARENT");
            if(contextAvailability()&&(!activeDelta.get("parentSha256").getAsString().equals("dbd591636e5caca299371ddf26f816066b35e50dbb2c681510b23e6d44ea9968")
                    ||!activeDelta.get("parentStaticSha256").getAsString().equals("950c093f6e1dd5b0d03852211b3af28b01e89055956f04d0075e40769fa0ba2a")))throw new IllegalArgumentException("CONTEXT_SCHEMA_PARENT");
            JsonObject atoms=activeDelta.getAsJsonObject("comparisonAtoms");
            if(!atoms.get("algorithmId").getAsString().equals("QM-LEVELER-S001-COMPARISON-V2")||!atoms.get("profileId").getAsString().equals("QM-LEVELER-V2")
                    ||!atoms.get("historicalProfileId").getAsString().equals("QM-LEVELER-V1"))throw new IllegalArgumentException("COMPARISON_SCHEMA_ATOMS");
        }
        for(JsonElement element:activeDelta.getAsJsonArray("classes")) {
            JsonObject rule=element.getAsJsonObject();String name=rule.get("owner").getAsString().replace('/','.');classes.put(name,rule);owned.add(name);
        }
        for(var e:activeDelta.getAsJsonObject("enums").entrySet())schema.getAsJsonObject("enums").add(e.getKey(),e.getValue().deepCopy());
        for(JsonElement element:activeDelta.getAsJsonArray("retentionRows")) {
            JsonObject row=element.getAsJsonObject();String id=row.get("id").getAsString();
            if(rows.put(id,row)!=null)throw new IllegalArgumentException("DUPLICATE_ACTIVE_ROW");schema.getAsJsonArray("retentionRows").add(row.deepCopy());
        }
        for(var family:activeDelta.getAsJsonObject("formulaDelta").entrySet())for(var term:family.getValue().getAsJsonObject().entrySet()) {
            JsonObject polynomial=schema.getAsJsonObject("formulas").getAsJsonObject(family.getKey());
            long previous=polynomial.has(term.getKey())?polynomial.get(term.getKey()).getAsLong():0;
            polynomial.addProperty(term.getKey(),Math.addExact(previous,term.getValue().getAsLong()));
        }
    }
    public JsonObject semanticCopy() { return schema.deepCopy(); }
    JsonObject classRule(String name) { return classes.get(name); }
    Collection<JsonObject> classRules() { return List.copyOf(classes.values()); }
    Set<String> rowIds() { return Set.copyOf(rows.keySet()); }
    boolean ownedType(Class<?> type) { return owned.contains(type.getName()); }
    String rowOwner(String row)
    { JsonObject value=rows.get(row); return value==null ? "" : value.get("owner").getAsString().replace('/', '.'); }
    String shapeError(Object value)
    {
        Class<?> type = value.getClass(); JsonObject rule = classes.get(type.getName());
        if (rule == null || !ownedType(type)) return "TYPE_NOT_OWNED";
        if (!Modifier.isFinal(type.getModifiers())) return "CLASS_MODIFIERS";
        List<Field> actual = MemoryAccess.fields(type);
        // Explicit current-class compatibility for unchanged historical minimum V1 fixtures.
        if(legacyComparisonTail(value))actual.removeIf(f->f.getName().equals("comparison"));
        List<JsonObject> expected = rule.getAsJsonArray("fields").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(f -> (f.get("access").getAsInt() & Modifier.STATIC)==0).toList();
        if (actual.size()!=expected.size()) return "FIELD_COUNT";
        for (Field f : actual)
        {
            JsonObject match = expected.stream().filter(e -> e.get("name").getAsString().equals(f.getName())).findFirst().orElse(null);
            if (match==null || !match.get("descriptor").getAsString().equals(MemoryAccess.descriptor(f.getType()))
                    || match.get("access").getAsInt()!=f.getModifiers()) return "FIELD_SCHEMA";
        }
        if(contextAvailability()&&type.getName().equals("com.quickmaster.processing.dynamics.leveler.model.BodyContextVector")) {
            int mask=(Integer)MemoryAccess.get(value,"loudnessAvailabilityMask");
            if(mask<0||mask>7)return "CONTEXT_MASK_DOMAIN";
        }
        return "";
    }
    boolean legacyComparisonTail(Object value) {
        if(active()||!value.getClass().getName().equals("com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisCache"))return false;
        try {
            Field field=value.getClass().getDeclaredField("comparison");
            return field.getModifiers()==18&&field.getType().getName().equals("com.quickmaster.processing.dynamics.leveler.model.ComparisonTimeline")
                    &&MemoryAccess.get(value,"comparison")==null&&MemoryAccess.get(value,"algorithmId").equals("QM-LEVELER-SHADOW-M004-V1")
                    &&MemoryAccess.get(value,"profileId").equals("QM-LEVELER-V1");
        } catch(NoSuchFieldException historicalClass){return false;}
    }
    boolean shared(Object object, String path)
    {
        if(path.startsWith("owner."))path=path.substring(6);
        if(active()&&path.equals("published.schedule")&&object.getClass().getName().equals("com.quickmaster.processing.dynamics.DenseGainSchedule")) {
            Object unit=MemoryAccess.get(object.getClass(),"UNIT");
            if(object!=unit||Double.doubleToLongBits((Double)MemoryAccess.get(unit,"envRateHz"))!=Double.doubleToLongBits(1))return false;
            Object data=MemoryAccess.get(unit,"sampleEnv");return data instanceof float[] a&&a.length==1&&Float.floatToRawIntBits(a[0])==Float.floatToRawIntBits(1f);
        }
        if (object instanceof Enum<?> value)
        {
            JsonArray names=schema.getAsJsonObject("enums").getAsJsonArray(value.getDeclaringClass().getName().replace('.','/'));
            if (names==null || value.ordinal()>=names.size() || !names.get(value.ordinal()).getAsString().equals(value.name())) return false;
            try { Field constant=value.getDeclaringClass().getField(value.name());constant.setAccessible(true);return constant.get(null)==value; }
            catch (ReflectiveOperationException ex) { return false; }
        }
        if (!(object instanceof String s)) return false;
        if (s.isEmpty()) return s=="";
        Map<String,String> keys=Map.of("algorithmId","algorithmId","profileId","calibrationProfileId","requirementId","requirementId");
        String tail=path.substring(path.lastIndexOf('.')+1);
        if(comparisonV2()&&path.equals("shadowAnalysis.cache.profileId"))return s=="QM-LEVELER-V2";
        if (path.equals("shadowAnalysis.cache.algorithmId"))
        {
            JsonObject engine=classes.get("com.quickmaster.processing.dynamics.leveler.LevelerAnalysisEngine");
            for (JsonElement e:engine.getAsJsonArray("fields"))
                if(e.getAsJsonObject().get("name").getAsString().equals("ALGORITHM_ID"))
                    return s==e.getAsJsonObject().getAsJsonObject("constantValue").get("value").getAsString().intern();
            return false;
        }
        if (keys.containsKey(tail))
        {
            JsonElement expected=schema.getAsJsonObject("staticValues").get(keys.get(tail));
            return expected!=null && s==expected.getAsString().intern();
        }
        if (tail.equals("setId") || tail.equals("setVersion"))
        {
            String suffix=tail.equals("setId")?"SetId":"SetVersion";
            return s==schema.getAsJsonObject("staticValues").get("itu"+suffix).getAsString().intern()
                    || s==schema.getAsJsonObject("staticValues").get("ebu"+suffix).getAsString().intern();
        }
        return false;
    }
    static String sha(byte[] bytes) throws Exception
    { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
