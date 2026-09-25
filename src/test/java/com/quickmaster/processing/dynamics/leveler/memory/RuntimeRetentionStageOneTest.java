package com.quickmaster.processing.dynamics.leveler.memory;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

class RuntimeRetentionStageOneTest
{
    private static final String SHA="441250987cdeb73fdfa2e78ebb157ea34f786df283d174fdd9ea83c593e2b508";
    private static final String ACTIVE_SHA="5f635ff7dba75f1866d74ce434d19c743da28ddaee0e6b8e6c6001886cf71db9";
    private static final String HISTORICAL_CONTEXT_SHA="2f8d668c8fcd3c6814dd089d16dd7085d4d3ea21aba331a276cda12fd81ddb08";
    private static Path activeSchema() throws Exception {return Path.of(Objects.requireNonNull(RuntimeRetentionStageOneTest.class.getResource("/leveler/active-retention-schema.json"),"Active schema test resource").toURI());}
    private static Path evidence() throws Exception {
        String configured=System.getProperty("qm.memory.evidence");
        if(configured==null)return Files.createTempDirectory("quickmaster-retention-");
        Path p=Path.of(configured);Files.createDirectories(p);return p;
    }
    private static Path classes(Class<?> anchor,String property) throws Exception {
        String configured=System.getProperty(property);
        return configured==null?Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI()):Path.of(configured);
    }
    private static Path schema() throws Exception {
        String configured=System.getProperty("qm.memory.schema");
        return configured==null?Path.of(Objects.requireNonNull(RuntimeRetentionStageOneTest.class.getResource(
                "/leveler/contracts/retention-schema-candidate.json"),"Historical retention schema resource").toURI()):Path.of(configured);
    }
    private record Trial(int exit,JsonObject json,Path root) { }
    private static Trial run(String mode,boolean agent,boolean lang,boolean util,String hash) throws Exception
    {
        Path root=Files.createTempDirectory(evidence(),mode+"-");
        Path jar=AgentJarBuilder.create(classes(ShadowReachabilityAgent.class,"qm.memory.testClasses"),root.resolve("retention-agent.jar"));
        List<String> command=new ArrayList<>();command.add(Path.of(System.getProperty("java.home"),"bin","java.exe").toString());
        command.add("-Xms16m");command.add("-Xmx256m");
        if(agent)command.add("-javaagent:"+jar);
        if(lang)command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        if(util)command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        String classpath=System.getProperty("java.class.path");
        if(mode.equals("min-unbound")||mode.equals("min-bound")) {
            classpath=historicalContextClasses(root)+java.io.File.pathSeparator+classpath;
            Files.writeString(root.resolve("subject-scope.txt"),"HISTORICAL_MINIMUM_COMPATIBILITY_ONLY: pinned six-double BodyContextVector precedes current classes; never active/bound application evidence.");
        }
        if(mode.equals("active-bound")) {
            Path candidate=com.quickmaster.processing.dynamics.leveler.RuntimeLevelerAcceptanceTest.candidate();
            classpath=candidate+java.io.File.pathSeparator+classpath;
            Files.writeString(root.resolve("candidate.sha256"),RetentionWhitelistV1.sha(Files.readAllBytes(candidate)));
        }
        command.add("-cp");command.add(classpath);command.add(mode.equals("active-static-reference-coverage")||mode.equals("active-comparison-shape")||mode.equals("active-context-shape")?RuntimeRetentionStageOneTest.class.getName():LevelerShadowMemoryHarness.class.getName());
        command.add(root.toString());command.add(schema().toString());command.add(hash);
        command.add(classes(com.quickmaster.processing.dynamics.LevelerProcessor.class,"qm.memory.classes").toString());command.add(mode);
        if(mode.startsWith("active-")||mode.equals("real-owner")){command.add(activeSchema().toString());command.add(ACTIVE_SHA);}
        Files.writeString(root.resolve("command.json"),new Gson().toJson(command));
        Files.writeString(root.resolve("agent.sha256"),RetentionWhitelistV1.sha(Files.readAllBytes(jar)));
        ProcessBuilder child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile());
        Map<String,String> inherited=new HashMap<>(child.environment());child.environment().clear();
        for(String name:List.of("SystemRoot","WINDIR"))if(inherited.containsKey(name))child.environment().put(name,inherited.get(name));
        child.environment().put("TEMP",root.toString());child.environment().put("TMP",root.toString());
        Process process=child.start();process.getOutputStream().close();
        if(!process.waitFor(40,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();fail("Owned small child timeout");}
        JsonObject result=JsonParser.parseString(Files.readString(root.resolve("result.json"))).getAsJsonObject();
        return new Trial(process.exitValue(),result,root);
    }
    private static RetainedGraphReport graph(Trial trial) {return new Gson().fromJson(trial.json.get("graph"),RetainedGraphReport.class);}
    private static RetentionWhitelistV1 whitelist() throws Exception {return new RetentionWhitelistV1(schema(),SHA);}
    private static Path historicalContextClasses(Path root) throws Exception {
        String configured=System.getProperty("qm.memory.historicalContextSource");
        Path original=configured==null?Path.of(Objects.requireNonNull(RuntimeRetentionStageOneTest.class.getResource(
                "/leveler/contracts/historical-BodyContextVector.java.txt"),"Historical context source resource").toURI()):Path.of(configured);
        byte[] source=Files.readAllBytes(original);assertEquals(HISTORICAL_CONTEXT_SHA,RetentionWhitelistV1.sha(source),"Literal historical source must be pinned before compile");
        Path folder=root.resolve("historical-context"),java=folder.resolve("BodyContextVector.java"),classes=folder.resolve("classes");Files.createDirectories(classes);Files.write(java,source,StandardOpenOption.CREATE_NEW);
        Files.writeString(folder.resolve("source.sha256"),HISTORICAL_CONTEXT_SHA,StandardOpenOption.CREATE_NEW);
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assertNotNull(compiler,"Historical fixture compiler required");
        try(var out=Files.newOutputStream(folder.resolve("compile.log"),StandardOpenOption.CREATE_NEW)) {
            assertEquals(0,compiler.run(null,out,out,"--release","17","-g:none","-encoding","UTF-8","-d",classes.toString(),java.toString()));
        }
        Path compiled=classes.resolve("com/quickmaster/processing/dynamics/leveler/model/BodyContextVector.class");
        Files.writeString(folder.resolve("class.sha256"),RetentionWhitelistV1.sha(Files.readAllBytes(compiled)),StandardOpenOption.CREATE_NEW);return classes;
    }
    @Test void comparisonV2SchemaHasExplicitHistoricalParentsAndLiteralPayload() throws Exception {
        JsonObject active=JsonParser.parseString(Files.readString(activeSchema())).getAsJsonObject();
        assertEquals("QM-S001-ACTIVE-RETENTION-3-CONTEXT",active.get("normativeSchemaId").getAsString(),"CONTEXT_RETENTION_SUCCESSOR_REQUIRED");
        assertEquals("2795e24224149d8a4712326655459ce139307b69fddbe0acdd1a5be5ab84d06d",active.get("comparisonParentSha256").getAsString());
        assertEquals("dbd591636e5caca299371ddf26f816066b35e50dbb2c681510b23e6d44ea9968",active.get("parentSha256").getAsString());
        assertEquals("950c093f6e1dd5b0d03852211b3af28b01e89055956f04d0075e40769fa0ba2a",active.get("parentStaticSha256").getAsString());
        assertFalse(active.get("approvedGlobalSchema").getAsBoolean());
        var method=assertDoesNotThrow(()->RetentionReconciler.class.getDeclaredMethod("comparisonDimensions",long.class,int.class));
        long[][] cases={{1,48000,1,1,178},{48000,48000,40,8,4784},{48001,48000,41,9,4962},{1228800000L,192000,256000,51200,30617600}};
        for(long[] c:cases)assertArrayEquals(new long[]{c[2],c[3],c[4]},(long[])method.invoke(null,c[0],(int)c[1]));
        var overflow=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,Long.MAX_VALUE,48000));
        assertInstanceOf(ArithmeticException.class,overflow.getCause());
    }
    @Test void contextPrimitiveMaskPreservesExactGraphAndRejectsEveryOutOfDomainShape() throws Exception {
        Trial trial=run("active-context-shape",true,true,true,SHA);assertEquals(0,trial.exit,trial.json.toString());
        var active=new RetentionWhitelistV1(schema(),SHA,activeSchema(),ACTIVE_SHA);var graph=graph(trial);RetentionReconciler.verify(graph,active,true);
        assertEquals(172,graph.nodes().size());assertEquals(91,graph.nodes().stream().filter(n->n.length()>=0).count());assertEquals(204,graph.edges().size());
        assertEquals("FIELD_COUNT",trial.json.get("historicalShapeError").getAsString());assertEquals(6,trial.json.get("numericDimension").getAsInt());
        assertEquals(8,trial.json.get("validMaskCount").getAsInt());assertEquals(4,trial.json.getAsJsonArray("invalidMaskGraphs").size());
        for(var row:trial.json.getAsJsonArray("invalidMaskGraphs")) {
            var invalid=new Gson().fromJson(row,RetainedGraphReport.class);assertEquals(trial.json.get("currentContextBytes").getAsLong(),invalid.unaccountedRetainedBytes());
            assertTrue(invalid.violations().stream().anyMatch(v->v.detail().equals("CONTEXT_MASK_DOMAIN")));assertThrows(AssertionError.class,()->RetentionReconciler.verify(invalid,active,true));
        }
        assertEquals(trial.json.get("currentContextBytes").getAsLong()-trial.json.get("historicalContextBytes").getAsLong(),trial.json.get("measuredContextDeltaBytes").getAsLong());
        assertFalse(trial.json.get("activeClassOverridden").getAsBoolean());
    }
    @Test void comparisonV2FourArraysAndAliasedFormatHaveCausalShapeControls() throws Exception {
        var active=new RetentionWhitelistV1(schema(),SHA,activeSchema(),ACTIVE_SHA);
        Trial t=run("active-comparison-shape",true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());
        RetainedGraphReport actual=graph(t);
        assertEquals(0,actual.unaccountedRetainedBytes(),"COMPARISON_GRAPH_UNACCOUNTED:"+actual.violations());
        RetentionReconciler.verify(actual,active,true);
        assertEquals(1,actual.dimensions().get("CP"));assertEquals(5,actual.dimensions().get("CS"));assertEquals(1,actual.dimensions().get("CL"));
        assertEquals(5,actual.nodes().stream().filter(n->n.rule().startsWith("comparison")).count());
        assertEquals(1,actual.nodes().stream().filter(n->n.rule().equals("format")).count());
        assertTrue(actual.edges().stream().anyMatch(e->e.path().endsWith(".comparison.format")&&e.repeated()));
        var omitted=new Gson().fromJson(t.json.get("comparisonRootOmitted"),RetainedGraphReport.class);
        assertEquals(0,omitted.nodes().stream().filter(n->n.rule().startsWith("comparison")).count());
        assertThrows(AssertionError.class,()->RetentionReconciler.verify(omitted,active,true));
        assertEquals(5,t.json.getAsJsonArray("comparisonMutants").size());
        for(JsonElement changed:t.json.getAsJsonArray("comparisonMutants")) {
            var mutant=new Gson().fromJson(changed,RetainedGraphReport.class);
            assertTrue(mutant.unaccountedRetainedBytes()>0||!mutant.violations().isEmpty());
            assertThrows(AssertionError.class,()->RetentionReconciler.verify(mutant,active,true));
        }
    }
    @Test void actualAgentMeasuresBothMinimumGraphsWithExactCounts() throws Exception
    {
        for(String mode:List.of("min-unbound","min-bound"))
        {
            Trial t=run(mode,true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());
            RetainedGraphReport r=graph(t);RetentionReconciler.verify(r,whitelist(),true);
            boolean bound=mode.equals("min-bound");
            assertEquals(bound?169:161,r.nodes().size());assertEquals(bound?91:87,r.nodes().stream().filter(n->n.length()>=0).count());assertEquals(bound?191:187,r.edges().size());
            assertTrue(r.totalRetainedBytes()>0);assertEquals(r.totalRetainedBytes(),r.accountedAllowedBytes());assertEquals(0,r.unaccountedRetainedBytes());
            assertFalse(r.approvedGlobalSchema());assertFalse(t.json.getAsJsonObject("frozenStaticGuard").get("passed").getAsBoolean(),"Pending Throwable cannot become a guard PASS");
        }
    }
    @Test void agentAndBothOpensAreMandatoryBeforeGraphWork() throws Exception
    {
        for(boolean[] flags:List.of(new boolean[]{false,true,true},new boolean[]{true,false,true},new boolean[]{true,true,false}))
        {
            Trial t=run("min-unbound",flags[0],flags[1],flags[2],SHA);assertEquals(2,t.exit);assertFalse(t.json.has("graph"));assertFalse(t.json.has("earlyProbesPassed"));
        }
        Trial badHash=run("min-unbound",true,true,true,"0".repeat(64));assertEquals(2,badHash.exit);assertTrue(badHash.json.get("failure").getAsString().contains("WHITELIST_HASH_MISMATCH"));
    }
    @Test void unknownNestedBytesAreAllMeasuredAndDefaultAllowIsDiscriminating() throws Exception
    {
        Trial t=run("unknown",true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());RetainedGraphReport r=graph(t);
        assertEquals(4,r.nodes().size());assertEquals(r.totalRetainedBytes(),r.unaccountedRetainedBytes());assertTrue(r.totalRetainedBytes()>64);assertFalse(r.stageOneGraphPassed());
        RetentionReconciler.verify(r,whitelist(),false);
        Trial control=run("allow-unknown",true,true,true,SHA);assertEquals(0,control.exit,control.json.toString());
        RetainedGraphReport bad=graph(control);assertTrue(bad.stageOneGraphPassed(),"Deleted UNKNOWN rule must expose the false-green control");
        assertThrows(AssertionError.class,()->assertFalse(bad.stageOneGraphPassed()));
    }
    @Test void falseZeroAndMissingRootCannotHideInSelfReportedTotals() throws Exception
    {
        Trial zero=run("zero-totals",true,true,true,SHA);assertEquals(0,zero.exit,zero.json.toString());assertFalse(graph(zero).nodes().isEmpty());
        assertThrows(AssertionError.class,()->RetentionReconciler.verify(graph(zero),whitelist(),true));
        Trial root=run("omit-cache",true,true,true,SHA);assertEquals(0,root.exit,root.json.toString());
        assertThrows(AssertionError.class,()->RetentionReconciler.verify(graph(root),whitelist(),true));
    }
    @Test void baselineCallerThreadLocalQueueAndSentinelDiffsHaveDeletionControls() throws Exception
    {
        for(String mode:List.of("probe-threadLocal","probe-inheritableThreadLocal","probe-queue","probe-callback","probe-future","probe-sentinel"))
        {
            Trial t=run(mode,true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());
            assertFalse(t.json.getAsJsonObject("probeDifference").getAsJsonArray("violations").isEmpty());
            assertEquals(0,t.json.get("probeRemovedRootCount").getAsInt());assertEquals(0,t.json.get("probeRemovedViolationCount").getAsInt());
            assertTrue(graph(t).unaccountedRetainedBytes()>0);
            assertThrows(AssertionError.class,()->assertFalse(t.json.getAsJsonObject("probeDifference").getAsJsonArray("violations").isEmpty()==false));
        }
    }
    @Test void realOwnerIsReadAfterJoinedOneShotWorker() throws Exception
    {
        Trial t=run("real-owner",true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());assertTrue(t.json.get("ownerRootExtracted").getAsBoolean());
        RetentionReconciler.verify(graph(t),new RetentionWhitelistV1(schema(),SHA,activeSchema(),ACTIVE_SHA),true);
    }
    @Test void activeInheritedPublicationRootsAreMeasuredAndCannotBeOmitted() throws Exception {
        var active=new RetentionWhitelistV1(schema(),SHA,activeSchema(),ACTIVE_SHA);
        Trial t=run("active-owner",true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());
        RetentionReconciler.verify(graph(t),active,true);assertEquals(1,graph(t).dimensions().get("A"));assertEquals(0,graph(t).dimensions().get("Z"));
        assertTrue(graph(t).nodes().stream().anyMatch(n->n.path().equals("owner.published")));assertFalse(graph(t).approvedGlobalSchema());
        assertFalse(graph(t).nodes().stream().anyMatch(n->n.type().equals("[F")),"No owned PCM or dense gain envelope");
        for(String mode:List.of("active-omit-cache","active-zero-totals")) {
            Trial mutant=run(mode,true,true,true,SHA);assertEquals(0,mutant.exit,mutant.json.toString());
            assertThrows(AssertionError.class,()->RetentionReconciler.verify(graph(mutant),active,true));
        }
    }
    @Test void activeUnknownDescendantsRemainUnaccountedAndMeasured() throws Exception {
        var active=new RetentionWhitelistV1(schema(),SHA,activeSchema(),ACTIVE_SHA);
        Trial t=run("active-unknown",true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());
        assertEquals(4,graph(t).nodes().size());assertEquals(graph(t).totalRetainedBytes(),graph(t).unaccountedRetainedBytes());RetentionReconciler.verify(graph(t),active,false);
        Trial deleted=run("active-allow-unknown",true,true,true,SHA);assertEquals(0,deleted.exit,deleted.json.toString());
        assertTrue(graph(deleted).stageOneGraphPassed());assertThrows(AssertionError.class,()->assertFalse(graph(deleted).stageOneGraphPassed()));
    }
    @Test void activeAuthenticBoundEvidenceGroupsAndSparsePiecesAreAllRetainedWithinSchema() throws Exception {
        var active=new RetentionWhitelistV1(schema(),SHA,activeSchema(),ACTIVE_SHA);
        Trial t=run("active-bound",true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());
        RetentionReconciler.verify(graph(t),active,true);assertEquals(94,graph(t).dimensions().get("E"));assertTrue(graph(t).dimensions().get("G")>0);assertTrue(graph(t).dimensions().get("T")>0);
        assertFalse(graph(t).nodes().stream().anyMatch(n->n.type().equals("[F")));assertTrue(t.json.getAsJsonObject("activeStaticGuard").get("passed").getAsBoolean());
        assertEquals(0,t.json.get("reportedDenseCount").getAsLong());assertEquals(0,t.json.get("reportedDenseElements").getAsLong());
    }
    @Test void activeLiveStaticMutationAndDeletedStaticScanCannotPass() throws Exception {
        Trial t=run("active-static-mutant",true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());
        JsonObject mutation=t.json.getAsJsonObject("staticMutation"),removed=t.json.getAsJsonObject("staticScanDeletion");
        assertFalse(mutation.get("passed").getAsBoolean());assertTrue(mutation.get("unaccountedStaticBytes").getAsLong()>0);
        assertTrue(mutation.getAsJsonArray("violations").toString().contains("STATIC_ATOM_CHANGED"));
        assertFalse(removed.get("passed").getAsBoolean());assertEquals(0,removed.get("inspectedFieldCount").getAsInt());assertTrue(removed.get("expectedFieldCount").getAsInt()>0);
        JsonObject profile=t.json.getAsJsonObject("profileV2Mutation");
        assertFalse(profile.get("passed").getAsBoolean());assertTrue(profile.get("unaccountedStaticBytes").getAsLong()>0);
        assertTrue(profile.getAsJsonArray("violations").toString().contains("PROFILE_V2_ID"));
    }
    @Test void activeFalseSchemaApprovalIsNotAValidSuccessor() throws Exception {
        JsonObject modified=JsonParser.parseString(Files.readString(activeSchema())).getAsJsonObject();modified.addProperty("approvedGlobalSchema",true);
        Path file=evidence().resolve("false-approved-schema.json");Files.writeString(file,modified.toString(),StandardOpenOption.CREATE_NEW);
        String hash=RetentionWhitelistV1.sha(Files.readAllBytes(file));
        var failure=assertThrows(IllegalArgumentException.class,()->new RetentionWhitelistV1(schema(),SHA,file,hash));assertEquals("ACTIVE_SCHEMA_AUTHORITY",failure.getMessage());
    }
    @Test void activeStaticReferencesMatchIndependentIdentityClosure() throws Exception {
        Trial t=run("active-static-reference-coverage",true,true,true,SHA);assertEquals(0,t.exit,t.json.toString());
        assertEquals(t.json.get("expectedIdentityCount").getAsLong(),t.json.get("observedIdentityCount").getAsLong());
        assertEquals(t.json.get("expectedBytes").getAsLong(),t.json.get("observedBytes").getAsLong());
        assertTrue(t.json.get("deletionDetected").getAsBoolean());assertFalse(t.json.getAsJsonObject("referentDeletionAudit").get("passed").getAsBoolean());
    }
    /** Separate child regression: enumerate every nonprimitive static referent, without enum/String exemptions. */
    public static void main(String[] args) throws Exception {
        if(args[4].equals("active-context-shape")){contextShapeChild(args);return;}
        if(args[4].equals("active-comparison-shape")){comparisonShapeChild(args);return;}
        JsonObject result=new JsonObject();int exit=0;
        try {
            MemoryAccess.probes();Path main=Path.of(args[3]);
            var whitelist=new RetentionWhitelistV1(Path.of(args[1]),args[2],Path.of(args[5]),args[6]);
            JsonObject guard=com.quickmaster.processing.dynamics.leveler.M004RuntimeGuardBridge.scanActive(main);
            assertTrue(guard.get("passed").getAsBoolean(),guard.toString());
            JsonObject observed=MemoryAccess.staticAudit(main,whitelist,false);result.add("actualAudit",observed);
            IdentityHashMap<Object,Boolean> expected=new IdentityHashMap<>();ArrayDeque<Object> queue=new ArrayDeque<>();
            for(String owner:com.quickmaster.processing.dynamics.leveler.M004RuntimeGuardBridge.activeOwners()) {
                Class<?> type=Class.forName(owner.replace('/','.'));
                for(var field:type.getDeclaredFields())if(java.lang.reflect.Modifier.isStatic(field.getModifiers())&&!field.getType().isPrimitive()) {
                    field.setAccessible(true);Object value=field.get(null);if(value!=null)queue.add(value);
                }
            }
            long bytes=0;
            while(!queue.isEmpty()) {
                Object value=queue.removeFirst();if(expected.put(value,Boolean.TRUE)!=null)continue;bytes=Math.addExact(bytes,ShadowReachabilityAgent.shallowSize(value));
                Class<?> type=value.getClass();
                if(type.isArray()) {if(!type.getComponentType().isPrimitive())for(int i=0;i<java.lang.reflect.Array.getLength(value);i++){Object ref=java.lang.reflect.Array.get(value,i);if(ref!=null)queue.add(ref);}}
                else for(Class<?> at=type;at!=null;at=at.getSuperclass())for(var field:at.getDeclaredFields())
                    if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())&&!field.getType().isPrimitive()){field.setAccessible(true);Object ref=field.get(value);if(ref!=null)queue.add(ref);}
            }
            IdentityHashMap<Object,Boolean> actual=new IdentityHashMap<>();long actualBytes=0;
            for(JsonElement element:observed.getAsJsonArray("sharedNodes")) {
                JsonObject row=element.getAsJsonObject();Object value=resolveStaticPath(row.get("path").getAsString());
                assertTrue(expected.containsKey(value),"Unexpected static identity");assertNull(actual.put(value,Boolean.TRUE),"Duplicate static identity");
                assertEquals(value.getClass().getName(),row.get("type").getAsString());long shallow=ShadowReachabilityAgent.shallowSize(value);
                assertEquals(shallow,row.get("shallowBytes").getAsLong());actualBytes=Math.addExact(actualBytes,shallow);
            }
            result.addProperty("expectedIdentityCount",expected.size());result.addProperty("observedIdentityCount",actual.size());result.addProperty("expectedBytes",bytes);result.addProperty("observedBytes",actualBytes);
            assertEquals(bytes,observed.get("sharedShallowBytes").getAsLong(),"STATIC_REFERENT_OMITTED_BYTES");
            assertEquals(expected.size(),actual.size(),"STATIC_REFERENT_OMITTED_IDENTITIES");
            for(Object value:expected.keySet())assertTrue(actual.containsKey(value),"STATIC_REFERENT_OMITTED_IDENTITY");
            JsonObject deleted=MemoryAccess.staticAudit(main,whitelist,false,true);result.add("referentDeletionAudit",deleted);
            long expectedBytes=bytes;assertThrows(AssertionError.class,()->assertEquals(expectedBytes,deleted.get("sharedShallowBytes").getAsLong(),"STATIC_REFERENT_OMITTED_BYTES"));
            assertTrue(deleted.getAsJsonArray("sharedNodes").size()<expected.size());result.addProperty("deletionDetected",true);
        } catch(Throwable failure) {exit=2;result.addProperty("failure",failure.toString());failure.printStackTrace();}
        result.addProperty("exit",exit);Files.writeString(Path.of(args[0]).resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result),StandardOpenOption.CREATE_NEW);
        if(exit!=0)System.exit(exit);
    }
    private static void contextShapeChild(String[] args) throws Exception {
        MemoryAccess.probes();Path root=Path.of(args[0]);
        var guard=com.quickmaster.processing.dynamics.leveler.M004RuntimeGuardBridge.scanActive(Path.of(args[3]));assertTrue(guard.get("passed").getAsBoolean(),guard.toString());
        var active=new RetentionWhitelistV1(Path.of(args[1]),args[2],Path.of(args[5]),args[6]);var auditor=new RetainedGraphAuditor(active);
        var owner=new com.quickmaster.processing.dynamics.LevelerProcessor();float[] pcm=new float[4801];owner.prepare(48000,pcm.length);owner.setEnabled(true);owner.analyze(pcm,1);
        var context=owner.getShadowAnalysis().cache().descriptors().get(0).context();var shape=context.getClass().getDeclaredFields();
        assertEquals(7,shape.length);assertEquals(6,Arrays.stream(shape).filter(f->f.getType()==double.class).count());
        var mask=context.getClass().getDeclaredField("loudnessAvailabilityMask");assertEquals(int.class,mask.getType());assertEquals(18,mask.getModifiers());mask.setAccessible(true);
        JsonObject output=new JsonObject();output.add("activeStaticGuard",guard);output.add("graph",new Gson().toJsonTree(auditor.auditActive(owner,RetainedGraphAuditor.Control.NONE)));
        output.addProperty("historicalShapeError",new RetentionWhitelistV1(Path.of(args[1]),args[2]).shapeError(context));
        int original=mask.getInt(context),valid=0;JsonArray invalid=new JsonArray();
        try {
            for(int value=0;value<=7;value++){mask.setInt(context,value);assertEquals("",active.shapeError(context));RetentionReconciler.verify(auditor.auditActive(owner,RetainedGraphAuditor.Control.NONE),active,true);valid++;}
            for(int value:new int[]{-1,8,Integer.MIN_VALUE,Integer.MAX_VALUE}){mask.setInt(context,value);invalid.add(new Gson().toJsonTree(auditor.auditActive(owner,RetainedGraphAuditor.Control.NONE)));}
        } finally {mask.setInt(context,original);}
        Path historical=historicalContextClasses(root);
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{historical.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            Class<?> type=loader.loadClass(context.getClass().getName());assertNotSame(context.getClass(),type);assertEquals(6,type.getDeclaredFields().length);
            Object prior=type.getConstructor(double.class,double.class,double.class,double.class,double.class,double.class).newInstance(0d,0d,0d,0d,0d,0d);
            long before=ShadowReachabilityAgent.shallowSize(prior),after=ShadowReachabilityAgent.shallowSize(context);output.addProperty("historicalContextBytes",before);output.addProperty("currentContextBytes",after);output.addProperty("measuredContextDeltaBytes",after-before);
        }
        output.addProperty("numericDimension",6);output.addProperty("validMaskCount",valid);output.add("invalidMaskGraphs",invalid);output.addProperty("activeClassOverridden",false);output.addProperty("exit",0);
        Files.writeString(root.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(output),StandardOpenOption.CREATE_NEW);
    }
    private static void comparisonShapeChild(String[] args) throws Exception {
        MemoryAccess.probes();
        var whitelist=new RetentionWhitelistV1(Path.of(args[1]),args[2],Path.of(args[5]),args[6]);
        var auditor=new RetainedGraphAuditor(whitelist);
        var owner=new com.quickmaster.processing.dynamics.LevelerProcessor();float[] pcm=new float[4801];
        owner.prepare(48000,pcm.length);owner.setEnabled(true);owner.analyze(pcm,1);
        var comparison=owner.getShadowAnalysis().cache().comparison();assertNotNull(comparison,"ACTUAL_COMPARISON_REQUIRED");
        JsonObject output=new JsonObject();output.add("graph",new Gson().toJsonTree(auditor.auditActive(owner,RetainedGraphAuditor.Control.NONE)));
        output.add("comparisonRootOmitted",new Gson().toJsonTree(auditor.auditActive(owner,RetainedGraphAuditor.Control.OMIT_COMPARISON_ROOT)));
        JsonArray mutants=new JsonArray();
        for(String name:List.of("shortValues","longValues","shortFlags","longFlags","format")) {
            var field=comparison.getClass().getDeclaredField(name);field.setAccessible(true);Object original=field.get(comparison);
            Object changed=name.equals("format")?new com.quickmaster.processing.dynamics.leveler.model.AudioFormat(48000,1,4801)
                    :java.lang.reflect.Array.newInstance(original.getClass().getComponentType(),java.lang.reflect.Array.getLength(original)+1);
            try{field.set(comparison,changed);mutants.add(new Gson().toJsonTree(auditor.auditActive(owner,RetainedGraphAuditor.Control.NONE)));}
            finally{field.set(comparison,original);}
        }
        output.add("comparisonMutants",mutants);output.addProperty("exit",0);
        Files.writeString(Path.of(args[0]).resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(output),StandardOpenOption.CREATE_NEW);
    }
    private static Object resolveStaticPath(String path) throws Exception {
        int dot=path.indexOf('.');Object value=Class.forName(path.substring(0,dot).replace('/','.'));int offset=dot;
        while(offset<path.length()) {
            if(path.charAt(offset)=='['){int close=path.indexOf(']',offset);value=java.lang.reflect.Array.get(value,Integer.parseInt(path.substring(offset+1,close)));offset=close+1;}
            else {int end=offset+1;while(end<path.length()&&path.charAt(end)!='.'&&path.charAt(end)!='[')end++;String name=path.substring(offset+1,end);
                Class<?> type=value instanceof Class<?> c?c:value.getClass();java.lang.reflect.Field found=null;
                for(Class<?> at=type;at!=null&&found==null;at=at.getSuperclass())try{found=at.getDeclaredField(name);}catch(NoSuchFieldException ignored){}
                if(found==null)throw new NoSuchFieldException(path);found.setAccessible(true);value=found.get(value instanceof Class<?>?null:value);offset=end;}
        }
        return value;
    }
}
