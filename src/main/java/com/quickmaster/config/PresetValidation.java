package com.quickmaster.config;

import com.dspark.effects.MasterEqualizer;
import com.dspark.effects.Saturation;
import com.quickmaster.processing.FadeProcessor;
import com.quickmaster.processing.clip.HardClipProcessor;
import com.quickmaster.processing.dynamics.BeatCompProcessor;
import com.quickmaster.processing.eq.AutoEqProcessor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure preflight: a malformed preset must not partially mutate the live chain. */
public final class PresetValidation {
    private PresetValidation() { }

    public static void validate(ChainPreset p) {
        if (p == null || p.version != 1) throw new IllegalArgumentException("Unsupported or empty preset version.");
        finiteFields(p);
        range(p.autoEqAmount, 0, 1, "Auto EQ amount");
        // Zero was the schema-v1 omitted-field default; the controls clamp it to their minimum.
        range(p.autoEqAttackSec, 0, .5, "Auto EQ attack"); range(p.autoEqReleaseSec, 0, 2, "Auto EQ release");
        enumValue(AutoEqProcessor.Target.class, p.autoEqTarget);
        range(p.fadeInSec, 0, FadeProcessor.MAX_FADE_SECONDS, "Fade in");
        range(p.fadeOutSec, 0, FadeProcessor.MAX_FADE_SECONDS, "Fade out");
        enumValue(FadeProcessor.FadeType.class, p.fadeType);
        range(p.peakCompTargetDb, -18, 0, "Peak reduction"); range(p.beatCompTargetDb, -18, 0, "Beat reduction");
        range(p.leveling, 0, 1, "Leveling"); range(p.levelerSpeed, 0, 1, "Leveler speed");
        range(p.punchAmountDb, 0, 12, "Punch"); enumValue(BeatCompProcessor.NoteValue.class, p.beatNote);
        range(p.softClipDb, 0, 12, "Soft clip"); range(p.hardClipDb, 0, 12, "Hard clip");
        enumValue(Saturation.Algorithm.class, p.softClipAlgo); enumValue(HardClipProcessor.Curve.class, p.hardClipCurve);
        if (p.mbPushDb == null || p.mbPushDb.length != 4) throw new IllegalArgumentException("Preset requires four multiband values.");
        for (double push : p.mbPushDb) range(push, 0, 6, "Multiband push");
        range(p.bbPushDb, 0, 9, "Broadband push"); range(p.normalizerTargetDbtp, -60, 0, "Peak target");
        if (p.osFactor < 1 || p.osFactor > 16 || Integer.bitCount(p.osFactor) != 1)
            throw new IllegalArgumentException("Unsupported oversampling factor.");
        order(p.chainOrder, Set.of("EQ", "Dynamics", "Clip", "Limit"), "chain");
        order(p.dynamicsOrder, Set.of("peak", "beat", "leveler", "punch"), "dynamics");
        if (p.bands == null || p.bands.size() > MasterEqualizer.DEFAULT_MAX_BANDS)
            throw new IllegalArgumentException("Invalid EQ band list.");
        for (ChainPreset.BandPreset band : p.bands) {
            if (band == null) throw new IllegalArgumentException("Null EQ band.");
            finiteFields(band);
            enumValue(MasterEqualizer.BandType.class, band.type);
            enumValue(MasterEqualizer.Channel.class, band.channel);
            enumValue(MasterEqualizer.BandPhase.class, band.phase);
            range(band.frequency, 20, 20000, "EQ frequency"); range(band.gainDb, -24, 24, "EQ gain");
            range(band.q, .1, 10, "EQ Q"); range(band.threshold, -120, 24, "EQ threshold");
            if (band.slope != 6 && band.slope != 12 && band.slope != 24 && band.slope != 36 && band.slope != 48)
                throw new IllegalArgumentException("Unsupported EQ slope.");
            range(band.aboveRangeDb, 0, 24, "EQ above range"); range(band.belowRangeDb, 0, 24, "EQ below range");
            range(band.aboveRatio, 1, 20, "EQ above ratio"); range(band.belowRatio, 1, 20, "EQ below ratio");
            range(band.aboveAttackMs, .1, 200, "EQ above attack"); range(band.belowAttackMs, .1, 200, "EQ below attack");
            range(band.aboveReleaseMs, 1, 1000, "EQ above release"); range(band.belowReleaseMs, 1, 1000, "EQ below release");
        }
    }

    private static void finiteFields(Object owner) {
        for (var field : owner.getClass().getFields()) if (field.getType() == double.class) {
            try {
                if (!Double.isFinite(field.getDouble(owner))) throw new IllegalArgumentException("Non-finite preset field: " + field.getName());
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    private static void range(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "].");
    }

    private static <E extends Enum<E>> void enumValue(Class<E> type, String value) {
        if (value != null) try { Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value); }
    }

    private static void order(List<String> values, Set<String> expected, String name) {
        if (values == null || (!values.isEmpty() &&
                (values.size() != expected.size() || !new HashSet<>(values).equals(expected))))
            throw new IllegalArgumentException("Invalid " + name + " order.");
    }
}
