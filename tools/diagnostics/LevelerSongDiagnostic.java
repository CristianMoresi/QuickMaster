import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.dynamics.LevelerProcessor;
import com.quickmaster.processing.dynamics.leveler.GainPlanner;
import com.quickmaster.processing.dynamics.leveler.TruePeakSafety;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import com.quickmaster.processing.dynamics.leveler.BodyContextGate;
import com.quickmaster.processing.dynamics.leveler.ComparisonComparator;
import com.quickmaster.processing.dynamics.leveler.LevelerCalibrationProfile;
import com.quickmaster.processing.dynamics.leveler.model.ControlState;
import java.util.Locale;

/** Diagnostic only: reproduces installed Leveler behavior without writing audio. */
public class LevelerSongDiagnostic {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        WavFile song = new WavFile(args[0]);
        song.load();
        float scale = args.length > 1 ? Float.parseFloat(args[1]) : 1;
        double speed = args.length > 2 ? Double.parseDouble(args[2]) : .5;
        float[] pcm = song.getSamples();
        int trimFrames = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        if (trimFrames > 0) pcm = java.util.Arrays.copyOf(pcm, pcm.length - trimFrames * song.getChannels());
        double samplePeak = 0;
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] *= scale;
            samplePeak = Math.max(samplePeak, Math.abs(pcm[i]));
        }
        int channels = song.getChannels(), rate = song.getSampleRate(), frames = pcm.length / channels;
        System.out.printf("INPUT scale=%.3f rate=%d channels=%d frames=%d samplePeakDb=%.6f%n",
                scale, rate, channels, frames, 20 * Math.log10(samplePeak));
        LevelerProcessor processor = new LevelerProcessor();
        processor.setEnabled(true);
        processor.setLeveling(1);
        processor.setSpeed(speed);
        processor.prepare(rate, frames);
        long started = System.nanoTime();
        processor.analyze(pcm, channels);
        System.out.printf("LEVELER status=%s analyzed=%s seconds=%.3f%n", processor.getAnalysisDiagnostic(),
                processor.isAnalyzed(), (System.nanoTime() - started) / 1e9);
        var snapshot = processor.getShadowAnalysis();
        if (snapshot == null) throw new AssertionError("No structural analysis");
        var cache = snapshot.cache();
        System.out.println("CONFORMANCE " + snapshot.diagnostics().standardValidation().state());
        var plan = new GainPlanner().plan(cache, new ControlState(1, speed));
        System.out.printf("PLAN valid=%s unit=%s regions=%d%n", plan.valid(), plan.schedule().isUnit(), plan.regionCount());
        var gate = new BodyContextGate();
        for (int i = 0; i < cache.descriptors().size(); i++) {
            var segment = cache.descriptors().get(i);
            var target = cache.referencePlan().targets().get(i);
            System.out.printf("REGION id=%d start=%.3f end=%.3f lufs=%.3f protection=%d reference=%s rawDb=%.6f confidence=%.6f plannedDb=%.6f%n",
                    i, segment.range().startInclusive() / (double)rate, segment.range().endExclusive() / (double)rate,
                    segment.regionalLoudness().present() ? segment.regionalLoudness().lufs() : Double.NaN,
                    cache.protections().get(i).flags().reasonBits(), target.reason(), target.rawDb(), target.gConf(), plan.targetAt(i));
            System.out.printf("BODY id=%d eligibility=%s foreground=%.4f activitySpread=%.4f activitySlope=%.4f loudnessSlope=%.4f loudnessDelta=%.4f context=%s%n",
                    i, gate.evaluate(i, cache.descriptors(), cache.protections()).rejectionReason(), segment.foregroundRatio(), segment.activitySpread(),
                    segment.activitySlopePerSec(), segment.loudnessSlopeLuPerSec(), segment.loudnessDeltaLu(),
                    java.util.Arrays.toString(new double[]{segment.context().componentAt(0), segment.context().componentAt(1),
                        segment.context().componentAt(2), segment.context().componentAt(3), segment.context().componentAt(4), segment.context().componentAt(5)}));
        }
        var rejections = new java.util.TreeMap<String, Integer>();
        for (int i = 0; i < cache.descriptors().size(); i++) {
            for (int j = i + 1; j < cache.descriptors().size(); j++) {
                var pair = cache.similarity().scoreAt(i, j);
                rejections.merge(pair.rejectionReason().toString(), 1, Integer::sum);
                if (gate.evaluate(i, cache.descriptors(), cache.protections()).eligible()
                        && gate.evaluate(j, cache.descriptors(), cache.protections()).eligible())
                System.out.printf("PAIR %d/%d reason=%s H=%.4f T=%.4f A=%.4f C=%.4f%n",
                        i, j, pair.rejectionReason(), pair.h(), pair.t(), pair.a(), pair.c());
            }
        }
        System.out.println("PAIR_REASONS " + rejections);
        for (int[] indices : new int[][]{{10, 12}, {10, 16}, {12, 16}}) {
            if (indices[1] >= cache.descriptors().size()) continue;
            var raw = new ComparisonComparator().compare(cache.descriptors().get(indices[0]).range(),
                    cache.descriptors().get(indices[1]).range(), cache.format(), cache.comparison(),
                    LevelerCalibrationProfile.V2, new CancellationToken());
            System.out.printf("RAW_PAIR %d/%d reason=%s H=%.4f T=%.4f A=%.4f C=%.4f%n",
                    indices[0], indices[1], raw.rejectionReason(), raw.h(), raw.t(), raw.a(), raw.c());
        }
        var safety = new TruePeakSafety().constrain(pcm, cache.format(), plan, new CancellationToken());
        System.out.printf("SAFETY status=%s inputTpDb=%.6f outputTpDb=%.6f ceilingDb=%.6f unit=%s%n",
                safety.proof().status(), 20 * Math.log10(safety.proof().inputTp()),
                20 * Math.log10(safety.proof().candidateTp()), 20 * Math.log10(safety.proof().ceilingLinear()), safety.schedule().isUnit());
        long changed = 0;
        double minGain = 0, maxGain = 0;
        for (int start = 0; start < frames; start += 4096) {
            int count = Math.min(4096, frames - start);
            float[] block = java.util.Arrays.copyOfRange(pcm, start * channels, (start + count) * channels);
            processor.process(block, channels);
            for (int i = 0; i < block.length; i++) {
                float original = pcm[start * channels + i];
                if (Float.floatToRawIntBits(original) != Float.floatToRawIntBits(block[i])) changed++;
                if (Math.abs(original) > 1e-5) {
                    double gain = 20 * Math.log10(Math.abs(block[i] / (double)original));
                    minGain = Math.min(minGain, gain);
                    maxGain = Math.max(maxGain, gain);
                }
            }
        }
        System.out.printf("RENDER changed=%d/%d minGainDb=%.9f maxGainDb=%.9f%n", changed, pcm.length, minGain, maxGain);
    }
}
