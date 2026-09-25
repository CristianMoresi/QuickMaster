package com.quickmaster.processing.dynamics.leveler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** UI copy/wiring and pure presentation policy; native rendering is a separate check. */
class LevelerUiDiagnosticTest {
    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/com/quickmaster/ui/MainController.java"));
    }
    private static String text(String diagnostic, boolean audio, boolean enabled, double amount) throws Exception {
        Method method = Class.forName("com.quickmaster.ui.MainController").getDeclaredMethod(
                "levelerDiagnosticText", String.class, boolean.class, boolean.class, double.class);
        method.setAccessible(true);
        return (String) method.invoke(null, diagnostic, audio, enabled, amount);
    }
    @Test void noUnconditionalPromiseToFlattenAllParts() throws Exception {
        String body=source();
        assertFalse(body.contains("100% = no difference between parts"),
                "QM_LEVELER_UI_OVERPROMISE: protected sections/confidence/caps do not disappear at 100%");
        assertTrue(body.contains("comparable sections"));
        assertTrue(body.contains("intros, outros and breaks"));
    }
    @Test void currentDiagnosticIsActuallyWired() throws Exception {
        String body=source();
        assertTrue(body.contains("leveler.getAnalysisDiagnostic()"),
                "QM_LEVELER_UI_DIAGNOSTIC_ABSENT: current publication outcome is not presented");
        assertTrue(body.contains("levelerDiagnosticLabel.setText("));
        assertTrue(body.contains("updateLevelerDiagnostic();"));
    }
    @Test void readyMeansAnalyzedNotNecessarilyNonzeroGain() throws Exception {
        assertEquals("Ready · comparable sections only", text("STRUCTURAL_READY",true,true,1));
        assertEquals("Awaiting analysis", text("UNIT",true,true,1));
        assertEquals("Awaiting analysis", text("CLEARED",true,true,1));
    }
    @Test void everyFallbackNamesAnUnchangedOutcome() throws Exception {
        String[][] cases={
                {"INVALID_INPUT","Unchanged · invalid audio"},
                {"INSUFFICIENT_ANALYSIS","Unchanged · insufficient musical evidence"},
                {"STANDARD_VALIDATION_FAILED","Unchanged · measurement validation failed"},
                {"PEAK_UNSAFE","Unchanged · peak safety not verified"},
                {"CANCELLED","Unchanged · analysis cancelled"},
                {"INFEASIBLE_INPUT_BASELINE","Unchanged · input exceeds peak safety limit"}};
        for(String[] item:cases) assertEquals(item[1],text(item[0],true,true,.5),item[0]);
    }
    @Test void inputBypassAndZeroAmountHaveHonestPriority() throws Exception {
        assertEquals("Load audio to analyze",text("STRUCTURAL_READY",false,false,0));
        assertEquals("Bypassed",text("STRUCTURAL_READY",true,false,1));
        assertEquals("Leveling off (0%)",text("STRUCTURAL_READY",true,true,0));
    }
    @Test void unknownOrLegacyStatusCannotBeCalledReady() throws Exception {
        for(String status:new String[]{null,"LEGACY_READY","FUTURE_STATUS"})
            assertEquals("Unchanged · analysis unavailable",text(status,true,true,.5));
    }
    private static String generation(long current,long started,long ready,long failed,long cancelled) throws Exception {
        Method method=Class.forName("com.quickmaster.ui.MainController").getDeclaredMethod(
                "levelerDiagnosticForGeneration",String.class,boolean.class,boolean.class,double.class,
                long.class,long.class,long.class,long.class,long.class);
        method.setAccessible(true);
        return (String)method.invoke(null,"STRUCTURAL_READY",true,true,.5,current,started,ready,failed,cancelled);
    }
    @Test void upstreamOrSourceDirtyThenFailureNeverRestoresOldReady() throws Exception {
        assertEquals("Ready · comparable sections only",generation(1,1,1,-1,-1));
        assertEquals("Awaiting analysis",generation(2,1,1,-1,-1));
        assertEquals("Analyzing audio…",generation(2,2,1,-1,-1));
        assertEquals("Analysis failed · previous result is stale",generation(2,2,1,2,-1));
        assertEquals("Analysis cancelled · previous result is stale",generation(2,2,1,-1,2));
    }
    @Test void obsoleteJobsCannotHideOrAuthorizeCurrentReadiness() throws Exception {
        assertEquals("Ready · comparable sections only",generation(3,3,3,2,1));
        assertEquals("Analyzing audio…",generation(3,3,2,2,1));
        assertEquals("Awaiting analysis",generation(4,3,3,2,1));
    }
    @Test void generationFreshnessIsWiredAtActualSourceAndControlBoundaries() throws Exception {
        String body=source();
        assertTrue(body.contains("levelerReadyGeneration = generation;"),
                "QM_LEVELER_UI_STALE_READY: current adoption is not linked to its generation");
        for(String signature:new String[]{"private void onAudioReady(File chosen)",
                "private void afterTrim(String message)","private void onReset()"}) {
            int at=body.indexOf(signature);assertTrue(at>=0);
            assertTrue(body.substring(at,Math.min(at+300,body.length())).contains("invalidateOutputAnalysis();"),signature);
        }
        assertTrue(body.contains("levelerFailedGeneration = generation;"));
        assertTrue(body.contains("levelerCancelledGeneration = generation;"));
        assertTrue(body.contains("task.setOnCancelled("));
    }
}
