package org.netpilot.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.PasswordMode;
import org.netpilot.model.UsernameMode;

/**
 * Generates batches of hotspot users with guaranteed-unique usernames.
 */
public final class UserGenerator {

    private static final String DIGITS = "0123456789";
    /** Letters and digits without look-alikes (0/O/o, 1/l/I) so printed vouchers are easy to type. */
    static final String ALPHANUMERIC = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MIN_SEQUENCE_DIGITS = 4;
    private static final int MAX_ATTEMPTS_PER_USER = 1_000;

    private final RandomGenerator random;

    public UserGenerator() {
        this(new SecureRandom());
    }

    /** For tests: supply a seeded generator for reproducible output. */
    public UserGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * @throws IllegalArgumentException if the settings are invalid
     */
    public List<HotspotUser> generate(GenerationSettings settings) {
        List<String> errors = settings.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }

        int count = settings.count();
        List<HotspotUser> users = new ArrayList<>(count);
        Set<String> usedNames = HashSet.newHashSet(count);

        for (int i = 0; i < count; i++) {
            String username = nextUniqueUsername(settings, i, usedNames);
            String password = password(settings, i, username);
            users.add(new HotspotUser(
                    username, password,
                    settings.profile(), settings.server(), settings.limitUptime()));
        }
        return users;
    }

    /**
     * Creates a user to replace one whose name already exists on the router. Only random
     * username modes can do this; sequential names would no longer be in sequence.
     *
     * @param index position for sequence-based passwords; pass {@code count + n} to continue after the batch
     * @param taken names that must not be used (on the router and already in the batch)
     * @return empty if the settings use a sequential username mode or no free name was found
     */
    public Optional<HotspotUser> replacement(GenerationSettings settings, int index, Set<String> taken) {
        if (!settings.usernameMode().isRandom()) {
            return Optional.empty();
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_USER; attempt++) {
            String candidate = username(settings, index);
            if (!taken.contains(candidate)) {
                return Optional.of(new HotspotUser(candidate, password(settings, index, candidate),
                        settings.profile(), settings.server(), settings.limitUptime()));
            }
        }
        return Optional.empty();
    }

    private String nextUniqueUsername(GenerationSettings settings, int index, Set<String> usedNames) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_USER; attempt++) {
            String candidate = username(settings, index);
            if (usedNames.add(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not create a unique username. Increase the username length.");
    }

    private String username(GenerationSettings s, int index) {
        UsernameMode mode = s.usernameMode();
        return switch (mode) {
            case RANDOM_ALPHANUMERIC -> randomString(ALPHANUMERIC, s.usernameLength());
            case RANDOM_NUMERIC -> randomString(DIGITS, s.usernameLength());
            case SEQUENTIAL -> "user" + sequence(s.usernameStart(), index, s.count());
            case PROFILE_BASED -> s.profile().replaceAll("\\s+", "") + sequence(s.usernameStart(), index, s.count());
        };
    }

    private String password(GenerationSettings s, int index, String username) {
        PasswordMode mode = s.passwordMode();
        return switch (mode) {
            case RANDOM_NUMERIC -> randomString(DIGITS, s.passwordLength());
            case RANDOM_ALPHANUMERIC -> randomString(ALPHANUMERIC, s.passwordLength());
            case SAME_AS_USERNAME -> username;
            case PREFIX_USERNAME -> s.passwordPrefix() + username;
            case SEQUENTIAL -> String.valueOf((long) s.passwordStart() + index);
        };
    }

    /** Zero-padded so every name in the batch has the same width. */
    private static String sequence(int start, int index, int count) {
        long last = (long) start + count - 1;
        int width = Math.max(MIN_SEQUENCE_DIGITS, String.valueOf(last).length());
        return String.format("%0" + width + "d", (long) start + index);
    }

    private String randomString(String alphabet, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
