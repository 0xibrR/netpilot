package org.netpilot.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One line of the router's log ({@code /log}).
 *
 * @param id      RouterOS id; grows with each new line
 * @param time    time as the router writes it, e.g. {@code 2026-11-11 10:04:12} or {@code 10:04:12}
 * @param topics  e.g. {@code hotspot}, {@code info}, {@code debug}
 * @param message the text
 */
public record LogEntry(String id, String time, List<String> topics, String message) {

    /** How serious a line is, from its topics. */
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    /** The lines a log page can show. */
    public enum Filter {

        ALL,
        HOTSPOT,
        SYSTEM,
        DHCP,
        PROBLEMS;

        public boolean test(LogEntry entry) {
            return switch (this) {
                case ALL -> true;
                case HOTSPOT -> entry.topics().contains("hotspot");
                case SYSTEM -> entry.topics().contains("system");
                case DHCP -> entry.topics().contains("dhcp");
                case PROBLEMS -> entry.severity() != Severity.INFO;
            };
        }
    }

    public LogEntry {
        Objects.requireNonNull(id, "id");
        time = time == null ? "" : time;
        topics = topics == null ? List.of() : List.copyOf(topics);
        message = message == null ? "" : message;
    }

    /** {@code hotspot,info,debug} → its topics. */
    public static List<String> topics(String commaSeparated) {
        return DnsSettings.split(commaSeparated).stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
    }

    public Severity severity() {
        if (topics.contains("error") || topics.contains("critical")) {
            return Severity.ERROR;
        }
        return topics.contains("warning") ? Severity.WARNING : Severity.INFO;
    }

    /** Search the message and the topics, ignoring case; blank matches every line. */
    public boolean matches(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return q.isEmpty() || message.toLowerCase(Locale.ROOT).contains(q) || String.join(",", topics).contains(q);
    }

    /** One line of a saved log file: time, topics, message. */
    public String line() {
        return time + "  " + String.join(",", topics) + "  " + message;
    }

    /**
     * RouterOS ids are {@code *} and a hexadecimal number that grows with each line; newer lines
     * sort after older ones even when the numbers have different lengths.
     */
    public long order() {
        try {
            return Long.parseLong(id.startsWith("*") ? id.substring(1) : id, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
