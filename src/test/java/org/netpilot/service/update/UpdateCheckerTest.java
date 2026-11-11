package org.netpilot.service.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCheckerTest {

    private static final byte[] INSTALLER = "pretend this is NetPilot-1.3.0.exe".repeat(4000)
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private String base;

    private record Reply(int status, byte[] body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            Reply reply = replies.getOrDefault(exchange.getRequestURI().getPath(), new Reply(404, new byte[0]));
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply.body());
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private UpdateChecker checker(String version) {
        return new UpdateChecker(HttpClient.newHttpClient(), URI.create(base), base + "/download/", version);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String releaseJson(String tag, String exeUrl, String sumsUrl) {
        return """
                {"tag_name":"%s","name":"NetPilot","body":"### Added\\n- Something new","draft":false,
                 "prerelease":false,"html_url":"https://github.com/0xibrR/netpilot/releases/tag/%s",
                 "assets":[{"name":"NetPilot-1.3.0.exe","size":%d,"browser_download_url":"%s"},
                           {"name":"SHA256SUMS","size":100,"browser_download_url":"%s"}]}
                """.formatted(tag, tag, INSTALLER.length, exeUrl, sumsUrl);
    }

    private void publish(String tag, String checksum) {
        replies.put("/repos/0xibrR/netpilot/releases/latest", new Reply(200, releaseJson(tag,
                base + "/download/v1.3.0/NetPilot-1.3.0.exe", base + "/download/v1.3.0/SHA256SUMS")
                .getBytes(StandardCharsets.UTF_8)));
        replies.put("/download/v1.3.0/NetPilot-1.3.0.exe", new Reply(200, INSTALLER));
        replies.put("/download/v1.3.0/SHA256SUMS", new Reply(200,
                (checksum + " *NetPilot-1.3.0.exe\n" + "0".repeat(64) + "  NetPilot-1.3.0-windows.zip\n")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void versionsCompareNumberByNumberAndTestBuildsAreNeverOffered() {
        AppVersion v190 = AppVersion.parse("1.9.0").orElseThrow();
        AppVersion v1100 = AppVersion.parse("v1.10.0").orElseThrow();
        assertTrue(v1100.newerThan(v190), "1.10 is newer than 1.9");
        assertEquals("1.2.0", AppVersion.parse("1.2").orElseThrow().toString());
        assertFalse(v190.newerThan(v190));
        for (String notAVersion : List.of("", "1.3.0-beta", "1.2.0-SNAPSHOT", "latest", "1", "v1.2.3.4")) {
            assertEquals(Optional.empty(), AppVersion.parse(notAVersion), notAVersion);
        }
    }

    @Test
    void readsTheLatestReleaseAndOffersItOnlyWhenNewer() throws Exception {
        publish("v1.3.0", sha256(INSTALLER));

        Release release = checker("1.2.0").newer().orElseThrow();
        assertEquals("1.3.0", release.version().toString());
        assertTrue(release.notes().contains("Something new"));
        assertEquals("NetPilot-1.3.0.exe", release.installer().orElseThrow().name());
        assertTrue(release.checksums().isPresent());
        assertTrue(checker("1.3.0").newer().isEmpty(), "same version: nothing to offer");
        assertTrue(checker("1.4.0").newer().isEmpty(), "older release: nothing to offer");
    }

    @Test
    void noReleaseYetOrAnErrorIsNotAnUpdate() throws Exception {
        assertTrue(checker("1.2.0").latest().isEmpty(), "404: nothing published");
        replies.put("/repos/0xibrR/netpilot/releases/latest", new Reply(403, "rate limited".getBytes()));
        assertThrows(IOException.class, () -> checker("1.2.0").latest());
        assertTrue(UpdateChecker.parse("""
                {"tag_name":"v9.0.0","draft":false,"prerelease":true,"assets":[]}""").isEmpty(), "pre-release");
        assertTrue(UpdateChecker.parse("""
                {"tag_name":"nightly","draft":false,"prerelease":false,"assets":[]}""").isEmpty(), "not a version");
    }

    @Test
    void downloadsTheInstallerAndChecksItsFingerprint() throws Exception {
        publish("v1.3.0", sha256(INSTALLER));
        Release release = checker("1.2.0").newer().orElseThrow();
        AtomicInteger calls = new AtomicInteger();

        Path file = checker("1.2.0").download(release, dir.resolve("updates"), (done, total) -> calls.incrementAndGet(),
                () -> false);

        assertEquals("NetPilot-1.3.0.exe", file.getFileName().toString());
        assertArrayEquals(INSTALLER, Files.readAllBytes(file));
        assertTrue(calls.get() > 0, "progress was reported");
        try (var files = Files.list(dir.resolve("updates"))) {
            assertEquals(1, files.count(), "no partial file left");
        }
    }

    @Test
    void aDownloadThatDoesNotMatchIsDeleted() throws Exception {
        publish("v1.3.0", "a".repeat(64));
        Release release = checker("1.2.0").newer().orElseThrow();

        assertThrows(IOException.class,
                () -> checker("1.2.0").download(release, dir.resolve("updates"), (d, t) -> {
                }, () -> false));
        try (var files = Files.list(dir.resolve("updates"))) {
            assertEquals(0, files.count(), "a damaged or changed file is never kept");
        }
    }

    @Test
    void cancellingStopsAndLeavesNothing() throws Exception {
        publish("v1.3.0", sha256(INSTALLER));
        Release release = checker("1.2.0").newer().orElseThrow();

        assertThrows(CancellationException.class,
                () -> checker("1.2.0").download(release, dir.resolve("updates"), (d, t) -> {
                }, () -> true));
        try (var files = Files.list(dir.resolve("updates"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void filesFromAnywhereElseAreRefused() throws Exception {
        replies.put("/repos/0xibrR/netpilot/releases/latest", new Reply(200, releaseJson("v1.3.0",
                "https://evil.example/NetPilot-1.3.0.exe", base + "/download/v1.3.0/SHA256SUMS")
                .getBytes(StandardCharsets.UTF_8)));
        replies.put("/download/v1.3.0/SHA256SUMS", new Reply(200,
                (sha256(INSTALLER) + "  NetPilot-1.3.0.exe\n").getBytes(StandardCharsets.UTF_8)));
        Release release = checker("1.2.0").newer().orElseThrow();
        int before = requests.get();

        IOException refused = assertThrows(IOException.class,
                () -> checker("1.2.0").download(release, dir.resolve("updates"), (d, t) -> {
                }, () -> false));
        assertTrue(refused.getMessage().contains("Not a NetPilot release download"));
        assertEquals(before + 1, requests.get(), "only the checksum list was fetched, never the other file");
    }

    @Test
    void readsChecksumLinesInBothFormats() {
        Map<String, String> sums = UpdateChecker.checksums("a".repeat(64) + " *x.exe\r\n"
                + "B".repeat(64) + "  y.zip\n" + "not a line\n");
        assertEquals("a".repeat(64), sums.get("x.exe"));
        assertEquals("b".repeat(64), sums.get("y.zip"));
        assertEquals(2, sums.size());
    }

    @Test
    void preferencesRememberTheDayAndTheSkippedVersion() throws Exception {
        Release r130 = new Release(new AppVersion(1, 3, 0), "v1.3.0", "", "", List.of());
        Release r140 = new Release(new AppVersion(1, 4, 0), "v1.4.0", "", "", List.of());
        LocalDate today = LocalDate.of(2026, 11, 12);

        UpdatePreferences fresh = UpdatePreferences.defaults();
        assertTrue(fresh.due(today));
        assertTrue(fresh.remind(r130));
        UpdatePreferences checked = fresh.checked(today).skip(r130);
        assertFalse(checked.due(today), "once a day");
        assertTrue(checked.due(today.plusDays(1)));
        assertFalse(checked.remind(r130), "skipped");
        assertTrue(checked.remind(r140), "a newer one is offered again");
        assertFalse(checked.withAutomatic(false).due(today.plusDays(5)), "turned off");

        Path file = dir.resolve("update.properties");
        checked.save(file);
        assertEquals(checked, UpdatePreferences.load(file));
        Files.writeString(file, "lastCheck=yesterday\nautomatic=true\n");
        assertTrue(UpdatePreferences.load(file).lastCheck().isEmpty(), "an unreadable date is ignored");
        assertEquals(UpdatePreferences.defaults(), UpdatePreferences.load(dir.resolve("missing.properties")));
    }
}
