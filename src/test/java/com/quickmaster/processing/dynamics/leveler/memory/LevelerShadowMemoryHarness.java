package com.quickmaster.processing.dynamics.leveler.memory;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.FutureTask;
import com.quickmaster.processing.dynamics.LevelerProcessor;
import com.quickmaster.processing.dynamics.leveler.M004RuntimeGuardBridge;

/** Stage-one child only: small graph trials, never the required paired resource benchmark. */
public final class LevelerShadowMemoryHarness
{
    private LevelerShadowMemoryHarness() { }
    public static void main(String[] args) throws Exception
    {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        JsonObject result=new JsonObject();result.addProperty("stage","SMALL_GRAPH_PROBES_ONLY");
        result.addProperty("schemaApproved",false);result.addProperty("globalAC10","NOT_EVALUATED");
        try
        {
            MemoryAccess.probes();result.addProperty("earlyProbesPassed",true);
            String mode=args[4];result.addProperty("mode",mode);
            // Current real owners use the V2 successor; min-* synthetic fixtures keep historical V1 semantics.
            boolean active=mode.startsWith("active-")||mode.equals("real-owner");
            var whitelist=active?new RetentionWhitelistV1(Path.of(args[1]),args[2],Path.of(args[5]),args[6]):new RetentionWhitelistV1(Path.of(args[1]),args[2]);
            var auditor=new RetainedGraphAuditor(whitelist);
            RetainedGraphReport graph;
            if(active) {
                result.addProperty("stage","ACTIVE_RETAINED_GRAPH_ONLY");
                JsonObject guard=M004RuntimeGuardBridge.scanActive(Path.of(args[3]));result.add("activeStaticGuard",guard);
                if(!guard.get("passed").getAsBoolean())throw new AssertionError("ACTIVE_STATIC_GUARD_REQUIRED");
                JsonObject statics=MemoryAccess.staticAudit(Path.of(args[3]),whitelist,false);result.add("activeStaticsBefore",statics);
                if(!statics.get("passed").getAsBoolean())throw new AssertionError("ACTIVE_STATIC_VALUES_REQUIRED:"+statics.get("violations"));
                if(mode.equals("active-static-mutant")) {
                    Class<?> stream=Class.forName("com.quickmaster.processing.dynamics.leveler.FiniteTruePeakStream");
                    double[][] matrix=(double[][])MemoryAccess.get(stream,"ANNEX2");double previous=matrix[0][0];
                    try {
                        matrix[0][0]=Math.nextUp(previous);
                        JsonObject changed=MemoryAccess.staticAudit(Path.of(args[3]),whitelist,false);
                        JsonObject deleted=MemoryAccess.staticAudit(Path.of(args[3]),whitelist,true);
                        result.add("staticMutation",changed);result.add("staticScanDeletion",deleted);
                        if(changed.get("passed").getAsBoolean()||changed.get("unaccountedStaticBytes").getAsLong()==0||deleted.get("passed").getAsBoolean())throw new AssertionError("STATIC_MUTATION_CONTROL_NOT_DISCRIMINATING");
                    } finally {matrix[0][0]=previous;}
                    if(whitelist.comparisonV2()) {
                        Object profile=com.quickmaster.processing.dynamics.leveler.LevelerCalibrationProfile.V2;
                        var identity=profile.getClass().getDeclaredField("profileId");identity.setAccessible(true);Object original=identity.get(profile);
                        try {
                            identity.set(profile,"QM-LEVELER-V9");
                            result.add("profileV2Mutation",MemoryAccess.staticAudit(Path.of(args[3]),whitelist,false));
                        } finally {identity.set(profile,original);}
                    }
                }
                if(mode.equals("active-unknown")||mode.equals("active-allow-unknown"))
                    graph=auditor.auditUnknownRoots(Map.of("external.nested",new RetainedGraphFixtures.UnknownHolder()),mode.equals("active-allow-unknown")?RetainedGraphAuditor.Control.ALLOW_UNKNOWN:RetainedGraphAuditor.Control.NONE);
                else {
                    LevelerProcessor owner=new LevelerProcessor();float[] pcm;
                    boolean bound=mode.equals("active-bound");
                    if(bound)pcm=musicalPcm("P02_REPEAT_OUTLIER");else pcm=new float[4801];
                    owner.prepare(48000,pcm.length);owner.setLeveling(1);owner.setEnabled(true);
                    Throwable[] failure=new Throwable[1];Thread worker=new Thread(()->{try{owner.analyze(pcm,bound?2:1);}catch(Throwable ex){failure[0]=ex;}},"S001-one-shot-test-worker");
                    worker.start();worker.join();worker=null;if(failure[0]!=null)throw new AssertionError("ANALYSIS_WORKER_FAILED",failure[0]);
                    result.addProperty("workerJoinedAndReleased",true);result.addProperty("ownerRootExtracted",true);
                    if(bound) {
                        if(!owner.getAnalysisDiagnostic().equals("STRUCTURAL_READY")||!owner.getShadowAnalysis().diagnostics().standardValidation().state().name().equals("PASSED"))throw new AssertionError("AUTHENTIC_BOUND_ACTIVE_PUBLICATION_REQUIRED");
                        if(!new com.quickmaster.processing.dynamics.leveler.LoudnessConformanceGuard().authorizesCurrentBuild(owner.getShadowAnalysis().diagnostics().standardValidation()))throw new AssertionError("CURRENT_BOUND_CONFORMANCE_REQUIRED");
                    }
                    var control=mode.equals("active-omit-cache")?RetainedGraphAuditor.Control.OMIT_CACHE_ROOT:mode.equals("active-zero-totals")?RetainedGraphAuditor.Control.ZERO_TOTALS:RetainedGraphAuditor.Control.NONE;
                    graph=auditor.auditActive(owner,control);
                    result.add("graph",new Gson().toJsonTree(graph));
                    result.addProperty("reportedDenseCount",owner.getShadowAnalysis().diagnostics().memoryCounters().denseEnvelopeCount());
                    result.addProperty("reportedDenseElements",owner.getShadowAnalysis().diagnostics().memoryCounters().denseEnvelopeElements());
                    if(bound&&(graph.dimensions().get("E")!=94||graph.dimensions().get("G")==0||graph.dimensions().get("T")==0))throw new AssertionError("ACTIVE_BOUND_COVERAGE_REQUIRED");
                    if(!mode.equals("active-omit-cache")&&!mode.equals("active-zero-totals"))RetentionReconciler.verify(graph,whitelist,true);
                }
                JsonObject after=MemoryAccess.staticAudit(Path.of(args[3]),whitelist,false);result.add("activeStaticsAfter",after);
                if(!after.get("passed").getAsBoolean()||!statics.equals(after))throw new AssertionError("ACTIVE_STATIC_VALUES_CHANGED");
            }
            else if(mode.equals("unknown")||mode.equals("allow-unknown"))
                graph=auditor.auditUnknownRoots(Map.of("external.nested",new RetainedGraphFixtures.UnknownHolder()),mode.equals("allow-unknown")?RetainedGraphAuditor.Control.ALLOW_UNKNOWN:RetainedGraphAuditor.Control.NONE);
            else if(mode.startsWith("probe-"))
            {
                List<Object> queue=new ArrayList<>();List<List<Object>> queues=List.of(queue);
                ThreadLocal<Object> local=new ThreadLocal<>();InheritableThreadLocal<Object> inherited=new InheritableThreadLocal<>();
                // Resolve/initialize the FutureTask class before the baseline, without scheduling anything.
                new FutureTask<Void>(() -> null);
                EscapeProbes.Capture before=EscapeProbes.capture(queues);
                byte[] payload=new byte[64];String disabled;
                switch(mode)
                {
                    case "probe-threadLocal"->{local.set(payload);disabled="threadLocal";}
                    case "probe-inheritableThreadLocal"->{inherited.set(payload);disabled="inheritableThreadLocal";}
                    case "probe-queue"->{queue.add(payload);disabled="queues";}
                    case "probe-callback"->{queue.add((Runnable)() -> {if(payload.length==0)throw new AssertionError();});disabled="queues";}
                    case "probe-future"->{queue.add(new FutureTask<Void>(() -> {if(payload.length==0)throw new AssertionError();return null;}));disabled="queues";}
                    case "probe-sentinel"->{HarnessEscapeSentinel.value=payload;disabled="sentinel";}
                    default->throw new IllegalArgumentException(mode);
                }
                EscapeProbes.Capture after=EscapeProbes.capture(queues);
                var difference=EscapeProbes.compare(before,after,Set.of());
                var omitted=EscapeProbes.compare(before,after,Set.of(disabled));
                JsonObject probeDifference=new JsonObject();probeDifference.add("violations",new Gson().toJsonTree(difference.violations()));
                probeDifference.add("forbiddenRootPaths",new Gson().toJsonTree(difference.forbiddenRoots().keySet()));
                result.add("probeDifference",probeDifference);
                result.addProperty("probeRemovedRootCount",omitted.forbiddenRoots().size());
                result.addProperty("probeRemovedViolationCount",omitted.violations().size());
                graph=auditor.auditUnknownRoots(difference.forbiddenRoots(),RetainedGraphAuditor.Control.NONE);
                local.remove();inherited.remove();queue.clear();HarnessEscapeSentinel.value=null;
            }
            else if(mode.equals("real-owner"))
            {
                LevelerProcessor owner=new LevelerProcessor();float[] pcm=new float[4801];
                owner.prepare(48000,pcm.length);
                Thread worker=new Thread(() -> owner.analyze(pcm,1),"M004-one-shot-test-worker");
                worker.start();worker.join();
                if(worker.isAlive())throw new AssertionError("WORKER_NOT_JOINED");
                worker=null;
                graph=auditor.audit(owner);
                result.addProperty("ownerRootExtracted",true);result.addProperty("workerJoinedAndReleased",true);
            }
            else
            {
                boolean bound=mode.equals("min-bound");
                RetainedGraphAuditor.Control control=mode.equals("zero-totals")?RetainedGraphAuditor.Control.ZERO_TOTALS:
                        mode.equals("omit-cache")?RetainedGraphAuditor.Control.OMIT_CACHE_ROOT:RetainedGraphAuditor.Control.NONE;
                graph=auditor.auditFixture(RetainedGraphFixtures.minimum(bound),control);
            }
            result.add("graph",new Gson().toJsonTree(graph));
            result.addProperty("stageOneGraphPassed",graph.stageOneGraphPassed());
            if(mode.equals("min-bound")||mode.equals("min-unbound")||mode.equals("real-owner"))
                RetentionReconciler.verify(graph,whitelist,true);
            else if(!mode.equals("zero-totals")&&!mode.equals("active-zero-totals") && graph.traversalComplete())RetentionReconciler.verify(graph,whitelist,false);
            if(!active)result.add("frozenStaticGuard",M004RuntimeGuardBridge.scan(Path.of(args[3])));
            result.addProperty("exit",0);
        }
        catch(Throwable ex)
        {
            result.addProperty("exit",2);result.addProperty("failureType",ex.getClass().getName());result.addProperty("failure",ex.toString());
            if(ex.getCause()!=null)result.addProperty("cause",ex.getCause().toString());
            Files.writeString(output.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
            ex.printStackTrace();System.exit(2);return;
        }
        Files.writeString(output.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
    private static float[] musicalPcm(String key) throws Exception {
        Class<?> fixture=Class.forName("com.quickmaster.processing.dynamics.leveler.MusicalPcmFixture");
        var catalog=fixture.getDeclaredMethod("catalog");catalog.setAccessible(true);
        for(Object test:(List<?>)catalog.invoke(null)) {
            var keyMethod=test.getClass().getDeclaredMethod("key");keyMethod.setAccessible(true);
            if(keyMethod.invoke(test).equals(key)) {
                var generate=fixture.getDeclaredMethod("generate",test.getClass(),int.class,int.class);generate.setAccessible(true);Object clip=generate.invoke(null,test,48000,2);
                var pcm=clip.getClass().getDeclaredMethod("pcm");pcm.setAccessible(true);return (float[])pcm.invoke(clip);
            }
        }
        throw new IllegalArgumentException("Missing frozen musical case");
    }
}
