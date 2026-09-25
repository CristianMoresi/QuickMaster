package com.quickmaster.processing.dynamics.leveler;

import com.google.gson.*;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit ADR013/014 and named S-001 successors; never grants from observed candidate bytes. */
final class ActiveLevelerGuardContract
{
    static final String D = "com/quickmaster/processing/dynamics/", L = D + "leveler/", M = L + "model/";
    private static final class Active {
        static final JsonObject DELTA = resource("active-schema-delta.json", "0202bcd4b0ab263c14b78b1e035cb3f2361a077d69d11ab777d8f0b5aeea0e00");
    }

    static JsonObject schema(String owner) { return Active.DELTA.getAsJsonObject("classes").getAsJsonObject(owner); }
    static List<String> enumNames(String owner) {
        JsonArray names = Active.DELTA.getAsJsonObject("enums").getAsJsonArray(owner);
        return names == null ? M004StaticClosureContract.ENUMS.get(owner) : M004StaticClosureContract.strings(names);
    }
    static boolean isEnum(String owner) { return enumNames(owner) != null; }
    static Set<String> owners() {
        Set<String> names = new TreeSet<>(M004BytecodePolicy.SCANNED_M004_CLASSFILES);
        names.addAll(M004BytecodePolicy.BOUNDARIES.keySet());
        names.addAll(Active.DELTA.getAsJsonObject("classes").keySet());
        names.addAll(Active.DELTA.getAsJsonObject("enums").keySet());
        return Set.copyOf(names);
    }
    static Map<String,String> boundaries() {
        Map<String,String> result = new TreeMap<>(M004BytecodePolicy.BOUNDARIES);
        result.keySet().removeAll(Active.DELTA.getAsJsonObject("classes").keySet());
        result.keySet().removeAll(Active.DELTA.getAsJsonObject("enums").keySet());
        return Map.copyOf(result);
    }
    static List<M004BytecodePolicy.FieldRule> fields(String owner) {
        if (Active.DELTA.getAsJsonObject("enums").has(owner)) {
            List<M004BytecodePolicy.FieldRule> fields = new ArrayList<>();
            for (String n : enumNames(owner)) fields.add(new M004BytecodePolicy.FieldRule(n,"L"+owner+";",0x4019));
            fields.add(new M004BytecodePolicy.FieldRule("$VALUES","[L"+owner+";",0x101a));
            return List.copyOf(fields);
        }
        JsonObject s = schema(owner);
        if(s==null)return M004BytecodePolicy.FIELDS.get(owner);
        List<M004BytecodePolicy.FieldRule> fields = new ArrayList<>();
        for(JsonElement e:s.getAsJsonArray("fields")) {
            JsonObject f=e.getAsJsonObject();fields.add(new M004BytecodePolicy.FieldRule(f.get("name").getAsString(),f.get("descriptor").getAsString(),f.get("access").getAsInt()));
        }
        return List.copyOf(fields);
    }
    static boolean abstractMethod(M004Classfile f,M004Classfile.Method m) {
        return M004StaticClosureContract.abstractMethod(f,m) || f.owner.equals(D+"GainSchedule")&&f.access==1536&&m.access()==1025&&m.code()==null
                && Set.of("domain()L"+D+"GainDomain;","sourceFrames()J").contains(m.name()+m.descriptor());
    }
    static boolean shape(M004Classfile f) {
        JsonObject s=schema(f.owner);
        if(s==null&&isEnum(f.owner))return f.access==(f.owner.startsWith(D)&&!f.owner.startsWith(L)?16432:16433)&&f.superclass.equals("java/lang/Enum")&&f.interfaces.isEmpty();
        if(s==null)return false;
        return f.access==s.get("access").getAsInt()&&f.superclass.equals(s.get("superclass").getAsString())
                &&f.interfaces.equals(M004StaticClosureContract.strings(s.getAsJsonArray("interfaces")))
                &&!f.owner.contains("$")&&!f.classAttributes.contains("EnclosingMethod")
                &&f.classAttributes.contains("Record")==f.owner.equals(D+"PublishedGain");
    }
    static boolean additionalType(M004Classfile f,String owner) {
        if(f.owner.equals(D+"PublishedGain") && recordProtocol(f) && Set.of("java/lang/Record","java/lang/runtime/ObjectMethods","java/lang/invoke/TypeDescriptor","java/lang/invoke/MethodHandle").contains(owner))return true;
        for(JsonElement e:Active.DELTA.getAsJsonArray("extraCalls")) {
            JsonObject c=e.getAsJsonObject();
            if(c.get("caller").getAsString().equals(f.owner)&&c.get("owner").getAsString().equals(owner))return true;
        }
        return f.owner.equals(L+"RampAllocator")&&owner.equals("java/util/Collection");
    }
    static boolean additionalCall(M004Classfile f,M004Classfile.Method m,M004BytecodePolicy.Call call) {
        JsonArray context=Active.DELTA.getAsJsonObject("contextCalls").getAsJsonArray(f.owner+"."+m.name()+m.descriptor());
        if(context!=null&&M004StaticClosureContract.strings(context).contains(Integer.toHexString(call.opcode())+":"+call.owner()+"."+call.name()+call.descriptor()))return true;
        JsonArray comparison=Active.DELTA.getAsJsonObject("comparisonCalls").getAsJsonArray(f.owner+"."+m.name()+m.descriptor());
        if(comparison!=null&&M004StaticClosureContract.strings(comparison).contains(Integer.toHexString(call.opcode())+":"+call.owner()+"."+call.name()+call.descriptor()))return true;
        if(f.owner.equals(D+"LevelerProcessor")&&m.name().equals("fingerprint")&&levelerThrowable(f)
                &&call.equals(new M004BytecodePolicy.Call(183,"java/lang/IllegalStateException","<init>","(Ljava/lang/String;Ljava/lang/Throwable;)V")))return true;
        for(JsonElement e:Active.DELTA.getAsJsonArray("extraCalls")) {
            JsonObject c=e.getAsJsonObject();
            if(c.get("caller").getAsString().equals(f.owner)&&c.get("method").getAsString().equals(m.name())
                    &&call.opcode()==c.get("opcode").getAsInt()&&call.owner().equals(c.get("owner").getAsString())
                    &&call.name().equals(c.get("name").getAsString())&&call.descriptor().equals(c.get("descriptor").getAsString()))return true;
        }
        return false;
    }
    static boolean annexStore(M004Classfile f,M004Classfile.Method m,M004Classfile.Member ref) {
        return f.owner.equals(L+"FiniteTruePeakStream")&&m.name().equals("<clinit>")&&m.descriptor().equals("()V")
                &&ref.equals(new M004Classfile.Member(f.owner,"ANNEX2","[[D"))&&annex(f);
    }
    static void activeRules(M004Classfile f,Set<AsyncEscapeBytecodeGuard.Rule> removed,List<AsyncEscapeBytecodeGuard.Violation> out) {
        JsonObject s=schema(f.owner);
        if(!removed.contains(AsyncEscapeBytecodeGuard.Rule.CLASS_SHAPE)&&(s!=null||Active.DELTA.getAsJsonObject("enums").has(f.owner))&&!shape(f))
            fail(out,"STATIC_CLASS_SHAPE",f,"Exact active superclass/interfaces/access required");
        if(s!=null&&!removed.contains(AsyncEscapeBytecodeGuard.Rule.DIRECTED_API)) {
            Map<String,Integer> methods=new TreeMap<>();
            for(JsonElement e:s.getAsJsonArray("methods")){JsonObject m=e.getAsJsonObject();methods.put(m.get("name").getAsString()+m.get("descriptor").getAsString(),m.get("access").getAsInt());}
            if(f.methods.size()!=methods.size()||f.methods.stream().anyMatch(m->!Objects.equals(methods.get(m.name()+m.descriptor()),m.access())))
                fail(out,"ACTIVE_METHOD_TABLE",f,"Exact active declaration table required");
        }
        if(s!=null&&!removed.contains(AsyncEscapeBytecodeGuard.Rule.CONSTANT_VALUES)) {
            for(JsonElement e:s.getAsJsonArray("fields")) {
                JsonObject r=e.getAsJsonObject();var fields=f.fields.stream().filter(x->x.name().equals(r.get("name").getAsString())).toList();
                if(fields.size()!=1)continue;var field=fields.get(0);String expected=r.has("constant")?r.get("constant").getAsString():"absent";
                String actual="absent";
                if(field.constantIndex()!=0){var cp=f.pool[field.constantIndex()];actual=switch(cp.tag()){case 3->"I:"+((Long)cp.value()).intValue();case 6->"D:"+Long.toUnsignedString((Long)cp.value(),16);case 8->"S:"+f.string(field.constantIndex());default->"invalid";};}
                if(!expected.equals(actual))fail(out,"STATIC_CONSTANT_VALUE",f,field.name());
            }
        }
        if(!removed.contains(AsyncEscapeBytecodeGuard.Rule.DIRECTED_API))sensitiveCode(f,out);
        if(!removed.contains(AsyncEscapeBytecodeGuard.Rule.ACTIVE_PUBLICATION_AUTHORITY))publicationAuthority(f,out);
        if(!removed.contains(AsyncEscapeBytecodeGuard.Rule.ACTIVE_COMPARISON_CALLS)){comparisonCalls(f,out);contextCalls(f,out);}
    }
    private static void contextCalls(M004Classfile f,List<AsyncEscapeBytecodeGuard.Violation> out) {
        for(var method:f.methods) {
            JsonArray rule=Active.DELTA.getAsJsonObject("contextCalls").getAsJsonArray(f.owner+"."+method.name()+method.descriptor());
            if(rule==null)continue; // Unchanged methods remain governed by the historical rules.
            Set<String> actual=new TreeSet<>();
            if(method.code()!=null)for(var ins:method.code().instructions())if(ins.opcode()>=182&&ins.opcode()<=185) {
                var ref=f.member(ins.operand());actual.add(Integer.toHexString(ins.opcode())+":"+ref.owner()+"."+ref.name()+ref.descriptor());
            }
            if(!actual.equals(new TreeSet<>(M004StaticClosureContract.strings(rule))))
                fail(out,"ACTIVE_CONTEXT_CALL_TABLE",f,method.name()+method.descriptor()+" expected="+rule+" actual="+actual);
        }
        if(f.owner.equals(M+"BodyContextVector")&&!M004StaticClosurePolicy.exact(f,"loudnessAvailabilityMask","()I",1,
                List.of("2a","b4:"+f.owner+".loudnessAvailabilityMaskI","ac")))fail(out,"ACTIVE_CONTEXT_MASK_GETTER",f,"Exact scalar mask read required");
    }
    private static void comparisonCalls(M004Classfile f,List<AsyncEscapeBytecodeGuard.Violation> out) {
        if(!Set.of(M+"ComparisonTimeline",L+"ComparisonFeatureExtractor",L+"ComparisonComparator").contains(f.owner))return;
        for(var method:f.methods) {
            JsonArray rule=Active.DELTA.getAsJsonObject("comparisonCalls").getAsJsonArray(f.owner+"."+method.name()+method.descriptor());
            Set<String> actual=new TreeSet<>();
            if(method.code()!=null)for(var ins:method.code().instructions())if(ins.opcode()>=182&&ins.opcode()<=185) {
                var ref=f.member(ins.operand());actual.add(Integer.toHexString(ins.opcode())+":"+ref.owner()+"."+ref.name()+ref.descriptor());
            }
            if(rule==null||!actual.equals(new TreeSet<>(M004StaticClosureContract.strings(rule))))
                fail(out,"ACTIVE_COMPARISON_CALL_TABLE",f,method.name()+method.descriptor()+" expected="+rule+" actual="+actual);
        }
    }
    static boolean fixedProfileV2(M004Classfile f) {
        if(!f.owner.equals(L+"LevelerCalibrationProfile"))return false;
        String owner=f.owner;
        List<String> initializer=new ArrayList<>();
        for(String name:List.of("V1","V2"))initializer.addAll(List.of("bb:"+owner,"59","S:QM-LEVELER-"+name,
                "b7:"+owner+".<init>(Ljava/lang/String;)V","b3:"+owner+"."+name+"L"+owner+";"));
        initializer.add("b1");
        boolean valid=M004StaticClosurePolicy.exact(f,"<clinit>","()V",8,initializer)
                &&M004StaticClosurePolicy.exact(f,"<init>","(Ljava/lang/String;)V",2,List.of("2a","b7:java/lang/Object.<init>()V","2a","2b","b5:"+owner+".profileIdLjava/lang/String;","b1"))
                &&M004StaticClosurePolicy.exact(f,"profileId","()Ljava/lang/String;",1,List.of("2a","b4:"+owner+".profileIdLjava/lang/String;","b0"))
                &&M004StaticClosurePolicy.exact(f,"isShortTransition","(JI)Z",1,List.of("1f","J:3","1d","85","69","94","9c:[9]","I:1","a7:[10]","I:0","ac"));
        Set<String> methods=new HashSet<>(Set.of("<init>(Ljava/lang/String;)V","<clinit>()V","profileId()Ljava/lang/String;","isShortTransition(JI)Z"));
        for(JsonElement element:M004StaticClosureContract.STATIC.getAsJsonObject("fixedIdsAndProfile").getAsJsonObject("profile").getAsJsonArray("constantReturningMethods")) {
            JsonObject r=element.getAsJsonObject();String name=r.get("name").getAsString(),desc=r.get("descriptor").getAsString();
            String value=desc.equals("()I")?"I:"+r.get("value").getAsInt():"D:"+Long.toUnsignedString(Long.parseUnsignedLong(r.get("rawBitsHex").getAsString(),16),16);
            valid&=M004StaticClosurePolicy.exact(f,name,desc,1,List.of(value,desc.equals("()I")?"ac":"af"));methods.add(name+desc);
        }
        return valid&&f.methods.stream().map(m->m.name()+m.descriptor()).collect(java.util.stream.Collectors.toSet()).equals(methods);
    }
    private static void fail(List<AsyncEscapeBytecodeGuard.Violation> out,String code,M004Classfile f,String detail)
    { M004StaticClosurePolicy.fail(out,code,f,"",detail); }

    /** Active-only authority graph: owner membership cannot authorize a publication edge. */
    private static void publicationAuthority(M004Classfile f,List<AsyncEscapeBytecodeGuard.Violation> out) {
        String base=D+"AnalysisDynamicsProcessor",leveler=D+"LevelerProcessor",publication=D+"PublishedGain";
        String analyze="analyze([FIL"+L+"CancellationToken;)V",adopt="adoptEnvelope(L"+base+";)V";
        Set<String> writers=Set.of("<init>()V","analyze([FI)V","remap()V","publishStructural(L"+D+"SparseGainSchedule;I)V",
                "publishUnit(L"+D+"AnalysisStatus;)V","adoptPublication(L"+publication+";)V","clearAnalysis()V");
        if(f.owner.equals(leveler))for(var m:f.methods)if(m.name().equals("analyze")&&m.descriptor().equals("([FI)V")&&!body(f,m,"ANALYZE_ENTRY"))
            fail(out,"ACTIVE_ENTRY_AUTHORITY_CODE",f,"Public two-argument analyze must only forward the same PCM/channels with a fresh token");
        if(f.owner.equals(base))for(var m:f.methods) {
            String key=m.name()+m.descriptor();
            if((writers.contains(key)||Set.of(adopt,"clearLegacyState()V","publishedGain()L"+publication+";","analysisRateHz()I").contains(key))
                    &&!basePublicationBody(f,m))fail(out,"ACTIVE_PUBLICATION_HELPER_CODE",f,key);
        }
        for(var m:f.methods)if(m.code()!=null)for(var i:m.code().instructions()) {
            if(i.opcode()<178||i.opcode()>185)continue;
            var ref=f.member(i.operand());String caller=m.name()+m.descriptor();
            boolean inherited=ref.owner().equals(base)||ref.owner().equals(leveler);
            if(i.opcode()==181&&inherited&&(ref.name().equals("published")||ref.name().equals("publicationSequence"))) {
                boolean descriptor=ref.name().equals("published")?ref.descriptor().equals("L"+publication+";"):ref.descriptor().equals("J");
                if(!descriptor||!f.owner.equals(base)||!ref.owner().equals(base)||!writers.contains(caller))
                    M004StaticClosurePolicy.fail(out,"ACTIVE_PUBLICATION_WRITE",f,caller,"Publication/generation writes only in the seven exact base writer bodies");
            }
            if(i.opcode()<182)continue;
            boolean allowed=true;
            if(inherited&&Set.of("publishStructural","adoptPublication","adoptEnvelope","analyze","remap").contains(ref.name())) {
                // Recognize inherited-owner aliases before rejecting; they never fall through to the broad own-owner call rule.
                allowed=switch(ref.name()) {
                    case "publishStructural" -> f.owner.equals(leveler)&&caller.equals(analyze)&&i.opcode()==182
                            &&ref.owner().equals(leveler)&&ref.descriptor().equals("(L"+D+"SparseGainSchedule;I)V");
                    case "adoptPublication" -> caller.equals(adopt)&&i.opcode()==182&&ref.owner().equals(f.owner)
                            &&(f.owner.equals(base)||f.owner.equals(leveler))&&ref.descriptor().equals("(L"+publication+";)V");
                    case "analyze" -> f.owner.equals(leveler)&&caller.equals("analyze([FI)V")&&i.opcode()==182
                            &&ref.owner().equals(leveler)&&(ref.name()+ref.descriptor()).equals(analyze);
                    default -> false; // No in-closure detours through legacy remap or public adoption/analysis entries.
                };
            }
            if(ref.owner().equals(publication)&&ref.name().equals("<init>")) {
                allowed=i.opcode()==183&&ref.descriptor().equals("(L"+D+"GainSchedule;IIJL"+D+"AnalysisStatus;)V")
                        &&(f.owner.equals(base)&&Set.of("analyze([FI)V","remap()V","publishStructural(L"+D+"SparseGainSchedule;I)V",
                            "adoptPublication(L"+publication+";)V").contains(caller)
                            ||f.owner.equals(publication)&&caller.equals("unit(JL"+D+"AnalysisStatus;)L"+publication+";"));
            }
            if(!allowed)M004StaticClosurePolicy.fail(out,"ACTIVE_PUBLICATION_CALLER",f,caller,"Unreviewed publication/construction/adoption/legacy detour: "+ref);
        }
    }
    private static boolean basePublicationBody(M004Classfile f,M004Classfile.Method m) {
        if(!body(f,m,"BASE_"+m.name()))return false;
        if(!m.name().equals("analyze")&&!m.name().equals("remap"))return m.code().handlers().isEmpty();
        int[][] expected=m.name().equals("analyze")?new int[][]{{66,110,113},{66,110,113}}
                :new int[][]{{19,72,99},{19,72,99},{73,96,99},{73,96,99}};
        if(m.code().handlers().size()!=expected.length)return false;
        for(int n=0;n<expected.length;n++) {
            var h=m.code().handlers().get(n);String type=n%2==0?"java/lang/IllegalArgumentException":"java/lang/IllegalStateException";
            if(h.start()!=expected[n][0]||h.end()!=expected[n][1]||h.target()!=expected[n][2]||h.catchType()==0||!f.className(h.catchType()).equals(type))return false;
        }
        return true;
    }

    static boolean recordProtocol(M004Classfile f) {
        if(!f.owner.equals(D+"PublishedGain")||f.bootstraps.size()!=1||!recordComponents(f))return false;
        var b=f.bootstraps.get(0);var handle=f.cp(b.handle(),15);
        if(handle.a()!=6||!f.member(handle.b()).equals(new M004Classfile.Member("java/lang/runtime/ObjectMethods","bootstrap",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;"))
                ||b.arguments().size()!=7||!f.className(b.arguments().get(0)).equals(f.owner)
                ||!f.string(b.arguments().get(1)).equals("schedule;sourceRateHz;sourceChannels;analysisGeneration;status"))return false;
        Set<Integer> handles=new HashSet<>();handles.add(b.handle());var fields=fields(f.owner);
        for(int i=0;i<5;i++) {
            int index=b.arguments().get(i+2);var getter=f.cp(index,15);var field=fields.get(i);
            if(getter.a()!=1||!f.member(getter.b()).equals(new M004Classfile.Member(f.owner,field.name(),field.descriptor())))return false;
            handles.add(index);
        }
        int dynamics=0;
        for(int i=1;i<f.pool.length;i++) {
            var cp=f.pool[i];if(cp==null)continue;
            if(cp.tag()==15&&!handles.contains(i)||cp.tag()==16||cp.tag()==17)return false;
            if(cp.tag()==18) { if(cp.a()!=0)return false;dynamics++; }
        }
        if(dynamics!=3)return false;
        for(String name:List.of("toString","hashCode","equals")) {
            String ret=name.equals("toString")?"Ljava/lang/String;":name.equals("hashCode")?"I":"Z";
            String desc=name.equals("equals")?"(Ljava/lang/Object;)Z":"()"+ret;
            var methods=f.methods.stream().filter(m->m.name().equals(name)&&m.descriptor().equals(desc)).toList();
            if(methods.size()!=1)return false;var m=methods.get(0);if(m.access()!=17||m.code()==null||!m.code().handlers().isEmpty())return false;
            var instructions=m.code().instructions();boolean eq=name.equals("equals");int at=eq?2:1;
            if(instructions.size()!=(eq?4:3)||instructions.get(0).opcode()!=42||eq&&instructions.get(1).opcode()!=43
                    ||instructions.get(at).opcode()!=186||instructions.get(at+1).opcode()!=(name.equals("toString")?176:172))return false;
            var dynamic=f.cp(instructions.get(at).operand(),18);var nat=f.cp(dynamic.b(),12);
            if(dynamic.a()!=0||!f.utf(nat.a()).equals(name)||!f.utf(nat.b()).equals("(L"+f.owner+";"+(eq?"Ljava/lang/Object;":"")+")"+ret))return false;
        }
        return true;
    }
    private static boolean recordComponents(M004Classfile f) {
        if(f.methods.isEmpty())return false;
        ByteBuffer bytes=ByteBuffer.wrap(f.bytes);bytes.position(f.methods.get(f.methods.size()-1).end());
        for(int n=u2(bytes);n>0;n--) {
            String attribute=f.utf(u2(bytes));int length=bytes.getInt(),end=bytes.position()+length;
            if(attribute.equals("Record")) {
                var rules=fields(f.owner);if(u2(bytes)!=rules.size())return false;
                for(var field:rules)if(!f.utf(u2(bytes)).equals(field.name())||!f.utf(u2(bytes)).equals(field.descriptor())||u2(bytes)!=0)return false;
                return bytes.position()==end;
            }
            bytes.position(end);
        }
        return false;
    }
    private static boolean annex(M004Classfile f) {
        if(!f.owner.equals(L+"FiniteTruePeakStream"))return false;
        double[][] coefficients={
            {.001708984375,.010986328125,-.0196533203125,.033203125,-.0594482421875,.1373291015625,.97216796875,-.102294921875,.047607421875,-.026611328125,.014892578125,-.00830078125},
            {-.0291748046875,.029296875,-.0517578125,.089111328125,-.16650390625,.465087890625,.77978515625,-.2003173828125,.1015625,-.0582275390625,.0330810546875,-.0189208984375},
            {-.0189208984375,.0330810546875,-.0582275390625,.1015625,-.2003173828125,.77978515625,.465087890625,-.16650390625,.089111328125,-.0517578125,.029296875,-.0291748046875},
            {-.00830078125,.014892578125,-.026611328125,.047607421875,-.102294921875,.97216796875,.1373291015625,-.0594482421875,.033203125,-.0196533203125,.010986328125,.001708984375}};
        List<String> expected=new ArrayList<>(List.of("I:4","bd:[D"));
        for(int phase=0;phase<4;phase++) {
            expected.addAll(List.of("59","I:"+phase,"I:12","bc"));
            for(int tap=0;tap<12;tap++)expected.addAll(List.of("59","I:"+tap,"D:"+Long.toUnsignedString(Double.doubleToRawLongBits(coefficients[phase][tap]),16),"52"));
            expected.add("53");
        }
        expected.addAll(List.of("b3:"+f.owner+".ANNEX2[[D","b1"));
        if(!M004StaticClosurePolicy.exact(f,"<clinit>","()V",8,expected))return false;
        var initializer=f.methods.stream().filter(m->m.name().equals("<clinit>")).findFirst().orElseThrow();
        if(initializer.code().instructions().stream().filter(i->i.opcode()==188).anyMatch(i->initializer.code().bytes()[i.offset()+1]!=7))return false;
        for(var m:f.methods)if(m.code()!=null&&!m.name().equals("<clinit>")) {
            for(var i:m.code().instructions())if(i.opcode()>=178&&i.opcode()<=185) {
                var member=f.member(i.operand());
                if(member.owner().equals(f.owner)&&member.name().equals("ANNEX2")&&!(m.name().equals("observe")&&i.opcode()==178))return false;
            }
            // The only reader is the exact scalar dot-product loop; it cannot expose the static array.
            if(m.name().equals("observe")&&!body(f,m,"OBSERVE"))return false;
        }
        return true;
    }
    private static void sensitiveCode(M004Classfile f,List<AsyncEscapeBytecodeGuard.Violation> out) {
        if(f.owner.equals(L+"FiniteTruePeakStream")&&!annex(f))fail(out,"ACTIVE_ANNEX2_CODE",f,"Exact coefficient initializer and scalar-only reader required");
        if(f.owner.equals(D+"PublishedGain")) {
            if(!recordProtocol(f))fail(out,"ACTIVE_RECORD_PROTOCOL",f,"Only canonical five-component ObjectMethods protocol");
            for(var m:f.methods)if(Set.of("<init>","unit","isRenderableFor").contains(m.name())&&!body(f,m,"PUBLICATION_"+m.name()))fail(out,"ACTIVE_PUBLICATION_CODE",f,m.name());
            for(var field:fields(f.owner)) {
                String ret=field.descriptor().equals("J")?"ad":field.descriptor().equals("I")?"ac":"b0";
                if(!M004StaticClosurePolicy.exact(f,field.name(),"()"+field.descriptor(),1,List.of("2a","b4:"+f.owner+"."+field.name()+field.descriptor(),ret)))
                    fail(out,"ACTIVE_PUBLICATION_CODE",f,field.name());
            }
        }
        if(f.owner.equals(L+"LoudnessConformanceGuard"))for(var m:f.methods)if(m.name().equals("authorizesCurrentBuild")&&!body(f,m,"AUTHORITY"))fail(out,"ACTIVE_AUTHORITY_CODE",f,m.name());
        if(f.owner.equals(D+"LevelerProcessor")) {
            for(var m:f.methods)if(m.name().equals("matches")&&!body(f,m,"CACHE"))fail(out,"ACTIVE_CACHE_IDENTITY_CODE",f,m.name());
            for(var m:f.methods)if(m.name().equals("analyze")&&m.descriptor().equals("([FIL"+L+"CancellationToken;)V")&&!analysisBody(f,m))
                fail(out,"ACTIVE_AUTHORITY_CALLSITE",f,"Current report and finite proof must dominate the only structural publication");
            if(!levelerThrowable(f))fail(out,"ACTIVE_LEVELER_CLEANUP_CODE",f,"Exact nonnested monitor cleanup and fingerprint catch/wrap required");
        }
        if(f.owner.equals(D+"GainSchedule")&&!f.typeUses.stream().filter(u->u.role().equals("PermittedSubclasses")).map(M004Classfile.TypeUse::type).toList()
                .equals(List.of(D+"DenseGainSchedule",D+"SparseGainSchedule")))fail(out,"ACTIVE_SEALED_SCHEDULE",f,"Only the two exact gain domains");
    }
    private static boolean analysisBody(M004Classfile f,M004Classfile.Method m) {
        if(!body(f,m,"ANALYZE")||m.code().handlers().size()!=15)return false;
        int[][] ranges={{84,129},{130,205},{206,222},{223,251},{252,386}};
        String[] types={"java/lang/IllegalArgumentException","java/lang/IllegalStateException","java/lang/ArithmeticException"};
        for(int i=0;i<15;i++){var h=m.code().handlers().get(i);if(h.start()!=ranges[i/3][0]||h.end()!=ranges[i/3][1]||h.target()!=389||!f.className(h.catchType()).equals(types[i%3]))return false;}
        return true;
    }
    static boolean levelerThrowable(M004Classfile f) {
        if(!f.owner.equals(D+"LevelerProcessor"))return false;
        int classes=0,names=0,descriptors=0;
        String descriptor="(Ljava/lang/String;Ljava/lang/Throwable;)V";
        for(var cp:f.pool)if(cp!=null) {
            if(cp.tag()==7&&f.utf(cp.a()).equals(THROWABLE))classes++;
            if(cp.tag()==1&&((String)cp.value()).contains(THROWABLE)) {
                if(cp.value().equals(THROWABLE))names++;else if(cp.value().equals(descriptor))descriptors++;else return false;
            }
        }
        if(classes!=1||names!=1||descriptors!=1)return false;
        var uses=f.typeUses.stream().filter(u->u.type().equals(THROWABLE)).toList();
        if(uses.size()!=4||uses.stream().anyMatch(u->!Set.of("constant-pool-member-descriptor","constant-pool-name-type").contains(u.role())
                &&!(u.role().equals("StackMapTable")&&u.member().equals("adoptEnvelope")&&u.descriptor().equals("(L"+D+"AnalysisDynamicsProcessor;)V"))))return false;
        var adopt=f.methods.stream().filter(m->m.name().equals("adoptEnvelope")&&m.descriptor().equals("(L"+D+"AnalysisDynamicsProcessor;)V")).toList();
        var fingerprint=f.methods.stream().filter(m->m.name().equals("fingerprint")&&m.descriptor().equals("([FIL"+L+"CancellationToken;)[B")).toList();
        if(adopt.size()!=1||fingerprint.size()!=1)return false;
        var a=adopt.get(0);var p=fingerprint.get(0);
        if(a.access()!=1||p.access()!=10||!body(f,a,"ADOPT")||!body(f,p,"FINGERPRINT"))return false;
        if(!a.code().handlers().equals(List.of(new M004Classfile.Handler(30,56,59,0),new M004Classfile.Handler(59,64,59,0),
                new M004Classfile.Handler(72,95,113,0),new M004Classfile.Handler(96,110,113,0),new M004Classfile.Handler(113,118,113,0))))return false;
        if(!throwableLocations(f,a).equals(List.of("59:stack:0:1","113:stack:0:1"))||!throwableLocations(f,p).isEmpty())return false;
        if(p.code().handlers().size()!=2)return false;
        int[][] ranges={{0,46,178},{47,177,178}};
        for(int i=0;i<2;i++){var h=p.code().handlers().get(i);if(h.start()!=ranges[i][0]||h.end()!=ranges[i][1]||h.target()!=ranges[i][2]||!f.className(h.catchType()).equals("java/security/NoSuchAlgorithmException"))return false;}
        return true;
    }
    private static boolean body(M004Classfile f,M004Classfile.Method m,String key) {
        if(m.code()==null)return false;
        if(!Set.of("ADOPT","FINGERPRINT","ANALYZE","BASE_analyze","BASE_remap").contains(key)&&!m.code().handlers().isEmpty())return false;
        String literal=Active.DELTA.getAsJsonObject("bodies").get(key).getAsString().replace("@D",D).replace("@L",L).replace("@M",M);
        if(!M004StaticClosurePolicy.tokens(f,m).equals(List.of(literal.split(" \\| "))))return false;
        for(var i:m.code().instructions()) {
            // The inherited token representation omits these two raw operands; constrain them too.
            if(i.opcode()==132&&m.code().bytes()[i.offset()+2]!=1)return false;
            if(i.opcode()==188&&(!key.equals("FINGERPRINT")||m.code().bytes()[i.offset()+1]!=8))return false;
        }
        return true;
    }
    static final String CORE_OWNER = "com/quickmaster/processing/dynamics/leveler/LoudnessCore";
    static final String LOADER_OWNER = "com/quickmaster/processing/dynamics/leveler/ConformanceArtifactLoader";
    static final String THROWABLE = "java/lang/Throwable";
    static final JsonObject CORE = resource("core-window-delta.json", "7bf7369efa66327034c1633c9c46b4fc78c0db71a7feb5941a926be932042078");
    static final String CORE_CHUNKS_SHA = "c284c29846f6d61f846d780c0848ecbb7ed5250051a15a9a7a3a1bd710577384";
    static final JsonObject CORE_CHUNKS = resource("core-chunk-delta.json", CORE_CHUNKS_SHA);
    static final JsonObject CLEANUP = resource("cleanup-metadata-delta.json", "0af869b1214bebad39d2f48aa04273286b2121589e8be5ee9bfaebcfbaa36367");
    private ActiveLevelerGuardContract() { }

    private static JsonObject resource(String name, String expected)
    {
        try (InputStream stream = ActiveLevelerGuardContract.class.getResourceAsStream("/leveler/" + name))
        {
            if (stream == null) throw new AssertionError("Missing explicit guard delta " + name);
            byte[] bytes = stream.readAllBytes();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!expected.equals(actual)) throw new AssertionError("Guard delta changed: " + name);
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    static void composeCore(Map<String, JsonObject> classes)
    {
        JsonObject previous = classes.get(CORE_OWNER);
        if (previous == null) throw new AssertionError("ADR013 requires the original Core contract");
        JsonArray prior = previous.getAsJsonArray("fields"), proposed = CORE.getAsJsonArray("fields");
        if (prior.size() != 14 || proposed.size() != 15) throw new AssertionError("Unexpected Core predecessor/successor extent");
        JsonArray fields = new JsonArray();
        for (int i = 0; i < proposed.size(); i++)
        {
            JsonObject source = proposed.get(i).getAsJsonObject(), field = new JsonObject();
            for (String key : List.of("name", "descriptor", "access")) field.add(key, source.get(key).deepCopy());
            JsonObject cv = new JsonObject(); cv.addProperty("attribute", "absent"); field.add("constantValue", cv);
            if (i != 10 && !field.equals(prior.get(i < 10 ? i : i - 1)))
                throw new AssertionError("ADR013 must preserve every existing field tuple");
            fields.add(field);
        }
        JsonObject successor = previous.deepCopy(); successor.add("fields", fields);
        composeCoreChunks(successor, CORE_CHUNKS);
        classes.put(CORE_OWNER, successor);
    }

    // Compose the historical 14 -> 15 field contract first; then this independently
    // pinned, two-descriptor successor. Never derive authority from candidate bytes.
    static void composeCoreChunks(JsonObject successor, JsonObject delta)
    {
        if (!delta.equals(CORE_CHUNKS)
                || !delta.getAsJsonObject("predecessor").get("sha256").getAsString()
                    .equals("7bf7369efa66327034c1633c9c46b4fc78c0db71a7feb5941a926be932042078"))
            throw new AssertionError("Unadjudicated Core chunk authority");
        JsonArray fields = successor.getAsJsonArray("fields");
        if (fields.size() != 15) throw new AssertionError("Core chunks require exact predecessor fields");
        for (int i = 0; i < fields.size(); i++)
        {
            JsonObject actual = fields.get(i).getAsJsonObject(), prior = CORE.getAsJsonArray("fields").get(i).getAsJsonObject();
            for (String key : List.of("name", "descriptor", "access"))
                if (!Objects.equals(actual.get(key), prior.get(key))) throw new AssertionError("Core predecessor tuple drift");
            JsonObject cv = actual.getAsJsonObject("constantValue");
            if (cv == null || cv.size() != 1 || !cv.get("attribute").getAsString().equals("absent"))
                throw new AssertionError("Core predecessor ConstantValue drift");
        }
        for (JsonElement element : delta.getAsJsonArray("fieldDescriptorChanges"))
        {
            JsonObject change = element.getAsJsonObject();
            int index = change.get("name").getAsString().equals("weightedPowerRing") ? 9 : 10;
            JsonObject field = fields.get(index).getAsJsonObject();
            if (!field.get("name").equals(change.get("name")) || !field.get("descriptor").equals(change.get("previousDescriptor")))
                throw new AssertionError("Core descriptor successor mismatch");
            field.add("descriptor", change.get("descriptor").deepCopy());
        }
    }

    static boolean coreMethods(M004Classfile file)
    {
        if (!file.owner.equals(CORE_OWNER)) return true;
        String model = "com/quickmaster/processing/dynamics/leveler/model/";
        Map<String, Integer> expected = new HashMap<>();
        expected.put("<init>(IL" + model + "ChannelLayout;)V", 1);
        expected.put("acceptFrame([FI)V", 1); expected.put("framesSeen()J", 1);
        expected.put("momentaryPower()D", 1); expected.put("shortTermPower()D", 1);
        expected.put("integrated([DI[JLcom/quickmaster/processing/dynamics/leveler/CancellationToken;)L" + model + "MeasuredLoudness;", 9);
        expected.put("fromPower(D)L" + model + "MeasuredLoudness;", 9);
        expected.put("requirePower(D)V", 10); expected.put("lufs(D)D", 10); expected.put("coefficients(I)[D", 10);
        for (JsonElement element : CORE_CHUNKS.getAsJsonArray("privateStaticHelpers"))
        {
            JsonObject h = element.getAsJsonObject();
            expected.put(h.get("name").getAsString() + h.get("descriptor").getAsString(), h.get("access").getAsInt());
        }
        if (file.methods.size() != expected.size()) return false;
        for (var method : file.methods)
            if (!Objects.equals(expected.get(method.name() + method.descriptor()), method.access()) || method.code() == null) return false;
        return true;
    }

    static boolean cleanup(M004Classfile file) { return cleanup(file, Set.of()); }

    // Test-only deletion controls; the composed guard always uses the empty set.
    static boolean cleanup(M004Classfile file, Set<String> removed)
    {
        if (!file.owner.equals(LOADER_OWNER)) return false;
        if (!removed.contains("POOL"))
        {
            int classes = 0, names = 0;
            for (var cp : file.pool)
                if (cp != null)
                {
                    if (cp.tag() == 7 && file.utf(cp.a()).equals(THROWABLE)) classes++;
                    if (cp.tag() == 1 && ((String) cp.value()).contains(THROWABLE))
                    { if (!cp.value().equals(THROWABLE)) return false; names++; }
                }
            if (classes != 1 || names != 1) return false;
        }
        if (!removed.contains("USES"))
        {
            var uses = file.typeUses.stream().filter(u -> u.type().equals(THROWABLE)).toList();
            if (uses.size() != 2 || uses.stream().anyMatch(u -> !u.role().equals("StackMapTable")
                    || !(u.member().equals("readBundle") && u.descriptor().equals("()[Ljava/lang/Object;")
                    || u.member().equals("readEntry") && u.descriptor().equals("(Ljava/util/jar/JarFile;Ljava/util/zip/ZipEntry;I)[B")))) return false;
        }
        for (JsonElement element : CLEANUP.getAsJsonObject("metadata").getAsJsonArray("methods"))
        {
            JsonObject rule = element.getAsJsonObject();
            var matches = file.methods.stream().filter(m -> m.name().equals(rule.get("name").getAsString())
                    && m.descriptor().equals(rule.get("descriptor").getAsString())).toList();
            if (matches.size() != 1) return false;
            var method = matches.get(0);
            if (method.access() != rule.get("access").getAsInt() || method.code() == null
                    || method.code().bytes().length != rule.get("codeLength").getAsInt()) return false;
            if (!removed.contains("HANDLERS"))
            {
                JsonArray expected = rule.getAsJsonArray("handlers");
                if (method.code().handlers().size() != expected.size()) return false;
                for (int i = 0; i < expected.size(); i++)
                {
                    var actual = method.code().handlers().get(i); JsonArray h = expected.get(i).getAsJsonArray();
                    if (actual.start() != h.get(0).getAsInt() || actual.end() != h.get(1).getAsInt()
                            || actual.target() != h.get(2).getAsInt() || actual.catchType() != 0) return false;
                }
            }
            if (!removed.contains("CODE") && !inventory(file, method).equals(CLEANUP.getAsJsonObject("instructions")
                    .getAsJsonObject(method.name()).getAsJsonArray("instructions"))) return false;
            if (!removed.contains("STACK") && !throwableLocations(file, method)
                    .equals(List.of(rule.get("handlerPc").getAsInt() + ":stack:0:1"))) return false;
        }
        return true;
    }

    static List<String> throwableLocations(M004Classfile file, M004Classfile.Method method)
    {
        List<String> locations = new ArrayList<>();
        var code = method.code();
        ByteBuffer data = ByteBuffer.wrap(file.bytes);
        data.position(code.absoluteStart() + code.bytes().length + 2 + 8 * code.handlers().size());
        for (int attributes = u2(data); attributes > 0; attributes--)
        {
            String name = file.utf(u2(data)); int length = data.getInt(), end = data.position() + length;
            if (name.equals("StackMapTable"))
            {
                int pc = -1;
                for (int frames = u2(data); frames > 0; frames--)
                {
                    int type = data.get() & 255;
                    int delta = type <= 63 ? type : type <= 127 ? type - 64 : u2(data);
                    pc += delta + 1;
                    if (type >= 64 && type <= 127 || type == 247) verification(data, file, pc, "stack", 0, 1, locations);
                    else if (type >= 252 && type <= 254)
                        for (int i = 0; i < type - 251; i++) verification(data, file, pc, "local", i, type - 251, locations);
                    else if (type == 255)
                    {
                        int locals = u2(data);
                        for (int i = 0; i < locals; i++) verification(data, file, pc, "local", i, locals, locations);
                        int stack = u2(data);
                        for (int i = 0; i < stack; i++) verification(data, file, pc, "stack", i, stack, locations);
                    }
                }
                if (data.position() != end) throw M004Classfile.fail("cleanup StackMap extent");
            }
            data.position(end);
        }
        return List.copyOf(locations);
    }
    private static void verification(ByteBuffer data, M004Classfile file, int pc, String role, int index, int count, List<String> out)
    {
        int tag = data.get() & 255;
        if (tag == 7 && file.className(u2(data)).equals(THROWABLE)) out.add(pc + ":" + role + ":" + index + ":" + count);
        else if (tag == 8) u2(data);
    }
    private static int u2(ByteBuffer data) { return data.getShort() & 65535; }

    static JsonArray inventory(M004Classfile file, M004Classfile.Method method)
    {
        JsonArray result = new JsonArray();
        for (var ins : method.code().instructions())
        {
            int op = ins.opcode(); String name = mnemonic(op), operand = "";
            if (op >= 0x2a && op <= 0x2d) { name = "aload"; operand = "" + (op - 0x2a); }
            else if (op >= 0x4b && op <= 0x4e) { name = "astore"; operand = "" + (op - 0x4b); }
            else if (!ins.targets().isEmpty()) operand = "" + ins.targets().get(0);
            else if (op >= 0xb2 && op <= 0xb9)
            {
                var member = file.member(ins.operand());
                operand = (op <= 0xb5 ? "Field " : "Method ")
                        + (member.owner().equals(file.owner) ? "" : member.owner() + ".")
                        + (member.name().equals("<init>") ? "\"<init>\"" : member.name()) + ":" + member.descriptor();
            }
            else if (op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1) operand = "class " + file.className(ins.operand());
            else if (op == 0x12 || op == 0x13 || op == 0x14)
            {
                var cp = file.pool[ins.operand()];
                operand = switch (cp.tag()) { case 7 -> "class " + file.className(ins.operand());
                    case 8 -> "String " + file.string(ins.operand()); case 3 -> "int " + ((Long)cp.value()).intValue(); default -> "UNADJUDICATED_CP"; };
            }
            else if (op == 0xbc) operand = (method.code().bytes()[ins.offset() + 1] & 255) == 8 ? "byte" : "UNADJUDICATED_ARRAY";
            else if (ins.operand() >= 0) operand = "" + ins.operand();
            JsonArray row = new JsonArray(); row.add(ins.offset()); row.add(name); row.add(operand); result.add(row);
        }
        return result;
    }
    private static String mnemonic(int opcode)
    {
        return switch (opcode) {
            case 1 -> "aconst_null"; case 2 -> "iconst_m1"; case 3 -> "iconst_0"; case 4 -> "iconst_1"; case 5 -> "iconst_2";
            case 9 -> "lconst_0"; case 18 -> "ldc"; case 19 -> "ldc_w"; case 20 -> "ldc2_w";
            case 21 -> "iload"; case 25 -> "aload"; case 28 -> "iload_2"; case 33 -> "lload_3";
            case 54 -> "istore"; case 58 -> "astore"; case 66 -> "lstore_3"; case 83 -> "aastore"; case 89 -> "dup";
            case 96 -> "iadd"; case 100 -> "isub"; case 133 -> "i2l"; case 136 -> "l2i"; case 148 -> "lcmp";
            case 153 -> "ifeq"; case 154 -> "ifne"; case 158 -> "ifle"; case 159 -> "if_icmpeq"; case 164 -> "if_icmple";
            case 167 -> "goto"; case 176 -> "areturn"; case 178 -> "getstatic"; case 182 -> "invokevirtual";
            case 183 -> "invokespecial"; case 184 -> "invokestatic"; case 187 -> "new"; case 188 -> "newarray";
            case 189 -> "anewarray"; case 190 -> "arraylength"; case 191 -> "athrow"; case 192 -> "checkcast";
            case 193 -> "instanceof"; case 198 -> "ifnull"; case 199 -> "ifnonnull"; default -> "UNADJUDICATED_OPCODE_" + opcode;
        };
    }
}
