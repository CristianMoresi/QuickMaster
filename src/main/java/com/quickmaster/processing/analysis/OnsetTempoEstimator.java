package com.quickmaster.processing.analysis;

import java.util.Arrays;

/**
 * Offline tempo estimate from repeated onset intervals. A short-lag histogram
 * is scored at beat, double-beat and subdivision periods; the metrical prior
 * resolves otherwise identical half/double-time pulse trains conservatively.
 */
final class OnsetTempoEstimator
{
    private static final double BIN_SEC = 0.005;
    private static final double MAX_LAG_SEC = 2.05;
    private static final double KERNEL_SEC = 0.025;
    private static final double MIN_BPM = 60.0;
    private static final double MAX_BPM = 200.0;
    private static final double BPM_STEP = 0.25;

    record Estimate(double bpm, double confidence, boolean conflictingSections)
    {
        Estimate(double bpm, double confidence) { this(bpm, confidence, false); }
        static Estimate unknown() { return new Estimate(0.0, 0.0); }
    }

    private OnsetTempoEstimator() { }

    static Estimate estimate(double[] times, float[] strengths)
    {
        if (times == null || strengths == null || times.length != strengths.length || times.length < 8)
            return Estimate.unknown();
        for (int i = 0; i < times.length; i++)
            if (!Double.isFinite(times[i]) || times[i] < 0.0 || !Float.isFinite(strengths[i])
                    || (i > 0 && times[i] <= times[i - 1]))
                return Estimate.unknown();
        Estimate whole = estimateRange(times, strengths, 0, times.length);
        if (whole.confidence() < TrackAnalysis.RELIABLE_CONFIDENCE
                || times.length < 48 || times[times.length - 1] - times[0] < 16.0)
            return whole;
        double midpoint = (times[0] + times[times.length - 1]) * 0.5;
        int split = 0;
        while (split < times.length && times[split] < midpoint) split++;
        if (split < 16 || times.length - split < 16) return whole;
        Estimate first = estimateRange(times, strengths, 0, split);
        Estimate second = estimateRange(times, strengths, split, times.length);
        if (first.confidence() < TrackAnalysis.RELIABLE_CONFIDENCE
                || second.confidence() < TrackAnalysis.RELIABLE_CONFIDENCE)
            return new Estimate(whole.bpm(), whole.confidence() * 0.8);
        double ratio = first.bpm() / second.bpm();
        if (StrictMath.abs(ratio - 1.0) < 0.03) return whole;
        // One half can emphasize the subdivision while the other emphasizes
        // the beat. Keep the global choice, but expose the metrical ambiguity.
        if (StrictMath.abs(ratio - 2.0) < 0.06 || StrictMath.abs(ratio - 0.5) < 0.015)
            return new Estimate(whole.bpm(), whole.confidence() * 0.65);
        // This is positive evidence against a single BPM, not merely a weak
        // estimate. A whole-track fallback must not silently overrule it.
        return new Estimate(0.0, 0.0, true);
    }

    private static Estimate estimateRange(double[] times, float[] strengths, int from, int to)
    {
        if (to - from < 8 || times[to - 1] - times[from] < 4.0) return Estimate.unknown();

        double[] histogram = new double[(int) StrictMath.ceil(MAX_LAG_SEC / BIN_SEC) + 1];
        int pairs = 0;
        for (int i = from; i < to; i++)
        {
            for (int j = i + 1; j < to; j++)
            {
                double lag = times[j] - times[i];
                if (lag > MAX_LAG_SEC) break;
                int bin = (int) StrictMath.round(lag / BIN_SEC);
                double left = Math.max(0.05, strengths[i]);
                double right = Math.max(0.05, strengths[j]);
                // Correlate onset salience itself. Taking a square root here
                // overweights the many weak subdivisions relative to accents.
                histogram[bin] += left * right;
                pairs++;
            }
        }
        if (pairs < 40) return Estimate.unknown();

        int count = (int) ((MAX_BPM - MIN_BPM) / BPM_STEP) + 1;
        double[] scores = new double[count];
        int best = 0;
        for (int i = 0; i < count; i++)
        {
            scores[i] = score(histogram, MIN_BPM + i * BPM_STEP);
            if (scores[i] > scores[best]) best = i;
        }
        double[] ordered = scores.clone();
        Arrays.sort(ordered);
        double median = ordered[count / 2];
        if (scores[best] <= 0.0) return Estimate.unknown();
        // Sparse, exactly quantized click trains have a genuine zero median.
        // The small score-relative floor keeps prominence finite without
        // classifying a single coincident pair as a reliable tempo.
        double prominence = scores[best] / Math.max(median, 0.02 * scores[best]);
        if (prominence < 2.0) return Estimate.unknown();
        double confidence = Math.min(1.0, (prominence - 1.0) / 4.0);
        double bpm = MIN_BPM + best * BPM_STEP;
        double octaveAlternative = 0.0;
        if (bpm * 0.5 >= MIN_BPM) octaveAlternative = rhythmicSupport(histogram, bpm * 0.5);
        if (bpm * 2.0 <= MAX_BPM) octaveAlternative = Math.max(octaveAlternative, rhythmicSupport(histogram, bpm * 2.0));
        // A preferred tempo range can choose a meter, but is not additional
        // evidence that resolves an equally supported half/double-time grid.
        if (octaveAlternative >= 0.85 * rhythmicSupport(histogram, bpm)) confidence = Math.min(confidence, 0.65);
        return new Estimate(bpm, confidence);
    }

    private static double score(double[] histogram, double bpm)
    {
        double octavesFromCenter = StrictMath.log(bpm / 120.0) / StrictMath.log(2.0);
        return rhythmicSupport(histogram, bpm) * StrictMath.exp(-0.5 * octavesFromCenter * octavesFromCenter);
    }

    private static double rhythmicSupport(double[] histogram, double bpm)
    {
        double period = 60.0 / bpm;
        return support(histogram, period)
                + 0.5 * support(histogram, 2.0 * period)
                + 0.1 * support(histogram, 0.5 * period)
                + 0.1 * support(histogram, 1.5 * period);
    }

    private static double support(double[] histogram, double period)
    {
        if (period <= 0.0 || period > MAX_LAG_SEC) return 0.0;
        int low = Math.max(1, (int) StrictMath.floor((period - KERNEL_SEC) / BIN_SEC));
        int high = Math.min(histogram.length - 1, (int) StrictMath.ceil((period + KERNEL_SEC) / BIN_SEC));
        double sum = 0.0;
        for (int bin = low; bin <= high; bin++)
        {
            double weight = Math.max(0.0, 1.0 - StrictMath.abs(bin * BIN_SEC - period) / KERNEL_SEC);
            sum += histogram[bin] * weight;
        }
        return sum;
    }
}
