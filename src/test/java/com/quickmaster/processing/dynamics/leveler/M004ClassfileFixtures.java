package com.quickmaster.processing.dynamics.leveler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Generates and mutates isolated fixtures; never writes target/classes. */
final class M004ClassfileFixtures
{
    static final Path RESULTS = Path.of(System.getProperty("qm.staticEvidence", "target/leveler-static-evidence"));
    static final Path SCRATCH = RESULTS.resolve("fixtures");
    record Compiled(Path root, byte[] bytes) { }
    record Added(byte[] bytes, int index) { }

    static Compiled compile(String binaryName, String source) throws Exception
    {
        Files.createDirectories(SCRATCH);
        Path root = Files.createTempDirectory(SCRATCH, "fixture-").toAbsolutePath();
        Files.writeString(root.resolve("fixture-source.txt"), source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("Supported JDK compiler required; no skips");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///" + binaryName.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE)
        { @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; } };
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null))
        {
            boolean success = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17", "-g:none", "-d", root.toString()), null, List.of(input)).call();
            if (!success) throw new AssertionError("fixture javac failed: " + diagnostics.getDiagnostics());
        }
        byte[] bytes = Files.readAllBytes(root.resolve(binaryName.replace('.', '/') + ".class"));
        Files.writeString(root.resolve("pristine.sha256"), AsyncEscapeBytecodeGuard.sha(bytes));
        return new Compiled(root, bytes);
    }

    static byte[] fieldless(String methods) throws Exception
    {
        return compile(M004BytecodePolicy.LEVELER.replace('/', '.') + "BoundaryDetector",
                "package com.quickmaster.processing.dynamics.leveler; final class BoundaryDetector { " + methods + " }").bytes();
    }

    static Added utf(byte[] original, String text) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeByte(1); out.writeUTF(text); }
        return addConstant(original, bytes.toByteArray());
    }
    static Added clazz(byte[] original, String name) throws Exception
    {
        Added utf = utf(original, name);
        return addConstant(utf.bytes, entry(7, utf.index));
    }
    static Added member(byte[] original, int tag, String owner, String name, String descriptor) throws Exception
    {
        Added type = clazz(original, owner), memberName = utf(type.bytes, name), memberDescriptor = utf(memberName.bytes, descriptor);
        Added nat = addConstant(memberDescriptor.bytes, entry(12, memberName.index, memberDescriptor.index));
        return addConstant(nat.bytes, entry(tag, type.index, nat.index));
    }
    static Added addConstant(byte[] original, byte[] entry) throws Exception
    {
        M004Classfile file = M004Classfile.parse(original);
        if (file.pool.length == 65535) throw new AssertionError("fixture constant pool full");
        byte[] result = replace(original, file.poolEnd, file.poolEnd, entry);
        putU2(result, 8, file.pool.length + ((entry[0] == 5 || entry[0] == 6) ? 2 : 1));
        return new Added(result, file.pool.length);
    }
    static byte[] entry(int tag, int... indices) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes))
        { out.writeByte(tag); for (int index : indices) out.writeShort(index); }
        return bytes.toByteArray();
    }
    static byte[] replace(byte[] original, int start, int end, byte[] data)
    {
        byte[] result = new byte[original.length - (end - start) + data.length];
        System.arraycopy(original, 0, result, 0, start);
        System.arraycopy(data, 0, result, start, data.length);
        System.arraycopy(original, end, result, start + data.length, original.length - end);
        return result;
    }
    static byte[] method(byte[] original, String oldName, String newName, String descriptor,
                         int access, byte[] code, int maxStack, int maxLocals, int[][] handlers) throws Exception
    {
        Added name = utf(original, newName), desc = utf(name.bytes, descriptor), codeName = utf(desc.bytes, "Code");
        M004Classfile file = M004Classfile.parse(codeName.bytes);
        M004Classfile.Method old = file.methods.stream().filter(m -> m.name().equals(oldName)).findFirst().orElseThrow();
        byte[] serialized = methodInfo(name.index, desc.index, access, codeName.index, code, maxStack, maxLocals, handlers);
        return replace(codeName.bytes, old.start(), old.end(), serialized);
    }
    static byte[] addMethod(byte[] original, String name, String descriptor, int access,
                            byte[] code, int maxStack, int maxLocals) throws Exception
    {
        Added nameCp = utf(original, name), descCp = utf(nameCp.bytes, descriptor), codeCp = utf(descCp.bytes, "Code");
        M004Classfile file = M004Classfile.parse(codeCp.bytes);
        byte[] serialized = methodInfo(nameCp.index, descCp.index, access, codeCp.index, code, maxStack, maxLocals, new int[0][]);
        byte[] result = replace(codeCp.bytes, file.classAttributesOffset, file.classAttributesOffset, serialized);
        putU2(result, file.methodCountOffset, file.methods.size() + 1);
        return result;
    }
    private static byte[] methodInfo(int name, int descriptor, int access, int codeName,
                                      byte[] code, int stack, int locals, int[][] handlers) throws Exception
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes))
        {
            out.writeShort(access); out.writeShort(name); out.writeShort(descriptor);
            out.writeShort(code == null ? 0 : 1);
            if (code != null)
            {
                out.writeShort(codeName); out.writeInt(12 + code.length + handlers.length * 8);
                out.writeShort(stack); out.writeShort(locals); out.writeInt(code.length); out.write(code);
                out.writeShort(handlers.length);
                for (int[] handler : handlers) for (int value : handler) out.writeShort(value);
                out.writeShort(0);
            }
        }
        return bytes.toByteArray();
    }
    static byte[] replaceOperand(byte[] original, String methodName, int opcode, int operand) throws Exception
    {
        M004Classfile file = M004Classfile.parse(original);
        M004Classfile.Code code = file.methods.stream().filter(m -> m.name().equals(methodName)).findFirst().orElseThrow().code();
        M004Classfile.Instruction instruction = code.instructions().stream().filter(i -> i.opcode() == opcode).findFirst().orElseThrow();
        byte[] bytes = original.clone();
        int at = code.absoluteStart() + instruction.offset() + 1;
        if (opcode == 0x12) { if (operand > 255) throw new AssertionError("ldc fixture operand overflow"); bytes[at] = (byte) operand; }
        else putU2(bytes, at, operand);
        return bytes;
    }
    static void putU2(byte[] bytes, int offset, int value) { bytes[offset] = (byte) (value >>> 8); bytes[offset + 1] = (byte) value; }
    static void putU4(byte[] bytes, int offset, int value)
    { putU2(bytes, offset, value >>> 16); putU2(bytes, offset + 2, value); }

    static void persist(String name, byte[] original, byte[] mutated) throws Exception
    {
        Files.createDirectories(RESULTS.resolve("mutants"));
        Path root = Files.createTempDirectory(RESULTS.resolve("mutants"), name + "-");
        Files.write(root.resolve("pristine.class"), original);
        Files.write(root.resolve("mutated.class"), mutated);
        Files.writeString(root.resolve("sha256.txt"), "pristine=" + AsyncEscapeBytecodeGuard.sha(original)
                + "\nmutated=" + AsyncEscapeBytecodeGuard.sha(mutated) + "\n");
    }
}
