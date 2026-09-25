package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import com.quickmaster.processing.dynamics.leveler.model.*;

class ConformancePackageTest
{
    @TempDir Path temp;
    private final Map<String, String> previousProperties = new LinkedHashMap<>();

    @BeforeEach
    void isolateExplicitPackageInputs()
    {
        for (String key : new String[] {"qm.officialLoudness", "qm.ituRoot", "qm.ebuRoot", "qm.ituManifest",
                "qm.ebuManifest", "qm.ebuAuthorization", "qm.conformanceEpoch"})
        {
            previousProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreExplicitPackageInputs()
    {
        for (var entry : previousProperties.entrySet())
            if (entry.getValue() == null) System.clearProperty(entry.getKey()); else System.setProperty(entry.getKey(), entry.getValue());
    }

    @Test
    void explodedOwnClassesRemainNotRunAndCannotProduceABinding()
    {
        assertEquals(ConformanceState.NOT_RUN, ConformanceArtifactLoader.loadCurrent().state());
        assertNull(ConformanceArtifactLoader.currentBuildBinding());
    }

    @Test
    void defaultFreshBytesRoundtripThroughOwnJarAndAllAbsentVersusPartialMetadata() throws Exception
    {
        Map<String, byte[]> entries = packageEntries();
        Path jar = writeJar(entries, "bound.jar");
        assertEquals("NOT_RUN:true", load(jar));
        Map<String, byte[]> absent = copy(entries);
        absent.remove("META-INF/quickmaster/leveler-conformance.json");
        absent.remove("META-INF/quickmaster/leveler-conformance-profile.json");
        absent.put("META-INF/MANIFEST.MF", manifest(null, null));
        assertEquals("NOT_RUN:false", load(writeJar(absent, "absent.jar")));
        for (String removed : new String[] {"META-INF/quickmaster/leveler-conformance.json",
                "META-INF/quickmaster/leveler-conformance-profile.json", "META-INF/MANIFEST.MF"})
        {
            Map<String, byte[]> partial = copy(entries); partial.remove(removed);
            assertEquals("FAILED:false", load(writeJar(partial, "partial-" + removed.hashCode() + ".jar")));
        }
        Path dependency = writeJar(Map.of("META-INF/MANIFEST.MF", manifest("f".repeat(64), "e".repeat(64)),
                "META-INF/quickmaster/leveler-conformance.json", "{\"state\":\"PASSED\"}".getBytes(StandardCharsets.US_ASCII)), "dependency.jar");
        assertEquals("NOT_RUN:true", load(jar, dependency));
    }

    @Test
    void everyActualAlgorithmClassMutationAndEachProfilePayloadManifestMutationRejectsBinding() throws Exception
    {
        Map<String, byte[]> original = packageEntries();
        assertEquals("NOT_RUN:true", load(writeJar(original, "positive.jar")));
        int classMutants = 0;
        String[] names = {
                "BuildAlgorithmBinding", "CancellationToken", "ConformanceArtifactLoader", "ConformanceCodec", "ConformanceRequirement",
                "ConformanceRun", "KWeightingAdapter", "LevelerAnalysisEngine", "LoudnessAnalyzer", "LoudnessConformanceGuard", "LoudnessCore",
                "LoudnessStandard", "model/AudioFormat", "model/ChannelLayout", "model/ConformanceReason", "model/ConformanceState",
                "model/FrozenList", "model/LoudnessTimeline", "model/LoudnessValueKind", "model/MeasuredLoudness", "model/OfficialSignalEvidence",
                "model/ReadingKind", "model/ReadoutMode", "model/RequiredOfficialReading", "model/RequiredSetReport", "model/StandardValidationReport"};
        for (String name : names)
        {
            Map<String, byte[]> mutant = copy(original);
            String entry = "com/quickmaster/processing/dynamics/leveler/" + name + ".class";
            byte[] changed = mutant.get(entry).clone(); changed[changed.length - 1] ^= 1; mutant.put(entry, changed);
            String result = load(writeJar(mutant, "class-" + classMutants++ + ".jar"));
            assertTrue(result.equals("FAILED:false") || result.equals("REFUSED:false"), name + " -> " + result);
        }
        assertEquals(26, classMutants);
        for (String entry : new String[] {"META-INF/quickmaster/leveler-conformance.json", "META-INF/quickmaster/leveler-conformance-profile.json"})
        {
            Map<String, byte[]> mutant = copy(original);
            byte[] changed = mutant.get(entry).clone(); changed[changed.length - 2] ^= 1; mutant.put(entry, changed);
            assertEquals("FAILED:false", load(writeJar(mutant, "resource-" + entry.hashCode() + ".jar")));
        }
        for (boolean runner : new boolean[] {true, false})
        {
            Map<String, byte[]> mutant = copy(original);
            Manifest m = new Manifest(new java.io.ByteArrayInputStream(mutant.get("META-INF/MANIFEST.MF")));
            m.getMainAttributes().putValue(runner ? "QuickMaster-Loudness-Runner-SHA256" : "QuickMaster-Loudness-Attestation-SHA256", "f".repeat(64));
            mutant.put("META-INF/MANIFEST.MF", manifestBytes(m));
            assertEquals("FAILED:false", load(writeJar(mutant, "manifest-" + runner + ".jar")));
        }
    }

    @Test
    void packageVerifierRejectsStaleOutputAndForbiddenAudioRunnerOrAgentEntries() throws Exception
    {
        Map<String, byte[]> entries = packageEntries();
        Path output = temp.resolve("prepared"); Files.createDirectories(output);
        Files.write(output.resolve("leveler-conformance.json"), entries.get("META-INF/quickmaster/leveler-conformance.json"));
        Files.write(output.resolve("leveler-conformance-profile.json"), entries.get("META-INF/quickmaster/leveler-conformance-profile.json"));
        Path classes = Path.of(ConformanceCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path tests = Path.of(ConformancePackageAssembler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ConformancePackageAssembler.verify(classes, tests, output, writeJar(entries, "clean.jar"));
        for (String entry : new String[] {"leaked.wav", "Universal-Orchestrator/task01/mission.md",
                "com/quickmaster/processing/dynamics/leveler/OfficialWaveReader.class", "diagnostics/RetentionAgent.class"})
        {
            Map<String, byte[]> mutant = copy(entries); mutant.put(entry, new byte[] {1});
            Path jar = writeJar(mutant, "leak-" + entry.hashCode() + ".jar");
            assertThrows(IllegalStateException.class, () -> ConformancePackageAssembler.verify(classes, tests, output, jar));
        }
        Files.write(output.resolve("leveler-conformance-profile.json"), new byte[] {1});
        assertThrows(IllegalStateException.class, () -> ConformancePackageAssembler.verify(classes, tests, output, writeJar(entries, "stale.jar")));
    }

    @Test
    void duplicateJarEntriesAndEachRecompiledRunnerClassFailTheReopenGate() throws Exception
    {
        Map<String, byte[]> entries = packageEntries();
        Path output = temp.resolve("duplicate-prepared"); Files.createDirectories(output);
        Files.write(output.resolve("leveler-conformance.json"), entries.get("META-INF/quickmaster/leveler-conformance.json"));
        Files.write(output.resolve("leveler-conformance-profile.json"), entries.get("META-INF/quickmaster/leveler-conformance-profile.json"));
        Path classes = Path.of(ConformanceCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path tests = Path.of(ConformancePackageAssembler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String extra = "META-INF/quickmaster/xeveler-conformance.json";
        String real = "META-INF/quickmaster/leveler-conformance.json";
        // Equal-length local and central names produce a genuine duplicate ZIP entry.
        assertEquals(real.length(), extra.length());
        Map<String, byte[]> duplicate = copy(entries);
        duplicate.put(extra, entries.get(real));
        Path badJar = writeJar(duplicate, "duplicate.jar");
        byte[] zip = Files.readAllBytes(badJar);
        byte[] from = extra.getBytes(StandardCharsets.US_ASCII), to = real.getBytes(StandardCharsets.US_ASCII);
        int replacements = 0;
        for (int i = 0; i <= zip.length - from.length; i++)
        {
            boolean match = true;
            for (int j = 0; j < from.length; j++) if (zip[i + j] != from[j]) { match = false; break; }
            if (match) { System.arraycopy(to, 0, zip, i, to.length); replacements++; }
        }
        assertEquals(2, replacements);
        Files.write(badJar, zip);
        assertThrows(IllegalStateException.class, () -> ConformancePackageAssembler.verify(classes, tests, output, badJar));
        Path good = writeJar(entries, "runner-original.jar");
        for (String selected : new String[] {"ConformancePackageAssembler", "OfficialLoudnessRunner", "OfficialWaveReader"})
        {
            Path copied = temp.resolve("runner-" + selected);
            for (String name : new String[] {"ConformancePackageAssembler", "OfficialLoudnessRunner", "OfficialWaveReader"})
            {
                String relative = "com/quickmaster/processing/dynamics/leveler/" + name + ".class";
                byte[] bytes = Files.readAllBytes(tests.resolve(relative));
                if (name.equals(selected)) bytes[bytes.length - 1] ^= 1;
                Path target = copied.resolve(relative); Files.createDirectories(target.getParent()); Files.write(target, bytes);
            }
            assertThrows(IllegalStateException.class, () -> ConformancePackageAssembler.verify(classes, copied, output, good));
        }
    }

    @Test
    void explicitUnavailableAttemptIsNotASyntheticPassedPackageAndHasFailureExit() throws Exception
    {
        ConformanceRun run = OfficialLoudnessRunner.measure(null, null, null, null, null, "", "", "", 0L);
        assertTrue(run.attempted()); assertFalse(run.ebuTermsAuthorized()); assertEquals(0, run.evidence().size());
        assertEquals(ConformanceState.UNAVAILABLE, StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, run, null).state());
        String old = System.getProperty("qm.officialLoudness");
        try
        {
            System.setProperty("qm.officialLoudness", "true");
            Path classes = Path.of(ConformanceCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path tests = Path.of(ConformancePackageAssembler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // A private copy isolates the deliberately failed packaging attempt from Maven's classes/target.
            Path isolated = temp.resolve("isolated-classes");
            try (var paths = Files.walk(classes))
            {
                for (Path file : paths.filter(Files::isRegularFile).toList())
                {
                    Path target = isolated.resolve(classes.relativize(file)); Files.createDirectories(target.getParent()); Files.copy(file, target);
                }
            }
            Path output = temp.resolve("unavailable");
            assertThrows(IllegalStateException.class, () -> ConformancePackageAssembler.prepare(temp, isolated, tests, output));
            assertTrue(Files.readString(output.resolve("status.txt")).contains("state=UNAVAILABLE"));
        }
        finally
        {
            if (old == null) System.clearProperty("qm.officialLoudness"); else System.setProperty("qm.officialLoudness", old);
        }
    }

    private static Map<String, byte[]> packageEntries() throws Exception
    {
        Path classes = Path.of(ConformanceCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path tests = Path.of(ConformancePackageAssembler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var files = Files.walk(classes))
        {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                if (file.toString().endsWith(".class")) entries.put(classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        String algorithm = ConformancePackageAssembler.cone(classes, false);
        String runner = ConformancePackageAssembler.cone(tests, true);
        byte[] profile = ConformanceCodec.profileBytes(ConformanceRequirement.OFFICIAL_LOUDNESS_V1);
        ConformanceRun run = new ConformanceRun("", "", new FrozenList<OfficialSignalEvidence>(new Object[0]), false, false,
                ConformanceReason.NONE, "QM-LOUDNESS-CORE-BS1770-5-V1", algorithm, ConformancePackageAssembler.hash(profile), runner, 0L);
        byte[] payload = ConformanceCodec.artifactBytes(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, run);
        entries.put("META-INF/quickmaster/leveler-conformance.json", payload);
        entries.put("META-INF/quickmaster/leveler-conformance-profile.json", profile);
        entries.put("META-INF/MANIFEST.MF", manifest(ConformancePackageAssembler.hash(payload), runner));
        return entries;
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> original) { return new LinkedHashMap<>(original); }

    private Path writeJar(Map<String, byte[]> entries, String name) throws Exception
    {
        Path path = temp.resolve(name);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path)))
        {
            for (Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                jar.putNextEntry(new JarEntry(entry.getKey())); jar.write(entry.getValue()); jar.closeEntry();
            }
        }
        return path;
    }

    private static byte[] manifest(String payload, String runner) throws Exception
    {
        Manifest manifest = new Manifest(); Attributes a = manifest.getMainAttributes(); a.putValue("Manifest-Version", "1.0");
        if (payload != null) a.putValue("QuickMaster-Loudness-Attestation-SHA256", payload);
        if (runner != null) a.putValue("QuickMaster-Loudness-Runner-SHA256", runner);
        return manifestBytes(manifest);
    }

    private static byte[] manifestBytes(Manifest manifest) throws Exception
    {
        var output = new java.io.ByteArrayOutputStream(); manifest.write(output); return output.toByteArray();
    }

    private static String load(Path jar, Path... dependency) throws Exception
    {
        URL[] urls = dependency.length == 0 ? new URL[] {jar.toUri().toURL()} : new URL[] {dependency[0].toUri().toURL(), jar.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()))
        {
            Class<?> type = Class.forName("com.quickmaster.processing.dynamics.leveler.ConformanceArtifactLoader", true, loader);
            var read = type.getDeclaredMethod("loadCurrent"); read.setAccessible(true);
            var binding = type.getDeclaredMethod("currentBuildBinding"); binding.setAccessible(true);
            Object report = read.invoke(null);
            return report.getClass().getMethod("state").invoke(report).toString() + ":" + (binding.invoke(null) != null);
        }
        catch (LinkageError ex) { return "REFUSED:false"; }
        catch (InvocationTargetException ex)
        {
            if (ex.getCause() instanceof LinkageError) return "REFUSED:false";
            throw ex;
        }
    }
}
