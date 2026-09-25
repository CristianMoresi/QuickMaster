package com.quickmaster.processing.dynamics.leveler;

import com.quickmaster.processing.dynamics.leveler.model.AudioFormat;
import com.quickmaster.processing.dynamics.leveler.model.ShadowAnalysisSnapshot;

/** Locally synthesized harmony, bass, transient and noise texture; never annotates the detector. */
final class MusicalContextPcmFixture
{
    static final int RATE = 16_000;
    static final int SECTION_FRAMES = 60 * RATE;

    static float[] pcm()
    {
        int hop = RATE / 2;
        float[][] patterns = new float[2][hop * 2];
        for (int kind = 0; kind < 2; kind++)
        {
            int random = 0x4d435458;
            for (int n = 0; n < hop; n++)
            {
                double time = n / (double) RATE;
                double bass = .08 * StrictMath.sin(2 * StrictMath.PI * (kind == 0 ? 100 : 125) * time);
                double chord = .045 * StrictMath.sin(2 * StrictMath.PI * (kind == 0 ? 200 : 250) * time)
                        + .03 * StrictMath.sin(2 * StrictMath.PI * (kind == 0 ? 250 : 375) * time)
                        + .025 * StrictMath.sin(2 * StrictMath.PI * (kind == 0 ? 300 : 500) * time);
                random ^= random << 13; random ^= random >>> 17; random ^= random << 5;
                double noise = (random / 2147483648d) * .007 * StrictMath.exp(-time * 28d);
                double kick = .06 * StrictMath.exp(-time * 45d) * StrictMath.sin(2 * StrictMath.PI * 60d * time);
                patterns[kind][2 * n] = (float) (bass + chord + kick + noise);
                patterns[kind][2 * n + 1] = (float) (bass - .8d * chord + kick - noise);
            }
        }
        float[] pcm = new float[SECTION_FRAMES * 5 * 2];
        for (int section = 0; section < 5; section++) for (int n = 0; n < SECTION_FRAMES; n++)
        {
            int source = 2 * (n % hop), target = 2 * (section * SECTION_FRAMES + n);
            pcm[target] = patterns[section % 2][source];
            // Polarity changes the channel sketch without changing its spectral power.
            pcm[target + 1] = (section == 3 ? -1f : 1f) * patterns[section % 2][source + 1];
        }
        return pcm;
    }

    static ShadowAnalysisSnapshot analyze()
    {
        float[] pcm = pcm();
        return new LevelerAnalysisEngine().analyzeShadow(pcm,
                new AudioFormat(RATE, 2, pcm.length / 2), new CancellationToken());
    }
}
