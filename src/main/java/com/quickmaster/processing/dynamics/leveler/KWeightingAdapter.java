package com.quickmaster.processing.dynamics.leveler;

import java.util.Arrays;
import java.util.BitSet;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ChannelLayout;

/** Maps complete shared-core windows onto the source-start-indexed 100 ms timeline. */
public final class KWeightingAdapter
{
    private KWeightingAdapter() { }

    public static boolean fillWindowPowers(float[] pcm, AudioFormat format,
                                            int momentaryWindowFrames, int shortTermWindowFrames,
                                            int hopFrames, double[] momentaryPower, BitSet momentaryValid,
                                            double[] shortTermPower, BitSet shortTermValid,
                                            CancellationToken cancellation)
    {
        if (cancellation != null && cancellation.isCancelled()) return false;
        if (pcm == null || format == null || momentaryPower == null || shortTermPower == null
                || momentaryValid == null || shortTermValid == null
                || momentaryPower == shortTermPower || momentaryValid == shortTermValid)
            throw new IllegalArgumentException("Distinct window arrays and masks are required.");
        int rate = format.sampleRateHz();
        long shortFrames = StrictMath.round(3.0d * rate);
        int expectedHop = Math.max(1, (int) StrictMath.round(0.1d * rate));
        int expectedMomentary = Math.max(1, (int) StrictMath.round(0.4d * rate));
        long count = (format.frames() - 1L) / expectedHop + 1L;
        if (shortFrames > Integer.MAX_VALUE || momentaryWindowFrames != expectedMomentary
                || shortTermWindowFrames != shortFrames || hopFrames != expectedHop
                || momentaryPower.length != count || shortTermPower.length != count
                || momentaryValid.length() != 0 || shortTermValid.length() != 0
                || pcm.length != format.frames() * format.channels())
            throw new IllegalArgumentException("Invalid source-clock window dimensions.");
        for (int i = 0; i < momentaryPower.length; i++)
        {
            if ((i & 4095) == 0 && cancellation != null && cancellation.isCancelled()) return false;
            if (!Double.isFinite(momentaryPower[i]) || momentaryPower[i] < 0.0d
                    || !Double.isFinite(shortTermPower[i]) || shortTermPower[i] < 0.0d)
                throw new IllegalArgumentException("Invalid window output storage.");
        }
        Arrays.fill(momentaryPower, 0.0d);
        Arrays.fill(shortTermPower, 0.0d);
        LoudnessCore core = new LoudnessCore(rate,
                format.channels() == 1 ? ChannelLayout.MONO_MAIN : ChannelLayout.STEREO_LR);
        for (int frame = 0; frame < format.frames(); frame++)
        {
            if ((frame & 1023) == 0 && cancellation != null && cancellation.isCancelled()) return false;
            core.acceptFrame(pcm, frame * format.channels());
            long completed = core.framesSeen();
            if (completed >= momentaryWindowFrames)
            {
                long start = completed - momentaryWindowFrames;
                if (start % hopFrames == 0L)
                {
                    int index = (int) (start / hopFrames);
                    momentaryPower[index] = core.momentaryPower();
                    momentaryValid.set(index);
                }
            }
            if (completed >= shortTermWindowFrames)
            {
                long start = completed - shortTermWindowFrames;
                if (start % hopFrames == 0L)
                {
                    int index = (int) (start / hopFrames);
                    shortTermPower[index] = core.shortTermPower();
                    shortTermValid.set(index);
                }
            }
        }
        return cancellation == null || !cancellation.isCancelled();
    }
}
