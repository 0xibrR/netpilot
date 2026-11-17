package org.netpilot.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.Test;
import org.netpilot.NetPilotApp;

/**
 * Every page of the side menu can be created: its view exists and the app's controller factory
 * knows its controller. A new page that is missing from the factory fails here, not in the app.
 */
class PageControllersTest {

    private static final Pattern CONTROLLER = Pattern.compile("fx:controller=\"([^\"]+)\"");

    @Test
    void theFactoryCreatesTheControllerOfEveryPage() throws Exception {
        for (PageId page : PageId.values()) {
            String fxml;
            try (InputStream in = NetPilotApp.class.getResourceAsStream(page.view())) {
                assertNotNull(in, page + ": no view " + page.view());
                fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher m = CONTROLLER.matcher(fxml);
            assertTrue(m.find(), page + ": no controller in its view");
            Class<?> type = Class.forName(m.group(1));
            FXMLLoader loader = NetPilotApp.loader(page.view(), null);
            Object controller = loader.getControllerFactory().call(type);
            assertNotNull(controller, page + ": the factory made nothing");
            assertEquals(type, controller.getClass(), page.name());
            assertTrue(controller instanceof Page, page + ": its controller is not a Page");
        }
    }
}
