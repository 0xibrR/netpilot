package org.netpilot.repository;

import java.nio.file.Path;
import org.netpilot.platform.AppDirectories;

/**
 * Well-known locations, independent of the directory the app was started from.
 */
public final class AppPaths {

    private AppPaths() {
    }

    /** Settings, history, sales and ledger: {@code ~/.netpilot} on Windows (see {@link AppDirectories}). */
    public static Path dataDirectory() {
        return AppDirectories.current().data();
    }

    public static Path settingsFile() {
        return AppDirectories.current().config().resolve("settings.properties");
    }

    public static Path logDirectory() {
        return AppDirectories.current().logs();
    }

    /** Remembered router passwords, encrypted for the Windows account (only if the user asks). */
    public static Path credentialsFile() {
        return dataDirectory().resolve("credentials");
    }

    /** The update check's own settings (automatic check, last check, skipped version). */
    public static Path updateFile() {
        return dataDirectory().resolve("update.properties");
    }

    /** Where downloaded updates wait to be installed. */
    public static Path updateDownloads() {
        return dataDirectory().resolve("updates");
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

    /** Customers' phone numbers linked to cards, for WhatsApp messages; kept on this computer only. */
    public static Path customersFile() {
        return dataDirectory().resolve("customers.csv");
    }

    /** {@code ~/Documents/NetPilot} when a Documents folder exists, otherwise {@code ~/NetPilot}. */
    public static Path defaultOutputDirectory() {
        return AppDirectories.current().documents();
    }
}
