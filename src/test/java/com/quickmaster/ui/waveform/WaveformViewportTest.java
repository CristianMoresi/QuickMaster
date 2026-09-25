package com.quickmaster.ui.waveform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalDouble;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveformViewportTest
{
    @Test
    @DisplayName("D/S/V validates empty, short, full and bounded viewport states")
    void validatesInvariantsAndMinimumVisibleDuration()
    {
        assertEquals(new WaveformViewport(0, 0, 0), WaveformViewport.empty());
        assertEquals(new WaveformViewport(.1, 0, .1), WaveformViewport.fullView(.1));
        assertEquals(.1, WaveformViewport.minimumVisibleSec(.1));
        assertEquals(.25, WaveformViewport.minimumVisibleSec(120.0));
        assertEquals(2.0, WaveformViewport.minimumVisibleSec(2_048.0));

        assertThrows(IllegalArgumentException.class,
                () -> new WaveformViewport(0, 0, .1));
        assertThrows(IllegalArgumentException.class,
                () -> new WaveformViewport(120, -1, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new WaveformViewport(120, 101, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new WaveformViewport(120, 0, .2));
        assertThrows(IllegalArgumentException.class,
                () -> new WaveformViewport(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> WaveformViewport.fullView(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("Full-view coordinates exactly reproduce the previous time/pixel mapping")
    void fullViewMatchesLegacyCoordinates()
    {
        WaveformViewport viewport = WaveformViewport.fullView(120.0);
        double width = 1_000.0;
        assertEquals(0.0, viewport.timeAtX(-500, width).orElseThrow());
        assertEquals(0.0, viewport.timeAtX(0, width).orElseThrow());
        assertEquals(30.0, viewport.timeAtX(250, width).orElseThrow());
        assertEquals(120.0, viewport.timeAtX(1_000, width).orElseThrow());
        assertEquals(120.0, viewport.timeAtX(1_500, width).orElseThrow());
        assertEquals(0.0, viewport.xAtTime(0, width).orElseThrow());
        assertEquals(250.0, viewport.xAtTime(30, width).orElseThrow());
        assertEquals(1_000.0, viewport.xAtTime(120, width).orElseThrow());
    }

    @Test
    @DisplayName("xAtTime keeps offscreen positions distinct while timeAtX clamps")
    void offscreenAndClampSemantics()
    {
        WaveformViewport viewport = new WaveformViewport(120, 40, 20);
        assertEquals(-500.0, viewport.xAtTime(30, 1_000).orElseThrow());
        assertEquals(1_500.0, viewport.xAtTime(70, 1_000).orElseThrow());
        assertEquals(40.0, viewport.timeAtX(-500, 1_000).orElseThrow());
        assertEquals(60.0, viewport.timeAtX(1_500, 1_000).orElseThrow());
    }

    @Test
    @DisplayName("At least 1000 finite time/pixel round trips stay well below one pixel")
    void roundTripsAcrossResizeWidths()
    {
        WaveformViewport viewport = new WaveformViewport(120, 40, 20);
        Random random = new Random(0x445356L);
        for (int i = 0; i < 1_500; i++)
        {
            double width = 1.0 + random.nextDouble() * 4_000.0;
            double time = 40.0 + random.nextDouble() * 20.0;
            double x = viewport.xAtTime(time, width).orElseThrow();
            double roundTrip = viewport.timeAtX(x, width).orElseThrow();
            double xRoundTrip = viewport.xAtTime(roundTrip, width).orElseThrow();
            assertTrue(Math.abs(xRoundTrip - x) <= 1e-9,
                    "round-trip drifted at width=" + width + ", x=" + x);
        }
    }

    @Test
    @DisplayName("Invalid dimensions and non-finite coordinates return empty without state mutation")
    void invalidMappingInputsAreIgnored()
    {
        WaveformViewport empty = WaveformViewport.empty();
        WaveformViewport viewport = WaveformViewport.fullView(30);
        for (OptionalDouble result : new OptionalDouble[] {
                empty.timeAtX(0, 100), empty.xAtTime(0, 100),
                viewport.timeAtX(0, 0), viewport.timeAtX(Double.NaN, 100),
                viewport.xAtTime(0, Double.POSITIVE_INFINITY),
                viewport.xAtTime(Double.NaN, 100)
        })
        {
            assertFalse(result.isPresent());
        }
        assertEquals(WaveformViewport.fullView(30), viewport);
    }

    @Test
    @DisplayName("Pointer-centred zoom preserves its anchor and clamps at both limits")
    void zoomAnchorsPointerAndClamps()
    {
        WaveformViewport full = WaveformViewport.fullView(120.0);
        WaveformViewport zoomed = full.zoomAt(250.0, 1_000.0, 80.0);
        assertTrue(zoomed.visibleSec() < full.visibleSec());
        assertEquals(30.0, zoomed.timeAtX(250.0, 1_000.0).orElseThrow(), 1e-12);
        WaveformViewport farther = zoomed;
        for (int i = 0; i < 100; i++) farther = farther.zoomAt(500.0, 1_000.0, 120.0);
        assertEquals(.25, farther.visibleSec());
        for (int i = 0; i < 100; i++) farther = farther.zoomAt(500.0, 1_000.0, -120.0);
        assertEquals(full, farther);
        assertEquals(full, full.zoomAt(0, 0, 40));
        assertEquals(full, full.zoomAt(Double.NaN, 100, 40));
        assertEquals(full, full.zoomAt(0, 100, 0));
        assertEquals(WaveformViewport.empty(), WaveformViewport.empty().zoomAt(0, 100, 40));
    }

    @Test
    @DisplayName("Wheel panning moves only the view origin, in either direction at the current zoom")
    void wheelPanningPreservesDurationAndZoom()
    {
        WaveformViewport viewport = new WaveformViewport(120, 40, 20);
        WaveformViewport earlier = viewport.panByWheel(40);
        WaveformViewport later = viewport.panByWheel(-40);
        assertEquals(new WaveformViewport(120, 38, 20), earlier);
        assertEquals(new WaveformViewport(120, 42, 20), later);
        assertEquals(viewport, earlier.panByWheel(-40));
        assertEquals(39.95, viewport.panByWheel(1).startSec(), 1e-12);
        assertEquals(41.0, new WaveformViewport(120, 40, 10).panByWheel(-40).startSec());
    }

    @Test
    @DisplayName("Panning clamps to track edges and leaves empty/full views unchanged")
    void panningClampsAndIgnoresInvalidDeltas()
    {
        WaveformViewport viewport = new WaveformViewport(120, 40, 20);
        assertEquals(24, viewport.panByWheel(Double.MAX_VALUE).startSec());
        assertEquals(56, viewport.panByWheel(-Double.MAX_VALUE).startSec());
        WaveformViewport left = viewport, right = viewport;
        for (int i = 0; i < 100; i++)
        {
            left = left.panByWheel(120);
            right = right.panByWheel(-120);
        }
        assertEquals(new WaveformViewport(120, 0, 20), left);
        assertEquals(new WaveformViewport(120, 100, 20), right);
        assertEquals(WaveformViewport.fullView(120), WaveformViewport.fullView(120).panByWheel(-120));
        assertEquals(WaveformViewport.empty(), WaveformViewport.empty().panByWheel(-120));
        for (double delta : new double[] {0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertEquals(viewport, viewport.panByWheel(delta));
    }

    @Test
    @DisplayName("Fractional wheel gestures are reversible and shift every overlay by the same transform")
    void panningCoordinateTransformIsReversible()
    {
        WaveformViewport viewport = new WaveformViewport(120, 40, 20);
        Random random = new Random(0x50414eL);
        for (int i = 0; i < 1_000; i++)
        {
            double delta = (random.nextDouble() - 0.5) * 240;
            WaveformViewport moved = viewport.panByWheel(delta);
            assertEquals(viewport.startSec(), moved.panByWheel(-delta).startSec(), 1e-12);
            assertEquals(viewport.visibleSec(), moved.visibleSec());
            double shift = moved.startSec() - viewport.startSec();
            for (double time : new double[] {30, 45, 52, 70})
                assertEquals(-shift * 50, moved.xAtTime(time, 1_000).orElseThrow()
                        - viewport.xAtTime(time, 1_000).orElseThrow(), 1e-10);
        }
    }

    @Test
    @DisplayName("The pure viewport source has no JavaFX or playback dependency")
    void remainsPure() throws IOException
    {
        Path source = Path.of("src", "main", "java", "com", "quickmaster", "ui",
                "waveform", "WaveformViewport.java");
        String text = Files.readString(source);
        assertFalse(text.contains("javafx."));
        assertFalse(text.contains("ScrollEvent"));
        assertFalse(text.contains("isShortcutDown"));
        assertFalse(text.contains("AudioPlayer"));
        assertFalse(text.contains("seekTo"));
    }
}
