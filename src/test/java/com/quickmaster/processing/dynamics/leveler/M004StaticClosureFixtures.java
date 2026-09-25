package com.quickmaster.processing.dynamics.leveler;

import java.nio.file.*;

final class M004StaticClosureFixtures
{
    private M004StaticClosureFixtures() { }
    static byte[] real(String owner) throws Exception
    { return Files.readAllBytes(Path.of(System.getProperty("qm.staticClasses", "target/classes"), owner + ".class")); }
    static byte[] fieldName(byte[] original, String field, String replacement) throws Exception
    {
        var added = M004ClassfileFixtures.utf(original, replacement); byte[] bytes = added.bytes().clone();
        var f = M004Classfile.parse(bytes).fields.stream().filter(x -> x.name().equals(field)).findFirst().orElseThrow();
        M004ClassfileFixtures.putU2(bytes, f.start() + 2, added.index()); return bytes;
    }
    static byte[] fieldDescriptor(byte[] original, String field, String replacement) throws Exception
    {
        var added = M004ClassfileFixtures.utf(original, replacement); byte[] bytes = added.bytes().clone();
        var f = M004Classfile.parse(bytes).fields.stream().filter(x -> x.name().equals(field)).findFirst().orElseThrow();
        M004ClassfileFixtures.putU2(bytes, f.start() + 4, added.index()); return bytes;
    }
    static byte[] fieldAccess(byte[] original, String field, int access)
    {
        byte[] bytes = original.clone(); var f = M004Classfile.parse(bytes).fields.stream().filter(x -> x.name().equals(field)).findFirst().orElseThrow();
        M004ClassfileFixtures.putU2(bytes, f.start(), access); return bytes;
    }
    static byte[] removeField(byte[] original, String field)
    {
        var f = M004Classfile.parse(original); var removed = f.fields.stream().filter(x -> x.name().equals(field)).findFirst().orElseThrow();
        byte[] bytes = M004ClassfileFixtures.replace(original, removed.start(), removed.end(), new byte[0]);
        M004ClassfileFixtures.putU2(bytes, f.fieldCountOffset, f.fields.size() - 1); return bytes;
    }
    static byte[] call(byte[] original, String method, M004BytecodePolicy.Call originalCall, M004BytecodePolicy.Call changed) throws Exception
    {
        var added = M004ClassfileFixtures.member(original, 10, changed.owner(), changed.name(), changed.descriptor());
        byte[] bytes = added.bytes().clone(); var f = M004Classfile.parse(bytes);
        var m = f.methods.stream().filter(x -> (x.name() + x.descriptor()).equals(method)).findFirst().orElseThrow();
        var ins = m.code().instructions().stream().filter(i -> i.opcode() == originalCall.opcode() && f.member(i.operand()).equals(new M004Classfile.Member(originalCall.owner(), originalCall.name(), originalCall.descriptor()))).findFirst().orElseThrow();
        bytes[m.code().absoluteStart() + ins.offset()] = (byte) changed.opcode();
        M004ClassfileFixtures.putU2(bytes, m.code().absoluteStart() + ins.offset() + 1, added.index()); return bytes;
    }
    static byte[] nop(byte[] original, String name) throws Exception
    {
        var m = M004Classfile.parse(original).methods.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        byte[] bytes = new byte[m.code().bytes().length + 1]; System.arraycopy(m.code().bytes(), 0, bytes, 1, bytes.length - 1);
        // Used only on straight-line canonical bodies, no branches/handlers to relocate.
        return M004ClassfileFixtures.method(original, name, name, m.descriptor(), m.access(), bytes, m.code().maxStack(), m.code().maxLocals(), new int[0][]);
    }
}
