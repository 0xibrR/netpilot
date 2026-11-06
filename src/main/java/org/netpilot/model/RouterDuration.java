package org.netpilot.model;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes RouterOS durations.
 *
 * <p>RouterOS uses two styles: {@code 1w2d3h4m5s} (REST API, settings) and {@code 4d06:13:16}
 * (WinBox / console). Both are accepted; an empty value means "none".
 */
public final class RouterDuration {

    /** e.g. 1w2d3h4m5s, 30m, 1h30m, with optional milliseconds (ms) that are ignored. */
    private static final Pattern UNITS = Pattern
            .compile("^(?:(\\d+)w)?(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:(\\d+)s)?(?:(\\d+)ms)?$");

    /** e.g. 06:13:16, 4d06:13:16, 1w2d00:10:00 */
    private static final Pattern CLOCK = Pattern.compile("^(?:(\\d+)w)?(?:(\\d+)d)?(\\d{1,2}):(\\d{2}):(\\d{2})$");

    private RouterDuration() {
    }

    /**
     * @return the duration, or empty for blank or unreadable text
     */
    public static Optional<Duration> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String value = text.strip();
        Matcher clock = CLOCK.matcher(value);
        if (clock.matches()) {
            return Optional.of(Duration.ofDays(7 * number(clock.group(1)) + number(clock.group(2)))
                    .plusHours(number(clock.group(3)))
                    .plusMinutes(number(clock.group(4)))
                    .plusSeconds(number(clock.group(5))));
        }
        Matcher units = UNITS.matcher(value);
        if (units.matches() && !value.isEmpty()) {
            return Optional.of(Duration.ofDays(7 * number(units.group(1)) + number(units.group(2)))
                    .plusHours(number(units.group(3)))
                    .plusMinutes(number(units.group(4)))
                    .plusSeconds(number(units.group(5))));
        }
        return Optional.empty();
    }

    /** Short human form, e.g. {@code 2d 3h}, {@code 1h 20m}, {@code 45s}, {@code 0}. */
    public static String format(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds <= 0) {
            return "0";
        }
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d" + (hours > 0 ? " " + hours + "h" : "");
        }
        if (hours > 0) {
            return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        if (minutes > 0) {
            return minutes + "m" + (secs > 0 ? " " + secs + "s" : "");
        }
        return secs + "s";
    }

    private static long number(String group) {
        return group == null ? 0 : Long.parseLong(group);
    }
}
