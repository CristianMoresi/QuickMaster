package com.quickmaster.processing.dynamics.leveler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Test-only Java 17 classfile decoder. Does not load or execute inspected classes. */
final class M004Classfile
{
    static final String FORMAT = "CLASSFILE_FORMAT";
    record Cp(int tag, int a, int b, Object value, int start, int end) { }
    record Member(String owner, String name, String descriptor) { }
    record Field(int access, String name, String descriptor, int constantIndex, int start, int end) { }
    record Instruction(int offset, int opcode, int operand, int size, List<Integer> targets) { }
    record Handler(int start, int end, int target, int catchType) { }
    record Code(int maxStack, int maxLocals, byte[] bytes, int absoluteStart,
                List<Instruction> instructions, List<Handler> handlers) { }
    record Method(int access, String name, String descriptor, Code code, int start, int end) { }
    record Bootstrap(int handle, List<Integer> arguments) { }
    record Descriptor(List<String> parameters, String result, Set<String> classes, int slots) { }
    record TypeUse(String type, String role, String member, String descriptor, String detail) { }

    final byte[] bytes;
    final Cp[] pool;
    final int poolEnd;
    final int access;
    final String owner;
    final String superclass;
    final List<String> interfaces;
    final List<Field> fields;
    final List<Method> methods;
    final List<Bootstrap> bootstraps;
    final Set<String> referencedClasses;
    final Set<String> classAttributes;
    final int fieldCountOffset;
    final int methodCountOffset;
    final int classAttributesOffset;
    final List<TypeUse> typeUses = new ArrayList<>();
    final Set<Integer> stackMapClassIndices = new HashSet<>();
    private String attributeMember = "", attributeDescriptor = "", annotationRole = "";

    private M004Classfile(byte[] input)
    {
        bytes = input.clone();
        Reader in = new Reader(bytes, 0, bytes.length);
        require(in.u4() == 0xCAFEBABEL, "magic");
        require(in.u2() == 0, "minor version must be 0 (no preview)");
        require(in.u2() == 61, "major version must be Java 17 (61)");
        int count = in.u2();
        require(count > 0, "constant_pool_count");
        pool = new Cp[count];
        for (int i = 1; i < count; i++)
        {
            int start = in.position;
            int tag = in.u1(), a = 0, b = 0;
            Object value = null;
            switch (tag)
            {
                case 1 -> value = in.utf();
                case 3, 4 -> value = in.u4();
                case 5, 6 -> { value = (in.u4() << 32) | in.u4(); require(i + 1 < count, "wide CP last slot"); }
                case 7, 8, 16, 19, 20 -> a = in.u2();
                case 9, 10, 11, 12, 17, 18 -> { a = in.u2(); b = in.u2(); }
                case 15 -> { a = in.u1(); b = in.u2(); }
                default -> throw fail("unknown constant-pool tag " + tag);
            }
            pool[i] = new Cp(tag, a, b, value, start, in.position);
            if (tag == 5 || tag == 6) i++;
        }
        poolEnd = in.position;
        referencedClasses = new LinkedHashSet<>();
        validatePool();
        access = in.u2();
        owner = className(in.u2());
        require(!owner.startsWith("["), "this_class must not be array");
        int parent = in.u2();
        superclass = parent == 0 ? "" : className(parent);
        if (parent != 0) typeUse(superclass, "superclass", "");
        require(parent != 0 || owner.equals("java/lang/Object") || (access & 0x8000) != 0, "missing superclass");
        List<String> contracts = new ArrayList<>();
        for (int n = in.u2(); n > 0; n--) { String type = className(in.u2()); contracts.add(type); typeUse(type, "interface", ""); }
        require(new HashSet<>(contracts).size() == contracts.size(), "duplicate interfaces");
        interfaces = List.copyOf(contracts);
        fieldCountOffset = in.position;
        List<Field> fieldList = new ArrayList<>();
        Set<String> fieldKeys = new HashSet<>();
        for (int n = in.u2(); n > 0; n--)
        {
            int start = in.position, flags = in.u2();
            String name = utf(in.u2()), descriptor = utf(in.u2());
            attributeMember = name; attributeDescriptor = descriptor;
            unqualified(name, false);
            addDescriptor(descriptor, false);
            descriptorUse(descriptor, false, "field-descriptor", "");
            require(fieldKeys.add(name + ":" + descriptor), "duplicate field " + name);
            AttributeState state = attributes(in, "field", null);
            if (state.constant != 0) validateConstant(descriptor, state.constant);
            fieldList.add(new Field(flags, name, descriptor, state.constant, start, in.position));
        }
        fields = List.copyOf(fieldList);
        methodCountOffset = in.position;
        List<Method> methodList = new ArrayList<>();
        Set<String> methodKeys = new HashSet<>();
        for (int n = in.u2(); n > 0; n--)
        {
            int start = in.position, flags = in.u2();
            String name = utf(in.u2()), descriptor = utf(in.u2());
            attributeMember = name; attributeDescriptor = descriptor;
            unqualified(name, true);
            Descriptor shape = addDescriptor(descriptor, true);
            descriptorUse(descriptor, true, "method-descriptor", "");
            require(methodKeys.add(name + descriptor), "duplicate method " + name + descriptor);
            require(!name.equals("<init>") || shape.result.equals("V"), "constructor result");
            require(!name.equals("<clinit>") || descriptor.equals("()V"), "class initializer descriptor");
            AttributeState state = attributes(in, "method", null);
            // Deliberately retain all declarations, including native/finalize with no Code.
            // The policy diagnoses those before enforcing the executable-method Code rule.
            if (state.code != null)
                require(state.code.maxLocals >= shape.slots + ((flags & 8) == 0 ? 1 : 0), "parameter locals");
            methodList.add(new Method(flags, name, descriptor, state.code, start, in.position));
        }
        methods = List.copyOf(methodList);
        classAttributesOffset = in.position;
        attributeMember = ""; attributeDescriptor = "";
        AttributeState state = attributes(in, "class", null);
        bootstraps = List.copyOf(state.bootstraps);
        classAttributes = Set.copyOf(state.names);
        in.end();
        for (int i = 1; i < pool.length; i++)
        {
            Cp cp = pool[i];
            if (cp != null && (cp.tag == 17 || cp.tag == 18))
                require(cp.a < bootstraps.size(), "bootstrap index cp#" + i);
        }
    }

    static M004Classfile parse(byte[] bytes)
    {
        require(bytes != null, "null classfile");
        try { return new M004Classfile(bytes); }
        catch (IndexOutOfBoundsException | ArithmeticException ex) { throw fail("invalid span/index: " + ex); }
    }

    Cp cp(int index, int... tags)
    {
        require(index > 0 && index < pool.length && pool[index] != null, "CP index " + index);
        Cp cp = pool[index];
        for (int tag : tags) if (cp.tag == tag) return cp;
        throw fail("CP type at #" + index + " was " + cp.tag + ", expected " + Arrays.toString(tags));
    }

    String utf(int index) { return (String) cp(index, 1).value; }
    String className(int index) { return utf(cp(index, 7).a); }
    String string(int index) { return utf(cp(index, 8).a); }
    Member member(int index)
    {
        Cp ref = cp(index, 9, 10, 11), nat = cp(ref.b, 12);
        return new Member(className(ref.a), utf(nat.a), utf(nat.b));
    }

    private void validatePool()
    {
        for (int i = 1; i < pool.length; i++)
        {
            Cp entry = pool[i];
            if (entry == null) continue;
            switch (entry.tag)
            {
                case 1, 3, 4, 5, 6 -> { }
                case 7 -> addClass(utf(entry.a));
                case 8, 19, 20 -> utf(entry.a);
                case 9, 10, 11 ->
                {
                    Member ref = member(i);
                    addClass(ref.owner);
                    unqualified(ref.name, entry.tag != 9);
                    Descriptor d = addDescriptor(ref.descriptor, entry.tag != 9);
                    typeUse(ref.owner, "constant-pool-member-owner", ref.name + ref.descriptor);
                    descriptorUse(ref.descriptor, entry.tag != 9, "constant-pool-member-descriptor", ref.name);
                    require(!ref.name.equals("<clinit>"), "Methodref to clinit");
                    require(!ref.name.equals("<init>") || entry.tag == 10 && d.result.equals("V"), "constructor Methodref");
                }
                case 12 ->
                {
                    String name = utf(entry.a), descriptor = utf(entry.b);
                    unqualified(name, descriptor.startsWith("("));
                    addDescriptor(descriptor, descriptor.startsWith("("));
                    descriptorUse(descriptor, descriptor.startsWith("("), "constant-pool-name-type", name);
                }
                case 15 ->
                {
                    int kind = entry.a;
                    require(kind >= 1 && kind <= 9, "MethodHandle reference_kind");
                    if (kind <= 4) cp(entry.b, 9);
                    else if (kind == 9) cp(entry.b, 11);
                    else if (kind == 5 || kind == 8) cp(entry.b, 10);
                    else cp(entry.b, 10, 11);
                    Member ref = member(entry.b);
                    require(kind == 8 ? ref.name.equals("<init>") : !ref.name.startsWith("<"), "MethodHandle constructor kind");
                }
                case 16 -> addDescriptor(utf(entry.a), true);
                case 17, 18 ->
                {
                    Cp nat = cp(entry.b, 12);
                    unqualified(utf(nat.a), false);
                    addDescriptor(utf(nat.b), entry.tag == 18);
                }
                default -> throw fail("unhandled CP tag " + entry.tag);
            }
        }
    }

    private void addClass(String name)
    {
        if (name.startsWith("[")) referencedClasses.addAll(descriptor(name, false).classes);
        else
        {
            require(!name.isEmpty() && !name.contains(".") && !name.contains(";") && !name.contains("[")
                    && !name.startsWith("/") && !name.endsWith("/") && !name.contains("//"), "binary name " + name);
            referencedClasses.add(name);
        }
    }

    private Descriptor addDescriptor(String text, boolean method)
    {
        Descriptor descriptor = descriptor(text, method);
        referencedClasses.addAll(descriptor.classes);
        return descriptor;
    }

    private void typeUse(String type, String role, String detail)
    { typeUses.add(new TypeUse(type, role, attributeMember, attributeDescriptor, detail)); }
    private void descriptorUse(String text, boolean method, String role, String detail)
    { for (String type : descriptor(text, method).classes()) typeUse(type, role, detail); }

    static Descriptor descriptor(String text, boolean method)
    {
        require(text != null && !text.isEmpty(), "empty descriptor");
        TypeReader in = new TypeReader(text);
        List<String> parameters = new ArrayList<>();
        int slots = 0;
        if (method)
        {
            in.expect('(');
            while (in.peek() != ')')
            {
                String type = in.type(false);
                parameters.add(type);
                slots += type.equals("J") || type.equals("D") ? 2 : 1;
                require(slots <= 255, "method parameter units");
            }
            in.expect(')');
        }
        String result = in.type(method);
        require(in.position == text.length(), "descriptor trailing bytes: " + text);
        return new Descriptor(List.copyOf(parameters), result, Set.copyOf(in.classes), slots);
    }

    private static void unqualified(String name, boolean method)
    {
        require(!name.isEmpty() && name.chars().noneMatch(c -> c == '.' || c == ';' || c == '[' || c == '/'), "member name " + name);
        require(!method || name.equals("<init>") || name.equals("<clinit>")
                || name.indexOf('<') < 0 && name.indexOf('>') < 0, "method name " + name);
    }

    private void validateConstant(String descriptor, int index)
    {
        switch (descriptor)
        {
            case "Z", "B", "C", "S", "I" -> cp(index, 3);
            case "J" -> cp(index, 5);
            case "F" -> cp(index, 4);
            case "D" -> cp(index, 6);
            case "Ljava/lang/String;" -> cp(index, 8);
            default -> throw fail("ConstantValue descriptor " + descriptor);
        }
    }

    private static final class AttributeState
    {
        final Set<String> names = new HashSet<>();
        final List<Bootstrap> bootstraps = new ArrayList<>();
        int constant;
        Code code;
    }

    private AttributeState attributes(Reader in, String context, Code enclosing)
    {
        AttributeState state = new AttributeState();
        for (int n = in.u2(); n > 0; n--)
        {
            String name = utf(in.u2());
            Reader a = in.section(in.length());
            require(state.names.add(name) || name.equals("LineNumberTable") || name.equals("LocalVariableTable")
                    || name.equals("LocalVariableTypeTable"), "duplicate attribute " + name);
            switch (name)
            {
                case "ConstantValue" -> { location(context, "field", name); state.constant = a.u2(); }
                case "Code" ->
                {
                    location(context, "method", name);
                    int stack = a.u2(), locals = a.u2(), length = a.length();
                    require(length > 0 && length < 65536, "Code length");
                    int absoluteStart = a.position;
                    byte[] code = a.take(length);
                    List<Instruction> instructions = decode(code, locals);
                    Set<Integer> offsets = new HashSet<>();
                    for (Instruction instruction : instructions) offsets.add(instruction.offset);
                    List<Handler> handlers = new ArrayList<>();
                    for (int h = a.u2(); h > 0; h--)
                    {
                        int start = a.u2(), end = a.u2(), target = a.u2(), catchType = a.u2();
                        require(start < end && offsets.contains(start) && (end == length || offsets.contains(end))
                                && offsets.contains(target), "exception table range");
                        if (catchType != 0) typeUse(className(catchType), "catch", "");
                        handlers.add(new Handler(start, end, target, catchType));
                    }
                    state.code = new Code(stack, locals, code, absoluteStart, instructions, List.copyOf(handlers));
                    attributes(a, "code", state.code);
                }
                case "Exceptions" ->
                {
                    location(context, "method", name);
                    for (int j = a.u2(); j > 0; j--) typeUse(className(a.u2()), "throws", "");
                }
                case "BootstrapMethods" ->
                {
                    location(context, "class", name);
                    for (int j = a.u2(); j > 0; j--)
                    {
                        int handle = a.u2(); cp(handle, 15);
                        List<Integer> arguments = new ArrayList<>();
                        for (int k = a.u2(); k > 0; k--)
                        {
                            int index = a.u2(); cp(index, 3, 4, 5, 6, 7, 8, 15, 16, 17);
                            arguments.add(index);
                        }
                        state.bootstraps.add(new Bootstrap(handle, List.copyOf(arguments)));
                    }
                }
                case "SourceFile" -> { location(context, "class", name); utf(a.u2()); }
                case "Signature" -> signature(utf(a.u2()));
                case "Synthetic", "Deprecated" -> { require(!context.equals("code"), "attribute context " + name); if (name.equals("Deprecated")) typeUse("java/lang/Deprecated", "Deprecated", context); }
                case "LineNumberTable" ->
                {
                    location(context, "code", name);
                    for (int j = a.u2(); j > 0; j--) { require(a.u2() < enclosing.bytes.length, "line offset"); a.u2(); }
                }
                case "LocalVariableTable", "LocalVariableTypeTable" ->
                {
                    location(context, "code", name);
                    for (int j = a.u2(); j > 0; j--)
                    {
                        int start = a.u2(), length = a.u2();
                        require(start + (long) length <= enclosing.bytes.length, "local variable range");
                        String localName = utf(a.u2());
                        if (name.equals("LocalVariableTable")) { String desc = utf(a.u2()); addDescriptor(desc, false); descriptorUse(desc, false, name, localName); }
                        else signature(utf(a.u2()));
                        require(a.u2() < enclosing.maxLocals, "local variable slot");
                    }
                }
                case "StackMapTable" -> { location(context, "code", name); stackMap(a, enclosing); }
                case "InnerClasses" ->
                {
                    location(context, "class", name);
                    for (int j = a.u2(); j > 0; j--)
                    {
                        typeUse(className(a.u2()), "InnerClasses", "inner");
                        int outer = a.u2(), inner = a.u2();
                        if (outer != 0) typeUse(className(outer), "InnerClasses", "outer");
                        if (inner != 0) utf(inner);
                        a.u2();
                    }
                }
                case "EnclosingMethod" ->
                {
                    location(context, "class", name); typeUse(className(a.u2()), name, "");
                    int method = a.u2(); if (method != 0) cp(method, 12);
                }
                case "NestHost" -> { location(context, "class", name); typeUse(className(a.u2()), name, ""); }
                case "NestMembers", "PermittedSubclasses" ->
                {
                    location(context, "class", name);
                    for (int j = a.u2(); j > 0; j--) typeUse(className(a.u2()), name, "");
                }
                case "MethodParameters" ->
                {
                    location(context, "method", name);
                    for (int j = a.u1(); j > 0; j--) { int index = a.u2(); if (index != 0) utf(index); a.u2(); }
                }
                case "Record" ->
                {
                    location(context, "class", name);
                    for (int j = a.u2(); j > 0; j--)
                    {
                        unqualified(utf(a.u2()), false);
                        addDescriptor(utf(a.u2()), false);
                        attributes(a, "record-component", null);
                    }
                }
                case "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" ->
                {
                    annotationRole = name;
                    for (int j = a.u2(); j > 0; j--) annotation(a, 0);
                }
                case "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" ->
                {
                    annotationRole = name;
                    location(context, "method", name);
                    for (int p = a.u1(); p > 0; p--) for (int j = a.u2(); j > 0; j--) annotation(a, 0);
                }
                case "AnnotationDefault" -> { location(context, "method", name); element(a, 0); }
                // Unimplemented optional Java attributes are rejected, never silently skipped.
                default -> throw fail("unsupported attribute " + name + " at " + context);
            }
            a.end();
        }
        return state;
    }

    private static void location(String actual, String expected, String attribute)
    { require(actual.equals(expected), "attribute " + attribute + " in " + actual); }

    private void annotation(Reader in, int depth)
    {
        require(depth < 64, "annotation nesting");
        String descriptor = utf(in.u2()); addDescriptor(descriptor, false);
        int count = in.u2(); descriptorUse(descriptor, false, annotationRole, "pairs=" + count + ";depth=" + depth);
        for (int j = count; j > 0; j--) { utf(in.u2()); element(in, depth + 1); }
    }
    private void element(Reader in, int depth)
    {
        require(depth < 64, "annotation nesting");
        switch (in.u1())
        {
            case 'B', 'C', 'I', 'S', 'Z' -> cp(in.u2(), 3);
            case 'J' -> cp(in.u2(), 5);
            case 'F' -> cp(in.u2(), 4);
            case 'D' -> cp(in.u2(), 6);
            case 's' -> utf(in.u2());
            case 'e' -> { String d = utf(in.u2()); addDescriptor(d, false); descriptorUse(d, false, "annotation-element", "enum"); utf(in.u2()); }
            case 'c' -> { String d = utf(in.u2()); if (!d.equals("V")) { addDescriptor(d, false); descriptorUse(d, false, "annotation-element", "class"); } }
            case '@' -> annotation(in, depth + 1);
            case '[' -> { for (int j = in.u2(); j > 0; j--) element(in, depth + 1); }
            default -> throw fail("annotation element tag");
        }
    }

    private void signature(String signature)
    {
        new SignatureReader(signature).read();
    }

    private final class SignatureReader
    {
        final String text;
        int position;
        SignatureReader(String text) { this.text = text; }
        char peek() { require(position < text.length(), "truncated generic signature"); return text.charAt(position); }
        void expect(char value) { require(peek() == value, "generic signature expected " + value); position++; }
        void read()
        {
            if (peek() == '<')
            {
                position++;
                do
                {
                    identifier(":"); expect(':');
                    if (peek() != ':') type(false, 0);
                    while (peek() == ':') { position++; type(false, 0); }
                } while (peek() != '>');
                position++;
            }
            if (peek() == '(')
            {
                position++;
                while (peek() != ')') type(true, 0);
                position++;
                if (peek() == 'V') position++; else type(true, 0);
                while (position < text.length()) { expect('^'); require(peek() == 'L' || peek() == 'T', "throws signature"); type(false, 0); }
            }
            else do { type(false, 0); } while (position < text.length());
            require(position == text.length(), "generic signature trailing input");
        }
        String identifier(String delimiters)
        {
            int start = position;
            while (position < text.length() && delimiters.indexOf(text.charAt(position)) < 0) position++;
            require(position > start && position < text.length(), "generic identifier");
            return text.substring(start, position);
        }
        void type(boolean primitive, int depth)
        {
            require(depth < 64, "generic signature nesting");
            char kind = peek(); position++;
            if (kind == '[') { type(true, depth + 1); return; }
            if (kind == 'T') { identifier(";"); expect(';'); return; }
            if (kind != 'L') { require(primitive && "BCDFIJSZ".indexOf(kind) >= 0, "generic type " + kind); return; }
            String binary = identifier("<;.");
            addClass(binary);
            typeUse(binary, "generic-signature", "");
            while (true)
            {
                if (peek() == '<')
                {
                    position++;
                    do
                    {
                        if (peek() == '*') position++;
                        else { if (peek() == '+' || peek() == '-') position++; type(false, depth + 1); }
                    } while (peek() != '>');
                    position++;
                }
                if (peek() != '.') break;
                position++; binary += "$" + identifier("<;."); addClass(binary); typeUse(binary, "generic-signature", "");
            }
            expect(';');
        }
    }

    private void stackMap(Reader in, Code code)
    {
        int offset = -1;
        for (int n = in.u2(); n > 0; n--)
        {
            int frame = in.u1(), delta;
            if (frame <= 63) delta = frame;
            else if (frame <= 127) { delta = frame - 64; verification(in, code); }
            else if (frame == 247) { delta = in.u2(); verification(in, code); }
            else if (frame >= 248 && frame <= 251) delta = in.u2();
            else if (frame >= 252 && frame <= 254)
            { delta = in.u2(); for (int i = 251; i < frame; i++) verification(in, code); }
            else if (frame == 255)
            {
                delta = in.u2();
                for (int nlocals = in.u2(); nlocals > 0; nlocals--) verification(in, code);
                for (int nstack = in.u2(); nstack > 0; nstack--) verification(in, code);
            }
            else throw fail("StackMap frame tag " + frame);
            offset += delta + 1;
            final int at = offset;
            require(code.instructions.stream().anyMatch(x -> x.offset == at), "StackMap offset");
        }
    }
    private void verification(Reader in, Code code)
    {
        int tag = in.u1();
        require(tag <= 8, "verification type tag");
        if (tag == 7) { int index = in.u2(); stackMapClassIndices.add(index); typeUse(className(index), "StackMapTable", ""); }
        if (tag == 8)
        {
            int offset = in.u2();
            require(code.instructions.stream().anyMatch(x -> x.offset == offset && x.opcode == 0xBB), "uninitialized offset");
        }
    }

    private List<Instruction> decode(byte[] code, int maxLocals)
    {
        Reader in = new Reader(code, 0, code.length);
        List<Instruction> instructions = new ArrayList<>();
        while (in.position < code.length)
        {
            int start = in.position, opcode = in.u1(), operand = -1;
            List<Integer> targets = new ArrayList<>();
            require(opcode <= 0xC9 && opcode != 0xA8 && opcode != 0xA9 && opcode != 0xC9, "reserved/obsolete opcode " + opcode);
            if (opcode == 0xAA || opcode == 0xAB)
            {
                while (in.position % 4 != 0) require(in.u1() == 0, "switch padding");
                targets.add(target(start, (int) in.u4(), code.length));
                if (opcode == 0xAA)
                {
                    int low = (int) in.u4(), high = (int) in.u4();
                    long count = 1L + high - low;
                    require(count > 0 && count <= in.remaining() / 4, "tableswitch bounds/overflow");
                    for (long j = 0; j < count; j++) targets.add(target(start, (int) in.u4(), code.length));
                }
                else
                {
                    int count = in.length(), previous = 0;
                    require(count <= in.remaining() / 8, "lookupswitch bounds");
                    for (int j = 0; j < count; j++)
                    {
                        int key = (int) in.u4(); require(j == 0 || key > previous, "lookupswitch key order"); previous = key;
                        targets.add(target(start, (int) in.u4(), code.length));
                    }
                }
            }
            else if (opcode == 0xC4)
            {
                int widened = in.u1();
                require(widened == 0x84 || widened >= 0x15 && widened <= 0x19 || widened >= 0x36 && widened <= 0x3A, "wide opcode");
                int local = in.u2(); local(local, widened, maxLocals);
                if (widened == 0x84) in.u2();
                operand = local;
            }
            else if (opcode >= 0x99 && opcode <= 0xA7 || opcode == 0xC6 || opcode == 0xC7)
                targets.add(target(start, (short) in.u2(), code.length));
            else if (opcode == 0xC8) targets.add(target(start, (int) in.u4(), code.length));
            else if (opcode == 0x10) operand = (byte) in.u1();
            else if (opcode == 0x11) operand = (short) in.u2();
            else if (opcode >= 0x15 && opcode <= 0x19 || opcode >= 0x36 && opcode <= 0x3A || opcode == 0x84)
            { operand = in.u1(); local(operand, opcode, maxLocals); if (opcode == 0x84) in.u1(); }
            else if (opcode == 0x12 || opcode == 0x13 || opcode == 0x14)
            {
                operand = opcode == 0x12 ? in.u1() : in.u2();
                if (opcode == 0x14) cp(operand, 5, 6, 17);
                else cp(operand, 3, 4, 7, 8, 15, 16, 17);
                if (cp(operand, 3, 4, 5, 6, 7, 8, 15, 16, 17).tag == 17)
                {
                    String d = utf(cp(pool[operand].b, 12).b);
                    require((opcode == 0x14) == (d.equals("J") || d.equals("D")), "ldc dynamic width");
                }
                if (pool[operand].tag == 7) typeUse(className(operand), "Code-class-literal", "");
            }
            else if (opcode >= 0xB2 && opcode <= 0xB9)
            {
                operand = in.u2();
                if (opcode <= 0xB5) cp(operand, 9);
                else if (opcode == 0xB6) cp(operand, 10);
                else if (opcode == 0xB9) cp(operand, 11);
                else cp(operand, 10, 11);
                if (opcode == 0xB9)
                { int units = descriptor(member(operand).descriptor, true).slots + 1; require(in.u1() == units && in.u1() == 0, "invokeinterface count/reserved"); }
            }
            else if (opcode == 0xBA)
            { operand = in.u2(); cp(operand, 18); require(in.u2() == 0, "invokedynamic reserved"); }
            else if (opcode == 0xBB || opcode == 0xBD || opcode == 0xC0 || opcode == 0xC1 || opcode == 0xC5)
            {
                operand = in.u2(); String type = className(operand);
                typeUse(type, "Code-type", Integer.toHexString(opcode));
                if (opcode == 0xBB) require(!type.startsWith("["), "new array class");
                if (opcode == 0xC5)
                {
                    int dimensions = in.u1(), depth = 0;
                    while (depth < type.length() && type.charAt(depth) == '[') depth++;
                    require(dimensions > 0 && dimensions <= depth, "multianewarray dimensions");
                }
            }
            else if (opcode == 0xBC) { int type = in.u1(); require(type >= 4 && type <= 11, "newarray atype"); }
            else if (opcode >= 0x1A && opcode <= 0x2D)
            { int group = (opcode - 0x1A) / 4; local((opcode - 0x1A) % 4, 0x15 + group, maxLocals); }
            else if (opcode >= 0x3B && opcode <= 0x4E)
            { int group = (opcode - 0x3B) / 4; local((opcode - 0x3B) % 4, 0x36 + group, maxLocals); }
            instructions.add(new Instruction(start, opcode, operand, in.position - start, List.copyOf(targets)));
        }
        Set<Integer> starts = new HashSet<>();
        for (Instruction instruction : instructions) starts.add(instruction.offset);
        for (Instruction instruction : instructions)
            for (int target : instruction.targets) require(starts.contains(target), "branch inside instruction");
        return List.copyOf(instructions);
    }

    private static int target(int origin, int displacement, int length)
    { long target = origin + (long) displacement; require(target >= 0 && target < length, "branch target"); return (int) target; }
    private static void local(int index, int opcode, int maximum)
    {
        int size = opcode == 0x16 || opcode == 0x18 || opcode == 0x37 || opcode == 0x39 ? 2 : 1;
        require(index >= 0 && index + size <= maximum, "local index");
    }
    static IllegalArgumentException fail(String reason) { return new IllegalArgumentException(FORMAT + ": " + reason); }
    static void require(boolean condition, String reason) { if (!condition) throw fail(reason); }

    private static final class TypeReader
    {
        final String text;
        final Set<String> classes = new LinkedHashSet<>();
        int position;
        TypeReader(String text) { this.text = text; }
        char peek() { require(position < text.length(), "truncated descriptor " + text); return text.charAt(position); }
        void expect(char c) { require(peek() == c, "descriptor expected " + c); position++; }
        String type(boolean allowVoid)
        {
            int start = position, dimensions = 0;
            while (peek() == '[') { dimensions++; position++; require(dimensions <= 255, "array dimensions"); }
            char c = peek(); position++;
            if (c == 'L')
            {
                int nameStart = position;
                while (peek() != ';') position++;
                String name = text.substring(nameStart, position++);
                require(!name.isEmpty() && name.chars().noneMatch(x -> x == '.' || x == '[' || x == '(' || x == ')')
                        && !name.startsWith("/") && !name.endsWith("/") && !name.contains("//"), "descriptor class " + name);
                classes.add(name);
            }
            else require("BCDFIJSZ".indexOf(c) >= 0 || c == 'V' && allowVoid && dimensions == 0, "descriptor type " + c);
            return text.substring(start, position);
        }
    }
    private static final class Reader
    {
        final byte[] data;
        final int limit;
        int position;
        Reader(byte[] data, int start, int limit) { this.data = data; this.position = start; this.limit = limit; }
        int remaining() { return limit - position; }
        int u1() { require(position < limit, "truncated at " + position); return data[position++] & 255; }
        int u2() { return u1() << 8 | u1(); }
        long u4() { return (long) u2() << 16 | u2(); }
        int length() { long length = u4(); require(length <= Integer.MAX_VALUE, "attribute/array length overflow"); return (int) length; }
        Reader section(int count) { require(count >= 0 && count <= remaining(), "truncated span at " + position); int start = position; position += count; return new Reader(data, start, position); }
        byte[] take(int count) { Reader span = section(count); return Arrays.copyOfRange(data, span.position, span.limit); }
        String utf()
        {
            int start = position, length = u2(); section(length);
            try { return new DataInputStream(new ByteArrayInputStream(data, start, length + 2)).readUTF(); }
            catch (IOException ex) { throw fail("modified UTF-8 at " + start); }
        }
        void end() { require(position == limit, "trailing bytes at " + position); }
    }
}
