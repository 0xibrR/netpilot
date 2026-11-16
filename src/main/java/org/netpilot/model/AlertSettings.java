package org.netpilot.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * What NetPilot watches on the routers and how it tells the user.
 *
 * @param enabled           the alerts that are on
 * @param cpuPercent        CPU load that counts as high
 * @param cpuMinutes        how long it must stay high (a short spike is no alert)
 * @param memoryPercent     free memory below this share counts as low
 * @param desktop           also show a Windows notification
 * @param backgroundMinutes how often watched branches are checked in the background
 * @param watched           ids of the branches checked in the background
 */
public record AlertSettings(Set<Alert.Kind> enabled, int cpuPercent, int cpuMinutes, int memoryPercent,
        boolean desktop, int backgroundMinutes, Set<String> watched) {

    public static final int DEFAULT_CPU = 85;
    public static final int DEFAULT_CPU_MINUTES = 2;
    public static final int DEFAULT_MEMORY = 10;
    public static final int DEFAULT_BACKGROUND_MINUTES = 5;

    public AlertSettings {
        enabled = enabled == null || enabled.isEmpty() ? EnumSet.noneOf(Alert.Kind.class) : EnumSet.copyOf(enabled);
        enabled = Set.copyOf(enabled);
        cpuPercent = Math.clamp(cpuPercent, 50, 100);
        cpuMinutes = Math.clamp(cpuMinutes, 1, 60);
        memoryPercent = Math.clamp(memoryPercent, 1, 50);
        backgroundMinutes = Math.clamp(backgroundMinutes, 1, 120);
        watched = watched == null ? Set.of() : Set.copyOf(watched);
    }

    /** Every alert on, the usual thresholds, Windows notifications, no branch watched yet. */
    public static AlertSettings defaults() {
        return new AlertSettings(EnumSet.allOf(Alert.Kind.class), DEFAULT_CPU, DEFAULT_CPU_MINUTES, DEFAULT_MEMORY,
                true, DEFAULT_BACKGROUND_MINUTES, Set.of());
    }

    public boolean on(Alert.Kind kind) {
        return enabled.contains(kind);
    }

    public AlertSettings watching(String branchId, boolean watch) {
        Set<String> copy = new java.util.HashSet<>(watched);
        if (watch) {
            copy.add(branchId);
        } else {
            copy.remove(branchId);
        }
        return new AlertSettings(enabled, cpuPercent, cpuMinutes, memoryPercent, desktop, backgroundMinutes, copy);
    }
}
