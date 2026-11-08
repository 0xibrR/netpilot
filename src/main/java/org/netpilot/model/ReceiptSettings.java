package org.netpilot.model;

/**
 * Where and on what paper receipts are printed. A4 PDF vouchers are separate and unchanged.
 *
 * @param printer name of the Windows printer, as the system lists it; empty for the default printer
 * @param paperMm roll width, {@value #NARROW} or {@value #WIDE} millimetres
 */
public record ReceiptSettings(String printer, int paperMm) {

    public static final int NARROW = 58;
    public static final int WIDE = 80;

    public ReceiptSettings {
        printer = printer == null ? "" : printer.strip();
        paperMm = paperMm == NARROW ? NARROW : WIDE;
    }

    public static ReceiptSettings defaults() {
        return new ReceiptSettings("", WIDE);
    }

    /**
     * Width that can be printed on, in millimetres. Thermal heads leave a margin: 80 mm rolls
     * print about 72 mm, 58 mm rolls about 48 mm.
     */
    public double printableMm() {
        return paperMm == NARROW ? 48 : 72;
    }

    /** Dots across the printable width on a common 203 dpi (8 dots/mm) thermal head. */
    public int dots() {
        return (int) Math.round(printableMm() * 8);
    }
}
