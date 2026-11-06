package org.netpilot.repository;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Well-known locations, independent of the directory the app was started from.
 */
public final class AppPaths {

    private AppPaths() {
    }

    /** {@code ~/.netpilot}: settings and history. */
    public static Path dataDirectory() {
        return userHome().resolve(".netpilot");
    }

    public static Path settingsFile() {
        return dataDirectory().resolve("settings.properties");
    }

    public static Path historyFile() {
        return dataDirectory().resolve("history.csv");
    }

    /** {@code ~/Documents/NetPilot} when a Documents folder exists, otherwise {@code ~/NetPilot}. */
    public static Path defaultOutputDirectory() {
        Path documents = userHome().resolve("Documents");
        return (Files.isDirectory(documents) ? documents : userHome()).resolve("NetPilot");
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }
}
