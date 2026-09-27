package com.quickmaster.processing.analysis;

import com.dspark.analysis.LoudnessMeter;
import com.dspark.analysis.TruePeak;
import com.quickmaster.processing.dynamics.leveler.CancellationToken;

/**
 * Complete user-facing analysis of a fully rendered master.
 *
 * <p>This class deliberately accepts only the final post-processing buffer. It
 * keeps output metering separate from the source-only musical analysis used by
 * tempo-, onset- and dynamics-aware processors.</p>
 */
public final class OutputAnalysis
{
    private OutputAnalysis() { }

    /** Immutable measurements for one post-processing render. */
    public record Result(double integratedLufs, double shortTermLufs,
                         double momentaryLufs, double loudnessRange,
                         double truePeakDbtp, double correlation,
                         double midPower, double sidePower,
                         SpectrumAnalysis spectrum)
    {
    }

    /** Measures loudness, true peak, stereo image and LTAS after the full chain. */
    public static Result measure(float[] rendered, int channels, int sampleRate)
    {
        return measure(rendered, channels, sampleRate, null);
    }

    /** Same numerical pass, cancellable between independent measurements. */
    public static Result measure(float[] rendered, int channels, int sampleRate, CancellationToken cancellation)
    {
        if (rendered == null)
            throw new IllegalArgumentException("Rendered output must not be null.");
        if ((channels != 1 && channels != 2) || rendered.length % channels != 0)
            throw new IllegalArgumentException("Require complete mono or stereo frames.");
        if (sampleRate <= 0)
            throw new IllegalArgumentException("Sample rate must be positive.");

        checkCancelled(cancellation);
        for (int i = 0; i < rendered.length; i++) {
            if ((i & 16383) == 0) checkCancelled(cancellation);
            if (!Float.isFinite(rendered[i])) throw new IllegalArgumentException("Non-finite rendered sample.");
        }
        LoudnessMeter loudness = new LoudnessMeter();
        loudness.prepare(sampleRate);
        loudness.process(rendered, channels);
        checkCancelled(cancellation);

        double truePeak = TruePeak.measureMax(rendered, channels);
        checkCancelled(cancellation);
        double truePeakDbtp = truePeak <= 0.0
                ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(truePeak);

        double correlation = 1.0;
        double midPower = 0.0;
        double sidePower = 0.0;
        int frames = rendered.length / channels;
        if (channels >= 2 && frames > 0)
        {
            double sumLR = 0.0;
            double sumL2 = 0.0;
            double sumR2 = 0.0;
            for (int frame = 0; frame < frames; frame++)
            {
                if ((frame & 16383) == 0) checkCancelled(cancellation);
                double left = rendered[frame * channels];
                double right = rendered[frame * channels + 1];
                sumLR += left * right;
                sumL2 += left * left;
                sumR2 += right * right;
                double mid = (left + right) * 0.5;
                double side = (left - right) * 0.5;
                midPower += mid * mid;
                sidePower += side * side;
            }
            double denominator = Math.sqrt(sumL2 * sumR2);
            correlation = denominator > 1e-12 ? sumLR / denominator : 1.0;
            midPower /= frames;
            sidePower /= frames;
        }
        else if (channels == 1 && frames > 0)
        {
            for (int frame = 0; frame < frames; frame++) {
                if ((frame & 16383) == 0) checkCancelled(cancellation);
                midPower += (double) rendered[frame] * rendered[frame];
            }
            midPower /= frames;
        }

        SpectrumAnalysis spectrum = new SpectrumAnalysis();
        checkCancelled(cancellation);
        spectrum.analyze(rendered, channels, sampleRate);
        checkCancelled(cancellation);

        return new Result(loudness.getIntegratedLufs(),
                loudness.getShortTermLufs(), loudness.getMomentaryLufs(),
                loudness.getLoudnessRange(), truePeakDbtp, correlation,
                midPower, sidePower, spectrum);
    }

    private static void checkCancelled(CancellationToken token)
    {
        if (Thread.currentThread().isInterrupted() || (token != null && token.isCancelled()))
            throw new java.util.concurrent.CancellationException("Output measurements superseded.");
    }
}
