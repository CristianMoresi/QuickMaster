package com.quickmaster.ui.waveform;

import java.util.OptionalDouble;

/**
 * Pure horizontal waveform viewport in seconds: duration (D), start (S) and
 * visible duration (V). Gesture handling stays in the controller; this value
 * object performs the platform-independent coordinate transform.
 */
public record WaveformViewport(double durationSec, double startSec, double visibleSec)
{
    private static final double ABSOLUTE_MIN_VISIBLE_SEC = .25;
    private static final double MAX_ZOOM_DIVISOR = 1_024.0;

    public WaveformViewport
    {
        durationSec = normalizeZero(durationSec);
        startSec = normalizeZero(startSec);
        visibleSec = normalizeZero(visibleSec);
        if (!Double.isFinite(durationSec) || !Double.isFinite(startSec)
                || !Double.isFinite(visibleSec) || durationSec < 0.0)
        {
            throw new IllegalArgumentException("Viewport values must be finite and duration non-negative.");
        }
        if (durationSec == 0.0)
        {
            if (startSec != 0.0 || visibleSec != 0.0)
            {
                throw new IllegalArgumentException("An empty track must use viewport (0,0,0).");
            }
        }
        else
        {
            double minimum = minimumVisibleSec(durationSec);
            if (visibleSec < minimum || visibleSec > durationSec)
            {
                throw new IllegalArgumentException("Visible duration is outside viewport limits.");
            }
            double maximumStart = durationSec - visibleSec;
            if (startSec < 0.0 || startSec > maximumStart)
            {
                throw new IllegalArgumentException("Viewport start is outside track bounds.");
            }
        }
    }

    public static WaveformViewport empty()
    {
        return new WaveformViewport(0.0, 0.0, 0.0);
    }

    public static WaveformViewport fullView(double durationSec)
    {
        if (!Double.isFinite(durationSec) || durationSec < 0.0)
        {
            throw new IllegalArgumentException("Track duration must be finite and non-negative.");
        }
        return durationSec == 0.0 ? empty() : new WaveformViewport(durationSec, 0.0, durationSec);
    }

    public static double minimumVisibleSec(double durationSec)
    {
        if (!Double.isFinite(durationSec) || durationSec <= 0.0)
        {
            throw new IllegalArgumentException("Track duration must be finite and positive.");
        }
        return Math.min(durationSec,
                Math.max(ABSOLUTE_MIN_VISIBLE_SEC, durationSec / MAX_ZOOM_DIVISOR));
    }

    /** Zooms around the pointer so the time below it remains stationary. */
    public WaveformViewport zoomAt(double x, double width, double wheelDelta)
    {
        if (durationSec == 0.0 || !Double.isFinite(x) || !Double.isFinite(width)
                || width <= 0.0 || !Double.isFinite(wheelDelta) || wheelDelta == 0.0)
            return this;
        double fraction = Math.max(0.0, Math.min(1.0, x / width));
        double steps = Math.max(-8.0, Math.min(8.0, wheelDelta / 40.0));
        double nextVisible = visibleSec / StrictMath.pow(1.2d, steps);
        nextVisible = Math.max(minimumVisibleSec(durationSec), Math.min(durationSec, nextVisible));
        if (nextVisible == visibleSec) return this;
        double anchorTime = startSec + fraction * visibleSec;
        double nextStart = Math.max(0.0,
                Math.min(durationSec - nextVisible, anchorTime - fraction * nextVisible));
        return new WaveformViewport(durationSec, nextStart, nextVisible);
    }

    /** Maps a pixel to absolute seconds, clamping the pixel to the useful width. */
    public OptionalDouble timeAtX(double x, double width)
    {
        if (durationSec <= 0.0 || !Double.isFinite(x)
                || !Double.isFinite(width) || width <= 0.0)
        {
            return OptionalDouble.empty();
        }
        double clampedX = Math.max(0.0, Math.min(width, x));
        return OptionalDouble.of(startSec + (clampedX / width) * visibleSec);
    }

    /** Maps absolute seconds to a pixel without clamping, preserving offscreen values. */
    public OptionalDouble xAtTime(double timeSec, double width)
    {
        if (durationSec <= 0.0 || !Double.isFinite(timeSec)
                || !Double.isFinite(width) || width <= 0.0)
        {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(((timeSec - startSec) / visibleSec) * width);
    }

    private static double normalizeZero(double value)
    {
        return value == 0.0 ? 0.0 : value;
    }
}
