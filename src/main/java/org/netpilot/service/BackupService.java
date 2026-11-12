package org.netpilot.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.netpilot.service.BackupException.Kind;

/**
 * Backup and restore of everything NetPilot keeps on this computer: settings, card design and its
 * pictures, first-use history, print history, points of sale and their ledger. Never the router
 * password (it is encrypted for this Windows account and could not be read elsewhere anyway) and
 * never the logs.
 *
 * <p>A backup is a zip with a manifest that lists every file with its size and SHA-256. With a
 * password it is encrypted as a whole with AES-256-GCM (key from the password with PBKDF2), so the
 * contents stay private; SHA-256 alone only detects changes.
 *
 * <p>Safety rules:
 * <ul>
 * <li>A backup is written to a temporary file, read back and fully checked, and only then given
 * its final name. If anything fails, no backup file is left behind.</li>
 * <li>Old automatic backups are removed only after a new one was made and checked.</li>
 * <li>A restore checks the format and every fingerprint before touching anything, takes a backup
 * of the current data, and swaps all files as one step: if any step fails, every file goes back
 * to how it was. A restore cut short (power cut) is undone at the next start.</li>
 * </ul>
 */
public final class BackupService {

    public static final String FORMAT = "netpilot-backup";
    public static final int VERSION = 1;
    /** Automatic backups kept in their folder; older ones are removed after a successful new one. */
    public static final int KEEP_AUTOMATIC = 14;

    static final String MANIFEST = "manifest.txt";
    static final String STAGING = ".restore-staging";
    static final String PREVIOUS = ".restore-old";
    static final String JOURNAL = "restore.journal";

    private static final Pattern DATA_FILE = Pattern.compile(
            "settings\\.properties|history\\.csv|usage\\.csv|resellers\\.csv|reseller-ledger\\.csv|customers\\.csv"
                    + "|voucher-(logo|background)\\.(png|jpg)");
    private static final byte[] MAGIC = "NPBK-AES1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 310_000;
    private static final int SALT = 16;
    private static final int IV = 12;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final String AUTO_PREFIX = "NetPilot-auto-";

    private final Path dataDir;
    private final Clock clock;
    private final String appVersion;
    private final SecureRandom random = new SecureRandom();
    /** Test hook: runs before each file is swapped in, to simulate a failure part-way. */
    private Consumer<String> beforeSwap = name -> {
    };

    public BackupService(Path dataDir, Clock clock, String appVersion) {
        this.dataDir = Objects.requireNonNull(dataDir);
        this.clock = Objects.requireNonNull(clock);
        this.appVersion = Objects.requireNonNull(appVersion);
    }

    void setBeforeSwap(Consumer<String> hook) {
        this.beforeSwap = hook;
    }

    /**
     * What a backup (or the current data) holds, counted for the user.
     *
     * @param ledgerIds ids of the points of sale ledger's lines, to find newer transactions
     * @param usageKeys router and card of each first-use record
     */
    public record Summary(LocalDateTime created, String appVersion, boolean encrypted, int resellers,
            int ledgerLines, int usageRecords, int printBatches, List<String> files, Set<String> ledgerIds,
            Set<String> usageKeys) {

        public Summary {
            files = List.copyOf(files);
            ledgerIds = Set.copyOf(ledgerIds);
            usageKeys = Set.copyOf(usageKeys);
        }
    }

    /**
     * A checked backup and what restoring it would change.
     *
     * @param newerLedger ledger lines on this computer that the backup does not have (they would be lost)
     * @param newerUsage  first-use records on this computer that the backup does not have
     */
    public record RestorePlan(Path file, Summary backup, Summary current, int newerLedger, int newerUsage) {

        /** Restoring would drop transactions or sales recorded after the backup was made. */
        public boolean losesNewerData() {
            return newerLedger > 0 || newerUsage > 0;
        }
    }

    // ------------------------------------------------------------------ create

    /**
     * Writes a backup of the current data to {@code target}, atomically: nothing is at
     * {@code target} until the written file was read back and every fingerprint matched.
     *
     * @param password encrypts the backup when not empty; {@code null} or empty for a plain zip
     */
    public Summary create(Path target, char[] password) throws IOException {
        Map<String, byte[]> files = currentFiles();
        byte[] payload = zip(files);
        byte[] bytes = password == null || password.length == 0 ? payload : encrypt(payload, password);
        Path folder = Objects.requireNonNullElse(target.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".netpilot-backup-", ".tmp");
        try {
            Files.write(temp, bytes);
            // read back exactly what is on disk and check it as a restore would
            Summary written = verify(Files.readAllBytes(temp), password);
            if (!written.files().equals(new ArrayList<>(files.keySet()))) {
                throw new BackupException(Kind.CORRUPT, target.getFileName());
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return written;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Makes a dated automatic backup in {@code folder}, then removes automatic backups beyond the
     * newest {@link #KEEP_AUTOMATIC}. When the new backup fails, nothing is removed.
     */
    public Path createAutomatic(Path folder) throws IOException {
        Path target = folder.resolve(AUTO_PREFIX + LocalDateTime.now(clock).format(STAMP) + ".zip");
        create(target, null); // throws before any old backup is touched
        List<Path> automatic = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, AUTO_PREFIX + "*.zip")) {
            files.forEach(automatic::add);
        }
        automatic.sort(Comparator.comparing(BackupService::name).reversed());
        for (Path old : automatic.subList(Math.min(KEEP_AUTOMATIC, automatic.size()), automatic.size())) {
            Files.deleteIfExists(old);
        }
        return target;
    }

    // ------------------------------------------------------------------ check

    /**
     * Checks a backup completely and compares it with the data on this computer. Changes nothing.
     *
     * @param password needed when the backup is encrypted
     * @throws BackupException when it is not a usable NetPilot backup (the message says why)
     */
    public RestorePlan check(Path file, char[] password) throws IOException {
        Summary backup = verify(Files.readAllBytes(file), password);
        Summary current = summarize(currentFiles(), LocalDateTime.now(clock), appVersion, false);
        Set<String> newerLedger = new LinkedHashSet<>(current.ledgerIds());
        newerLedger.removeAll(backup.ledgerIds());
        Set<String> newerUsage = new LinkedHashSet<>(current.usageKeys());
        newerUsage.removeAll(backup.usageKeys());
        return new RestorePlan(file, backup, current, newerLedger.size(), newerUsage.size());
    }

    /** True when the file starts like an encrypted NetPilot backup. */
    public static boolean encrypted(Path file) throws IOException {
        byte[] head = new byte[MAGIC.length];
        try (var in = Files.newInputStream(file)) {
            return in.readNBytes(head, 0, head.length) == head.length && Arrays.equals(head, MAGIC);
        }
    }

    // ------------------------------------------------------------------ restore

    /**
     * Restores a backup: checks it again, saves the current data to {@code safetyFolder}, then
     * replaces all data files as one step. On any failure every file goes back as it was.
     *
     * @return the safety backup of the data that was there before (empty when there was none)
     */
    public java.util.Optional<Path> restore(Path file, char[] password, Path safetyFolder) throws IOException {
        Map<String, byte[]> restored = contents(Files.readAllBytes(file), password); // verified, or throws
        java.util.Optional<Path> safety = java.util.Optional.empty();
        if (!currentFiles().isEmpty()) {
            Path before = safetyFolder.resolve("NetPilot-before-restore-" + LocalDateTime.now(clock).format(STAMP)
                    + ".zip");
            create(before, null);
            safety = java.util.Optional.of(before);
        }
        // the pictures' paths in the settings point at the computer the backup was made on
        if (restored.containsKey("settings.properties")) {
            restored.put("settings.properties", relink(restored.get("settings.properties"), restored.keySet()));
        }
        swap(restored);
        return safety;
    }

    /**
     * Undoes a restore that was cut short (power cut, crash): puts the previous files back.
     * Called at start-up, before anything reads the data.
     *
     * @return whether an interrupted restore was found and undone
     */
    public boolean recoverInterruptedRestore() throws IOException {
        Path previous = dataDir.resolve(PREVIOUS);
        Path staging = dataDir.resolve(STAGING);
        if (!Files.isDirectory(previous)) {
            deleteTree(staging);
            return false;
        }
        Path journal = previous.resolve(JOURNAL);
        Set<String> incoming = Files.exists(journal)
                ? new LinkedHashSet<>(Files.readAllLines(journal, StandardCharsets.UTF_8))
                : Set.of();
        for (String name : incoming) {
            if (DATA_FILE.matcher(name).matches() && !Files.exists(previous.resolve(name))) {
                Files.deleteIfExists(dataDir.resolve(name)); // came with the restore, was not there before
            }
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(previous)) {
            for (Path old : files) {
                String name = name(old);
                if (DATA_FILE.matcher(name).matches()) {
                    Files.move(old, dataDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        deleteTree(previous);
        deleteTree(staging);
        return true;
    }

    /** All files are written first; then old files are moved aside and new ones moved in. */
    private void swap(Map<String, byte[]> restored) throws IOException {
        Path staging = dataDir.resolve(STAGING);
        Path previous = dataDir.resolve(PREVIOUS);
        recoverInterruptedRestore();
        Files.createDirectories(staging);
        for (Map.Entry<String, byte[]> f : restored.entrySet()) {
            Files.write(staging.resolve(f.getKey()), f.getValue());
        }
        Files.createDirectories(previous);
        // the journal comes first: it lets a later start undo a swap that was cut short
        Files.write(previous.resolve(JOURNAL), restored.keySet(), StandardCharsets.UTF_8);
        try {
            for (String name : currentNames()) {
                Files.move(dataDir.resolve(name), previous.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            }
            for (String name : restored.keySet()) {
                beforeSwap.accept(name);
                Files.move(staging.resolve(name), dataDir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                recoverInterruptedRestore();
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure);
        }
        deleteTree(previous);
        deleteTree(staging);
    }

    /** Points the settings' logo and card picture at this computer's data folder. */
    private byte[] relink(byte[] settings, Set<String> files) throws IOException {
        Properties p = new Properties();
        p.load(new java.io.InputStreamReader(new ByteArrayInputStream(settings), StandardCharsets.UTF_8));
        for (String key : List.of("voucher.logo", "voucher.image.file")) {
            String value = p.getProperty(key, "");
            if (value.isBlank()) {
                continue;
            }
            String name = value.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            p.setProperty(key, files.contains(name) ? dataDir.resolve(name).toString() : "");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            p.store(writer, "NetPilot settings");
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ format

    private List<String> currentNames() throws IOException {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(dataDir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dataDir)) {
                for (Path f : files) {
                    String name = name(f);
                    if (Files.isRegularFile(f) && DATA_FILE.matcher(name).matches()) {
                        names.add(name);
                    }
                }
            }
        }
        names.sort(null);
        return names;
    }

    private Map<String, byte[]> currentFiles() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : currentNames()) {
            files.put(name, Files.readAllBytes(dataDir.resolve(name)));
        }
        return files;
    }

    private byte[] zip(Map<String, byte[]> files) throws IOException {
        StringBuilder manifest = new StringBuilder()
                .append("format=").append(FORMAT).append('\n')
                .append("version=").append(VERSION).append('\n')
                .append("created=").append(LocalDateTime.now(clock).withNano(0)).append('\n')
                .append("app=").append(appVersion).append('\n');
        files.forEach((name, bytes) -> manifest.append("file=").append(name).append('|').append(bytes.length)
                .append('|').append(sha256(bytes)).append('\n'));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, MANIFEST, manifest.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                put(zip, f.getKey(), f.getValue());
            }
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Checks a backup's bytes completely; returns what it holds. */
    private Summary verify(byte[] bytes, char[] password) throws IOException {
        boolean encrypted = startsWithMagic(bytes);
        Map<String, byte[]> files = contents(bytes, password);
        Map<String, String> head = new LinkedHashMap<>();
        // the manifest was read and checked in contents(); its header is read again for the summary
        String manifest = new String(readEntries(encrypted ? decrypt(bytes, password) : bytes).get(MANIFEST),
                StandardCharsets.UTF_8);
        for (String line : manifest.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("file=")) {
                head.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return summarize(files, LocalDateTime.parse(head.get("created")), head.getOrDefault("app", ""), encrypted);
    }

    /**
     * The data files of a backup, after checking format, version, the file list, sizes and
     * fingerprints. Throws without returning anything when any check fails.
     */
    private Map<String, byte[]> contents(byte[] bytes, char[] password) throws IOException {
        byte[] payload = startsWithMagic(bytes) ? decrypt(bytes, password) : bytes;
        Map<String, byte[]> entries = readEntries(payload);
        byte[] manifestBytes = entries.get(MANIFEST);
        if (manifestBytes == null) {
            throw new BackupException(Kind.NOT_A_BACKUP);
        }
        Map<String, String> head = new LinkedHashMap<>();
        Map<String, String[]> listed = new LinkedHashMap<>();
        for (String line : new String(manifestBytes, StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("file=")) {
                String[] f = line.substring(5).split("\\|");
                if (f.length != 3 || !DATA_FILE.matcher(f[0]).matches()) {
                    throw new BackupException(Kind.NOT_A_BACKUP);
                }
                listed.put(f[0], f);
            } else if (line.indexOf('=') > 0) {
                head.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
            }
        }
        if (!FORMAT.equals(head.get("format")) || head.get("created") == null) {
            throw new BackupException(Kind.NOT_A_BACKUP);
        }
        int version;
        try {
            version = Integer.parseInt(head.getOrDefault("version", ""));
        } catch (NumberFormatException e) {
            throw new BackupException(Kind.NOT_A_BACKUP);
        }
        if (version > VERSION) {
            throw new BackupException(Kind.NEWER_FORMAT, head.getOrDefault("app", "?"));
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String[] f : listed.values()) {
            byte[] data = entries.get(f[0]);
            if (data == null) {
                throw new BackupException(Kind.INCOMPLETE, f[0]);
            }
            if (!String.valueOf(data.length).equals(f[1]) || !sha256(data).equals(f[2])) {
                throw new BackupException(Kind.CORRUPT, f[0]);
            }
            files.put(f[0], data);
        }
        for (String name : entries.keySet()) {
            if (!name.equals(MANIFEST) && !listed.containsKey(name)) {
                throw new BackupException(Kind.CORRUPT, name); // a file the manifest does not vouch for
            }
        }
        return files;
    }

    /** Zip entries by name; names outside the known set, or repeated, mean it is not our backup. */
    private static Map<String, byte[]> readEntries(byte[] payload) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                boolean known = name.equals(MANIFEST) || DATA_FILE.matcher(name).matches();
                if (!known || entry.isDirectory() || entries.containsKey(name)) {
                    throw new BackupException(Kind.NOT_A_BACKUP);
                }
                entries.put(name, zip.readAllBytes());
            }
        } catch (BackupException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new BackupException(Kind.CORRUPT, "zip");
        }
        if (entries.isEmpty()) {
            throw new BackupException(Kind.NOT_A_BACKUP);
        }
        return entries;
    }

    private Summary summarize(Map<String, byte[]> files, LocalDateTime created, String app, boolean encrypted) {
        List<String> ledger = dataLines(files.get("reseller-ledger.csv"));
        List<String> usage = dataLines(files.get("usage.csv"));
        Set<String> ledgerIds = new LinkedHashSet<>();
        ledger.forEach(line -> ledgerIds.add(Csv.parseLine(line).getFirst()));
        Set<String> usageKeys = new LinkedHashSet<>();
        for (String line : usage) {
            List<String> f = Csv.parseLine(line);
            if (f.size() >= 2) {
                usageKeys.add(f.get(0) + "|" + f.get(1));
            }
        }
        return new Summary(created, app, encrypted, dataLines(files.get("resellers.csv")).size(), ledger.size(),
                usage.size(), dataLines(files.get("history.csv")).size(), new ArrayList<>(files.keySet()), ledgerIds,
                usageKeys);
    }

    /** The CSV lines after the header. */
    private static List<String> dataLines(byte[] csv) {
        if (csv == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(new String(csv, StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank()).toList());
        return lines.isEmpty() ? lines : lines.subList(1, lines.size());
    }

    // ------------------------------------------------------------------ encryption

    private byte[] encrypt(byte[] payload, char[] password) throws IOException {
        try {
            byte[] salt = new byte[SALT];
            byte[] iv = new byte[IV];
            random.nextBytes(salt);
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
            cipher.updateAAD(MAGIC);
            byte[] sealed = cipher.doFinal(payload);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(MAGIC);
            out.write(salt);
            out.write(iv);
            out.write(sealed);
            return out.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption is not available", e);
        }
    }

    private static byte[] decrypt(byte[] bytes, char[] password) throws IOException {
        if (password == null || password.length == 0) {
            throw new BackupException(Kind.NEEDS_PASSWORD);
        }
        int head = MAGIC.length + SALT + IV;
        if (bytes.length <= head) {
            throw new BackupException(Kind.CORRUPT, "header");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    key(password, Arrays.copyOfRange(bytes, MAGIC.length, MAGIC.length + SALT)),
                    new GCMParameterSpec(128, Arrays.copyOfRange(bytes, MAGIC.length + SALT, head)));
            cipher.updateAAD(MAGIC);
            return cipher.doFinal(bytes, head, bytes.length - head);
        } catch (AEADBadTagException e) {
            throw new BackupException(Kind.WRONG_PASSWORD, e);
        } catch (GeneralSecurityException e) {
            throw new IOException("Decryption is not available", e);
        }
    }

    private static SecretKeySpec key(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean startsWithMagic(byte[] bytes) {
        return bytes.length >= MAGIC.length && Arrays.equals(Arrays.copyOf(bytes, MAGIC.length), MAGIC);
    }

    // ------------------------------------------------------------------ helpers

    /** A path's file name, or "" for a root. */
    private static String name(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
