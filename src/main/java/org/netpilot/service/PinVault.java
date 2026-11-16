package org.netpilot.service;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.netpilot.model.Role;

/**
 * App protection: the owner's and the cashier's PINs and the owner's recovery code, in
 * {@code security.properties}. Only one-way fingerprints are stored (PBKDF2-HMAC-SHA256 with a
 * random salt each), so the file never reveals a PIN. Wrong guesses are counted <em>in the file</em>,
 * so restarting NetPilot does not reset them: after {@value #FREE_TRIES} wrong PINs each further try
 * waits 30 seconds, doubling, at most {@link #MAX_WAIT}. The recovery code has its own count, works
 * once, and is replaced by a new one when used.
 *
 * <p>Protects the app at the counter against whoever is using the screen; it is not meant to resist
 * someone with full access to the computer's files (the router password has its own protection).
 */
public final class PinVault {

    private static final System.Logger LOG = System.getLogger(PinVault.class.getName());

    /** Wrong tries without any wait. */
    public static final int FREE_TRIES = 5;
    /** The first wait after the free tries; each further wrong try doubles it. */
    static final Duration FIRST_WAIT = Duration.ofSeconds(30);
    /** The longest wait, however many wrong tries. */
    public static final Duration MAX_WAIT = Duration.ofMinutes(15);
    public static final int MIN_DIGITS = 4;
    public static final int MAX_DIGITS = 8;
    public static final int DEFAULT_IDLE_MINUTES = 10;

    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    /** Recovery codes: no 0/O, 1/I/L, so they are easy to read back from paper. */
    private static final String CODE_LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** What a PIN opened. */
    public sealed interface Result {
    }

    /** The PIN of this role. */
    public record Opened(Role role) implements Result {
    }

    /** Wrong; {@code triesLeft} before waits start (0 once they have). */
    public record Wrong(int triesLeft) implements Result {
    }

    /** Too many wrong tries: nothing is checked until {@code until}. */
    public record Waiting(Instant until) implements Result {
    }

    private final Path file;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PinVault(Path file, Clock clock) {
        this.file = Objects.requireNonNull(file);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Whether protection is on (an owner PIN is set). */
    public synchronized boolean enabled() {
        return !load().getProperty("owner", "").isEmpty();
    }

    public synchronized boolean hasCashierPin() {
        return !load().getProperty("cashier", "").isEmpty();
    }

    public synchronized int idleMinutes() {
        try {
            return Math.clamp(Integer.parseInt(load().getProperty("idleMinutes", "")), 1, 240);
        } catch (NumberFormatException e) {
            return DEFAULT_IDLE_MINUTES;
        }
    }

    /**
     * Turns protection on.
     *
     * @param cashier empty for no cashier PIN (the app then opens as the cashier)
     * @return the recovery code, to be written down; shown once
     * @throws IllegalArgumentException when a PIN is not 4 to 8 digits, or both PINs are the same
     */
    public synchronized String enable(char[] owner, Optional<char[]> cashier, int idleMinutes) throws IOException {
        if (enabled()) {
            throw new IllegalStateException("Protection is already on");
        }
        validate(owner);
        cashier.ifPresent(c -> {
            validate(c);
            requireDifferent(owner, c);
        });
        Properties p = new Properties();
        p.setProperty("owner", hash(owner));
        p.setProperty("cashier", cashier.map(this::hash).orElse(""));
        String code = newRecoveryCode();
        p.setProperty("recovery", hash(code.toCharArray()));
        p.setProperty("idleMinutes", String.valueOf(Math.clamp(idleMinutes, 1, 240)));
        save(p);
        LOG.log(System.Logger.Level.INFO, "App protection turned on");
        return code;
    }

    /**
     * Checks a PIN typed on the lock screen or to become the owner.
     *
     * @return which role it opened, or that it was wrong, or that tries must wait
     */
    public synchronized Result check(char[] pin) throws IOException {
        Properties p = load();
        Optional<Instant> until = waitUntil(p, "failures", "lastFailure");
        if (until.isPresent()) {
            return new Waiting(until.get());
        }
        Optional<Role> role = matches(pin, p.getProperty("owner", ""))
                ? Optional.of(Role.OWNER)
                : matches(pin, p.getProperty("cashier", "")) ? Optional.of(Role.CASHIER) : Optional.empty();
        if (role.isPresent()) {
            p.setProperty("failures", "0");
            save(p);
            return new Opened(role.get());
        }
        int failures = failures(p, "failures") + 1;
        p.setProperty("failures", String.valueOf(failures));
        p.setProperty("lastFailure", clock.instant().toString());
        save(p);
        LOG.log(System.Logger.Level.WARNING, "Wrong PIN ({0} in a row)", failures);
        return failures >= FREE_TRIES
                ? new Waiting(waitUntil(p, "failures", "lastFailure").orElseThrow())
                : new Wrong(FREE_TRIES - failures);
    }

    /**
     * Asks for the owner PIN again before a sensitive change. A wrong PIN counts like any wrong PIN.
     *
     * @throws SecurityException when it is not the owner's PIN (or tries must wait)
     */
    private void requireOwner(char[] ownerPin) throws IOException {
        Result result = check(ownerPin);
        if (!(result instanceof Opened(Role role)) || role != Role.OWNER) {
            throw new SecurityException(result instanceof Waiting w ? "wait:" + w.until() : "wrong-owner-pin");
        }
    }

    /** A new owner PIN; the current one is asked again. */
    public synchronized void changeOwner(char[] currentOwner, char[] newOwner) throws IOException {
        requireOwner(currentOwner);
        validate(newOwner);
        Properties p = load();
        if (matches(newOwner, p.getProperty("cashier", ""))) {
            throw new IllegalArgumentException("same-as-cashier");
        }
        p.setProperty("owner", hash(newOwner));
        save(p);
        LOG.log(System.Logger.Level.INFO, "Owner PIN changed");
    }

    /** Sets, changes or (empty) removes the cashier PIN; the owner PIN is asked again. */
    public synchronized void setCashier(char[] ownerPin, Optional<char[]> cashier) throws IOException {
        requireOwner(ownerPin);
        cashier.ifPresent(c -> {
            validate(c);
            requireDifferent(ownerPin, c);
        });
        Properties p = load();
        p.setProperty("cashier", cashier.map(this::hash).orElse(""));
        save(p);
        LOG.log(System.Logger.Level.INFO, "Cashier PIN {0}", cashier.isPresent() ? "set" : "removed");
    }

    public synchronized void setIdleMinutes(int minutes) throws IOException {
        Properties p = load();
        p.setProperty("idleMinutes", String.valueOf(Math.clamp(minutes, 1, 240)));
        save(p);
    }

    /** Turns protection off; the owner PIN is asked again. */
    public synchronized void disable(char[] ownerPin) throws IOException {
        requireOwner(ownerPin);
        Files.deleteIfExists(file);
        LOG.log(System.Logger.Level.INFO, "App protection turned off");
    }

    /**
     * A forgotten owner PIN: the recovery code sets a new one. The code works once: a new code is
     * returned, to be written down in its place. Wrong codes have their own count and waits.
     *
     * @return the new recovery code
     * @throws SecurityException when the code is wrong (or tries must wait)
     */
    public synchronized String recover(String code, char[] newOwner) throws IOException {
        Properties p = load();
        Optional<Instant> until = waitUntil(p, "recoveryFailures", "lastRecoveryFailure");
        if (until.isPresent()) {
            throw new SecurityException("wait:" + until.get());
        }
        String typed = code == null ? "" : code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (!matches(typed.toCharArray(), p.getProperty("recovery", ""))) {
            int failures = failures(p, "recoveryFailures") + 1;
            p.setProperty("recoveryFailures", String.valueOf(failures));
            p.setProperty("lastRecoveryFailure", clock.instant().toString());
            save(p);
            LOG.log(System.Logger.Level.WARNING, "Wrong recovery code ({0} in a row)", failures);
            throw new SecurityException("wrong-recovery-code");
        }
        validate(newOwner);
        if (matches(newOwner, p.getProperty("cashier", ""))) {
            throw new IllegalArgumentException("same-as-cashier");
        }
        String next = newRecoveryCode();
        p.setProperty("owner", hash(newOwner));
        p.setProperty("recovery", hash(next.toCharArray())); // the used code no longer works
        p.setProperty("recoveryFailures", "0");
        p.setProperty("failures", "0");
        save(p);
        LOG.log(System.Logger.Level.INFO, "Owner PIN reset with the recovery code; a new code was issued");
        return next;
    }

    /** While tries must wait, until when. */
    public synchronized Optional<Instant> waitingUntil() {
        return waitUntil(load(), "failures", "lastFailure");
    }

    // ------------------------------------------------------------------ inside

    /** 30 s after the fifth wrong try, then 1, 2, 4 … minutes, never more than 15. */
    static Duration waitAfter(int failures) {
        if (failures < FREE_TRIES) {
            return Duration.ZERO;
        }
        int doublings = Math.min(failures - FREE_TRIES, 10);
        Duration wait = FIRST_WAIT.multipliedBy(1L << doublings);
        return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
    }

    private Optional<Instant> waitUntil(Properties p, String countKey, String timeKey) {
        int failures = failures(p, countKey);
        String last = p.getProperty(timeKey, "");
        if (failures < FREE_TRIES || last.isEmpty()) {
            return Optional.empty();
        }
        try {
            Instant until = Instant.parse(last).plus(waitAfter(failures));
            return until.isAfter(clock.instant()) ? Optional.of(until) : Optional.empty();
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static int failures(Properties p, String key) {
        try {
            return Math.max(0, Integer.parseInt(p.getProperty(key, "0")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void validate(char[] pin) {
        if (pin == null || pin.length < MIN_DIGITS || pin.length > MAX_DIGITS) {
            throw new IllegalArgumentException("pin-length");
        }
        for (char c : pin) {
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("pin-digits");
            }
        }
    }

    private static void requireDifferent(char[] owner, char[] cashier) {
        if (Arrays.equals(owner, cashier)) {
            throw new IllegalArgumentException("same-as-cashier");
        }
    }

    private String newRecoveryCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            code.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
        }
        return code.toString();
    }

    /** {@code XXXX-XXXX-XXXX}, for showing a recovery code. */
    public static String formatCode(String code) {
        return code.replaceAll("(.{4})(?!$)", "$1-");
    }

    String hash(char[] secret) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return "v1$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(secret, salt, ITERATIONS));
    }

    static boolean matches(char[] secret, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !"v1".equals(parts[0]) || secret == null || secret.length == 0) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(secret, salt, Integer.parseInt(parts[1])));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(char[] secret, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private Properties load() {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING, "App protection file unreadable: {0}", e.getMessage());
            }
        }
        return p;
    }

    private void save(Properties p) throws IOException {
        Path folder = Objects.requireNonNullElse(file.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".security-", ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                p.store(out, "NetPilot app protection: fingerprints only, never a PIN");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
