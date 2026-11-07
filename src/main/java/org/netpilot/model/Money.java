package org.netpilot.model;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An amount with its currency text, read from a voucher price such as {@code 1000 ل.س},
 * {@code ١٠٠٠ ل.س}, {@code $5} or {@code 2,500 SYP}.
 *
 * @param amount   the number
 * @param currency what was written around the number, e.g. {@code ل.س}, {@code $}; may be empty
 */
public record Money(BigDecimal amount, String currency) {

    /** 2,500 / 2 500 / 2500 / 12.5 */
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:[, ]\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");

    public Money {
        Objects.requireNonNull(amount, "amount");
        currency = currency == null ? "" : currency.strip();
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * @return the first number in the text and the rest as currency, or empty if there is no number
     */
    public static Optional<Money> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = westernDigits(text.strip());
        Matcher m = NUMBER.matcher(normalized);
        if (!m.find()) {
            return Optional.empty();
        }
        BigDecimal amount = new BigDecimal(m.group().replace(",", "").replace(" ", ""));
        String currency = (normalized.substring(0, m.start()) + " " + normalized.substring(m.end()))
                .replaceAll("\\s+", " ")
                .strip();
        return Optional.of(new Money(amount, currency));
    }

    public Money times(long count) {
        return new Money(amount.multiply(BigDecimal.valueOf(count)), currency);
    }

    /**
     * @throws IllegalArgumentException if the currencies differ; adding them would be meaningless
     */
    public Money plus(Money other) {
        if (!sameCurrency(other)) {
            throw new IllegalArgumentException("Cannot add " + currency + " and " + other.currency);
        }
        return new Money(amount.add(other.amount), currency);
    }

    public boolean sameCurrency(Money other) {
        return currency.equalsIgnoreCase(other.currency);
    }

    /** {@code 47,000 ل.س}, {@code 12.5 $}. */
    public String format() {
        DecimalFormat number = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        String value = number.format(amount);
        return currency.isEmpty() ? value : value + " " + currency;
    }

    /** Arabic-Indic (٠-٩) and Persian (۰-۹) digits and separators become ASCII. */
    private static String westernDigits(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '٠' && c <= '٩') {
                sb.append((char) ('0' + (c - '٠')));
            } else if (c >= '۰' && c <= '۹') {
                sb.append((char) ('0' + (c - '۰')));
            } else if (c == '٫') { // Arabic decimal separator
                sb.append('.');
            } else if (c == '٬') { // Arabic thousands separator
                sb.append(',');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
