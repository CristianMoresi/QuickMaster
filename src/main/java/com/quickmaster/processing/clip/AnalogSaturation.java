package com.quickmaster.processing.clip;

import com.dspark.analysis.LoudnessMeter;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Saturates a whole song through an analog model so that the most any moment
 * of it loses to the saturation is an exact number of decibels, keeping its
 * loudness.
 * <p>
 * What a moment loses is measured against the model's linear response: the
 * same model with the song far below its saturation, brought to the song's
 * loudness, compared hop by hop (the peak of each hop of the output against
 * the peak of the same hop of the linear response). A model's equalization,
 * filters and phase change a song's peaks by themselves and are not
 * saturation. A model with memory takes more off some moments than off the
 * song's loudest one (a tape's pre-emphasis saturates bright transients
 * first), so the reduction asked is the most it takes off anywhere: the
 * stage's meter reads exactly that at the most saturated moment and less
 * everywhere else, as it does for a memoryless curve at the song's peak.
 * <p>
 * The song goes into the model at the model's alignment: its integrated
 * loudness is brought to the model's 0 VU, so the same song saturates alike
 * whatever level it arrives at. The drive is solved on a sample of the song:
 * its loudest moments in the input and in the linear response, the moments
 * each whole render saturated most, and windows spread over its loudness,
 * which also give how the model moves the loudness, each rendered after
 * enough of what precedes it for the model to settle. The whole song is then
 * rendered in parallel stretches, each started early enough to be the same as
 * one continuous pass, and every join is checked against the stretch before
 * it. The output gain makes the most saturated moment lose exactly the
 * reduction asked; the loudness is then within {@link #LOUDNESS_TOLERANCE_DB}
 * of the input's, or the drive is solved again with what the render measured.
 * <p>
 * When even the least drive saturates further than asked, the least drive is
 * used and the output raised to the reduction asked. When the most drive
 * cannot reach it, the most drive is used and DSPark's sine knee takes the
 * rest after it, with the loudness kept.
 * <p>
 * Records are immutable results; the static methods allocate and use several
 * threads.
 */
final class AnalogSaturation
{
    static final int HOP = AnalyzedClipProcessor.HOP;
    static final double LOUDNESS_TOLERANCE_DB = 0.05;
    private static final int MAX_RENDERS = 8;
    private static final int REGIONS = 24;
    /** Moments of a rendering added to the sample where it had none: the linear response's loudest, a render's most saturated. */
    private static final int ADDED_REGIONS = 8;
    private static final int BODIES = 8;
    /** How near the sample's solution must come, in dB of reduction. */
    private static final double SOLVE_TOLERANCE_DB = 0.005;
    /** The half-width of the first bracket around a hint, in dB of drive. */
    private static final double HINT_SPAN_DB = 3.0;
    private static final double REGION_GAP_SECONDS = 0.25;
    private static final double WARM_SECONDS = 0.4;
    private static final double REGION_SPAN_SECONDS = 0.05;
    private static final double BODY_SECONDS = 1.0;
    private static final double TAIL_SECONDS = 0.1;
    private static final double STRETCH_SECONDS = 10.0;
    private static final double JOIN_SECONDS = 0.05;
    private static final double JOIN_TOLERANCE = 1e-6;
    private static final double MAX_ALIGNMENT_DB = 60.0;
    /** How far below its alignment the linear response is rendered, in dB. */
    private static final double LINEAR_DB = 40.0;
    /** Hops this far below the linear response's peak are not measured: nothing saturates there. */
    private static final double FLOOR_DB = 60.0;
    private static final int BLOCK = 2048;

    private AnalogSaturation() { }

    /** A stretch of the song kept with what precedes it: {@code heard} is what is measured. */
    record Region(int heardStart, int heardEnd, int from, float[] audio, double lufs) { }

    /** What the stage measures once of its input. */
    record Analysis(int rate, int channels, int frames, double peak, double lufs, float[] hopPeak,
                    List<Region> regions, List<Region> bodies, long fingerprint)
    {
        double peakDb() { return 20.0 * Math.log10(peak); }

        boolean silent() { return !(peak > 0.0) || !(lufs > LoudnessMeter.SILENCE_LUFS); }

        /**
         * The gain that brings the song to a model's alignment, a loudness of
         * two channels: a mono song is aligned 3 dB lower, at the same level
         * in its one channel.
         */
        double alignment(double lufsTarget)
        {
            double target = channels == 1 ? lufsTarget - 10.0 * Math.log10(2.0) : lufsTarget;
            double db = Math.max(-MAX_ALIGNMENT_DB, Math.min(MAX_ALIGNMENT_DB, target - lufs));
            return Math.pow(10.0, db / 20.0);
        }
    }

    /**
     * How the model was driven and what came of it: {@code reductionDb} is
     * the most any hop lost to the saturation. When the model could not reach
     * the reduction on its own, the sine knee after it runs at
     * {@code clipCeiling} of {@code clipScale} and the output is then raised
     * by {@code clipGainDb}; {@code clipScale} is 0 otherwise.
     */
    record Solution(double driveDb, double gainDb, double reductionDb, double loudnessDb, int renders,
                    double clipScale, double clipCeiling, double clipGainDb)
    {
        static final Solution BYPASS = new Solution(0.0, 0.0, 0.0, 0.0, 0, 0.0, 1.0, 0.0);

        Solution(double driveDb, double gainDb, double reductionDb, double loudnessDb, int renders)
        {
            this(driveDb, gainDb, reductionDb, loudnessDb, renders, 0.0, 1.0, 0.0);
        }

        boolean clips() { return clipScale > 0.0; }
    }

    /**
     * The model's linear response to the song, brought to the input's
     * loudness: its peak in dBFS and the peak of every hop, what each hop of
     * the output is measured against (0 where it is not known).
     */
    record Linear(double peakDb, float[] hopPeak)
    {
        /** The level below which a hop is not measured. */
        double floor() { return Math.pow(10.0, (peakDb - FLOOR_DB) / 20.0); }
    }

    /** A drive, the loudness change a whole render measured and the one the sample predicted. */
    record Level(double driveDb, double lambdaDb, double sampledDb) { }

    /** A whole render with its solution and its measured output. */
    record Result(Analysis analysis, Solution solution, float[] output, float[] hopPeakOut, List<Level> levels) { }

    /** Measures the input: its peak, loudness, the peak of every hop, its loudest moments and its body. */
    static Analysis analyze(float[] input, int channels, int rate, CancellationToken cancel)
    {
        int frames = input.length / channels;
        int hops = (int) (((long) frames + HOP - 1) / HOP);
        float[] hopPeak = new float[hops];
        double[] hopPower = new double[hops];
        long fingerprint = 1469598103934665603L ^ input.length;
        for (int f = 0; f < frames; f++)
        {
            if ((f & 65535) == 0) check(cancel);
            int h = f / HOP;
            for (int c = 0; c < channels; c++)
            {
                float v = input[f * channels + c];
                if (!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite saturation input.");
                float a = Math.abs(v);
                if (a > hopPeak[h]) hopPeak[h] = a;
                hopPower[h] += (double) v * v;
                fingerprint = (fingerprint ^ Float.floatToIntBits(v)) * 1099511628211L;
            }
        }
        double peak = 0.0;
        for (float p : hopPeak) peak = Math.max(peak, p);
        double lufs = peak > 0.0 ? LoudnessMeter.measureIntegrated(input, rate, channels) : LoudnessMeter.SILENCE_LUFS;
        List<Region> regions = new ArrayList<>();
        Integer[] order = new Integer[hops];
        for (int h = 0; h < hops; h++) order[h] = h;
        Arrays.sort(order, (a, b) -> Float.compare(hopPeak[b], hopPeak[a]));
        int gap = (int) Math.ceil(REGION_GAP_SECONDS * rate);
        for (int k = 0; k < hops && regions.size() < REGIONS; k++)
        {
            int h = order[k];
            if (!(hopPeak[h] > 0.0f)) break;
            int centre = h * HOP + HOP / 2;
            boolean near = false;
            for (Region r : regions) near |= Math.abs((r.heardStart() + r.heardEnd()) / 2 - centre) < gap;
            if (!near) regions.add(region(input, channels, rate, h * HOP, Math.min(frames, (h + 1) * HOP), false));
        }
        return new Analysis(rate, channels, frames, peak, lufs, hopPeak, List.copyOf(regions),
                bodies(input, channels, rate, hopPower), fingerprint);
    }

    /**
     * Windows of the song spread over its loudness: of the second-long windows
     * within 20 dB of the loudest, one at each of {@link #BODIES} even steps of
     * their ranking by power.
     */
    private static List<Region> bodies(float[] input, int channels, int rate, double[] hopPower)
    {
        int frames = input.length / channels;
        int span = Math.max(HOP, (int) Math.ceil(BODY_SECONDS * rate)), hopsPer = Math.max(1, span / HOP);
        int windows = hopPower.length / hopsPer;
        if (windows == 0) return List.of(region(input, channels, rate, 0, frames, true));
        double[] power = new double[windows];
        double loudest = 0.0;
        for (int w = 0; w < windows; w++)
        {
            for (int h = w * hopsPer; h < (w + 1) * hopsPer; h++) power[w] += hopPower[h];
            loudest = Math.max(loudest, power[w]);
        }
        List<Integer> kept = new ArrayList<>();
        for (int w = 0; w < windows; w++) if (power[w] > loudest * 0.01) kept.add(w);
        kept.sort((x, y) -> Double.compare(power[x], power[y]));
        List<Region> bodies = new ArrayList<>();
        int count = Math.min(BODIES, kept.size());
        for (int i = 0; i < count; i++)
        {
            int w = kept.get((int) ((i + 0.5) * kept.size() / count));
            int start = w * hopsPer * HOP;
            bodies.add(region(input, channels, rate, start, Math.min(frames, start + hopsPer * HOP), true));
        }
        return List.copyOf(bodies);
    }

    /** The analysis with the hops of the highest score added as moments of the sample, where it had none. */
    static Analysis withMoments(Analysis a, float[] input, float[] score)
    {
        int ch = a.channels(), hops = a.hopPeak().length;
        Integer[] order = new Integer[hops];
        for (int h = 0; h < hops; h++) order[h] = h;
        Arrays.sort(order, (x, y) -> Float.compare(score[y], score[x]));
        List<Region> regions = new ArrayList<>(a.regions());
        int added = 0;
        for (int k = 0; k < hops && added < ADDED_REGIONS; k++)
        {
            int h = order[k], frame = h * HOP + HOP / 2;
            if (!(score[h] > 0.0f)) break;
            boolean covered = false;
            for (Region r : regions) covered |= frame >= r.heardStart() && frame < r.heardEnd();
            if (covered) continue;
            regions.add(region(input, ch, a.rate(), h * HOP, Math.min(a.frames(), (h + 1) * HOP), false));
            added++;
        }
        return added == 0 ? a : new Analysis(a.rate(), ch, a.frames(), a.peak(), a.lufs(), a.hopPeak(),
                List.copyOf(regions), a.bodies(), a.fingerprint());
    }

    /** The peak of every hop of an interleaved rendering, scaled by {@code gain}. */
    private static float[] hopPeaks(float[] audio, int channels, int hops, double gain)
    {
        float[] peaks = new float[hops];
        for (int i = 0; i < audio.length; i++)
        {
            int h = i / channels / HOP;
            float v = Math.abs(audio[i]);
            if (v > peaks[h]) peaks[h] = v;
        }
        for (int h = 0; h < hops; h++) peaks[h] = (float) (peaks[h] * gain);
        return peaks;
    }

    /** The most, in dB, any measured hop of a rendering lies below the linear response (negative when none does). */
    static double worst(Linear linear, float[] hopOut)
    {
        double floor = linear.floor(), worst = Double.NEGATIVE_INFINITY;
        float[] ref = linear.hopPeak();
        for (int h = 0; h < Math.min(ref.length, hopOut.length); h++)
            if (ref[h] > floor && hopOut[h] > 0.0f) worst = Math.max(worst, 20.0 * Math.log10(ref[h] / hopOut[h]));
        return worst == Double.NEGATIVE_INFINITY ? 0.0 : worst;
    }

    /** Per hop, how far a rendering lies below the linear response, in dB: at most 0, and 0 where not measured. */
    static float[] meter(Linear linear, float[] hopOut)
    {
        double floor = linear.floor();
        float[] ref = linear.hopPeak(), env = new float[Math.min(ref.length, hopOut.length)];
        for (int h = 0; h < env.length; h++)
            env[h] = ref[h] > floor && hopOut[h] > 0.0f
                    ? (float) Math.min(0.0, 20.0 * Math.log10(hopOut[h] / ref[h])) : 0.0f;
        return env;
    }

    /** Per hop, the ratio by which a rendering lies below the linear response, where it does (0 elsewhere). */
    private static float[] lost(Linear linear, float[] hopOut)
    {
        double floor = linear.floor();
        float[] ref = linear.hopPeak(), score = new float[hopOut.length];
        for (int h = 0; h < Math.min(ref.length, hopOut.length); h++)
            if (ref[h] > floor && hopOut[h] > 0.0f && ref[h] > hopOut[h]) score[h] = ref[h] / hopOut[h];
        return score;
    }

    /**
     * The model's linear response: the whole song through it at its least
     * drive, {@link #LINEAR_DB} below its alignment, brought to the input's loudness.
     */
    static Linear linear(float[] input, Analysis a, Supplier<AnalogModel> factory, int workers, CancellationToken cancel)
    {
        if (a.silent()) return new Linear(a.peakDb(), a.hopPeak());
        AnalogModel probe = factory.get();
        double norm = a.alignment(probe.alignmentLufs()) * Math.pow(10.0, -LINEAR_DB / 20.0);
        Whole w = renderWhole(input, a.channels(), a.rate(), norm, probe.minDriveDb(), factory, workers, cancel);
        double gainDb = a.lufs() - w.lufs();
        return new Linear(w.peakDb() + gainDb, hopPeaks(w.output(), a.channels(), a.hopPeak().length,
                Math.pow(10.0, gainDb / 20.0)));
    }

    /** The linear response over the sample alone, for a provisional solution: the hops it covers. */
    private static Linear sampledLinear(Analysis a, Supplier<AnalogModel> factory, int workers, CancellationToken cancel)
    {
        AnalogModel probe = factory.get();
        double norm = a.alignment(probe.alignmentLufs()) * Math.pow(10.0, -LINEAR_DB / 20.0);
        Sample s = sample(a, probe.minDriveDb(), factory, norm, workers, cancel);
        float gain = (float) Math.pow(10.0, -s.sampledDb() / 20.0);
        float[] hopPeak = new float[a.hopPeak().length];
        double peak = 0.0;
        for (int i = 0; i < s.hops().length; i++)
        {
            float v = s.peaks()[i] * gain;
            hopPeak[s.hops()[i]] = Math.max(hopPeak[s.hops()[i]], v);
            peak = Math.max(peak, v);
        }
        return new Linear(20.0 * Math.log10(Math.max(peak, 1e-30)), hopPeak);
    }

    private static Region region(float[] input, int channels, int rate, int start, int end, boolean body)
    {
        int frames = input.length / channels;
        int span = body ? 0 : (int) Math.ceil(REGION_SPAN_SECONDS * rate);
        int heardStart = Math.max(0, start - span), heardEnd = Math.min(frames, end + span);
        int from = Math.max(0, heardStart - (int) Math.ceil(WARM_SECONDS * rate));
        int to = Math.min(frames, heardEnd + (int) Math.ceil(TAIL_SECONDS * rate));
        double lufs = body ? LoudnessMeter.measureIntegrated(
                Arrays.copyOfRange(input, heardStart * channels, heardEnd * channels), rate, channels) : 0.0;
        return new Region(heardStart, heardEnd, from, Arrays.copyOfRange(input, from * channels, to * channels), lufs);
    }

    /**
     * Solves the drive on the sample, renders the whole song and solves again
     * with what the render measured until the loudness is kept. The reduction
     * is measured against {@code linear}, the model's linear response.
     */
    static Result render(float[] input, Analysis analysis, Linear linear, double targetDb,
                         Supplier<AnalogModel> factory, int workers, CancellationToken cancel)
    {
        int channels = analysis.channels();
        if (analysis.silent() || !(targetDb > 0.0))
            return new Result(analysis, Solution.BYPASS, input, analysis.hopPeak(), List.of());
        AnalogModel probe = factory.get();
        double norm = analysis.alignment(probe.alignmentLufs());
        double least = probe.minDriveDb(), most = probe.maxDriveDb();
        int hops = analysis.hopPeak().length;
        List<Level> levels = new ArrayList<>();
        Analysis a = withMoments(analysis, input, linear.hopPeak());
        Solution best = null;
        float[] bestOut = null;
        double bestError = Double.POSITIVE_INFINITY;
        // The drives of whole renders that saturated too little and too much, once the loudness is kept.
        double under = Double.NaN, underError = 0.0, over = Double.NaN, overError = 0.0;
        int side = 0;
        double lastDrive = Double.NaN;
        for (int pass = 0; pass < MAX_RENDERS; pass++)
        {
            check(cancel);
            Estimate e = solve(a, linear, targetDb, factory, norm, levels, levels.isEmpty() ? Double.NaN
                    : levels.get(levels.size() - 1).driveDb(), workers, cancel);
            double drive = e.driveDb();
            if (!Double.isNaN(under) && !Double.isNaN(over))
            {
                double lo = Math.min(under, over), hi = Math.max(under, over);
                if (!(drive > lo && drive < hi))
                {
                    double fu = underError, fo = overError;
                    if (side == -1) fo *= 0.5;
                    else if (side == 1) fu *= 0.5;
                    drive = under - fu * (over - under) / (fo - fu);
                    if (!(drive > lo && drive < hi)) drive = 0.5 * (lo + hi);
                }
            }
            if (drive == lastDrive) break;
            lastDrive = drive;
            double sampled = drive == e.driveDb() ? e.sampledDb() : sample(a, drive, factory, norm, workers, cancel).sampledDb();
            Whole whole = renderWhole(input, channels, a.rate(), norm, drive, factory, workers, cancel);
            float[] hopOut = hopPeaks(whole.output(), channels, hops, 1.0);
            double lost = worst(linear, hopOut), lambda = whole.lufs() - a.lufs();
            double gainDb = lost - targetDb;
            levels.add(new Level(drive, lambda, sampled));
            // The loudness change once the gain makes the most saturated hop lose exactly the reduction.
            double error = lost + lambda - targetDb;
            boolean floorBound = drive <= least && error >= 0.0;
            boolean ceilingBound = drive >= most && error <= 0.0;
            double loudness = error;
            if (ceilingBound)
            {
                gainDb = -lambda;
                loudness = 0.0;
            }
            double score = ceilingBound || floorBound ? 0.0 : Math.abs(error);
            if (score < bestError)
            {
                bestError = score;
                best = new Solution(drive, gainDb, lost - gainDb, loudness, pass + 1);
                bestOut = whole.output();
            }
            if (Math.abs(error) <= LOUDNESS_TOLERANCE_DB || floorBound || ceilingBound) break;
            if (error < 0.0)
            {
                under = drive;
                underError = error;
                side = -1;
            }
            else
            {
                over = drive;
                overError = error;
                side = 1;
            }
            a = withMoments(a, input, lost(linear, hopOut));
        }
        float gain = (float) Math.pow(10.0, best.gainDb() / 20.0);
        for (int i = 0; i < bestOut.length; i++) bestOut[i] *= gain;
        if (best.reductionDb() < targetDb - 1e-6) best = complete(bestOut, a, linear, best, targetDb, cancel);
        return new Result(a, best, bestOut, hopPeaks(bestOut, channels, hops, 1.0), List.copyOf(levels));
    }

    /**
     * Takes the rest of the reduction the model could not reach with the sine
     * knee, on the model's output in place, raising the result back to the
     * loudness it had, so the most saturated hop still loses exactly the
     * reduction asked.
     */
    private static Solution complete(float[] out, Analysis a, Linear linear, Solution s, double targetDb,
                                     CancellationToken cancel)
    {
        com.dspark.effects.Clipper knee = new com.dspark.effects.Clipper();
        knee.setMode(com.dspark.effects.Clipper.Mode.ANALOG);
        double positive = 0.0, negative = 0.0;
        for (float v : out)
        {
            positive = Math.max(positive, v);
            negative = Math.max(negative, -v);
        }
        double scale = Math.max(positive, negative);
        if (!(scale > 0.0)) return s;
        double pos = positive / scale, neg = negative / scale;
        int hops = a.hopPeak().length;
        // How far the knee lowers the peak is solved by secant steps on what the most saturated hop loses.
        double ask = targetDb - s.reductionDb(), ceiling = 1.0, gainDb = 0.0;
        double prevAsk = Double.NaN, prevError = 0.0;
        float[] trial = new float[out.length];
        for (int pass = 0; pass < 16; pass++)
        {
            check(cancel);
            ceiling = ceilingFor(knee, pos, neg, Math.pow(10.0, -ask / 20.0));
            for (int i = 0; i < out.length; i++) trial[i] = (float) (scale * knee.shapeSample(out[i] / scale, ceiling));
            gainDb = a.lufs() + s.loudnessDb() - LoudnessMeter.measureIntegrated(trial, a.rate(), a.channels());
            double error = worst(linear, hopPeaks(trial, a.channels(), hops, 1.0)) - gainDb - targetDb;
            if (Math.abs(error) < 1e-4) break;
            double next = Double.isNaN(prevAsk) || error == prevError ? ask - error
                    : ask - error * (ask - prevAsk) / (error - prevError);
            prevAsk = ask;
            prevError = error;
            ask = Math.max(1e-6, Math.min(60.0, next));
        }
        float g = (float) Math.pow(10.0, gainDb / 20.0);
        for (int i = 0; i < out.length; i++) out[i] = trial[i] * g;
        return new Solution(s.driveDb(), s.gainDb(), worst(linear, hopPeaks(out, a.channels(), hops, 1.0)),
                s.loudnessDb(), s.renders(), scale, ceiling, gainDb);
    }

    /** The ceiling at which the sine knee leaves a normalized peak at {@code target}. */
    static double ceilingFor(com.dspark.effects.Clipper knee, double positive, double negative, double target)
    {
        double lo = 0.0, hi = 2.0;
        while (Math.max(knee.shapeSample(positive, hi), -knee.shapeSample(-negative, hi)) < target && hi < 0x1p40) hi *= 2.0;
        for (int i = 0; i < 64; i++)
        {
            double mid = 0.5 * (lo + hi);
            if (Math.max(knee.shapeSample(positive, mid), -knee.shapeSample(-negative, mid)) > target) hi = mid;
            else lo = mid;
        }
        return 0.5 * (lo + hi);
    }

    /**
     * A drive solved on the sample, with what the sample measured there: the
     * most a sampled hop lost before the gain, and the loudness change of the
     * bodies, alone and corrected by the nearest whole render.
     */
    record Estimate(double driveDb, double lostDb, double sampledDb, double lambdaDb) { }

    /**
     * The drive at which the most saturated moment of the sample loses the
     * reduction asked once the loudness the sample measures, corrected by the
     * nearest whole render, is kept.
     */
    static Estimate solve(Analysis a, Linear linear, double targetDb, Supplier<AnalogModel> factory, double norm,
                          List<Level> levels, double hint, int workers, CancellationToken cancel)
    {
        AnalogModel probe = factory.get();
        double least = probe.minDriveDb(), most = probe.maxDriveDb();
        double lo = least, hi = most;
        Sample sLo = null, sHi = null;
        double fLo = 0.0, fHi = 0.0;
        if (Double.isFinite(hint))
        {
            // A bracket around the last drive first: the next solve lands near it.
            lo = Math.max(least, hint - HINT_SPAN_DB);
            hi = Math.min(most, hint + HINT_SPAN_DB);
            sLo = sample(a, lo, factory, norm, workers, cancel);
            fLo = reduction(linear, sLo, lo, levels) - targetDb;
            if (fLo >= 0.0 && lo > least) { hi = lo; sHi = sLo; fHi = fLo; lo = least; sLo = null; }
            else
            {
                sHi = sample(a, hi, factory, norm, workers, cancel);
                fHi = reduction(linear, sHi, hi, levels) - targetDb;
                if (fHi <= 0.0 && hi < most) { lo = hi; sLo = sHi; fLo = fHi; hi = most; sHi = null; }
            }
        }
        if (sLo == null)
        {
            sLo = sample(a, lo, factory, norm, workers, cancel);
            fLo = reduction(linear, sLo, lo, levels) - targetDb;
        }
        if (fLo >= 0.0) return estimate(linear, lo, sLo, levels);
        if (sHi == null)
        {
            sHi = sample(a, hi, factory, norm, workers, cancel);
            fHi = reduction(linear, sHi, hi, levels) - targetDb;
        }
        if (fHi <= 0.0) return estimate(linear, hi, sHi, levels);
        double x = hi;
        Sample sx = sHi;
        int side = 0;
        for (int i = 0; i < 40 && hi - lo > 1e-4; i++)
        {
            x = (lo * fHi - hi * fLo) / (fHi - fLo);
            sx = sample(a, x, factory, norm, workers, cancel);
            double fx = reduction(linear, sx, x, levels) - targetDb;
            if (Math.abs(fx) < SOLVE_TOLERANCE_DB) break;
            if (fx < 0.0)
            {
                lo = x;
                fLo = fx;
                if (side == -1) fHi *= 0.5;
                side = -1;
            }
            else
            {
                hi = x;
                fHi = fx;
                if (side == 1) fLo *= 0.5;
                side = 1;
            }
        }
        return estimate(linear, x, sx, levels);
    }

    private static Estimate estimate(Linear linear, double drive, Sample s, List<Level> levels)
    {
        return new Estimate(drive, s.lost(linear), s.sampledDb(), s.sampledDb() + correction(levels, drive));
    }

    /** What the most saturated sampled hop loses at a drive once the loudness the sample predicts is kept. */
    private static double reduction(Linear linear, Sample s, double drive, List<Level> levels)
    {
        return s.lost(linear) + s.sampledDb() + correction(levels, drive);
    }

    /** What the whole render nearest a drive measured of the loudness beyond what the sample did. */
    static double correction(List<Level> levels, double drive)
    {
        Level near = null;
        for (Level l : levels)
            if (near == null || Math.abs(l.driveDb() - drive) < Math.abs(near.driveDb() - drive)) near = l;
        return near == null ? 0.0 : near.lambdaDb() - near.sampledDb();
    }

    /**
     * What the sample measured at a drive: the peak of every hop it covers
     * (a hop may appear more than once), back at the input's level, and the
     * loudness change of its bodies.
     */
    record Sample(int[] hops, float[] peaks, double sampledDb)
    {
        /** The most, in dB, a measured hop of the sample lies below the linear response. */
        double lost(Linear linear)
        {
            double floor = linear.floor(), worst = Double.NEGATIVE_INFINITY;
            float[] ref = linear.hopPeak();
            for (int i = 0; i < hops.length; i++)
            {
                float r = ref[hops[i]];
                if (r > floor && peaks[i] > 0.0f) worst = Math.max(worst, 20.0 * Math.log10(r / peaks[i]));
            }
            return worst == Double.NEGATIVE_INFINITY ? 0.0 : worst;
        }
    }

    /** The hops of a region, from {@code first}, and their peaks. */
    private record Part(int first, float[] peaks) { }

    /** Renders the loud moments and the body windows at a drive, all at once. */
    static Sample sample(Analysis a, double drive, Supplier<AnalogModel> factory, double norm,
                         int workers, CancellationToken cancel)
    {
        List<Region> regions = a.regions(), bodies = a.bodies();
        int count = regions.size() + bodies.size();
        Part[] parts = new Part[count];
        double[] loud = new double[bodies.size()];
        List<Runnable> jobs = new ArrayList<>(count);
        for (int k = 0; k < regions.size(); k++)
        {
            final int index = k;
            jobs.add(() -> parts[index] = through(a, regions.get(index), drive, factory, norm, null, cancel));
        }
        for (int k = 0; k < bodies.size(); k++)
        {
            final int index = k;
            jobs.add(() ->
            {
                Region r = bodies.get(index);
                float[] heard = new float[(r.heardEnd() - r.heardStart()) * a.channels()];
                parts[regions.size() + index] = through(a, r, drive, factory, norm, heard, cancel);
                loud[index] = LoudnessMeter.measureIntegrated(heard, a.rate(), a.channels());
            });
        }
        run(jobs, workers, cancel);
        int total = 0;
        for (Part p : parts) total += p.peaks().length;
        int[] hops = new int[total];
        float[] peaks = new float[total];
        int at = 0;
        for (Part p : parts)
            for (int i = 0; i < p.peaks().length; i++)
            {
                hops[at] = p.first() + i;
                peaks[at++] = p.peaks()[i];
            }
        double in = 0.0, out = 0.0;
        for (int k = 0; k < bodies.size(); k++)
        {
            in += Math.pow(10.0, bodies.get(k).lufs() / 10.0);
            out += Math.pow(10.0, loud[k] / 10.0);
        }
        double sampled = in > 0.0 && out > 0.0 ? 10.0 * Math.log10(out / in) : 0.0;
        return new Sample(hops, peaks, sampled);
    }

    /**
     * A region through a fresh model at a drive; returns the peaks, back at
     * the input's level, of the whole hops it hears, and copies what it hears
     * into {@code heard} when given.
     */
    private static Part through(Analysis a, Region r, double drive, Supplier<AnalogModel> factory, double norm,
                                float[] heard, CancellationToken cancel)
    {
        AnalogModel m = factory.get();
        m.setDriveDb(drive);
        m.prepare(a.rate(), a.channels(), BLOCK);
        int lat = m.latency(), ch = a.channels();
        int frames = r.audio().length / ch;
        // The whole hops within what is heard; the song's last hop is whole when the region reaches the end.
        int first = (r.heardStart() + HOP - 1) / HOP;
        int end = r.heardEnd() >= a.frames() ? a.hopPeak().length : r.heardEnd() / HOP;
        float[] peaks = new float[Math.max(0, end - first)];
        double back = 1.0 / norm;
        Runner run = new Runner(m, ch, norm);
        for (int start = 0; start < frames; start += BLOCK)
        {
            check(cancel);
            int n = Math.min(BLOCK, frames - start);
            double[][] y = run.block(r.audio(), start, n);
            for (int i = 0; i < n; i++)
            {
                int src = r.from() + start + i - lat;
                if (src < r.heardStart() || src >= r.heardEnd()) continue;
                int h = src / HOP - first;
                for (int c = 0; c < ch; c++)
                {
                    double v = y[c][i] * back;
                    if (h >= 0 && h < peaks.length) peaks[h] = (float) Math.max(peaks[h], Math.abs(v));
                    if (heard != null) heard[(src - r.heardStart()) * ch + c] = (float) v;
                }
            }
        }
        return new Part(first, peaks);
    }

    /**
     * The drive and gain for a reduction from the sample alone, for a
     * provisional preview while the whole song is rendered; the linear
     * response is {@code linear} when known, the sample's otherwise.
     */
    static Solution provisional(Analysis a, Linear linear, double targetDb, Supplier<AnalogModel> factory,
                                List<Level> levels, int workers, CancellationToken cancel)
    {
        if (a.silent() || !(targetDb > 0.0)) return Solution.BYPASS;
        AnalogModel probe = factory.get();
        double norm = a.alignment(probe.alignmentLufs());
        Linear lin = linear != null ? linear : sampledLinear(a, factory, workers, cancel);
        Estimate e = solve(a, lin, targetDb, factory, norm, levels, levels.isEmpty() ? Double.NaN
                : levels.get(levels.size() - 1).driveDb(), workers, cancel);
        double gainDb = e.lostDb() - targetDb;
        return new Solution(e.driveDb(), gainDb, targetDb, e.lambdaDb() + gainDb, 0);
    }

    private record Whole(float[] output, double peakDb, double lufs) { }

    /** The whole song through the model, back at the input's level, its delay taken out. */
    private static Whole renderWhole(float[] input, int channels, int rate, double norm, double drive,
                                     Supplier<AnalogModel> factory, int workers, CancellationToken cancel)
    {
        int frames = input.length / channels;
        int warm = (int) Math.ceil(factory.get().settleSeconds() * rate), join = (int) Math.ceil(JOIN_SECONDS * rate);
        // The stretches follow the song's length alone, so the result is the same on any machine.
        int count = Math.max(1, (int) (frames / (STRETCH_SECONDS * rate)));
        float[] out = new float[input.length];
        float[][] tails = new float[count][];
        List<Runnable> jobs = new ArrayList<>(count);
        for (int k = 0; k < count; k++)
        {
            final int index = k;
            jobs.add(() -> tails[index] = stretch(input, channels, rate, out, bound(index, frames, count),
                    bound(index + 1, frames, count), index == 0 ? 0 : warm, index == 0 ? 0 : join,
                    norm, drive, factory, cancel));
        }
        run(jobs, workers, cancel);
        for (int k = 1; k < count; k++)
        {
            int start = bound(k, frames, count);
            if (!joins(out, tails[k], start, channels))
            {
                tails[k] = stretch(input, channels, rate, out, start, bound(k + 1, frames, count),
                        Math.min(start, 4 * warm), join, norm, drive, factory, cancel);
                if (!joins(out, tails[k], start, channels))
                {
                    stretch(input, channels, rate, out, 0, frames, 0, 0, norm, drive, factory, cancel);
                    break;
                }
            }
        }
        double peak = 0.0;
        for (float v : out) peak = Math.max(peak, Math.abs(v));
        double lufs = LoudnessMeter.measureIntegrated(out, rate, channels);
        return new Whole(out, 20.0 * Math.log10(Math.max(peak, 1e-30)), lufs);
    }

    /** The stretch {@code [start, end)} into {@code out}; returns its own output over the join before it. */
    private static float[] stretch(float[] input, int channels, int rate, float[] out, int start, int end,
                                   int warm, int join, double norm, double drive, Supplier<AnalogModel> factory,
                                   CancellationToken cancel)
    {
        int from = Math.max(0, start - warm);
        int joinFrom = Math.max(from, start - join);
        float[] tail = new float[(start - joinFrom) * channels];
        AnalogModel m = factory.get();
        m.setDriveDb(drive);
        m.prepare(rate, channels, BLOCK);
        int lat = m.latency();
        Runner run = new Runner(m, channels, norm);
        double back = 1.0 / norm;
        int last = end + lat;
        for (int pos = from; pos < last; pos += BLOCK)
        {
            check(cancel);
            int n = Math.min(BLOCK, last - pos);
            double[][] y = run.block(input, pos, n);
            for (int i = 0; i < n; i++)
            {
                int dst = pos + i - lat;
                if (dst < joinFrom || dst >= end) continue;
                for (int c = 0; c < channels; c++)
                {
                    float v = (float) (y[c][i] * back);
                    if (dst >= start) out[dst * channels + c] = v;
                    else tail[(dst - joinFrom) * channels + c] = v;
                }
            }
        }
        return tail;
    }

    private static boolean joins(float[] out, float[] tail, int start, int channels)
    {
        if (tail == null) return false;
        int from = start * channels - tail.length;
        double scale = 0.0, diff = 0.0;
        for (int i = 0; i < tail.length; i++)
        {
            scale = Math.max(scale, Math.abs(out[from + i]));
            diff = Math.max(diff, Math.abs(out[from + i] - tail[i]));
        }
        return diff <= JOIN_TOLERANCE * Math.max(scale, 1e-9);
    }

    private static int bound(int k, int frames, int count)
    {
        return (int) ((long) k * frames / count);
    }

    /** Feeds interleaved float frames through a model, scaled into it. */
    static final class Runner
    {
        private final AnalogModel model;
        private final int channels;
        private final double scale;
        private final double[][] buffer;

        Runner(AnalogModel model, int channels, double scale)
        {
            this.model = model;
            this.channels = channels;
            this.scale = scale;
            this.buffer = new double[channels][BLOCK];
        }

        /** Frames {@code [start, start + n)} of {@code audio} (zeros past its end) through the model. */
        double[][] block(float[] audio, int start, int n)
        {
            int frames = audio.length / channels;
            for (int i = 0; i < n; i++)
            {
                int f = start + i;
                for (int c = 0; c < channels; c++)
                    buffer[c][i] = f < frames ? audio[f * channels + c] * scale : 0.0;
            }
            model.process(buffer, n);
            return buffer;
        }
    }

    private static void run(List<Runnable> jobs, int workers, CancellationToken cancel)
    {
        if (workers <= 1 || jobs.size() <= 1)
        {
            for (Runnable job : jobs) job.run();
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(workers, jobs.size()), task ->
        {
            Thread t = new Thread(task, "saturation-render");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        try
        {
            List<Future<?>> pending = new ArrayList<>(jobs.size());
            for (Runnable job : jobs) pending.add(pool.submit(job));
            for (Future<?> f : pending)
            {
                try
                {
                    f.get();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Saturation cancelled.");
                }
                catch (ExecutionException e)
                {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException r) throw r;
                    if (cause instanceof Error err) throw err;
                    throw new IllegalStateException(cause);
                }
            }
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    private static void check(CancellationToken cancel)
    {
        if (Thread.currentThread().isInterrupted() || cancel != null && cancel.isCancelled())
            throw new CancellationException("Saturation cancelled.");
    }
}
