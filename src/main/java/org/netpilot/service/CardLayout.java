package org.netpilot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.netpilot.model.CardImage;
import org.netpilot.model.DataLimit;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.VoucherStyle;
import org.netpilot.model.VoucherTemplate;

/**
 * Where everything goes on a compact voucher card. The PDF and the on-screen preview both draw
 * these elements, so the preview always matches the print.
 *
 * <p>Coordinates are PDF points with the origin at the card's top-left corner. Text positions are
 * baselines; {@link Align} says which edge of the text the x coordinate is.
 */
public final class CardLayout {

    /** Something drawn on a card. */
    public sealed interface Element permits Fill, Line, Text, Qr, Background, Logo, Box {
    }

    /** A rounded, half-transparent box behind text on the user's picture. */
    public record Box(double x, double y, double width, double height, String color, double opacity)
            implements
                Element {
    }

    /** The network logo, fitted inside this box keeping its proportions. */
    public record Logo(double x, double y, double width, double height) implements Element {
    }

    /** A filled rectangle (the coloured band). */
    public record Fill(double x, double y, double width, double height, String color) implements Element {
    }

    /** A thin dashed separator. */
    public record Line(double x1, double y1, double x2, double y2, String color) implements Element {
    }

    /** One line of text. */
    public record Text(String text, double x, double baseline, double size, String color, Align align, Slot slot)
            implements
                Element {
    }

    /** The QR login code (a square). */
    public record Qr(double x, double y, double size, Slot slot) implements Element {
    }

    /** The user's card picture, stretched over the whole card. */
    public record Background(double x, double y, double width, double height) implements Element {
    }

    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    /** Parts the user can drag on their own card picture. */
    public enum Slot {
        NONE,
        USERNAME,
        PASSWORD,
        QR,
        PRICE
    }

    /** Text width in points for a font size, as the drawing side measures it. */
    @FunctionalInterface
    public interface Measure {

        double width(String text, double size);
    }

    /** The details of one card. */
    public record Card(String username, String password, String profile, String time, Optional<String> price,
            boolean qr) {

        /** @param withQr whether a QR code is printed (needs a login URL and the QR option) */
        public static Card of(HotspotUser user, VoucherTemplate template, boolean withQr) {
            return new Card(user.username(), user.password(), template.showProfile() ? user.profile() : "",
                    // time and data limits share the "time" line, e.g. "8h · 1 GB"
                    template.showUptime()
                            ? joined(user.limitUptime(), user.dataLimit().map(DataLimit::label).orElse(""))
                            : "",
                    template.showPrice() ? template.priceFor(user.profile()) : Optional.empty(),
                    withQr && template.style() != VoucherStyle.MINIMAL);
        }

        /** Username and password the same (number-only cards): print it once. */
        boolean singleCode() {
            return password.isEmpty() || password.equals(username);
        }
    }

    private static final String WHITE = "#FFFFFF";
    private static final String DARK = "#14181F";
    private static final String MUTED = "#5B6573";
    private static final String DASH = "#B8C0CC";

    private final VoucherTemplate template;
    private final Measure measure;
    private final double width;
    private final double height;
    private final boolean rtl;
    private final double pad;

    private CardLayout(VoucherTemplate template, Measure measure, double width, double height) {
        this.template = template;
        this.measure = measure;
        this.width = width;
        this.height = height;
        this.rtl = template.language() == VoucherTemplate.Language.ARABIC;
        this.pad = Math.max(3, height * 0.07);
    }

    /**
     * @param width  card width in points
     * @param height card height in points
     */
    public static List<Element> layout(VoucherTemplate template, Card card, double width, double height,
            Measure measure) {
        CardLayout layout = new CardLayout(template, measure, width, height);
        return switch (template.style()) {
            case MODERN, CLASSIC -> layout.modern(card);
            case QR_SIDE -> layout.qrSide(card);
            case MINIMAL -> layout.minimal(card);
            case IMAGE -> layout.image(card);
        };
    }

    // ------------------------------------------------------------------ styles

    private List<Element> modern(Card card) {
        List<Element> out = new ArrayList<>();
        double band = height * 0.26;
        out.add(new Fill(0, 0, width, band, template.accentColor()));
        double titleSize = band * 0.52;
        String price = card.price().orElse("");
        double priceWidth = price.isEmpty() ? 0 : measure.width(price, titleSize) + pad;
        double logoWidth = 0;
        if (template.logo() != null) {
            double logoSize = band * 0.78;
            logoWidth = logoSize + pad * 0.6;
            out.add(new Logo(rtl ? width - pad - logoSize : pad, (band - logoSize) / 2, logoSize, logoSize));
        }
        double titleX = rtl ? width - pad - logoWidth : pad + logoWidth;
        out.add(text(template.networkName(), titleX, band * 0.7,
                fit(template.networkName(), titleSize, width - 2 * pad - priceWidth - logoWidth), WHITE,
                startAlign(), Slot.NONE));
        if (!price.isEmpty()) {
            out.add(text(price, endX(), band * 0.7, titleSize, WHITE, endAlign(), Slot.NONE));
        }

        String footer = footer(card);
        double footerHeight = footer.isEmpty() ? 0 : height * 0.19;
        double bodyTop = band;
        double bodyHeight = height - band - footerHeight;
        double qrSize = card.qr() ? Math.min(bodyHeight - pad, width * 0.36) : 0;
        if (card.qr()) {
            double qrX = rtl ? pad : width - pad - qrSize;
            out.add(new Qr(qrX, bodyTop + (bodyHeight - qrSize) / 2, qrSize, Slot.QR));
        }
        double areaWidth = width - 2 * pad - (card.qr() ? qrSize + pad : 0);
        double areaCenter = card.qr() ? (rtl ? width - pad - areaWidth / 2 : pad + areaWidth / 2) : width / 2;
        credentials(out, card, areaCenter, areaWidth, bodyTop, bodyHeight);

        if (!footer.isEmpty()) {
            out.add(new Line(pad, height - footerHeight, width - pad, height - footerHeight, DASH));
            out.add(text(footer, width / 2, height - footerHeight * 0.3,
                    fit(footer, footerHeight * 0.55, width - 2 * pad),
                    MUTED, Align.CENTER, Slot.NONE));
        }
        return out;
    }

    private List<Element> qrSide(Card card) {
        List<Element> out = new ArrayList<>();
        double qrSize = card.qr() ? height - 2 * pad : 0;
        if (card.qr()) {
            out.add(new Qr(rtl ? pad : width - pad - qrSize, pad, qrSize, Slot.QR));
        }
        double areaWidth = width - 2 * pad - (card.qr() ? qrSize + pad : 0);
        double x = card.qr() ? (rtl ? width - pad : pad) : width / 2;
        Align align = card.qr() ? startAlign() : Align.CENTER;

        List<String> lines = new ArrayList<>();
        lines.add(template.networkName());
        lines.add(card.username());
        if (!card.singleCode()) {
            lines.add(card.password());
        }
        String details = joined(card.price().orElse(""), card.time());
        if (!details.isEmpty()) {
            lines.add(details);
        }
        double step = (height - 2 * pad) / lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean title = i == 0;
            boolean code = i == 1 || (i == 2 && !card.singleCode());
            double size = fit(line, step * (code ? 0.78 : 0.62), areaWidth);
            String color = title ? template.accentColor() : code ? DARK : MUTED;
            Slot slot = i == 1 ? Slot.USERNAME : (i == 2 && !card.singleCode()) ? Slot.PASSWORD : Slot.NONE;
            out.add(text(line, x, pad + step * i + step * 0.72, size, color, align, slot));
        }
        return out;
    }

    private List<Element> minimal(Card card) {
        List<Element> out = new ArrayList<>();
        String top = joined(template.networkName(), card.price().orElse(""));
        double usable = width - 2 * pad;
        out.add(text(top, width / 2, height * 0.3, fit(top, height * 0.17, usable), template.accentColor(),
                Align.CENTER, Slot.NONE));
        double codeY = card.singleCode() ? height * 0.74 : height * 0.62;
        out.add(text(card.username(), width / 2, codeY, fit(card.username(), height * 0.34, usable), DARK,
                Align.CENTER, Slot.USERNAME));
        if (!card.singleCode()) {
            out.add(text(card.password(), width / 2, height * 0.9, fit(card.password(), height * 0.2, usable), MUTED,
                    Align.CENTER, Slot.PASSWORD));
        }
        return out;
    }

    private List<Element> image(Card card) {
        CardImage picture = template.image();
        List<Element> out = new ArrayList<>();
        out.add(new Background(0, 0, width, height));
        double size = height * 0.17 * picture.scale();
        String color = picture.textColor();
        addOnPicture(out, spotText(card.username(), picture.username(), size, color, Slot.USERNAME));
        if (!card.singleCode()) {
            addOnPicture(out, spotText(card.password(), picture.password(), size * 0.8, color, Slot.PASSWORD));
        }
        card.price().ifPresent(
                price -> addOnPicture(out, spotText(price, picture.price(), size * 0.8, color, Slot.PRICE)));
        if (card.qr()) {
            double qr = Math.min(height * 0.42 * picture.scale(), Math.min(width, height));
            double x = Math.clamp(picture.qr().x() * width - qr / 2, 0, width - qr);
            double y = Math.clamp(picture.qr().y() * height - qr / 2, 0, height - qr);
            out.add(new Qr(x, y, qr, Slot.QR));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Text on the user's picture, with a contrasting box behind it unless switched off. */
    private void addOnPicture(List<Element> out, Text text) {
        CardImage picture = template.image();
        if (picture.textBox()) {
            double w = measure.width(text.text(), text.size()) + text.size() * 0.7;
            double h = text.size() * 1.3;
            double x = Math.clamp(text.x() - w / 2, 0, Math.max(0, width - w));
            double y = Math.clamp(text.baseline() - text.size() * 0.98, 0, Math.max(0, height - h));
            out.add(picture.lightText() ? new Box(x, y, w, h, "#000000", 0.5) : new Box(x, y, w, h, "#FFFFFF", 0.8));
        }
        out.add(text);
    }

    /** Username large; the password below it, or a label above a single code. */
    private void credentials(List<Element> out, Card card, double center, double areaWidth, double top,
            double bodyHeight) {
        if (card.singleCode()) {
            String label = template.language().username();
            out.add(text(label, center, top + bodyHeight * 0.36, fit(label, bodyHeight * 0.2, areaWidth), MUTED,
                    Align.CENTER, Slot.NONE));
            out.add(text(card.username(), center, top + bodyHeight * 0.82,
                    fit(card.username(), bodyHeight * 0.42, areaWidth), DARK, Align.CENTER, Slot.USERNAME));
        } else {
            out.add(text(card.username(), center, top + bodyHeight * 0.5,
                    fit(card.username(), bodyHeight * 0.38, areaWidth), DARK, Align.CENTER, Slot.USERNAME));
            String password = template.language().password() + ": " + card.password();
            out.add(text(password, center, top + bodyHeight * 0.86, fit(password, bodyHeight * 0.24, areaWidth),
                    MUTED, Align.CENTER, Slot.PASSWORD));
        }
    }

    private Text spotText(String value, CardImage.Spot spot, double size, String color, Slot slot) {
        double fitted = fit(value, size, width - 2 * pad);
        double half = measure.width(value, fitted) / 2;
        double x = Math.clamp(spot.x() * width, half, Math.max(half, width - half));
        double baseline = Math.clamp(spot.y() * height + fitted * 0.35, fitted, height - fitted * 0.2);
        return new Text(value, x, baseline, fitted, color, Align.CENTER, slot);
    }

    private String footer(Card card) {
        return joined(card.time(), card.profile());
    }

    private static String joined(String first, String second) {
        if (first.isBlank()) {
            return second.strip();
        }
        return second.isBlank() ? first.strip() : first.strip() + " · " + second.strip();
    }

    /** The largest size up to {@code size} at which the text fits in {@code maxWidth}. */
    private double fit(String value, double size, double maxWidth) {
        if (value.isEmpty() || maxWidth <= 0) {
            return size;
        }
        double needed = measure.width(value, size);
        return needed <= maxWidth ? size : Math.max(4, size * maxWidth / needed);
    }

    private static Text text(String value, double x, double baseline, double size, String color, Align align,
            Slot slot) {
        return new Text(value, x, baseline, size, color, align, slot);
    }

    private double endX() {
        return rtl ? pad : width - pad;
    }

    private Align startAlign() {
        return rtl ? Align.RIGHT : Align.LEFT;
    }

    private Align endAlign() {
        return rtl ? Align.LEFT : Align.RIGHT;
    }
}
