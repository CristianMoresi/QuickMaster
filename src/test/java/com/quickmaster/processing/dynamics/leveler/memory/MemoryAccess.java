package com.quickmaster.processing.dynamics.leveler.memory;

import java.lang.reflect.*;
import java.util.*;
import com.google.gson.*;
import com.quickmaster.processing.dynamics.leveler.M004RuntimeGuardBridge;

/** Reflection is confined to this test auditor and explicit test mutations. */
final class MemoryAccess
{
    private MemoryAccess() { }
    static Object get(Object owner, String field)
    {
        try
        {
            Field f = field(owner instanceof Class<?> c ? c : owner.getClass(), field);
            f.setAccessible(true); return f.get(owner instanceof Class<?> ? null : owner);
        }
        catch (ReflectiveOperationException | RuntimeException ex)
        { throw new IllegalStateException("REFLECTION_UNAVAILABLE:" + field, ex); }
    }
    static Field field(Class<?> type, String name) throws NoSuchFieldException
    {
        for (Class<?> at = type; at != null; at = at.getSuperclass())
            for (Field f : at.getDeclaredFields()) if (f.getName().equals(name)) return f;
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    static List<Field> fields(Class<?> type)
    {
        List<Field> result = new ArrayList<>();
        for (Class<?> at = type; at != null; at = at.getSuperclass())
            for (Field f : at.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) result.add(f);
        result.sort(Comparator.comparing((Field f) -> f.getDeclaringClass().getName()).thenComparing(Field::getName));
        return result;
    }
    static void probes()
    {
        if (ShadowReachabilityAgent.shallowSize(new Object()) <= 0) throw new AssertionError("SIZE_PROBE");
        String ascii = "M004";
        if (!(get(ascii, "value") instanceof byte[]) || ((Byte)get(ascii, "coder")) != 0)
            throw new IllegalStateException("COMPACT_STRING_PROBE");
        for (String name : List.of("threadLocals", "inheritableThreadLocals")) get(Thread.currentThread(), name);
        BitSet bits = new BitSet(1);
        if (!(get(bits, "words") instanceof long[]) || !(get(bits, "wordsInUse") instanceof Integer)
                || !(get(bits, "sizeIsSticky") instanceof Boolean)) throw new IllegalStateException("BITSET_PROBE");
    }
    static String descriptor(Class<?> type) { return type.descriptorString().replace('.', '/'); }
    static JsonObject staticAudit(java.nio.file.Path classes,RetentionWhitelistV1 whitelist,boolean omitStaticScan) throws Exception {
        return staticAudit(classes,whitelist,omitStaticScan,false);
    }
    /** Fourth argument is a causal test-only deletion control, never the production audit path. */
    static JsonObject staticAudit(java.nio.file.Path classes,RetentionWhitelistV1 whitelist,boolean omitStaticScan,boolean omitEnumAndConstantReferents) throws Exception {
        JsonArray rows=new JsonArray(),measured=new JsonArray();List<String> failures=new ArrayList<>();
        JsonArray declarations=M004RuntimeGuardBridge.staticFields(classes);
        IdentityHashMap<Object,Boolean> seen=new IdentityHashMap<>();long total=0,unaccounted=0;
        Set<String> permitted=new HashSet<>();for(JsonElement e:whitelist.activeCopy().getAsJsonArray("sharedStaticRoots"))permitted.add(e.getAsString());
        if(!omitStaticScan)for(JsonElement e:declarations) {
            int priorFailures=failures.size();JsonObject declaration=e.getAsJsonObject();String owner=declaration.get("owner").getAsString(),name=declaration.get("name").getAsString(),path=owner+"."+name;
            Class<?> type=Class.forName(owner.replace('/','.'));Field field=type.getDeclaredField(name);field.setAccessible(true);Object value=field.get(null);
            JsonObject row=declaration.deepCopy();rows.add(row);
            if(field.getModifiers()!=declaration.get("access").getAsInt()||!descriptor(field.getType()).equals(declaration.get("descriptor").getAsString()))failures.add("STATIC_FIELD_SCHEMA:"+path);
            if(declaration.has("constant")) {
                String actual=constantValue(value,field.getType());row.addProperty("observedConstant",actual);
                if(!actual.equals(declaration.get("constant").getAsString()))failures.add("STATIC_CONSTANT_CHANGED:"+path);
                // A ConstantValue String is still a reference root. Only primitive values have no referents.
                if(field.getType().isPrimitive()||omitEnumAndConstantReferents)continue;
            }
            List<String> enumNames=M004RuntimeGuardBridge.activeEnumNames(owner);
            if(declaration.has("constant")) {
                if(field.getType()!=String.class)failures.add("STATIC_REFERENCE_CONSTANT_TYPE:"+path);
            } else if(enumNames!=null) {
                if(name.equals("$VALUES")) {
                    if(value==null||!value.getClass().isArray()||Array.getLength(value)!=enumNames.size())failures.add("STATIC_ENUM_ARRAY:"+path);
                    else for(int i=0;i<enumNames.size();i++)if(Array.get(value,i)!=get(type,enumNames.get(i)))failures.add("STATIC_ENUM_IDENTITY:"+path);
                } else if(!enumNames.contains(name)||!(value instanceof Enum<?> item)||item.ordinal()!=enumNames.indexOf(name)||!item.name().equals(name)||item.getDeclaringClass()!=type)failures.add("STATIC_ENUM_CONSTANT:"+path);
            } else if(!permitted.contains(path))failures.add("UNKNOWN_STATIC_ROOT:"+path);
            else try { validateStaticAtom(path,value,whitelist); } catch(AssertionError|RuntimeException ex) {failures.add("STATIC_ATOM_CHANGED:"+path+":"+ex.getMessage());}
            // Enumerate every reachable shared identity as evidence too; unknown/inaccessible subtrees are never accepted.
            boolean accounted=failures.size()==priorFailures;Deque<Object[]> queue=new ArrayDeque<>();queue.add(new Object[]{value,path});
            while(!queue.isEmpty()) {
                Object[] pending=queue.removeFirst();Object object=pending[0];String at=(String)pending[1];
                if(object==null){failures.add("STATIC_REQUIRED_NULL:"+at);continue;}
                if(seen.put(object,Boolean.TRUE)!=null)continue;
                long size=ShadowReachabilityAgent.shallowSize(object);total=Math.addExact(total,size);if(!accounted)unaccounted=Math.addExact(unaccounted,size);
                JsonObject n=new JsonObject();n.addProperty("path",at);n.addProperty("type",object.getClass().getName());n.addProperty("shallowBytes",size);n.addProperty("accounted",accounted);n.addProperty("length",object.getClass().isArray()?Array.getLength(object):-1);measured.add(n);
                if(object instanceof Enum<?> item) {
                    List<String> names=M004RuntimeGuardBridge.activeEnumNames(item.getDeclaringClass().getName().replace('.','/'));
                    if(names==null||item.ordinal()>=names.size()||!item.name().equals(names.get(item.ordinal()))||get(item.getDeclaringClass(),item.name())!=item)failures.add("UNKNOWN_SHARED_ENUM:"+at);
                    // Enum.name is inherited reference state; validated enum identity is not a traversal cut.
                    if(omitEnumAndConstantReferents)continue;
                }
                if(object instanceof String s) {
                    if(s.length()>128||((Byte)get(s,"coder"))!=0)failures.add("STATIC_STRING_CAP:"+at);
                    queue.add(new Object[]{get(s,"value"),at+".value"});continue;
                }
                if(object.getClass().isArray()) {
                    int count=Array.getLength(object);
                    if(count>128)failures.add("STATIC_ARRAY_CAP:"+at);
                    if(!object.getClass().getComponentType().isPrimitive())for(int i=0;i<count;i++)queue.add(new Object[]{Array.get(object,i),at+"["+i+"]"});
                    continue;
                }
                for(Field ref:fields(object.getClass()))if(!ref.getType().isPrimitive())try{ref.setAccessible(true);queue.add(new Object[]{ref.get(object),at+"."+ref.getName()});}
                catch(ReflectiveOperationException|RuntimeException ex){failures.add("STATIC_REFERENCE_INACCESSIBLE:"+at+"."+ref.getName());}
            }
        }
        JsonObject result=new JsonObject();result.add("owners",new Gson().toJsonTree(M004RuntimeGuardBridge.activeOwners()));result.add("fields",rows);result.add("sharedNodes",measured);
        result.addProperty("expectedFieldCount",declarations.size());result.addProperty("inspectedFieldCount",rows.size());result.addProperty("sharedShallowBytes",total);result.addProperty("accountedSharedBytes",total-unaccounted);result.addProperty("unaccountedStaticBytes",unaccounted);result.add("violations",new Gson().toJsonTree(failures));
        result.addProperty("scanOmitted",omitStaticScan);result.addProperty("referentsOmitted",omitEnumAndConstantReferents);result.addProperty("passed",!omitStaticScan&&!omitEnumAndConstantReferents&&rows.size()==declarations.size()&&failures.isEmpty());return result;
    }
    private static String constantValue(Object value,Class<?> type) {
        if(type==double.class)return "D:"+Long.toUnsignedString(Double.doubleToRawLongBits((Double)value),16);
        if(type==float.class)return "F:"+Integer.toUnsignedString(Float.floatToRawIntBits((Float)value),16);
        if(type==long.class)return "J:"+value;
        if(type==String.class)return "S:"+value;
        if(type==boolean.class)return "I:"+((Boolean)value?1:0);
        if(type==char.class)return "I:"+(int)(Character)value;
        return "I:"+value;
    }
    private static void validateStaticAtom(String path,Object value,RetentionWhitelistV1 whitelist) {
        if(value==null)throw new AssertionError("NULL");JsonObject fixed=whitelist.semanticCopy().getAsJsonObject("staticValues");
        if(path.endsWith(".ANNEX2")) {
            JsonArray expected=whitelist.activeCopy().getAsJsonArray("annex2");if(!(value instanceof double[][] actual)||actual.length!=4)throw new AssertionError("ANNEX_ROWS");
            for(int p=0;p<4;p++){if(actual[p]==null||actual[p].length!=12)throw new AssertionError("ANNEX_COLUMNS");for(int t=0;t<12;t++)if(Double.doubleToRawLongBits(actual[p][t])!=Double.doubleToRawLongBits(expected.get(p).getAsJsonArray().get(t).getAsDouble()))throw new AssertionError("ANNEX_VALUE");}return;
        }
        if(path.endsWith("DenseGainSchedule.UNIT")) {
            if(!((Double)get(value,"envRateHz")).equals(1d)||!(get(value,"sampleEnv") instanceof float[] samples)||samples.length!=1||Float.floatToRawIntBits(samples[0])!=Float.floatToRawIntBits(1f))throw new AssertionError("DENSE_UNIT");return;
        }
        if(path.endsWith("LevelerCalibrationProfile.V1")) {if(!get(value,"profileId").equals(fixed.get("calibrationProfileId").getAsString())||fields(value.getClass()).size()!=1)throw new AssertionError("PROFILE_ID");return;}
        if(path.endsWith("LevelerCalibrationProfile.V2")&&whitelist.comparisonV2()) {
            if(!get(value,"profileId").equals(whitelist.activeCopy().getAsJsonObject("comparisonAtoms").get("profileId").getAsString())||fields(value.getClass()).size()!=1
                    ||value==get(value.getClass(),"V1"))throw new AssertionError("PROFILE_V2_ID");return;
        }
        if(path.endsWith("LoudnessStandard.BS1770_5")) {if(!get(value,"standardId").equals("BS1770-5")||fields(value.getClass()).size()!=1)throw new AssertionError("STANDARD_ID");return;}
        if(!path.endsWith("ConformanceRequirement.OFFICIAL_LOUDNESS_V1"))throw new AssertionError("UNKNOWN_ATOM");
        List<Field> fields=fields(value.getClass());if(fields.size()!=9)throw new AssertionError("REQUIREMENT_FIELDS");
        for(Field field:fields)if(!field.getName().equals("requiredReadings"))if(!get(value,field.getName()).equals(fixed.get(field.getName()).getAsString()))throw new AssertionError("REQUIREMENT_VALUE:"+field.getName());
        Object[] readings=(Object[])get(get(value,"requiredReadings"),"elements");JsonArray expected=fixed.getAsJsonArray("requiredReadings");
        if(readings.length!=94||expected.size()!=94)throw new AssertionError("REQUIREMENT_COUNT");
        for(int i=0;i<94;i++) {
            JsonObject normative=expected.get(i).getAsJsonObject();Object reading=readings[i];
            if(reading==null||fields(reading.getClass()).size()!=normative.size())throw new AssertionError("READING_FIELDS");
            for(var entry:normative.entrySet()) {
                Object actual=get(reading,entry.getKey());String rendered=actual instanceof Double number?String.format(java.util.Locale.ROOT,"%016x",Double.doubleToRawLongBits(number)):actual instanceof Enum<?> item?item.name():actual.toString();
                if(!rendered.equals(entry.getValue().getAsString()))throw new AssertionError("READING_VALUE:"+i+":"+entry.getKey());
            }
        }
    }
}
