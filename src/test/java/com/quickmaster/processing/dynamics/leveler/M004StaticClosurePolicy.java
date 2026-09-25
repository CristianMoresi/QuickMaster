package com.quickmaster.processing.dynamics.leveler;

import com.google.gson.*;
import java.util.*;

/** Directed closure layered over the preserved ADR007 guards. Every rule has a scoped deletion control. */
final class M004StaticClosurePolicy
{
    private static final String L = M004StaticClosureContract.L, M = M004StaticClosureContract.M;
    private static final String A = M004StaticClosureContract.A;
    private M004StaticClosurePolicy() { }

    static List<AsyncEscapeBytecodeGuard.Violation> inspect(byte[] bytes)
    { return inspect(M004Classfile.parse(bytes), Set.of()); }
    static List<AsyncEscapeBytecodeGuard.Violation> inspect(M004Classfile f, Set<AsyncEscapeBytecodeGuard.Rule> removed)
    { return inspectMode(f,removed,false); }
    static List<AsyncEscapeBytecodeGuard.Violation> inspectActive(M004Classfile f, Set<AsyncEscapeBytecodeGuard.Rule> removed)
    { return inspectMode(f,removed,true); }
    private static List<AsyncEscapeBytecodeGuard.Violation> inspectMode(M004Classfile f, Set<AsyncEscapeBytecodeGuard.Rule> removed, boolean active)
    {
        List<AsyncEscapeBytecodeGuard.Violation> out = new ArrayList<>();
        JsonObject schema = M004StaticClosureContract.CLASSES.get(f.owner);
        if (!removed.contains(AsyncEscapeBytecodeGuard.Rule.CLASS_SHAPE))
        {
            int access = schema == null ? f.access : schema.get("classAccess").getAsInt();
            String parent = f.owner.equals("com/quickmaster/processing/dynamics/LevelerProcessor") ? A : M004StaticClosureContract.ENUMS.containsKey(f.owner) ? "java/lang/Enum" : "java/lang/Object";
            List<String> interfaces = f.owner.equals(A) ? List.of("com/quickmaster/processing/AudioProcessor") : List.of();
            if (!(active && ActiveLevelerGuardContract.shape(f)) && (f.access != access || !f.superclass.equals(parent) || !f.interfaces.equals(interfaces) || f.owner.contains("$") || f.classAttributes.contains("EnclosingMethod") || f.classAttributes.contains("Record"))) fail(out, "STATIC_CLASS_SHAPE", f, "", "Exact superclass/interfaces/access required");
        }
        if (f.owner.equals(A) && !removed.contains(AsyncEscapeBytecodeGuard.Rule.ABSTRACT))
        {
            List<String> actual = f.methods.stream().filter(m -> (m.access() & 0x400) != 0).filter(m -> M004StaticClosureContract.abstractMethod(f, m)).map(m -> m.name() + m.descriptor()).sorted().toList();
            if (!actual.equals(List.of("computeFeatures([FIII)V", "mapFeaturesToGain()V")) || f.methods.stream().filter(m -> (m.access() & 0x400) != 0).count() != 2) fail(out, "STATIC_ABSTRACT_TABLE", f, "", "Only two exact protected abstract declarations without Code");
        }
        if (!(active && ActiveLevelerGuardContract.schema(f.owner) != null) && !removed.contains(AsyncEscapeBytecodeGuard.Rule.CONSTANT_VALUES)) constantValues(f, schema, out);
        if (!removed.contains(AsyncEscapeBytecodeGuard.Rule.CLOSED_ENUM)) closedEnum(f, out, active);
        if (!removed.contains(AsyncEscapeBytecodeGuard.Rule.FIXED_PROFILE)) {
            if(active&&f.owner.equals(L+"LevelerCalibrationProfile")) {
                if(!ActiveLevelerGuardContract.fixedProfileV2(f))fail(out,"STATIC_FIXED_PROFILE",f,"","Exact active V1+V2 atoms and unchanged historical thresholds required");
            } else fixedProfiles(f,out);
        }
        if (!removed.contains(AsyncEscapeBytecodeGuard.Rule.DIRECTED_API)) directedApi(f, out);
        if (!removed.contains(AsyncEscapeBytecodeGuard.Rule.STATIC_TABLE)) staticTable(f, out);
        if (!removed.contains(AsyncEscapeBytecodeGuard.Rule.LOADER_BOUNDARY) && f.owner.equals(M004StaticClosureContract.LOADER)) M004StaticClosureLoader.inspect(f, out);
        if (active) ActiveLevelerGuardContract.activeRules(f, removed, out);
        return List.copyOf(out);
    }

    private static void constantValues(M004Classfile f, JsonObject schema, List<AsyncEscapeBytecodeGuard.Violation> out)
    {
        Map<String, JsonObject> rules = new HashMap<>();
        if (schema != null) for (JsonElement e : schema.getAsJsonArray("fields")) { JsonObject o = e.getAsJsonObject(); rules.put(o.get("name").getAsString(), o.getAsJsonObject("constantValue")); }
        for (M004Classfile.Field field : f.fields)
        {
            JsonObject cv = rules.get(field.name());
            if (cv == null || cv.get("attribute").getAsString().equals("absent"))
            { if (field.constantIndex() != 0) fail(out, "STATIC_CONSTANT_VALUE", f, field.name(), "ConstantValue must be absent"); continue; }
            if (field.constantIndex() == 0) { fail(out, "STATIC_CONSTANT_VALUE", f, field.name(), "ConstantValue missing"); continue; }
            M004Classfile.Cp cp = f.pool[field.constantIndex()];
            String tag = cv.get("tag").getAsString();
            boolean valid = switch (tag)
            {
                case "CONSTANT_Double" -> cp.tag() == 6 && ((Long) cp.value()).longValue() == Long.parseUnsignedLong(cv.get("rawBitsHex").getAsString(), 16);
                case "CONSTANT_Integer", "Integer" -> cp.tag() == 3 && ((Long) cp.value()).intValue() == (cv.has("value") ? cv.get("value").getAsInt() : cv.get("literal").getAsInt());
                case "CONSTANT_String" -> cp.tag() == 8 && f.string(field.constantIndex()).equals(cv.get("value").getAsString());
                default -> throw new AssertionError("Unimplemented normative ConstantValue tag " + tag);
            };
            if (!valid) fail(out, "STATIC_CONSTANT_VALUE", f, field.name(), "Typed value/raw bits differ from accepted annex");
        }
    }

    static List<String> tokens(M004Classfile f, M004Classfile.Method method)
    {
        if (method.code() == null) return List.of("NO_CODE");
        List<String> result = new ArrayList<>(); var code = method.code().instructions();
        Map<Integer, Integer> indices = new HashMap<>(); for (int i = 0; i < code.size(); i++) indices.put(code.get(i).offset(), i);
        for (var ins : code)
        {
            int op = ins.opcode();
            if (op >= 2 && op <= 8) result.add("I:" + (op - 3));
            else if (op == 0x10 || op == 0x11) result.add("I:" + ins.operand());
            else if (op == 9 || op == 10) result.add("J:" + (op - 9));
            else if (op == 14 || op == 15) result.add("D:" + Long.toUnsignedString(Double.doubleToRawLongBits(op - 14), 16));
            else if (op >= 0x12 && op <= 0x14)
            {
                var cp = f.pool[ins.operand()];
                result.add(switch (cp.tag())
                { case 3 -> "I:" + ((Long) cp.value()).intValue(); case 5 -> "J:" + cp.value(); case 6 -> "D:" + Long.toUnsignedString((Long) cp.value(), 16); case 8 -> "S:" + f.string(ins.operand()); case 7 -> "CLASS:" + f.className(ins.operand()); default -> "CP:" + cp.tag(); });
            }
            else if (op >= 0xB2 && op <= 0xB9) { var ref = f.member(ins.operand()); result.add(Integer.toHexString(op) + ":" + ref.owner() + "." + ref.name() + ref.descriptor()); }
            else if (op == 0xBB || op == 0xBD || op == 0xC0 || op == 0xC1) result.add(Integer.toHexString(op) + ":" + f.className(ins.operand()));
            else if (!ins.targets().isEmpty()) result.add(Integer.toHexString(op) + ":" + ins.targets().stream().map(indices::get).toList());
            else result.add(Integer.toHexString(op) + (ins.operand() < 0 ? "" : ":" + ins.operand()));
        }
        return List.copyOf(result);
    }
    static boolean exact(M004Classfile f, String name, String descriptor, int flags, List<String> expected)
    {
        var methods = f.methods.stream().filter(m -> m.name().equals(name) && m.descriptor().equals(descriptor)).toList();
        return methods.size() == 1 && methods.get(0).access() == flags && methods.get(0).code() != null && methods.get(0).code().handlers().isEmpty() && tokens(f, methods.get(0)).equals(expected);
    }
    private static void closedEnum(M004Classfile f, List<AsyncEscapeBytecodeGuard.Violation> out, boolean active)
    {
        List<String> names = active ? ActiveLevelerGuardContract.enumNames(f.owner) : M004StaticClosureContract.ENUMS.get(f.owner); if (names == null) return;
        String d = "L" + f.owner + ";", a = "[" + d;
        boolean valid = exact(f, "<init>", "(Ljava/lang/String;I)V", 2, List.of("2a", "2b", "1c", "b7:java/lang/Enum.<init>(Ljava/lang/String;I)V", "b1"))
                && exact(f, "values", "()" + a, 9, List.of("b2:" + f.owner + ".$VALUES" + a, "b6:" + a + ".clone()Ljava/lang/Object;", "c0:" + a, "b0"))
                && exact(f, "valueOf", "(Ljava/lang/String;)" + d, 9, List.of("CLASS:" + f.owner, "2a", "b8:java/lang/Enum.valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", "c0:" + f.owner, "b0"));
        List<String> values = new ArrayList<>(List.of("I:" + names.size(), "bd:" + f.owner));
        List<String> clinit = new ArrayList<>();
        for (int i = 0; i < names.size(); i++)
        {
            values.addAll(List.of("59", "I:" + i, "b2:" + f.owner + "." + names.get(i) + d, "53"));
            clinit.addAll(List.of("bb:" + f.owner, "59", "S:" + names.get(i), "I:" + i, "b7:" + f.owner + ".<init>(Ljava/lang/String;I)V", "b3:" + f.owner + "." + names.get(i) + d));
        }
        values.add("b0"); clinit.addAll(List.of("b8:" + f.owner + ".$values()" + a, "b3:" + f.owner + ".$VALUES" + a, "b1"));
        valid &= exact(f, "$values", "()" + a, 0x100a, values) && exact(f, "<clinit>", "()V", 8, clinit);
        Set<String> allowed = new HashSet<>(Set.of("<init>(Ljava/lang/String;I)V", "values()" + a, "valueOf(Ljava/lang/String;)" + d, "$values()" + a, "<clinit>()V"));
        if (f.owner.equals(M + "ChannelLayout")) allowed.addAll(Set.of("channels()I", "powerWeight(I)D"));
        valid &= f.methods.stream().map(m -> m.name() + m.descriptor()).collect(java.util.stream.Collectors.toSet()).equals(allowed);
        if (!valid) fail(out, "STATIC_ENUM_PROTOCOL", f, "", "Names/order/ordinals/identity/array/clone/write/constructor/five canonical bodies differ");
    }

    private static void fixedProfiles(M004Classfile f, List<AsyncEscapeBytecodeGuard.Violation> out)
    {
        boolean profile = f.owner.equals(L + "LevelerCalibrationProfile"), standard = f.owner.equals(L + "LoudnessStandard");
        if (!profile && !standard) return;
        String field = profile ? "profileId" : "standardId", singleton = profile ? "V1" : "BS1770_5", id = profile ? "QM-LEVELER-V1" : "BS1770-5";
        boolean valid = exact(f, "<init>", "(Ljava/lang/String;)V", 2, List.of("2a", "b7:java/lang/Object.<init>()V", "2a", "2b", "b5:" + f.owner + "." + field + "Ljava/lang/String;", "b1"))
                && exact(f, "<clinit>", "()V", 8, List.of("bb:" + f.owner, "59", "S:" + id, "b7:" + f.owner + ".<init>(Ljava/lang/String;)V", "b3:" + f.owner + "." + singleton + "L" + f.owner + ";", "b1"))
                && exact(f, field, "()Ljava/lang/String;", 1, List.of("2a", "b4:" + f.owner + "." + field + "Ljava/lang/String;", "b0"));
        Set<String> methods = new HashSet<>(List.of("<init>(Ljava/lang/String;)V", "<clinit>()V", field + "()Ljava/lang/String;"));
        if (profile)
        {
            valid &= exact(f, "isShortTransition", "(JI)Z", 1, List.of("1f", "J:3", "1d", "85", "69", "94", "9c:[9]", "I:1", "a7:[10]", "I:0", "ac")); methods.add("isShortTransition(JI)Z");
            for (JsonElement e : M004StaticClosureContract.STATIC.getAsJsonObject("fixedIdsAndProfile").getAsJsonObject("profile").getAsJsonArray("constantReturningMethods"))
            {
                JsonObject rule = e.getAsJsonObject(); String name = rule.get("name").getAsString(), desc = rule.get("descriptor").getAsString();
                String value = desc.equals("()I") ? "I:" + rule.get("value").getAsInt() : "D:" + Long.toUnsignedString(Long.parseUnsignedLong(rule.get("rawBitsHex").getAsString(), 16), 16);
                valid &= exact(f, name, desc, 1, List.of(value, desc.equals("()I") ? "ac" : "af")); methods.add(name + desc);
            }
        }
        else
        {
            for (var e : M004StaticClosureContract.STATIC.getAsJsonObject("fixedIdsAndProfile").getAsJsonObject("loudnessStandardReference").getAsJsonObject("methodConstants").entrySet())
                if (!e.getKey().equals("standardId"))
                { valid &= exact(f, e.getKey(), "()D", 1, List.of("D:" + Long.toUnsignedString(Double.doubleToRawLongBits(e.getValue().getAsDouble()), 16), "af")); methods.add(e.getKey() + "()D"); }
        }
        valid &= f.methods.stream().map(m -> m.name() + m.descriptor()).collect(java.util.stream.Collectors.toSet()).equals(methods);
        if (!valid) fail(out, "STATIC_FIXED_PROFILE", f, "", "Singleton/ID/constructor/constant getter/raw bits/method table differs");
    }

    private static void directedApi(M004Classfile f, List<AsyncEscapeBytecodeGuard.Violation> out)
    {
        if (!ActiveLevelerGuardContract.coreMethods(f))
            fail(out, "STATIC_CORE_METHOD_TABLE", f, "", "ADR013 exact original API and four private static helpers required");
        Map<String, String> helpers = Map.of(L + "BoundaryDetector", "originalNovelty(L" + M + "FeatureTimeline;JL" + L + "LevelerCalibrationProfile;)[D", L + "SegmentDescriptorBuilder", "structuralBins(L" + M + "FeatureTimeline;L" + M + "FrameRange;J)L" + M + "FrozenList;", L + "SegmentComparator", "compare(L" + M + "SegmentDescriptor;L" + M + "SegmentDescriptor;L" + M + "AudioFormat;L" + M + "FeatureTimeline;L" + L + "LevelerCalibrationProfile;)L" + M + "SimilarityScore;");
        String directed = helpers.get(f.owner);
        if (directed != null)
        {
            int access = f.owner.endsWith("SegmentComparator") ? 1 : 8;
            if (f.methods.stream().filter(m -> (m.name() + m.descriptor()).equals(directed) && m.access() == access && m.code() != null).count() != 1) fail(out, "STATIC_DIRECTED_API", f, directed, "ADR010 exact declaration required");
            String name = directed.substring(0, directed.indexOf('('));
            if (f.methods.stream().filter(m -> m.name().equals(name)).count() != 1) fail(out, "STATIC_DIRECTED_API", f, name, "No old overload/adjacent helper");
        }
        for (var method : f.methods) if (method.code() != null) for (var ins : method.code().instructions())
        {
            if (ins.opcode() < 0xb6 || ins.opcode() > 0xb9) continue;
            var ref = f.member(ins.operand()); String expected = helpers.get(ref.owner());
            if (expected != null && expected.startsWith(ref.name() + "(") && (!(ref.name() + ref.descriptor()).equals(expected) || ins.opcode() != (ref.owner().endsWith("SegmentComparator") ? 0xb6 : 0xb8))) fail(out, "STATIC_DIRECTED_API", f, method.name(), "ADR010 exact invoked descriptor/opcode required");
            if (ref.owner().equals(L + "BuildAlgorithmBinding") && ref.name().equals("<init>") && !f.owner.equals(M004StaticClosureContract.LOADER)) fail(out, "STATIC_BINDING_FACTORY", f, method.name(), "Only own-JAR loader constructs binding");
        }
        if (f.owner.equals(L + "BuildAlgorithmBinding") && f.methods.stream().anyMatch(m -> m.name().equals("<init>") && m.access() != 0)) fail(out, "STATIC_BINDING_FACTORY", f, "<init>", "Package-private binding factory only");
    }
    private static void staticTable(M004Classfile f, List<AsyncEscapeBytecodeGuard.Violation> out)
    {
        if (!f.owner.equals(L + "ConformanceRequirement")) return;
        List<String> constructor = new ArrayList<>(List.of("2a", "b7:java/lang/Object.<init>()V"));
        JsonObject values = M004StaticClosureContract.CONFORMANCE.getAsJsonObject("staticValues");
        for (var field : M004BytecodePolicy.FIELDS.get(f.owner))
            if (field.access() == 0x12 && field.descriptor().equals("Ljava/lang/String;")) constructor.addAll(List.of("2a", "S:" + values.get(field.name()).getAsString(), "b5:" + f.owner + "." + field.name() + field.descriptor()));
        constructor.addAll(List.of("2a", "bb:" + M + "FrozenList", "59", "I:94", "bd:java/lang/Object"));
        var readingFields = M004BytecodePolicy.FIELDS.get(M + "RequiredOfficialReading");
        String descriptor = "(" + String.join("", readingFields.stream().map(M004BytecodePolicy.FieldRule::descriptor).toList()) + ")V";
        JsonArray readings = M004StaticClosureContract.RUNTIME_PROFILE.getAsJsonArray("requiredReadings");
        for (int i = 0; i < readings.size(); i++)
        {
            constructor.addAll(List.of("59", "I:" + i, "bb:" + M + "RequiredOfficialReading", "59"));
            JsonObject reading = readings.get(i).getAsJsonObject();
            for (var field : readingFields)
            {
                String type = field.descriptor(); JsonElement value = reading.get(field.name());
                if (type.equals("D")) constructor.add("D:" + Long.toUnsignedString(Long.parseUnsignedLong(value.getAsString(), 16), 16));
                else if (type.equals("I") || type.equals("J")) constructor.add(type + ":" + value.getAsLong());
                else if (type.equals("Ljava/lang/String;")) constructor.add("S:" + value.getAsString());
                else constructor.add("b2:" + type.substring(1, type.length() - 1) + "." + value.getAsString() + type);
            }
            constructor.addAll(List.of("b7:" + M + "RequiredOfficialReading.<init>" + descriptor, "53"));
        }
        constructor.addAll(List.of("b7:" + M + "FrozenList.<init>([Ljava/lang/Object;)V", "b5:" + f.owner + ".requiredReadingsL" + M + "FrozenList;", "b1"));
        boolean valid = exact(f, "<init>", "()V", 2, constructor)
                && exact(f, "<clinit>", "()V", 8, List.of("bb:" + f.owner, "59", "b7:" + f.owner + ".<init>()V", "b3:" + f.owner + ".OFFICIAL_LOUDNESS_V1L" + f.owner + ";", "b1"));
        if (!valid) fail(out, "STATIC_REQUIRED_TABLE", f, "<init>", "94 canonical constructor arguments/order/IDs/pins/sole singleton initializer required");
    }
    static void fail(List<AsyncEscapeBytecodeGuard.Violation> out, String code, M004Classfile f, String method, String detail)
    { out.add(new AsyncEscapeBytecodeGuard.Violation(code, f.owner, method, -1, -1, f.owner, "", "", detail)); }
}
