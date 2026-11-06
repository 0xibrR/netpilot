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

    requires com.github.librepdf.openpdf;
    requires com.google.zxing;
    requires com.google.zxing.javase;

    // JavaFX instantiates the Application subclass reflectively
    exports org.netpilot to javafx.graphics;

    // FXMLLoader injects @FXML fields and handlers into controllers
    opens org.netpilot.controller to javafx.fxml;
}
