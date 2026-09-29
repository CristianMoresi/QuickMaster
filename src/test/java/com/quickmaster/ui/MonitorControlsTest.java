package com.quickmaster.ui;

import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

class MonitorControlsTest {
    @Test void stereoSliderValuesAreReadoutsNotDialogButtons() throws Exception {
        assertEquals(Label.class,StereoImagePane.class.getDeclaredField("lowValue").getType());
        assertEquals(Label.class,StereoImagePane.class.getDeclaredField("sideValue").getType());
    }

    @Test void monoButtonIsOffAndImmediatelyBelowTheSideMeter() throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(var stream=MainController.class.getResourceAsStream("main-view.fxml")) {
            assertNotNull(stream);
            var elements=factory.newDocumentBuilder().parse(stream).getElementsByTagName("ToggleButton");
            for(int i=0;i<elements.getLength();i++) {
                var button=(Element)elements.item(i);
                if(!"listenMono".equals(button.getAttribute("fx:id")))continue;
                assertEquals("Listen in mono",button.getAttribute("text"));
                assertNotEquals("true",button.getAttribute("selected"));
                var previous=button.getPreviousSibling();
                while(previous!=null && !(previous instanceof Element))previous=previous.getPreviousSibling();
                assertNotNull(previous);
                assertEquals("sideRow",((Element)previous).getAttribute("fx:id"));
                return;
            }
            fail("Missing mono monitor button");
        }
    }
}
