package com.quickmaster.ui;

import com.quickmaster.ui.waveform.WaveformViewport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static integration guard for the M-003 full-view wiring (headless-safe). */
class MainControllerWaveformIntegrationTest
{
    private static final Path CONTROLLER = Path.of("src", "main", "java", "com",
            "quickmaster", "ui", "MainController.java");

    @Test
    @DisplayName("Waveform, scrub, selection, playhead and both fades use the shared viewport")
    void allExistingWaveformRoutesUseOneTransformation() throws IOException
    {
        String source = Files.readString(CONTROLLER);
        assertTrue(source.contains("private WaveformViewport waveformViewport"));
        assertTrue(source.contains("WaveformViewport.fullView(loadedFile.getDuration())"));

        String press = method(source, "onWaveformMousePressed(MouseEvent e)");
        String drag = method(source, "onWaveformMouseDragged(MouseEvent e)");
        String secAtX = method(source, "secAtX(double x)");
        String seek = method(source, "seekFromMouseX(double x)");
        String scroll = method(source, "onWaveformScroll(ScrollEvent e)");
        String downsample = method(source, "private void downsampleForDisplay()");
        String draw = method(source, "private void drawWaveform()");

        assertTrue(press.contains("waveformXAtTime(fade.getFadeInSec())"));
        assertTrue(press.contains("waveformXAtTime(dur - fade.getFadeOutSec())"));
        assertTrue(drag.contains("secAtX(e.getX())"));
        assertTrue(secAtX.contains("waveformViewport.timeAtX"));
        assertTrue(seek.contains("waveformViewport.timeAtX"));
        assertTrue(scroll.contains("waveformXAtTime(fade.getFadeInSec())"));
        assertTrue(scroll.contains("waveformXAtTime(dur - fade.getFadeOutSec())"));
        assertTrue(downsample.contains("waveformPeakIndex.columns(waveformViewport"),
                "Repeated zoom redraws must use the bounded peak lookup");
        assertTrue(occurrences(draw, "waveformXAtTime(") >= 5,
                "draw must route fades, selection endpoints and playhead through xAtTime");

        for (String route : new String[] { press, drag, secAtX, seek, scroll })
        {
            assertFalse(route.contains("e.getX() /"));
            assertFalse(route.contains("/ dur"));
            assertFalse(route.contains("/ loadedFile.getDuration()"));
        }
        assertFalse(draw.contains("selStartSec") && draw.contains("/ dur"));
        assertFalse(draw.contains("pos / (double) total"));
    }

    @Test
    @DisplayName("Full-view coordinates remain the previous coordinates for every overlay and hit-test")
    void fullViewCoordinatesRemainObservableEquivalent()
    {
        WaveformViewport viewport = WaveformViewport.fullView(120.0);
        double width = 1_000.0;

        assertEquals(41.666666666666664,
                viewport.xAtTime(5.0, width).orElseThrow());        // fade-in
        assertEquals(916.6666666666666,
                viewport.xAtTime(120.0 - 10.0, width).orElseThrow()); // fade-out start
        assertEquals(350.0,
                viewport.xAtTime(42.0, width).orElseThrow());       // selection start
        assertEquals(483.3333333333333,
                viewport.xAtTime(58.0, width).orElseThrow());       // selection end
        assertEquals(416.6666666666667,
                viewport.xAtTime(50.0, width).orElseThrow());       // playhead
        assertEquals(50.0,
                viewport.timeAtX(416.6666666666667, width).orElseThrow(), 1e-12); // scrub
    }

    @Test
    @DisplayName("Wheel pans, platform shortcut zooms, and only Alt-wheel can edit fade curves")
    void zoomGestureAndFadeWheelDoNotConflict() throws IOException
    {
        String source = Files.readString(CONTROLLER);
        String scroll = method(source, "onWaveformScroll(ScrollEvent e)");
        assertTrue(scroll.contains("e.isAltDown() && (fadeDragMode != 0 || nearHandle)"));
        assertTrue(scroll.contains("fade.cycleFadeType()"));
        assertTrue(scroll.contains("e.consume()"));
        assertTrue(scroll.contains("e.isShortcutDown()"));
        assertTrue(scroll.contains("waveformViewport.zoomAt("));
        assertTrue(scroll.contains("waveformViewport.panByWheel(delta)"));
        assertTrue(scroll.contains("e.getDeltaX()"), "Horizontal trackpad scrolling must work too");
        assertTrue(scroll.indexOf("e.isShortcutDown()") < scroll.indexOf("fade.cycleFadeType()"));
        assertFalse(scroll.contains("player."));
        assertFalse(scroll.contains("seekFromMouseX"));
        assertFalse(scroll.contains("seekTo"));
        assertFalse(scroll.contains("selStartSec ="));
        assertFalse(scroll.contains("selEndSec ="));
        assertTrue(source.contains("WaveformPeakIndex"));
        assertFalse(source.contains("TimelineEditRebaser"));
        assertFalse(source.contains("WaveformGestureAdapter"));
    }

    private static String method(String source, String signature)
    {
        int signatureIndex = source.indexOf(signature);
        assertTrue(signatureIndex >= 0, "missing method " + signature);
        int open = source.indexOf('{', signatureIndex);
        assertTrue(open >= 0, "missing body " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++)
        {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("unterminated method " + signature);
    }

    private static int occurrences(String text, String needle)
    {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }
}
