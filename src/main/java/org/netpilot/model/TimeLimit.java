package org.netpilot.model;

import java.time.Duration;
import java.util.Optional;
import org.netpilot.i18n.Messages;

/**
 * A card's time limit as the user types it: any whole number and a unit. Turns into RouterOS's
 * {@code limit-uptime} text ({@code 45m}, {@code 8h}, {@code 3d}, {@code 2w}) and back.
 *
 * <p>RouterOS has no month unit, so a month is written as 30 days ({@code 1} month → {@code 30d}).
 *
 * @param amount how many units, at least 1
 * @param unit   minutes, hours, days, weeks or months
 */
public record TimeLimit(long amount, Unit unit) {

    public static final long MAX_AMOUNT = 99_999;

    public enum Unit {

        MINUTES("m", 60),
        HOURS("h", 3_600),
        DAYS("d", 86_400),
        WEEKS("w", 604_800),
        /** 30 days, written in days because RouterOS has no month unit. */
        MONTHS("d", 2_592_000);

        private final String suffix;
        private final long seconds;

        Unit(String suffix, long seconds) {
            this.suffix = suffix;
            this.seconds = seconds;
        }

        @Override
        public String toString() {
            return Messages.get("time.unit." + name());
        }
    }

    public TimeLimit {
        amount = Math.clamp(amount, 1, MAX_AMOUNT);
        unit = unit == null ? Unit.HOURS : unit;
    }

    /** RouterOS {@code limit-uptime} text, e.g. {@code 90m}, or {@code 60d} for 2 months. */
    public String routerText() {
        return unit == Unit.MONTHS ? amount * 30 + "d" : amount + unit.suffix;
    }

    /**
     * Reads saved or router text in the largest unit that keeps it exact ({@code 1d12h} → 36 hours,
     * {@code 7d} → 1 week, {@code 30d} → 1 month). Empty for blank text (no limit) or text RouterOS
     * would not accept.
     */
    public static Optional<TimeLimit> parse(String text) {
        Optional<Duration> duration = RouterDuration.parse(text);
        if (duration.isEmpty() || duration.get().toSeconds() < 60) {
            return Optional.empty();
        }
        long seconds = duration.get().toSeconds();
        for (Unit unit : new Unit[]{Unit.MONTHS, Unit.WEEKS, Unit.DAYS, Unit.HOURS}) {
            if (seconds % unit.seconds == 0) {
                return Optional.of(new TimeLimit(seconds / unit.seconds, unit));
            }
        }
        return Optional.of(new TimeLimit(Math.max(1, seconds / 60), Unit.MINUTES));
    }
}
