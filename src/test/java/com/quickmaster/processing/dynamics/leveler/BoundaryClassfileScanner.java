package com.quickmaster.processing.dynamics.leveler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Test-only direct-call guard. Reuses the constant-pool and instruction decoder
 * structure from M-004/critic-scratch-008/classfile_probe.py, with Java's modified
 * UTF-8 reader and complete bounds checks. This is not a CFG or dynamic-call proof.
 */
final class BoundaryClassfileScanner
{
    static final String PACKAGE = "com/quickmaster/processing/dynamics/leveler/";
    static final String DETECTOR = PACKAGE + "BoundaryDetector";
    static final String ENGINE = PACKAGE + "LevelerAnalysisEngine";
    static final String PROFILE = PACKAGE + "LevelerCalibrationProfile";
    static final String FORMAT = PACKAGE + "model/AudioFormat";
    static final String DETECT_DESCRIPTOR = "(L" + PACKAGE + "model/FeatureTimeline;JL"
            + PROFILE + ";)L" + PACKAGE + "model/SegmentLayout;";
    static final String ENGINE_DESCRIPTOR = "([FL" + FORMAT + ";L" + PACKAGE
            + "CancellationToken;)L" + PACKAGE + "model/ShadowAnalysisSnapshot;";
    static final String FAILURE = "BOUNDARY_DIRECT_WIRING";

    record Member(String owner, String name, String descriptor) { }
    record Entry(int tag, int first, int second, String text, int start, int end) { }
    record Instruction(int offset, int opcode, int operand) { }
    record Method(int access, String name, String descriptor, List<Instruction> instructions) { }
    record Classfile(byte[] bytes, int access, String owner, int fields, Entry[] pool,
                     int poolEnd, List<Method> methods)
    {
        Member member(int index)
        {
            Entry reference = pool[index];
            require(reference != null && reference.tag >= 9 && reference.tag <= 11, "member ref");
            Entry nameAndType = pool[reference.second];
            require(nameAndType != null && nameAndType.tag == 12, "name and type");
            return new Member(className(pool, reference.first), utf(pool, nameAndType.first),
                    utf(pool, nameAndType.second));
        }
    }
    record ScannedFile(String path, String sha256) { }
    record Scan(List<ScannedFile> files, int usedReferences, int invocations, String site) { }

    static Scan scan(Path root) throws Exception
    {
        List<Path> paths;
        try (Stream<Path> files = Files.walk(root))
        {
            paths = files.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                    .sorted().toList();
        }
        require(!paths.isEmpty(), "empty first-party output");
        List<ScannedFile> hashes = new ArrayList<>();
        List<String> references = new ArrayList<>();
        int calls = 0;
        int detectors = 0;
        String site = "";
        for (Path path : paths)
        {
            Classfile file = read(path);
            hashes.add(new ScannedFile(root.relativize(path).toString().replace('\\', '/'), sha(file.bytes)));
            if (file.owner.equals(DETECTOR))
            {
                detectors++;
                require((file.access & 0x0010) != 0 && (file.access & 0x7607) == 0,
                        "detector must be final/package-private");
                require(file.fields == 0, "detector must be fieldless");
                int detectMethods = 0;
                for (Method method : file.methods)
                {
                    require(!method.name.equals("inferTotalFrames"), "obsolete inference method");
                    if (!method.name.equals("detect")) continue;
                    detectMethods++;
                    require(method.access == 0 && method.descriptor.equals(DETECT_DESCRIPTOR),
                            "detect signature/visibility");
                }
                require(detectMethods == 1, "detect must be unique");
            }
            for (Method method : file.methods)
            {
                List<Instruction> code = method.instructions;
                for (int i = 0; i < code.size(); i++)
                {
                    Instruction instruction = code.get(i);
                    if (instruction.opcode < 0xB6 || instruction.opcode > 0xB9) continue;
                    Member target = file.member(instruction.operand);
                    if (!target.owner.equals(DETECTOR) || !target.name.equals("detect")) continue;
                    calls++;
                    String reference = file.owner + "#" + instruction.operand;
                    if (!references.contains(reference)) references.add(reference);
                    require(instruction.opcode == 0xB6 && target.descriptor.equals(DETECT_DESCRIPTOR),
                            "detector invoke descriptor/opcode");
                    require(file.owner.equals(ENGINE) && method.name.equals("analyzeShadow")
                            && method.descriptor.equals(ENGINE_DESCRIPTOR), "unexpected detector caller");
                    require(i >= 3 && code.get(i - 3).opcode == 0x2C, "direct source load");
                    require(isMember(file, code.get(i - 2), 0xB6, FORMAT, "frames", "()J"),
                            "direct source.frames()");
                    require(isMember(file, code.get(i - 1), 0xB2, PROFILE, "V1", "L" + PROFILE + ";"),
                            "fixed profile atom");
                    site = file.owner + "." + method.name + ":" + instruction.offset;
                }
            }
        }
        require(detectors == 1 && calls == 1 && references.size() == 1,
                "exactly one detector, used reference and invocation required");
        return new Scan(hashes, references.size(), calls, site);
    }

    private static boolean isMember(Classfile file, Instruction instruction, int opcode,
                                    String owner, String name, String descriptor)
    {
        return instruction.opcode == opcode
                && file.member(instruction.operand).equals(new Member(owner, name, descriptor));
    }

    static Classfile read(Path path) throws IOException
    {
        byte[] bytes = Files.readAllBytes(path);
        Reader in = new Reader(bytes);
        require(in.u4() == 0xCAFEBABEL, "magic");
        in.u2();
        require(in.u2() == 61, "Java 17 classfile required");
        Entry[] pool = new Entry[in.u2()];
        for (int i = 1; i < pool.length; i++)
        {
            int start = in.position;
            int tag = in.u1();
            int first = 0;
            int second = 0;
            String text = null;
            switch (tag)
            {
                case 1 -> text = in.utf();
                case 3, 4 -> in.skip(4);
                case 5, 6 -> in.skip(8);
                case 7, 8, 16, 19, 20 -> first = in.u2();
                case 9, 10, 11, 12, 17, 18 -> { first = in.u2(); second = in.u2(); }
                case 15 -> { first = in.u1(); second = in.u2(); }
                default -> throw new IllegalArgumentException(FAILURE + ": constant-pool tag " + tag);
            }
            pool[i] = new Entry(tag, first, second, text, start, in.position);
            if (tag == 5 || tag == 6) i++;
        }
        int poolEnd = in.position;
        int access = in.u2();
        String owner = className(pool, in.u2());
        in.u2();
        in.skip(2 * in.u2());
        int fields = in.u2();
        for (int i = 0; i < fields; i++)
        {
            in.skip(6);
            skipAttributes(in);
        }
        List<Method> methods = new ArrayList<>();
        int methodCount = in.u2();
        for (int i = 0; i < methodCount; i++)
        {
            int flags = in.u2();
            String name = utf(pool, in.u2());
            String descriptor = utf(pool, in.u2());
            int attributes = in.u2();
            List<Instruction> instructions = List.of();
            for (int j = 0; j < attributes; j++)
            {
                String attributeName = utf(pool, in.u2());
                byte[] attribute = in.take(in.length());
                if (!attributeName.equals("Code")) continue;
                Reader code = new Reader(attribute);
                code.skip(4);
                instructions = decode(code.take(code.length()));
                code.skip(Math.multiplyExact(8, code.u2()));
                skipAttributes(code);
                require(code.position == attribute.length, "Code attribute trailing bytes");
            }
            methods.add(new Method(flags, name, descriptor, instructions));
        }
        skipAttributes(in);
        require(in.position == bytes.length, "classfile trailing bytes");
        return new Classfile(bytes, access, owner, fields, pool, poolEnd, methods);
    }

    private static void skipAttributes(Reader in)
    {
        int attributes = in.u2();
        for (int i = 0; i < attributes; i++)
        {
            in.u2();
            in.skip(in.length());
        }
    }

    private static List<Instruction> decode(byte[] code)
    {
        Reader in = new Reader(code);
        List<Instruction> result = new ArrayList<>();
        while (in.position < code.length)
        {
            int start = in.position;
            int opcode = in.u1();
            int operand = -1;
            int size;
            if (opcode == 0x10 || opcode == 0x12 || opcode >= 0x15 && opcode <= 0x19
                    || opcode >= 0x36 && opcode <= 0x3A || opcode == 0xA9 || opcode == 0xBC)
            {
                size = 1;
            }
            else if (opcode == 0x11 || opcode == 0x13 || opcode == 0x14 || opcode == 0x84
                    || opcode >= 0x99 && opcode <= 0xA8 || opcode >= 0xB2 && opcode <= 0xB8
                    || opcode == 0xBB || opcode == 0xBD || opcode == 0xC0 || opcode == 0xC1
                    || opcode == 0xC6 || opcode == 0xC7)
            {
                size = 2;
            }
            else if (opcode == 0xB9 || opcode == 0xBA || opcode == 0xC8 || opcode == 0xC9) size = 4;
            else if (opcode == 0xC5) size = 3;
            else if (opcode == 0xAA || opcode == 0xAB)
            {
                in.skip((4 - in.position % 4) % 4);
                in.skip(4);
                if (opcode == 0xAA)
                {
                    int low = (int) in.u4();
                    int high = (int) in.u4();
                    require(high >= low, "tableswitch bounds");
                    size = Math.toIntExact(4L * (1L + (long) high - low));
                }
                else
                {
                    int pairs = in.length();
                    size = Math.multiplyExact(8, pairs);
                }
            }
            else if (opcode == 0xC4)
            {
                int widened = in.u1();
                require(widened == 0x84 || widened >= 0x15 && widened <= 0x19
                        || widened >= 0x36 && widened <= 0x3A || widened == 0xA9, "wide opcode");
                size = widened == 0x84 ? 4 : 2;
            }
            else
            {
                require(opcode <= 0xC9, "reserved opcode");
                size = 0;
            }
            if (opcode >= 0xB2 && opcode <= 0xBA) operand = in.peekU2();
            in.skip(size);
            result.add(new Instruction(start, opcode, operand));
        }
        return result;
    }

    static byte[] withUnusedDetectorReference(Path path, boolean interfaceReference) throws IOException
    {
        Classfile file = read(path);
        Entry reference = null;
        for (int i = 1; i < file.pool.length; i++)
        {
            Entry entry = file.pool[i];
            if (entry != null && entry.tag == 10 && file.member(i).owner.equals(DETECTOR)
                    && file.member(i).name.equals("detect")) { reference = entry; break; }
        }
        require(reference != null && file.pool.length < 65_535, "duplicate fixture methodref");
        int size = reference.end - reference.start;
        byte[] bytes = new byte[file.bytes.length + size];
        System.arraycopy(file.bytes, 0, bytes, 0, file.poolEnd);
        System.arraycopy(file.bytes, reference.start, bytes, file.poolEnd, size);
        System.arraycopy(file.bytes, file.poolEnd, bytes, file.poolEnd + size,
                file.bytes.length - file.poolEnd);
        int count = file.pool.length + 1;
        bytes[8] = (byte) (count >>> 8);
        bytes[9] = (byte) count;
        if (interfaceReference) bytes[file.poolEnd] = 11;
        return bytes;
    }

    static String sha(byte[] bytes) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String className(Entry[] pool, int index)
    {
        require(pool[index] != null && pool[index].tag == 7, "class entry");
        return utf(pool, pool[index].first);
    }

    private static String utf(Entry[] pool, int index)
    {
        require(pool[index] != null && pool[index].tag == 1, "UTF-8 entry");
        return pool[index].text;
    }

    private static void require(boolean condition, String reason)
    {
        if (!condition) throw new IllegalArgumentException(FAILURE + ": " + reason);
    }

    private static final class Reader
    {
        private final byte[] bytes;
        private int position;

        Reader(byte[] bytes) { this.bytes = bytes; }
        int u1() { require(position < bytes.length, "truncated byte"); return bytes[position++] & 255; }
        int u2() { return (u1() << 8) | u1(); }
        long u4() { return ((long) u2() << 16) | u2(); }
        int length() { return Math.toIntExact(u4()); }
        int peekU2()
        {
            require(position <= bytes.length - 2, "truncated operand");
            return ((bytes[position] & 255) << 8) | (bytes[position + 1] & 255);
        }
        void skip(int count)
        {
            require(count >= 0 && position <= bytes.length - count, "truncated span");
            position += count;
        }
        byte[] take(int count)
        {
            int start = position;
            skip(count);
            return Arrays.copyOfRange(bytes, start, position);
        }
        String utf() throws IOException
        {
            int start = position;
            skip(2 + peekU2());
            return new DataInputStream(new ByteArrayInputStream(bytes, start, position - start)).readUTF();
        }
    }
}
