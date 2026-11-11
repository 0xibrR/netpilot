package org.netpilot.service.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks GitHub for the latest NetPilot release and downloads its installer, checked against the
 * release's SHA-256 list. Nothing is sent but a plain request naming NetPilot and its version; no
 * account or token is used. Files are only taken from this repository's own release downloads.
 */
public final class UpdateChecker {

    private static final System.Logger LOG = System.getLogger(UpdateChecker.class.getName());
    private static final JsonMapper JSON = JsonMapper.shared();

    /** The repository releases are published from. */
    public static final String REPOSITORY = "0xibrR/netpilot";

    private final HttpClient http;
    private final URI api;
    private final String downloadPrefix;
    private final AppVersion current;
    private final String userAgent;

    /** Reading progress of a download. */
    @FunctionalInterface
    public interface Progress {

        /** @param total the size, or 0 when unknown */
        void update(long done, long total);
    }

    /**
     * @param api            GitHub's API, {@code https://api.github.com}
     * @param downloadPrefix the only place files may come from, e.g.
     *                       {@code https://github.com/0xibrR/netpilot/releases/download/}
     * @param currentVersion this NetPilot's version
     */
    public UpdateChecker(HttpClient http, URI api, String downloadPrefix, String currentVersion) {
        this.http = Objects.requireNonNull(http);
        this.api = Objects.requireNonNull(api);
        this.downloadPrefix = Objects.requireNonNull(downloadPrefix);
        this.current = AppVersion.parse(currentVersion).orElse(new AppVersion(0, 0, 0));
        this.userAgent = "NetPilot/" + currentVersion + " (+https://github.com/" + REPOSITORY + ")";
    }

    /** The real GitHub. */
    public static UpdateChecker gitHub(String currentVersion) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL) // downloads redirect to GitHub's file servers (HTTPS)
                .build();
        return new UpdateChecker(http, URI.create("https://api.github.com"),
                "https://github.com/" + REPOSITORY + "/releases/download/", currentVersion);
    }

    public AppVersion current() {
        return current;
    }

    /**
     * The latest published release (GitHub leaves out drafts and pre-releases).
     *
     * @return empty when there is none yet
     * @throws IOException when GitHub cannot be reached or answers with an error
     */
    public Optional<Release> latest() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(api.resolve("/repos/" + REPOSITORY + "/releases/latest"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .GET().build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return Optional.empty(); // nothing published yet
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode());
        }
        return parse(response.body());
    }

    /** A release newer than this NetPilot, if there is one. */
    public Optional<Release> newer() throws IOException {
        return latest().filter(r -> r.version().newerThan(current));
    }

    /** Reads GitHub's release JSON; a release whose tag is not a plain version is ignored. */
    static Optional<Release> parse(String json) {
        JsonNode node = JSON.readTree(json);
        if (node.path("draft").asBoolean(false) || node.path("prerelease").asBoolean(false)) {
            return Optional.empty();
        }
        String tag = node.path("tag_name").asString("");
        Optional<AppVersion> version = AppVersion.parse(tag);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        List<Release.Asset> assets = new ArrayList<>();
        for (JsonNode a : node.path("assets")) {
            String name = a.path("name").asString("");
            String url = a.path("browser_download_url").asString("");
            if (!name.isEmpty() && !url.isEmpty()) {
                assets.add(new Release.Asset(name, url, a.path("size").asLong(0)));
            }
        }
        return Optional.of(new Release(version.get(), tag, node.path("body").asString(""),
                node.path("html_url").asString(""), assets));
    }

    /**
     * Downloads the release's installer into {@code folder} and checks it against the release's
     * SHA-256 list. A file that does not match is deleted.
     *
     * @param cancelled checked while downloading; true stops the download and deletes the part read
     * @return the checked installer
     * @throws IOException when there is no installer or checksum, a file is not from this
     *                     repository's releases, or the checksum does not match
     * @throws CancellationException when cancelled
     */
    public Path download(Release release, Path folder, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        Release.Asset installer = release.installer().orElseThrow(() -> new IOException("No installer in release"));
        Release.Asset sums = release.checksums().orElseThrow(() -> new IOException("No SHA256SUMS in release"));
        String expected = checksums(fetchText(sums)).get(installer.name());
        if (expected == null) {
            throw new IOException("SHA256SUMS has no line for " + installer.name());
        }
        // only the file name: a name with folders in it could never write outside the updates folder
        Path name = Path.of(installer.name()).getFileName();
        if (name == null || !name.toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            throw new IOException("Not an installer name: " + installer.name());
        }
        String safeName = name.toString();
        Files.createDirectories(folder);
        Path part = Files.createTempFile(folder, ".netpilot-update-", ".part");
        try {
            String actual = fetchFile(installer, part, progress, cancelled);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("The download does not match its checksum");
            }
            Path target = folder.resolve(safeName);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.log(System.Logger.Level.INFO, "Update {0} downloaded and checked: {1}", release.version(), target);
            return target;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** {@code <hash>  <name>} or {@code <hash> *<name>} lines, as sha256sum writes them. */
    static Map<String, String> checksums(String text) {
        Map<String, String> out = new HashMap<>();
        for (String line : text.split("\\R")) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length == 2 && parts[0].matches("[0-9a-fA-F]{64}")) {
                String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
                out.put(name.strip(), parts[0].toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private String fetchText(Release.Asset asset) throws IOException {
        HttpResponse<String> response = send(request(asset),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Download of " + asset.name() + " answered " + response.statusCode());
        }
        return response.body();
    }

    /** @return the file's SHA-256, hex */
    private String fetchFile(Release.Asset asset, Path target, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        HttpResponse<InputStream> response = send(request(asset), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download of " + asset.name() + " answered " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(asset.size());
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Update download cancelled");
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                progress.update(done, total);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private HttpRequest request(Release.Asset asset) throws IOException {
        if (!asset.url().startsWith(downloadPrefix)) {
            throw new IOException("Not a NetPilot release download: " + asset.url());
        }
        return HttpRequest.newBuilder(URI.create(asset.url()))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", userAgent)
                .header("Accept", "application/octet-stream")
                .GET().build();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
