package com.quickmaster.ui;

import com.quickmaster.processing.eq.AutoEqProcessor;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the Cut/Boost control of the Auto EQ is written: the share of what it
 * would lower that it lowers, a slash, and the share of what it would raise
 * that it raises, in percent. "100/100" lowers and raises alike, "100/0"
 * lowers only, "0/100" raises only.
 */
final class CutBoostText
{
    private static final Pattern NUMBER = Pattern.compile("-?\\d*\\.?\\d+");

    private CutBoostText() { }

    /** The two shares of a Cut/Boost position (-1 lowers only, 0 alike, 1 raises only). */
    static String text(double cutBoost)
    {
        return String.format(Locale.US, "%.0f/%.0f",
                100.0 * AutoEqProcessor.cutShare(cutBoost), 100.0 * AutoEqProcessor.boostShare(cutBoost));
    }

    /**
     * The position a typed text stands for. Two numbers with a slash are the
     * two shares, the larger taken as the whole ("50/25" is "100/50"); one
     * number is the lean in percent, negative toward Cut. NaN when the text
     * holds neither.
     */
    static double value(String text)
    {
        if (text == null) return Double.NaN;
        Matcher m = NUMBER.matcher(text);
        int slash = text.indexOf('/');
        try
        {
            if (slash >= 0)
            {
                if (!m.find() || m.start() > slash) return Double.NaN;
                double cut = Math.abs(Double.parseDouble(m.group()));
                if (!m.find(slash)) return Double.NaN;
                double boost = Math.abs(Double.parseDouble(m.group()));
                if (!(cut > 0.0) && !(boost > 0.0)) return Double.NaN;
                return cut >= boost ? boost / cut - 1.0 : 1.0 - cut / boost;
            }
            if (!m.find()) return Double.NaN;
            return Math.max(-1.0, Math.min(Double.parseDouble(m.group()) / 100.0, 1.0));
        }
        catch (NumberFormatException e)
        {
            return Double.NaN;
        }
    }
}
