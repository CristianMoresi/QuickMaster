package com.quickmaster.processing.dynamics.leveler;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Mechanical candidate composer. Input is 08 plus accepted annexes, never product classfiles. */
public final class M004StaticSchema
{
    private static final String ARCH = "/leveler/contracts/08-architecture.md";
    private static final String M = M004StaticClosureContract.M, L = M004StaticClosureContract.L;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    record Row(String id, String owner, String quantity, boolean array, String referenceElements, String capacity) { }
    record Composition(JsonObject semantic, JsonObject provenance, JsonObject derivation) { }
    private M004StaticSchema() { }

    static Composition compose() throws Exception
    {
        String architecture;
        try (var input = Objects.requireNonNull(M004StaticSchema.class.getResourceAsStream(ARCH), "Architecture contract resource")) {
            architecture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, List<M004BytecodePolicy.FieldRule>> base = parseArchitectureFields(architecture);
        JsonObject semantic = new JsonObject(), provenance = new JsonObject(), entries = new JsonObject();
        semantic.addProperty("schema_version", "QM-M004-RETENTION-CANDIDATE-1");
        semantic.addProperty("normativeSchemaId", M004StaticClosureContract.CONFORMANCE.get("proposed_semantic_schema").getAsString());
        semantic.addProperty("rootOwner", "com.quickmaster.processing.dynamics.LevelerProcessor");
        semantic.addProperty("rootField", "shadowAnalysis");
        semantic.addProperty("rootType", M + "ShadowAnalysisSnapshot");
        semantic.addProperty("rootAccess", 0x42);
        JsonArray roots = new JsonArray(); for (String root : List.of("result", "cache", "diagnostics")) roots.add(root); semantic.add("orderedRootFields", roots);
        semantic.addProperty("additionalRootsAllowed", false);
        List<Row> rows = rows(); Set<String> owned = new HashSet<>(); for (Row row : rows) if (!row.array) owned.add(row.owner);
        JsonArray classes = new JsonArray();
        for (String owner : new TreeSet<>(M004BytecodePolicy.SCANNED_M004_CLASSFILES))
        {
            JsonObject c = new JsonObject(); c.addProperty("owner", owner);
            String source;
            JsonObject closed = M004StaticClosureContract.CLASSES.get(owner);
            List<M004BytecodePolicy.FieldRule> fields = M004BytecodePolicy.FIELDS.get(owner);
            if (fields == null) throw new AssertionError("Missing normative field table " + owner);
            if (closed != null)
            {
                c.addProperty("classAccess", closed.get("classAccess").getAsInt());
                source = authority(owner);
                c.addProperty("ownership", owned.contains(owner) ? "owned_snapshot" : closed.get("ownership").getAsString());
            }
            else
            {
                c.addProperty("final", true);
                c.addProperty("ownership", owned.contains(owner) ? "owned_snapshot" : M004StaticClosureContract.ENUMS.containsKey(owner) ? "shared_enum_exact" : "call_local_not_retained");
                source = "08-architecture.md#Tipos, unidades e invariantes/" + owner.substring(owner.lastIndexOf('/') + 1);
                if (base.containsKey(owner) && !base.get(owner).equals(fields)) throw new AssertionError("08 field transcription differs from literal guard: " + owner);
                if (!base.containsKey(owner) && !fields.isEmpty() && !M004StaticClosureContract.ENUMS.containsKey(owner) && !owner.equals(L + "CancellationToken")) throw new AssertionError("No normative field provenance " + owner);
            }
            JsonArray fieldArray = new JsonArray();
            for (var field : fields)
            {
                JsonObject f = new JsonObject(); f.addProperty("name", field.name()); f.addProperty("descriptor", field.descriptor()); f.addProperty("access", field.access());
                JsonObject cv = new JsonObject(); cv.addProperty("attribute", "absent");
                if (closed != null) for (JsonElement e : closed.getAsJsonArray("fields")) if (e.getAsJsonObject().get("name").getAsString().equals(field.name())) cv = e.getAsJsonObject().getAsJsonObject("constantValue").deepCopy();
                f.add("constantValue", cv); fieldArray.add(f);
                JsonObject p = new JsonObject(); p.addProperty("authority", source); p.addProperty("selector", "fields[name=" + field.name() + "]");
                if (source.startsWith("08")) p.addProperty("normativeClause", clause(architecture, owner.substring(owner.lastIndexOf('/') + 1), field.name()));
                entries.add("classes/" + owner + "/fields/" + field.name(), p);
            }
            c.add("fields", fieldArray); c.addProperty("additionalFieldsAllowed", false); classes.add(c);
            JsonObject p = new JsonObject(); p.addProperty("authority", source); entries.add("classes/" + owner, p);
        }
        semantic.add("classes", classes);
        JsonObject enums = new JsonObject(); for (var entry : new TreeMap<>(M004StaticClosureContract.ENUMS).entrySet())
        { JsonArray values = new JsonArray(); entry.getValue().forEach(values::add); enums.add(entry.getKey(), values); JsonObject p = new JsonObject(); p.addProperty("authority", authority(entry.getKey())); entries.add("enums/" + entry.getKey(), p); }
        semantic.add("enums", enums);
        semantic.add("exactAliases", aliases());
        semantic.addProperty("unlistedAliasesAllowed", false);
        semantic.add("evidenceInvariants", M004StaticClosureContract.CONFORMANCE.get("evidenceInvariants").deepCopy());
        JsonObject statics = M004StaticClosureContract.CONFORMANCE.getAsJsonObject("staticValues").deepCopy();
        statics.add("requiredReadings", M004StaticClosureContract.RUNTIME_PROFILE.get("requiredReadings").deepCopy());
        statics.add("officialProtocolNorms", M004StaticClosureContract.RUNTIME_PROFILE.get("norms").deepCopy());
        JsonObject fixed = M004StaticClosureContract.STATIC.getAsJsonObject("fixedIdsAndProfile");
        JsonObject profile = fixed.getAsJsonObject("profile");
        statics.addProperty("calibrationProfileId", profile.getAsJsonObject("instanceId").get("value").getAsString());
        statics.add("calibrationConstantMethods", profile.get("constantReturningMethods").deepCopy());
        statics.addProperty("shortTransitionPredicate", profile.getAsJsonObject("shortTransition").get("predicate").getAsString());
        statics.add("loudnessStandardConstants", fixed.getAsJsonObject("loudnessStandardReference").get("methodConstants").deepCopy());
        semantic.add("staticValues", statics);
        semantic.add("adapters", adapters()); semantic.add("dimensions", dimensions());
        semantic.add("externalBoundaries", externalBoundaries());
        JsonArray rowJson = new JsonArray();
        for (Row row : rows)
        {
            JsonObject r = JSON.toJsonTree(row).getAsJsonObject();
            JsonArray refs = new JsonArray();
            if (!row.array) for (String field : referenceFields(row.owner)) refs.add(field);
            r.add("referenceFields", refs); rowJson.add(r);
            JsonObject p = new JsonObject(); p.addProperty("authority", "08-architecture.md#Whitelist DTO, adapters y caps exactos/Familia"); p.addProperty("role", row.id); entries.add("retentionRows/" + row.id, p);
        }
        semantic.add("retentionRows", rowJson);
        JsonObject derived = derive(rows);
        semantic.add("formulas", derived.get("polynomials").deepCopy());
        semantic.add("audioPayloads", M004StaticClosureContract.CONFORMANCE.getAsJsonObject("retention").get("unchangedAudioPayloads").deepCopy());
        semantic.addProperty("stringPayloadCap", "64*H_b+64*J+192*E");
        semantic.addProperty("scoreLogicalBytes", "40*P"); semantic.addProperty("groupArraysLogicalBytes", "12*sum(groupMembers)"); semantic.addProperty("weightsLogicalBytes", "8*S");
        semantic.add("unknownRule", JsonParser.parseString("""
                {"measure":"Instrumentation.getObjectSize for every reachable identity once",
                 "totalRetainedBytes":"add the measured shallow size for every identity",
                 "oneValidRule":"add the same measured shallow size to accountedAllowedBytes",
                 "zeroOrMultipleValidRules":"add the same measured shallow size to unaccountedRetainedBytes; record violation; continue BFS",
                 "continueAfterUnknown":true,"parentReconstructsRows":true,
                 "parentReconciliation":"totalRetainedBytes==accountedAllowedBytes+unaccountedRetainedBytes",
                 "acceptance":"unaccountedRetainedBytes==0 and totalRetainedBytes==accountedAllowedBytes",
                 "externalBoundaryBytesCompensateUnknown":false}
                """));
        semantic.addProperty("identityRule", "reference_identity_not_identityHashCode; each_owned_identity_counted_once");
        semantic.addProperty("enumerationOrder", "root result/cache/diagnostics; fields declaring binary name then field name; arrays/FrozenList by index");
        for (String key : List.of("exactAliases", "adapters", "dimensions", "externalBoundaries", "formulas", "audioPayloads", "stringPayloadCap", "scoreLogicalBytes", "groupArraysLogicalBytes", "weightsLogicalBytes", "identityRule", "unknownRule", "enumerationOrder", "orderedRootFields", "rootOwner", "rootField", "rootType", "rootAccess", "additionalRootsAllowed", "unlistedAliasesAllowed"))
        { JsonObject p = new JsonObject(); p.addProperty("authority", "08-architecture.md#Agente, statics y defensas runtime; Whitelist DTO, adapters y caps exactos"); entries.add(key, p); }
        JsonObject identityProvenance = new JsonObject();
        identityProvenance.addProperty("authority", "conformance-contract-001/schema-delta.001.json#/proposed_semantic_schema (accepted ADR011/decision013)");
        entries.add("normativeSchemaId", identityProvenance);
        entries.getAsJsonObject("unknownRule").addProperty("normativeClause", architecture.lines().filter(line -> line.startsWith("Para cada identidad alcanzable, `totalRetainedBytes`")).findFirst().orElseThrow());
        JsonObject p = new JsonObject(); p.addProperty("authority", "conformance-contract-001/schema-delta.001.json#/evidenceInvariants"); entries.add("evidenceInvariants", p);
        for (int i = 0; i < 94; i++) for (var e : statics.getAsJsonArray("requiredReadings").get(i).getAsJsonObject().entrySet())
        { p = new JsonObject(); p.addProperty("authority", "conformance-contract-001/runtime-profile.001.json#/requiredReadings/" + i + "/" + e.getKey()); entries.add("staticValues/requiredReadings/" + i + "/" + e.getKey(), p); }
        for (var e : statics.entrySet()) if (!e.getKey().equals("requiredReadings"))
        { p = new JsonObject(); p.addProperty("authority", Set.of("calibrationProfileId", "calibrationConstantMethods", "shortTransitionPredicate").contains(e.getKey()) ? "static-baseline-contract-001/static-baseline.001.json#/fixedIdsAndProfile/profile" : e.getKey().equals("loudnessStandardConstants") ? "conformance-contract-001/schema-delta.001.json#/classes[name=LoudnessStandard]/constants" : e.getKey().equals("officialProtocolNorms") ? "conformance-contract-001/runtime-profile.001.json#/norms" : "conformance-contract-001/schema-delta.001.json#/staticValues/" + e.getKey()); entries.add("staticValues/" + e.getKey(), p); }
        provenance.addProperty("status", "CANDIDATE_NOT_FROZEN_NOT_ACCEPTANCE");
        provenance.addProperty("baseMachineReadableSchemaPreviouslyExisted", false);
        provenance.addProperty("initialAuthority", "08/ADR007 prose field contract, mechanically transcribed; no invented historic global JSON/hash");
        provenance.addProperty("architectureSha256", AsyncEscapeBytecodeGuard.sha(architecture.getBytes(StandardCharsets.UTF_8)));
        provenance.addProperty("semanticSha256Candidate", digest(semantic)); provenance.add("entries", entries);
        provenance.addProperty("deduplication", "LoudnessStandard two fields cross-checked012 mirror, sole011 authority; DSPark not retained and sole011 guard binding");
        provenance.addProperty("excludedFromSemanticDigest", "this provenance/report, all classfile hashes, measured shallow bytes, timestamps, semantic hash itself, prior delta-only digest");
        provenance.addProperty("notImplemented", "BFS/javaagent/runtime statics/escape/RSS/p95/C28/globalAC10/acceptance");
        return new Composition(semantic, provenance, derived);
    }

    private static String authority(String owner)
    {
        if (owner.equals(ActiveLevelerGuardContract.CORE_OWNER))
            return "src/test/resources/leveler/core-chunk-delta.json#/fieldDescriptorChanges (representation B; pinned core-window predecessor; root GO after independent critique)";
        for (JsonElement e : M004StaticClosureContract.CONFORMANCE.getAsJsonArray("classes")) if (e.getAsJsonObject().get("name").getAsString().replace('.', '/').equals(owner)) return "conformance-contract-001/schema-delta.001.json#/classes[name=" + owner.replace('/', '.') + "]";
        for (JsonElement e : M004StaticClosureContract.STATIC.getAsJsonArray("classes")) if (e.getAsJsonObject().get("owner").getAsString().equals(owner)) return "static-baseline-contract-001/static-baseline.001.json#/classes[owner=" + owner + "] (accepted decision014)";
        return "08-architecture.md#Tipos, unidades e invariantes/" + owner.substring(owner.lastIndexOf('/') + 1);
    }
    static Map<String, List<M004BytecodePolicy.FieldRule>> parseArchitectureFields(String text)
    {
        Map<String, List<M004BytecodePolicy.FieldRule>> result = new TreeMap<>();
        Matcher matcher = Pattern.compile("`([A-Z][A-Za-z0-9]*)\\(([^`()]*)\\)`").matcher(text);
        while (matcher.find())
        {
            String owner = M + matcher.group(1);
            if (!M004BytecodePolicy.FIELDS.containsKey(owner)) continue;
            List<M004BytecodePolicy.FieldRule> fields = new ArrayList<>(); boolean complete = true;
            for (String arg : matcher.group(2).split(","))
            {
                String[] pair = arg.strip().split("\\s+"); if (pair.length != 2) { complete = false; break; }
                String type = pair[0].replaceAll("<[^>]+>", "");
                if (type.equals("String")) type = "java.lang.String";
                else if (type.equals("BitSet")) type = "java.util.BitSet";
                else if (Character.isUpperCase(type.charAt(0))) type = (M + type).replace('/', '.');
                fields.add(new M004BytecodePolicy.FieldRule(pair[1], M004StaticClosureContract.descriptor(type), 0x12));
            }
            if (complete) { var prior = result.putIfAbsent(owner, List.copyOf(fields)); if (prior != null && !prior.equals(fields)) throw new AssertionError("Contradictory 08 signatures for " + owner); }
        }
        if (!text.contains("exactamente `private final Object[] elements`")) throw new AssertionError("FrozenList provenance missing");
        result.put(M + "FrozenList", List.of(new M004BytecodePolicy.FieldRule("elements", "[Ljava/lang/Object;", 0x12)));
        return result;
    }
    private static String clause(String architecture, String type, String field)
    {
        for (String line : architecture.split("\\R")) if (line.contains(type) && line.contains(field) && (line.startsWith("- ") || type.equals("FrozenList") || type.equals("CancellationToken"))) return line;
        // Enums have their closed constant list in the same normative types/guard section.
        for (String line : architecture.split("\\R")) if (line.contains(type) && line.contains(field)) return line;
        if (type.equals("LayoutStatus") && Set.of("READY", "INSUFFICIENT_FEATURES", "TOO_MANY_SEGMENTS").contains(field))
            for (String line : architecture.split("\\R")) if (line.contains("`" + field + "`") && (line.contains("regiones") || line.contains("partición") || line.contains("empty"))) return line;
        if (field.equals("$VALUES")) return "08 enum protocol: canonical enum constants/$VALUES; exact Java17 synthetic backing";
        throw new AssertionError("No clause for " + type + "." + field);
    }
    private static JsonArray aliases()
    {
        JsonArray a = new JsonArray();
        for (String s : List.of("result.referencePlan==cache.referencePlan", "result.status==diagnostics.status", "targets[i].segmentId==descriptors[i].id", "protections[i].id==descriptors[i].id", "descriptors[i].range==layout.regions[i]", "sets[j].evidence[i].setId==sets[j].setId", "sets[j].evidence[i].setVersion==sets[j].setVersion", "algorithm/profile/requirement/set/version IDs and empty String==fixed shared literal", "enum values==fixed canonical shared identity")) a.add(s);
        return a;
    }
    private static JsonObject adapters()
    {
        return JsonParser.parseString("""
                {"FrozenList":{"fields":{"elements":{"descriptor":"[Ljava/lang/Object;","access":18}},"capacity":"exact logical size; zero spare; immutable no mutable exposure"},
                 "BitSet":{"fields":{"words":"[J","wordsInUse":"I","sizeIsSticky":"Z"},"capacity":"ceilDiv(R,64)","sizeIsSticky":true,"wordsInUse":"last nonzero word+1","unusedHighBits":0},
                 "String":{"fields":{"value":"[B","coder":"B"},"coder":0,"encoding":"LATIN1 ASCII","hashLength":64,"signalIdMin":1,"signalIdMax":128,"sharedIdMax":64,"sharedVersionMax":32,"ownedIdentity":"all owned strings and value arrays distinct even equal contents; never alias requirement table"}}
                """).getAsJsonObject();
    }
    private static JsonObject dimensions()
    {
        return JsonParser.parseString("""
                {"H100":"max(1,round(.1*sr))","Wm":"max(1,round(.4*sr))","Wq":"max(1,round(3*sr))","H500":"max(1,round(.5*sr))",
                 "M":"F==0?0:ceilDiv(F,H100)","Q":"F==0?0:ceilDiv(F,H100)","N":"F==0?0:ceilDiv(F,H500)","S":"0..64","P":"S*(S-1)/2","C":[1,2],"B":2048,"L":32,"d":22,
                 "D":"0..8*S+64","G":"groups.size","R":"pairs.size","K":"sum(groupMembers)+2*R<=S; disjoint; group size>=3","E":"sum(set.evidence.size)<=94","E_REQ":94,"H_b":[0,4],"J":"0..2","U":"H_b+J+2*E"}
                """).getAsJsonObject();
    }
    private static JsonObject externalBoundaries()
    {
        JsonObject boundary = new JsonObject(); JsonArray roots = new JsonArray(), callLocal = new JsonArray(), atoms = new JsonArray();
        for (String s : List.of("input float[F*C] PCM", "AnalysisDynamicsProcessor.published/DenseGainSchedule.sampleEnv float[F]", "legacy sectionLoud/sectionPeak/DenseGainCursor")) roots.add(s);
        for (String s : List.of("CancellationToken input", "BodyEligibility", "ConformanceRun", "BuildAlgorithmBinding", "LoudnessCore", "ConformanceCodec", "ConformanceArtifactLoader")) callLocal.add(s);
        for (String s : List.of("LevelerCalibrationProfile.V1", "LoudnessStandard.BS1770_5", "ConformanceRequirement.OFFICIAL_LOUDNESS_V1", "DenseGainSchedule.UNIT")) atoms.add(s);
        boundary.add("externalRootsNotOwnedByShadow", roots);
        boundary.addProperty("externalRootsOwnership", "measured separately as externalBoundaryBytes; forbidden from shadow; legacy identity/bytes compared with LEGACY_ONLY");
        boundary.addProperty("legacyGainEnv", "AnalysisDynamicsProcessor.gainEnv must remain null");
        boundary.add("callLocalNeverRetained", callLocal);
        boundary.addProperty("callLocalOwnership", "forbidden from snapshot and statics; not external roots and not owned allowances");
        boundary.addProperty("shadowEdgeToExternalOrCallLocal", "violation; measure and account as unaccounted; continue BFS; never authorize a traversal cut");
        boundary.addProperty("shadowTraversalMayCutByType", false);
        boundary.addProperty("externalBoundaryBytesCompensateUnknown", false);
        boundary.add("sharedStaticAtoms", atoms);
        boundary.addProperty("sharedStaticAtomsOwnership", "four exact statics inspected independently; not generic external roots or new owned types");
        boundary.addProperty("sharedStaticAtomsRequireExactIdentityAndRecursiveHash", true);
        return boundary;
    }
    static List<Row> rows()
    {
        List<Row> rows = new ArrayList<>();
        object(rows,"snapshot","ShadowAnalysisSnapshot","1"); object(rows,"result","ShadowAnalysisResult","1"); object(rows,"cache","ShadowAnalysisCache","1"); object(rows,"diagnostics","ShadowDiagnostics","1"); object(rows,"counters","ShadowMemoryCounters","1"); object(rows,"standardReport","StandardValidationReport","1"); object(rows,"format","AudioFormat","1"); object(rows,"oneAliasedReferencePlan","ReferencePlan","1"); object(rows,"twoSetReports","RequiredSetReport","2");
        array(rows,"fingerprint","[B","1","0","32");
        object(rows,"loudness","LoudnessTimeline","1"); rows.add(new Row("validityMasks","java/util/BitSet","2",false,"0","logical M/Q exact")); object(rows,"integrated","MeasuredLoudness","1");
        array(rows,"momentaryPower","[D","1","0","M"); array(rows,"momentaryWords","[J","1","0","ceilDiv(M,64)"); array(rows,"shortTermLufs","[D","1","0","Q"); array(rows,"shortTermWords","[J","1","0","ceilDiv(Q,64)");
        object(rows,"features","FeatureTimeline","1"); list(rows,"featureList","1","N"); object(rows,"structuralFrames","StructuralFrame","N"); array(rows,"frameChroma","[D","N","0","12"); array(rows,"frameSpectral","[D","N","0","8");
        object(rows,"layout","SegmentLayout","1"); list(rows,"regions","1","S"); object(rows,"ranges","FrameRange","S"); list(rows,"descriptors","1","S"); object(rows,"descriptorObjects","SegmentDescriptor","S"); object(rows,"segmentIds","SegmentId","S"); object(rows,"regionalLoudness","MeasuredLoudness","S"); object(rows,"contexts","BodyContextVector","S"); object(rows,"sketches","PcmSketch","S");
        list(rows,"bins","S","S*L"); object(rows,"binObjects","StructuralBin","S*L"); array(rows,"binChroma","[D","S*L","0","12"); array(rows,"binSpectral","[D","S*L","0","8"); array(rows,"sketchOuter","[[D","S","S*C","C"); array(rows,"sketchChannels","[D","S*C","0","B");
        list(rows,"protections","1","S"); object(rows,"protectionDecisions","ProtectionDecision","S"); object(rows,"protectionFlags","ProtectionFlags","S"); object(rows,"matrix","SimilarityMatrix","1"); list(rows,"scores","1","P"); object(rows,"scoreObjects","SimilarityScore","P");
        object(rows,"grouping","GroupingResult","1"); list(rows,"groups","1","G"); list(rows,"pairs","1","R"); object(rows,"groupObjects","ComparableGroup","G"); object(rows,"pairObjects","ComparablePair","R"); array(rows,"groupOrdinals","[I","G","0","k_i >=3; sum(k_i)+2R<=S"); array(rows,"groupQuality","[D","G","0","same k_i as ordinal array");
        list(rows,"targets","1","S"); object(rows,"targetObjects","ReferenceTarget","S"); object(rows,"targetLoudness","MeasuredLoudness","S"); array(rows,"weights","[D","1","0","S");
        list(rows,"diagnosticEntries","1","D"); object(rows,"diagnosticObjects","DiagnosticEntry","D"); list(rows,"sets","1","2"); list(rows,"evidenceLists","2","E"); object(rows,"evidenceObjects","OfficialSignalEvidence","E");
        rows.add(new Row("ownedStrings","java/lang/String","U",false,"0","H_b hashes + J manifests + 2E evidence strings")); array(rows,"ownedStringBytes","[B","U","0","hash64 / signalId1..128; distinct arrays");
        return List.copyOf(rows);
    }
    private static void object(List<Row> rows, String id, String type, String quantity) { rows.add(new Row(id, M + type, quantity, false, "0", "exact schema; nonnull; aliases explicit")); }
    private static void array(List<Row> rows, String id, String type, String quantity, String references, String capacity) { rows.add(new Row(id, type, quantity, true, references, capacity)); }
    private static void list(List<Row> rows, String id, String quantity, String references) { object(rows,id,"FrozenList",quantity); array(rows,id+"Backing","[Ljava/lang/Object;",quantity,references,"exact logical membership"); }
    static List<String> referenceFields(String owner)
    {
        if (owner.equals("java/lang/String")) return List.of("value:[B");
        if (owner.equals("java/util/BitSet")) return List.of("words:[J");
        return M004BytecodePolicy.FIELDS.get(owner).stream().filter(f -> (f.access() & 8) == 0 && (f.descriptor().startsWith("L") || f.descriptor().startsWith("["))).map(f -> f.name() + ":" + f.descriptor()).toList();
    }
    static JsonObject derive(List<Row> rows)
    {
        Map<String,Long> identities = new TreeMap<>(), arrays = new TreeMap<>(), edges = new TreeMap<>();
        JsonArray derivation = new JsonArray();
        for (Row row : rows)
        {
            add(identities,row.quantity,1); if (row.array) add(arrays,row.quantity,1);
            int references = row.array ? 0 : referenceFields(row.owner).size();
            if (row.array) add(edges,row.referenceElements,1); else add(edges,row.quantity,references);
            JsonObject r = JSON.toJsonTree(row).getAsJsonObject(); r.addProperty("referenceFieldsPerObject", references); derivation.add(r);
        }
        JsonObject polynomials = new JsonObject(); polynomials.add("identities", JSON.toJsonTree(identities)); polynomials.add("arrays", JSON.toJsonTree(arrays)); polynomials.add("edges", JSON.toJsonTree(edges));
        for (String key : List.of("identities", "arrays", "edges"))
        {
            Map<String,Long> normative = new TreeMap<>();
            String expression = M004StaticClosureContract.CONFORMANCE.getAsJsonObject("retention").get(key).getAsString();
            for (String term : expression.split("\\+")) add(normative,term.strip(),1);
            if (!polynomials.get(key).equals(JSON.toJsonTree(normative))) throw new AssertionError("Rows fail normative polynomial identity " + key + ": " + polynomials.get(key));
        }
        JsonObject result = new JsonObject(); result.add("polynomials", polynomials); result.add("rows", derivation);
        JsonObject goldens = new JsonObject(); JsonObject expected = M004StaticClosureContract.CONFORMANCE.getAsJsonObject("retention").getAsJsonObject("goldens");
        for (var e : expected.entrySet())
        {
            JsonObject dimension = e.getValue().getAsJsonObject(), actual = new JsonObject();
            actual.addProperty("identities", evaluate(identities,dimension)); actual.addProperty("arrays", evaluate(arrays,dimension)); actual.addProperty("edges", evaluate(edges,dimension));
            for (String key : List.of("identities","arrays","edges")) if (actual.get(key).getAsLong() != dimension.get(key).getAsLong()) throw new AssertionError("Row derivation differs at " + e.getKey() + "." + key + ": " + actual);
            goldens.add(e.getKey(), actual);
        }
        result.add("goldens", goldens); result.addProperty("notRuntimeTraversal", true); return result;
    }
    private static void add(Map<String,Long> p, String monomial, long multiplier)
    {
        long factor = multiplier; List<String> variables = new ArrayList<>();
        for (String part : monomial.split("\\*")) if (part.matches("[0-9]+")) factor = Math.multiplyExact(factor,Long.parseLong(part)); else variables.add(part);
        if (factor == 0) return; Collections.sort(variables); String key = variables.isEmpty() ? "1" : String.join("*",variables); p.merge(key,factor,Math::addExact);
    }
    static long evaluate(Map<String,Long> p, JsonObject dimensions)
    {
        long total = 0;
        for (var e : p.entrySet()) { long value = e.getValue(); if (!e.getKey().equals("1")) for (String v : e.getKey().split("\\*")) value = Math.multiplyExact(value,dimensions.get(v).getAsLong()); total = Math.addExact(total,value); }
        return total;
    }
    static JsonElement canonical(JsonElement value)
    {
        if (value.isJsonObject()) { JsonObject o = new JsonObject(); new TreeMap<>(value.getAsJsonObject().asMap()).forEach((k,v) -> o.add(k,canonical(v))); return o; }
        if (value.isJsonArray()) { JsonArray a = new JsonArray(); value.getAsJsonArray().forEach(v -> a.add(canonical(v))); return a; } return value.deepCopy();
    }
    static byte[] canonicalBytes(JsonElement json) { return JSON.toJson(canonical(json)).getBytes(StandardCharsets.UTF_8); }
    static String digest(JsonElement json) throws Exception { return AsyncEscapeBytecodeGuard.sha(canonicalBytes(json)); }
    public static void main(String[] args) throws Exception
    {
        Path out = args.length == 0 ? Path.of("target/leveler-static-evidence") : Path.of(args[0]); Files.createDirectories(out);
        Composition composed = compose();
        Files.write(out.resolve("retention-schema-candidate.json"), canonicalBytes(composed.semantic));
        Files.write(out.resolve("retention-schema-provenance.json"), canonicalBytes(composed.provenance));
        Files.write(out.resolve("retention-schema-derivation.json"), canonicalBytes(composed.derivation));
        System.out.println("CANDIDATE (not frozen) semantic SHA-256 " + digest(composed.semantic));
    }
}
