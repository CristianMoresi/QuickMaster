package com.quickmaster.processing.dynamics.leveler;

import com.google.gson.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Accepted normative annexes, pinned bytes. Never reads product source/reflection to grant permission. */
final class M004StaticClosureContract
{
    static final String BASE = "/leveler/contracts/";
    static final String A = "com/quickmaster/processing/dynamics/AnalysisDynamicsProcessor";
    static final String L = "com/quickmaster/processing/dynamics/leveler/";
    static final String M = L + "model/";
    static final String LOADER = L + "ConformanceArtifactLoader", CODEC = L + "ConformanceCodec";
    static final JsonObject STATIC = read("static-baseline-contract-001/static-baseline.001.json", "6f18e4d64926edbce089590ed9dabdf63a854b11fbb797bd1fdd1031705cf16b");
    static final JsonObject CONFORMANCE = read("conformance-contract-001/schema-delta.001.json", "59e4ad48616254d9d61a83793488790dfb85385416893b26578b84a1226396df");
    static final JsonObject RUNTIME_PROFILE = read("conformance-contract-001/runtime-profile.001.json", "80d1e8e6cf59e39bb74eb5c2e262cf64d50893b21e4d907d550c170e770348a4");
    static final Map<String, JsonObject> CLASSES = classes();
    static final Map<String, List<String>> ENUMS = enums();
    static final Set<M004BytecodePolicy.Call> ADDITIONAL_CALLS = calls(STATIC.getAsJsonArray("jdkAdditionalCalls"));
    static final Set<M004BytecodePolicy.Call> LOADER_CALLS = calls(CONFORMANCE.getAsJsonObject("guard").getAsJsonArray("loaderOnlyCalls"));
    static final Set<M004BytecodePolicy.Call> CODEC_CALLS = calls(CONFORMANCE.getAsJsonObject("guard").getAsJsonArray("codecOnlyAdditions"));
    static final Set<M004BytecodePolicy.Call> DSPARK_CALLS = calls(CONFORMANCE.getAsJsonObject("dsparkBoundary").getAsJsonArray("allowedCalls"));
    static final Set<String> LOADER_TYPES = new HashSet<>(strings(CONFORMANCE.getAsJsonObject("guard").getAsJsonArray("loaderOnlyTypeReferences")));
    static final Set<String> OWNED_STRING_CALLERS = Set.of(CODEC, LOADER, L + "BuildAlgorithmBinding", L + "ConformanceRun", M + "OfficialSignalEvidence", M + "RequiredSetReport", M + "StandardValidationReport");
    static final String ENGINE_METHOD = "analyzeShadow([FL" + M + "AudioFormat;L" + L + "CancellationToken;)L" + M + "ShadowAnalysisSnapshot;";

    private M004StaticClosureContract() { }
    static JsonObject read(String path, String pin)
    {
        try
        {
            byte[] bytes;
            try (var input = Objects.requireNonNull(M004StaticClosureContract.class.getResourceAsStream(BASE + path),
                    "Missing contract resource: " + path)) { bytes = input.readAllBytes(); }
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(pin)) throw new AssertionError("Normative annex changed: " + path + " expected=" + pin + " actual=" + actual);
            return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        catch (Exception ex) { throw new AssertionError("Cannot read normative annex " + path, ex); }
    }
    static List<String> strings(JsonArray array) { List<String> out = new ArrayList<>(); for (JsonElement e : array) out.add(e.getAsString()); return List.copyOf(out); }
    static String str(JsonObject o, String key) { return o.get(key).getAsString(); }
    static int flags(String text)
    {
        if (text.startsWith("0x")) return Integer.parseInt(text.substring(2), 16);
        int flags = 0;
        for (String word : text.split(" ")) flags |= switch (word)
        { case "public" -> 1; case "private" -> 2; case "protected" -> 4; case "static" -> 8; case "final" -> 16; case "volatile" -> 64; case "synthetic" -> 4096; case "enum" -> 16384; case "abstract" -> 1024; case "package-private" -> 0; default -> throw new AssertionError("Unknown normative modifier " + word); };
        return flags;
    }
    static String descriptor(String type)
    {
        if (type.endsWith("[]")) return "[" + descriptor(type.substring(0, type.length() - 2));
        return switch (type) { case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C"; case "short" -> "S"; case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D"; default -> "L" + type.replace('.', '/') + ";"; };
    }
    private static Map<String, JsonObject> classes()
    {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (JsonElement e : CONFORMANCE.getAsJsonArray("classes"))
        {
            JsonObject c = e.getAsJsonObject(), n = new JsonObject();
            String owner = str(c, "name").replace('.', '/'); n.addProperty("owner", owner);
            n.addProperty("classAccess", flags(str(c, "classModifiers")) | 32);
            n.addProperty("ownership", str(c, "instanceOwnership"));
            JsonArray fields = new JsonArray();
            for (JsonElement fe : c.getAsJsonArray("fields"))
            {
                JsonObject f = fe.getAsJsonObject(), field = new JsonObject();
                field.addProperty("name", str(f, "name")); field.addProperty("descriptor", descriptor(str(f, "type")));
                field.addProperty("access", flags(str(f, "modifiers")));
                JsonObject cv = new JsonObject(); cv.addProperty("attribute", f.has("constantValue") ? "present" : "absent");
                if (f.has("constantValue")) { cv.addProperty("tag", "Integer"); cv.add("value", f.get("constantValue")); }
                field.add("constantValue", cv); fields.add(field);
            }
            n.add("fields", fields); out.put(owner, n);
        }
        for (JsonElement e : STATIC.getAsJsonArray("classes"))
        {
            JsonObject c = e.getAsJsonObject(), n = new JsonObject(); String owner = str(c, "owner");
            n.addProperty("owner", owner); n.addProperty("classAccess", flags(str(c, "classAccessHex")));
            n.addProperty("ownership", str(c, "ownership")); JsonArray fields = new JsonArray();
            for (JsonElement fe : c.getAsJsonArray("fields"))
            {
                JsonObject f = fe.getAsJsonObject(), field = new JsonObject(); field.addProperty("name", str(f, "name"));
                field.addProperty("descriptor", str(f, "descriptor")); field.addProperty("access", flags(str(f, "accessHex")));
                field.add("constantValue", f.get("constantValue").deepCopy()); fields.add(field);
            }
            n.add("fields", fields);
            if (out.containsKey(owner))
            {
                if (!owner.equals(L + "LoudnessStandard") || !out.get(owner).get("fields").equals(fields))
                    throw new AssertionError("Conflicting independent authorities for " + owner);
                continue; // ADR011 sole authority, ADR012 mirror verified and deduplicated.
            }
            out.put(owner, n);
        }
        ActiveLevelerGuardContract.composeCore(out);
        return Collections.unmodifiableMap(out);
    }
    private static Map<String, List<String>> enums()
    {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put(M + "LayoutStatus", List.of("READY", "INSUFFICIENT_FEATURES", "TOO_MANY_SEGMENTS")); // 08 V1
        for (var e : CONFORMANCE.getAsJsonObject("enums").entrySet()) out.put(M + e.getKey(), strings(e.getValue().getAsJsonArray()));
        for (JsonElement e : STATIC.getAsJsonArray("enums"))
        { JsonObject o = e.getAsJsonObject(); if (out.put(str(o, "owner"), strings(o.getAsJsonArray("namesInOrdinalOrder"))) != null) throw new AssertionError("Enum authority collision"); }
        return Collections.unmodifiableMap(out);
    }
    static Set<M004BytecodePolicy.Call> calls(JsonArray array)
    {
        Set<M004BytecodePolicy.Call> out = new LinkedHashSet<>();
        for (JsonElement e : array)
        {
            JsonObject c = e.getAsJsonObject(); String op = str(c, "opcode");
            int opcode = switch (op) { case "invokestatic" -> 184; case "invokespecial" -> 183; case "invokevirtual" -> 182; default -> Integer.parseInt(op); };
            out.add(new M004BytecodePolicy.Call(opcode, str(c, "owner"), str(c, "name"), str(c, "descriptor")));
        }
        return Set.copyOf(out);
    }
    static void mergeFields(Map<String, List<M004BytecodePolicy.FieldRule>> fields)
    {
        for (var entry : CLASSES.entrySet())
        {
            List<M004BytecodePolicy.FieldRule> rules = new ArrayList<>();
            for (JsonElement e : entry.getValue().getAsJsonArray("fields"))
            { JsonObject f = e.getAsJsonObject(); rules.add(new M004BytecodePolicy.FieldRule(str(f, "name"), str(f, "descriptor"), f.get("access").getAsInt())); }
            fields.put(entry.getKey(), List.copyOf(rules));
        }
    }
    static boolean abstractMethod(M004Classfile f, M004Classfile.Method m)
    { return f.owner.equals(A) && f.access == 0x421 && m.access() == 0x404 && m.code() == null && (m.name() + m.descriptor()).matches("computeFeatures\\(\\[FIII\\)V|mapFeaturesToGain\\(\\)V"); }
    static boolean metadata(M004Classfile f, String type)
    {
        if (type.equals("java/lang/Throwable")) return ActiveLevelerGuardContract.cleanup(f);
        List<M004Classfile.TypeUse> uses = f.typeUses.stream().filter(u -> u.type().equals(type)).toList();
        if (uses.isEmpty()) return false;
        if (type.equals("java/lang/Deprecated")) return f.owner.equals(A) && uses.stream().allMatch(u -> u.member().equals("gainEnv") && u.descriptor().equals("[F") && (u.role().equals("Deprecated") && u.detail().equals("field") || u.role().equals("RuntimeVisibleAnnotations") && u.detail().equals("pairs=0;depth=0")))
                && Arrays.stream(f.pool).filter(Objects::nonNull).noneMatch(cp -> cp.tag() == 7 && f.utf(cp.a()).equals(type));
        if (type.equals("java/lang/Exception"))
        {
            for (int i = 1; i < f.pool.length; i++) if (f.pool[i] != null && f.pool[i].tag() == 7 && f.utf(f.pool[i].a()).equals(type) && !f.stackMapClassIndices.contains(i)) return false;
            return f.owner.equals(L + "LevelerAnalysisEngine") && uses.stream().allMatch(u -> (u.member() + u.descriptor()).equals(ENGINE_METHOD) && (u.role().equals("StackMapTable") || u.role().equals("LocalVariableTable") && u.detail().equals("ex")));
        }
        if (type.equals("java/io/IOException")) return f.owner.equals(LOADER) && uses.stream().allMatch(u -> Set.of("catch", "throws", "StackMapTable", "LocalVariableTable").contains(u.role()));
        return false;
    }
    static boolean contextualCall(M004Classfile f, M004Classfile.Method m, M004BytecodePolicy.Call call)
    {
        if (LOADER_CALLS.contains(call)) return f.owner.equals(LOADER);
        if (CODEC_CALLS.contains(call)) return f.owner.equals(CODEC);
        if (call.equals(new M004BytecodePolicy.Call(183, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V"))) return OWNED_STRING_CALLERS.contains(f.owner);
        if (DSPARK_CALLS.contains(call))
        {
            if (call.owner().equals("com/dspark/core/FFTReal")) return f.owner.equals(L + "StructuralFeatureExtractor");
            return f.owner.equals("com/quickmaster/processing/dynamics/LevelerProcessor") && (call.name().equals("clamp") && (m.name() + m.descriptor()).matches("setLeveling\\(D\\)V|setSpeed\\(D\\)V") || call.name().equals("decibelsToGain") && (m.name() + m.descriptor()).equals("mapFeaturesToGain()V"));
        }
        return false;
    }
}
