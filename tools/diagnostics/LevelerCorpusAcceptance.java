import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.dynamics.LevelerProcessor;
import com.quickmaster.processing.dynamics.leveler.*;
import com.quickmaster.processing.dynamics.leveler.model.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Actual packaged processor, private local audio, in-memory-only level-error injection. */
public class LevelerCorpusAcceptance {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path file = Path.of(args[0]);
        String hash = hash(file);
        WavFile song = new WavFile(file.toString());
        song.load();
        int channels = song.getChannels(), rate = song.getSampleRate();
        float[] input = song.getSamples();
        System.out.printf("SOURCE %s sha256=%s frames=%d rate=%d channels=%d%n",
                file.getFileName(), hash, input.length / channels, rate, channels);
        Path jar = Path.of(LevelerProcessor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        System.out.println("JAR " + jar + " sha256=" + hash(jar));
        LevelerProcessor original = processor(input, channels, rate, 1);
        float[] output = render(original, input, channels);
        long changed = report("ORIGINAL", input, output);
        protectedExact(original.getShadowAnalysis().cache(), input, output, channels);
        if (args.length > 1 && args[1].equals("--positive") && changed == 0)
            throw new AssertionError("Original track has no actual correction");

        // The original file is already mastered. Reserve headroom in memory so
        // a known +4 dB defect exercises leveling, not an infeasible clipped input.
        float[] clean = input.clone();
        for (int i = 0; i < clean.length; i++) clean[i] *= .25f;
        LevelerProcessor baseline = processor(clean, channels, rate, 1);
        var baselineCache = baseline.getShadowAnalysis().cache();
        int selected = -1;
        double loudest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < baselineCache.referencePlan().size(); i++) {
            var target = baselineCache.referencePlan().targets().get(i);
            var region = baselineCache.descriptors().get(i);
            if (target.referenceLoudness().present() && region.regionalLoudness().lufs() > loudest) {
                selected = i;
                loudest = region.regionalLoudness().lufs();
            }
        }
        if (selected < 0) throw new AssertionError("No comparable bodies for independent level-error trial");
        var selectedRegion = baselineCache.descriptors().get(selected).range();
        double reference = baselineCache.referencePlan().targets().get(selected).referenceLoudness().lufs();
        List<FrameRange> peers = new ArrayList<>();
        for (int i = 0; i < baselineCache.referencePlan().size(); i++) {
            var target = baselineCache.referencePlan().targets().get(i);
            if (i != selected && target.referenceLoudness().present()
                    && Math.abs(target.referenceLoudness().lufs() - reference) < 1e-9)
                peers.add(baselineCache.descriptors().get(i).range());
        }
        if (peers.isEmpty()) throw new AssertionError("No reference peers");
        float[] damaged = clean.clone();
        for (int i = Math.toIntExact(selectedRegion.startInclusive() * channels);
                i < selectedRegion.endExclusive() * channels; i++) damaged[i] *= (float)Math.pow(10, 4.0 / 20);
        LevelerProcessor repaired = processor(damaged, channels, rate, 1);
        float[] repairedOutput = render(repaired, damaged, channels);
        long injectedChanged = report("INJECTED_PLUS_4_DB", damaged, repairedOutput);
        protectedExact(baselineCache, damaged, repairedOutput, channels);
        var analyzer = new LoudnessAnalyzer();
        var format = baselineCache.format();
        var before = analyzer.analyze(damaged, format, new CancellationToken());
        var after = analyzer.analyze(repairedOutput, format, new CancellationToken());
        double beforeGap = level(analyzer, before, selectedRegion, rate) - peerLevel(analyzer, before, peers, rate);
        double afterGap = level(analyzer, after, selectedRegion, rate) - peerLevel(analyzer, after, peers, rate);
        System.out.printf("KNOWN_ERROR selected=%.3f..%.3f peers=%d beforeGapLu=%.6f afterGapLu=%.6f improvementLu=%.6f%n",
                selectedRegion.startInclusive() / (double)rate, selectedRegion.endExclusive() / (double)rate,
                peers.size(), beforeGap, afterGap, beforeGap - afterGap);
        if (injectedChanged == 0 || beforeGap - afterGap < 1)
            throw new AssertionError("Known +4 dB body error was not reduced by at least 1 LU");

        LevelerProcessor zero = processor(damaged, channels, rate, 0);
        if (report("ZERO_AMOUNT", damaged, render(zero, damaged, channels)) != 0)
            throw new AssertionError("Zero amount changed audio");
        if (!hash.equals(hash(file))) throw new AssertionError("Source file changed");
        System.out.println("PASS actualPackagedRender=true protectedOriginalRegionsExact=true sourceUnchanged=true");
    }

    private static LevelerProcessor processor(float[] input, int channels, int rate, double amount) {
        LevelerProcessor processor = new LevelerProcessor();
        processor.setEnabled(true);
        processor.setLeveling(amount);
        processor.setSpeed(.5);
        processor.prepare(rate, input.length / channels);
        processor.analyze(input, channels);
        if (processor.getShadowAnalysis() == null || processor.getShadowAnalysis().diagnostics().standardValidation().state() != ConformanceState.PASSED)
            throw new AssertionError("Authentic packaged conformance is required");
        System.out.println("STATUS amount=" + amount + " " + processor.getAnalysisDiagnostic());
        return processor;
    }

    private static float[] render(LevelerProcessor processor, float[] input, int channels) {
        float[] output = new float[input.length];
        processor.setPlaybackPosition(0);
        for (int start = 0; start < input.length; start += 4096 * channels) {
            int end = Math.min(input.length, start + 4096 * channels);
            float[] block = Arrays.copyOfRange(input, start, end);
            processor.process(block, channels);
            System.arraycopy(block, 0, output, start, block.length);
        }
        return output;
    }

    private static long report(String trial, float[] input, float[] output) {
        long changed = 0;
        double minimum = 0, maximum = 0;
        for (int i = 0; i < input.length; i++) {
            if (!Float.isFinite(output[i])) throw new AssertionError("Nonfinite render");
            if (Float.floatToRawIntBits(input[i]) != Float.floatToRawIntBits(output[i])) changed++;
            if (Math.abs(input[i]) > 1e-5) {
                double gain = 20 * Math.log10(Math.abs(output[i] / (double)input[i]));
                minimum = Math.min(minimum, gain);
                maximum = Math.max(maximum, gain);
            }
        }
        if (minimum < -6.00001 || maximum > 3.00001) throw new AssertionError("Gain bounds exceeded");
        System.out.printf("RENDER %s changed=%d/%d minGainDb=%.6f maxGainDb=%.6f%n", trial, changed, input.length, minimum, maximum);
        return changed;
    }

    private static void protectedExact(ShadowAnalysisCache cache, float[] input, float[] output, int channels) {
        for (int i = 0; i < cache.descriptors().size(); i++) {
            if (!cache.protections().get(i).isBlocked()) continue;
            FrameRange range = cache.descriptors().get(i).range();
            for (int index = Math.toIntExact(range.startInclusive() * channels); index < range.endExclusive() * channels; index++)
                if (Float.floatToRawIntBits(input[index]) != Float.floatToRawIntBits(output[index]))
                    throw new AssertionError("Protected source region " + i + " changed at sample " + index);
        }
    }

    private static double level(LoudnessAnalyzer analyzer, LoudnessTimeline timeline, FrameRange range, int rate) {
        MeasuredLoudness value = analyzer.regionalLoudness(timeline, range, rate);
        if (!value.present()) throw new AssertionError("Missing regional loudness");
        return value.lufs();
    }

    private static double peerLevel(LoudnessAnalyzer analyzer, LoudnessTimeline timeline, List<FrameRange> peers, int rate) {
        double[] levels = new double[peers.size()];
        for (int i = 0; i < levels.length; i++) levels[i] = level(analyzer, timeline, peers.get(i), rate);
        Arrays.sort(levels);
        return (levels[(levels.length - 1) / 2] + levels[levels.length / 2]) / 2;
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[65536];
        try (var stream = Files.newInputStream(path)) {
            for (int count; (count = stream.read(block)) >= 0;) digest.update(block, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
