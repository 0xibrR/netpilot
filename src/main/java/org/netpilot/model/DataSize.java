package org.netpilot.model;

import java.util.Locale;

/**
 * Byte counts written the way WinBox shows them, so numbers can be compared side by side:
 * binary units (1 KiB = 1024 B) and a larger unit only from five of it ({@code 4905.9 MiB},
 * then {@code 5.9 GiB}).
 */
public final class DataSize {

    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};
    private static final int SWITCH_AT = 5;

    private DataSize() {
    }

    /** {@code 0 B}, {@code 3000 B}, {@code 524.3 MiB}, {@code 48.4 GiB}. */
    public static String format(long bytes) {
        if (bytes < (long) SWITCH_AT * 1024) {
            return Math.max(0, bytes) + " B";
        }
        double value = bytes;
        int unit = 0;
        while (unit < UNITS.length - 1 && value >= SWITCH_AT * 1024.0) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
