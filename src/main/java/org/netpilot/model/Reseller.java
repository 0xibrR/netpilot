package org.netpilot.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.netpilot.i18n.Messages;

/**
 * A point of sale that receives cards to sell. Its settings can change at any time: every
 * handover keeps the price and commission of its own moment in the ledger, so a change only
 * affects cards handed over later (see {@link LedgerEvent}).
 *
 * @param id              stable id, never reused
 * @param name            shop or person name
 * @param phone           optional contact number
 * @param commissionType  a percentage of the card price, or a fixed amount per card
 * @param commissionValue the percentage (e.g. 10) or the amount per card (e.g. 100)
 * @param billing         whether the point of sale owes for a card when it is sold or when received
 */
public record Reseller(String id, String name, String phone, CommissionType commissionType,
        BigDecimal commissionValue, Billing billing) {

    public static final int MAX_NAME = 60;

    /** How the point of sale's share is counted. */
    public enum CommissionType {

        PERCENT,
        PER_CARD;

        @Override
        public String toString() {
            return Messages.get("reseller.commission." + name());
        }
    }

    /** When the point of sale owes for a card. */
    public enum Billing {

        /** Only once the card's customer has used it (first use = sale). */
        ON_SALE,
        /** As soon as the card is handed over. */
        ON_HANDOVER;

        @Override
        public String toString() {
            return Messages.get("reseller.billing." + name());
        }
    }

    public Reseller {
        Objects.requireNonNull(id, "id");
        name = name == null ? "" : name.strip();
        phone = phone == null ? "" : phone.strip();
        commissionType = commissionType == null ? CommissionType.PERCENT : commissionType;
        commissionValue = commissionValue == null ? BigDecimal.ZERO : commissionValue.stripTrailingZeros();
        billing = billing == null ? Billing.ON_SALE : billing;
    }

    /** The name, as lists and choice boxes show it. */
    @Override
    public String toString() {
        return name;
    }

    /** The commission on one card of this price, by today's settings (frozen at each handover). */
    public BigDecimal commissionOn(BigDecimal price) {
        return switch (commissionType) {
            case PERCENT -> price.multiply(commissionValue).divide(BigDecimal.valueOf(100), 2,
                    java.math.RoundingMode.HALF_UP).stripTrailingZeros();
            case PER_CARD -> commissionValue;
        };
    }

    /** @return human-readable problems, empty when the point of sale can be saved */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (name.isEmpty() || name.length() > MAX_NAME || name.contains("\n")) {
            errors.add(Messages.get("reseller.validation.name", MAX_NAME));
        }
        if (commissionValue.signum() < 0
                || (commissionType == CommissionType.PERCENT
                        && commissionValue.compareTo(BigDecimal.valueOf(100)) > 0)) {
            errors.add(Messages.get("reseller.validation.commission"));
        }
        return errors;
    }
}
