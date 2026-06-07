package com.quickmaster.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * A thin vertical level meter that sits next to a threshold knob: it shows the
 * live detector level (filled from the bottom), a moving <b>threshold marker</b>
 * (so the value can be placed relative to the audio), and a short peak hold.
 * The portion of the level that exceeds the threshold is drawn hot (amber) so it
 * is obvious when the audio crosses the threshold. Display-only (no input).
 */
public final class LevelBar extends Region
{
    private static final double W = 14, H = 68;

    private final Canvas canvas = new Canvas(W, H);
    private double min = -60.0, max = 0.0;
    private double level = Double.NaN;
    private double levelPeak = Double.NaN;
    private double threshold = Double.NaN;

    public LevelBar()
    {
        getChildren().add(canvas);
        setMinSize(W, H);
        setPrefSize(W, H);
        setMaxSize(W, H);
        draw();
    }

    public LevelBar range(double mn, double mx) { this.min = mn; this.max = mx; draw(); return this; }

    public void setThreshold(double t) { this.threshold = t; draw(); }

    /** Sets the live level ({@code NaN} clears it); keeps a ~1 s peak hold. */
    public void setLevel(double v)
    {
        this.level = v;
        if (Double.isNaN(v))
        {
            levelPeak = Double.NaN;
        }
        else if (Double.isNaN(levelPeak) || v >= levelPeak)
        {
            levelPeak = v;
        }
        else
        {
            levelPeak -= (max - min) / 35.0;     // ~1 s hold-and-fall at ~30 fps
            if (levelPeak < v) levelPeak = v;
        }
        draw();
    }

    private double frac(double v)
    {
        if (max <= min) return 0.0;
        v = v < min ? min : (v > max ? max : v);
        return (v - min) / (max - min);
    }

    private void draw()
    {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, W, H);
        g.setFill(Color.web("#14141a"));
        g.fillRect(0, 0, W, H);
        g.setStroke(Color.web("#2c2c34"));
        g.setLineWidth(1.0);
        g.strokeRect(0.5, 0.5, W - 1, H - 1);

        if (!Double.isNaN(level))
        {
            double levelY  = H * (1.0 - frac(level));
            double threshY = Double.isNaN(threshold) ? H : H * (1.0 - frac(threshold));

            // Calm fill below the threshold, hot fill above it.
            double greenTop = Math.max(levelY, threshY);
            g.setFill(Color.web("#46d17a"));
            g.fillRect(1, greenTop, W - 2, H - greenTop - 1);
            if (levelY < threshY)
            {
                g.setFill(Color.web("#f0b14a"));
                g.fillRect(1, levelY, W - 2, threshY - levelY);
            }
        }

        if (!Double.isNaN(levelPeak))
        {
            double py = H * (1.0 - frac(levelPeak));
            g.setStroke(Color.web("#9af0ff"));
            g.setLineWidth(2.0);
            g.strokeLine(1, py, W - 1, py);
        }

        if (!Double.isNaN(threshold))
        {
            double ty = H * (1.0 - frac(threshold));
            g.setStroke(Color.WHITE);
            g.setLineWidth(1.5);
            g.strokeLine(0, ty, W, ty);
        }
    }
}
