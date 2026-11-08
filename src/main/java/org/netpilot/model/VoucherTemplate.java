package org.netpilot.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.netpilot.i18n.Messages;

/**
 * How printed vouchers look.
 *
 * @param networkName title printed on every voucher, e.g. {@code شبكة الأمل} or {@code AlAmal WiFi}
 * @param note        small text at the bottom, e.g. validity terms; empty for none
 * @param language    language of the field labels (User / اسم المستخدم ...)
 * @param accentColor title color as {@code #RRGGBB}
 * @param prices      price text per hotspot profile, e.g. {@code 2M -> 1000 ل.س}; also used for sales reports
 * @param showQr      print a QR auto-login code (needs a login URL)
 * @param showUptime  print the time limit
 * @param showPrice   print the profile's price
 * @param showProfile print the profile name
 * @param logo        logo image inside NetPilot's data folder, or {@code null}
 * @param style       card layout
 * @param size        card size for compact styles
 * @param image       the user's own card picture, used by {@link VoucherStyle#IMAGE}
 */
public record VoucherTemplate(
        String networkName,
        String note,
        Language language,
        String accentColor,
        Map<String, String> prices,
        boolean showQr,
        boolean showUptime,
        boolean showPrice,
        boolean showProfile,
        Path logo,
        VoucherStyle style,
        CardSize size,
        CardImage image) {

    public static final int MAX_NAME = 40;
    public static final int MAX_NOTE = 90;
    public static final int MAX_PRICE = 24;

    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** Language of the labels printed next to the values. */
    public enum Language {

        ENGLISH("English", "User", "Pass", "Time", "Price", "Package", "Valid for", "Data"),
        ARABIC("العربية", "اسم المستخدم", "كلمة المرور", "المدة", "السعر", "الباقة", "الصلاحية", "البيانات");

        private final String label;
        private final String username;
        private final String password;
        private final String time;
        private final String price;
        private final String profile;
        private final String validity;
        private final String data;

        Language(String label, String username, String password, String time, String price, String profile,
                String validity, String data) {
            this.label = label;
            this.username = username;
            this.password = password;
            this.time = time;
            this.price = price;
            this.profile = profile;
            this.validity = validity;
            this.data = data;
        }

        /** Label of the card's data limit. */
        public String data() {
            return data;
        }

        /** Label of the package (hotspot profile) name. */
        public String profile() {
            return profile;
        }

        /** Label of the card validity after its first login. */
        public String validity() {
            return validity;
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }

        public String time() {
            return time;
        }

        public String price() {
            return price;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** New installations start with small modern cards. */
    public static VoucherTemplate defaults() {
        return new VoucherTemplate("WiFi", "", Language.ENGLISH, "#1F5FA8", Map.of(), true, true, true, true, null,
                VoucherStyle.MODERN, CardSize.SMALL, CardImage.defaults());
    }

    /** The original tall voucher layout (kept for templates saved before compact styles). */
    public VoucherTemplate(String networkName, String note, Language language, String accentColor,
            Map<String, String> prices, boolean showQr, boolean showUptime, boolean showPrice, boolean showProfile,
            Path logo) {
        this(networkName, note, language, accentColor, prices, showQr, showUptime, showPrice, showProfile, logo,
                VoucherStyle.CLASSIC, CardSize.SMALL, CardImage.defaults());
    }

    public VoucherTemplate {
        networkName = networkName == null ? "" : networkName.strip();
        note = note == null ? "" : note.strip();
        language = language == null ? Language.ENGLISH : language;
        accentColor = accentColor == null || !COLOR.matcher(accentColor).matches() ? "#1F5FA8" : accentColor;
        prices = Map.copyOf(cleanPrices(prices));
        style = style == null ? VoucherStyle.MODERN : style;
        size = size == null ? CardSize.SMALL : size;
        image = image == null ? CardImage.defaults() : image;
    }

    /** Price text for a profile, if one is set. */
    public Optional<String> priceFor(String profile) {
        return Optional.ofNullable(prices.get(profile));
    }

    public Optional<Path> logoFile() {
        return Optional.ofNullable(logo);
    }

    /**
     * @return human-readable problems, empty when the template is usable
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (networkName.length() > MAX_NAME) {
            errors.add(Messages.get("template.validation.name", MAX_NAME));
        }
        if (note.length() > MAX_NOTE) {
            errors.add(Messages.get("template.validation.note", MAX_NOTE));
        }
        if (style == VoucherStyle.IMAGE && image.background() == null) {
            errors.add(Messages.get("template.validation.image"));
        }
        prices.forEach((profile, price) -> {
            if (price.length() > MAX_PRICE) {
                errors.add(Messages.get("template.validation.price", profile, MAX_PRICE));
            }
        });
        return errors;
    }

    public VoucherTemplate withLogo(Path newLogo) {
        return new VoucherTemplate(networkName, note, language, accentColor, prices, showQr, showUptime, showPrice,
                showProfile, newLogo, style, size, image);
    }

    public VoucherTemplate withImage(CardImage newImage) {
        return new VoucherTemplate(networkName, note, language, accentColor, prices, showQr, showUptime, showPrice,
                showProfile, logo, style, size, newImage);
    }

    /** Drops blank entries and line breaks, which would break the layout and the settings file. */
    private static Map<String, String> cleanPrices(Map<String, String> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> clean = new java.util.TreeMap<>();
        raw.forEach((profile, price) -> {
            String p = profile == null ? "" : profile.strip();
            String v = price == null ? "" : price.replaceAll("[\\r\\n]", " ").strip();
            if (!p.isEmpty() && !v.isEmpty()) {
                clean.put(p, v);
            }
        });
        return clean;
    }
}
