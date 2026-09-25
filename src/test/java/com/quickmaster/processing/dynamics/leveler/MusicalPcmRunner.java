package com.quickmaster.processing.dynamics.leveler;

import java.nio.file.*;
import java.io.PrintWriter;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Standalone real JUnit launcher; no Maven and no shared target directory. */
public final class MusicalPcmRunner
{
    public static void main(String[] args)throws Exception {
        Path out=MusicalPcmMatrixTest.OUT;
        try(var entries=Files.list(out)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Output must remain newly reserved and empty: "+out);}
        MusicalPcmMatrixTest.write(out.resolve("truth-catalog-before-engine.json"),MusicalPcmFixture.catalog());
        MusicalPcmMatrixTest.write(out.resolve("runtime.json"),MusicalPcmMatrixTest.m("javaVersion",System.getProperty("java.version"),"vendor",System.getProperty("java.vendor"),
                "vm",System.getProperty("java.vm.name"),"os",System.getProperty("os.name"),"arch",System.getProperty("os.arch"),
                "officialGate","unmodified; no synthetic PASSED", "renderAndTruePeak","PENDING_M005", "A05_INTERSAMPLE_PEAK","NOT_APPLICABLE_THIS_MILESTONE"));
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(MusicalPcmMatrixTest.class)).build());
        var summary=listener.getSummary();try(PrintWriter writer=new PrintWriter(Files.newBufferedWriter(out.resolve("junit-summary.txt")))){summary.printTo(writer);summary.printFailuresTo(writer);}
        MusicalPcmMatrixTest.write(out.resolve("junit-summary.json"),MusicalPcmMatrixTest.m("testsFound",summary.getTestsFoundCount(),"testsStarted",summary.getTestsStartedCount(),
                "succeeded",summary.getTestsSucceededCount(),"failed",summary.getTestsFailedCount(),"aborted",summary.getTestsAbortedCount(),"skipped",summary.getTestsSkippedCount(),
                "containerFailures",summary.getContainersFailedCount()));
        summary.printTo(new PrintWriter(System.out,true));System.exit(summary.getTotalFailureCount()==0?0:1);
    }
}
