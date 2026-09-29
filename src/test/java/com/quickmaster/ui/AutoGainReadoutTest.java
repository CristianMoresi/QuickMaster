package com.quickmaster.ui;

import org.junit.jupiter.api.Test;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

class AutoGainReadoutTest {
    @Test void inactiveCompensationIsBlankInBothAuditionModes() {
        for (boolean preview : new boolean[]{false, true})
            for (double db : new double[]{0, -6, 3})
                assertEquals("", MainController.formatAutoGainDb(false, db, preview));
    }

    @Test void unityAndValuesBelowDisplayResolutionAreBlank() {
        for (boolean preview : new boolean[]{false, true})
            for (double db : new double[]{0, -0.0, 1e-9, -1e-9, .049, -.049})
                assertEquals("", MainController.formatAutoGainDb(true, db, preview));
    }

    @Test void actualCompensationRetainsItsSignAndProvisionalMarker() {
        assertEquals("-6.0 dB", MainController.formatAutoGainDb(true, -6, false));
        assertEquals("+2.3 dB", MainController.formatAutoGainDb(true, 2.3, false));
        assertEquals("≈ -6.0 dB", MainController.formatAutoGainDb(true, -6, true));
        assertEquals("≈ +2.3 dB", MainController.formatAutoGainDb(true, 2.3, true));
        assertEquals("+0.1 dB", MainController.formatAutoGainDb(true, .05, false));
        assertEquals("-0.1 dB", MainController.formatAutoGainDb(true, -.05, false));
    }

    @Test void unavailableMeasurementsDoNotShowMisleadingText() {
        for (double db : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertEquals("", MainController.formatAutoGainDb(true, db, true));
    }

    @Test void initialFxmlReadoutIsBlankWithStableLayout() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var stream = MainController.class.getResourceAsStream("main-view.fxml")) {
            assertNotNull(stream);
            var labels = factory.newDocumentBuilder().parse(stream).getElementsByTagName("Label");
            for (int i = 0; i < labels.getLength(); i++) {
                var label = (org.w3c.dom.Element)labels.item(i);
                if ("eqAutoGainLabel".equals(label.getAttribute("fx:id"))) {
                    assertEquals("", label.getAttribute("text"));
                    assertEquals("68", label.getAttribute("minWidth"));
                    return;
                }
            }
            fail("Missing EQ Auto Gain readout");
        }
    }
}
