package org.netpilot.model;

import org.netpilot.i18n.Messages;

/**
 * How a voucher card is laid out. Every style except {@link #CLASSIC} prints small cards in a
 * fixed grid (see {@link CardSize}) to save paper.
 */
public enum VoucherStyle {

    /** The original tall voucher; the number per row comes from the Create cards page. */
    CLASSIC,
    /** Coloured band with network name and price, the username large below. */
    MODERN,
    /** QR code on one side, the details on the other. */
    QR_SIDE,
    /** Network and price small, the username large; no QR. Smallest cards. */
    MINIMAL,
    /** The user's own card picture with the details placed on top. */
    IMAGE;

    /** Whether the style uses the fixed grid of a {@link CardSize}. */
    public boolean compact() {
        return this != CLASSIC;
    }

    @Override
    public String toString() {
        return Messages.get("voucher.style." + name());
    }
}
