package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.Reseller;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.ResellerRepository;
import org.netpilot.service.BackupException.Kind;
import org.netpilot.service.ResellerAccounts.Card;
import org.netpilot.service.ResellerAccounts.Statement;

class BackupServiceTest {

    private static final String ROUTER = "10.0.0.1:80";
    private static final char[] PASSWORD = "شبكة-2026".toCharArray();

    @TempDir
    Path temp;

    private Path home;
    private Path backups;
    private MovingClock clock;
    private BackupService service;

    /** A clock the test moves forward, so automatic backups get different names. */
    private static final class MovingClock extends Clock {

        private Instant now = Instant.parse("2026-11-09T09:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        home = temp.resolve("pc1/.netpilot");
        backups = temp.resolve("usb");
        clock = new MovingClock();
        service = new BackupService(home, clock, "1.0.0");
        Files.createDirectories(home);
        // a shop with a sale, a partial payment and an advance, a logo and a card picture
        Files.write(home.resolve("voucher-logo.png"), new byte[]{1, 2, 3, 4});
        Files.write(home.resolve("voucher-background.jpg"), new byte[]{9, 8, 7});
        settings(home, home.resolve("voucher-logo.png"), home.resolve("voucher-background.jpg"));
        Files.writeString(home.resolve("credentials"), "secret-dpapi-blob"); // must never be backed up
        ResellerAccounts accounts = accounts(home);
        Reseller shop = accounts.save(new Reseller("shop", "محل علي", "", Reseller.CommissionType.PERCENT,
                new BigDecimal("10"), Reseller.Billing.ON_SALE));
        accounts.handOver(shop, List.of(new Card(ROUTER, "a", "1M"), new Card(ROUTER, "b", "1M")),
                Map.of("1M", "1000 ل.س"), false);
        Files.writeString(home.resolve("usage.csv"), "router,username,profile,batch,firstUsed\n"
                + ROUTER + ",a,1M,NetPilot 2026-11-08 15:59 #13eb,2026-11-09\n");
        accounts.recordSales(ROUTER, List.of(new UsageRecord(ROUTER, "a", "1M", "", Optional.of(
                java.time.LocalDate.of(2026, 11, 9)))));
        accounts.pay("p1", shop, new BigDecimal("400"), "ل.س", java.time.LocalDate.of(2026, 11, 9), "part");
        Files.writeString(home.resolve("history.csv"), "date,folder\n2026-11-09,x\n");
    }

    private static void settings(Path dir, Path logo, Path picture) throws IOException {
        Properties p = new Properties();
        p.setProperty("ui.language", "ARABIC");
        p.setProperty("voucher.logo", logo.toString());
        p.setProperty("voucher.image.file", picture.toString());
        try (var w = Files.newBufferedWriter(dir.resolve("settings.properties"), StandardCharsets.UTF_8)) {
            p.store(w, null);
        }
    }

    private ResellerAccounts accounts(Path dir) {
        return new ResellerAccounts(new ResellerRepository(dir.resolve("resellers.csv"),
                dir.resolve("reseller-ledger.csv")), clock);
    }

    private static Properties load(Path file) throws IOException {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    @Test
    void restoreOnAnotherComputerKeepsEveryFinancialRecordExactlyAndRelinksPictures() throws Exception {
        Path file = backups.resolve("NetPilot-backup.zip");
        BackupService.Summary made = service.create(file, null);
        assertEquals(1, made.resellers());
        assertEquals(4, made.ledgerLines(), "2 handovers, 1 sale, 1 payment");
        assertFalse(made.files().contains("credentials"), "the router password never leaves this computer");

        // a different Windows computer: other user folder, empty data folder
        Path otherHome = temp.resolve("pc2/Users/sami/.netpilot");
        BackupService other = new BackupService(otherHome, clock, "1.0.0");
        BackupService.RestorePlan plan = other.check(file, null);
        assertFalse(plan.losesNewerData());
        assertEquals(Optional.empty(), other.restore(file, null, temp.resolve("pc2/safety")),
                "nothing to save first on an empty computer");

        for (String name : List.of("reseller-ledger.csv", "resellers.csv", "usage.csv", "history.csv",
                "voucher-logo.png", "voucher-background.jpg")) {
            assertArrayEquals(Files.readAllBytes(home.resolve(name)), Files.readAllBytes(otherHome.resolve(name)),
                    name + " is restored byte for byte");
        }
        Statement before = accounts(home).statements().getFirst();
        Statement after = accounts(otherHome).statements().getFirst();
        assertEquals(before.chargedValue(), after.chargedValue());
        assertEquals(before.commission(), after.commission());
        assertEquals(before.paid(), after.paid());
        assertEquals(before.due(), after.due());
        assertEquals(new BigDecimal("500"), after.due(), "900 for the sale minus the 400 paid");

        Properties restored = load(otherHome.resolve("settings.properties"));
        assertEquals(otherHome.resolve("voucher-logo.png").toString(), restored.getProperty("voucher.logo"));
        assertEquals(otherHome.resolve("voucher-background.jpg").toString(),
                restored.getProperty("voucher.image.file"));
        assertEquals("ARABIC", restored.getProperty("ui.language"));
        assertFalse(Files.exists(otherHome.resolve("credentials")));
    }

    @Test
    void anEncryptedBackupHidesItsContentsAndNeedsTheRightPassword() throws Exception {
        Path file = backups.resolve("secret.npbackup");
        service.create(file, PASSWORD);

        assertTrue(BackupService.encrypted(file));
        String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("reseller-ledger") || raw.contains("HANDOVER") || raw.contains("manifest"),
                "no file name or ledger text is readable without the password");
        assertEquals(Kind.NEEDS_PASSWORD, assertThrows(BackupException.class,
                () -> service.check(file, null)).kind());
        assertEquals(Kind.WRONG_PASSWORD, assertThrows(BackupException.class,
                () -> service.check(file, "wrong".toCharArray())).kind());
        assertTrue(service.check(file, PASSWORD).backup().encrypted());

        byte[] tampered = Files.readAllBytes(file);
        tampered[tampered.length - 5] ^= 1;
        Files.write(file, tampered);
        assertEquals(Kind.WRONG_PASSWORD, assertThrows(BackupException.class,
                () -> service.check(file, PASSWORD)).kind(), "any change to the encrypted file is caught");
    }

    @Test
    void corruptedIncompleteForeignAndNewerBackupsAreRefusedBeforeAnythingChanges() throws Exception {
        byte[] ledger = Files.readAllBytes(home.resolve("reseller-ledger.csv"));
        String manifest = "format=netpilot-backup\nversion=1\ncreated=2026-11-09T09:00\napp=1.0.0\n"
                + "file=reseller-ledger.csv|" + ledger.length + "|" + BackupService.sha256(ledger) + "\n";

        Path changed = zip("changed.zip", Map.of("manifest.txt", manifest,
                "reseller-ledger.csv", new String(ledger, StandardCharsets.UTF_8).replace("400", "900")));
        Path missing = zip("missing.zip", Map.of("manifest.txt", manifest));
        Path foreign = zip("foreign.zip", Map.of("readme.txt", "hello"));
        Path newer = zip("newer.zip", Map.of("manifest.txt", manifest.replace("version=1", "version=2"),
                "reseller-ledger.csv", new String(ledger, StandardCharsets.UTF_8)));
        Path extra = zip("extra.zip", Map.of("manifest.txt", manifest,
                "reseller-ledger.csv", new String(ledger, StandardCharsets.UTF_8), "usage.csv", "smuggled"));
        Path truncated = backups.resolve("truncated.zip");
        service.create(truncated, null);
        byte[] full = Files.readAllBytes(truncated);
        Files.write(truncated, java.util.Arrays.copyOf(full, full.length / 2));

        byte[] before = Files.readAllBytes(home.resolve("reseller-ledger.csv"));
        assertEquals(Kind.CORRUPT, refused(changed), "an amount changed in the ledger");
        assertEquals(Kind.INCOMPLETE, refused(missing));
        assertEquals(Kind.NOT_A_BACKUP, refused(foreign));
        assertEquals(Kind.NEWER_FORMAT, refused(newer));
        assertEquals(Kind.CORRUPT, refused(extra), "a file the manifest does not list");
        assertTrue(Stream.of(Kind.CORRUPT, Kind.NOT_A_BACKUP, Kind.INCOMPLETE).anyMatch(k -> k == refused(truncated)),
                "a half-copied file");
        assertArrayEquals(before, Files.readAllBytes(home.resolve("reseller-ledger.csv")), "nothing was touched");
        assertFalse(Files.exists(home.resolve(BackupService.STAGING)));
    }

    private Kind refused(Path file) {
        return assertThrows(BackupException.class, () -> service.restore(file, null, backups.resolve("safety")))
                .kind();
    }

    private Path zip(String name, Map<String, String> entries) throws IOException {
        Files.createDirectories(backups);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Path file = backups.resolve(name);
        Files.write(file, out.toByteArray());
        return file;
    }

    @Test
    void aRestoreThatFailsPartWayPutsEveryFileBack() throws Exception {
        Path file = backups.resolve("good.zip");
        service.create(file, null);
        // the data changes after the backup, then a restore fails half-way through
        accounts(home).pay("p2", accounts(home).resellers().getFirst(), BigDecimal.TEN, "ل.س",
                java.time.LocalDate.of(2026, 11, 10), "");
        Map<String, byte[]> before = snapshot(home);
        int[] swapped = {0};
        service.setBeforeSwap(name -> {
            if (++swapped[0] == 3) {
                throw new IllegalStateException("disk full");
            }
        });

        assertThrows(IOException.class, () -> service.restore(file, null, backups.resolve("safety")));

        assertEquals(before.keySet(), snapshot(home).keySet());
        Map<String, byte[]> now = snapshot(home);
        before.forEach((name, bytes) -> assertArrayEquals(bytes, now.get(name), name + " is back"));
        assertFalse(Files.exists(home.resolve(BackupService.STAGING)));
        assertFalse(Files.exists(home.resolve(BackupService.PREVIOUS)));
    }

    @Test
    void aRestoreCutShortByAPowerCutIsUndoneAtTheNextStart() throws Exception {
        Map<String, byte[]> before = snapshot(home);
        // the state a power cut leaves: old files moved aside, one new file already in place
        Path previous = Files.createDirectories(home.resolve(BackupService.PREVIOUS));
        Files.writeString(previous.resolve(BackupService.JOURNAL), "usage.csv\nreseller-ledger.csv\n");
        Files.move(home.resolve("usage.csv"), previous.resolve("usage.csv"));
        Files.move(home.resolve("reseller-ledger.csv"), previous.resolve("reseller-ledger.csv"));
        Files.writeString(home.resolve("usage.csv"), "half-restored");

        assertTrue(service.recoverInterruptedRestore());
        assertFalse(service.recoverInterruptedRestore(), "nothing left to undo");

        Map<String, byte[]> now = snapshot(home);
        before.forEach((name, bytes) -> assertArrayEquals(bytes, now.get(name), name + " is back"));
    }

    @Test
    void anOlderBackupWarnsAboutNewerTransactionsAndTheCurrentDataIsSavedFirst() throws Exception {
        Path old = backups.resolve("old.zip");
        service.create(old, null);
        ResellerAccounts accounts = accounts(home);
        accounts.pay("p2", accounts.resellers().getFirst(), new BigDecimal("500"), "ل.س",
                java.time.LocalDate.of(2026, 11, 10), "newer");
        Files.writeString(home.resolve("usage.csv"), ROUTER + ",b,1M,x,2026-11-10\n",
                java.nio.file.StandardOpenOption.APPEND);

        BackupService.RestorePlan plan = service.check(old, null);
        assertTrue(plan.losesNewerData());
        assertEquals(1, plan.newerLedger(), "the payment made after the backup");
        assertEquals(1, plan.newerUsage(), "the sale recorded after the backup");

        clock.advance(Duration.ofMinutes(1));
        Path safety = service.restore(old, null, backups.resolve("safety")).orElseThrow();
        assertEquals(1, new BackupService(temp.resolve("check"), clock, "1.0.0").check(safety, null).backup()
                .ledgerLines() - 4, "the safety copy still holds the newer payment");
        assertEquals(new BigDecimal("400"), accounts(home).statements().getFirst().paid(), "the old backup's figures");
    }

    @Test
    void automaticBackupsKeepTheNewestAndNeverDeleteWhenANewOneFails() throws Exception {
        for (int i = 0; i < BackupService.KEEP_AUTOMATIC + 2; i++) {
            service.createAutomatic(backups);
            clock.advance(Duration.ofDays(1));
        }
        assertEquals(BackupService.KEEP_AUTOMATIC, count(backups), "the two oldest were removed");

        // the next backup cannot be written: a folder already sits where its file would go
        Path blocked = backups.resolve("NetPilot-auto-" + java.time.LocalDateTime.now(clock).format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
        Files.createDirectories(blocked.resolve("inside"));
        assertThrows(IOException.class, () -> service.createAutomatic(backups));
        assertEquals(BackupService.KEEP_AUTOMATIC, count(backups) - 1, "no old backup was removed");
        try (var files = Files.list(backups)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")),
                    "no half-written file is left behind");
        }
    }

    private static long count(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            return files.filter(f -> f.getFileName().toString().startsWith("NetPilot-auto-")).count();
        }
    }

    private static Map<String, byte[]> snapshot(Path dir) throws IOException {
        Map<String, byte[]> files = new java.util.TreeMap<>();
        try (var list = Files.list(dir)) {
            for (Path f : list.filter(Files::isRegularFile).toList()) {
                files.put(f.getFileName().toString(), Files.readAllBytes(f));
            }
        }
        return files;
    }
}
