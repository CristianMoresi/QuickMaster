package com.dspark.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link AuditoryBands}. */
class AuditoryBandsTest
{
    private static final int FFT = 2048, BANDS = 72;

    private static AuditoryBands model()
    {
        AuditoryBands b = new AuditoryBands();
        b.prepare(48000.0, FFT, BANDS, 20.0, 20000.0);
        return b;
    }

    private static double widthInBins(AuditoryBands b, int band)
    {
        double z = b.getBark(band), dz = b.getSpacing();
        return (AuditoryScale.barkToHz(z + 0.5 * dz) - AuditoryScale.barkToHz(z - 0.5 * dz)) / (48000.0 / FFT);
    }

    @Test
    @DisplayName("The bands run evenly on the Bark scale between the two centres asked")
    void layout()
    {
        AuditoryBands b = model();
        assertEquals(BANDS, b.getNumBands());
        assertEquals(FFT / 2 + 1, b.getNumBins());
        assertEquals(20.0, b.getCentre(0), 1e-9);
        assertEquals(20000.0, b.getCentre(BANDS - 1), 1e-6);
        for (int i = 1; i < BANDS; i++)
        {
            assertEquals(b.getSpacing(), b.getBark(i) - b.getBark(i - 1), 1e-12, "spacing at " + i);
            assertTrue(b.getCentre(i) > b.getCentre(i - 1));
            assertEquals(b.getBark(i), AuditoryScale.hzToBark(b.getCentre(i)), 1e-9);
        }
        assertEquals(b.getCentre(0), b.getCentre(-5), 0.0, "out of range bands are clamped");
        assertEquals(b.getCentre(BANDS - 1), b.getCentre(BANDS + 5), 0.0);
    }

    @Test
    @DisplayName("A line in the wide bands gives all of its power to the two bands around it")
    void lineKeepsItsPower()
    {
        AuditoryBands b = model();
        double[] spectrum = new double[FFT / 2 + 1];
        double[] out = new double[BANDS];
        for (int bin : new int[] { 60, 128, 300, 511, 700 })
        {
            java.util.Arrays.fill(spectrum, 0.0);
            spectrum[bin] = 0.37;
            b.bandPowers(spectrum, out);
            double sum = 0.0;
            int touched = 0;
            for (double v : out)
            {
                sum += v;
                if (v > 0.0) touched++;
            }
            assertEquals(0.37, sum, 1e-12, "bin " + bin);
            assertTrue(touched <= 2, "bin " + bin + " feeds " + touched + " bands");
        }
    }

    @Test
    @DisplayName("A flat spectrum reads each band's own width, narrow bands and wide ones alike")
    void flatSpectrumReadsWidths()
    {
        AuditoryBands b = model();
        double[] spectrum = new double[FFT / 2 + 1];
        java.util.Arrays.fill(spectrum, 1.0);
        double[] out = new double[BANDS];
        b.bandPowers(spectrum, out);
        boolean sawNarrow = false, sawWide = false;
        for (int i = 1; i < BANDS - 1; i++)
        {
            double width = widthInBins(b, i);
            if (width < 2.0)
            {
                sawNarrow = true;
                assertEquals(width, out[i], 1e-9, "narrow band " + i);
            }
            else
            {
                sawWide = true;
                assertEquals(1.0, out[i] / width, 0.2, "wide band " + i + " of " + width + " bins");
            }
        }
        assertTrue(sawNarrow && sawWide, "both kinds of band are present at this size");
    }

    @Test
    @DisplayName("Several spectra at once read what each reads alone")
    void interleavedMatchesSingle()
    {
        AuditoryBands b = model();
        int bins = FFT / 2 + 1, lanes = 4;
        Random r = new Random(11);
        double[][] single = new double[lanes][bins];
        double[] together = new double[lanes * bins];
        for (int k = 0; k < bins; k++)
            for (int j = 0; j < lanes; j++)
            {
                single[j][k] = r.nextDouble();
                together[lanes * k + j] = single[j][k];
            }
        double[] out = new double[lanes * BANDS];
        b.bandPowersInterleaved(lanes, together, out);
        double[] one = new double[BANDS];
        for (int j = 0; j < lanes; j++)
        {
            b.bandPowers(single[j], one);
            for (int i = 0; i < BANDS; i++) assertEquals(one[i], out[lanes * i + j], 1e-12, "lane " + j + " band " + i);
        }
    }

    @Test
    @DisplayName("The spread across bands keeps a level, is symmetric and does nothing at zero")
    void smoothAcross()
    {
        double[] level = new double[BANDS];
        java.util.Arrays.fill(level, 3.5);
        AuditoryBands.smoothAcross(level, BANDS, 0.9);
        for (double v : level) assertEquals(3.5, v, 1e-12);

        double[] line = new double[BANDS];
        line[36] = 1.0;
        AuditoryBands.smoothAcross(line, BANDS, 0.25);
        double sum = 0.0;
        for (double v : line) sum += v;
        assertEquals(1.0, sum, 1e-9, "a line keeps its sum away from the ends");
        for (int d = 1; d < 20; d++) assertEquals(line[36 - d], line[36 + d], 1e-15, "symmetry at " + d);
        assertTrue(line[36] > line[37] && line[37] > line[38]);

        double[] untouched = { 1.0, 5.0, 2.0 };
        AuditoryBands.smoothAcross(untouched, 3, 0.0);
        assertEquals(5.0, untouched[1], 0.0);
    }

    @Test
    @DisplayName("Specific loudness is nothing in silence, rises with power and nears the power law far over the threshold")
    void specificLoudness()
    {
        AuditoryBands b = model();
        double[] power = new double[BANDS], loud = new double[BANDS], louder = new double[BANDS];
        b.specificLoudness(power, loud);
        for (int i = 0; i < BANDS; i++) assertEquals(0.0, loud[i], 0.0, "silence in band " + i);

        java.util.Arrays.fill(power, 1e-3);
        b.specificLoudness(power, loud);
        java.util.Arrays.fill(power, 2e-3);
        b.specificLoudness(power, louder);
        for (int i = 0; i < BANDS; i++)
        {
            assertTrue(louder[i] > loud[i] && loud[i] > 0.0, "band " + i);
            double law = Math.pow(1e-3 + b.getThreshold(i), b.getExponent(i)) - b.getThresholdLoudness(i);
            assertEquals(law, loud[i], 1e-15);
        }
        // Mid band, some 70 dB over its threshold: the plain power law less
        // the threshold's own loudness, a few parts in a hundred of it.
        int mid = 40;
        double plain = Math.pow(1e-3, b.getExponent(mid));
        assertTrue(b.getThreshold(mid) < 1e-9);
        assertTrue(loud[mid] < plain && loud[mid] > 0.95 * plain, loud[mid] + " against " + plain);
        assertEquals(plain - b.getThresholdLoudness(mid), loud[mid], 1e-6 * plain);

        java.util.Arrays.fill(loud, 1.0);
        assertEquals((BANDS - 1) * b.getSpacing(), b.totalLoudness(loud), 1e-12);
    }

    @Test
    @DisplayName("The reference level moves the thresholds decibel for decibel")
    void referenceLevel()
    {
        AuditoryBands b = model();
        double at100 = b.getThreshold(30);
        b.setReferenceSpl(90.0);
        assertEquals(10.0 * at100, b.getThreshold(30), 1e-12 * at100);
        assertEquals(Math.pow(b.getThreshold(30), b.getExponent(30)), b.getThresholdLoudness(30), 1e-18);
        b.setReferenceSpl(Double.NaN);
        assertEquals(10.0 * at100, b.getThreshold(30), 1e-12 * at100, "a value that is not a number is ignored");
    }
}
