import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.dynamics.leveler.*;
import com.quickmaster.processing.dynamics.leveler.model.*;
import java.util.Arrays;
import java.util.Locale;

/** Read-only exploration of level-independent arrangement evidence, not a correction oracle. */
public class LevelerBodyDiagnostic {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        WavFile song = new WavFile(args[0]);
        song.load();
        var format = new AudioFormat(song.getSampleRate(), song.getChannels(),
                song.getSamples().length / song.getChannels());
        var analysis = new LevelerAnalysisEngine().analyzeShadow(song.getSamples(), format, new CancellationToken());
        var cache = analysis.cache();
        var timeline = cache.comparison();
        int count = cache.descriptors().size();
        double[][] means = new double[count][16];
        double[][] tones = new double[count][36];
        for (int i = 0; i < count; i++) {
            var d = cache.descriptors().get(i);
            int start = (int)(d.range().startInclusive() * 40 / format.sampleRateHz());
            int end = Math.min(timeline.shortCount(), (int)(d.range().endExclusive() * 40 / format.sampleRateHz()));
            double[] values = new double[end - start];
            for (int band = 0; band < 16; band++) {
                int n = 0;
                for (int row = start; row < end; row++) {
                    if ((timeline.shortFlagsAt(row) & 3) != 3) continue;
                    values[n++] = timeline.shortValueAt(row, 36 + band) / 256.;
                }
                Arrays.sort(values, 0, n);
                means[i][band] = n == 0 ? 0 : values[n / 2];
            }
            int n = 0;
            for (int row = start; row < end; row++) {
                if ((timeline.shortFlagsAt(row) & 7) != 7) continue;
                n++;
                double sum = 0;
                for (int band = 0; band < 36; band++) sum += timeline.shortValueAt(row, band) & 65535;
                for (int band = 0; band < 36; band++) tones[i][band] += (timeline.shortValueAt(row, band) & 65535) / sum;
            }
            if (n > 0) for (int b = 0; b < 36; b++) tones[i][b] /= n;
            System.out.printf("BODY %d %.1f..%.1f LU=%.2f slope=%.4f delta=%.3f consistency=%.3f protected=%d spectral=%s%n",
                    i, d.range().startInclusive() / (double)format.sampleRateHz(), d.range().endExclusive() / (double)format.sampleRateHz(),
                    d.regionalLoudness().present() ? d.regionalLoudness().lufs() : Double.NaN,
                    d.loudnessSlopeLuPerSec(), d.loudnessDeltaLu(), d.loudnessConsistency(), cache.protections().get(i).flags().reasonBits(),
                    Arrays.toString(means[i]));
        }
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) {
            if (cache.protections().get(i).isBlocked() || cache.protections().get(j).isBlocked()) continue;
            double shape = 0, contrast = 0, tone = 0;
            for (int b = 0; b < 8; b++) {
                shape += Math.pow(means[i][b] - means[j][b], 2) / 8;
                contrast += Math.pow(means[i][8+b] - means[j][8+b], 2) / 8;
            }
            for (int b = 0; b < 36; b++) tone += Math.abs(tones[i][b] - tones[j][b]) / 2;
            System.out.printf("PAIR %d/%d shapeRmsDb=%.3f contrastRmsDb=%.3f toneTV=%.3f%n", i, j, Math.sqrt(shape), Math.sqrt(contrast), tone);
        }
    }
}
