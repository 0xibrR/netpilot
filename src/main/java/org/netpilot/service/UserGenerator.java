package org.netpilot.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.netpilot.i18n.Messages;
import org.netpilot.model.CodeFormat;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;

/**
 * Generates batches of hotspot cards: prefix, random part, suffix.
 *
 * <p>Every character comes from {@link SecureRandom} (the operating system's cryptographic
 * generator), so numbers cannot be predicted from earlier ones. On top of that, {@link CodeRules}
 * refuses weak patterns ({@code 1111}, {@code 1234}, {@code 1212}) and any number that is equal to
 * or only one character different from another card in the batch, so a typo or a changed digit
 * never opens someone else's card.
 */
public final class UserGenerator {

    private static final int MAX_ATTEMPTS_PER_USER = 5_000;

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
        CodeRules.Batch batch = new CodeRules.Batch();
        List<HotspotUser> users = new ArrayList<>(settings.count());
        for (int i = 0; i < settings.count(); i++) {
            users.add(user(settings, next(settings, batch)
                    .orElseThrow(() -> new IllegalStateException(Messages.get("generator.noUniqueName")))));
        }
        return users;
    }

    /**
     * Creates a card to replace one whose number already exists on the router, following the
     * same rules against every number in {@code taken}.
     *
     * @param taken numbers that must not be used or resembled (on the router and in the batch)
     * @return empty if no free number was found
     */
    public Optional<HotspotUser> replacement(GenerationSettings settings, Set<String> taken) {
        return next(settings, new CodeRules.Batch(taken)).map(code -> user(settings, code));
    }

    /** One example card number for the given settings (shown live in the form). */
    public String example(GenerationSettings settings) {
        return next(settings, new CodeRules.Batch()).orElseGet(() -> code(settings));
    }

    private Optional<String> next(GenerationSettings settings, CodeRules.Batch batch) {
        boolean digits = settings.format() == CodeFormat.DIGITS;
        boolean leadingFree = settings.prefix().isEmpty();
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_USER; attempt++) {
            String randomPart = randomString(settings.format().alphabet(), Math.max(0, settings.randomLength()));
            if (!CodeRules.strong(randomPart, digits, leadingFree)) {
                continue;
            }
            String candidate = settings.prefix() + randomPart + settings.suffix();
            if (batch.tryAdd(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private HotspotUser user(GenerationSettings s, String code) {
        String password = switch (s.passwordStyle()) {
            case NONE -> ""; // the number alone logs in
            case RANDOM -> strongPassword(s);
            case SAME_AS_CODE -> code;
            case SHARED -> s.sharedPassword();
        };
        return new HotspotUser(code, password, s.profile(), s.server(), s.limitUptime(), s.limitBytes());
    }

    /** Separate passwords follow the same pattern rules (no {@code 1111} or {@code 1234}). */
    private String strongPassword(GenerationSettings s) {
        String password = randomString(s.format().alphabet(), s.passwordLength());
        for (int attempt = 0; attempt < 100 && !CodeRules.strong(password, false, false); attempt++) {
            password = randomString(s.format().alphabet(), s.passwordLength());
        }
        return password;
    }

    private String code(GenerationSettings s) {
        return s.prefix() + randomString(s.format().alphabet(), Math.max(0, s.randomLength())) + s.suffix();
    }

    private String randomString(String alphabet, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
