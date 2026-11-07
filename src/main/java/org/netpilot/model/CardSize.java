package org.netpilot.model;

import org.netpilot.i18n.Messages;

/**
 * Size of compact voucher cards: a fixed grid on an A4 page (with 18 pt margins).
 */
public enum CardSize {

    TINY(5, 13),
    SMALL(4, 10),
    MEDIUM(3, 8);

    /** A4 in PDF points (1/72 inch). */
    public static final double PAGE_WIDTH = 595.28;
    public static final double PAGE_HEIGHT = 841.89;
    public static final double MARGIN = 18;

    private final int columns;
    private final int rows;

    CardSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int perPage() {
        return columns * rows;
    }

    /** Card width in points. */
    public double width() {
        return (PAGE_WIDTH - 2 * MARGIN) / columns;
    }

    /** Card height in points. */
    public double height() {
        return (PAGE_HEIGHT - 2 * MARGIN) / rows;
    }

    @Override
    public String toString() {
        return Messages.get("voucher.size." + name(), perPage());
    }
}
