package org.netpilot.model;

import java.util.Optional;

/** Phone numbers as WhatsApp wants them: the full international number, digits only. */
public final class PhoneNumber {

    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15; // the international maximum (E.164)

    private PhoneNumber() {
    }

    /**
     * Reads a number as people type it: {@code +963 944 123 456}, {@code 00963944123456},
     * {@code 0944-123-456} (local, the country code is put in front) or {@code 963944123456}.
     * Arabic-Indic digits ({@code ٠٩٤٤…}) are read too.
     *
     * @return international digits without {@code +}; empty when it is not a phone number
     */
    public static Optional<String> normalize(String input, String countryCode) {
        if (input == null) {
            return Optional.empty();
        }
        StringBuilder digits = new StringBuilder();
        boolean plus = false;
        for (char c : input.strip().toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(Character.forDigit(Character.digit(c, 10), 10));
            } else if (c == '+' && digits.isEmpty()) {
                plus = true;
            } else if (" -(). ".indexOf(c) < 0) {
                return Optional.empty(); // letters or other signs: not a number
            }
        }
        String number = digits.toString();
        String country = countryCode == null ? "" : countryCode.replaceAll("\\D", "");
        if (plus) {
            // already international
        } else if (number.startsWith("00")) {
            number = number.substring(2);
        } else if (number.startsWith("0")) {
            number = country + number.substring(1);
        } else if (!country.isEmpty() && !number.startsWith(country)) {
            number = country + number; // typed without the leading 0
        }
        if (number.length() < MIN_DIGITS || number.length() > MAX_DIGITS || number.startsWith("0")) {
            return Optional.empty();
        }
        return Optional.of(number);
    }

    /** {@code 963944123456} → {@code +963 944 123 456} (groups of three after the country code). */
    public static String display(String international) {
        if (international == null || international.isEmpty()) {
            return "";
        }
        int country = Math.min(3, Math.max(1, international.length() - 9));
        StringBuilder out = new StringBuilder("+").append(international, 0, country);
        for (int i = country; i < international.length(); i += 3) {
            out.append(' ').append(international, i, Math.min(i + 3, international.length()));
        }
        return out.toString();
    }
}
