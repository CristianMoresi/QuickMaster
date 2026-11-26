import com.dspark.analysis.LoudnessMeter;
import com.quickmaster.audio.WavFile;
import com.quickmaster.processing.ProcessingPipeline;
import com.quickmaster.processing.clip.SaturationProcessor;
import com.quickmaster.processing.clip.SaturationType;
import com.quickmaster.processing.clip.TapePreset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Whole-song acceptance of the saturation stage's analog models on a real
 * track, through the packaged classes: for every model (and every tape
 * alignment) at each reduction, the time the solve and render take, the
 * stage's meter (never a gain, its deepest reading exactly the knob's to
 * 0.001 dB, and the share of the song it reads deeper than half the knob),
 * the fall of the song's peak from the model's linear response and from the
 * input, the loudness against the input, the drive solved, whether the sine
 * knee completed it, a second render identical to the bit, and the source
 * file unchanged.
 * <p>
 * Arguments: the WAV, then optional {@code db=1,3,6} and {@code types=TAPE,TUBE}.
 */
public final class SaturationTrackAudit
{
    public static void main(String[] args) throws Exception
    {
        Path wav = Path.of(args[0]);
        double[] reductions = { 1.0, 3.0, 6.0 };
        List<SaturationType> types = new ArrayList<>(List.of(SaturationType.TUBE_PREAMP,
                SaturationType.TRANSFORMER, SaturationType.MULTI_STAGE, SaturationType.TAPE));
        for (int i = 1; i < args.length; i++)
        {
            if (args[i].startsWith("db=")) reductions = Arrays.stream(args[i].substring(3).split(","))
                    .mapToDouble(Double::parseDouble).toArray();
            if (args[i].startsWith("types=")) types = Arrays.stream(args[i].substring(6).split(","))
                    .map(SaturationType::valueOf).toList();
        }
        String before = sha(wav);
        System.out.println("APPLICATION_CLASSES " + SaturationProcessor.class.getProtectionDomain().getCodeSource().getLocation());
        WavFile file = new WavFile(wav.toString());
        file.load();
        float[] source = file.getSamples();
        int ch = file.getChannels(), rate = file.getSampleRate();
        double inPeak = peakDb(source), inLufs = LoudnessMeter.measureIntegrated(source, rate, ch);
        System.out.printf(Locale.US, "SOURCE %s %.1f s %d Hz %d ch peak %.3f dBFS %.2f LUFS%n", wav.getFileName(),
                source.length / (double) ch / rate, rate, ch, inPeak, inLufs);
        int failures = 0;
        for (SaturationType type : types)
        {
            List<TapePreset> presets = type == SaturationType.TAPE ? List.of(TapePreset.values()) : Arrays.asList((TapePreset) null);
            for (TapePreset preset : presets)
                for (double db : reductions)
                {
                    float[] input = source.clone();
                    SaturationProcessor p = stage(type, preset, db);
                    long t = System.nanoTime();
                    float[] out = render(p, input, rate, ch);
                    double seconds = (System.nanoTime() - t) / 1e9;
                    float[] again = render(stage(type, preset, db), input, rate, ch);
                    double fall = p.getLinearPeakDb() - peakDb(out), inputFall = inPeak - peakDb(out);
                    double loud = LoudnessMeter.measureIntegrated(out, rate, ch) - inLufs;
                    boolean finite = true;
                    for (float v : out) finite &= Float.isFinite(v);
                    boolean same = Arrays.equals(out, again), untouched = Arrays.equals(input, source);
                    boolean kept = Math.abs(loud) <= 0.06 || p.isCompletedByKnee() && Math.abs(loud) <= 0.1;
                    int frames = out.length / ch, half = 0, read = 0;
                    double worst = 0.0;
                    boolean noGain = true;
                    for (long f = 0; f < frames; f += 512)
                    {
                        double gr = p.getGrAtPosition(f);
                        noGain &= gr <= 0.0;
                        worst = Math.min(worst, gr);
                        read++;
                        if (gr < -db / 2) half++;
                    }
                    boolean exact = noGain && Math.abs(worst + db) < 1e-3;
                    boolean pass = finite && same && untouched && exact && kept;
                    if (!pass) failures++;
                    System.out.printf(Locale.US, "%s %-11s %-16s %4.1f dB: %6.2f s renders %d drive %+7.3f dB meter deepest %.4f dB, past half %.2f%% of the song; peak fall from linear %.3f, from input %+.3f; loudness %+.3f dB knee=%b deterministic=%b%n",
                            pass ? "PASS" : "FAIL", type, preset == null ? "-" : preset.label(), db, seconds,
                            p.getSolveRenders(), p.getSolvedDriveDb(), worst, 100.0 * half / read, fall, inputFall,
                            loud, p.isCompletedByKnee(), same);
                }
        }
        boolean sourceUnchanged = before.equals(sha(wav));
        System.out.println((failures == 0 && sourceUnchanged ? "SATURATION_TRACK_PASS" : "SATURATION_TRACK_FAIL")
                + " failures=" + failures + " sourceUnchanged=" + sourceUnchanged);
        if (failures != 0 || !sourceUnchanged) System.exit(1);
    }

    private static SaturationProcessor stage(SaturationType type, TapePreset preset, double db)
    {
        SaturationProcessor p = new SaturationProcessor();
        p.setEnabled(true);
        p.setType(type);
        if (preset != null) p.setTapePreset(preset);
        p.setSatDb(db);
        return p;
    }

    private static float[] render(SaturationProcessor p, float[] input, int rate, int ch)
    {
        ProcessingPipeline pipe = new ProcessingPipeline();
        pipe.addProcessor(p);
        pipe.prepare(rate, input.length);
        return pipe.analyzeAndRender(input, ch, 0, null, null, null);
    }

    private static double peakDb(float[] x)
    {
        double p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return 20 * Math.log10(p);
    }

    private static String sha(Path p) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
}
