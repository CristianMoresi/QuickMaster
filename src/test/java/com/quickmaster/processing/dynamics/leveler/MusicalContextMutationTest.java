package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import com.google.gson.*;
import org.junit.jupiter.api.Test;

class MusicalContextMutationTest
{
    @Test
    void actualSourceMutantsAreKilledWithoutReplacingProductOrWeakeningGoldens() throws Exception
    {
        Path sourceRoot = Path.of("src/main/java/com/quickmaster/processing/dynamics/leveler");
        String comparator = Files.readString(sourceRoot.resolve("SegmentComparator.java")).replace("\r\n", "\n");
        String builder = Files.readString(sourceRoot.resolve("SegmentDescriptorBuilder.java")).replace("\r\n", "\n");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path root = Path.of("target/leveler-context-mutations");
        Files.createDirectories(root); Path evidence = Files.createTempDirectory(root, "run-");
        JsonArray outcomes = new JsonArray();
        try
        {
            for (String test : List.of("last", "mask", "rotation", "previous", "predicate", "q"))
                run(evidence.resolve("BASE-" + test), classpath, test, false, outcomes);
            mutant(evidence, "OMIT_SLOT_7", "SegmentComparator", replace(comparator, "slot < 8", "slot < 7"), classpath, "last", outcomes);
            mutant(evidence, "REINDEX_CENTRAL", "SegmentComparator", replace(comparator,
                    "SegmentDescriptorBuilder.structuralBins(\n                    features, new FrameRange(start, end), source.frames())",
                    "(changeFirst ? first.bins() : second.bins())"), classpath, "last", outcomes);
            mutant(evidence, "RECYCLE_CENTRAL_MASK", "SegmentComparator", replace(comparator,
                    "&& valid(first.get(bin)) && valid(second.get(bin))", "&& true"), classpath, "mask", outcomes);
            mutant(evidence, "RECYCLE_ROTATION_ELIGIBILITY", "SegmentComparator", replace(comparator,
                    "if (bestRotation != 0)", "if (false)"), classpath, "rotation", outcomes);
            String previous = replace(comparator, "        for (int slot = 0; slot < 8; slot++)",
                    "        double previousAlignment = alignment;\n        for (int slot = 0; slot < 8; slot++)");
            previous = replace(previous, "!stableAlignment(alignment, scores[2])", "!stableAlignment(previousAlignment, scores[2])");
            previous = replace(previous, "        }\n        return new SimilarityScore(harmonic", "            previousAlignment = scores[2];\n        }\n        return new SimilarityScore(harmonic");
            mutant(evidence, "APREV", "SegmentComparator", previous, classpath, "previous", outcomes);
            mutant(evidence, "PREDICATE_EPSILON", "SegmentComparator", replace(comparator, "<= 0.05d;", "<= 0.05d + 1.0e-12d;"), classpath, "predicate", outcomes);
            mutant(evidence, "Q_BUILDER_SCALE", "SegmentDescriptorBuilder", replace(builder,
                    "rightNovelty[segment] = boundaryNovelty(", "rightNovelty[segment] = 2.0d * boundaryNovelty("), classpath, "q", outcomes);
            String otherFormula = replace(builder,
                    "double[] novelty = BoundaryDetector.originalNovelty(features, format.frames(), LevelerCalibrationProfile.V1);",
                    "double[] novelty = new double[Math.max(0, features.size() - 1)];\n"
                    + "        for (int i = 0; i < novelty.length; i++) novelty[i] = Math.abs(features.frame(i + 1).activity() - features.frame(i).activity())\n"
                    + "                + Math.abs(features.frame(i + 1).onsetFlux() - features.frame(i).onsetFlux());");
            mutant(evidence, "Q_SECOND_FORMULA", "SegmentDescriptorBuilder", otherFormula, classpath, "q", outcomes);
        }
        finally
        {
            Files.writeString(evidence.resolve("outcomes.json"), new GsonBuilder().setPrettyPrinting().create().toJson(outcomes));
            assertEquals(comparator, Files.readString(sourceRoot.resolve("SegmentComparator.java")).replace("\r\n", "\n"));
            assertEquals(builder, Files.readString(sourceRoot.resolve("SegmentDescriptorBuilder.java")).replace("\r\n", "\n"));
            System.out.println("MUSICAL_MUTATIONS " + evidence.toAbsolutePath());
        }
        assertEquals(14, outcomes.size());
    }

    private static String replace(String source, String before, String after)
    {
        int first = source.indexOf(before);
        assertTrue(first >= 0 && first == source.lastIndexOf(before), "Mutation must have one exact anchor: " + before);
        return source.substring(0, first) + after + source.substring(first + before.length());
    }
    private static void mutant(Path root, String name, String owner, String source, String classpath,
                               String test, JsonArray outcomes) throws Exception
    {
        Path dir = root.resolve(name), classes = dir.resolve("classes"); Files.createDirectories(classes);
        Path file = dir.resolve(owner + ".java"); Files.writeString(file, source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = ToolProvider.getSystemJavaCompiler().run(null, output, output, "--release", "17", "-g", "-cp", classpath,
                "-d", classes.toString(), file.toString());
        Files.writeString(dir.resolve("compile.log"), output.toString(StandardCharsets.UTF_8));
        assertEquals(0, exit, "A compile failure is not a killed mutant: " + name);
        Files.writeString(dir.resolve("source.sha256"), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))));
        run(dir, classes.toAbsolutePath() + File.pathSeparator + classpath, test, true, outcomes);
    }
    private static void run(Path dir, String classpath, String test, boolean killed, JsonArray outcomes) throws Exception
    {
        Files.createDirectories(dir); Path log = dir.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Xmx256m", "-cp", classpath, MusicalContextMutationChild.class.getName(), test)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Mutation child timed out"); }
        finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); } }
        String output = Files.readString(log); JsonObject row = new JsonObject();
        row.addProperty("name", dir.getFileName().toString()); row.addProperty("test", test); row.addProperty("exitCode", child.exitValue());
        row.addProperty("assertionFailure", output.contains("org.opentest4j.AssertionFailedError")); outcomes.add(row);
        if (killed)
        {
            assertNotEquals(0, child.exitValue(), "Mutant survived: " + dir);
            assertTrue(output.contains("org.opentest4j.AssertionFailedError"), "Infrastructure errors do not kill mutants: " + output);
        }
        else { assertEquals(0, child.exitValue(), output); assertTrue(output.contains("ASSERTIONS_PASSED")); }
    }
}
