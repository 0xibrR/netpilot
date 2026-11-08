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

    public static Path logDirectory() {
        return dataDirectory().resolve("logs");
    }

    /** Remembered router passwords, encrypted for the Windows account (only if the user asks). */
    public static Path credentialsFile() {
        return dataDirectory().resolve("credentials");
    }

    public static Path historyFile() {
        return dataDirectory().resolve("history.csv");
    }

    /** When each card was first seen used; the basis of sales reports. */
    public static Path usageFile() {
        return dataDirectory().resolve("usage.csv");
    }

    /** Points of sale (names, commission settings). */
    public static Path resellersFile() {
        return dataDirectory().resolve("resellers.csv");
    }

    /** The points of sale ledger: handovers, sales and payments, only ever appended to. */
    public static Path resellerLedgerFile() {
        return dataDirectory().resolve("reseller-ledger.csv");
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
