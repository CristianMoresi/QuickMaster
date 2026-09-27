import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import java.io.PrintWriter;
import java.nio.file.Path;

/** Executes regression tests with the actual application JAR first on the classpath. */
public class PackagedTestAudit {
    public static void main(String[] args) {
        int start = 0;
        if (args[0].equals("--jar")) {
            Path expected = Path.of(args[1]).toAbsolutePath().normalize();
            for (Class<?> type : new Class<?>[]{com.quickmaster.audio.WavFile.class,
                    com.quickmaster.processing.ProcessingPipeline.class,
                    com.quickmaster.processing.analysis.SpectrumAnalysis.class}) {
                try {
                    Path loaded = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
                    if (!loaded.equals(expected)) throw new AssertionError("Test loaded a different build: " + type + " " + loaded);
                    System.out.println("PACKAGED_TEST_CLASS " + type.getName() + " " + loaded);
                } catch (java.net.URISyntaxException e) { throw new AssertionError(e); }
            }
            start = 2;
        }
        var builder = LauncherDiscoveryRequestBuilder.request();
        for (int i = start; i < args.length; i++) builder.selectors(selectClass(args[i]));
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(builder.build());
        listener.getSummary().printTo(new PrintWriter(System.out, true));
        listener.getSummary().printFailuresTo(new PrintWriter(System.out, true));
        if (listener.getSummary().getTestsFoundCount() == 0 || listener.getSummary().getTotalFailureCount() != 0) System.exit(1);
    }
}
