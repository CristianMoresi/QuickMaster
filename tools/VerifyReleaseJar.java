import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.jar.JarFile;

/** Release-only check, launched in source-file mode; never shipped inside the app. */
public class VerifyReleaseJar {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: VerifyReleaseJar.java jar version sha256");
        }
        Path path = Path.of(args[0]).toAbsolutePath();
        String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        if (!actual.equalsIgnoreCase(args[2])) {
            throw new IllegalStateException("Release JAR SHA-256 mismatch");
        }
        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry("META-INF/maven/com.quickmaster/quickmaster/pom.properties");
            if (entry == null) throw new IllegalStateException("Missing Maven version");
            Properties properties = new Properties();
            try (var input = jar.getInputStream(entry)) {
                properties.load(input);
            }
            if (!args[1].equals(properties.getProperty("version"))) {
                throw new IllegalStateException("Release JAR version mismatch");
            }
        }
        // Isolated loader ensures evidence is read from this JAR, not workspace classes.
        try (URLClassLoader loader = new URLClassLoader(new URL[] {path.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName(
                    "com.quickmaster.processing.dynamics.leveler.ConformanceArtifactLoader", true, loader);
            var load = type.getDeclaredMethod("loadCurrent");
            load.setAccessible(true);
            Object report = load.invoke(null);
            Object state = report.getClass().getMethod("state").invoke(report);
            if (!"PASSED".equals(state.toString())) {
                throw new IllegalStateException("Release JAR loudness conformance is " + state);
            }
        }
        System.out.println("RELEASE_JAR_PASS version=" + args[1] + " sha256=" + actual);
    }
}
