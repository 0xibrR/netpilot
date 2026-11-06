package org.netpilot.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything needed to generate one batch of hotspot users.
 *
 * <p>Instances are created freely (e.g. straight from form input); call {@link #validate()}
 * before using them.
 */
public record GenerationSettings(
        int count,
        UsernameMode usernameMode,
        int usernameLength,
        int usernameStart,
        PasswordMode passwordMode,
        int passwordLength,
        int passwordStart,
        String passwordPrefix,
        String profile,
        String server,
        String limitUptime) {

    public static final int MAX_COUNT = 10_000;
    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 32;

    /** RouterOS durations: {@code 30m}, {@code 1h30m}, {@code 1d}, {@code 01:30:00}. Blank means unlimited. */
    private static final Pattern DURATION =
            Pattern.compile("^$|^(\\d+[wdhms])+$|^\\d{1,2}:\\d{2}:\\d{2}$");

    private static final Pattern PREFIX = Pattern.compile("^[A-Za-z0-9._-]{0,16}$");

    private static final Pattern NAME = Pattern.compile("^[^\"\\\\\\r\\n]{1,64}$");

    public static GenerationSettings defaults() {
        return new GenerationSettings(
                20,
                UsernameMode.RANDOM_ALPHANUMERIC, 6, 1,
                PasswordMode.RANDOM_NUMERIC, 6, 1000, "",
                "default", "all", "1h");
    }

    public GenerationSettings {
        passwordPrefix = passwordPrefix == null ? "" : passwordPrefix.strip();
        profile = profile == null ? "" : profile.strip();
        server = server == null ? "" : server.strip();
        limitUptime = limitUptime == null ? "" : limitUptime.strip();
    }

    /**
     * @return human-readable problems, empty when the settings are usable
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        if (count < 1 || count > MAX_COUNT) {
            errors.add("Users count must be between 1 and " + MAX_COUNT + ".");
        }
        if (usernameMode == null) {
            errors.add("Choose a username mode.");
        }
        if (passwordMode == null) {
            errors.add("Choose a password mode.");
        }
        if (usernameMode != null && usernameMode.usesLength()) {
            checkLength("Username", usernameLength, errors);
        }
        if (usernameMode != null && usernameMode.usesSequence() && usernameStart < 0) {
            errors.add("Username start number cannot be negative.");
        }
        if (passwordMode != null && passwordMode.usesLength()) {
            checkLength("Password", passwordLength, errors);
        }
        if (passwordMode != null && passwordMode.usesSequence() && passwordStart < 0) {
            errors.add("Password start number cannot be negative.");
        }
        if (passwordMode == PasswordMode.PREFIX_USERNAME && passwordPrefix.isEmpty()) {
            errors.add("Enter a password prefix.");
        }
        if (!PREFIX.matcher(passwordPrefix).matches()) {
            errors.add("Prefix may contain only letters, digits, '.', '_' or '-' (max 16).");
        }
        if (!NAME.matcher(profile).matches()) {
            errors.add("Enter a profile name (max 64 characters, no quotes).");
        }
        if (!NAME.matcher(server).matches()) {
            errors.add("Enter a server name, or 'all' (max 64 characters, no quotes).");
        }
        if (!DURATION.matcher(limitUptime).matches()) {
            errors.add("Uptime limit must look like 30m, 1h, 1d12h or 01:30:00 (blank = unlimited).");
        }
        if (errors.isEmpty() && usernameMode.usesLength()
                && !hasRoomFor(usernameMode == UsernameMode.RANDOM_NUMERIC ? 10 : 56, usernameLength)) {
            errors.add("Username length " + usernameLength + " is too short to create "
                    + count + " unique users. Increase the length.");
        }
        return errors;
    }

    /** Random names need plenty of headroom so collisions stay rare. */
    private boolean hasRoomFor(int alphabetSize, int length) {
        BigInteger capacity = BigInteger.valueOf(alphabetSize).pow(length);
        return capacity.compareTo(BigInteger.valueOf(count).multiply(BigInteger.TEN)) >= 0;
    }

    private static void checkLength(String field, int length, List<String> errors) {
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            errors.add(field + " length must be between " + MIN_LENGTH + " and " + MAX_LENGTH + ".");
        }
    }
}
