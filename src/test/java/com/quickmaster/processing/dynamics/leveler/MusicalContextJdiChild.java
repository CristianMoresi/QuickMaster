package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;
import java.util.BitSet;
import com.google.gson.*;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Child-only case boundaries let an external observer attribute actual product events. */
public final class MusicalContextJdiChild
{
    public static void main(String[] args) throws Exception
    {
        switch (args[0])
        {
            case "dense-pcm" -> {
                var test=MusicalPcmFixture.catalog().stream().filter(c->c.key().equals("A06_DENSE_NOVELTY")).findFirst().orElseThrow();
                for(int rate:new int[]{44100,48000})for(int channels:new int[]{1,2}) {
                    var clip=MusicalPcmFixture.generate(test,rate,channels);
                    mark("A06-"+rate+"-"+channels);
                    var snapshot=new LevelerAnalysisEngine().analyzeShadow(clip.pcm(),clip.format(),new CancellationToken());
                    JsonObject row=new JsonObject();row.addProperty("engineNull",snapshot==null);
                    row.addProperty("frames",clip.format().frames());row.addProperty("rate",rate);row.addProperty("channels",channels);
                    finish(row.toString());
                }
            }
            case "engine" -> {
                for (int run = 0; run < 3; run++)
                {
                    mark("ENGINE-" + run);
                    ShadowAnalysisSnapshot snapshot = MusicalContextPcmFixture.analyze();
                    if (snapshot == null) throw new AssertionError("Missing PCM snapshot");
                    JsonObject row = new JsonObject(); row.addProperty("summary", MusicalContextEngineTest.summarize(snapshot));
                    var cache = snapshot.cache();
                    row.addProperty("frames", cache.format().frames()); row.addProperty("hop", cache.features().hopFrames());
                    row.addProperty("rate", cache.format().sampleRateHz()); row.addProperty("channels", cache.format().channels());
                    row.addProperty("algorithm", cache.algorithmId()); row.addProperty("profile", cache.profileId());
                    row.addProperty("comparisonPresent", cache.comparison() != null);
                    JsonArray descriptors = new JsonArray(), pairs = new JsonArray();
                    for (int i = 0; i < cache.descriptors().size(); i++)
                    {
                        var d = cache.descriptors().get(i); JsonObject r = new JsonObject();
                        r.addProperty("start", d.range().startInclusive()); r.addProperty("end", d.range().endExclusive());
                        r.addProperty("left", bits(d.context().leftNoveltyMad())); r.addProperty("right", bits(d.context().rightNoveltyMad()));
                        r.addProperty("protected", cache.protections().get(i).isBlocked());
                        r.addProperty("present", d.regionalLoudness().present()); descriptors.add(r);
                    }
                    int scoreIndex = 0;
                    for (int a = 0; a < cache.descriptors().size(); a++) for (int b = a + 1; b < cache.descriptors().size(); b++)
                    {
                        SimilarityScore score = cache.similarity().scores().get(scoreIndex++);
                        if (score.rejectionReason() != SimilarityRejectionReason.NONE) continue;
                        if (cache.protections().get(a).isBlocked() || cache.protections().get(b).isBlocked())
                            throw new AssertionError("Protected pair reached comparator");
                        JsonObject pair = score(score); pair.addProperty("first", a); pair.addProperty("second", b); pairs.add(pair);
                    }
                    if (pairs.isEmpty()) throw new AssertionError("PCM did not reach comparator");
                    row.add("descriptors", descriptors); row.add("pairs", pairs); finish(row.toString());
                }
            }
            case "comparisons" -> {
                compare("J-STABLE", 48_000, 32 * 24_000L, 4 * 24_000L, 10 * 24_000L, 16 * 24_000L, 22 * 24_000L, -1);
                compare("J-EXT", 48_000, 32 * 24_000L, 4 * 24_000L, 10 * 24_000L, 16 * 24_000L, 22 * 24_000L, 3);
                for (int tail = 0; tail < 2; tail++)
                    compare("J-EDGE-" + tail, 48_000, 32 * 24_000L - tail, 4 * 24_000L, 10 * 24_000L,
                            26 * 24_000L, 32 * 24_000L - tail, -1);
                long length = 7_056_000L;
                for (int rate : new int[] { 44_100, 96_000 }) compare("J-RATE-" + rate, rate, 6 * length,
                        length, 2 * length, 4 * length, 5 * length, (int) (length / Math.round(.5d * rate) - 1));
            }
            case "q" -> {
                for (boolean chroma : new boolean[] { true, false })
                {
                    mark(chroma ? "Q-CHROMA" : "Q-SPECTRAL");
                    long hop = 32, frames = 1025 * hop;
                    FeatureTimeline features = MusicalContextFixtures.features(hop, frames, i -> {
                        double[] c = new double[12], s = new double[8];
                        c[i >= 512 && chroma ? 1 : 0] = 1; s[0] = i >= 512 && !chroma ? -1 : 1;
                        return new StructuralBin(c, s, 0, .75);
                    });
                    SegmentLayout layout = new BoundaryDetector().detect(features, frames, LevelerCalibrationProfile.V1);
                    var descriptors = MusicalContextFixtures.build(64, frames, features,
                            MusicalContextFixtures.loudness(64, frames, new double[0], new BitSet()), 0, 512 * hop, frames);
                    JsonObject row = new JsonObject(); row.addProperty("regions", layout.regions().size());
                    row.addProperty("context", bits(descriptors.get(0).context().rightNoveltyMad())); finish(row.toString());
                }
                for (boolean persistent : new boolean[] { false, true })
                {
                    mark(persistent ? "Q-PERSISTENT" : "Q-RETRY");
                    int count = persistent ? 131_073 : 30_000;
                    double[] activity = new double[count]; Arrays.fill(activity, .2);
                    if (persistent) for (int i = 0; i < count; i++) activity[i] = (Math.min(64, i / 1024) & 1) == 0 ? .2 : .8;
                    else for (int j = 0; j < 80; j++)
                    {
                        int start = 1000 + 300 * j, end = j == 79 ? count : start + 300;
                        Arrays.fill(activity, start, end, j % 2 == 0 ? (j < 30 ? .8 : j < 50 ? .6 : .4) : .2);
                    }
                    FeatureTimeline features = MusicalContextFixtures.features(1, count,
                            i -> MusicalModelFixtures.bin(0, 0, activity[i]));
                    SegmentLayout layout = new BoundaryDetector().detect(features, count, LevelerCalibrationProfile.V1);
                    JsonObject row = new JsonObject(); row.addProperty("status", layout.status().name());
                    row.addProperty("regions", layout.regions().size()); finish(row.toString());
                }
            }
            case "radii" -> {
                for (int sr : new int[] { 1, 3, 29, 31, 44_100, 48_000 })
                {
                    mark("Q-RADII-" + sr); long hop = Math.max(1, Math.round(sr * .5d));
                    BoundaryDetector.originalNovelty(MusicalContextFixtures.features(hop, 2 * hop,
                            i -> MusicalContextFixtures.base()), 2 * hop, LevelerCalibrationProfile.V1);
                    finish("{}");
                }
            }
            case "trends" -> {
                for (int mode = 0; mode < 4; mode++)
                {
                    mark("TREND-" + mode); double[] values = new double[40]; BitSet valid = new BitSet(); valid.set(0, 40);
                    for (int i = 0; i < 40; i++) values[i] = -30d + i / 8d;
                    if (mode == 0) { valid.clear(3, 20); Arrays.fill(values, 3, 20, 0); }
                    if (mode == 1) { valid.clear(17); values[17] = 0; }
                    if (mode == 2) { Arrays.fill(values, -30); values[0] = -10; values[39] = -50; }
                    if (mode == 3) for (int i = 0; i < 40; i += 5) values[i] += i / 5 % 2 == 0 ? 1 : -1;
                    long frames = 331_200;
                    MusicalContextFixtures.build(48_000, frames, MusicalContextFixtures.features(24_000, frames,
                            i -> MusicalContextFixtures.base()), MusicalContextFixtures.loudness(48_000, frames, values, valid), 0, frames);
                    finish("{}");
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static void compare(String name, int rate, long frames, long startA, long endA,
                                long startB, long endB, int exterior) throws Exception
    {
        mark(name); long hop = Math.max(1, Math.round(rate * .5d));
        FeatureTimeline features = MusicalContextFixtures.features(hop, frames,
                i -> i == exterior ? MusicalContextFixtures.exterior() : MusicalContextFixtures.base());
        var a = MusicalContextFixtures.descriptor(0, features, new FrameRange(startA, endA), frames);
        var b = MusicalContextFixtures.descriptor(1, features, new FrameRange(startB, endB), frames);
        SimilarityScore score = new SegmentComparator().compare(a, b, new AudioFormat(rate, 1, frames), features, LevelerCalibrationProfile.V1);
        JsonObject result = score(score); result.addProperty("frames", frames); result.addProperty("hop", hop);
        finish(result.toString());
    }

    static JsonObject score(SimilarityScore score)
    {
        JsonObject row = new JsonObject(); row.addProperty("reason", score.rejectionReason().name());
        JsonArray scores = new JsonArray(); for (double value : new double[] { score.h(), score.t(), score.a(), score.c() }) scores.add(bits(value));
        row.add("scores", scores); row.addProperty("rotation", score.chromaRotation());
        row.addProperty("validCount", score.validBins()); return row;
    }
    static String bits(double value) { return Long.toHexString(Double.doubleToRawLongBits(value)); }
    static void mark(String label) { if (label.isEmpty()) throw new IllegalArgumentException(); }
    static void finish(String result) { if (result.isEmpty()) throw new IllegalArgumentException(); }
}
