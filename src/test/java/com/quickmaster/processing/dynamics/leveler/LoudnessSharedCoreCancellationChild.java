package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;

/** Fresh JVM target; cancellation is supplied externally while real product code is paused. */
public final class LoudnessSharedCoreCancellationChild
{
    public static void main(String[] args)
    {
        CancellationToken cancellation = new CancellationToken();
        boolean cancelled;
        if (args[0].equals("frames"))
        {
            float[] pcm = new float[48_000];
            java.util.Arrays.fill(pcm, 0.1f);
            cancelled = new LoudnessAnalyzer().analyze(pcm,
                    new AudioFormat(48_000, 1, pcm.length), cancellation) == null;
        }
        else
        {
            double[] blocks = new double[5_000];
            java.util.Arrays.fill(blocks, 1.0d);
            cancelled = LoudnessCore.integrated(blocks, blocks.length, new long[3], cancellation) == null;
        }
        finish(cancelled);
        if (!cancelled) throw new AssertionError("The externally cancelled measurement completed.");
    }

    public static void finish(boolean cancelled) { System.out.println("cancelled=" + cancelled); }
}
