package com.quickmaster.processing.dynamics.leveler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Static AC10 facet only. No runtime-retention, same-thread or RSS claims. */
final class AsyncEscapeBytecodeGuard
{
    static final String ENUM_FAILURE = "ENUM_VALUEOF_NONCANONICAL";
    private static final String ENUM_DESCRIPTOR = "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;";
    private static final String CONCAT_OWNER = "java/lang/invoke/StringConcatFactory";
    private static final String CONCAT_DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
    enum Rule { UNIVERSE, DEPENDENCY, FIELDS, CALLS, BOOTSTRAPS, PUTSTATIC, ENUM_CANONICAL, FINALIZER, NATIVE, CLASS_SHAPE, ABSTRACT, CONSTANT_VALUES, CLOSED_ENUM, FIXED_PROFILE, DIRECTED_API, LOADER_BOUNDARY, STATIC_TABLE, ACTIVE_PUBLICATION_AUTHORITY, ACTIVE_COMPARISON_CALLS }
    record Violation(String code, String classfile, String method, int offset, int opcode,
                     String owner, String name, String descriptor, String detail) { }
    record FileEvidence(String owner, String sha256, int fields, int methods) { }
    record Result(List<FileEvidence> files, List<Violation> violations)
    {
        boolean passed() { return violations.isEmpty(); }
        void requirePassed()
        {
            if (!passed()) throw new AssertionError("AC10 static guard failed: " + violations.size() + " violations\n"
                    + String.join("\n", violations.stream().map(Object::toString).toList()));
        }
    }

    static Result scan(Path root) throws Exception { return scan(root, Set.of()); }

    /** Explicit S-001 successor entry point; legacy scan retains its original contract. */
    static Result scanActive(Path root) throws Exception {
        String vendor = sha(Files.readAllBytes(Path.of(System.getProperty("qm.dsparkJar", "libs/dspark-0.1.0.jar"))));
        if (!vendor.equals("de368f326f668267d0cba20f34135efb466dafc53f84a4aee32d8af4347e4141"))
            return new Result(List.of(), List.of(new Violation("ACTIVE_DSPARK_PIN", "", "", -1, -1,
                    "com/dspark/analysis/TruePeak", "", "", "Exact independently audited vendor JAR required: " + vendor)));
        return scan(root, Set.of(), true);
    }
    static List<Violation> inspectActive(byte[] bytes)
    {
        List<Violation> out = new ArrayList<>();
        M004Classfile file = M004Classfile.parse(bytes);
        boolean boundary = ActiveLevelerGuardContract.boundaries().containsKey(file.owner);
        inspect(file, boundary, Set.of(), Set.of(), out, true);
        if (!boundary) out.addAll(M004StaticClosurePolicy.inspectActive(file, Set.of()));
        return List.copyOf(out);
    }
    // Named active equivalent of the unchanged legacy deletion-control entry point.
    static List<Violation> inspectActiveControl(byte[] bytes, Set<Rule> removed, boolean boundary)
    {
        List<Violation> out=new ArrayList<>();
        inspect(M004Classfile.parse(bytes),boundary,removed,Set.of(),out,true);
        return List.copyOf(out);
    }

    // Package-private and test-only: deletion controls never change scan(Path)'s policy.
    static Result deletionControl(Path root, Rule rule) throws Exception { return scan(root, Set.of(rule)); }

    private static Result scan(Path root, Set<Rule> removed) throws Exception
    { return scan(root, removed, false); }
    private static Result scan(Path root, Set<Rule> removed, boolean active) throws Exception
    {
        Set<String> owners = active ? ActiveLevelerGuardContract.owners() : M004BytecodePolicy.SCANNED_M004_CLASSFILES;
        Map<String,String> boundaries = active ? ActiveLevelerGuardContract.boundaries() : M004BytecodePolicy.BOUNDARIES;
        List<Violation> violations = new ArrayList<>();
        List<FileEvidence> evidence = new ArrayList<>();
        Set<String> actual = new TreeSet<>();
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) throw new IOException("Missing compiled classfile root " + normalized);
        try (Stream<Path> paths = Files.walk(normalized))
        {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).sorted().toList())
            {
                String relative = normalized.relativize(path).toString().replace('\\', '/');
                String name = relative.substring(0, relative.length() - 6);
                if (active && owners.stream().anyMatch(o -> name.equals(o) || name.startsWith(o + "$")) || name.startsWith(M004BytecodePolicy.LEVELER)
                        || name.equals(M004BytecodePolicy.DYNAMICS + "LevelerProcessor")
                        || name.startsWith(M004BytecodePolicy.DYNAMICS + "LevelerProcessor$")
                        || name.equals(M004BytecodePolicy.DYNAMICS + "AnalysisDynamicsProcessor")
                        || name.startsWith(M004BytecodePolicy.DYNAMICS + "AnalysisDynamicsProcessor$")) actual.add(name);
            }
        }
        if (!removed.contains(Rule.UNIVERSE))
        {
            if (active) {
                Set<String> missing = new TreeSet<>(owners); missing.removeAll(actual);
                Set<String> extra = new TreeSet<>(actual); extra.removeAll(owners);
                for (String n : missing) violation(violations,"CLASSFILE_UNIVERSE_MISSING",n,"",-1,-1,n,"","","missing active literal member");
                for (String n : extra) violation(violations,"CLASSFILE_UNIVERSE_EXTRA",n,"",-1,-1,n,"","","extra active member");
            } else violations.addAll(universeCheck(actual, false));
        }
        Set<String> all = new TreeSet<>(actual); all.addAll(boundaries.keySet());
        for (String name : all)
        {
            Path path = normalized.resolve(name + ".class");
            if (!Files.isRegularFile(path))
            {
                violation(violations, "BOUNDARY_CLASSFILE_MISSING", name, "", -1, -1, name, "", "", "missing M003 boundary");
                continue;
            }
            byte[] bytes = Files.readAllBytes(path);
            try
            {
                M004Classfile file = M004Classfile.parse(bytes);
                evidence.add(new FileEvidence(name, sha(bytes), file.fields.size(), file.methods.size()));
                if (!file.owner.equals(name)) violation(violations, "CLASSFILE_OWNER_PATH", name, "", -1, -1, file.owner, "", "", "this_class differs from relative path");
                boolean boundary = boundaries.containsKey(name);
                if (boundary && !boundaries.get(name).equals(sha(bytes)))
                    violation(violations, "BOUNDARY_HASH_MISMATCH", name, "", -1, -1, name, "", "", "expected " + boundaries.get(name) + " actual " + sha(bytes));
                inspect(file, boundary, removed, Set.of(), violations, active);
                if (!boundary) violations.addAll(active ? M004StaticClosurePolicy.inspectActive(file, removed) : M004StaticClosurePolicy.inspect(file, removed));
            }
            catch (IllegalArgumentException ex)
            { violation(violations, M004Classfile.FORMAT, name, "", -1, -1, name, "", "", ex.getMessage()); }
        }
        return new Result(List.copyOf(evidence), List.copyOf(violations));
    }

    static List<Violation> universeCheck(Set<String> actual, boolean removeRule)
    {
        if (removeRule) return List.of();
        List<Violation> violations = new ArrayList<>();
        Set<String> missing = new TreeSet<>(M004BytecodePolicy.SCANNED_M004_CLASSFILES); missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual); extra.removeAll(M004BytecodePolicy.SCANNED_M004_CLASSFILES);
        for (String name : missing) violation(violations, "CLASSFILE_UNIVERSE_MISSING", name, "", -1, -1, name, "", "", "missing literal member");
        for (String name : extra) violation(violations, "CLASSFILE_UNIVERSE_EXTRA", name, "", -1, -1, name, "", "", "extra binary, including nested/anonymous helper");
        return List.copyOf(violations);
    }

    static List<Violation> inspect(byte[] bytes)
    { return inspectControl(bytes, Set.of(), false); }
    static List<Violation> inspectControl(byte[] bytes, Set<Rule> removed, boolean boundary)
    {
        List<Violation> violations = new ArrayList<>();
        inspect(M004Classfile.parse(bytes), boundary, removed, Set.of(), violations);
        return List.copyOf(violations);
    }
    static List<Violation> inspectWithoutPermit(byte[] bytes, M004BytecodePolicy.Call permit)
    {
        List<Violation> violations = new ArrayList<>();
        inspect(M004Classfile.parse(bytes), false, Set.of(), Set.of(permit), violations);
        return List.copyOf(violations);
    }

    private static void inspect(M004Classfile file, boolean boundary, Set<Rule> removed, Set<M004BytecodePolicy.Call> denied, List<Violation> out)
    { inspect(file,boundary,removed,denied,out,false); }
    private static void inspect(M004Classfile file, boolean boundary, Set<Rule> removed, Set<M004BytecodePolicy.Call> denied, List<Violation> out, boolean active)
    {
        methodTable(file, boundary, removed, out, active);
        if ((active ? ActiveLevelerGuardContract.isEnum(file.owner) : M004BytecodePolicy.ENUMS.contains(file.owner))) enumMethods(file, removed, out);
        if (boundary) return; // Frozen M003 code is not retroactively subject to full call policy.
        if (!removed.contains(Rule.CLASS_SHAPE) && !(active && ActiveLevelerGuardContract.shape(file)) && !M004StaticClosureContract.A.equals(file.owner)
                && ((file.access & 0x10) == 0 || (file.access & (0x200 | 0x400 | 0x1000 | 0x2000)) != 0
                || file.owner.contains("$") || file.classAttributes.contains("EnclosingMethod") || file.classAttributes.contains("Record")))
            violation(out, "M004_CLASS_SHAPE", file.owner, "", -1, -1, file.owner, "", "", "M004/owners must be final, concrete, non-synthetic and non-nested");
        if (!removed.contains(Rule.FIELDS)) fields(file, out, active);
        if (!removed.contains(Rule.DEPENDENCY)) dependencies(file, out, active);
        Set<Integer> concatHandles = bootstraps(file, removed, out, active);
        for (M004Classfile.Method method : file.methods)
        {
            if (method.code() == null) continue;
            List<M004Classfile.Instruction> instructions = method.code().instructions();
            for (int i = 0; i < instructions.size(); i++)
            {
                M004Classfile.Instruction instruction = instructions.get(i);
                int op = instruction.opcode();
                if (op >= 0xB2 && op <= 0xB9)
                {
                    M004Classfile.Member ref = file.member(instruction.operand());
                    if (op == 0xB3 && !removed.contains(Rule.PUTSTATIC) && !permittedPutstatic(file, method, ref, active))
                        at(out, "PRODUCTIVE_NON_CONSTANT_PUTSTATIC", file, method, instruction, ref, "write must target a literal atom/enum in own <clinit>");
                    if (op >= 0xB6 && !removed.contains(Rule.CALLS)) call(file, method, instruction, i, removed, denied, out, active);
                    if (op <= 0xB5 && !quickMaster(ref.owner()) && !jdkField(op, ref))
                        at(out, "BYTECODE_FIELD_NOT_ALLOWED", file, method, instruction, ref, "no literal JDK field rule");
                }
                if ((op == 0x12 || op == 0x13 || op == 0x14) && !removed.contains(Rule.BOOTSTRAPS))
                {
                    M004Classfile.Cp cp = file.pool[instruction.operand()];
                    if (cp.tag() == 15 || cp.tag() == 16 || cp.tag() == 17)
                        at(out, "BYTECODE_EXECUTABLE_HANDLE", file, method, instruction, null, "Code loads MethodHandle/MethodType/ConstantDynamic");
                    if (cp.tag() == 7 && !((active ? ActiveLevelerGuardContract.isEnum(file.owner) : M004BytecodePolicy.ENUMS.contains(file.owner)) && canonicalEnum(file, method))
                            && !(file.owner.equals(M004StaticClosureContract.LOADER) && file.className(instruction.operand()).equals(file.owner)))
                        at(out, "BYTECODE_CLASS_LITERAL", file, method, instruction, null, "Class literal outside canonical enum");
                }
                if (op == 0xBA && !removed.contains(Rule.BOOTSTRAPS))
                {
                    M004Classfile.Cp dynamic = file.cp(instruction.operand(), 18);
                    if (!concatHandles.contains(dynamic.a()))
                        at(out, "BYTECODE_BOOTSTRAP_NOT_ALLOWED", file, method, instruction, null, "invokedynamic bootstrap not closed StringConcat");
                }
                if ((op == 0xBB || op == 0xBD || op == 0xC0 || op == 0xC1 || op == 0xC5)
                        && !removed.contains(Rule.DEPENDENCY))
                {
                    String type = file.className(instruction.operand());
                    if (structuralType(type) && !(file.owner.equals(M004StaticClosureContract.LOADER) && type.equals("java/net/JarURLConnection")))
                        at(out, "BYTECODE_STRUCTURAL_LINKAGE_ESCAPE", file, method, instruction, null, "structural-only Class/invoke type used in Code: " + type);
                }
            }
        }
    }

    private static void methodTable(M004Classfile file, boolean boundary, Set<Rule> removed, List<Violation> out, boolean active)
    {
        for (M004Classfile.Method method : file.methods)
        {
            if ((method.access() & 8) == 0 && method.name().equals("finalize") && method.descriptor().equals("()V")
                    && !removed.contains(Rule.FINALIZER))
                declaration(out, "FINALIZER_DECLARATION", file, method, "instance finalize()V regardless of flags/Code/throws");
            if ((method.access() & 0x100) != 0 && !removed.contains(Rule.NATIVE))
                declaration(out, "NATIVE_METHOD_DECLARATION", file, method, "ACC_NATIVE regardless of Code/static");
            if ((method.access() & 0x400) != 0 && !boundary && !removed.contains(Rule.ABSTRACT)
                    && !(active ? ActiveLevelerGuardContract.abstractMethod(file, method) : M004StaticClosureContract.abstractMethod(file, method)))
                declaration(out, "ABSTRACT_METHOD_OUTSIDE_BOUNDARY", file, method, "only pinned M003 boundaries retain abstract methods");
            if ((method.access() & (0x100 | 0x400)) == 0 && method.code() == null
                    && !(method.name().equals("finalize") && method.descriptor().equals("()V")))
                declaration(out, "METHOD_CODE_MISSING", file, method, "executable method has no Code");
        }
    }

    private static void fields(M004Classfile file, List<Violation> out, boolean active)
    {
        List<M004BytecodePolicy.FieldRule> rules = active ? ActiveLevelerGuardContract.fields(file.owner) : M004BytecodePolicy.FIELDS.get(file.owner);
        if (rules == null)
        {
            violation(out, "FIELD_SCHEMA_UNADJUDICATED", file.owner, "", -1, -1, file.owner, "", "",
                    "accepted inputs do not enumerate a complete literal field schema; observed=" + file.fields);
            return;
        }
        List<M004BytecodePolicy.FieldRule> observed = file.fields.stream()
                .map(f -> new M004BytecodePolicy.FieldRule(f.name(), f.descriptor(), f.access())).toList();
        if (!rules.equals(observed))
            violation(out, "FIELD_SCHEMA_MISMATCH", file.owner, "", -1, -1, file.owner, "", "",
                    "expected ordered=" + rules + " observed=" + observed);
        for (M004Classfile.Field field : file.fields)
        {
            if ((field.access() & 8) != 0 && (field.access() & 0x10) == 0)
                violation(out, "PRODUCTIVE_NON_CONSTANT_STATIC", file.owner, "", field.start(), -1, file.owner,
                        field.name(), field.descriptor(), "static field is mutable, even when null");
            if ((field.access() & 0x18) == 0x18 && (field.descriptor().length() == 1 || field.descriptor().equals("Ljava/lang/String;"))
                    && field.constantIndex() == 0)
                violation(out, "STATIC_CONSTANT_VALUE_MISSING", file.owner, "", field.start(), -1, file.owner,
                        field.name(), field.descriptor(), "static final primitive/String requires ConstantValue");
        }
    }

    private static void dependencies(M004Classfile file, List<Violation> out, boolean active)
    {
        for (M004Classfile.Field field : file.fields)
            for (String type : M004Classfile.descriptor(field.descriptor(), false).classes())
                if (structuralType(type)) violation(out, "BYTECODE_STRUCTURAL_LINKAGE_ESCAPE", file.owner, "", field.start(), -1,
                        type, field.name(), field.descriptor(), "structural-only Class/invoke type retained as field");
        for (M004Classfile.Method method : file.methods)
            for (String type : M004Classfile.descriptor(method.descriptor(), true).classes())
                if (structuralType(type)) declaration(out, "BYTECODE_STRUCTURAL_LINKAGE_ESCAPE", file, method,
                        "structural-only Class/invoke type in application method signature: " + type);
        Set<String> jdk = new HashSet<>();
        for (M004BytecodePolicy.Call call : M004BytecodePolicy.JDK_CALLS) jdk.add(call.owner());
        jdk.addAll(Set.of("java/lang/Class", "java/lang/Enum", "java/lang/String", "java/lang/Object",
                "java/security/NoSuchAlgorithmException", "java/nio/charset/StandardCharsets", "java/nio/charset/Charset"));
        if (!file.bootstraps.isEmpty()) jdk.addAll(Set.of(CONCAT_OWNER, "java/lang/invoke/MethodHandles", "java/lang/invoke/MethodHandles$Lookup", "java/lang/invoke/MethodType", "java/lang/invoke/CallSite"));
        for (String owner : file.referencedClasses)
        {
            if (Set.of("java/lang/Deprecated", "java/lang/Exception", "java/io/IOException", "java/lang/Throwable").contains(owner))
            {
                if (!(M004StaticClosureContract.metadata(file, owner) || active && owner.equals("java/lang/Throwable") && ActiveLevelerGuardContract.levelerThrowable(file)))
                    violation(out, "BYTECODE_METADATA_ROLE_NOT_ALLOWED", file.owner, "", -1, -1, owner, "", "", "closed metadata context required: " + file.typeUses.stream().filter(u -> u.type().equals(owner)).toList());
                continue;
            }
            if (quickMaster(owner))
            {
                if (!(active ? ActiveLevelerGuardContract.owners().contains(owner) : M004BytecodePolicy.SCANNED_M004_CLASSFILES.contains(owner) || M004BytecodePolicy.BOUNDARIES.containsKey(owner)))
                    violation(out, "BYTECODE_DEPENDENCY_OUTSIDE_UNIVERSE", file.owner, "", -1, -1, owner, "", "", "reference including unused CP/descriptor/attribute");
            }
            else if (owner.startsWith("com/dspark/"))
            {
                if (!M004BytecodePolicy.DSPARK_OWNERS.contains(owner) && !(active && ActiveLevelerGuardContract.additionalType(file,owner)))
                    violation(out, "DSPARK_BINDING_UNADJUDICATED", file.owner, "", -1, -1, owner, "", "", "no approved exact owner binding");
            }
            else if (!jdk.contains(owner) && !(active && ActiveLevelerGuardContract.additionalType(file,owner)) && !(file.owner.equals(M004StaticClosureContract.LOADER) && M004StaticClosureContract.LOADER_TYPES.contains(owner)))
                violation(out, "BYTECODE_DEPENDENCY_NOT_ALLOWED", file.owner, "", -1, -1, owner, "", "", "no literal allowed class, including unused CP/descriptor/attribute");
        }
    }

    private static void call(M004Classfile file, M004Classfile.Method method, M004Classfile.Instruction instruction,
                             int index, Set<Rule> removed, Set<M004BytecodePolicy.Call> denied, List<Violation> out, boolean active)
    {
        M004Classfile.Member ref = file.member(instruction.operand());
        if (denied.contains(new M004BytecodePolicy.Call(instruction.opcode(), ref.owner(), ref.name(), ref.descriptor())))
        { at(out, "BYTECODE_CALL_NOT_ALLOWED", file, method, instruction, ref, "exact permission removed in isolated sensitivity control"); return; }
        if (quickMaster(ref.owner())) return; // outgoing owner closure is independently mandatory
        if (ref.owner().equals("java/lang/Enum") && ref.name().equals("valueOf")
                && !removed.contains(Rule.ENUM_CANONICAL) && canonicalEnum(file, method)) return;
        if (enumClone(file, method, instruction, index, active)) return;
        var tuple = new M004BytecodePolicy.Call(instruction.opcode(), ref.owner(), ref.name(), ref.descriptor());
        boolean allowed = M004BytecodePolicy.JDK_CALLS.contains(tuple) || M004StaticClosureContract.contextualCall(file, method, tuple) || active && ActiveLevelerGuardContract.additionalCall(file,method,tuple);
        if (M004StaticClosureContract.CODEC_CALLS.contains(tuple)) allowed = file.owner.equals(M004StaticClosureContract.CODEC);
        if (tuple.equals(new M004BytecodePolicy.Call(183, "java/lang/IndexOutOfBoundsException", "<init>", "(Ljava/lang/String;)V")))
            allowed &= file.owner.equals(M004BytecodePolicy.MODEL + "FrozenList") && (method.name() + method.descriptor()).equals("get(I)Ljava/lang/Object;")
                    || file.owner.equals(M004BytecodePolicy.MODEL + "BodyContextVector") && (method.name() + method.descriptor()).equals("componentAt(I)D");
        if (allowed && ref.owner().equals("java/lang/String") && ref.name().equals("<init>"))
        {
            var code = method.code().instructions();
            allowed = index > 0 && code.get(index - 1).opcode() == 0xB2 && file.member(code.get(index - 1).operand()).equals(new M004Classfile.Member("java/nio/charset/StandardCharsets", "US_ASCII", "Ljava/nio/charset/Charset;"));
        }
        if (allowed && ref.owner().equals("java/security/MessageDigest") && ref.name().equals("getInstance"))
        {
            List<M004Classfile.Instruction> code = method.code().instructions();
            allowed = index > 0 && (code.get(index - 1).opcode() == 0x12 || code.get(index - 1).opcode() == 0x13)
                    && file.pool[code.get(index - 1).operand()].tag() == 8
                    && file.string(code.get(index - 1).operand()).equals("SHA-256");
        }
        if (allowed && ref.owner().equals("java/lang/String") && ref.name().equals("getBytes"))
        {
            List<M004Classfile.Instruction> code = method.code().instructions();
            allowed = index > 0 && code.get(index - 1).opcode() == 0xB2
                    && file.member(code.get(index - 1).operand()).equals(new M004Classfile.Member(
                    "java/nio/charset/StandardCharsets", "US_ASCII", "Ljava/nio/charset/Charset;"));
        }
        if (!allowed) at(out, "BYTECODE_CALL_NOT_ALLOWED", file, method, instruction, ref, "no literal (opcode,owner,name,descriptor) or exact contextual rule");
    }

    private static boolean quickMaster(String owner) { return owner.startsWith("com/quickmaster/"); }
    private static boolean structuralType(String owner)
    {
        if (owner.startsWith("[")) return M004Classfile.descriptor(owner, false).classes().stream().anyMatch(AsyncEscapeBytecodeGuard::structuralType);
        return owner.equals("java/lang/Class") || owner.startsWith("java/lang/invoke/");
    }
    private static boolean jdkField(int op, M004Classfile.Member ref)
    {
        if (op != 0xB2) return false;
        return ((ref.owner().equals("java/lang/Math") || ref.owner().equals("java/lang/StrictMath"))
                && (ref.name().equals("E") || ref.name().equals("PI")) && ref.descriptor().equals("D"))
                || ref.equals(new M004Classfile.Member("java/nio/charset/StandardCharsets", "US_ASCII", "Ljava/nio/charset/Charset;"));
    }

    private static boolean permittedPutstatic(M004Classfile file, M004Classfile.Method method, M004Classfile.Member ref, boolean active)
    {
        if (active && ActiveLevelerGuardContract.annexStore(file,method,ref)) return true;
        if(active&&file.owner.equals(M004BytecodePolicy.LEVELER+"LevelerCalibrationProfile")&&method.name().equals("<clinit>")&&method.descriptor().equals("()V")
                &&ref.equals(new M004Classfile.Member(file.owner,"V2","L"+file.owner+";"))&&ActiveLevelerGuardContract.fixedProfileV2(file))return true;
        if (!method.name().equals("<clinit>") || !method.descriptor().equals("()V") || !ref.owner().equals(file.owner)) return false;
        List<M004BytecodePolicy.FieldRule> rules = active ? ActiveLevelerGuardContract.fields(file.owner) : M004BytecodePolicy.FIELDS.get(file.owner);
        if (rules == null || rules.stream().noneMatch(f -> f.name().equals(ref.name()) && f.descriptor().equals(ref.descriptor()) && (f.access() & 0x18) == 0x18)) return false;
        if ((active ? ActiveLevelerGuardContract.isEnum(file.owner) : M004BytecodePolicy.ENUMS.contains(file.owner)))
            return ref.descriptor().equals("L" + file.owner + ";") || ref.name().equals("$VALUES") && ref.descriptor().equals("[L" + file.owner + ";");
        return ref.equals(new M004Classfile.Member(M004BytecodePolicy.LEVELER + "LevelerCalibrationProfile", "V1", "L" + M004BytecodePolicy.LEVELER + "LevelerCalibrationProfile;"))
                || ref.equals(new M004Classfile.Member(M004BytecodePolicy.LEVELER + "LoudnessStandard", "BS1770_5", "L" + M004BytecodePolicy.LEVELER + "LoudnessStandard;"))
                || ref.equals(new M004Classfile.Member(M004BytecodePolicy.LEVELER + "ConformanceRequirement", "OFFICIAL_LOUDNESS_V1", "L" + M004BytecodePolicy.LEVELER + "ConformanceRequirement;"));
    }

    private static Set<Integer> bootstraps(M004Classfile file, Set<Rule> removed, List<Violation> out, boolean active)
    {
        if (active && ActiveLevelerGuardContract.recordProtocol(file)) return Set.of(0);
        if (removed.contains(Rule.BOOTSTRAPS)) return Set.of();
        Set<Integer> allowedBootstraps = new HashSet<>(), allowedHandles = new HashSet<>();
        for (int i = 0; i < file.bootstraps.size(); i++)
        {
            M004Classfile.Bootstrap bootstrap = file.bootstraps.get(i);
            M004Classfile.Cp handle = file.cp(bootstrap.handle(), 15);
            M004Classfile.Member member = file.member(handle.b());
            boolean allowed = handle.a() == 6 && file.pool[handle.b()].tag() == 10
                    && member.equals(new M004Classfile.Member(CONCAT_OWNER, "makeConcatWithConstants", CONCAT_DESCRIPTOR))
                    && bootstrap.arguments().size() >= 1 && file.pool[bootstrap.arguments().get(0)].tag() == 8;
            if (allowed)
            {
                for (int j = 1; j < bootstrap.arguments().size(); j++)
                    allowed &= Set.of(3, 4, 5, 6, 8).contains(file.pool[bootstrap.arguments().get(j)].tag());
                for (int j = 1; j < file.pool.length; j++)
                {
                    M004Classfile.Cp dynamic = file.pool[j];
                    if (dynamic == null || dynamic.tag() != 18 || dynamic.a() != i) continue;
                    String descriptor = file.utf(file.cp(dynamic.b(), 12).b());
                    M004Classfile.Descriptor shape = M004Classfile.descriptor(descriptor, true);
                    String recipe = file.string(bootstrap.arguments().get(0));
                    allowed &= shape.result().equals("Ljava/lang/String;")
                            && shape.parameters().stream().allMatch(p -> p.length() == 1 || p.equals("Ljava/lang/String;"))
                            && recipe.chars().filter(c -> c == 1).count() == shape.parameters().size()
                            && recipe.chars().filter(c -> c == 2).count() == bootstrap.arguments().size() - 1;
                }
            }
            if (allowed) { allowedBootstraps.add(i); allowedHandles.add(bootstrap.handle()); }
            else violation(out, "BYTECODE_BOOTSTRAP_NOT_ALLOWED", file.owner, "", i, -1, member.owner(), member.name(), member.descriptor(), "not closed StringConcatFactory linkage");
        }
        for (int i = 1; i < file.pool.length; i++)
        {
            M004Classfile.Cp cp = file.pool[i];
            if (cp == null) continue;
            if (cp.tag() == 17 || cp.tag() == 16 || cp.tag() == 15 && !allowedHandles.contains(i))
                violation(out, "BYTECODE_CONSTANT_LINKAGE_NOT_ALLOWED", file.owner, "", i, -1, "", "", "", "unused or executable MethodHandle/MethodType/ConstantDynamic outside concat");
        }
        return allowedBootstraps;
    }

    static List<Violation> enumCheck(byte[] bytes, boolean removeCanonicalException)
    {
        List<Violation> out = new ArrayList<>();
        enumMethods(M004Classfile.parse(bytes), removeCanonicalException ? Set.of(Rule.ENUM_CANONICAL) : Set.of(), out);
        return List.copyOf(out);
    }
    private static void enumMethods(M004Classfile file, Set<Rule> removed, List<Violation> out)
    {
        int valid = 0;
        for (M004Classfile.Method method : file.methods)
        {
            boolean callsEnum = method.code() != null && method.code().instructions().stream()
                    .filter(i -> i.opcode() >= 0xB6 && i.opcode() <= 0xB9)
                    .map(i -> file.member(i.operand())).anyMatch(m -> m.owner().equals("java/lang/Enum") && m.name().equals("valueOf"));
            if (method.name().equals("valueOf") || callsEnum)
            {
                if (!removed.contains(Rule.ENUM_CANONICAL) && canonicalEnum(file, method)) valid++;
                else declaration(out, ENUM_FAILURE, file, method, "noncanonical enum valueOf method/Code or removed exception");
            }
        }
        if (valid != 1) violation(out, ENUM_FAILURE, file.owner, "valueOf", -1, -1, file.owner,
                "valueOf", "(Ljava/lang/String;)L" + file.owner + ";", "requires exactly one canonical Java17 method; observed=" + valid);
    }
    private static boolean canonicalEnum(M004Classfile file, M004Classfile.Method method)
    {
        if ((file.access & 0x4000) == 0 || !method.name().equals("valueOf") || method.access() != 9
                || !method.descriptor().equals("(Ljava/lang/String;)L" + file.owner + ";")
                || method.code() == null || !method.code().handlers().isEmpty()) return false;
        List<M004Classfile.Instruction> code = method.code().instructions();
        if (code.size() != 5) return false;
        M004Classfile.Instruction load = code.get(0), invoke = code.get(2), cast = code.get(3);
        return (load.opcode() == 0x12 || load.opcode() == 0x13)
                && file.pool[load.operand()].tag() == 7 && file.className(load.operand()).equals(file.owner)
                && code.get(1).opcode() == 0x2A && invoke.opcode() == 0xB8
                && file.member(invoke.operand()).equals(new M004Classfile.Member("java/lang/Enum", "valueOf", ENUM_DESCRIPTOR))
                && cast.opcode() == 0xC0 && file.className(cast.operand()).equals(file.owner) && code.get(4).opcode() == 0xB0;
    }
    private static boolean enumClone(M004Classfile file, M004Classfile.Method method, M004Classfile.Instruction instruction, int index, boolean active)
    {
        if (!(active ? ActiveLevelerGuardContract.isEnum(file.owner) : M004BytecodePolicy.ENUMS.contains(file.owner)) || !method.name().equals("values")
                || method.access() != 9 || !method.descriptor().equals("()[L" + file.owner + ";")
                || !method.code().handlers().isEmpty() || instruction.opcode() != 0xB6) return false;
        List<M004Classfile.Instruction> code = method.code().instructions();
        return code.size() == 4 && index == 1 && code.get(0).opcode() == 0xB2
                && file.member(code.get(0).operand()).equals(new M004Classfile.Member(file.owner, "$VALUES", "[L" + file.owner + ";"))
                && file.member(instruction.operand()).equals(new M004Classfile.Member("[L" + file.owner + ";", "clone", "()Ljava/lang/Object;"))
                && code.get(2).opcode() == 0xC0 && file.className(code.get(2).operand()).equals("[L" + file.owner + ";")
                && code.get(3).opcode() == 0xB0;
    }

    private static void declaration(List<Violation> out, String code, M004Classfile file, M004Classfile.Method method, String detail)
    { violation(out, code, file.owner, method.name() + method.descriptor(), method.start(), -1, file.owner, method.name(), method.descriptor(), detail); }
    private static void at(List<Violation> out, String code, M004Classfile file, M004Classfile.Method method,
                           M004Classfile.Instruction instruction, M004Classfile.Member member, String detail)
    { violation(out, code, file.owner, method.name() + method.descriptor(), instruction.offset(), instruction.opcode(), member == null ? "" : member.owner(), member == null ? "" : member.name(), member == null ? "" : member.descriptor(), detail); }
    private static void violation(List<Violation> out, String code, String file, String method, int offset, int opcode,
                                  String owner, String name, String descriptor, String detail)
    { out.add(new Violation(code, file, method, offset, opcode, owner, name, descriptor, detail)); }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
