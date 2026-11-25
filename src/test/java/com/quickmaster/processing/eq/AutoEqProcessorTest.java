package com.quickmaster.processing.eq;
import com.quickmaster.processing.analysis.SpectrumAnalysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link AutoEqProcessor}. */
class AutoEqProcessorTest
{
    private static final int SR = 48000;

    private static float[] whiteNoise(double sec)
    {
        int n = (int) (SR * sec);
        float[] x = new float[n];
        Random r = new Random(42);
        for (int i = 0; i < n; i++) x[i] = (float) ((r.nextDouble() * 2 - 1) * 0.3);
        return x;
    }

    /** A white noise with a tone standing over it at 2.5 kHz. */
    private static float[] noiseWithATone(double sec)
    {
        float[] x = whiteNoise(sec);
        double w = 2.0 * Math.PI * 2500.0 / SR;
        for (int i = 0; i < x.length; i++) x[i] = (float) (0.5 * x[i] + 0.1 * Math.sin(w * i));
        return x;
    }

    /** Spectral tilt = level(8 kHz) - level(200 Hz), in dB. */
    private static double tilt(float[] x)
    {
        SpectrumAnalysis s = new SpectrumAnalysis();
        s.analyze(x, 1, SR);
        return s.levelDbAt(8000.0) - s.levelDbAt(200.0);
    }

    private static double rmsDb(float[] x)
    {
        double s = 0.0;
        for (float v : x) s += (double) v * v;
        return 10.0 * Math.log10(s / x.length);
    }

    private static float[] rendered(AutoEqProcessor p, float[] source)
    {
        p.prepare(SR, source.length);
        p.analyze(source, 1);
        p.prepare(SR, source.length);
        return p.process(source.clone(), 1);
    }

    private static AutoEqProcessor with(double amount, double cutBoost)
    {
        AutoEqProcessor p = new AutoEqProcessor();
        p.setEnabled(true);
        p.setAmount(amount);
        p.setCutBoost(cutBoost);
        return p;
    }

    /** The level of a tone in a signal, by its projection, over the middle of the signal. */
    private static double toneLevel(float[] x, double hz)
    {
        double w = 2.0 * Math.PI * hz / SR, s = 0.0, c = 0.0;
        for (int i = SR; i < x.length - SR; i++)
        {
            s += x[i] * Math.sin(w * i);
            c += x[i] * Math.cos(w * i);
        }
        return Math.hypot(s, c);
    }

    /** The lowest and the highest correction drawn from 30 Hz to 16 kHz, from the first second of the programme on. */
    private static double[] correctionSpan(AutoEqProcessor p, int frames)
    {
        double[] freqs = new double[160], db = new double[160];
        for (int i = 0; i < freqs.length; i++) freqs[i] = 30.0 * Math.pow(16000.0 / 30.0, i / (freqs.length - 1.0));
        double least = 0.0, most = 0.0;
        for (long position = SR; position < frames; position += SR / 8)
        {
            p.fillCorrection(freqs, position, db);
            for (double v : db)
            {
                least = Math.min(least, v);
                most = Math.max(most, v);
            }
        }
        return new double[] { least, most };
    }

    @Test
    @DisplayName("More Amount moves the tone further: a bright (white) input is darkened, with its level left in place")
    void amountDarkensABrightProgramme()
    {
        float[] noise = whiteNoise(4.0);
        double tiltIn = tilt(noise);
        float[] quarter = rendered(with(0.25, 0.0), noise), half = rendered(with(0.5, 0.0), noise), full = rendered(with(1.0, 0.0), noise);
        double tiltQuarter = tilt(quarter), tiltHalf = tilt(half), tiltFull = tilt(full);
        assertTrue(tiltFull < tiltIn - 2.0,
                "spectrum should tilt down toward the balanced shape (in=" + tiltIn + ", out=" + tiltFull + ")");
        assertTrue(tiltQuarter < tiltIn - 0.25 && tiltHalf < tiltQuarter - 0.25 && tiltFull < tiltHalf - 0.5,
                "more Amount goes further: in " + tiltIn + ", quarter " + tiltQuarter + ", half " + tiltHalf + ", full " + tiltFull);
        assertEquals(rmsDb(noise), rmsDb(full), 3.0, "in the middle of Cut/Boost the level is redistributed, not scaled away");
    }

    @Test
    @DisplayName("The correction at an amount is about that share of the correction at full, down to nothing")
    void amountIsAShareOfTheWholeCorrection()
    {
        float[] noise = noiseWithATone(5.0);
        AutoEqProcessor full = with(1.0, 0.0);
        rendered(full, noise);
        double[] whole = correctionSpan(full, noise.length);
        assertTrue(whole[0] < -4.0 && whole[1] > 1.0, "the whole correction of this programme: " + whole[0] + " to " + whole[1] + " dB");
        for (double amount : new double[] { 0.5, 0.25, 0.1, 0.02 })
        {
            AutoEqProcessor p = with(amount, 0.0);
            rendered(p, noise);
            double[] span = correctionSpan(p, noise.length);
            assertEquals(amount * whole[0], span[0], 0.2 * amount * Math.abs(whole[0]) + 0.02, "the deepest cut at " + amount);
            assertEquals(amount * whole[1], span[1], 0.2 * amount * whole[1] + 0.02, "the highest lift at " + amount);
            assertTrue(Math.max(-span[0], span[1]) <= 12.0 * amount + 0.006, "no band over " + 12.0 * amount + " dB at " + amount);
        }
    }

    @Test
    @DisplayName("Amount lowers a resonance, further as it grows and never by more than 12 dB")
    void amountLowersAResonance()
    {
        float[] noise = noiseWithATone(4.0);
        double before = toneLevel(noise, 2500.0);
        float[] half = rendered(with(0.5, 0.0), noise), full = rendered(with(1.0, 0.0), noise);
        double halfDb = 20.0 * Math.log10(toneLevel(half, 2500.0) / before);
        double fullDb = 20.0 * Math.log10(toneLevel(full, 2500.0) / before);
        assertTrue(halfDb < -2.0, "half the amount lowers the tone: " + halfDb + " dB");
        assertTrue(fullDb < halfDb - 1.0, "all of it lowers it further: " + fullDb + " against " + halfDb + " dB");
        assertTrue(fullDb > -12.5, "never past the 12 dB of the amount at full: " + fullDb + " dB");
    }

    @Test
    @DisplayName("Toward Cut what is raised fades out until nothing is; toward Boost what is lowered does")
    void cutBoostChoosesWhatTheCorrectionIsMadeOf()
    {
        float[] noise = noiseWithATone(5.0);
        double level = rmsDb(noise);
        double[][] spans = new double[5][];
        double[] levels = new double[5];
        double[] leans = { -1.0, -0.5, 0.0, 0.5, 1.0 };
        for (int i = 0; i < leans.length; i++)
        {
            AutoEqProcessor p = with(1.0, leans[i]);
            levels[i] = rmsDb(rendered(p, noise));
            spans[i] = correctionSpan(p, noise.length);
        }
        // In the middle it lowers and raises.
        assertTrue(spans[2][0] < -4.0 && spans[2][1] > 1.0, "both in the middle: " + spans[2][0] + " to " + spans[2][1] + " dB");
        // At Cut nothing is raised.
        assertEquals(0.0, spans[0][1], 0.0, "nothing raised at Cut");
        assertTrue(spans[0][0] < -4.0, "and the cuts are there: " + spans[0][0] + " dB");
        assertTrue(spans[1][1] < spans[2][1] - 0.2 && spans[1][1] > spans[0][1] + 0.2,
                "half way to Cut the raising is between the two: " + spans[1][1] + " dB");
        // At Boost nothing is lowered.
        assertEquals(0.0, spans[4][0], 0.0, "nothing lowered at Boost");
        assertTrue(spans[4][1] > 1.0, "and the lifts are there: " + spans[4][1] + " dB");
        assertTrue(spans[3][0] > spans[2][0] + 0.2 && spans[3][0] < spans[4][0] - 0.2,
                "half way to Boost the lowering is between the two: " + spans[3][0] + " dB");
        // Only lowering takes level away, only raising adds some.
        assertTrue(levels[0] < level - 0.5, "the level at Cut: " + levels[0] + " against " + level + " dB");
        assertTrue(levels[4] > level + 0.05, "the level at Boost: " + levels[4] + " against " + level + " dB");
        assertTrue(levels[0] < levels[1] && levels[1] < levels[2] && levels[2] < levels[3] && levels[3] < levels[4],
                "the level rises from Cut to Boost: " + java.util.Arrays.toString(levels));
    }

    @Test
    @DisplayName("Disabled passes through unchanged")
    void disabledPassThrough()
    {
        float[] noise = whiteNoise(2.0);
        AutoEqProcessor p = with(1.0, 0.0);
        p.prepare(SR, noise.length);
        p.analyze(noise, 1);

        p.setEnabled(false);
        p.prepare(SR, noise.length);
        float[] out = p.process(noise.clone(), 1);
        assertArrayEquals(noise, out, 0.0f);
    }

    @Test
    @DisplayName("With Amount at zero the output is the input to the bit, wherever Cut/Boost stands")
    void noAmountIsTransparent()
    {
        float[] noise = whiteNoise(3.0);
        double[] freqs = { 60.0, 1000.0, 9000.0 }, db = new double[3];
        for (double lean : new double[] { -1.0, -0.3, 0.0, 0.6, 1.0 })
        {
            AutoEqProcessor p = with(0.0, lean);
            assertArrayEquals(noise, rendered(p, noise), 0.0f, "Cut/Boost at " + lean);
            java.util.Arrays.fill(db, 9.0);
            p.fillCorrection(freqs, SR, db);
            for (double v : db) assertEquals(0.0, v, 0.0, "nothing drawn with Cut/Boost at " + lean);
            assertEquals(0.0, p.getRangeDb(), 0.0);
        }
    }

    @Test
    @DisplayName("The correction shown follows the playhead, stays within the range and is nothing without a render")
    void correctionForDisplay()
    {
        double[] freqs = { 30.0, 150.0, 1000.0, 8000.0, 16000.0 };
        double[] db = new double[freqs.length];
        AutoEqProcessor p = with(1.0, 0.0);
        java.util.Arrays.fill(db, 9.0);
        p.fillCorrection(freqs, 0L, db);
        for (double v : db) assertEquals(0.0, v, 0.0, "nothing before a render");

        float[] noise = whiteNoise(4.0);
        rendered(p, noise);
        for (long position : new long[] { -5L, 0L, SR, 3L * SR, 100L * SR })
        {
            p.fillCorrection(freqs, position, db);
            for (int i = 0; i < db.length; i++)
                assertTrue(Math.abs(db[i]) <= p.getRangeDb() + 1e-9,
                        "at " + freqs[i] + " Hz, frame " + position + ": " + db[i]);
            assertTrue(db[3] < db[1] - 1.0, "the top of a white noise is lowered against its low end: " + db[3] + " and " + db[1]);
        }
    }

    @Test
    @DisplayName("What the two controls ask of the tonal balance: half the amount in the middle is its own defaults")
    void whatTheControlsAsk()
    {
        AutoEqProcessor p = new AutoEqProcessor();
        assertEquals(0.25, AutoEqProcessor.DEFAULT_AMOUNT, 0.0);
        assertEquals(AutoEqProcessor.DEFAULT_AMOUNT, p.getAmount(), 0.0);
        assertEquals(0.0, p.getCutBoost(), 0.0);
        var defaults = p.settings();
        assertEquals(0.25, defaults.balance(), 0.0);
        assertEquals(0.25, defaults.trimPeaks(), 0.0);
        assertEquals(0.15, defaults.fillDips(), 1e-15);
        assertEquals(1.0, defaults.raising(), 0.0);
        assertEquals(1.0, defaults.lowering(), 0.0);
        assertEquals(0.25, defaults.detail(), 0.0);
        assertEquals(3.0, defaults.rangeDb(), 0.0);

        p.setAmount(0.5);
        assertEquals(TonalBalanceRender.Settings.even(0.5, 0.5, 0.3, 0.25, 6.0), p.settings(), "the tonal balance's defaults");
        p.setAmount(1.0);
        assertEquals(TonalBalanceRender.Settings.even(1.0, 1.0, 0.6, 0.25, 12.0), p.settings());
        assertEquals(12.0, p.getRangeDb(), 0.0);

        // Cut: all that it lowers and none of what it raises. Boost: the other way round.
        p.setAmount(0.5);
        p.setCutBoost(-1.0);
        assertEquals(new TonalBalanceRender.Settings(0.5, 0.5, 0.3, 0.0, 1.0, 0.25, 6.0), p.settings());
        p.setCutBoost(1.0);
        assertEquals(new TonalBalanceRender.Settings(0.5, 0.5, 0.3, 1.0, 0.0, 0.25, 6.0), p.settings());
        p.setCutBoost(-0.25);
        assertEquals(new TonalBalanceRender.Settings(0.5, 0.5, 0.3, 0.75, 1.0, 0.25, 6.0), p.settings());

        // Anywhere: Amount is the share of every correction, Cut/Boost the two shares of its directions.
        for (double amount : new double[] { 0.1, 0.5, 1.0 })
            for (double lean = -1.0; lean <= 1.0; lean += 0.125)
            {
                p.setAmount(amount);
                p.setCutBoost(lean);
                var s = p.settings();
                assertEquals(amount, s.balance(), 0.0);
                assertEquals(amount, s.trimPeaks(), 0.0);
                assertEquals(0.6 * amount, s.fillDips(), 1e-15);
                assertEquals(AutoEqProcessor.boostShare(lean), s.raising(), 0.0);
                assertEquals(AutoEqProcessor.cutShare(lean), s.lowering(), 0.0);
                assertEquals(12.0 * amount, s.rangeDb(), 1e-12);
                assertEquals(1.0, Math.max(s.raising(), s.lowering()), 0.0, "one of the two is always whole");
                assertTrue(!s.asksNothing());
            }
        assertEquals(1.0, AutoEqProcessor.cutShare(-1.0), 0.0);
        assertEquals(0.0, AutoEqProcessor.boostShare(-1.0), 0.0);
        assertEquals(0.75, AutoEqProcessor.boostShare(-0.25), 0.0);
        assertEquals(1.0, AutoEqProcessor.cutShare(0.0), 0.0);
        assertEquals(1.0, AutoEqProcessor.boostShare(0.0), 0.0);
        assertEquals(0.25, AutoEqProcessor.cutShare(0.75), 0.0);
        assertEquals(0.0, AutoEqProcessor.cutShare(3.0), 0.0);
        assertEquals(0.0, AutoEqProcessor.boostShare(-3.0), 0.0);

        p.setAmount(0.0);
        assertTrue(p.settings().asksNothing());
        p.setAmount(0.001);
        assertTrue(!p.settings().asksNothing());
    }

    @Test
    @DisplayName("It awaits a render while it is on and what it holds is not the render of its settings")
    void awaitsRenderFollowsTheSettings()
    {
        float[] noise = whiteNoise(1.0);
        AutoEqProcessor p = with(0.5, 0.0);
        assertTrue(p.awaitsRender(), "on, with nothing rendered");
        rendered(p, noise);
        assertTrue(!p.awaitsRender(), "the whole render of these settings");
        p.setAmount(0.8);
        assertTrue(p.awaitsRender(), "a control moved");
        p.setAmount(0.5);
        assertTrue(!p.awaitsRender(), "and moved back");
        p.setCutBoost(-0.4);
        assertTrue(p.awaitsRender(), "the other control moved");
        p.setCutBoost(0.0);
        assertTrue(!p.awaitsRender());
        p.setEnabled(false);
        p.setAmount(0.1);
        assertTrue(!p.awaitsRender(), "off, it plays nothing");
        p.setEnabled(true);
        assertTrue(p.awaitsRender());
        p.renderPreview(noise, 1, 0, null);
        assertTrue(p.awaitsRender(), "a provisional window is not the whole render");
        p.forgetRender();
        assertTrue(p.awaitsRender());
        float[] untouched = p.process(noise.clone(), 1);
        assertArrayEquals(noise, untouched, 0.0f, "with no render the programme passes as it is");
    }

    @Test
    @DisplayName("A provisional window is heard by position, close to the whole render once it has settled")
    void provisionalWindow()
    {
        float[] noise = noiseWithATone(12.0);
        float[] whole = rendered(with(1.0, 0.0), noise);

        int from = 6 * SR, length = (int) (2.2 * SR), heard = from + (int) (1.6 * SR), frames = SR / 2;
        AutoEqProcessor p = with(1.0, 0.0);
        p.prepare(SR, noise.length);
        p.renderPreview(java.util.Arrays.copyOfRange(noise, from, from + length), 1, from, null);
        p.setPlaybackPosition(heard);
        float[] out = p.process(java.util.Arrays.copyOfRange(noise, heard, heard + frames), 1);
        double error = 0.0, level = 0.0, change = 0.0;
        for (int i = 0; i < frames; i++)
        {
            error += Math.pow(out[i] - whole[heard + i], 2);
            level += Math.pow(whole[heard + i], 2);
            change += Math.pow(whole[heard + i] - noise[heard + i], 2);
        }
        assertTrue(change > 0.01 * level, "the whole render does change this programme");
        assertTrue(error < 0.1 * change,
                "the window stands " + 10.0 * Math.log10(error / change) + " dB under what the Auto EQ changes");

        // Outside the window the programme passes as it is.
        p.setPlaybackPosition(0);
        float[] before = p.process(java.util.Arrays.copyOfRange(noise, 0, frames), 1);
        assertArrayEquals(java.util.Arrays.copyOfRange(noise, 0, frames), before, 0.0f);

        // At an oversampled rate the same window is read between its frames.
        p.prepare(2 * SR, 2L * noise.length);
        p.setPlaybackPosition(2L * heard);
        float[] fast = p.process(new float[2 * 64], 1);
        for (int k = 0; k < 64; k++) assertEquals(out[k], fast[2 * k], 0.0f, "frame " + k + " at twice the rate");
    }

    @Test
    @DisplayName("The controls are clamped to their ranges")
    void controlsAreClamped()
    {
        AutoEqProcessor p = new AutoEqProcessor();
        p.setAmount(4.0);
        p.setCutBoost(-7.0);
        assertEquals(1.0, p.getAmount(), 0.0);
        assertEquals(-1.0, p.getCutBoost(), 0.0);
        p.setAmount(-0.5);
        p.setCutBoost(9.0);
        assertEquals(0.0, p.getAmount(), 0.0);
        assertEquals(1.0, p.getCutBoost(), 0.0);
    }
}
