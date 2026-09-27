import com.quickmaster.processing.analysis.SpectrumAnalysis;
import com.quickmaster.processing.analysis.LiveSpectrum;
import com.quickmaster.processing.analysis.OutputAnalysis;
import java.util.Locale;

/** Synthetic, read-only metering audit, executable against the packaged JAR. */
public class SpectrumAudit {
    public static void main(String[] args) {
        int failures = 0;
        for (int rate : new int[]{8000, 16000, 22050, 32000, 44100, 48000, 96000, 192000}) {
            float[] mono = signal(rate, 1, false);
            try {
                SpectrumAnalysis spectrum = new SpectrumAnalysis();
                spectrum.analyze(mono, 1, rate);
                if (!spectrum.isReady() || !Double.isFinite(spectrum.levelDbAt(1000)))
                    throw new AssertionError("offline spectrum not ready/finite");
            } catch (Throwable e) {
                failures++;
                System.out.println("SPECTRUM_OFFLINE_FAILURE rate=" + rate + " " + e);
            }
            try {
                LiveSpectrum spectrum = new LiveSpectrum();
                spectrum.setSampleRate(rate);
                spectrum.push(mono, 1);
                spectrum.update();
                if (!spectrum.isReady() || !Double.isFinite(spectrum.levelDbAt(1000)))
                    throw new AssertionError("live spectrum not ready/finite");
            } catch (Throwable e) {
                failures++;
                System.out.println("SPECTRUM_LIVE_FAILURE rate=" + rate + " " + e);
            }
        }
        SpectrumAnalysis inPhase = new SpectrumAnalysis(), antiPhase = new SpectrumAnalysis();
        inPhase.analyze(signal(48000, 2, false), 2, 48000);
        antiPhase.analyze(signal(48000, 2, true), 2, 48000);
        double delta = antiPhase.levelDbAt(1000) - inPhase.levelDbAt(1000);
        System.out.printf(Locale.ROOT, "SPECTRUM_STEREO phaseInversionDeltaDb=%.9f%n", delta);
        if (Math.abs(delta) > 0.001) failures++;
        LiveSpectrum liveIn = new LiveSpectrum(), liveAnti = new LiveSpectrum();
        liveIn.push(signal(48000, 2, false), 2); liveAnti.push(signal(48000, 2, true), 2);
        for (int i = 0; i < 30; i++) { liveIn.update(); liveAnti.update(); }
        delta = liveAnti.levelDbAt(1000) - liveIn.levelDbAt(1000);
        System.out.printf(Locale.ROOT, "SPECTRUM_LIVE_STEREO phaseInversionDeltaDb=%.9f%n", delta);
        if (Math.abs(delta) > 0.001) failures++;
        var monoResult = OutputAnalysis.measure(signal(48000, 1, false), 1, 48000);
        System.out.printf(Locale.ROOT, "MONO_IMAGE midPower=%.9f sidePower=%.9f%n", monoResult.midPower(), monoResult.sidePower());
        if (Math.abs(monoResult.midPower() - .125) > .001 || monoResult.sidePower() != 0) failures++;
        System.out.println("SPECTRUM_AUDIT failures=" + failures);
        if (failures != 0) System.exit(1);
    }

    private static float[] signal(int rate, int channels, boolean antiPhase) {
        float[] data = new float[32768 * channels];
        for (int f = 0; f < 32768; f++) for (int c = 0; c < channels; c++)
            data[f * channels + c] = (float) (.5 * Math.sin(2 * Math.PI * 1000 * f / rate)
                    * (antiPhase && c == 1 ? -1 : 1));
        return data;
    }
}
