package org.netpilot.model;

/**
 * How the hotspot login page looks: what the customer sees on their phone before logging in.
 *
 * @param title         the big name at the top; empty for the card design's network name
 * @param welcome       a short message (offer, notice); empty for none
 * @param phone         a number to call; empty for no call button
 * @param whatsApp      a number (digits, with country code) for a WhatsApp button; empty for none
 * @param color         main colour as {@code #RRGGBB}
 * @param showPrices    show the packages and their prices
 * @param showLogo      show the card design's logo
 * @param passwordField ask for a password too (cards made with passwords)
 * @param language      the page's language
 * @param showSellers   list the points of sale (name and a call button) on their own tab
 */
public record LoginPageDesign(String title, String welcome, String phone, String whatsApp, String color,
        boolean showPrices, boolean showLogo, boolean passwordField, Language language, boolean showSellers) {

    /** Without the points of sale tab. */
    public LoginPageDesign(String title, String welcome, String phone, String whatsApp, String color,
            boolean showPrices, boolean showLogo, boolean passwordField, Language language) {
        this(title, welcome, phone, whatsApp, color, showPrices, showLogo, passwordField, language, false);
    }

    /** The page's language. */
    public enum Language {
        ARABIC,
        ENGLISH
    }

    public static final String DEFAULT_COLOR = "#1f6feb";

    public LoginPageDesign {
        title = clean(title, 60);
        welcome = clean(welcome, 300);
        phone = phone == null ? "" : phone.replaceAll("[^0-9+]", "");
        whatsApp = whatsApp == null ? "" : whatsApp.replaceAll("[^0-9]", "");
        color = color != null && color.matches("#[0-9a-fA-F]{6}")
                ? color.toLowerCase(java.util.Locale.ROOT)
                : DEFAULT_COLOR;
        language = language == null ? Language.ARABIC : language;
    }

    public static LoginPageDesign defaults() {
        return new LoginPageDesign("", "", "", "", DEFAULT_COLOR, true, true, false, Language.ARABIC);
    }

    private static String clean(String text, int max) {
        String t = text == null ? "" : text.strip().replaceAll("[\\r\\n]+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }
}
