package org.netpilot.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the comment NetPilot writes on every user of a batch, e.g.
 * {@code NetPilot 2026-11-06 21:56 #b062}.
 *
 * @param comment the full comment
 * @param created when the batch was sent to the router
 * @param id      the short random tag, e.g. {@code b062}
 */
public record BatchTag(String comment, LocalDateTime created, String id) {

    private static final Pattern TAG = Pattern
            .compile("^NetPilot (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}) #([0-9a-f]{4})$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * @return the tag, or empty for comments NetPilot did not write (e.g. users added by hand)
     */
    public static Optional<BatchTag> parse(String comment) {
        if (comment == null) {
            return Optional.empty();
        }
        Matcher m = TAG.matcher(comment.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BatchTag(comment.strip(), LocalDateTime.parse(m.group(1), TIME), m.group(2)));
        } catch (DateTimeParseException e) {
            return Optional.empty(); // e.g. month 13
        }
    }
}
