package org.netpilot.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * A customer's phone number linked to one card, kept on this computer only.
 *
 * @param router   the router the card is on
 * @param card     the card (hotspot user name)
 * @param phone    international digits without {@code +}, e.g. {@code 963944123456}
 * @param linked   when the number was linked
 * @param reminded when the "ends soon" reminder was sent; empty while not sent
 */
public record Customer(String router, String card, String phone, LocalDateTime linked,
        Optional<LocalDateTime> reminded) {

    public Customer {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(linked, "linked");
        reminded = reminded == null ? Optional.empty() : reminded;
    }

    /** The key a card is found by: router and card name. */
    public String key() {
        return key(router, card);
    }

    public static String key(String router, String card) {
        return router + "|" + card;
    }

    public Customer remindedAt(LocalDateTime when) {
        return new Customer(router, card, phone, linked, Optional.of(when));
    }

    /** {@code 963944123456} → {@code +963 944 123 456}, for showing. */
    public String displayPhone() {
        return PhoneNumber.display(phone);
    }
}
