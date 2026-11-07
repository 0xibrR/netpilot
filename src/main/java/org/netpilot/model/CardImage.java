package org.netpilot.model;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The user's own card picture and where the details go on it ({@link VoucherStyle#IMAGE}).
 *
 * @param background picture inside NetPilot's data folder, or {@code null} while none is chosen
 * @param username   centre of the username, as a fraction of the card width and height
 * @param password   centre of the password (left out when it equals the username)
 * @param qr         centre of the QR code
 * @param price      centre of the price
 * @param textColor  colour of the printed details, {@code #RRGGBB}
 * @param scale      text and QR size, 0.6 to 1.6 (1 = normal)
 * @param textBox    draw a half-transparent box behind each detail, so it reads on any picture
 */
public record CardImage(Path background, Spot username, Spot password, Spot qr, Spot price, String textColor,
        double scale, boolean textBox) {

    public static final double MIN_SCALE = 0.6;
    public static final double MAX_SCALE = 1.6;

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** A position on the card: 0 is the left/top edge, 1 the right/bottom edge. */
    public record Spot(double x, double y) {

        public Spot {
            x = clamp(x);
            y = clamp(y);
        }

        private static double clamp(double value) {
            return Double.isFinite(value) ? Math.clamp(value, 0, 1) : 0.5;
        }
    }

    public CardImage {
        username = username == null ? new Spot(0.5, 0.5) : username;
        password = password == null ? new Spot(0.5, 0.72) : password;
        qr = qr == null ? new Spot(0.84, 0.66) : qr;
        price = price == null ? new Spot(0.16, 0.2) : price;
        textColor = textColor == null || !COLOR.matcher(textColor.strip()).matches()
                ? "#FFFFFF"
                : textColor.strip().toUpperCase(Locale.ROOT);
        scale = Double.isFinite(scale) ? Math.clamp(scale, MIN_SCALE, MAX_SCALE) : 1;
    }

    /** With a box behind the text, which keeps it readable whatever the picture looks like. */
    public CardImage(Path background, Spot username, Spot password, Spot qr, Spot price, String textColor,
            double scale) {
        this(background, username, password, qr, price, textColor, scale, true);
    }

    public static CardImage defaults() {
        return new CardImage(null, null, null, null, null, null, 1, true);
    }

    public CardImage withBackground(Path picture) {
        return new CardImage(picture, username, password, qr, price, textColor, scale, textBox);
    }

    public CardImage withUsername(Spot spot) {
        return new CardImage(background, spot, password, qr, price, textColor, scale, textBox);
    }

    public CardImage withPassword(Spot spot) {
        return new CardImage(background, username, spot, qr, price, textColor, scale, textBox);
    }

    public CardImage withQr(Spot spot) {
        return new CardImage(background, username, password, spot, price, textColor, scale, textBox);
    }

    public CardImage withPrice(Spot spot) {
        return new CardImage(background, username, password, qr, spot, textColor, scale, textBox);
    }

    /** Whether the text colour is light (then the box behind it is dark, and the other way round). */
    public boolean lightText() {
        int rgb = Integer.parseInt(textColor.substring(1), 16);
        double luminance = 0.299 * (rgb >> 16 & 0xFF) + 0.587 * (rgb >> 8 & 0xFF) + 0.114 * (rgb & 0xFF);
        return luminance > 140;
    }
}
