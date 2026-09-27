package com.quickmaster.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PresetValidationTest {
    @Test void schemaOneDefaultsAndCompleteOrdersRemainValid() {
        ChainPreset p = new ChainPreset(); PresetValidation.validate(p);
        p.chainOrder = List.of("Limit", "EQ", "Dynamics", "Clip");
        p.dynamicsOrder = List.of("beat", "punch", "leveler", "peak");
        p.bands.add(validBand()); PresetValidation.validate(p);
        for (int slope : new int[]{6, 12, 24, 36, 48}) {
            p.bands.get(0).slope = slope;
            assertDoesNotThrow(() -> PresetValidation.validate(p), "UI slope=" + slope);
        }
    }

    @Test void everyNonFiniteScalarIsRejected() throws Exception {
        for (var field : ChainPreset.class.getFields()) if (field.getType() == double.class)
            for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                ChainPreset p = new ChainPreset(); field.setDouble(p, bad);
                assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p), field.getName());
            }
        for (var field : ChainPreset.BandPreset.class.getFields()) if (field.getType() == double.class) {
            ChainPreset p = new ChainPreset(); var band = validBand(); p.bands.add(band); field.setDouble(band, Double.NaN);
            assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p), field.getName());
        }
    }

    @Test void nullMalformedAndDuplicateCollectionsAreRejected() {
        ChainPreset p = new ChainPreset(); p.mbPushDb = null;
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
        p.mbPushDb = new double[]{0, 0, Double.NaN, 0};
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
        p.mbPushDb = new double[4]; p.bands.add(null);
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
        p.bands.clear(); p.dynamicsOrder = List.of("peak", "peak", "beat", "punch");
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
        p.dynamicsOrder = List.of("peak", "beat", "leveler", "future");
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
    }

    @Test void unsupportedVersionEnumsAndCapacityAreRejected() {
        ChainPreset p = new ChainPreset(); p.version = 2;
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
        p.version = 1; p.beatNote = "UNKNOWN";
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
        p.beatNote = null;
        for (int i = 0; i < 17; i++) p.bands.add(validBand());
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p));
    }

    @Test void boundsAreCheckedWithoutMutatingInput() {
        ChainPreset p = new ChainPreset(); p.fadeInSec = 4000;
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p)); assertEquals(4000, p.fadeInSec);
        p.fadeInSec = 0; p.osFactor = 3;
        assertThrows(IllegalArgumentException.class, () -> PresetValidation.validate(p)); assertEquals(3, p.osFactor);
    }

    private static ChainPreset.BandPreset validBand() {
        var band = new ChainPreset.BandPreset(); band.frequency = 1000; band.q = .707;
        band.aboveRatio = 2; band.belowRatio = 1; band.aboveAttackMs = 5; band.belowAttackMs = 10;
        band.aboveReleaseMs = 80; band.belowReleaseMs = 120;
        return band;
    }
}
