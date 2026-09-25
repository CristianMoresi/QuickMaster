package com.quickmaster.processing.dynamics.leveler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import java.util.zip.ZipEntry;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Only product I/O boundary: immutable bytes from this exact class's local containing JAR. */
final class ConformanceArtifactLoader
{
    private ConformanceArtifactLoader() { }

    static StandardValidationReport loadCurrent()
    {
        try
        {
            Object[] bundle = readBundle();
            if (bundle == null)
                return StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, null, null);
            return StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1,
                    (ConformanceRun) bundle[0], (BuildAlgorithmBinding) bundle[1]);
        }
        catch (IOException ex) { return failedReport(); }
        catch (NoSuchAlgorithmException ex) { return failedReport(); }
        catch (IllegalArgumentException ex) { return failedReport(); }
        catch (IllegalStateException ex) { return failedReport(); }
        catch (ArithmeticException ex) { return failedReport(); }
    }

    static BuildAlgorithmBinding currentBuildBinding()
    {
        try
        {
            Object[] bundle = readBundle();
            return bundle == null ? null : (BuildAlgorithmBinding) bundle[1];
        }
        catch (IOException ex) { return null; }
        catch (NoSuchAlgorithmException ex) { return null; }
        catch (IllegalArgumentException ex) { return null; }
        catch (IllegalStateException ex) { return null; }
        catch (ArithmeticException ex) { return null; }
    }

    private static StandardValidationReport failedReport()
    {
        ConformanceRun failure = new ConformanceRun("", "", new FrozenList<OfficialSignalEvidence>(new Object[0]),
                false, true, ConformanceReason.ATTESTATION_MISMATCH,
                "QM-LOUDNESS-CORE-BS1770-5-V1", "", "", "", 0L);
        return StandardValidationReport.verifyBound(ConformanceRequirement.OFFICIAL_LOUDNESS_V1, failure, null);
    }

    private static Object[] readBundle() throws IOException, NoSuchAlgorithmException
    {
        URL own = ConformanceArtifactLoader.class.getResource("ConformanceArtifactLoader.class");
        if (own == null || !"jar".equals(own.getProtocol())) return null;
        URLConnection connection = own.openConnection();
        connection.setUseCaches(false);
        if (!(connection instanceof JarURLConnection)) throw new IllegalArgumentException("Expected own JAR connection.");
        JarURLConnection jarConnection = (JarURLConnection) connection;
        URL location = jarConnection.getJarFileURL();
        if (!"file".equals(location.getProtocol()) || location.getHost().length() != 0)
            throw new IllegalArgumentException("Conformance accepts only the local containing JAR.");
        JarFile jar = jarConnection.getJarFile();
        try
        {
            Manifest manifest = jar.getManifest();
            Attributes attributes = manifest == null ? null : manifest.getMainAttributes();
            String attestation = attributes == null ? null
                    : attributes.getValue("QuickMaster-Loudness-Attestation-SHA256");
            String runner = attributes == null ? null : attributes.getValue("QuickMaster-Loudness-Runner-SHA256");
            ZipEntry payloadEntry = jar.getJarEntry("META-INF/quickmaster/leveler-conformance.json");
            ZipEntry profileEntry = jar.getJarEntry("META-INF/quickmaster/leveler-conformance-profile.json");
            if (attestation == null && runner == null && payloadEntry == null && profileEntry == null) return null;
            if (attestation == null || runner == null || payloadEntry == null || profileEntry == null)
                throw new IllegalArgumentException("Incomplete conformance bundle.");
            byte[] payload = readEntry(jar, payloadEntry, 262144);
            byte[] profile = readEntry(jar, profileEntry, 262144);
            ConformanceRequirement requirement = ConformanceRequirement.OFFICIAL_LOUDNESS_V1;
            if (!Arrays.equals(profile, ConformanceCodec.profileBytes(requirement))
                    || !hash(profile).equals("daad1207ff680b67b64108692a2070be389227ac8ddffa177ae9b8ac39ffbc14")
                    || !hash(payload).equals(attestation))
                throw new IllegalArgumentException("Profile or attestation bytes mismatch.");
            ConformanceRun run = ConformanceCodec.decodeArtifact(payload);
            String algorithm = classCone(jar);
            String profileHash = hash(profile);
            if (!algorithm.equals(run.algorithmSha256()) || !profileHash.equals(run.profileSha256())
                    || !runner.equals(run.runnerSha256()) || !requirement.algorithmId().equals(run.algorithmId()))
                throw new IllegalArgumentException("The running JAR does not match its attestation.");
            BuildAlgorithmBinding binding = new BuildAlgorithmBinding(requirement.algorithmId(), algorithm, profileHash,
                    attestation, runner, run.createdAtEpochSecond());
            return new Object[] {run, binding};
        }
        finally
        {
            jar.close();
        }
    }

    private static byte[] readEntry(JarFile jar, ZipEntry entry, int cap) throws IOException
    {
        long length = entry.getSize();
        if (length <= 0L || length > cap) throw new IllegalArgumentException("Invalid conformance entry size.");
        byte[] result = new byte[(int) length];
        byte[] chunk = new byte[32768];
        InputStream input = jar.getInputStream(entry);
        try
        {
            int total = 0;
            int count;
            while ((count = input.read(chunk, 0, chunk.length)) != -1)
            {
                if (count <= 0 || count > result.length - total)
                    throw new IllegalArgumentException("Inconsistent JAR entry extent.");
                System.arraycopy(chunk, 0, result, total, count);
                total += count;
            }
            if (total != result.length) throw new IllegalArgumentException("Truncated JAR entry.");
            return result;
        }
        finally
        {
            input.close();
        }
    }

    private static String classCone(JarFile jar) throws IOException, NoSuchAlgorithmException
    {
        String[] names = new String[] {
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
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("QM-LOUDNESS-CLASS-CONE-1".getBytes(StandardCharsets.US_ASCII));
        unsigned(digest, names.length, 4);
        for (int i = 0; i < names.length; i++)
        {
            byte[] name = names[i].getBytes(StandardCharsets.US_ASCII);
            ZipEntry entry = jar.getJarEntry(names[i]);
            if (entry == null) throw new IllegalArgumentException("Missing algorithm class.");
            byte[] bytes = readEntry(jar, entry, 1048576);
            unsigned(digest, name.length, 4);
            digest.update(name);
            unsigned(digest, bytes.length, 8);
            digest.update(bytes);
        }
        return hex(digest.digest());
    }

    private static void unsigned(MessageDigest digest, long value, int width)
    {
        for (int shift = (width - 1) * 8; shift >= 0; shift -= 8)
            digest.update((byte) (value >>> shift));
    }

    private static String hash(byte[] bytes) throws NoSuchAlgorithmException
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        return hex(digest.digest());
    }

    private static String hex(byte[] digest)
    {
        byte[] result = new byte[digest.length * 2];
        for (int i = 0; i < digest.length; i++)
        {
            int high = (digest[i] >>> 4) & 15;
            int low = digest[i] & 15;
            result[2 * i] = (byte) (high < 10 ? '0' + high : 'a' + high - 10);
            result[2 * i + 1] = (byte) (low < 10 ? '0' + low : 'a' + low - 10);
        }
        return new String(result, StandardCharsets.US_ASCII);
    }
}
