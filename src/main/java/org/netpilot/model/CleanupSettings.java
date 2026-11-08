package org.netpilot.model;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Automatic daily deletion of expired cards, done by NetPilot while it is connected (so every
 * card is recorded as sold before it is deleted).
 *
 * @param enabled     whether NetPilot cleans up by itself
 * @param at          time of day for the clean-up, e.g. 03:00
 * @param lastRun     when it last ran; empty if never
 * @param lastDeleted how many cards that run deleted
 */
public record CleanupSettings(boolean enabled, LocalTime at, Optional<LocalDateTime> lastRun, int lastDeleted) {

    public static final LocalTime DEFAULT_TIME = LocalTime.of(3, 0);

    public CleanupSettings {
        at = at == null ? DEFAULT_TIME : at.withSecond(0).withNano(0);
        lastRun = lastRun == null ? Optional.empty() : lastRun;
        lastDeleted = Math.max(0, lastDeleted);
    }

    /** Off, at 03:00, never run. */
    public static CleanupSettings defaults() {
        return new CleanupSettings(false, DEFAULT_TIME, Optional.empty(), 0);
    }

    /**
     * Whether a clean-up should run now: once per day at {@link #at()}, or as soon as possible
     * after a missed time (NetPilot was closed or not connected). Never run before: due once
     * today's time has passed.
     */
    public boolean due(LocalDateTime now) {
        if (!enabled) {
            return false;
        }
        if (lastRun.isEmpty()) {
            return !now.toLocalTime().isBefore(at);
        }
        LocalDateTime last = lastRun.get();
        LocalDateTime next = last.toLocalTime().isBefore(at)
                ? last.toLocalDate().atTime(at)
                : last.toLocalDate().plusDays(1).atTime(at);
        return !now.isBefore(next);
    }

    /** The same settings after a run at {@code when} that deleted {@code deleted} cards. */
    public CleanupSettings ran(LocalDateTime when, int deleted) {
        return new CleanupSettings(enabled, at, Optional.of(when.withNano(0)), deleted);
    }

    public CleanupSettings withSchedule(boolean on, LocalTime time) {
        return new CleanupSettings(on, time, lastRun, lastDeleted);
    }
}
