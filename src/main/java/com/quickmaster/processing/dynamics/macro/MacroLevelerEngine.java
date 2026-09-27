package com.quickmaster.processing.dynamics.macro;

import com.dspark.analysis.TruePeak;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Whole-file, stereo-linked macro RMS normalization. Musical labels and repeated
 * arrangements are deliberately not eligibility gates. Linear PCM scans plus
 * O(points log points) quantiles; O(duration / 100 ms) retained storage.
 */
public final class MacroLevelerEngine {
    public static final double MAX_CORRECTION_DB = 24;
    private static final double CEILING = .999;
    public record Report(String status, double targetRmsDb, double headroomOffsetDb,
                         double minimumGainDb, double maximumGainDb, double outputTruePeak,
                         int boundaries, int limitedPoints, int controlPoints) { }
    public record Result(MacroGainCurve curve, Report report, int channels) { }

    public Result analyze(float[] pcm, int channels, int rate, double amount, double speed,
                          CancellationToken token) {
        checkCancellation(token);
        if (pcm == null || pcm.length == 0 || (channels != 1 && channels != 2)
                || pcm.length % channels != 0 || rate < 1
                || !Double.isFinite(amount) || amount < 0 || amount > 1
                || !Double.isFinite(speed) || speed < 0 || speed > 1)
            throw new IllegalArgumentException("Invalid macro analysis input.");
        int frames = pcm.length / channels, hop = Math.max(1, rate / 10);
        int bins = (frames - 1) / hop + 1;
        double[] powers = new double[bins], peaks = new double[bins], prefix = new double[bins + 1];
        double[] frameCounts = new double[bins + 1];
        for (int b = 0; b < bins; b++) {
            checkCancellation(token);
            int first = b * hop, end = (int)Math.min(frames, (long)first + hop);
            double sum = 0, peak = 0;
            for (int f = first; f < end; f++) for (int c = 0; c < channels; c++) {
                float sample = pcm[f * channels + c];
                if (!Float.isFinite(sample)) throw new IllegalArgumentException("Non-finite audio.");
                sum += (double)sample * sample;
                peak = Math.max(peak, Math.abs((double)sample));
            }
            powers[b] = sum / ((end - first) * (double)channels);
            peaks[b] = peak;
            // Integrate energy with actual duration. In particular a trailing
            // single frame must not count as an entire 100 ms analysis block.
            prefix[b + 1] = prefix[b] + sum / channels;
            frameCounts[b + 1] = end;
        }
        double[] sorted = powers.clone(); Arrays.sort(sorted);
        double highDb = db(sorted[(int)((bins - 1) * .90)]);
        // A residual floor, not a claim to semantically distinguish noise from
        // intentional noisy music. Keep 35 dB of active musical range; passages
        // below it fade out of correction through the 6 dB activity knee.
        double activityFloor = Math.max(-65, highDb - 35);
        double floorPower = Math.pow(10, activityFloor / 10);
        double[] active = new double[bins]; int activeCount = 0;
        for (double p : powers) if (p > floorPower) active[activeCount++] = p;
        if (activeCount == 0 || amount == 0) {
            double[] unit = new double[bins + 1]; Arrays.fill(unit, 1);
            return result(new MacroGainCurve(rate, frames, hop, unit), channels,
                    activeCount == 0 ? "NO_MUSICAL_ACTIVITY" : "WITHIN_TOLERANCE",
                    Double.NaN, 0, Double.NaN, 0, 0);
        }

        // The reference is independent of Speed. Percentile of 3 s musical power
        // suppresses beat phase / single-transient bias without requiring recurrence.
        int refRadius = Math.max(1, (int)Math.round(1.5 * rate / hop));
        int refCount = 0;
        for (int i = 0; i < bins; i++) if (powers[i] > floorPower) {
            double p = mean(prefix, frameCounts, Math.max(0, i - refRadius), Math.min(bins, i + refRadius));
            active[refCount++] = p;
        }
        Arrays.sort(active, 0, refCount);
        double target = db(active[(int)((refCount - 1) * .75)]);

        // Abrupt, persistent changes: compare homogeneous 1 s contexts. A
        // boundary changes estimation support, never whether leveling is allowed.
        int context = Math.max(2, (int)Math.round(rate / (double)hop));
        double[] novelty = new double[bins + 1];
        for (int i = context; i <= bins - context; i++) {
            double left = db(mean(prefix, frameCounts, i-context, i));
            double right = db(mean(prefix, frameCounts, i, i+context));
            int half = context / 2;
            double leftSpread = Math.abs(db(mean(prefix,frameCounts,i-context,i-half))-db(mean(prefix,frameCounts,i-half,i)));
            double rightSpread = Math.abs(db(mean(prefix,frameCounts,i,i+half))-db(mean(prefix,frameCounts,i+half,i+context)));
            if (left > activityFloor+6 && right > activityFloor+6
                    && leftSpread < 1.5 && rightSpread < 1.5 && Math.abs(right-left) >= 3)
                novelty[i] = Math.abs(right-left);
        }
        int[] edges = new int[bins + 2]; int edgeCount = 1; edges[0] = 0;
        for (int i = context; i <= bins - context; i++) if (novelty[i] > 0) {
            boolean maximum = true;
            for (int j = Math.max(context,i-context); j <= Math.min(bins-context,i+context); j++)
                if (novelty[j] > novelty[i] || (novelty[j] == novelty[i] && j < i)) { maximum=false; break; }
            if (maximum && i - edges[edgeCount-1] >= context*2) edges[edgeCount++] = i;
        }
        edges[edgeCount++] = bins;

        // Speed is macro, not compressor attack/release: 6..2 s power context,
        // and a symmetric 1.2..0.2 s transition. At default: 3.46 s / 0.49 s.
        double horizon = 6 * Math.pow(1.0/3, speed);
        int radius = Math.max(1, (int)Math.round(horizon*.5*rate/hop));
        double[] desired = new double[bins + 1]; int region = 0, limited = 0;
        for (int i = 0; i <= bins; i++) {
            checkCancellation(token);
            while (region + 2 < edgeCount && i >= edges[region+1]) region++;
            int from = Math.max(edges[region],i-radius), to = Math.min(edges[region+1],i+radius);
            if (to <= from) { from=Math.max(0,Math.min(i,bins-1)); to=from+1; }
            double level = db(mean(prefix,frameCounts,from,to));
            double correction = target - level;
            boolean exceedsLimit = Math.abs(correction) > MAX_CORRECTION_DB;
            correction = Math.max(-MAX_CORRECTION_DB,Math.min(MAX_CORRECTION_DB,correction));
            // Local activity prevents the macro window lifting an adjacent noise
            // tail. A 6 dB soft knee avoids a hard gain switch at the floor.
            double local = db(powers[Math.min(i,bins-1)]);
            double activity = Math.max(0,Math.min(1,(local-activityFloor)/6));
            activity = activity*activity*(3-2*activity);
            if (exceedsLimit && activity > .99) limited++;
            desired[i] = amount * correction * activity;
        }
        int smoothRadius = Math.max(1,(int)Math.round(.5*1.2*Math.pow(1.0/6,speed)*rate/hop));
        double[] gains = new double[bins+1];
        for (int i=0;i<gains.length;i++) {
            double sum=0, weight=0;
            for(int j=-smoothRadius;j<=smoothRadius;j++) {
                double w=smoothRadius+1-Math.abs(j);
                sum+=w*desired[Math.max(0,Math.min(bins,i+j))]; weight+=w;
            }
            gains[i]=Math.pow(10,sum/weight/20);
        }
        MacroGainCurve curve = new MacroGainCurve(rate, frames, hop, gains);
        // Bound sample peaks before the FIR scan, avoiding float overflow even
        // for otherwise finite malformed/high-level input. This is a common
        // scalar, never a section-specific gain clamp or peak limiter.
        double bound = 0;
        for(int b=0;b<bins;b++) bound=Math.max(bound,peaks[b]*Math.max(gains[b],gains[b+1]));
        double offset = bound > CEILING ? 20*Math.log10(CEILING/bound) : 0;
        if(offset<0)curve=curve.scaled(Math.pow(10,offset/20));
        double peak=scan(pcm,channels,curve,token);
        if(peak>CEILING) {
            double scale=CEILING/peak;
            curve=curve.scaled(scale);offset+=20*Math.log10(scale);
            peak=scan(pcm,channels,curve,token);
        }
        // The safety margin exceeds float rounding. Do not publish an unproved
        // result if a future interpolator change invalidates that assumption.
        if(!Double.isFinite(peak)||peak>1)throw new IllegalStateException("Macro peak safety not verified.");
        String status = limited>0 ? "MACRO_LIMITED" : "MACRO_READY";
        if(Math.max(Math.abs(curve.minimumDb()),Math.abs(curve.maximumDb()))<.01)status="WITHIN_TOLERANCE";
        return result(curve,channels,status,target,offset,peak,edgeCount-2,limited);
    }
    private static Result result(MacroGainCurve curve,int channels,String status,double target,
                                 double offset,double peak,int boundaries,int limited) {
        return new Result(curve,new Report(status,target,offset,curve.minimumDb(),curve.maximumDb(),
                peak,boundaries,limited,curve.controlPoints()),channels);
    }
    private static double scan(float[] pcm,int channels,MacroGainCurve curve,CancellationToken token) {
        TruePeak[] detectors=new TruePeak[channels];Arrays.setAll(detectors,i->new TruePeak());double peak=0;
        for(int f=0;f<curve.sourceFrames();f++) {
            if((f&4095)==0)checkCancellation(token);
            double gain=curve.linearAt(f);
            for(int c=0;c<channels;c++)peak=Math.max(peak,detectors[c].process((float)(pcm[f*channels+c]*gain)));
        }
        for(int f=0;f<TruePeak.TAIL_FRAMES;f++)for(var detector:detectors)peak=Math.max(peak,detector.process(0));
        return peak;
    }
    private static double mean(double[] prefix, double[] frames, int from, int to) {
        return Math.max(0, (prefix[to] - prefix[from]) / (frames[to] - frames[from]));
    }
    private static double db(double power) { return 10*Math.log10(Math.max(1e-100,power)); }
    private static void checkCancellation(CancellationToken token) {
        if(Thread.currentThread().isInterrupted() || (token!=null&&token.isCancelled()))
            throw new CancellationException("Macro analysis superseded.");
    }
}
