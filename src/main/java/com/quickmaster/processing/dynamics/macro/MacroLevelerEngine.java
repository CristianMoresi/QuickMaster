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
    public record Report(String status, double targetRmsDb, double headroomOffsetDb,
                         double minimumGainDb, double maximumGainDb, double outputTruePeak,
                         int boundaries, int limitedPoints, int controlPoints) { }
    public record Result(MacroGainCurve curve, Report report, int channels) { }

    public Result analyze(float[] pcm, int channels, int rate, double amount, double speed,
                          CancellationToken token) {
        return analyze(pcm,channels,rate,amount,speed,LevelerExclusions.EMPTY,token);
    }
    public Result analyze(float[] pcm, int channels, int rate, double amount, double speed,
                          LevelerExclusions exclusions, CancellationToken token) {
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
        boolean allExcluded=exclusions.regions().size()==1&&exclusions.regions().get(0).start()==0&&exclusions.regions().get(0).end()>=frames;
        if (activeCount == 0 || amount == 0 || allExcluded) {
            double[] unit = new double[bins + 1]; Arrays.fill(unit, 1);
            return result(new MacroGainCurve(rate, frames, hop, unit), channels,
                    allExcluded ? "ALL_REGIONS_EXCLUDED" : activeCount == 0 ? "NO_MUSICAL_ACTIVITY" : "WITHIN_TOLERANCE",
                    Double.NaN, 0, Double.NaN, 0, 0);
        }

        // One fixed 3 s macro scale: the strongest sustained input window is
        // the reference, never a sample peak. Short files use the whole file.
        int macroBins=Math.min(bins,Math.max(1,(int)Math.round(3.0*rate/hop)));
        double targetPower=0;
        for(int i=0;i+macroBins<=bins;i++)
            targetPower=Math.max(targetPower,mean(prefix,frameCounts,i,i+macroBins));
        double target = db(targetPower);

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
            boolean exceedsLimit = correction > MAX_CORRECTION_DB;
            correction = Math.max(0,Math.min(MAX_CORRECTION_DB,correction));
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
            gains[i]=Math.pow(10,Math.min(desired[i],sum/weight)/20);
        }
        // Bound each 3 s output window by the strongest 3 s INPUT window.
        // A bin's maximum endpoint gain conservatively bounds interpolation.
        // Only retract EXTRA gain towards unity: never attenuate the source.
        // Later reductions cannot invalidate an earlier window's upper bound.
        for(int b=0;b<bins;b++) {
            double safe=peaks[b]>0?Math.max(1,Float.MAX_VALUE/peaks[b]):Double.MAX_VALUE;
            gains[b]=Math.min(gains[b],safe);gains[b+1]=Math.min(gains[b+1],safe);
        }
        for(int start=0;start+macroBins<=bins;start++) {
            checkCancellation(token);
            int end=start+macroBins;
            double c=prefix[end]-prefix[start],a=0,b=0;
            for(int j=start;j<end;j++) {
                double energy=prefix[j+1]-prefix[j];
                double extra=Math.max(gains[j],gains[j+1])-1;
                a+=energy*extra*extra;b+=2*energy*extra;
            }
            double budget=Math.max(0,targetPower*(frameCounts[end]-frameCounts[start])-c);
            if(a+b>budget && a+b>0) {
                // Stable positive root of a*x*x + b*x = budget.
                double scale=a==0?budget/b:2*budget/(b+Math.sqrt(b*b+4*a*budget));
                scale=Math.max(0,Math.min(1,scale));
                for(int j=start;j<=end;j++)gains[j]=1+(gains[j]-1)*scale;
            }
        }
        // Smooth bound-induced corners, projected below the proven envelope.
        // Eroding every neighbourhood first would spread a local unity bound
        // across neighbouring quiet passages and needlessly defeat slow leveling.
        double[] lower=gains.clone();
        for(int i=0;i<gains.length;i++)
            lower[i]=Math.min(gains[i],Math.min(gains[Math.max(0,i-1)],gains[Math.min(bins,i+1)]));
        for(int i=0;i<gains.length;i++) {
            double sum=0,weight=0;
            for(int j=-smoothRadius;j<=smoothRadius;j++) {
                double w=smoothRadius+1-Math.abs(j);
                sum+=w*lower[Math.max(0,Math.min(bins,i+j))];weight+=w;
            }
            gains[i]=Math.max(1,Math.min(gains[i],sum/weight));
        }
        MacroGainCurve curve = new MacroGainCurve(rate,frames,hop,gains,exclusions,
                Math.max(.1,smoothRadius*hop/(double)rate)*rate);
        // Floating-point DSP may exceed 0 dBFS; final output limiting is a
        // separate module. Do not silently lower the song or cancel leveling
        // to preserve the original sample/true peak. Report required headroom.
        double peak=scan(pcm,channels,curve,token);
        if(!Double.isFinite(peak))throw new IllegalStateException("Non-finite macro output.");
        String status = limited>0 ? "MACRO_LIMITED" : "MACRO_READY";
        if(Math.max(Math.abs(curve.minimumDb()),Math.abs(curve.maximumDb()))<.01)status="WITHIN_TOLERANCE";
        return result(curve,channels,status,target,0,peak,edgeCount-2,limited);
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
