import com.dspark.analysis.TruePeak;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.PeakNormalizer;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.clip.HardClipProcessor;
import com.quickmaster.processing.clip.SoftClipProcessor;
import java.util.Locale;
import java.util.Random;

/** Read-only synthetic check of the final ceiling across oversampling modes. */
public class OversamplingCeilingAudit {
    public static void main(String[] args) {
        int cases = 0, violations = 0;
        double worst = Double.NEGATIVE_INFINITY;
        for (int rate : new int[]{44100, 48000, 96000})
            for (int channels : new int[]{1, 2})
                for (int factor : new int[]{1, 2, 4, 8, 16})
                    for (int shape = 0; shape < 3; shape++)
                        for (int clip = 0; clip < 3; clip++) {
                            float[] input = new float[8193 * channels];
                            Random random = new Random(415);
                            for (int f = 0; f < 8193; f++) for (int c = 0; c < channels; c++) {
                                double wave = shape == 0 ? Math.sin(f * Math.PI * 0.37 + c * 0.6)
                                        : shape == 1 ? random.nextDouble() * 2 - 1
                                        : (f % 173 == 0 ? (c == 0 ? 1 : -1) : 0);
                                input[f * channels + c] = (float) (wave * 0.8);
                            }
                            ProcessingPipeline pipeline = new ProcessingPipeline();
                            if (clip == 1) { HardClipProcessor p = new HardClipProcessor(); p.setEnabled(true); p.setClipDb(6); pipeline.addProcessor(p); }
                            if (clip == 2) { SoftClipProcessor p = new SoftClipProcessor(); p.setEnabled(true); p.setSatDb(6); pipeline.addProcessor(p); }
                            pipeline.addProcessor(new PeakNormalizer(-1));
                            WavFile file = new WavFile("synthetic", rate, channels, input, 32, true);
                            pipeline.processOversampled(file, factor, null);
                            double peak = 20 * Math.log10(TruePeak.measureMax(file.getSamples(), channels));
                            double excess = peak + 1;
                            cases++; worst = Math.max(worst, excess);
                            if (!Double.isFinite(peak) || excess > 0.005) {
                                violations++;
                                System.out.printf(Locale.ROOT, "CEILING_VIOLATION rate=%d channels=%d factor=%d shape=%d clip=%d peak=%.9f excess=%.9f%n", rate, channels, factor, shape, clip, peak, excess);
                            }
                        }
        System.out.printf(Locale.ROOT, "CEILING_AUDIT cases=%d violations=%d worstExcessDb=%.9f%n", cases, violations, worst);
        if (violations != 0) System.exit(1);
    }
}
