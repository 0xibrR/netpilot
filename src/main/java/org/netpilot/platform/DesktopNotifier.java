package org.netpilot.platform;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.net.URL;

/**
 * Desktop notifications (the balloon by the clock on Windows, the notification centre on macOS)
 * through one tray icon for the whole app. The icon is added the first time a notification is
 * shown and removed by {@link #close()}: a tray icon left behind would keep the app running after
 * its window closed.
 */
public final class DesktopNotifier {

    private static final System.Logger LOG = System.getLogger(DesktopNotifier.class.getName());
    private static final Object LOCK = new Object();
    private static TrayIcon icon;

    /** How a notification looks. */
    public enum Level {
        INFO,
        WARNING
    }

    private DesktopNotifier() {
    }

    /** Whether this computer shows desktop notifications at all. */
    public static boolean supported() {
        try {
            return SystemTray.isSupported();
        } catch (RuntimeException | Error e) {
            return false; // headless or no desktop
        }
    }

    /** Shows a notification; does nothing where desktop notifications are not supported. */
    public static void show(String title, String message, Level level, URL iconImage) {
        if (!supported()) {
            return;
        }
        EventQueue.invokeLater(() -> {
            try {
                TrayIcon tray = tray(iconImage);
                tray.displayMessage(title, message,
                        level == Level.WARNING ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
            } catch (AWTException | RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Desktop notification not shown: {0}", e.getMessage());
            }
        });
    }

    private static TrayIcon tray(URL iconImage) throws AWTException {
        synchronized (LOCK) {
            if (icon == null) {
                Image image = Toolkit.getDefaultToolkit().getImage(iconImage);
                TrayIcon created = new TrayIcon(image, "NetPilot");
                created.setImageAutoSize(true);
                SystemTray.getSystemTray().add(created);
                icon = created;
            }
            return icon;
        }
    }

    /** Removes the tray icon, so the app can end; called when NetPilot closes. */
    public static void close() {
        synchronized (LOCK) {
            if (icon == null) {
                return;
            }
            TrayIcon removed = icon;
            icon = null;
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(removed));
        }
    }
}
