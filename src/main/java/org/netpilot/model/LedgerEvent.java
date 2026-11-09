package org.netpilot.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One line of the points of sale ledger. The ledger is only ever added to: a mistake is corrected
 * by a new event (a return, a payment void), never by changing or deleting an old one, so the
 * whole history stays readable and the balances can always be recomputed from it.
 *
 * <p>Prices and commissions are copied into each handover when it happens. A sale takes them from
 * its handover, so later changes to package prices or to the point of sale's commission never
 * change past figures.
 *
 * @param id         unique id; an event whose id is already in the ledger is not added again
 * @param kind       what happened
 * @param at         when it happened (for a sale: the day of first use)
 * @param resellerId the point of sale
 * @param router     router address the card belongs to (empty for payments)
 * @param card       card name (empty for payments)
 * @param profile    the card's package
 * @param price      card price frozen at the handover (copied into its sale and return)
 * @param commission the point of sale's share of that price, frozen at the handover
 * @param billing    when the point of sale owes for this card, frozen at the handover
 * @param amount     money paid (payments only)
 * @param currency   currency text of price, commission and amount, e.g. {@code ل.س}
 * @param ref        the handover a sale or return belongs to, or the payment a void cancels
 * @param note       optional text, e.g. a receipt number for a payment
 */
public record LedgerEvent(String id, Kind kind, LocalDateTime at, String resellerId, String router, String card,
        String profile, BigDecimal price, BigDecimal commission, Reseller.Billing billing, BigDecimal amount,
        String currency, String ref, String note) {

    /** The four kinds of facts, plus the correction of a payment. */
    public enum Kind {
        /** A card was given to the point of sale. */
        HANDOVER,
        /** An unsold card came back (also the first half of a transfer to another point of sale). */
        RETURN,
        /** The card was used for the first time: its customer bought it. */
        SALE,
        /** The point of sale paid an amount (any amount: partial payments are normal). */
        PAYMENT,
        /** A payment entered by mistake is cancelled; the payment itself stays in the ledger. */
        PAYMENT_VOID,
        /**
         * Direct sale, step 1: a card is reserved at the counter before its receipt is printed, so
         * no other sale can take it. Not part of any point of sale's account.
         */
        DIRECT_RESERVE,
        /** Direct sale completed: the customer got the receipt and paid {@code amount}. Once per reservation. */
        DIRECT_SALE,
        /** Direct sale did not happen: the reserved card goes back to stock, nothing is charged. */
        DIRECT_RELEASE
    }

    public LedgerEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(resellerId, "resellerId");
        router = router == null ? "" : router;
        card = card == null ? "" : card;
        profile = profile == null ? "" : profile;
        price = price == null ? BigDecimal.ZERO : price;
        commission = commission == null ? BigDecimal.ZERO : commission;
        billing = billing == null ? Reseller.Billing.ON_SALE : billing;
        amount = amount == null ? BigDecimal.ZERO : amount;
        currency = currency == null ? "" : currency.strip();
        ref = ref == null ? "" : ref;
        note = note == null ? "" : note.strip();
    }

    /** What the point of sale owes for this card: its price minus its commission. */
    public BigDecimal net() {
        return price.subtract(commission);
    }
}
