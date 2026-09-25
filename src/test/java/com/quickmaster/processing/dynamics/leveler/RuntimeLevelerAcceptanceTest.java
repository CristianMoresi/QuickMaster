package com.quickmaster.processing.dynamics.leveler;

import com.dspark.analysis.TruePeak;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Test-only own-JAR bridge: authentic official measurements, never a fabricated PASSED DTO. */
public final class RuntimeLevelerAcceptanceTest {
    private static Path candidate;
    private RuntimeLevelerAcceptanceTest() { }

    public static synchronized Path candidate() throws Exception {
        if (candidate != null) return candidate;
        Path classes = Path.of(LevelerAnalysisEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path tests = Path.of(RuntimeLevelerAcceptanceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isDirectory(classes) || !Files.isDirectory(tests))
            throw new IllegalStateException("Acceptance packaging requires actual compiled source/test directories.");
        String supplied = System.getProperty("qm.leveler.candidateJar");
        if (supplied != null && !supplied.isBlank()) {
            Path path = Path.of(supplied).toAbsolutePath().normalize();
            requireCurrentClasses(path, classes);
            candidate = path;
            return candidate;
        }
        Path runs = classes.getParent().resolve("active-leveler-acceptance");
        Files.createDirectories(runs);
        Path run = Files.createTempDirectory(runs, "run-");
        Path copy = run.resolve("classes");
        try (Stream<Path> entries = Files.walk(classes)) {
            for (Path from : entries.sorted().toList()) {
                if (Files.isSymbolicLink(from)) throw new IllegalStateException("No linked class input.");
                Path to = copy.resolve(classes.relativize(from));
                if (Files.isDirectory(from)) Files.createDirectories(to);
                else Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        Path output = run.resolve("conformance");
        Path jar = run.resolve("quickmaster-acceptance.jar");
        Map<String,String> requested = new LinkedHashMap<>();
        requested.put("qm.officialLoudness", "true");
        for (String key : List.of("qm.ituRoot", "qm.ebuRoot", "qm.ituManifest", "qm.ebuManifest", "qm.ebuAuthorization")) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank() || value.startsWith("${"))
                throw new IllegalStateException("Official acceptance requires -D" + key + "=<local path>; see docs/VALIDATION.md.");
            requested.put(key, value);
        }
        Map<String,String> previous = new LinkedHashMap<>();
        try {
            for (var entry : requested.entrySet()) {
                String old = System.getProperty(entry.getKey());
                previous.put(entry.getKey(), old);
                if (entry.getKey().equals("qm.officialLoudness") || old == null || old.isBlank() || old.startsWith("${"))
                    System.setProperty(entry.getKey(), entry.getValue());
            }
            ConformancePackageAssembler.prepare(Path.of("").toAbsolutePath(), copy, tests, output);
            Manifest manifest;
            try (InputStream input = Files.newInputStream(output.resolve("MANIFEST.MF"))) { manifest = new Manifest(input); }
            try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(jar), manifest);
                 Stream<Path> entries = Files.walk(copy)) {
                for (Path file : entries.filter(Files::isRegularFile).sorted().toList()) {
                    String name = copy.relativize(file).toString().replace('\\', '/');
                    if (name.equals("META-INF/MANIFEST.MF")) continue;
                    JarEntry entry = new JarEntry(name);
                    entry.setTime(0);
                    archive.putNextEntry(entry);
                    Files.copy(file, archive);
                    archive.closeEntry();
                }
            }
            ConformancePackageAssembler.verify(copy, tests, output, jar);
            requireCurrentClasses(jar, classes);
            candidate = jar;
            System.out.println("ACTIVE_ACCEPTANCE_JAR " + jar + " sha256=" + OfficialWaveReader.sha256(jar));
            return candidate;
        } finally {
            for (var entry : previous.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void requireCurrentClasses(Path path, Path classes) throws Exception {
        try (JarFile jar = new JarFile(path.toFile()); Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                JarEntry entry = jar.getJarEntry(name);
                if (entry == null) throw new IllegalStateException("Candidate lacks current class " + name);
                try (InputStream input = jar.getInputStream(entry)) {
                    if (!Arrays.equals(Files.readAllBytes(file), input.readAllBytes()))
                        throw new IllegalStateException("Stale candidate class " + name);
                }
            }
        }
    }

    public static BoundProcessor processor() throws Exception { return new BoundProcessor(candidate()); }

    public static float[] positivePcm(int rate, int channels) {
        var test = MusicalPcmFixture.catalog().stream().filter(c -> c.key().equals("P01_ABA_LEVEL")).findFirst().orElseThrow();
        return MusicalPcmFixture.generate(test, rate, channels).pcm();
    }

    public static final class BoundProcessor implements AutoCloseable {
        private final URLClassLoader loader;
        private final Object processor;
        private final Class<?> type;
        private BoundProcessor(Path jar) throws Exception {
            URL vendor = TruePeak.class.getProtectionDomain().getCodeSource().getLocation();
            loader = new URLClassLoader(new URL[] {jar.toUri().toURL(), vendor}, ClassLoader.getPlatformClassLoader());
            type = Class.forName("com.quickmaster.processing.dynamics.LevelerProcessor", true, loader);
            processor = type.getConstructor().newInstance();
        }
        public void controls(double amount, double speed) throws Exception {
            type.getMethod("setLeveling", double.class).invoke(processor, amount);
            type.getMethod("setSpeed", double.class).invoke(processor, speed);
            type.getMethod("setEnabled", boolean.class).invoke(processor, true);
        }
        public void analyze(float[] pcm, int rate, int channels) throws Exception {
            type.getMethod("prepare", int.class, long.class).invoke(processor, rate, (long)pcm.length);
            type.getMethod("analyze", float[].class, int.class).invoke(processor, pcm, channels);
        }
        public String status() throws Exception { return (String)type.getMethod("getAnalysisDiagnostic").invoke(processor); }
        public String conformance() throws Exception {
            Object snapshot = type.getMethod("getShadowAnalysis").invoke(processor);
            Object diagnostics = snapshot.getClass().getMethod("diagnostics").invoke(snapshot);
            Object validation = diagnostics.getClass().getMethod("standardValidation").invoke(diagnostics);
            return validation.getClass().getMethod("state").invoke(validation).toString();
        }
        private Object schedule() throws Exception {
            Method read = type.getSuperclass().getDeclaredMethod("publishedGain"); read.setAccessible(true);
            Object publication = read.invoke(processor);
            Method getter = publication.getClass().getDeclaredMethod("schedule"); getter.setAccessible(true);
            return getter.invoke(publication);
        }
        public record Piece(double start, double end, double fromDb, double toDb, boolean smooth) { }
        /** Immutable primitive observations only; no product object is modified by this bridge. */
        public List<Piece> pieces() throws Exception {
            Object schedule = schedule();
            if (!schedule.getClass().getSimpleName().equals("SparseGainSchedule")) {
                gainAt(0); // requires the exact canonical Dense unit, not a legacy envelope
                return List.of();
            }
            int size = (int)schedule.getClass().getMethod("pieceCount").invoke(schedule);
            List<Piece> pieces = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                Object piece = schedule.getClass().getMethod("pieceAt", int.class).invoke(schedule, i);
                Class<?> kind = piece.getClass();
                String shape = kind.getMethod("shape").invoke(piece).toString();
                if (!shape.equals("HOLD") && !shape.equals("SMOOTHSTEP")) throw new IllegalStateException("Unknown piece shape");
                pieces.add(new Piece((double)kind.getMethod("startFrame").invoke(piece),
                        (double)kind.getMethod("endFrame").invoke(piece),
                        (double)kind.getMethod("startDb").invoke(piece),
                        (double)kind.getMethod("endDb").invoke(piece), shape.equals("SMOOTHSTEP")));
            }
            return List.copyOf(pieces);
        }
        public double gainAt(double sourceFrame) throws Exception {
            Object schedule = schedule();
            if (!schedule.getClass().getSimpleName().equals("SparseGainSchedule")) {
                Method unit = schedule.getClass().getDeclaredMethod("unit"); unit.setAccessible(true);
                if (unit.invoke(null) == schedule) return 0;
                throw new IllegalStateException("Active Leveler leaked a dense schedule.");
            }
            return (double)schedule.getClass().getMethod("gainDbAt", double.class).invoke(schedule, sourceFrame);
        }
        public float[] render(float[] input, int channels, long startFrame) throws Exception {
            type.getMethod("setPlaybackPosition", long.class).invoke(processor, startFrame);
            float[] output = input.clone();
            Method process = type.getMethod("process", float[].class, int.class);
            for (int from = 0; from < output.length; from += 4096 * channels) {
                int end = Math.min(output.length, from + 4096 * channels);
                float[] block = Arrays.copyOfRange(output, from, end);
                float[] result = (float[])process.invoke(processor, block, channels);
                if (result.length != block.length) throw new IllegalStateException("Changed render extent.");
                System.arraycopy(result, 0, output, from, result.length);
            }
            return output;
        }
        public void enabled(boolean enabled) throws Exception { type.getMethod("setEnabled", boolean.class).invoke(processor, enabled); }
        @Override public void close() throws Exception { loader.close(); }
    }
}
