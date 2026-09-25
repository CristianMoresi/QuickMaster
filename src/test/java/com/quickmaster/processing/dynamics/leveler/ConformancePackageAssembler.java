package com.quickmaster.processing.dynamics.leveler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Test-only lifecycle gate. All assertions refer to freshly compiled bytes and a newly generated payload. */
public final class ConformancePackageAssembler
{
    private ConformancePackageAssembler() { }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 6 || !(args[0].equals("prepare") || args[0].equals("verify")))
            throw new IllegalArgumentException("Usage: prepare|verify project classes test-classes output jar");
        Path project = Path.of(args[1]).toAbsolutePath().normalize();
        Path classes = Path.of(args[2]).toAbsolutePath().normalize();
        Path tests = Path.of(args[3]).toAbsolutePath().normalize();
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        Path jar = Path.of(args[5]).toAbsolutePath().normalize();
        if (args[0].equals("prepare")) prepare(project, classes, tests, output);
        else verify(classes, tests, output, jar);
    }

    static void prepare(Path project, Path classes, Path tests, Path output) throws Exception
    {
        ConformanceRequirement requirement = ConformanceRequirement.OFFICIAL_LOUDNESS_V1;
        byte[] profile = ConformanceCodec.profileBytes(requirement);
        String profileHash = hash(profile);
        if (!profileHash.equals("daad1207ff680b67b64108692a2070be389227ac8ddffa177ae9b8ac39ffbc14"))
            throw new IllegalStateException("Compiled profile differs from the approved canonical pin.");
        String algorithmHash = cone(classes, false);
        String runnerHash = cone(tests, true);
        String epoch = System.getenv("SOURCE_DATE_EPOCH");
        String override = System.getProperty("qm.conformanceEpoch");
        long timestamp = Long.parseLong(override != null && !override.startsWith("$") ? override
                : epoch == null || epoch.isEmpty() ? "0" : epoch);
        if (timestamp < 0L) throw new IllegalArgumentException("SOURCE_DATE_EPOCH must be nonnegative.");
        boolean official = Boolean.parseBoolean(System.getProperty("qm.officialLoudness", "false"));
        ConformanceRun run = official
                ? OfficialLoudnessRunner.measure(propertyPath("qm.ituRoot"), propertyPath("qm.ebuRoot"),
                        propertyPath("qm.ituManifest"), propertyPath("qm.ebuManifest"), propertyPath("qm.ebuAuthorization"),
                        algorithmHash, profileHash, runnerHash, timestamp)
                : new ConformanceRun("", "", new FrozenList<OfficialSignalEvidence>(new Object[0]),
                        false, false, ConformanceReason.NONE, requirement.algorithmId(),
                        algorithmHash, profileHash, runnerHash, timestamp);
        byte[] artifact = ConformanceCodec.artifactBytes(requirement, run);
        BuildAlgorithmBinding binding = new BuildAlgorithmBinding(requirement.algorithmId(), algorithmHash,
                profileHash, hash(artifact), runnerHash, timestamp);
        StandardValidationReport report = StandardValidationReport.verifyBound(requirement, run, binding);
        Files.createDirectories(output);
        Path metadata = classes.resolve("META-INF/quickmaster");
        Files.createDirectories(metadata);
        Files.write(output.resolve("leveler-conformance-profile.json"), profile);
        Files.write(output.resolve("leveler-conformance.json"), artifact);
        Files.write(metadata.resolve("leveler-conformance-profile.json"), profile);
        Files.write(metadata.resolve("leveler-conformance.json"), artifact);
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("QuickMaster-Loudness-Attestation-SHA256", binding.attestationSha256());
        attributes.putValue("QuickMaster-Loudness-Runner-SHA256", runnerHash);
        try (java.io.OutputStream stream = Files.newOutputStream(output.resolve("MANIFEST.MF")))
        {
            manifest.write(stream);
        }
        String status = "state=" + report.state() + "\nevidence=" + run.evidence().size()
                + "\nalgorithmSha256=" + algorithmHash + "\nprofileSha256=" + profileHash
                + "\nattestationSha256=" + binding.attestationSha256() + "\nrunnerSha256=" + runnerHash
                + "\ncreatedAtEpochSecond=" + timestamp + "\nfailureReason=" + run.failureReason()
                + "\nituState=" + report.sets().get(0).state() + "\nituReason=" + report.sets().get(0).reason()
                + "\nebuState=" + report.sets().get(1).state() + "\nebuReason=" + report.sets().get(1).reason()
                + "\nclaim=QM-OFFICIAL-LOUDNESS-FILE-V1 only; not full EBU Mode, live, LRA or true peak"
                + "\nEBU11/14=LIVE_ALTERNATIVE_NOT_APPLICABLE_TO_FILE_PROFILE; TP15..23=M005; OTHER_ACQUIRED=8\n";
        Files.writeString(output.resolve("status.txt"), status, StandardCharsets.US_ASCII);
        System.out.print(status);
        if (!algorithmHash.equals(cone(classes, false)) || !runnerHash.equals(cone(tests, true)))
            throw new IllegalStateException("Class files changed while measuring.");
        if (official && (report.state() != ConformanceState.PASSED || !report.matches(binding)))
            throw new IllegalStateException("Official gate failed; fresh non-PASS evidence is preserved at " + output);
        if (!official && (report.state() != ConformanceState.NOT_RUN || run.evidence().size() != 0))
            throw new IllegalStateException("Default packaging must remain a fresh NOT_RUN.");
    }

    static void verify(Path classes, Path tests, Path output, Path jarPath) throws Exception
    {
        byte[] expectedArtifact = Files.readAllBytes(output.resolve("leveler-conformance.json"));
        byte[] expectedProfile = Files.readAllBytes(output.resolve("leveler-conformance-profile.json"));
        String algorithm = cone(classes, false);
        String runner = cone(tests, true);
        ConformanceRun run = ConformanceCodec.decodeArtifact(expectedArtifact);
        if (!algorithm.equals(run.algorithmSha256()) || !runner.equals(run.runnerSha256())
                || !hash(expectedProfile).equals(run.profileSha256()))
            throw new IllegalStateException("Stale prepare-package output or recompiled classes.");
        Set<String> observed = new HashSet<>();
        try (JarFile jar = new JarFile(jarPath.toFile()))
        {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements())
            {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!observed.add(name)) throw new IllegalStateException("Duplicate JAR entry: " + name);
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".wav") || lower.endsWith(".aiff") || lower.endsWith(".flac") || lower.endsWith(".mp3")
                        || lower.contains("universal-orchestrator") || lower.contains("official-conformance/")
                        || lower.contains("javaagent") || lower.contains("retentionagent")
                        || name.startsWith("com/quickmaster/processing/dynamics/leveler/OfficialWaveReader")
                        || name.startsWith("com/quickmaster/processing/dynamics/leveler/OfficialLoudnessRunner")
                        || name.startsWith("com/quickmaster/processing/dynamics/leveler/ConformancePackageAssembler"))
                    throw new IllegalStateException("Forbidden audio/runner/agent JAR entry: " + name);
            }
            if (!observed.contains("META-INF/MANIFEST.MF")) throw new IllegalStateException("Missing manifest.");
            Manifest manifest = jar.getManifest();
            if (manifest == null || !hash(expectedArtifact).equals(manifest.getMainAttributes().getValue("QuickMaster-Loudness-Attestation-SHA256"))
                    || !runner.equals(manifest.getMainAttributes().getValue("QuickMaster-Loudness-Runner-SHA256")))
                throw new IllegalStateException("Packaged manifest binding mismatch.");
            if (!Arrays.equals(expectedArtifact, entryBytes(jar, "META-INF/quickmaster/leveler-conformance.json", 262144))
                    || !Arrays.equals(expectedProfile, entryBytes(jar, "META-INF/quickmaster/leveler-conformance-profile.json", 262144))
                    || !Arrays.equals(expectedProfile, ConformanceCodec.profileBytes(ConformanceRequirement.OFFICIAL_LOUDNESS_V1))
                    || !algorithm.equals(jarCone(jar)))
                throw new IllegalStateException("The packaged byte cone/profile/payload differs from the measured build.");
        }
        // A fresh loader has only this JAR, not the test/application classpath or a dependency's manifest.
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader()))
        {
            Class<?> type = Class.forName("com.quickmaster.processing.dynamics.leveler.ConformanceArtifactLoader", true, loader);
            java.lang.reflect.Method read = type.getDeclaredMethod("loadCurrent");
            read.setAccessible(true);
            Object report = read.invoke(null);
            Object state = report.getClass().getMethod("state").invoke(report);
            ConformanceState expected = StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, run,
                    new BuildAlgorithmBinding(run.algorithmId(), algorithm, run.profileSha256(), hash(expectedArtifact), runner,
                            run.createdAtEpochSecond())).state();
            if (!state.toString().equals(expected.toString())) throw new IllegalStateException("Own-JAR loader disagrees with expected state.");
            if (Boolean.parseBoolean(System.getProperty("qm.officialLoudness", "false")) && expected != ConformanceState.PASSED)
                throw new IllegalStateException("Official packaged report did not pass.");
            System.out.println("REOPENED_JAR state=" + state + " sha256=" + OfficialWaveReader.sha256(jarPath)
                    + " algorithm=" + algorithm + " runner=" + runner);
        }
    }

    static String cone(Path root, boolean runner) throws Exception
    {
        String[] names = runner ? runnerNames() : algorithmNames();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((runner ? "QM-LOUDNESS-RUNNER-1" : "QM-LOUDNESS-CLASS-CONE-1").getBytes(StandardCharsets.US_ASCII));
        unsigned(digest, names.length, 4);
        for (String name : names)
        {
            Path file = root.resolve(name);
            OfficialWaveReader.validateRegular(file);
            long size = Files.size(file);
            if (size < 1L || size > 1048576L) throw new IllegalArgumentException("Class entry exceeds cap.");
            byte[] bytes = Files.readAllBytes(file);
            byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
            unsigned(digest, encoded.length, 4);
            digest.update(encoded);
            unsigned(digest, bytes.length, 8);
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String jarCone(JarFile jar) throws Exception
    {
        String[] names = algorithmNames();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("QM-LOUDNESS-CLASS-CONE-1".getBytes(StandardCharsets.US_ASCII));
        unsigned(digest, names.length, 4);
        for (String name : names)
        {
            byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
            byte[] bytes = entryBytes(jar, name, 1048576);
            unsigned(digest, encoded.length, 4);
            digest.update(encoded);
            unsigned(digest, bytes.length, 8);
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] entryBytes(JarFile jar, String name, int cap) throws Exception
    {
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null || entry.getSize() < 1L || entry.getSize() > cap)
            throw new IllegalStateException("Missing or oversized packaged entry: " + name);
        try (InputStream stream = jar.getInputStream(entry))
        {
            byte[] bytes = stream.readNBytes(cap + 1);
            if (bytes.length > cap || bytes.length != entry.getSize() || stream.read() != -1)
                throw new IllegalStateException("Packaged entry extent mismatch.");
            return bytes;
        }
    }

    private static String[] algorithmNames()
    {
        return new String[] {
            "com/quickmaster/processing/dynamics/leveler/BuildAlgorithmBinding.class",
            "com/quickmaster/processing/dynamics/leveler/CancellationToken.class",
            "com/quickmaster/processing/dynamics/leveler/ConformanceArtifactLoader.class",
            "com/quickmaster/processing/dynamics/leveler/ConformanceCodec.class",
            "com/quickmaster/processing/dynamics/leveler/ConformanceRequirement.class",
            "com/quickmaster/processing/dynamics/leveler/ConformanceRun.class",
            "com/quickmaster/processing/dynamics/leveler/KWeightingAdapter.class",
            "com/quickmaster/processing/dynamics/leveler/LevelerAnalysisEngine.class",
            "com/quickmaster/processing/dynamics/leveler/LoudnessAnalyzer.class",
            "com/quickmaster/processing/dynamics/leveler/LoudnessConformanceGuard.class",
            "com/quickmaster/processing/dynamics/leveler/LoudnessCore.class",
            "com/quickmaster/processing/dynamics/leveler/LoudnessStandard.class",
            "com/quickmaster/processing/dynamics/leveler/model/AudioFormat.class",
            "com/quickmaster/processing/dynamics/leveler/model/ChannelLayout.class",
            "com/quickmaster/processing/dynamics/leveler/model/ConformanceReason.class",
            "com/quickmaster/processing/dynamics/leveler/model/ConformanceState.class",
            "com/quickmaster/processing/dynamics/leveler/model/FrozenList.class",
            "com/quickmaster/processing/dynamics/leveler/model/LoudnessTimeline.class",
            "com/quickmaster/processing/dynamics/leveler/model/LoudnessValueKind.class",
            "com/quickmaster/processing/dynamics/leveler/model/MeasuredLoudness.class",
            "com/quickmaster/processing/dynamics/leveler/model/OfficialSignalEvidence.class",
            "com/quickmaster/processing/dynamics/leveler/model/ReadingKind.class",
            "com/quickmaster/processing/dynamics/leveler/model/ReadoutMode.class",
            "com/quickmaster/processing/dynamics/leveler/model/RequiredOfficialReading.class",
            "com/quickmaster/processing/dynamics/leveler/model/RequiredSetReport.class",
            "com/quickmaster/processing/dynamics/leveler/model/StandardValidationReport.class"
        };
    }

    private static String[] runnerNames()
    {
        return new String[] {
            "com/quickmaster/processing/dynamics/leveler/ConformancePackageAssembler.class",
            "com/quickmaster/processing/dynamics/leveler/OfficialLoudnessRunner.class",
            "com/quickmaster/processing/dynamics/leveler/OfficialWaveReader.class"
        };
    }

    private static Path propertyPath(String name)
    {
        String value = System.getProperty(name);
        return value == null || value.isBlank() || value.startsWith("$") ? null : Path.of(value);
    }

    private static void unsigned(MessageDigest digest, long value, int width)
    {
        for (int shift = (width - 1) * 8; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    static String hash(byte[] bytes) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

