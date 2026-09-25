package com.quickmaster.processing.dynamics.leveler;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;

class MusicalContextObservationTest
{
    @Test
    void realDensePcmUsesBothProducersAndAllOrderedRetriesBeforeEmptyFallback() throws Exception {
        JsonArray cases=observe("dense-pcm");assertEquals(4,cases.size());
        for(JsonElement element:cases) {
            JsonObject row=element.getAsJsonObject();assertDenseRetries(row);
            JsonObject missing=row.deepCopy();missing.getAsJsonArray("select").remove(3);
            assertThrows(AssertionError.class,()->assertDenseRetries(missing));
            JsonObject early=row.deepCopy();early.getAsJsonArray("select").remove(1);
            assertThrows(AssertionError.class,()->assertDenseRetries(early));
            JsonObject prefix=row.deepCopy();prefix.getAsJsonObject("child").addProperty("engineNull",false);
            assertThrows(AssertionError.class,()->assertDenseRetries(prefix));
        }
    }
    private static void assertDenseRetries(JsonObject row) {
        assertTrue(row.getAsJsonObject("child").get("engineNull").getAsBoolean(),"No prefix analysis may be returned");
        JsonArray selections=originalSelections(row,3,0);
        JsonObject original=row.getAsJsonArray("qReturns").get(0).getAsJsonObject();
        assertEquals(1,row.getAsJsonArray("qReturns").size());
        double[] q=new double[original.getAsJsonArray("bits").size()];
        for(int i=0;i<q.length;i++)q[i]=value(original.getAsJsonArray("bits").get(i));
        double[] sorted=q.clone();Arrays.sort(sorted);double median=rank(sorted,.5);
        double[] dev=Arrays.stream(q).map(v->Math.abs(v-median)).sorted().toArray();double mad=rank(dev,.5);
        double[] thresholds={mad>0?median+3*mad:rank(sorted,.95),rank(sorted,.975),rank(sorted,.99)};
        for(int i=0;i<3;i++) {
            JsonObject call=selections.get(i).getAsJsonObject();
            assertEquals(original.get("bits"),call.get("bits"));assertEquals(original.get("qId"),call.get("qId"));
            assertEquals(Double.toHexString(thresholds[i]),call.get("threshold").getAsString(),"Exact ordered fresh-Q retry threshold");
        }
        JsonArray raw=row.getAsJsonArray("persistentReturns").get(0).getAsJsonObject().getAsJsonArray("bits");
        double[] scores=new double[raw.size()];for(int i=0;i<scores.length;i++)scores[i]=value(raw.get(i));
        List<Integer> expected=new ArrayList<>();for(int i=1;i<90;i++)expected.add(8*i);
        assertEquals(expected,MusicalPcmMatrixTest.selectedCandidates(scores,.02),"All89 actual lasting-content boundaries are observed before cap");
    }
    private static double rank(double[] sorted,double p){return sorted[Math.max(0,(int)Math.ceil(sorted.length*p)-1)];}
    @Test
    void originalQFlowsUnscaledThroughBothConsumersAndEveryRetry() throws Exception
    {
        JsonArray cases = observe("q");
        assertEquals(4, cases.size());
        for (int index = 0; index < cases.size(); index++)
        {
            JsonObject row = cases.get(index).getAsJsonObject();
            JsonArray returns = row.getAsJsonArray("qReturns");
            int expectedSelections = index < 2 ? 1 : index == 2 ? 2 : 3;
            JsonArray selections = originalSelections(row, expectedSelections, 0);
            assertEquals(index < 2 ? 2 : 1, returns.size());
            assertEquals(expectedSelections, selections.size());
            JsonObject original = returns.get(0).getAsJsonObject();
            for (JsonElement element : returns) assertEquals(original.get("bits"), element.getAsJsonObject().get("bits"));
            for (JsonElement element : selections)
            {
                assertEquals(original.get("bits"), element.getAsJsonObject().get("bits"));
                assertEquals(original.get("qId"), element.getAsJsonObject().get("qId"));
            }
            if (index < 2)
            {
                String golden = MusicalContextJdiChild.bits(index == 0 ? 5e11 : 3e11);
                assertEquals(golden, original.getAsJsonArray("bits").get(511).getAsString());
                assertEquals(golden, row.getAsJsonObject("child").get("context").getAsString());
            }
            else assertEquals(index == 2 ? 51 : 0, row.getAsJsonObject("child").get("regions").getAsInt());
            // Counterfactual observer records must not disappear through route filtering.
            JsonObject unknown = row.deepCopy();
            unknown.getAsJsonArray("select").get(0).getAsJsonObject().addProperty("route", "UNKNOWN");
            assertThrows(AssertionError.class, () -> originalSelections(unknown, expectedSelections, 0));
            JsonObject missing = row.deepCopy();
            missing.getAsJsonArray("select").remove(missing.getAsJsonArray("select").size() - 1);
            assertThrows(AssertionError.class, () -> originalSelections(missing, expectedSelections, 0));
        }
    }

    @Test
    void nominalNoveltyRadiiStayFourEightSixteenAtEveryDeclaredRate() throws Exception
    {
        JsonArray cases = observe("radii"); assertEquals(6, cases.size());
        for (JsonElement element : cases) assertEquals(JsonParser.parseString("[4,8,16]"), element.getAsJsonObject().get("radii"));
    }

    @Test
    void allEightIndependentSlotsUseRealExteriorAndExactClippedTailRanges() throws Exception
    {
        JsonArray cases = observe("comparisons"); assertEquals(6, cases.size());
        for (int i = 0; i < cases.size(); i++)
        {
            JsonObject row = cases.get(i).getAsJsonObject(), child = row.getAsJsonObject("child");
            assertEquals(1, row.getAsJsonArray("comparisons").size());
            JsonObject comparison = row.getAsJsonArray("comparisons").get(0).getAsJsonObject();
            assertSlots(comparison, child.get("frames").getAsLong(), child.get("hop").getAsLong(), i == 1 ? 1 : 8);
            assertEquals(i == 1 ? "UNSTABLE_BOUNDARY" : "NONE", child.get("reason").getAsString());
            double firstVariant = value(comparison.getAsJsonArray("kernels").get(1).getAsJsonObject().getAsJsonArray("scores").get(2));
            double expected = i == 1 ? 283d / 320d : i == 4 ? .9999050875675191d : i == 5 ? .9994373846594699d : 1d;
            assertEquals(expected, firstVariant, 1e-15);
            if (i != 1) assertEquals(comparison.getAsJsonArray("kernels").get(0).getAsJsonObject().get("scores"), child.get("scores"));
        }
    }

    @Test
    void pcmEnginePassesTheSameReferencesAndQTwiceWithThreeRealComparisonsPerRun() throws Exception
    {
        JsonArray cases = observe("engine"); assertEquals(3, cases.size());
        String expected = null, expectedV2 = null;
        var references = new ComparisonV2Oracle.ReferenceMemo();
        for (JsonElement element : cases)
        {
            JsonObject row = element.getAsJsonObject(), child = row.getAsJsonObject("child");
            String summary = child.get("summary").getAsString();
            if (expected == null) expected = summary; else assertEquals(expected, summary);
            assertEquals(2, row.getAsJsonArray("noveltyEntries").size());
            assertEquals(2, row.getAsJsonArray("qReturns").size());
            JsonObject original = row.getAsJsonArray("qReturns").get(0).getAsJsonObject();
            assertEquals(original.get("bits"), row.getAsJsonArray("qReturns").get(1).getAsJsonObject().get("bits"));
            JsonArray selections = originalSelections(row, 1, 1);
            assertEquals(original.get("bits"), selections.get(0).getAsJsonObject().get("bits"));
            long frames = child.get("frames").getAsLong(), hop = child.get("hop").getAsLong();
            for (JsonElement descriptor : child.getAsJsonArray("descriptors"))
            {
                JsonObject d = descriptor.getAsJsonObject();
                assertEquals(qAt(original, d.get("start").getAsLong(), frames, hop), d.get("left").getAsString());
                assertEquals(qAt(original, d.get("end").getAsLong(), frames, hop), d.get("right").getAsString());
            }
            // The child has drained completely before any full-grid reference arithmetic.
            String actualV2 = assertV2Engine(row, references);
            if (expectedV2 == null) expectedV2 = actualV2; else assertEquals(expectedV2, actualV2, "All actual V2 slots/reasons/rotation/coverage/cells repeat bit-exactly");
            assertV2RecordCounterfactuals(row, references);
        }
    }

    private static String assertV2Engine(JsonObject row, ComparisonV2Oracle.ReferenceMemo references)
    {
        JsonObject child = row.getAsJsonObject("child"), retained = row.getAsJsonObject("retained");
        assertNotNull(retained, "QM_CONTEXT_V2_RETAINED_IDENTITY");
        assertEquals(ComparisonV2Oracle.V2_ALGORITHM, child.get("algorithm").getAsString(), "QM_CONTEXT_V2_VERSION");
        assertEquals(ComparisonV2Oracle.V2_PROFILE, child.get("profile").getAsString(), "QM_CONTEXT_V2_VERSION");
        assertEquals(child.get("algorithm"), retained.get("algorithm"), "QM_CONTEXT_V2_VERSION");
        assertEquals(child.get("profile"), retained.get("profile"), "QM_CONTEXT_V2_VERSION");
        assertEquals(2, ComparisonV2Oracle.version(retained.get("algorithm").getAsString(), retained.get("profile").getAsString()));
        assertTrue(child.get("comparisonPresent").getAsBoolean(), "QM_CONTEXT_V2_RETAINED_IDENTITY");
        JsonArray extractions = row.getAsJsonArray("extractions");
        assertEquals(1, extractions.size(), "QM_CONTEXT_V2_EXTRACTION_COUNT");
        JsonObject extraction = extractions.get(0).getAsJsonObject(), timeline = extraction.getAsJsonObject("timeline");
        assertNotNull(timeline, "QM_CONTEXT_V2_MISSING_EXTRACTION_RETURN");
        assertEquals(row.get("enginePcmId"), extraction.get("pcmId"), "QM_CONTEXT_V2_PCM_IDENTITY");
        assertEquals(row.get("engineSourceId"), extraction.get("sourceId"), "QM_CONTEXT_V2_SOURCE_IDENTITY");
        assertEquals(row.get("engineFormat"), timeline.get("format"), "QM_CONTEXT_V2_SOURCE_IDENTITY");
        assertEquals(row.get("engineFormat"), retained.get("format"), "QM_CONTEXT_V2_RETAINED_IDENTITY");
        assertEquals(row.get("engineSourceId"), retained.get("timelineSourceId"), "QM_CONTEXT_V2_RETAINED_IDENTITY");
        assertEquals(timeline.get("timelineId"), retained.get("timelineId"), "QM_CONTEXT_V2_RETAINED_IDENTITY");
        for (String name : List.of("shortValues", "longValues", "shortFlags", "longFlags"))
            assertEquals(timeline.get(name + "Id"), retained.get(name + "Id"), "QM_CONTEXT_V2_RETAINED_IDENTITY");
        ComparisonV2Oracle.Source source = observedSource(timeline);
        assertEquals(child.get("frames").getAsLong(), source.format().frames(), "QM_CONTEXT_V2_SOURCE_IDENTITY");
        assertEquals(child.get("rate").getAsInt(), source.format().sampleRateHz(), "QM_CONTEXT_V2_SOURCE_IDENTITY");
        assertEquals(child.get("channels").getAsInt(), source.format().channels(), "QM_CONTEXT_V2_SOURCE_IDENTITY");
        StringBuilder deterministic = new StringBuilder(child.get("algorithm").getAsString()).append('/').append(child.get("profile").getAsString())
                .append('/').append(ComparisonV2Oracle.sourceIdentity(source));
        JsonArray comparisons = row.getAsJsonArray("comparisons"), pairs = child.getAsJsonArray("pairs");
        assertEquals(3, comparisons.size(), "QM_CONTEXT_V2_PAIR_COUNT"); assertEquals(3, pairs.size(), "QM_CONTEXT_V2_PAIR_COUNT");
        int[][] expectedPairs = {{2,3},{2,4},{3,4}};
        for (int index = 0; index < expectedPairs.length; index++)
        {
            JsonObject comparison = comparisons.get(index).getAsJsonObject(), pair = pairs.get(index).getAsJsonObject();
            int a = expectedPairs[index][0], b = expectedPairs[index][1];
            assertEquals("V2", comparison.get("route").getAsString(), "QM_CONTEXT_V2_VERSION");
            assertEquals(ComparisonV2Oracle.V2_PROFILE, comparison.get("profile").getAsString(), "QM_CONTEXT_V2_VERSION");
            assertEquals(a, comparison.get("first").getAsInt(), "QM_CONTEXT_V2_PAIR_INDEX");
            assertEquals(b, comparison.get("second").getAsInt(), "QM_CONTEXT_V2_PAIR_INDEX");
            assertEquals(a, pair.get("first").getAsInt(), "QM_CONTEXT_V2_PAIR_INDEX");
            assertEquals(b, pair.get("second").getAsInt(), "QM_CONTEXT_V2_PAIR_INDEX");
            int descriptorCount = retained.getAsJsonArray("descriptors").size();
            int scoreIndex = a * (2 * descriptorCount - a - 1) / 2 + b - a - 1;
            assertEquals(scoreIndex, comparison.get("scoreIndex").getAsInt(), "QM_CONTEXT_V2_PAIR_INDEX");
            assertEquals(row.get("engineSourceId"), comparison.get("sourceId"), "QM_CONTEXT_V2_SOURCE_IDENTITY");
            assertEquals(timeline.get("timelineId"), comparison.get("timelineId"), "QM_CONTEXT_V2_TIMELINE_IDENTITY");
            JsonObject ar = retained.getAsJsonArray("descriptors").get(a).getAsJsonObject(), br = retained.getAsJsonArray("descriptors").get(b).getAsJsonObject();
            assertEquals(ar.get("rangeId"), comparison.get("firstRangeId"), "QM_CONTEXT_V2_RANGE_IDENTITY");
            assertEquals(br.get("rangeId"), comparison.get("secondRangeId"), "QM_CONTEXT_V2_RANGE_IDENTITY");
            assertEquals(ar.get("range"), comparison.get("a"), "QM_CONTEXT_V2_ENDPOINT_RANGE");
            assertEquals(br.get("range"), comparison.get("b"), "QM_CONTEXT_V2_ENDPOINT_RANGE");
            var expected = references.compare(source, observedRange(comparison.getAsJsonArray("a")), observedRange(comparison.getAsJsonArray("b")));
            assertEquals(8, expected.variants().size(), "QM_CONTEXT_V2_ORACLE_ENDPOINTS");
            assertEquals(expected.reason().name(), pair.get("reason").getAsString(), "QM_CONTEXT_V2_PUBLIC_REASON");
            JsonArray kernels = comparison.getAsJsonArray("kernels");
            assertEquals(9, kernels.size(), "QM_CONTEXT_V2_KERNEL_COUNT");
            Set<Long> changedRanges = new HashSet<>();
            deterministic.append(';').append(a).append(':').append(b).append(':').append(pair.get("reason"));
            for (int k = 0; k < 9; k++)
            {
                int slot = k - 1;
                JsonObject kernel = kernels.get(k).getAsJsonObject();
                assertEquals(slot, kernel.get("slot").getAsInt(), "QM_CONTEXT_V2_SLOT_ORDER");
                assertEquals(comparison.get("sourceId"), kernel.get("sourceId"), "QM_CONTEXT_V2_SOURCE_IDENTITY");
                assertEquals(comparison.get("timelineId"), kernel.get("timelineId"), "QM_CONTEXT_V2_TIMELINE_IDENTITY");
                var wantedA = slot < 0 ? observedRange(comparison.getAsJsonArray("a")) : expected.variants().get(slot).a();
                var wantedB = slot < 0 ? observedRange(comparison.getAsJsonArray("b")) : expected.variants().get(slot).b();
                assertEquals(wantedA, observedRange(kernel.getAsJsonArray("a")), "QM_CONTEXT_V2_ENDPOINT_RANGE");
                assertEquals(wantedB, observedRange(kernel.getAsJsonArray("b")), "QM_CONTEXT_V2_ENDPOINT_RANGE");
                if (slot < 0)
                {
                    assertEquals(comparison.get("firstRangeId"), kernel.get("firstRangeId"), "QM_CONTEXT_V2_RANGE_IDENTITY");
                    assertEquals(comparison.get("secondRangeId"), kernel.get("secondRangeId"), "QM_CONTEXT_V2_RANGE_IDENTITY");
                }
                else
                {
                    String changed = slot < 4 ? "firstRangeId" : "secondRangeId", unchanged = slot < 4 ? "secondRangeId" : "firstRangeId";
                    assertEquals(comparison.get(unchanged), kernel.get(unchanged), "QM_CONTEXT_V2_RANGE_IDENTITY");
                    assertNotEquals(comparison.get(changed), kernel.get(changed), "QM_CONTEXT_V2_RANGE_IDENTITY");
                    assertTrue(changedRanges.add(kernel.get(changed).getAsLong()), "QM_CONTEXT_V2_RANGE_IDENTITY");
                }
                JsonObject actual = kernel.getAsJsonObject("actualScore");
                assertNotNull(actual, "QM_CONTEXT_V2_MISSING_SCORE");
                assertTrue(actual.get("scoreId").getAsLong() > 0, "QM_CONTEXT_V2_SCORE_IDENTITY");
                assertCapturedScore(actual, slot < 0 ? expected.central() : expected.variants().get(slot).score());
                deterministic.append('/').append(slot).append(':').append(wantedA).append(':').append(wantedB).append(':').append(scoreFields(actual));
            }
            JsonObject central = kernels.get(0).getAsJsonObject().getAsJsonObject("actualScore");
            JsonObject published = retained.getAsJsonArray("scores").get(scoreIndex).getAsJsonObject();
            assertEquals(central, published, "QM_CONTEXT_V2_PUBLIC_SCORE_IDENTITY");
            assertEquals(scoreFields(central), scoreFields(pair), "QM_CONTEXT_V2_PUBLIC_SCORE");
        }
        return deterministic.toString();
    }

    private static void assertCapturedScore(JsonObject actual, ComparisonV2Oracle.Score expected)
    {
        assertEquals(expected.reason().name(), actual.get("reason").getAsString(), "QM_CONTEXT_V2_SCORE_REFERENCE");
        assertEquals(expected.rotation(), actual.get("rotation").getAsInt(), "QM_CONTEXT_V2_SCORE_REFERENCE");
        assertEquals(expected.count(), actual.get("validCount").getAsInt(), "QM_CONTEXT_V2_SCORE_REFERENCE");
        double[] want = {expected.h(), expected.t(), expected.a(), expected.c()};
        assertEquals(4, actual.getAsJsonArray("scores").size(), "QM_CONTEXT_V2_SCORE_REFERENCE");
        for (int i = 0; i < 4; i++) assertEquals(want[i], value(actual.getAsJsonArray("scores").get(i)), 1e-12, "QM_CONTEXT_V2_SCORE_REFERENCE HTAC " + i);
        if (expected.reason() == com.quickmaster.processing.dynamics.leveler.model.SimilarityRejectionReason.NONE)
            ComparisonV2Oracle.requireHarmonicIdentity(value(actual.getAsJsonArray("scores").get(0)), expected, "actual JDI returned score");
    }
    private static JsonObject scoreFields(JsonObject row)
    {
        JsonObject selected = new JsonObject();
        for (String name : List.of("scores", "reason", "rotation", "validCount")) selected.add(name, row.get(name));
        return selected;
    }
    private static ComparisonV2Oracle.Range observedRange(JsonArray range)
    { return new ComparisonV2Oracle.Range(range.get(0).getAsLong(), range.get(1).getAsLong()); }
    private static ComparisonV2Oracle.Source observedSource(JsonObject timeline)
    {
        JsonObject f = timeline.getAsJsonObject("format");
        var format = new com.quickmaster.processing.dynamics.leveler.model.AudioFormat(f.get("rate").getAsInt(), f.get("channels").getAsInt(), f.get("frames").getAsLong());
        byte[] sv = Base64.getDecoder().decode(timeline.get("shortValuesBase64").getAsString()), lv = Base64.getDecoder().decode(timeline.get("longValuesBase64").getAsString());
        byte[] sf = Base64.getDecoder().decode(timeline.get("shortFlagsBase64").getAsString()), lf = Base64.getDecoder().decode(timeline.get("longFlagsBase64").getAsString());
        assertEquals(timeline.get("shortValuesLength").getAsInt() * 2, sv.length, "QM_CONTEXT_V2_CELL_EXTENT");
        assertEquals(timeline.get("longValuesLength").getAsInt() * 2, lv.length, "QM_CONTEXT_V2_CELL_EXTENT");
        assertEquals(timeline.get("shortFlagsLength").getAsInt(), sf.length, "QM_CONTEXT_V2_CELL_EXTENT");
        assertEquals(timeline.get("longFlagsLength").getAsInt(), lf.length, "QM_CONTEXT_V2_CELL_EXTENT");
        ComparisonV2Oracle.Source result = new ComparisonV2Oracle.Source() {
            public com.quickmaster.processing.dynamics.leveler.model.AudioFormat format() { return format; }
            public int size(boolean longer) { return (longer ? lf : sf).length; }
            public int flags(boolean longer, int row) { return (longer ? lf : sf)[row] & 255; }
            public int packed(boolean longer, int row, int column) {
                byte[] bytes = longer ? lv : sv; int at = 2 * (row * (longer ? 36 : 52) + column);
                return (short) (((bytes[at] & 255) << 8) | (bytes[at + 1] & 255));
            }
        };
        ComparisonV2Oracle.validate(result); return result;
    }

    /** Counterfactual records, not altered product execution or replacement expected scores. */
    private static void assertV2RecordCounterfactuals(JsonObject row, ComparisonV2Oracle.ReferenceMemo references)
    {
        record Control(String cause, java.util.function.Consumer<JsonObject> change) { }
        List<Control> controls = List.of(
                new Control("KERNEL_COUNT", r -> firstComparison(r).getAsJsonArray("kernels").remove(8)),
                new Control("MISSING_SCORE", r -> firstKernel(r).remove("actualScore")),
                new Control("SCORE_REFERENCE", r -> {
                    JsonArray scores = firstKernel(r).getAsJsonObject("actualScore").getAsJsonArray("scores");
                    scores.set(0, new JsonPrimitive(MusicalContextJdiChild.bits(value(scores.get(0)) == 0 ? .5 : 0)));
                }),
                new Control("SCORE_REFERENCE", r -> firstKernel(r).getAsJsonObject("actualScore").addProperty("reason", "AMBIGUOUS")),
                new Control("SCORE_REFERENCE", r -> {
                    JsonObject score = firstKernel(r).getAsJsonObject("actualScore");
                    score.addProperty("rotation", (score.get("rotation").getAsInt() + 1) % 12);
                }),
                new Control("SCORE_REFERENCE", r -> firstKernel(r).getAsJsonObject("actualScore").addProperty("validCount", 0)),
                new Control("TIMELINE_IDENTITY", r -> firstComparison(r).addProperty("timelineId", -1)),
                new Control("SOURCE_IDENTITY", r -> firstKernel(r).addProperty("sourceId", -1)),
                new Control("PCM_IDENTITY", r -> r.getAsJsonArray("extractions").get(0).getAsJsonObject().addProperty("pcmId", -1)),
                new Control("ENDPOINT_RANGE", r -> firstKernel(r).getAsJsonArray("a").set(0, new JsonPrimitive(-1))),
                new Control("SLOT_ORDER", r -> firstKernel(r).addProperty("slot", 8)),
                new Control("RANGE_IDENTITY", r -> firstComparison(r).getAsJsonArray("kernels").get(1).getAsJsonObject().add("firstRangeId", firstComparison(r).get("firstRangeId"))),
                new Control("RETAINED_IDENTITY", r -> r.getAsJsonObject("retained").addProperty("timelineId", -1)),
                new Control("RETAINED_IDENTITY", r -> r.getAsJsonObject("retained").getAsJsonObject("format").addProperty("sourceId", -1)),
                new Control("PAIR_INDEX", r -> firstComparison(r).addProperty("first", 0)),
                new Control("VERSION", r -> firstComparison(r).addProperty("route", "V1")),
                new Control("VERSION", r -> r.getAsJsonObject("retained").addProperty("profile", ComparisonV2Oracle.V1_PROFILE)));
        for (Control control : controls)
        {
            JsonObject changed = row.deepCopy(); control.change.accept(changed);
            AssertionError error = assertThrows(AssertionError.class, () -> assertV2Engine(changed, references));
            assertTrue(error.getMessage().contains("QM_CONTEXT_V2_" + control.cause), "Owning record falsifier: " + control.cause + " / " + error);
        }
    }
    private static JsonObject firstComparison(JsonObject row) { return row.getAsJsonArray("comparisons").get(0).getAsJsonObject(); }
    private static JsonObject firstKernel(JsonObject row) { return firstComparison(row).getAsJsonArray("kernels").get(0).getAsJsonObject(); }

    @Test
    void lagFiveRequiresAllSixOriginalPositionsAndEveryPhase() throws Exception
    {
        JsonArray cases = observe("trends"); assertEquals(4, cases.size());
        int[] available = { 15, 29, 35, 35 }, material = { 15, 29, 2, 35 }, consistent = { 15, 29, 0, 31 };
        for (int i = 0; i < cases.size(); i++)
        {
            JsonArray trends = cases.get(i).getAsJsonObject().getAsJsonArray("trends"); assertEquals(1, trends.size());
            JsonObject trend = trends.get(0).getAsJsonObject();
            assertEquals(available[i], trend.get("available").getAsInt());
            assertEquals(material[i], trend.get("material").getAsInt());
            assertEquals(consistent[i], trend.get("consistent").getAsInt());
        }
    }

    private static void assertSlots(JsonObject comparison, long frames, long hop, int slots)
    {
        assertEquals("V1", comparison.get("route").getAsString(), "Historical bins observer stays explicitly V1");
        JsonArray kernels = comparison.getAsJsonArray("kernels"), rebins = comparison.getAsJsonArray("rebins");
        assertEquals(slots + 1, kernels.size()); assertEquals(slots, rebins.size());
        JsonObject central = kernels.get(0).getAsJsonObject();
        for (int slot = 0; slot < slots; slot++)
        {
            JsonArray original = comparison.getAsJsonArray(slot < 4 ? "a" : "b");
            long start = original.get(0).getAsLong(), end = original.get(1).getAsLong();
            int axis = slot % 4;
            if (axis == 0) start = Math.max(0, start - hop);
            if (axis == 1) start = Math.min(frames, start + hop);
            if (axis == 2) end = Math.max(0, end - hop);
            if (axis == 3) end = Math.min(frames, end + hop);
            JsonObject rebin = rebins.get(slot).getAsJsonObject(), kernel = kernels.get(slot + 1).getAsJsonObject();
            assertEquals(start, rebin.getAsJsonArray("range").get(0).getAsLong(), "slot " + slot);
            assertEquals(end, rebin.getAsJsonArray("range").get(1).getAsLong(), "slot " + slot);
            assertEquals(comparison.get("featuresId"), rebin.get("featuresId"));
            assertEquals(frames, rebin.get("frames").getAsLong());
            assertEquals(end - start, kernel.get(slot < 4 ? "firstDuration" : "secondDuration").getAsLong());
            String unchanged = slot < 4 ? "secondBinsId" : "firstBinsId", changed = slot < 4 ? "firstBinsId" : "secondBinsId";
            assertEquals(central.get(unchanged), kernel.get(unchanged));
            assertNotEquals(central.get(changed), kernel.get(changed), "Even a clipped no-op must be re-extracted and evaluated");
            assertTrue(kernel.has("scores"), "Every requested variant must finish the kernel");
        }
    }

    private static JsonArray originalSelections(JsonObject row, int expectedOriginal, int expectedRamp)
    {
        JsonObject original = row.getAsJsonArray("qReturns").get(0).getAsJsonObject();
        JsonArray persistentReturns = row.getAsJsonArray("persistentReturns");
        assertNotNull(persistentReturns, "Observe the independent persistent-contrast producer");
        assertEquals(1, persistentReturns.size());
        JsonObject persistent = persistentReturns.get(0).getAsJsonObject();
        assertNotEquals(original.get("qId"), persistent.get("qId"));
        assertEquals(original.get("featuresId"), persistent.get("featuresId"));
        JsonArray selections = row.getAsJsonArray("select"), selectedOriginal = new JsonArray();
        assertEquals(expectedOriginal + 1 + expectedRamp, selections.size(), "Every selection has a witnessed route");
        int persistentCount = 0, rampCount = 0;
        for (JsonElement element : selections)
        {
            JsonObject selection = element.getAsJsonObject();
            assertTrue(selection.has("route"), "No unclassified selection may be ignored");
            assertEquals("com.quickmaster.processing.dynamics.leveler.BoundaryDetector", selection.get("callerClass").getAsString());
            assertTrue(selection.get("callerLine").getAsInt() > 0);
            assertTrue(selection.get("callerCodeIndex").getAsLong() >= 0);
            switch (selection.get("route").getAsString())
            {
                case "ORIGINAL_Q" -> {
                    assertEquals("detect", selection.get("callerMethod").getAsString());
                    assertEquals(original.get("qId"), selection.get("qId"));
                    assertEquals(original.get("bits"), selection.get("bits"));
                    selectedOriginal.add(selection);
                }
                case "PERSISTENT_CONTRAST" -> {
                    assertEquals("detect", selection.get("callerMethod").getAsString());
                    assertEquals(persistent.get("qId"), selection.get("qId"));
                    assertEquals(persistent.get("bits"), selection.get("bits"));
                    assertEquals(Double.toHexString(.02d), selection.get("threshold").getAsString());
                    persistentCount++;
                }
                case "LOUDNESS_RAMP" -> {
                    assertEquals("refineLoudnessTransitions", selection.get("callerMethod").getAsString());
                    assertEquals(selection.get("callerScoresId"), selection.get("qId"));
                    assertNotEquals(original.get("qId"), selection.get("qId"));
                    assertNotEquals(persistent.get("qId"), selection.get("qId"));
                    assertEquals(Double.toHexString(.25d), selection.get("threshold").getAsString());
                    rampCount++;
                }
                default -> fail("Unknown selection route: " + selection);
            }
        }
        assertEquals(expectedOriginal, selectedOriginal.size(), "Retain every original-Q retry");
        assertEquals(1, persistentCount); assertEquals(expectedRamp, rampCount);
        return selectedOriginal;
    }

    private static String qAt(JsonObject original, long boundary, long frames, long hop)
    { return boundary == 0 || boundary == frames ? "0" : original.getAsJsonArray("bits").get((int) (boundary / hop - 1)).getAsString(); }
    static double value(JsonElement bits) { return Double.longBitsToDouble(Long.parseUnsignedLong(bits.getAsString(), 16)); }
    private static JsonArray observe(String mode) throws Exception
    {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        JsonObject result = MusicalContextJdiObserver.observe(mode, classpath);
        Path root = Path.of(System.getProperty("musical.context.jdi.output",
                "target/leveler-context-jdi"));
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, mode + "-");
        Files.writeString(dir.resolve("observation.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("MUSICAL_JDI " + dir.toAbsolutePath());
        assertTrue(result.get("enabledBeforeResume").getAsBoolean());
        assertTrue(result.get("sawVmDeath").getAsBoolean()); assertTrue(result.get("drainedToVmDisconnect").getAsBoolean());
        assertEquals(0, result.get("exitCode").getAsInt(), result.toString());
        return result.getAsJsonArray("cases");
    }
}
