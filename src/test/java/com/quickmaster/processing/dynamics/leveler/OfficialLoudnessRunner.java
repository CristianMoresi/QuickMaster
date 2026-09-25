package com.quickmaster.processing.dynamics.leveler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickmaster.processing.dynamics.leveler.model.*;

/** Test-only file-profile runner; the product and runner execute the same LoudnessCore class. */
public final class OfficialLoudnessRunner
{
    private OfficialLoudnessRunner() { }

    public static ConformanceRun measure(Path ituRoot, Path ebuRoot, Path ituManifest, Path ebuManifest,
                                         Path authorization, String algorithmSha256, String profileSha256,
                                         String runnerSha256, long timestamp) throws Exception
    {
        ConformanceRequirement requirement = ConformanceRequirement.OFFICIAL_LOUDNESS_V1;
        boolean authorized = false;
        if (authorization != null && Files.isRegularFile(authorization))
        {
            OfficialWaveReader.validateRegular(authorization);
            JsonObject document = JsonParser.parseString(Files.readString(authorization)).getAsJsonObject();
            authorized = document.has("status") && document.get("status").getAsString().equals("AUTHORIZED")
                    && document.has("explicit_user_response_received") && document.get("explicit_user_response_received").getAsBoolean()
                    && document.has("authorized_scope") && document.get("authorized_scope").getAsString().contains("no redistribution");
        }
        boolean ituAvailable = ituRoot != null && Files.isDirectory(ituRoot) && ituManifest != null && Files.isRegularFile(ituManifest);
        boolean ebuAvailable = authorized && ebuRoot != null && Files.isDirectory(ebuRoot) && ebuManifest != null && Files.isRegularFile(ebuManifest);
        if (ituAvailable) OfficialWaveReader.validateRegular(ituManifest);
        if (ebuAvailable) OfficialWaveReader.validateRegular(ebuManifest);
        String ituHash = ituAvailable ? OfficialWaveReader.sha256(ituManifest) : "";
        String ebuHash = ebuAvailable ? OfficialWaveReader.sha256(ebuManifest) : "";
        ConformanceReason failure = ConformanceReason.NONE;
        if ((ituAvailable && !ituHash.equals(requirement.ituPinnedManifestSha256()))
                || (ebuAvailable && !ebuHash.equals(requirement.ebuPinnedManifestSha256())))
            failure = ConformanceReason.MANIFEST_MISMATCH;
        Object[] evidence = new Object[ConformanceRequirement.E_REQ];
        int count = 0;
        if (failure == ConformanceReason.NONE)
        {
            for (int i = 0; i < requirement.requiredReadings().size(); i++)
            {
                RequiredOfficialReading reading = requirement.requiredReadings().get(i);
                boolean itu = reading.setId().equals(requirement.ituSetId());
                if (!(itu ? ituAvailable : ebuAvailable)) continue;
                Path root = itu ? ituRoot : ebuRoot;
                String filename = reading.signalId() + (itu ? ".wav" : "");
                Path normalizedRoot = root.toAbsolutePath().normalize();
                Path source = normalizedRoot.resolve("signals").resolve(filename).normalize();
                if (!source.startsWith(normalizedRoot) || !source.getFileName().toString().equals(filename))
                    throw new IllegalArgumentException("Literal official signal escaped its root.");
                if (!Files.exists(source)) continue;
                try
                {
                    OfficialSignalEvidence result = measureReading(source, reading);
                    evidence[count++] = result;
                    System.out.println("READING " + i + " " + reading.signalId() + " " + reading.readingKind()
                            + " measured=" + result.measuredKind() + ":" + result.measuredLufs()
                            + " min=" + result.minimumLufs() + " frames=" + result.sourceFramesConsumed()
                            + " readouts=" + result.readoutCount() + " gates=" + result.completeGatingBlocks()
                            + "/" + result.absoluteGateBlocks() + "/" + result.relativeGateBlocks()
                            + " reset=" + result.resetCount() + " eof=" + result.eofReached());
                }
                catch (IllegalStateException ex)
                {
                    failure = ConformanceReason.INVALID_EVIDENCE;
                    System.out.println("READING_FAILED " + i + " " + reading.signalId() + " " + ex.getMessage());
                }
                catch (Exception ex)
                {
                    failure = ConformanceReason.CONTAINER_MISMATCH;
                    System.out.println("READING_FAILED " + i + " " + reading.signalId() + " " + ex.getClass().getName() + " " + ex.getMessage());
                }
            }
        }
        if (count == 0 && failure == ConformanceReason.NONE && !ituAvailable && !ebuAvailable)
            failure = ConformanceReason.CORPUS_UNAVAILABLE;
        if (ituAvailable && !ituHash.equals(OfficialWaveReader.sha256(ituManifest))) failure = ConformanceReason.MANIFEST_MISMATCH;
        if (ebuAvailable && !ebuHash.equals(OfficialWaveReader.sha256(ebuManifest))) failure = ConformanceReason.MANIFEST_MISMATCH;
        System.out.println("APPLICABILITY EBU11/14=LIVE_ALTERNATIVE_NOT_APPLICABLE_TO_FILE_PROFILE; TP15..23=M005; OTHER_ACQUIRED=8");
        return new ConformanceRun(ituHash, ebuHash, new FrozenList<OfficialSignalEvidence>(Arrays.copyOf(evidence, count)),
                authorized, true, failure, requirement.algorithmId(), algorithmSha256, profileSha256, runnerSha256, timestamp);
    }

    static OfficialSignalEvidence measureReading(Path path, RequiredOfficialReading reading) throws Exception
    {
        int resets = 0;
        LoudnessCore core = new LoudnessCore(reading.sampleRateHz(), reading.channelLayout());
        resets++;
        int momentary = Math.toIntExact(Math.round(0.4d * reading.sampleRateHz()));
        int shortTerm = Math.toIntExact(Math.round(3.0d * reading.sampleRateHz()));
        int hop = Math.toIntExact(Math.round(0.1d * reading.sampleRateHz()));
        int blocksCapacity = Math.toIntExact(Math.max(0L, 1L + Math.floorDiv(reading.sourceFrames() - momentary, hop)));
        double[] blockPower = reading.readoutMode() == ReadoutMode.EOF_INTEGRATED ? new double[blocksCapacity] : new double[0];
        int blocks = 0;
        long count = 0L;
        long first = 0L;
        long last = 0L;
        long minEnd = 0L;
        long maxEnd = 0L;
        double minimum = 0.0d;
        double maximum = 0.0d;
        boolean finiteSeen = false;
        MeasuredLoudness measured;
        long[] gateCounts = new long[3];
        boolean eof;
        long consumed;
        try (OfficialWaveReader source = new OfficialWaveReader(path, reading))
        {
            float[] pcm = new float[65536 * reading.channels()];
            int frames;
            while ((frames = source.readPcm(pcm)) != -1)
            {
                for (int i = 0; i < frames; i++)
                {
                    core.acceptFrame(pcm, i * reading.channels());
                    long end = core.framesSeen();
                    if (reading.readoutMode() == ReadoutMode.EOF_INTEGRATED)
                    {
                        if (end >= momentary && (end - momentary) % hop == 0L)
                            blockPower[blocks++] = core.momentaryPower();
                    }
                    else if (reading.readoutMode() == ReadoutMode.CONSTANT_INTERVAL
                            || reading.readoutMode() == ReadoutMode.MAXIMUM_FULL_WINDOWS)
                    {
                        long start = reading.readoutMode() == ReadoutMode.CONSTANT_INTERVAL
                                ? (reading.readingKind() == ReadingKind.SHORT_TERM ? 144001L : 48001L)
                                : (reading.readingKind() == ReadingKind.SHORT_TERM ? shortTerm : momentary);
                        if (end < start) continue;
                        MeasuredLoudness window = LoudnessCore.fromPower(reading.readingKind() == ReadingKind.SHORT_TERM
                                ? core.shortTermPower() : core.momentaryPower());
                        if (count == 0L) first = end;
                        last = end;
                        count++;
                        if (!window.present())
                        {
                            if (reading.readoutMode() == ReadoutMode.CONSTANT_INTERVAL)
                                throw new IllegalStateException("A required constant-interval readout is absent at " + end);
                            continue;
                        }
                        if (!finiteSeen || window.lufs() < minimum) { minimum = window.lufs(); minEnd = end; }
                        if (!finiteSeen || window.lufs() > maximum) { maximum = window.lufs(); maxEnd = end; }
                        finiteSeen = true;
                    }
                }
            }
            source.finish();
            consumed = source.framesRead();
            eof = source.eofReached();
        }
        if (consumed != core.framesSeen()) throw new IllegalStateException("Reader/core frame counters disagree.");
        if (reading.readoutMode() == ReadoutMode.EOF_INTEGRATED)
        {
            measured = LoudnessCore.integrated(blockPower, blocks, gateCounts, new CancellationToken());
            if (measured == null) throw new IllegalStateException("Unexpected cancellation in official runner.");
            minimum = measured.lufs();
            first = last = minEnd = maxEnd = core.framesSeen();
            count++;
        }
        else if (reading.readoutMode() == ReadoutMode.STEADY_EOF)
        {
            measured = LoudnessCore.fromPower(reading.readingKind() == ReadingKind.SHORT_TERM
                    ? core.shortTermPower() : core.momentaryPower());
            minimum = measured.lufs();
            first = last = minEnd = maxEnd = core.framesSeen();
            count++;
        }
        else
        {
            measured = finiteSeen ? new MeasuredLoudness(true, maximum) : MeasuredLoudness.absent();
            if (reading.readoutMode() == ReadoutMode.MAXIMUM_FULL_WINDOWS)
            {
                minimum = measured.lufs();
                minEnd = maxEnd;
            }
            if (!finiteSeen) minEnd = maxEnd = first;
        }
        return new OfficialSignalEvidence(reading.setId(), reading.setVersion(), reading.caseNumber(),
                reading.signalId(), reading.signalSha256(), reading.sampleRateHz(), reading.channels(), reading.readingKind(),
                reading.expectedLufs(), measured.lufs(), reading.toleranceLu(), reading.channelLayout(), reading.readoutMode(),
                reading.expectedKind(), measured.present() ? LoudnessValueKind.FINITE : LoudnessValueKind.NO_LOUDNESS,
                minimum, consumed, first, last, count, minEnd, maxEnd, gateCounts[0], gateCounts[1], gateCounts[2], resets, eof);
    }
}
