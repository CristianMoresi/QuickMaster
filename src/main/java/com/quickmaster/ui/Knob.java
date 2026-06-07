package com.quickmaster.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rotary <b>knob</b> control (the pro-audio idiom):
 * <ul>
 *   <li><b>drag</b> vertically to change the value (Shift = fine),</li>
 *   <li><b>mouse wheel</b> to step (Shift = fine),</li>
 *   <li><b>double-click</b> to type an exact value (Hz, ms, dB … - accepts a
 *       trailing {@code k} for kilo).</li>
 * </ul>
 * Optionally {@linkplain #logarithmic() logarithmic} (for frequency) and
 * {@linkplain #scale(double) scalable} (a bigger face for prominent controls).
 * The value is a {@link DoubleProperty} for binding; the face shows a formatted
 * string while the editor takes a plain number.
 */
public final class Knob extends Region
{
    /** Formats the value for the knob face. */
    public interface Formatter { String format(double v); }

    private static final Pattern NUMBER = Pattern.compile("-?\\d*\\.?\\d+");

    private static final double BASE_W = 56, BASE_H = 70, BASE_R = 18, BASE_CY = 26;
    private static final double NAME_LINE = 12;                     // extra height for a 2nd name line
    private static final double START_ANGLE = 225, SWEEP = -270;   // 270° arc, gap at the bottom

    private double scale = 1.0;
    private double knobW = BASE_W, knobH = BASE_H, radius = BASE_R, centerY = BASE_CY;
    private final String[] nameLines;          // the name, wrapped to 1 or 2 lines
    private final double nameExtra;            // extra base height when the name needs 2 lines

    private final DoubleProperty value = new SimpleDoubleProperty();
    private double min, max;
    private boolean log = false;
    private final String name;
    private String accent = "#4a9eff";
    private Formatter formatter;

    private final Canvas canvas = new Canvas(BASE_W, BASE_H);
    private TextField editor;

    private double dragStartY, dragStartFrac;
    private final double defaultValue;

    public Knob(String name, double min, double max, double initial)
    {
        this.name = name;
        this.nameLines = wrapName(name);
        this.nameExtra = (nameLines.length > 1) ? NAME_LINE : 0.0;
        this.knobH = BASE_H + nameExtra;
        this.min = min;
        this.max = max;
        this.formatter = v -> String.format(Locale.US, "%.2f", v);

        getStyleClass().add("knob");
        getChildren().add(canvas);
        applySize();

        value.set(clamp(initial));
        this.defaultValue = clamp(initial);
        value.addListener((o, a, b) -> draw());
        disabledProperty().addListener((o, a, b) -> draw());
        installHandlers();
        draw();
    }

    /* ---- public API ---- */

    public DoubleProperty valueProperty() { return value; }
    public double getValue()              { return value.get(); }
    public void setValue(double v)        { value.set(clamp(v)); }

    public Knob formatter(Formatter f) { this.formatter = f; draw(); return this; }
    public Knob accent(String hex)     { this.accent = hex; draw(); return this; }
    public Knob logarithmic()          { this.log = true; draw(); return this; }

    /** Scales the knob face by the given factor. */
    public Knob scale(double s)
    {
        this.scale = Math.max(0.5, s);
        this.knobW = BASE_W * scale;
        this.knobH = (BASE_H + nameExtra) * scale;
        this.radius = BASE_R * scale;
        this.centerY = BASE_CY * scale;
        applySize();
        draw();
        return this;
    }

    /** Installs a hover tooltip (so the UI carries no fixed explanatory text). */
    public Knob tooltip(String text)
    {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(javafx.util.Duration.millis(250));
        tip.setShowDuration(javafx.util.Duration.INDEFINITE);   // stays until the pointer leaves
        tip.setHideDelay(javafx.util.Duration.millis(100));
        tip.setWrapText(true);
        tip.setMaxWidth(300);
        Tooltip.install(this, tip);
        return this;
    }

    public Knob range(double mn, double mx)
    {
        this.min = mn;
        this.max = mx;
        setValue(getValue());
        draw();                 // redraw the arc/pointer even if the value was unchanged
        return this;
    }

    public double getMin() { return min; }
    public double getMax() { return max; }

    /* ---- interaction ---- */

    private void applySize()
    {
        canvas.setWidth(knobW);
        canvas.setHeight(knobH);
        setMinSize(knobW, knobH);
        setPrefSize(knobW, knobH);
        setMaxSize(knobW, knobH);
        requestLayout();
    }

    private void installHandlers()
    {
        canvas.setOnMousePressed(e ->
        {
            if (e.getButton() == MouseButton.PRIMARY && e.isShortcutDown())
            {
                setValue(defaultValue);          // Ctrl/⌘-click resets to default
                e.consume();
                return;
            }
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2)
            {
                showEditor();
                e.consume();
                return;
            }
            dragStartY = e.getSceneY();
            dragStartFrac = frac();
            e.consume();
        });
        canvas.setOnMouseDragged(e ->
        {
            double dy = dragStartY - e.getSceneY();              // up = increase
            double sens = e.isShiftDown() ? 1.0 / 900.0 : 1.0 / 180.0;   // px → fraction
            setValue(valueForFrac(dragStartFrac + dy * sens));
            e.consume();
        });
        canvas.setOnScroll(e ->
        {
            double step = e.isShiftDown() ? 0.002 : 0.01;
            setValue(valueForFrac(frac() + (e.getDeltaY() >= 0 ? step : -step)));
            e.consume();
        });
    }

    private void showEditor()
    {
        if (editor == null)
        {
            editor = new TextField();
            editor.getStyleClass().add("knob-editor");
            editor.setOnAction(e -> commitEditor());
            editor.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) hideEditor(); });
            editor.focusedProperty().addListener((o, was, is) -> { if (!is) commitEditor(); });
            getChildren().add(editor);
        }
        editor.setText(plain(getValue()));
        editor.setVisible(true);
        editor.setManaged(true);
        requestLayout();
        editor.requestFocus();
        editor.selectAll();
    }

    private void commitEditor()
    {
        if (editor == null || !editor.isVisible()) return;
        setValue(parse(editor.getText(), getValue()));
        hideEditor();
    }

    private void hideEditor()
    {
        if (editor != null) { editor.setVisible(false); editor.setManaged(false); }
    }

    @Override
    protected void layoutChildren()
    {
        canvas.relocate(0, 0);
        if (editor != null && editor.isVisible())
        {
            double eh = editor.prefHeight(-1);
            editor.resizeRelocate(3, centerY - eh / 2.0, knobW - 6, eh);
        }
    }

    /* ---- value <-> arc fraction ---- */

    private double clamp(double v) { return v < min ? min : (v > max ? max : v); }

    private double frac() { return fracOf(getValue()); }

    private double fracOf(double v)
    {
        if (max <= min) return 0.0;
        v = v < min ? min : (v > max ? max : v);
        if (log && min > 0.0) return Math.log(v / min) / Math.log(max / min);
        return (v - min) / (max - min);
    }

    private double valueForFrac(double f)
    {
        f = f < 0.0 ? 0.0 : (f > 1.0 ? 1.0 : f);
        return (log && min > 0.0) ? min * Math.pow(max / min, f) : min + f * (max - min);
    }

    /** A plain editable number (base units), e.g. 1000, 80, -3.0. */
    private static String plain(double v)
    {
        return (v == Math.rint(v))
                ? String.format(Locale.US, "%.0f", v)
                : String.format(Locale.US, "%.2f", v);
    }

    /** Parses a typed value: a number, optionally with a trailing {@code k}. */
    private double parse(String s, double fallback)
    {
        if (s == null) return fallback;
        String t = s.trim().toLowerCase(Locale.US);
        double mult = t.contains("k") ? 1000.0 : 1.0;     // 1k, 1.5 kHz …
        Matcher m = NUMBER.matcher(t);
        if (m.find())
        {
            try { return clamp(Double.parseDouble(m.group()) * mult); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    /* ---- rendering ---- */

    private void draw()
    {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, knobW, knobH);
        boolean dim = isDisabled();
        double cx = knobW / 2.0;
        double f = frac();
        double lw = 5.0 * scale;

        // Track arc.
        g.setLineWidth(lw);
        g.setStroke(Color.web("#2c2c34"));
        g.strokeArc(cx - radius, centerY - radius, 2 * radius, 2 * radius,
                START_ANGLE, SWEEP, ArcType.OPEN);

        // Value arc.
        g.setStroke(Color.web(accent, dim ? 0.35 : 1.0));
        g.setLineWidth(lw);
        g.strokeArc(cx - radius, centerY - radius, 2 * radius, 2 * radius,
                START_ANGLE, SWEEP * f, ArcType.OPEN);

        // Pointer.
        double ang = Math.toRadians(START_ANGLE + SWEEP * f);
        g.setStroke(Color.web(dim ? "#5a5a63" : "#e7e7ea"));
        g.setLineWidth(2.5 * scale);
        g.strokeLine(cx, centerY, cx + Math.cos(ang) * (radius - 4 * scale),
                centerY - Math.sin(ang) * (radius - 4 * scale));

        // Value text (prominent), under the arc.
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web(dim ? "#6a6a74" : "#e7e7ea"));
        g.setFont(Font.font("System", FontWeight.BOLD, 11.0 * scale));
        g.fillText(formatter.format(getValue()), cx, centerY + radius + 12 * scale);

        // Name (one or two lines, anchored at the bottom).
        g.setFill(Color.web("#8a8a93"));
        g.setFont(Font.font(9.0 * scale));
        double lh = 11.0 * scale;
        double ny = knobH - 2 * scale - (nameLines.length - 1) * lh;
        for (String line : nameLines) { g.fillText(line, cx, ny); ny += lh; }
    }

    /** Splits a long, multi-word name into two balanced lines so it fits under the knob. */
    private static String[] wrapName(String name)
    {
        if (name == null || name.isEmpty()) return new String[0];
        if (name.length() <= 11 || name.indexOf(' ') < 0) return new String[] { name };
        int mid = name.length() / 2;
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < name.length(); i++)
        {
            if (name.charAt(i) == ' ')
            {
                int d = Math.abs(i - mid);
                if (d < bestDist) { bestDist = d; best = i; }
            }
        }
        return (best < 0) ? new String[] { name }
                          : new String[] { name.substring(0, best), name.substring(best + 1) };
    }
}
