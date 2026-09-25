package com.quickmaster.processing.dynamics.leveler;

import java.util.*;

/** Static same-JAR perimeter. Literal provenance and fail-branch reachability, not substring/source scans. */
final class M004StaticClosureLoader
{
    private M004StaticClosureLoader() { }
    static void inspect(M004Classfile f, List<AsyncEscapeBytecodeGuard.Violation> out)
    {
        boolean valid = true; int anchors = 0, jars = 0;
        Set<String> publicApi = Set.of("loadCurrent()L" + M004StaticClosureContract.M + "StandardValidationReport;", "currentBuildBinding()L" + M004StaticClosureContract.L + "BuildAlgorithmBinding;");
        Set<String> entries = Set.of("META-INF/quickmaster/leveler-conformance.json", "META-INF/quickmaster/leveler-conformance-profile.json");
        Set<String> attributes = Set.of("QuickMaster-Loudness-Attestation-SHA256", "QuickMaster-Loudness-Runner-SHA256");
        for (var m : f.methods)
        {
            if ((m.access() & 2) == 0 && !(m.access() == 8 && publicApi.contains(m.name() + m.descriptor()))) valid = false;
            if (m.code() == null) { valid = false; continue; }
            var code = m.code().instructions(); List<String> tokens = M004StaticClosurePolicy.tokens(f, m);
            int coneLocal = literalCone(f, m);
            for (int i = 0; i < code.size(); i++)
            {
                var ins = code.get(i);
                if ((ins.opcode() == 0x12 || ins.opcode() == 0x13) && f.pool[ins.operand()].tag() == 7)
                    valid &= i + 2 < code.size() && f.className(ins.operand()).equals(f.owner) && string(f, code.get(i + 1)).equals("ConformanceArtifactLoader.class") && isCall(f, code.get(i + 2), "java/lang/Class", "getResource");
                if (ins.opcode() < 0xb6 || ins.opcode() > 0xb9) continue;
                var ref = f.member(ins.operand());
                if (ref.owner().equals("java/lang/Class") && ref.name().equals("getResource"))
                {
                    anchors++; valid &= i >= 2 && string(f, code.get(i - 1)).equals("ConformanceArtifactLoader.class") && tokens.get(i - 2).equals("CLASS:" + f.owner);
                }
                if (ref.owner().equals("java/util/jar/Attributes") && ref.name().equals("getValue")) valid &= i > 0 && attributes.contains(string(f, code.get(i - 1)));
                if (ref.owner().equals("java/util/jar/JarFile") && ref.name().equals("getJarEntry"))
                {
                    boolean literal = i > 0 && entries.contains(string(f, code.get(i - 1)));
                    boolean cone = i >= 3 && coneLocal >= 0 && code.get(i - 1).opcode() == 0x32 && loadLocal(code.get(i - 3)) == coneLocal && isIntLoad(code.get(i - 2));
                    valid &= literal || cone;
                }
                if (ref.owner().equals("java/net/JarURLConnection") && ref.name().equals("getJarFile"))
                {
                    jars++;
                    valid &= validatedProtocol(f, m, i, "jar") && validatedProtocol(f, m, i, "file") && validatedEmptyHost(f, m, i) && ownUrlFlow(f, m, i);
                }
                if (ref.owner().equals("java/net/URLConnection") && ref.name().equals("setUseCaches")) valid &= i > 0 && tokens.get(i - 1).equals("I:0");
            }
        }
        // Enforce cleanup even when a mutation removes all Throwable references;
        // absence of the contextual type must not make the finally contract vanish.
        valid &= anchors == 1 && jars == 1 && ActiveLevelerGuardContract.cleanup(f);
        if (!valid) M004StaticClosurePolicy.fail(out, "STATIC_LOADER_PERIMETER", f, "", "Own literal anchor/local jar+file+empty host guards, fixed entry/manifest provenance and closed factory API required");
    }
    private static int literalCone(M004Classfile f, M004Classfile.Method m)
    {
        List<String> expected = M004StaticClosureContract.strings(M004StaticClosureContract.CONFORMANCE.getAsJsonObject("hashing").getAsJsonArray("algorithmClasses")).stream().map(n -> n.replace('.', '/') + ".class").toList();
        var code = m.code().instructions(); var tokens = M004StaticClosurePolicy.tokens(f, m);
        for (int start = 0; start + 2 + expected.size() * 4 < code.size(); start++)
        {
            if (!tokens.get(start).equals("I:" + expected.size()) || !tokens.get(start + 1).equals("bd:java/lang/String")) continue;
            boolean valid = true;
            for (int j = 0; j < expected.size(); j++) valid &= tokens.subList(start + 2 + j * 4, start + 6 + j * 4).equals(List.of("59", "I:" + j, "S:" + expected.get(j), "53"));
            int end = start + 2 + expected.size() * 4, local = storeLocal(code.get(end));
            if (!valid || local < 0) continue;
            for (int i = 0; i < code.size(); i++)
            {
                if (i != end && storeLocal(code.get(i)) == local) valid = false;
                if (loadLocal(code.get(i)) == local)
                    valid &= i + 1 < code.size() && (code.get(i + 1).opcode() == 0xbe || i + 2 < code.size() && isIntLoad(code.get(i + 1)) && code.get(i + 2).opcode() == 0x32);
            }
            if (valid) return local;
        }
        return -1;
    }
    private static boolean validatedProtocol(M004Classfile f, M004Classfile.Method m, int call, String protocol)
    {
        var code = m.code().instructions();
        for (int i = 0; i + 4 < call; i++)
            if (string(f, code.get(i)).equals(protocol) && loadLocal(code.get(i + 1)) >= 0 && isCall(f, code.get(i + 2), "java/net/URL", "getProtocol") && isCall(f, code.get(i + 3), "java/lang/String", "equals") && guarded(m, i + 4, call, false)) return true;
        return false;
    }
    private static boolean validatedEmptyHost(M004Classfile f, M004Classfile.Method m, int call)
    {
        var code = m.code().instructions();
        for (int i = 0; i + 2 < call; i++) if (isCall(f, code.get(i), "java/net/URL", "getHost") && isCall(f, code.get(i + 1), "java/lang/String", "length") && guarded(m, i + 2, call, true)) return true;
        return false;
    }
    private static boolean ownUrlFlow(M004Classfile f, M004Classfile.Method m, int call)
    {
        var c = m.code().instructions(); int own = -1, connection = -1, jarConnection = -1, inner = -1;
        for (int i = 1; i < call; i++)
        {
            if (isCall(f, c.get(i - 1), "java/lang/Class", "getResource")) own = storeLocal(c.get(i));
            if (i >= 2 && isCall(f, c.get(i - 1), "java/net/URL", "openConnection") && loadLocal(c.get(i - 2)) == own) connection = storeLocal(c.get(i));
            if (i >= 2 && c.get(i - 1).opcode() == 0xc0 && f.className(c.get(i - 1).operand()).equals("java/net/JarURLConnection") && loadLocal(c.get(i - 2)) == connection) jarConnection = storeLocal(c.get(i));
            if (i >= 2 && isCall(f, c.get(i - 1), "java/net/JarURLConnection", "getJarFileURL") && loadLocal(c.get(i - 2)) == jarConnection) inner = storeLocal(c.get(i));
            if (i >= 1 && isCall(f, c.get(i), "java/net/URL", "getProtocol"))
            {
                String literal = i >= 2 ? string(f, c.get(i - 2)) : ""; int local = loadLocal(c.get(i - 1));
                if (!(literal.equals("jar") && local == own || literal.equals("file") && local == inner)) return false;
            }
            if (isCall(f, c.get(i), "java/net/URL", "getHost") && loadLocal(c.get(i - 1)) != inner) return false;
        }
        if (own < 0 || connection < 0 || jarConnection < 0 || inner < 0 || loadLocal(c.get(call - 1)) != jarConnection) return false;
        for (int slot : new int[]{own, connection, jarConnection, inner}) if (c.stream().filter(i -> storeLocal(i) == slot).count() != 1) return false;
        return true;
    }
    private static boolean guarded(M004Classfile.Method m, int branch, int call, boolean zeroPasses)
    {
        var code = m.code().instructions(); var b = code.get(branch);
        if (b.opcode() != 0x99 && b.opcode() != 0x9a || b.targets().size() != 1) return false;
        int target = index(code, b.targets().get(0));
        boolean takenPass = (b.opcode() == 0x99) == zeroPasses;
        int fail = takenPass ? branch + 1 : target, pass = takenPass ? target : branch + 1;
        return !reachable(m, 0, call, branch) && !reachable(m, fail, call, -1) && reachable(m, pass, call, -1);
    }
    private static boolean reachable(M004Classfile.Method m, int from, int target, int excluded)
    {
        var c = m.code().instructions(); Set<Integer> seen = new HashSet<>(); ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(from);
        while (!queue.isEmpty())
        {
            int i = queue.remove(); if (i == excluded || i < 0 || i >= c.size() || !seen.add(i)) continue; if (i == target) return true;
            var ins = c.get(i); int op = ins.opcode();
            if (op >= 0xac && op <= 0xb1 || op == 0xbf) continue;
            for (int offset : ins.targets()) queue.add(index(c, offset));
            if (op != 0xa7 && op != 0xc8 && op != 0xaa && op != 0xab) queue.add(i + 1);
        }
        return false;
    }
    private static int index(List<M004Classfile.Instruction> c, int offset) { for (int i = 0; i < c.size(); i++) if (c.get(i).offset() == offset) return i; return -1; }
    private static String string(M004Classfile f, M004Classfile.Instruction i) { return (i.opcode() == 0x12 || i.opcode() == 0x13) && f.pool[i.operand()].tag() == 8 ? f.string(i.operand()) : ""; }
    private static boolean isCall(M004Classfile f, M004Classfile.Instruction i, String owner, String name) { return i.opcode() >= 0xb6 && i.opcode() <= 0xb9 && f.member(i.operand()).owner().equals(owner) && f.member(i.operand()).name().equals(name); }
    private static int loadLocal(M004Classfile.Instruction i) { return i.opcode() == 0x19 ? i.operand() : i.opcode() >= 0x2a && i.opcode() <= 0x2d ? i.opcode() - 0x2a : -1; }
    private static int storeLocal(M004Classfile.Instruction i) { return i.opcode() == 0x3a ? i.operand() : i.opcode() >= 0x4b && i.opcode() <= 0x4e ? i.opcode() - 0x4b : -1; }
    private static boolean isIntLoad(M004Classfile.Instruction i) { return i.opcode() == 0x15 || i.opcode() >= 0x1a && i.opcode() <= 0x1d; }
}
