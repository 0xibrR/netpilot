package org.netpilot.controller;

import java.util.List;
import javafx.scene.image.WritableImage;
import org.netpilot.model.ReceiptSettings;
import org.netpilot.platform.PlatformInfo;

/**
 * Talking to receipt printers: listing them and printing finished receipt pictures. Drawing the
 * receipt ({@link ReceiptPrinter#render}) is the same everywhere; only this part may differ per
 * platform (e.g. paper names of macOS/CUPS drivers later).
 */
interface ReceiptPrinting {

    /** Printer names as the system lists them, the default one first. */
    List<String> printerNames();

    /**
     * Prints each picture as its own job.
     *
     * @throws IllegalStateException with a message for the user when printing fails
     */
    void print(List<WritableImage> receipts, ReceiptSettings settings);

    /** The implementation for this computer: JavaFX printing, through the system's drivers. */
    static ReceiptPrinting forPlatform(PlatformInfo platform) {
        return switch (platform.os()) {
            // JavaFX prints through the Windows spooler and through CUPS on macOS and Linux alike
            case WINDOWS, MACOS, LINUX, OTHER -> JavaFxPrinting.INSTANCE;
        };
    }

    /** {@link ReceiptPrinter}'s JavaFX printing. */
    enum JavaFxPrinting implements ReceiptPrinting {

        INSTANCE;

        @Override
        public List<String> printerNames() {
            return ReceiptPrinter.printerNames();
        }

        @Override
        public void print(List<WritableImage> receipts, ReceiptSettings settings) {
            ReceiptPrinter.print(receipts, settings);
        }
    }
}
