package org.netpilot.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * NetPilot's memory of a card that has been used ("sold"). Kept even after the card is deleted
 * from the router, so past revenue does not disappear.
 *
 * @param router    router address the card belongs to, e.g. {@code 10.0.0.1:80}
 * @param username  card user name
 * @param profile   card profile, for looking up its price
 * @param batch     batch comment, or empty for users added by hand
 * @param firstUsed day NetPilot first saw the card used; empty if it was already used when
 *                  tracking started (its exact day is unknown)
 */
public record UsageRecord(String router, String username, String profile, String batch, Optional<LocalDate> firstUsed) {

    public UsageRecord {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(username, "username");
        profile = profile == null ? "" : profile;
        batch = batch == null ? "" : batch;
        firstUsed = firstUsed == null ? Optional.empty() : firstUsed;
    }
}
