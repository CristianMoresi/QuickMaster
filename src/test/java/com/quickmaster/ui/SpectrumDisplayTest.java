package com.quickmaster.ui;

import com.quickmaster.processing.analysis.LiveSpectrum;
import com.quickmaster.processing.analysis.SpectrumAnalysis;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumDisplayTest {
    @Test void defaultMatchesProQ4WithOneKhzPivot() {
        assertEquals(4.5, SpectrumDisplay.TILT_DB_PER_OCTAVE);
        assertEquals(-40, SpectrumDisplay.tiltedDb(1000, -40), 1e-12);
        assertEquals(-35.5, SpectrumDisplay.tiltedDb(2000, -40), 1e-12);
        assertEquals(-44.5, SpectrumDisplay.tiltedDb(500, -40), 1e-12);
        assertEquals(-31, SpectrumDisplay.tiltedDb(4000, -40), 1e-12);
    }

    @Test void pinkNoiseShowsOnePointFiveDbRiseNotSixDbCompensation() {
        double at500 = SpectrumDisplay.tiltedDb(500, -37);
        double at1000 = SpectrumDisplay.tiltedDb(1000, -40);
        double at2000 = SpectrumDisplay.tiltedDb(2000, -43);
        assertEquals(1.5, at1000 - at500, 1e-12);
        assertEquals(1.5, at2000 - at1000, 1e-12);
    }

    @Test void silenceAndOutOfBandBinsStayAtBottom() {
        for (double hz : new double[]{20, 1000, 20000})
            assertEquals(-120, SpectrumDisplay.tiltedDb(hz, -120));
        assertArrayEquals(new float[]{400, 400, 400}, new SpectrumDisplay().project(
                new double[]{20, 1000, 20000}, 3, 400, f -> -120, false));
    }

    @Test void autoRangeUsesTiltedPeakAndLiveAndStaticAgree() {
        double[] frequencies = {20, 1000, 20000};
        float[] stopped = new SpectrumDisplay().project(frequencies, 3, 400, f -> -25, false);
        float[] playing = new SpectrumDisplay().project(frequencies, 3, 400, f -> -25, true);
        assertArrayEquals(stopped, playing);
        assertEquals(30, stopped[2], 1e-4, "6 dB headroom in an 80 dB display");
        for (float y : playing) assertTrue(y >= 0 && y <= 400);
    }

    @Test void livePeakReleaseDoesNotPumpAndStoppedViewResetsIt() {
        SpectrumDisplay display = new SpectrumDisplay();
        double[] frequencies = {1000};
        assertEquals(30, display.project(frequencies, 1, 400, f -> -10, true)[0], 1e-4);
        assertEquals(79.75, display.project(frequencies, 1, 400, f -> -20, true)[0], 1e-4);
        assertEquals(30, display.project(frequencies, 1, 400, f -> -20, false)[0], 1e-4);
    }

    @Test void projectionCannotChangePcmOrStaticAndLiveMeasurements() {
        float[] pcm = new float[32768];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (.3 * Math.sin(2 * Math.PI * 1000 * i / 48000));
        float[] original = pcm.clone();
        SpectrumAnalysis analysis = new SpectrumAnalysis(); analysis.analyze(pcm, 1, 48000);
        LiveSpectrum live = new LiveSpectrum(); live.setSampleRate(48000); live.push(pcm, 1); live.update();
        double[] frequencies = {20, 500, 1000, 2000, 20000};
        double[] raw = new double[frequencies.length], liveRaw = raw.clone();
        for (int i = 0; i < raw.length; i++) {
            raw[i] = analysis.levelDbAt(frequencies[i]); liveRaw[i] = live.levelDbAt(frequencies[i]);
        }
        SpectrumDisplay display = new SpectrumDisplay();
        display.project(frequencies, frequencies.length, 400, analysis::levelDbAt, false);
        display.project(frequencies, frequencies.length, 400, live::levelDbAt, true);
        assertArrayEquals(original, pcm);
        for (int i = 0; i < raw.length; i++) {
            assertEquals(raw[i], analysis.levelDbAt(frequencies[i]));
            assertEquals(liveRaw[i], live.levelDbAt(frequencies[i]));
        }
    }
}
