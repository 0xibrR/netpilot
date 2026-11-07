package org.netpilot.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.AppSettings;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HistoryRecord;
import org.netpilot.model.PasswordMode;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterSettings;
import org.netpilot.model.Theme;
import org.netpilot.model.UiLanguage;
import org.netpilot.model.UiPreferences;
import org.netpilot.model.UsernameMode;
import org.netpilot.model.VoucherTemplate;

class RepositoriesTest {

    @TempDir
    Path dir;

    @Test
    void settingsRoundTrip() throws Exception {
        SettingsRepository repo = new SettingsRepository(dir.resolve("sub/settings.properties"));
        AppSettings saved = new AppSettings(
                new GenerationSettings(50, UsernameMode.PROFILE_BASED, 7, 100,
                        PasswordMode.PREFIX_USERNAME, 8, 9000, "vip", "10M", "hotspot1", "1d"),
                "http://192.168.88.1/login", 4, dir.resolve("out"),
                new RouterSettings(new RouterEndpoint("10.0.0.1", 8443, true, "netpilot"), Map.of(
                        "10.0.0.1:8443", "ab".repeat(32),
                        "[fe80::1]:443", "cd".repeat(32)), true),
                new VoucherTemplate("شبكة الأمل", "صالح لمدة 30 يوماً", VoucherTemplate.Language.ARABIC, "#AA3300",
                        Map.of("2M", "1000 ل.س", "My Profile", "5$"), true, false, true, false, null),
                new UiPreferences(UiLanguage.ARABIC, Theme.SEMI_DARK, "#1E9E6A"));

        repo.save(saved);

        assertEquals(saved, repo.load());
        String stored = Files.readString(dir.resolve("sub/settings.properties"));
        assertTrue(!stored.toLowerCase().contains("password="), "router passwords are never stored");
    }

    @Test
    void logoIsCopiedNextToTheSettings() throws Exception {
        SettingsRepository repo = new SettingsRepository(dir.resolve("data/settings.properties"));
        Path original = Files.write(dir.resolve("my logo.PNG"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        Path stored = repo.storeLogo(original);
        Files.delete(original);

        assertEquals(dir.resolve("data/voucher-logo.png"), stored);
        assertTrue(Files.exists(stored), "the copy survives deleting the original");

        AppSettings withLogo = repo.defaults().withVoucherTemplate(VoucherTemplate.defaults().withLogo(stored));
        repo.save(withLogo);
        assertEquals(stored, repo.load().voucherTemplate().logo());

        Files.delete(stored);
        assertEquals(null, repo.load().voucherTemplate().logo(), "a deleted logo is dropped, not an error");
    }

    @Test
    void logoMustBeAnImage() throws Exception {
        SettingsRepository repo = new SettingsRepository(dir.resolve("settings.properties"));
        Path text = Files.writeString(dir.resolve("notes.txt"), "x");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> repo.storeLogo(text));
    }

    @Test
    void invalidTrustedFingerprintsAreIgnored() throws Exception {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "router.trustedCertificate.10.0.0.1\\:443=not-a-fingerprint\nrouter.port=x\n",
                StandardCharsets.UTF_8);

        AppSettings loaded = new SettingsRepository(file).load();

        assertTrue(loaded.router().trustedFingerprints().isEmpty());
        assertEquals(RouterEndpoint.defaults().port(), loaded.router().lastEndpoint().port());
    }

    @Test
    void missingSettingsFileGivesDefaults() {
        SettingsRepository repo = new SettingsRepository(dir.resolve("none.properties"));
        assertEquals(repo.defaults(), repo.load());
    }

    @Test
    void brokenValuesFallBackIndividually() throws Exception {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "count=abc\nusernameMode=NOPE\nprofile=5M\n", StandardCharsets.UTF_8);

        AppSettings loaded = new SettingsRepository(file).load();

        assertEquals(GenerationSettings.defaults().count(), loaded.generation().count());
        assertEquals(GenerationSettings.defaults().usernameMode(), loaded.generation().usernameMode());
        assertEquals("5M", loaded.generation().profile());
    }

    @Test
    void historyAppendsReadsNewestFirstAndClears() throws Exception {
        HistoryRepository repo = new HistoryRepository(dir.resolve("history.csv"));
        HistoryRecord older = new HistoryRecord(LocalDateTime.of(2026, 1, 1, 10, 0), 20, "2M", dir.resolve("a"));
        HistoryRecord newer = new HistoryRecord(LocalDateTime.of(2026, 2, 1, 10, 0), 30, "My, Profile",
                dir.resolve("b"));

        repo.append(older);
        repo.append(newer);

        assertEquals(List.of(newer, older), repo.findAll());

        repo.clear();
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void corruptHistoryLinesAreSkipped() throws Exception {
        Path file = dir.resolve("history.csv");
        HistoryRepository repo = new HistoryRepository(file);
        repo.append(new HistoryRecord(LocalDateTime.of(2026, 1, 1, 10, 0), 20, "2M", dir));
        Files.writeString(file, "garbage line\n2026-13-01T00:00,x,2M,c\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertEquals(1, repo.findAll().size());
    }
}
