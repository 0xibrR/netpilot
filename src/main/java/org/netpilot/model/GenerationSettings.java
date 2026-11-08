package org.netpilot.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.netpilot.i18n.Messages;

/**
 * Everything needed to generate one batch of hotspot cards.
 *
 * <p>Instances are created freely (e.g. straight from form input); call {@link #validate()}
 * before using them.
 *
 * @param count          how many cards
 * @param format         characters of the random part (digits, or letters and digits)
 * @param prefix         fixed start of every card number, e.g. {@code 9}; may be empty
 * @param suffix         fixed end of every card number; may be empty
 * @param codeLength     total length of the card number, prefix and suffix included
 * @param passwordStyle  no password, a random one, the card number again, or one shared password
 * @param passwordLength length of random passwords (1 or more); used by {@link PasswordStyle#RANDOM}
 * @param sharedPassword the password of every card; used by {@link PasswordStyle#SHARED}
 * @param profile        hotspot user profile
 * @param server         hotspot server, or {@code all}
 * @param limitUptime    RouterOS duration such as {@code 8h}; empty for no limit
 * @param limitBytes     data limit in bytes (upload and download together); 0 for no limit
 */
public record GenerationSettings(
        int count,
        CodeFormat format,
        String prefix,
        String suffix,
        int codeLength,
        PasswordStyle passwordStyle,
        int passwordLength,
        String sharedPassword,
        String profile,
        String server,
        String limitUptime,
        long limitBytes) {

    public static final int MAX_COUNT = 10_000;
    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = 32;
    /** At least this many random characters remain after the prefix and suffix. */
    public static final int MIN_RANDOM = 3;
    public static final int MAX_AFFIX = 8;

    /** RouterOS durations: {@code 30m}, {@code 1h30m}, {@code 1d}, {@code 01:30:00}. Blank means unlimited. */
    private static final Pattern DURATION = Pattern.compile("^$|^(\\d+[wdhms])+$|^\\d{1,2}:\\d{2}:\\d{2}$");

    private static final Pattern AFFIX = Pattern.compile("^[A-Za-z0-9]{0," + MAX_AFFIX + "}$");

    private static final Pattern NAME = Pattern.compile("^[^\"\\\\\\r\\n]{1,64}$");

    private static final Pattern SHARED = Pattern.compile("^[^\"\\\\\\r\\n\\s]{1," + MAX_LENGTH + "}$");

    public static GenerationSettings defaults() {
        return new GenerationSettings(20, CodeFormat.DIGITS, "", "", 8, PasswordStyle.NONE, 4, "", "default", "all",
                "1h");
    }

    /** Password from a length alone: {@code 0} = no password, otherwise random of that length. */
    public GenerationSettings(int count, CodeFormat format, String prefix, String suffix, int codeLength,
            int passwordLength, String profile, String server, String limitUptime) {
        this(count, format, prefix, suffix, codeLength, passwordLength == 0 ? PasswordStyle.NONE : PasswordStyle.RANDOM,
                passwordLength == 0 ? 4 : passwordLength, "", profile, server, limitUptime);
    }

    /** Settings without a data limit. */
    public GenerationSettings(int count, CodeFormat format, String prefix, String suffix, int codeLength,
            PasswordStyle passwordStyle, int passwordLength, String sharedPassword, String profile, String server,
            String limitUptime) {
        this(count, format, prefix, suffix, codeLength, passwordStyle, passwordLength, sharedPassword, profile, server,
                limitUptime, 0);
    }

    public GenerationSettings {
        format = format == null ? CodeFormat.DIGITS : format;
        passwordStyle = passwordStyle == null ? PasswordStyle.NONE : passwordStyle;
        sharedPassword = sharedPassword == null ? "" : sharedPassword.strip();
        prefix = prefix == null ? "" : prefix.strip();
        suffix = suffix == null ? "" : suffix.strip();
        profile = profile == null ? "" : profile.strip();
        server = server == null ? "" : server.strip();
        limitUptime = limitUptime == null ? "" : limitUptime.strip();
        limitBytes = Math.max(0, limitBytes);
    }

    /** No password: the customer types the card number only. */
    public boolean pin() {
        return passwordStyle == PasswordStyle.NONE;
    }

    /** The shortest card number that still leaves {@link #MIN_RANDOM} random characters. */
    public int minimumLength() {
        return Math.max(MIN_LENGTH, prefix.length() + suffix.length() + MIN_RANDOM);
    }

    /** Characters chosen at random in each card number. */
    public int randomLength() {
        return codeLength - prefix.length() - suffix.length();
    }

    /**
     * @return human-readable problems, empty when the settings are usable
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (count < 1 || count > MAX_COUNT) {
            errors.add(Messages.get("validation.count", MAX_COUNT));
        }
        if (codeLength < MIN_LENGTH || codeLength > MAX_LENGTH) {
            errors.add(Messages.get("validation.codeLength", MIN_LENGTH, MAX_LENGTH));
        }
        if (passwordStyle == PasswordStyle.RANDOM && (passwordLength < 1 || passwordLength > MAX_LENGTH)) {
            errors.add(Messages.get("validation.passwordLength", MAX_LENGTH));
        }
        if (passwordStyle == PasswordStyle.SHARED && !SHARED.matcher(sharedPassword).matches()) {
            errors.add(Messages.get("validation.sharedPassword", MAX_LENGTH));
        }
        if (!AFFIX.matcher(prefix).matches() || !AFFIX.matcher(suffix).matches()) {
            errors.add(Messages.get("validation.affix", MAX_AFFIX));
        }
        if (errors.isEmpty() && randomLength() < MIN_RANDOM) {
            errors.add(Messages.get("validation.affixTooLong", codeLength, prefix.length() + suffix.length(),
                    Math.max(0, randomLength()), MIN_RANDOM, minimumLength()));
        }
        if (!NAME.matcher(profile).matches()) {
            errors.add(Messages.get("validation.profile"));
        }
        if (!NAME.matcher(server).matches()) {
            errors.add(Messages.get("validation.server"));
        }
        if (!DURATION.matcher(limitUptime).matches()) {
            errors.add(Messages.get("validation.uptime"));
        }
        if (errors.isEmpty() && spacedCodes().compareTo(BigInteger.valueOf(count).multiply(HEADROOM)) < 0) {
            errors.add(Messages.get("validation.codeTooShort", codeLength, count));
        }
        return errors;
    }

    /** Room left so random picks still find free, well-spaced numbers quickly. */
    private static final BigInteger HEADROOM = BigInteger.valueOf(4);

    /** How many different card numbers these settings can make. */
    public BigInteger possibleCodes() {
        return BigInteger.valueOf(format.alphabet().length()).pow(Math.max(0, randomLength()));
    }

    /**
     * Roughly how many numbers fit when any two must differ in at least two positions: each card
     * also blocks every number one substitution away from it (the sphere-packing bound).
     */
    public BigInteger spacedCodes() {
        long neighbours = 1L + (long) Math.max(0, randomLength()) * (format.alphabet().length() - 1);
        return possibleCodes().divide(BigInteger.valueOf(neighbours));
    }
}
