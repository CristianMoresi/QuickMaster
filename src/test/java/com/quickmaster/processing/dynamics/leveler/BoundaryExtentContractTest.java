package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class BoundaryExtentContractTest
{
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SOURCE = Path.of("src/main/java/com/quickmaster/processing/dynamics/leveler");
    private static final Path CLASSES = Path.of("target/classes").toAbsolutePath().normalize();
    private static final Map<String, Path> VARIANTS = new LinkedHashMap<>();
    private static final String CLASSPATH = System.getProperty("surefire.test.class.path",
            System.getProperty("java.class.path"));
    private static Path evidence;

    @BeforeAll
    static void compileIsolatedMutantsFromTheActualProduct() throws Exception
    {
        Path results = Path.of("target/leveler-boundary-contract")
                .toAbsolutePath().normalize();
        Files.createDirectories(results);
        evidence = Files.createTempDirectory(results, "run-");
        String detector = Files.readString(SOURCE.resolve("BoundaryDetector.java")).replace("\r\n", "\n");
        String engine = Files.readString(SOURCE.resolve("LevelerAnalysisEngine.java")).replace("\r\n", "\n");
        VARIANTS.put("BASE", CLASSES);
        variant("NO_DEBUG", Map.of("BoundaryDetector", detector, "LevelerAnalysisEngine", engine));
        variant("CAST", Map.of("BoundaryDetector", replaceOnce(detector,
                "if (expected != (long) count)", "if ((int) expected != count)")));
        String comparison = "        if (expected != (long) count)";
        variant("INLINE", Map.of("BoundaryDetector", replaceOnce(detector, comparison,
                "        BoundaryAllocationSink.keep(new byte[(int) expected]);\n" + comparison)));
        String helper = replaceOnce(detector, comparison,
                "        BoundaryAllocationSink.keep(reserveExtent(totalFrames, hop));\n" + comparison);
        helper = appendMethod(helper, "    private static byte[] reserveExtent(long frames, long hop)\n"
                + "    { return new byte[(int) (1L + (frames - 1L) / hop)]; }\n");
        variant("HELPER", Map.of("BoundaryDetector", helper));
        String centerLoop = "        try\n        {\n            for (int i = 0; i < count; i++)";
        variant("EARLY", Map.of("BoundaryDetector", replaceOnce(detector, centerLoop,
                "        if (count > 1) rawNovelty(features, 1, 1);\n" + centerLoop)));
        String single = "        if (count == 1) return layout(totalFrames, new int[0], 0, hop);";
        String safe = replaceOnce(detector, single,
                "        if (expected < 1L || expected > Integer.MAX_VALUE)\n"
                + "            return failed(LayoutStatus.INSUFFICIENT_FEATURES);\n"
                + "        int checked = Math.toIntExact(expected);\n"
                + "        if (checked != count) return failed(LayoutStatus.INSUFFICIENT_FEATURES);\n" + single);
        variant("SAFE", Map.of("BoundaryDetector", safe));
        String direct = "features, source.frames(), LevelerCalibrationProfile.V1";
        variant("PLUS_ONE", Map.of("LevelerAnalysisEngine", replaceOnce(engine, direct,
                "features, source.frames() + 1L, LevelerCalibrationProfile.V1")));
        variant("MINUS_ONE", Map.of("LevelerAnalysisEngine", replaceOnce(engine, direct,
                "features, source.frames() - 1L, LevelerCalibrationProfile.V1")));
        variant("INFERRED", Map.of("LevelerAnalysisEngine", replaceOnce(engine, direct,
                "features, features.size() * features.hopFrames(), LevelerCalibrationProfile.V1")));
        variant("OLD_OVERLOAD", Map.of("BoundaryDetector", appendMethod(detector,
                "    SegmentLayout detect(FeatureTimeline features, LevelerCalibrationProfile profile)\n"
                + "    { return detect(features, 1L, profile); }\n")));
        variant("OLD_INFERENCE_METHOD", Map.of("BoundaryDetector", appendMethod(detector,
                "    private static long inferTotalFrames(FeatureTimeline features)\n"
                + "    { return features.size() * features.hopFrames(); }\n")));
        variant("PUBLIC_DETECTOR", Map.of("BoundaryDetector", replaceOnce(detector,
                "final class BoundaryDetector", "public final class BoundaryDetector")));
        variant("SECOND_CALLER", Map.of("SecondBoundaryCaller", "package com.quickmaster.processing.dynamics.leveler;\n"
                + "import com.quickmaster.processing.dynamics.leveler.model.*;\n"
                + "final class SecondBoundaryCaller {\n"
                + "  SegmentLayout call(FeatureTimeline features, long frames) {\n"
                + "    return new BoundaryDetector().detect(features, frames, LevelerCalibrationProfile.V1);\n"
                + "  }\n}\n"));
        variant("TIE", Map.of("BoundaryDetector", replaceOnce(detector,
                "&& otherBoundary < boundary)", "&& otherBoundary > boundary)")));
        variant("RETRY", Map.of("BoundaryDetector", replaceOnce(detector,
                "percentileLower(novelty, 0.975d)", "percentileLower(novelty, 0.99d)")));
        variant("INTERVAL", Map.of("BoundaryDetector", replaceOnce(detector,
                "Math.max(0, boundary - radius)", "Math.max(0, boundary - radius + 1)")));
        variant("EQUALITY", Map.of("BoundaryDetector", replaceOnce(detector,
                "value > threshold && value > 0.0d", "value >= threshold && value > 0.0d")));
        variant("PROFILE_UNION_REPLACED", Map.of("LevelerAnalysisEngine", replaceOnce(engine,
                "decision.flags().reasonBits() | (1L << 5)", "(1L << 5)")));
        String profile = Files.readString(SOURCE.resolve("LevelerCalibrationProfile.java")).replace("\r\n", "\n");
        variant("PROFILE_INCLUSIVE", Map.of("LevelerCalibrationProfile", replaceOnce(profile,
                "lengthFrames < 3L * sourceSampleRateHz", "lengthFrames <= 3L * sourceSampleRateHz")));
        JsonObject metadata = new JsonObject();
        metadata.addProperty("javaHome", System.getProperty("java.home"));
        metadata.addProperty("javaVersion", System.getProperty("java.runtime.version"));
        metadata.addProperty("productClassesRoot", CLASSES.toString());
        metadata.addProperty("detectorSourceSha256", BoundaryClassfileScanner.sha(detector.getBytes(StandardCharsets.UTF_8)));
        metadata.addProperty("engineSourceSha256", BoundaryClassfileScanner.sha(engine.getBytes(StandardCharsets.UTF_8)));
        JsonObject roots = new JsonObject();
        for (var variant : VARIANTS.entrySet()) roots.addProperty(variant.getKey(), variant.getValue().toString());
        metadata.add("variantRoots", roots);
        write("build-metadata.json", metadata);
    }

    @Test
    void scansEveryCompiledFirstPartyClassAndKillsWrongCallersSignaturesAndExtentExpressions() throws Exception
    {
        JsonArray results = new JsonArray();
        write("production-classfiles.json", BoundaryClassfileScanner.scan(CLASSES));
        for (String positive : new String[] { "BASE", "NO_DEBUG", "SAFE" })
        {
            BoundaryClassfileScanner.Scan scan = BoundaryClassfileScanner.scan(VARIANTS.get(positive));
            assertEquals(1, scan.usedReferences());
            assertEquals(1, scan.invocations());
            results.add(row(positive, true, "PASS"));
        }
        for (boolean interfaceRef : new boolean[] { false, true })
        {
            String name = interfaceRef ? "UNUSED_INTERFACE_REF" : "UNUSED_METHOD_REF";
            Path copy = copyClasses(name);
            Path engine = copy.resolve(BoundaryClassfileScanner.ENGINE + ".class");
            Files.write(engine, BoundaryClassfileScanner.withUnusedDetectorReference(engine, interfaceRef));
            BoundaryClassfileScanner.Scan scan = BoundaryClassfileScanner.scan(copy);
            assertEquals(1, scan.usedReferences());
            assertEquals(1, scan.invocations());
            results.add(row(name, true, "Unused constant-pool references do not count"));
        }
        for (String negative : new String[] { "PLUS_ONE", "MINUS_ONE", "INFERRED", "OLD_OVERLOAD",
                "OLD_INFERENCE_METHOD", "PUBLIC_DETECTOR", "SECOND_CALLER" })
        {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> BoundaryClassfileScanner.scan(VARIANTS.get(negative)), negative);
            assertTrue(error.getMessage().startsWith(BoundaryClassfileScanner.FAILURE));
            results.add(row(negative, false, error.getMessage()));
        }
        write("wiring-results.json", results);
    }

    @Test
    void invalidExtentsRejectWithinBudgetBeforeNoveltyWithAnExternalJdiObserver() throws Exception
    {
        JsonArray results = new JsonArray();
        try
        {
            for (String variant : new String[] { "BASE", "SAFE" })
            {
                observe(results, variant, "invalid", "ACTIVE", true, false, null);
                observe(results, variant, "positive", "ACTIVE", false, true, null);
                observe(results, variant, "minimum", "ACTIVE", false, false, null);
                observe(results, variant, "maximum", "ACTIVE", false, false, null);
            }
            observe(results, "CAST", "alias", "ACTIVE", true, false, "STATUS_OR_EXCEPTION");
            for (String variant : new String[] { "INLINE", "HELPER" })
            {
                JsonObject mib = observe(results, variant, "mib", "ACTIVE", true, false, "ALLOCATION_BUDGET");
                List<String> withoutBudget = BoundaryJdiObserver.violations(mib, true, false, false, true);
                assertTrue(withoutBudget.isEmpty(), "Removing only the allocation budget admits the MiB mutant");
                mib.add("withoutBudgetViolations", JSON.toJsonTree(withoutBudget));
                observe(results, variant, "heap", "ACTIVE", true, false, "CHILD_EXIT");
            }
            JsonObject early = observe(results, "EARLY", "center", "ACTIVE", true, false, "EARLY_NOVELTY");
            List<String> withoutPhase = BoundaryJdiObserver.violations(early, true, false, true, false);
            assertTrue(withoutPhase.isEmpty(), "Removing only the phase counter admits early novelty");
            early.add("withoutPhaseViolations", JSON.toJsonTree(withoutPhase));
            observe(results, "BASE", "positive", "BLIND", false, true, "OBSERVER_INCOMPLETE");
            observe(results, "BASE", "positive", "WRONG_FILTER", false, true, "OBSERVER_INCOMPLETE");
        }
        finally { write("jdi-allocation-results.json", results); }
    }

    @Test
    void fullEngineGoldensAndProfilePropertiesDiscriminateMutants() throws Exception
    {
        JsonArray results = new JsonArray();
        try
        {
            functional(results, "BASE", true, "engine");
            for (String variant : new String[] { "PLUS_ONE", "MINUS_ONE", "INFERRED" })
                functional(results, variant, false, "engine");
            functional(results, "BASE", true, "golden", "ftMad0TieKeepsEarlierOfFourEqualMaxima");
            functional(results, "TIE", false, "golden", "ftMad0TieKeepsEarlierOfFourEqualMaxima");
            functional(results, "BASE", true, "golden", "rawNoveltyUsesBothHalfOpenWindowsAndTheirLowerMedians");
            functional(results, "INTERVAL", false, "golden", "rawNoveltyUsesBothHalfOpenWindowsAndTheirLowerMedians");
            functional(results, "RETRY", false, "golden", "ftRetryOrderAcceptsP975ResultOf51BeforeP99ResultOf31");
            functional(results, "EQUALITY", false, "golden", "positiveThresholdEqualityIsNotACandidateAndPercentilesStayLower");
            functional(results, "BASE", true, "profile", "temporalUnionPreservesEveryCombinationOfClassifierBitsAndCannotUndoAVeto");
            functional(results, "PROFILE_UNION_REPLACED", false, "profile",
                    "temporalUnionPreservesEveryCombinationOfClassifierBitsAndCannotUndoAVeto");
            functional(results, "PROFILE_INCLUSIVE", false, "profile",
                    "temporalUnionPreservesEveryCombinationOfClassifierBitsAndCannotUndoAVeto");
        }
        finally { write("functional-mutation-results.json", results); }
    }

    private static JsonObject observe(JsonArray results, String variant, String cases, String observer,
                                      boolean invalid, boolean novelty, String expectedFailure) throws Exception
    {
        JsonObject result = BoundaryJdiObserver.observe(classpath(VARIANTS.get(variant)), cases, observer);
        result.addProperty("variant", variant);
        result.addProperty("selection", cases);
        List<String> violations = BoundaryJdiObserver.violations(result, invalid, novelty, true, true);
        result.add("violations", JSON.toJsonTree(violations));
        results.add(result);
        write("jdi-allocation-results.json", results);
        if (expectedFailure == null) assertTrue(violations.isEmpty(), () -> result.toString());
        else assertTrue(violations.stream().anyMatch(reason -> reason.startsWith(expectedFailure)),
                () -> "Expected " + expectedFailure + ": " + result);
        return result;
    }

    private static void functional(JsonArray results, String variant, boolean expected, String... args)
            throws Exception
    {
        String label = variant + "-" + String.join("-", args);
        Path output = evidence.resolve(label + ".stdout.json");
        Path error = evidence.resolve(label + ".stderr.log");
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-Xms16m", "-Xmx256m", "-cp",
                classpath(VARIANTS.get(variant)), FoundationFunctionalChild.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile())
                .redirectError(error.toFile()).start();
        boolean finished = process.waitFor(60L, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(3L, TimeUnit.SECONDS); }
        assertTrue(finished, "Functional child timeout: " + label);
        JsonObject result = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        result.addProperty("variant", variant);
        result.addProperty("exitCode", process.exitValue());
        result.add("command", JSON.toJsonTree(command));
        result.addProperty("stderr", Files.readString(error));
        results.add(result);
        assertEquals(expected, result.get("passed").getAsBoolean(), label + ": " + result);
        assertEquals(expected ? 0 : 2, process.exitValue(), label);
    }

    private static void variant(String name, Map<String, String> sources) throws Exception
    {
        Path output = copyClasses(name);
        Path sourceRoot = evidence.resolve(name).resolve("source");
        Files.createDirectories(sourceRoot);
        List<String> command = new ArrayList<>(List.of("--release", "17", "-g:none", "-classpath",
                classpath(output), "-sourcepath", "", "-d", output.toString()));
        for (var source : sources.entrySet())
        {
            Path path = sourceRoot.resolve(source.getKey() + ".java");
            Files.writeString(path, source.getValue());
            command.add(path.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Supported JDK compiler is required; cannot skip mutation controls");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        int exit = compiler.run(null, log, log, command.toArray(String[]::new));
        Files.write(evidence.resolve(name).resolve("compile.log"), log.toByteArray());
        Files.writeString(evidence.resolve(name).resolve("compile-command.json"), JSON.toJson(command));
        assertEquals(0, exit, name + ": " + log.toString(StandardCharsets.UTF_8));
        VARIANTS.put(name, output);
    }

    private static Path copyClasses(String name) throws Exception
    {
        Path root = evidence.resolve(name).resolve("classes");
        try (Stream<Path> paths = Files.walk(CLASSES))
        {
            for (Path source : paths.sorted().toList())
            {
                Path target = root.resolve(CLASSES.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return root;
    }

    private static String classpath(Path classes) { return classes + File.pathSeparator + CLASSPATH; }
    private static String javaExecutable()
    {
        Path binary = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        return Files.isRegularFile(binary) ? binary.toString()
                : Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
    private static String replaceOnce(String text, String before, String after)
    {
        int position = text.indexOf(before);
        assertTrue(position >= 0 && text.indexOf(before, position + before.length()) == -1,
                "Mutation anchor must occur exactly once: " + before);
        return text.substring(0, position) + after + text.substring(position + before.length());
    }
    private static String appendMethod(String source, String method)
    {
        int end = source.lastIndexOf('}');
        assertTrue(end >= 0);
        return source.substring(0, end) + method + "}\n";
    }
    private static JsonObject row(String variant, boolean accepted, String reason)
    {
        JsonObject result = new JsonObject();
        result.addProperty("variant", variant);
        result.addProperty("accepted", accepted);
        result.addProperty("reason", reason);
        return result;
    }
    private static void write(String name, Object data) throws Exception
    {
        Files.writeString(evidence.resolve(name), JSON.toJson(data), StandardCharsets.UTF_8);
    }
}
