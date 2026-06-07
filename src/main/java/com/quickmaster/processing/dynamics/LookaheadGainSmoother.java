package com.quickmaster.processing.dynamics;

/**
 * Converts a per-sample <i>target gain</i> signal into a smooth gain envelope
 * with <b>look-ahead, near-instant attack</b> and exponential release.
 * <p>
 * Because the entire target-gain signal is known in advance, the envelope can
 * be pulled down <i>before</i> a peak arrives. Two steps achieve that:
 * <ol>
 *   <li><b>Forward look-ahead minimum.</b> Each output sample is constrained by
 *       the minimum target gain over a short forward window
 *       ({@code lookaheadMs}), so the gain starts dropping a couple of
 *       milliseconds early and is already at its target when the peak lands.</li>
 *   <li><b>One-pole smoothing.</b> A fast attack coefficient (going down) and a
 *       configurable release coefficient (coming back up) shape the transitions
 *       so they are click-free, while the look-ahead keeps the effect "instant".</li>
 * </ol>
 * The target gain is linear and &le; 1 (1 = no reduction); the result is &le; 1.
 * The forward sliding minimum is computed in O(n) with a monotonic deque.
 */
public final class LookaheadGainSmoother
{
    private LookaheadGainSmoother() { }

    /**
     * Smooths a target-gain signal.
     *
     * @param targetGain  per-sample desired linear gain (&le; 1), one per frame
     * @param sampleRate  sample rate of the signal in Hz
     * @param lookaheadMs forward look-ahead window in ms (e.g. 2.0)
     * @param attackMs    attack time constant in ms (small, e.g. 0.5)
     * @param releaseMs   release time constant in ms (e.g. 100)
     * @return a new array of the same length with the smoothed gain envelope
     */
    public static float[] smooth(float[] targetGain, double sampleRate,
                                 double lookaheadMs, double attackMs, double releaseMs)
    {
        int n = (targetGain == null) ? 0 : targetGain.length;
        float[] out = new float[n];
        if (n == 0) return out;

        int look = Math.max(1, (int) Math.round(lookaheadMs * sampleRate / 1000.0));
        float[] fmin = forwardSlidingMin(targetGain, look);

        double atk = Math.exp(-1.0 / (sampleRate * Math.max(attackMs, 0.01) / 1000.0));
        double rel = Math.exp(-1.0 / (sampleRate * Math.max(releaseMs, 0.01) / 1000.0));

        double env = 1.0;
        for (int i = 0; i < n; i++)
        {
            double t = fmin[i];
            double c = (t < env) ? atk : rel;      // fast down, slow up
            env = t + c * (env - t);
            out[i] = (float) env;
        }
        return out;
    }

    /**
     * Forward sliding minimum: {@code res[i] = min(a[i .. min(i+look, n-1)])},
     * in O(n) via a monotonic deque. Implemented as a standard backward window
     * minimum of size {@code look+1} read at position {@code i+look}.
     */
    static float[] forwardSlidingMin(float[] a, int look)
    {
        int n = a.length;
        float[] g = new float[n];          // g[p] = min over [p-look, p]
        int[] dq = new int[n];
        int head = 0, tail = 0;
        for (int p = 0; p < n; p++)
        {
            while (tail > head && a[dq[tail - 1]] >= a[p]) tail--;
            dq[tail++] = p;
            if (dq[head] < p - look) head++;
            g[p] = a[dq[head]];
        }
        float[] res = new float[n];
        for (int i = 0; i < n; i++)
        {
            int q = Math.min(i + look, n - 1);
            res[i] = g[q];
        }
        return res;
    }
}
