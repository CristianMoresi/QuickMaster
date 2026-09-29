import java.io.ByteArrayOutputStream;
import java.nio.file.*;

/** Synthetic MPEG-1 Layer III intensity stereo bitstreams; no music or encoder.
 * Huffman table 1, no reservoir, explicit scalefactors and spectral holes.
 * Decode with an independent implementation to obtain reference PCM. */
public final class Mp3IntensityFixtures {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        for (int mode : new int[]{1, 3}) for (String shape : new String[]{"long", "short", "mixed", "mixed-low"})
            for (int sf : new int[]{3, 7}) {
                ByteArrayOutputStream file = new ByteArrayOutputStream();
                for (int frame = 0; frame < 12; frame++) {
                    boolean shortBlock = !shape.equals("long") && frame >= 2 && frame < 9;
                    boolean mixed = shortBlock && shape.startsWith("mixed");
                    int type = shortBlock ? 2 : !shape.equals("long") && frame == 1 ? 1
                            : !shape.equals("long") && frame == 9 ? 3 : 0;
                    Bits side = new Bits(), main = new Bits();
                    side.put(0, 9); side.put(0, 3); side.put(0, 8); // reservoir, private, scfsi
                    for (int granule = 0; granule < 2; granule++) for (int ch = 0; ch < 2; ch++) {
                        Bits data = new Bits();
                        if (shortBlock) {
                            if (mixed) for (int b = 0; b < 8; b++) data.put(ch == 1 ? sf : 0, 4);
                            for (int b = mixed ? 3 : 0; b < 12; b++) for (int w = 0; w < 3; w++)
                                data.put(ch == 1 ? sf : 0, b < 6 ? 4 : 3);
                        } else for (int b = 0; b < 21; b++) data.put(ch == 1 ? sf : 0, b < 11 ? 4 : 3);
                        int[] quantized = new int[576];
                        if (ch == 0) {
                            for (int i = 0; i < 576; i += 17) quantized[i] = 1;
                        } else {
                            quantized[19] = 1;
                            if (!shape.equals("mixed-low")) { quantized[120] = 1; quantized[209] = 1; }
                        }
                        for (int i = 0; i < 576; i += 2) {
                            int x = quantized[i], y = quantized[i + 1];
                            if (x == 0 && y == 0) data.put(1, 1);
                            else if (x == 1 && y == 0) data.put(1, 2);
                            else if (x == 0 && y == 1) data.put(1, 3);
                            else data.put(0, 3);
                            if (x != 0) data.put(0, 1);
                            if (y != 0) data.put(0, 1);
                        }
                        side.put(data.size, 12); side.put(288, 9); side.put(195, 8); side.put(15, 4);
                        side.put(type != 0 ? 1 : 0, 1);
                        if (type != 0) {
                            side.put(type, 2); side.put(mixed ? 1 : 0, 1);
                            side.put(1, 5); side.put(1, 5); side.put(0, 9);
                        } else { side.put(1, 5); side.put(1, 5); side.put(1, 5); side.put(7, 4); side.put(5, 3); }
                        side.put(0, 1); side.put(0, 1); side.put(0, 1);
                        main.append(data);
                    }
                    if (side.size != 256 || main.size > 924 * 8) throw new AssertionError();
                    file.write(new byte[]{(byte) 0xff, (byte) 0xfb, (byte) 0xe4, (byte) (0x40 | mode << 4)});
                    file.write(side.bytes()); byte[] payload = new byte[924];
                    System.arraycopy(main.bytes(), 0, payload, 0, main.bytes().length); file.write(payload);
                }
                Files.write(directory.resolve("intensity-" + shape + "-" + mode + "-" + sf + ".mp3"), file.toByteArray());
            }
    }
    private static final class Bits {
        final byte[] data = new byte[2048]; int size;
        void put(int value, int count) {
            for (int i = count - 1; i >= 0; i--, size++) data[size / 8] |= (byte) ((value >>> i & 1) << (7 - size % 8));
        }
        void append(Bits bits) { for (int i = 0; i < bits.size; i++) put(bits.data[i / 8] >>> (7 - i % 8) & 1, 1); }
        byte[] bytes() { return java.util.Arrays.copyOf(data, (size + 7) / 8); }
    }
}
