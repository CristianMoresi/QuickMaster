package com.quickmaster.processing.dynamics.leveler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import com.google.gson.*;
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

/** Read-only breakpoints on the actual product. No product instrumentation or replacement classes. */
final class MusicalContextJdiObserver
{
    private static final String PACKAGE = "com.quickmaster.processing.dynamics.leveler.";

    static JsonObject observe(String mode, String classpath) throws Exception
    {
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("home").setValue(System.getProperty("java.home"));
        args.get("options").setValue("-Xmx384m -cp \"" + classpath + "\"");
        args.get("main").setValue(MusicalContextJdiChild.class.getName() + " " + mode);
        args.get("suspend").setValue("true");
        VirtualMachine vm = connector.launch(args);
        Process child = vm.process();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<String> out = readers.submit(() -> new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Future<String> err = readers.submit(() -> new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        JsonObject result = new JsonObject();
        JsonArray cases = new JsonArray(); result.add("cases", cases);
        JsonObject current = null, comparison = null, kernel = null;
        boolean death = false, disconnect = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
        try
        {
            for (String name : List.of("BoundaryDetector", "SegmentDescriptorBuilder", "SegmentComparator",
                    "ComparisonComparator", "ComparisonFeatureExtractor", "LevelerAnalysisEngine", "MusicalContextJdiChild"))
            {
                ClassPrepareRequest request = vm.eventRequestManager().createClassPrepareRequest();
                request.addClassFilter(PACKAGE + name);
                request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); request.enable();
            }
            result.addProperty("enabledBeforeResume", true);
            vm.resume();
            while (!disconnect && System.nanoTime() < deadline)
            {
                EventSet set = vm.eventQueue().remove(100);
                if (set == null) continue;
                for (Event event : set)
                {
                    if (event instanceof ClassPrepareEvent prepared) install(vm, prepared.referenceType(), mode);
                    else if (event instanceof VMDeathEvent) death = true;
                    else if (event instanceof VMDisconnectEvent) disconnect = true;
                    else if (event instanceof BreakpointEvent hit)
                    {
                        StackFrame frame = hit.thread().frame(0);
                        String method = hit.location().method().name();
                        List<Value> values = frame.getArgumentValues();
                        String tag = (String) hit.request().getProperty("tag");
                        if (method.equals("mark"))
                        {
                            current = new JsonObject(); cases.add(current);
                            current.addProperty("label", ((StringReference) values.get(0)).value());
                            for (String key : List.of("noveltyEntries", "qReturns", "persistentReturns", "select", "radii", "comparisons", "trends", "extractions"))
                                current.add(key, new JsonArray());
                            comparison = null; kernel = null;
                        }
                        else if (current == null) throw new IllegalStateException("Unmarked product event " + method);
                        else if (method.equals("finish"))
                        {
                            current.add("child", JsonParser.parseString(((StringReference) values.get(0)).value()));
                            if (mode.equals("engine")) current.add("retained", retained(hit.thread().frame(1)));
                            comparison = null;
                        }
                        else if (method.equals("analyzeShadow"))
                        {
                            current.addProperty("engineSourceId", id(values.get(1)));
                            current.addProperty("enginePcmId", id(values.get(0)));
                            current.add("engineFormat", format((ObjectReference) values.get(1)));
                        }
                        else if (tag.equals("v2-extract-entry"))
                        {
                            JsonObject extraction = new JsonObject();
                            extraction.addProperty("pcmId", id(values.get(0))); extraction.addProperty("sourceId", id(values.get(1)));
                            extraction.add("entryLocation", location(hit.location()));
                            current.getAsJsonArray("extractions").add(extraction);
                        }
                        else if (tag.equals("v2-extract-return"))
                        {
                            JsonArray extractions = current.getAsJsonArray("extractions");
                            if (extractions.size() != 1) throw new IllegalStateException("QM_CONTEXT_V2_EXTRACT_COUNT");
                            JsonObject extraction = extractions.get(0).getAsJsonObject();
                            if (extraction.has("timeline")) throw new IllegalStateException("QM_CONTEXT_V2_DUPLICATE_EXTRACTION_RETURN");
                            extraction.add("timeline", timeline((ObjectReference) local(frame, "result")));
                            extraction.add("returnLocation", location(hit.location()));
                        }
                        else if (tag.equals("v2-compare-entry"))
                        {
                            comparison = new JsonObject(); current.getAsJsonArray("comparisons").add(comparison);
                            comparison.addProperty("route", "V2");
                            comparison.addProperty("sourceId", id(values.get(2))); comparison.addProperty("timelineId", id(values.get(3)));
                            comparison.addProperty("firstRangeId", id(values.get(0))); comparison.addProperty("secondRangeId", id(values.get(1)));
                            comparison.add("a", range((ObjectReference) values.get(0))); comparison.add("b", range((ObjectReference) values.get(1)));
                            comparison.addProperty("profile", string(field((ObjectReference) values.get(4), "profileId")));
                            StackFrame caller = hit.thread().frame(1);
                            if (!caller.location().declaringType().name().equals(PACKAGE + "LevelerAnalysisEngine")
                                    || !caller.location().method().name().equals("analyzeShadow"))
                                throw new IllegalStateException("QM_CONTEXT_V2_WRONG_CALLER");
                            comparison.addProperty("first", ((IntegerValue) local(caller, "first")).value());
                            comparison.addProperty("second", ((IntegerValue) local(caller, "second")).value());
                            comparison.addProperty("scoreIndex", ((IntegerValue) local(caller, "scoreIndex")).value());
                            comparison.add("entryLocation", location(hit.location()));
                            comparison.add("kernels", new JsonArray()); kernel = null;
                        }
                        else if (tag.equals("v2-kernel-entry"))
                        {
                            if (comparison == null || !"V2".equals(comparison.get("route").getAsString()))
                                throw new IllegalStateException("QM_CONTEXT_V2_ORPHAN_KERNEL");
                            kernel = new JsonObject();
                            kernel.addProperty("slot", comparison.getAsJsonArray("kernels").size() - 1);
                            kernel.addProperty("firstRangeId", id(values.get(0))); kernel.addProperty("secondRangeId", id(values.get(1)));
                            kernel.add("a", range((ObjectReference) values.get(0))); kernel.add("b", range((ObjectReference) values.get(1)));
                            kernel.addProperty("sourceId", id(values.get(2))); kernel.addProperty("timelineId", id(values.get(3)));
                            kernel.add("entryLocation", location(hit.location())); comparison.getAsJsonArray("kernels").add(kernel);
                        }
                        else if (tag.equals("v2-central") || tag.equals("v2-variant"))
                        {
                            int slot = tag.equals("v2-central") ? -1 : ((IntegerValue) local(frame, "slot")).value();
                            if (kernel == null || kernel.get("slot").getAsInt() != slot || kernel.has("actualScore"))
                                throw new IllegalStateException("QM_CONTEXT_V2_MISSING_OR_DUPLICATE_KERNEL_RETURN");
                            JsonObject score = score((ObjectReference) local(frame, slot < 0 ? "central" : "variant"));
                            kernel.add("actualScore", score);
                            kernel.add("returnLocation", location(hit.location()));
                        }
                        else if (method.equals("originalNovelty"))
                        {
                            JsonObject row = new JsonObject(); row.addProperty("featuresId", id(values.get(0)));
                            row.addProperty("frames", ((LongValue) values.get(1)).value());
                            if (tag.equals("return"))
                            {
                                ArrayReference q = (ArrayReference) frame.getValue(frame.visibleVariableByName("novelty"));
                                row.addProperty("qId", q.uniqueID()); row.add("bits", bits(q));
                                current.getAsJsonArray("qReturns").add(row);
                            }
                            else current.getAsJsonArray("noveltyEntries").add(row);
                        }
                        else if (method.equals("persistentContrast"))
                        {
                            JsonObject row = new JsonObject(); row.addProperty("featuresId", id(values.get(0)));
                            ArrayReference scores = (ArrayReference) frame.getValue(frame.visibleVariableByName("scores"));
                            row.addProperty("qId", scores.uniqueID()); row.add("bits", bits(scores));
                            current.getAsJsonArray("persistentReturns").add(row);
                        }
                        else if (method.equals("select"))
                        {
                            JsonObject row = new JsonObject(); ArrayReference q = (ArrayReference) values.get(0);
                            row.addProperty("qId", q.uniqueID()); row.add("bits", bits(q));
                            row.addProperty("threshold", Double.toHexString(((DoubleValue) values.get(1)).value()));
                            // Keep every event. Route identity comes from the actual producer
                            // and caller, never from an assumed order or threshold value.
                            StackFrame caller = hit.thread().frame(1);
                            Location location = caller.location();
                            String callerMethod = location.method().name(), route = "UNKNOWN";
                            row.addProperty("callerClass", location.declaringType().name());
                            row.addProperty("callerMethod", callerMethod);
                            row.addProperty("callerLine", location.lineNumber());
                            row.addProperty("callerCodeIndex", location.codeIndex());
                            if (location.declaringType().name().equals(PACKAGE + "BoundaryDetector"))
                            {
                                if (callerMethod.equals("detect"))
                                {
                                    ArrayReference original = (ArrayReference) caller.getValue(caller.visibleVariableByName("novelty"));
                                    if (q.uniqueID() == original.uniqueID()) route = "ORIGINAL_Q";
                                    else for (JsonElement producer : current.getAsJsonArray("persistentReturns"))
                                        if (producer.getAsJsonObject().get("qId").getAsLong() == q.uniqueID())
                                            route = "PERSISTENT_CONTRAST";
                                }
                                else if (callerMethod.equals("refineLoudnessTransitions"))
                                {
                                    ArrayReference scores = (ArrayReference) caller.getValue(caller.visibleVariableByName("scores"));
                                    row.addProperty("callerScoresId", scores.uniqueID());
                                    if (q.uniqueID() == scores.uniqueID()) route = "LOUDNESS_RAMP";
                                }
                            }
                            row.addProperty("route", route);
                            current.getAsJsonArray("select").add(row);
                        }
                        else if (method.equals("rawNovelty"))
                            current.getAsJsonArray("radii").add(((IntegerValue) values.get(2)).value());
                        else if (method.equals("compare"))
                        {
                            comparison = new JsonObject(); current.getAsJsonArray("comparisons").add(comparison);
                            comparison.addProperty("route", "V1");
                            comparison.addProperty("sourceId", id(values.get(2)));
                            comparison.addProperty("featuresId", id(values.get(3)));
                            comparison.add("a", range((ObjectReference) field((ObjectReference) values.get(0), "range")));
                            comparison.add("b", range((ObjectReference) field((ObjectReference) values.get(1), "range")));
                            comparison.add("kernels", new JsonArray()); comparison.add("rebins", new JsonArray());
                        }
                        else if (method.equals("structuralBins") && comparison != null)
                        {
                            JsonObject row = new JsonObject(); row.addProperty("featuresId", id(values.get(0)));
                            row.add("range", range((ObjectReference) values.get(1)));
                            row.addProperty("frames", ((LongValue) values.get(2)).value());
                            comparison.getAsJsonArray("rebins").add(row);
                        }
                        else if (method.equals("kernel") && comparison != null)
                        {
                            if (tag.equals("entry"))
                            {
                                kernel = new JsonObject(); comparison.getAsJsonArray("kernels").add(kernel);
                                kernel.addProperty("firstBinsId", id(values.get(0))); kernel.addProperty("secondBinsId", id(values.get(1)));
                                kernel.addProperty("firstDuration", ((LongValue) values.get(4)).value());
                                kernel.addProperty("secondDuration", ((LongValue) values.get(5)).value());
                            }
                            else
                            {
                                kernel.add("scores", bits((ArrayReference) values.get(7)));
                                ArrayReference details = (ArrayReference) values.get(8);
                                kernel.addProperty("rotation", ((IntegerValue) details.getValue(0)).value());
                                kernel.addProperty("validCount", ((IntegerValue) details.getValue(1)).value());
                            }
                        }
                        else if (method.equals("loudnessStats"))
                        {
                            JsonObject row = new JsonObject();
                            for (String name : List.of("available", "material", "consistent"))
                                row.addProperty(name, ((IntegerValue) frame.getValue(frame.visibleVariableByName(name))).value());
                            current.getAsJsonArray("trends").add(row);
                        }
                    }
                }
                set.resume();
            }
            if (!disconnect) throw new IllegalStateException("Observer timeout before VM disconnect");
            if (!child.waitFor(3, TimeUnit.SECONDS)) throw new IllegalStateException("Child did not exit");
            result.addProperty("exitCode", child.exitValue());
            result.addProperty("stdout", out.get(3, TimeUnit.SECONDS));
            result.addProperty("stderr", err.get(3, TimeUnit.SECONDS));
        }
        finally
        {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); }
            readers.shutdownNow();
            result.addProperty("sawVmDeath", death); result.addProperty("drainedToVmDisconnect", disconnect);
        }
        result.addProperty("javaRuntime", System.getProperty("java.runtime.version"));
        result.addProperty("pid", child.pid());
        JsonObject classHashes = new JsonObject();
        for (String owner : List.of("BoundaryDetector", "SegmentDescriptorBuilder", "SegmentComparator", "ComparisonComparator", "ComparisonFeatureExtractor", "LevelerAnalysisEngine"))
        {
            Path file = Path.of("target/classes/com/quickmaster/processing/dynamics/leveler", owner + ".class");
            classHashes.addProperty(owner, java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        result.add("observedProductClassHashes", classHashes);
        return result;
    }

    private static void install(VirtualMachine vm, ReferenceType type, String mode) throws Exception
    {
        if (type.name().equals(PACKAGE + "ComparisonFeatureExtractor"))
        {
            Method extract = onlyMethod(type, "extract");
            breakpoint(vm, extract.location(), "v2-extract-entry");
            exactLine(vm, extract, "ComparisonFeatureExtractor", "            return cancelled(token) ? null : result;", "v2-extract-return");
            return;
        }
        if (type.name().equals(PACKAGE + "ComparisonComparator"))
        {
            Method compare = onlyMethod(type, "compare");
            breakpoint(vm, compare.location(), "v2-compare-entry");
            breakpoint(vm, onlyMethod(type, "kernel").location(), "v2-kernel-entry");
            exactLine(vm, compare, "ComparisonComparator", "            if (central == null) return null;", "v2-central");
            exactLine(vm, compare, "ComparisonComparator", "                if (variant == null) return null;", "v2-variant");
            return;
        }
        for (Method method : type.methods())
        {
            String name = method.name();
            if (type.name().equals(MusicalContextJdiChild.class.getName())
                    && !name.equals("mark") && !name.equals("finish")) continue;
            if (List.of("mark", "finish", "analyzeShadow", "originalNovelty", "select", "compare", "kernel", "structuralBins").contains(name)
                    || name.equals("rawNovelty") && mode.equals("radii"))
                breakpoint(vm, method.location(), "entry");
            if (name.equals("originalNovelty") || name.equals("persistentContrast") || name.equals("kernel"))
            {
                List<Location> locations = method.allLineLocations();
                breakpoint(vm, locations.get(locations.size() - 1), "return");
            }
            if (name.equals("loudnessStats"))
            {
                List<String> source = Files.readAllLines(Path.of("src/main/java/com/quickmaster/processing/dynamics/leveler/SegmentDescriptorBuilder.java"));
                int line = source.indexOf("            if (available == 0) return null;") + 1;
                if (line == 0) throw new IllegalStateException("Missing trend observation point");
                List<Location> locations = method.locationsOfLine(line);
                if (locations.isEmpty()) throw new IllegalStateException("Missing debug location");
                breakpoint(vm, locations.get(0), "trend");
            }
        }
    }

    private static void breakpoint(VirtualMachine vm, Location location, String tag)
    {
        BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(location);
        request.putProperty("tag", tag); request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); request.enable();
    }

    private static Method onlyMethod(ReferenceType type, String name)
    {
        List<Method> methods = type.methodsByName(name);
        if (methods.size() != 1) throw new IllegalStateException("Ambiguous observed method " + type.name() + "." + name);
        return methods.get(0);
    }
    private static void exactLine(VirtualMachine vm, Method method, String owner, String text, String tag) throws Exception
    {
        List<String> source = Files.readAllLines(Path.of("src/main/java/com/quickmaster/processing/dynamics/leveler", owner + ".java"));
        int first = source.indexOf(text);
        if (first < 0 || first != source.lastIndexOf(text)) throw new IllegalStateException("Missing/ambiguous V2 observation source " + tag);
        List<Location> locations = method.locationsOfLine(first + 1);
        if (locations.size() != 1) throw new IllegalStateException("Missing/ambiguous V2 debug location " + tag);
        breakpoint(vm, locations.get(0), tag);
    }
    private static Value local(StackFrame frame, String name) throws AbsentInformationException
    {
        LocalVariable variable = frame.visibleVariableByName(name);
        if (variable == null) throw new IllegalStateException("Missing observed local " + name);
        Value value = frame.getValue(variable);
        if (value == null) throw new IllegalStateException("Null observed local " + name);
        return value;
    }
    private static String string(Value value) { return ((StringReference) value).value(); }
    private static JsonObject location(Location location)
    {
        JsonObject row = new JsonObject(); row.addProperty("class", location.declaringType().name());
        row.addProperty("method", location.method().name()); row.addProperty("line", location.lineNumber());
        row.addProperty("codeIndex", location.codeIndex()); return row;
    }
    private static JsonObject format(ObjectReference format)
    {
        JsonObject row = new JsonObject(); row.addProperty("sourceId", format.uniqueID());
        row.addProperty("rate", ((IntegerValue) field(format, "sampleRateHz")).value());
        row.addProperty("channels", ((IntegerValue) field(format, "channels")).value());
        row.addProperty("frames", ((LongValue) field(format, "frames")).value()); return row;
    }
    private static JsonObject score(ObjectReference score)
    {
        if (score == null) throw new IllegalStateException("QM_CONTEXT_V2_NULL_SCORE");
        JsonObject row = new JsonObject(); row.addProperty("scoreId", score.uniqueID()); JsonArray values = new JsonArray();
        for (String name : List.of("h", "t", "a", "c")) values.add(Long.toHexString(Double.doubleToRawLongBits(((DoubleValue) field(score, name)).value())));
        row.add("scores", values); row.addProperty("rotation", ((IntegerValue) field(score, "chromaRotation")).value());
        row.addProperty("validCount", ((IntegerValue) field(score, "validBins")).value());
        row.addProperty("reason", string(field((ObjectReference) field(score, "rejectionReason"), "name"))); return row;
    }
    private static String packed(ArrayReference values, boolean shorts)
    {
        byte[] bytes = new byte[Math.multiplyExact(values.length(), shorts ? 2 : 1)]; int at = 0;
        for (Value value : values.getValues())
        {
            if (shorts) { int number = ((ShortValue) value).value(); bytes[at++] = (byte) (number >>> 8); bytes[at++] = (byte) number; }
            else bytes[at++] = ((ByteValue) value).value();
        }
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
    private static JsonObject timeline(ObjectReference timeline)
    {
        if (timeline == null) throw new IllegalStateException("QM_CONTEXT_V2_NULL_TIMELINE");
        JsonObject row = new JsonObject(); row.addProperty("timelineId", timeline.uniqueID());
        row.add("format", format((ObjectReference) field(timeline, "format")));
        for (String name : List.of("shortValues", "longValues", "shortFlags", "longFlags"))
        {
            ArrayReference array = (ArrayReference) field(timeline, name);
            row.addProperty(name + "Id", array.uniqueID()); row.addProperty(name + "Length", array.length());
            row.addProperty(name + "Base64", packed(array, name.endsWith("Values")));
        }
        return row;
    }
    private static JsonObject retained(StackFrame caller) throws Exception
    {
        ObjectReference snapshot = (ObjectReference) local(caller, "snapshot");
        ObjectReference cache = (ObjectReference) field(snapshot, "cache");
        ObjectReference timeline = (ObjectReference) field(cache, "comparison");
        JsonObject row = new JsonObject(); row.addProperty("cacheId", cache.uniqueID());
        row.addProperty("algorithm", string(field(cache, "algorithmId"))); row.addProperty("profile", string(field(cache, "profileId")));
        row.add("format", format((ObjectReference) field(cache, "format")));
        row.addProperty("timelineId", id(timeline)); row.addProperty("timelineSourceId", id(field(timeline, "format")));
        for (String name : List.of("shortValues", "longValues", "shortFlags", "longFlags")) row.addProperty(name + "Id", id(field(timeline, name)));
        ArrayReference descriptors = (ArrayReference) field((ObjectReference) field(cache, "descriptors"), "elements");
        JsonArray ranges = new JsonArray();
        for (Value value : descriptors.getValues())
        {
            ObjectReference descriptor = (ObjectReference) value, range = (ObjectReference) field(descriptor, "range");
            JsonObject r = new JsonObject(); r.addProperty("descriptorId", descriptor.uniqueID()); r.addProperty("rangeId", range.uniqueID());
            r.add("range", range(range)); ranges.add(r);
        }
        row.add("descriptors", ranges);
        ObjectReference matrix = (ObjectReference) field(cache, "similarity");
        ArrayReference scores = (ArrayReference) field((ObjectReference) field(matrix, "scores"), "elements");
        JsonArray observedScores = new JsonArray();
        for (Value value : scores.getValues()) observedScores.add(score((ObjectReference) value));
        row.add("scores", observedScores); return row;
    }

    private static long id(Value value) { return ((ObjectReference) value).uniqueID(); }
    private static Value field(ObjectReference object, String name) {
        List<Field> fields = object.referenceType().allFields().stream().filter(f -> f.name().equals(name)).toList();
        if (fields.size() != 1) throw new IllegalStateException("Missing/ambiguous observed field " + object.referenceType().name() + "." + name);
        return object.getValue(fields.get(0));
    }
    private static JsonArray range(ObjectReference object)
    {
        JsonArray result = new JsonArray();
        result.add(((LongValue) field(object, "startInclusive")).value());
        result.add(((LongValue) field(object, "endExclusive")).value());
        return result;
    }
    private static JsonArray bits(ArrayReference array)
    {
        JsonArray result = new JsonArray();
        for (Value value : array.getValues()) result.add(Long.toHexString(Double.doubleToRawLongBits(((DoubleValue) value).value())));
        return result;
    }
}
