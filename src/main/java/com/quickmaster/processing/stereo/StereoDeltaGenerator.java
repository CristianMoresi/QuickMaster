package com.quickmaster.processing.stereo;

import com.dspark.core.OversamplingEngine;
import com.dspark.effects.Saturation;

/** New stereo from moving differential bands, never multiplication of existing Side. */
final class StereoDeltaGenerator {
    static final int BANDS_PER_CHANNEL = 8;
    private final StereoImageSettings settings;
    private final int rate, latency;
    // Eight upper bands/channel plus two low bands/channel. The bank never
    // changes when the low-frequency exclusion is toggled: only the FIR does.
    private final Band[] bands = new Band[20];
    private final PartitionedFir highPass;
    private final Harmonics harmonics;
    private final double[] dryL, dryR;
    private final double[] deltaPadding;
    private int paddingIndex;
    private int delayIndex;
    private long frame;
    double left, right;
    double rawDelta;

    StereoDeltaGenerator(StereoImageSettings settings, int rate) {
        this.settings = settings; this.rate = rate;
        for (int i = 0; i < bands.length; i++) bands[i] = new Band(i, rate);
        harmonics = settings.generates() && settings.harmonics() ? new Harmonics(rate) : null;
        int rawLatency;
        if (settings.generates() && settings.generatedLowCutHz() > 0) {
            double[] taps = PartitionedFir.lowCut(rate, settings.generatedLowCutHz());
            int block = Integer.highestOneBit(Math.max(64, (int)Math.round(rate / 93.75)));
            highPass = new PartitionedFir(taps, block);
            rawLatency = (taps.length - 1) / 2 + block + (harmonics == null ? 0 : harmonics.latency());
        } else {
            highPass = null; rawLatency = harmonics == null ? 0 : harmonics.latency();
        }
        // All supported global 2x–16x renders can compensate this delay exactly
        // in base-rate frames, without rounding a fractional dry-branch delay.
        latency = (rawLatency + 15) / 16 * 16;
        deltaPadding = new double[latency - rawLatency];
        dryL = new double[Math.max(1, latency)]; dryR = new double[dryL.length];
    }
    int latency() { return latency; }
    int harmonicLatency() { return harmonics == null ? 0 : harmonics.latency(); }
    void position(long frame) { this.frame = frame; }

    void process(double l, double r) {
        double delta = 0;
        if (settings.generates()) {
            double dl = 0, dr = 0;
            for (int i = 0; i < bands.length; i++) {
                double d = bands[i].process((i & 1) == 0 ? l : r, frame);
                if ((i & 1) == 0) dl += d; else dr += d;
            }
            delta = harmonics == null ? .5 * (dl - dr) : harmonics.process(dl, dr);
            rawDelta = delta;
            if (highPass != null) delta = highPass.process(delta);
            if (deltaPadding.length > 0) {
                double delayed = deltaPadding[paddingIndex]; deltaPadding[paddingIndex] = delta;
                paddingIndex = (paddingIndex + 1) % deltaPadding.length; delta = delayed;
            }
            // The control is the level of the NEW parallel track, not old Side.
            // Deliberately broad user range: the old maximum is now 25%.
            // Do not counteract this with a hidden full-mix gain reduction.
            delta *= 8 * settings.generationAmount();
        }
        if (latency > 0) {
            double oldL = dryL[delayIndex], oldR = dryR[delayIndex];
            dryL[delayIndex] = l; dryR[delayIndex] = r;
            delayIndex = (delayIndex + 1) % latency;
            l = oldL; r = oldR;
        }
        left = l + delta; right = r - delta; frame++;
    }

    /** TPT SVF bandpass, scaled to unity at its center; delta of a parallel bell EQ.
     * Simper 2013: https://cytomic.com/files/dsp/SvfLinearTrapOptimised2.pdf .
     * Interpolate the physical g parameter, not direct-form denominator coefficients.
     */
    private static final class Band {
        private final int index, rate;
        private final double base, corridor;
        private double s1, s2, g, gain, dg, dGain;
        private long nextUpdate = Long.MIN_VALUE;
        Band(int index, int rate) {
            this.index = index; this.rate = rate;
            double first = index < 16 ? 210 : 45;
            double last = index < 16 ? Math.min(14000, rate * .35) : 165;
            corridor = Math.log(last / first) / (index < 16 ? 15 : 3);
            base = first * Math.exp((index < 16 ? index : index - 16) * corridor);
        }
        double process(double x, long frame) {
            if (frame >= nextUpdate || frame < nextUpdate - 64) {
                double t = frame / (double)rate;
                double nowG = coefficient(t), nowGain = amplitude(t);
                double future = t + 64.0 / rate;
                g = nowG; gain = nowGain;
                dg = (coefficient(future) - nowG) / 64;
                dGain = (amplitude(future) - nowGain) / 64;
                nextUpdate = frame + 64;
            }
            // Q=2: adjacent alternating bells remain distinct. Q=.8 made their
            // overlapping boost/cut deltas nearly cancel on broadband music.
            double k = .5;
            double a = 1 / (1 + g * (g + k));
            double v1 = a * s1 + g * a * (x - s2);
            double v2 = s2 + g * v1;
            s1 = 2 * v1 - s1; s2 = 2 * v2 - s2;
            double out = gain * k * v1;
            g += dg; gain += dGain;
            return out;
        }
        private double coefficient(double t) {
            // Disjoint corridors: neighboring L/R centers cannot cross at any time.
            double frequency = base * Math.exp(.22 * corridor * Math.sin(2 * Math.PI * (.029 + index * .0017) * t + index * 2.399963));
            return Math.tan(Math.PI * frequency / rate);
        }
        private double amplitude(double t) {
            // Alternating polarities, independent slow trajectories; <=3 dB/band.
            double db = (((index / 2 + index % 2) & 1) == 0 ? 1 : -1) *
                    (2.6 + .4 * Math.sin(2 * Math.PI * (.037 + index * .0013) * t + index * 1.618034));
            return Math.expm1(db * Math.log(10) / 20);
        }
    }

    /** Delta-only DSPark saturation. 8x at normal rates, less at already high rates. */
    private static final class Harmonics {
        private static final int BLOCK = 256;
        private final OversamplingEngine os = new OversamplingEngine();
        private final Saturation tube = new Saturation(), tape = new Saturation();
        private final float[] input = new float[BLOCK * 2], output = new float[BLOCK * 2];
        private final float[] l, r;
        private final double[] dry;
        private int dryPosition;
        private int pos;
        Harmonics(int rate) {
            int factor = rate <= 48000 ? 8 : rate <= 96000 ? 4 : rate <= 192000 ? 2 : 1;
            l = new float[BLOCK * factor]; r = new float[BLOCK * factor];
            os.prepare(factor, 2, BLOCK, OversamplingEngine.Quality.MAXIMUM);
            tube.prepare((double)rate * factor, 1); tape.prepare((double)rate * factor, 1);
            tube.setAlgorithm(Saturation.Algorithm.TUBE); tape.setAlgorithm(Saturation.Algorithm.TAPE);
            tube.setDriveDb(0); tape.setDriveDb(0);
            tube.setCeiling(.08); tape.setCeiling(.08);
            dry = new double[latency()];
        }
        int latency() { return BLOCK + os.getLatencyBaseFrames(); }
        double process(double dl, double dr) {
            double shaped = .5 * (output[2 * pos] - output[2 * pos + 1]);
            double linear = dry[dryPosition]; dry[dryPosition] = .5 * (dl - dr);
            dryPosition = (dryPosition + 1) % dry.length;
            // Add a modest harmonic residual; don't flatten or attenuate the
            // differential EQ track just because the color stage has a ceiling.
            double result = linear + .1 * (shaped - linear);
            input[2 * pos] = (float)dl; input[2 * pos + 1] = (float)dr;
            if (++pos == BLOCK) {
                float[] high = os.upsample(input, BLOCK);
                for (int i = 0; i < l.length; i++) { l[i] = high[2 * i]; r[i] = high[2 * i + 1]; }
                tube.process(l, 1); tape.process(r, 1);
                for (int i = 0; i < l.length; i++) { high[2 * i] = l[i]; high[2 * i + 1] = r[i]; }
                os.downsample(high, BLOCK, output); pos = 0;
            }
            return result;
        }
    }
}
