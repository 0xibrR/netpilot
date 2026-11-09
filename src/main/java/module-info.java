/**
 * NetPilot desktop application.
 *
 * <p>Being a named module lets JavaFX load from the module path, as it officially requires,
 * and prepares the app for {@code jlink}/{@code jpackage} installers.
 */
module org.netpilot {

    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires java.logging; // backs System.Logger and writes the log files (see AppLogging)
    requires java.net.http; // RouterOS REST API client

    requires tools.jackson.databind; // parses REST API responses

    requires com.github.librepdf.openpdf;
    requires com.google.zxing;
    requires com.google.zxing.javase;
    requires com.sun.jna; // native calls: macOS Keychain
    requires com.sun.jna.platform; // Windows DPAPI for a remembered router password

    // JavaFX instantiates the Application subclass reflectively
    exports org.netpilot to javafx.graphics;

    // FXMLLoader injects @FXML fields and handlers into controllers
    opens org.netpilot.controller to javafx.fxml;

    // JNA builds the Keychain bindings (org.netpilot.platform.mac) by reflection
    exports org.netpilot.platform.mac to com.sun.jna;
}
