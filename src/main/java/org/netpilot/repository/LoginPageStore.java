package org.netpilot.repository;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.netpilot.model.LoginPageDesign;

/**
 * The hotspot login page design, and for each router the page folder its hotspot profiles used
 * before NetPilot's, so "back to the previous page" restores exactly what was there.
 */
public final class LoginPageStore {

    private static final System.Logger LOG = System.getLogger(LoginPageStore.class.getName());
    private static final String PREVIOUS = "previous.";

    private final Path file;

    public LoginPageStore(Path file) {
        this.file = Objects.requireNonNull(file);
    }

    public synchronized LoginPageDesign design() {
        Properties p = load();
        LoginPageDesign d = LoginPageDesign.defaults();
        LoginPageDesign.Language language;
        try {
            language = LoginPageDesign.Language.valueOf(p.getProperty("language", d.language().name()));
        } catch (IllegalArgumentException e) {
            language = d.language();
        }
        return new LoginPageDesign(p.getProperty("title", d.title()), p.getProperty("welcome", d.welcome()),
                p.getProperty("phone", d.phone()), p.getProperty("whatsApp", d.whatsApp()),
                p.getProperty("color", d.color()), flag(p, "showPrices", d.showPrices()),
                flag(p, "showLogo", d.showLogo()), flag(p, "passwordField", d.passwordField()), language,
                flag(p, "showSellers", d.showSellers()), p.getProperty("logo", ""));
    }

    public synchronized void save(LoginPageDesign d) throws IOException {
        Properties p = load();
        p.setProperty("title", d.title());
        p.setProperty("welcome", d.welcome());
        p.setProperty("phone", d.phone());
        p.setProperty("whatsApp", d.whatsApp());
        p.setProperty("color", d.color());
        p.setProperty("showPrices", String.valueOf(d.showPrices()));
        p.setProperty("showLogo", String.valueOf(d.showLogo()));
        p.setProperty("passwordField", String.valueOf(d.passwordField()));
        p.setProperty("language", d.language().name());
        p.setProperty("showSellers", String.valueOf(d.showSellers()));
        p.setProperty("logo", d.logo());
        write(p);
    }

    /**
     * The page folder of each hotspot profile on this router before NetPilot's page, by profile
     * name; empty when NetPilot's page was never sent there (or was taken back).
     */
    public synchronized Map<String, String> previous(String router) {
        Map<String, String> out = new HashMap<>();
        String prefix = PREVIOUS + router + ".";
        load().forEach((k, v) -> {
            if (String.valueOf(k).startsWith(prefix)) {
                out.put(String.valueOf(k).substring(prefix.length()), String.valueOf(v));
            }
        });
        return out;
    }

    /** Remembers the folders before NetPilot's page; folders already remembered are kept. */
    public synchronized void rememberPrevious(String router, Map<String, String> folders) throws IOException {
        Properties p = load();
        folders.forEach((profile, folder) -> p.putIfAbsent(PREVIOUS + router + "." + profile, folder));
        write(p);
    }

    public synchronized void forgetPrevious(String router) throws IOException {
        Properties p = load();
        p.keySet().removeIf(k -> String.valueOf(k).startsWith(PREVIOUS + router + "."));
        write(p);
    }

    private static boolean flag(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private Properties load() {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING, "Login page settings unreadable: {0}", e.getMessage());
            }
        }
        return p;
    }

    private void write(Properties p) throws IOException {
        Path folder = Objects.requireNonNullElse(file.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".login-page-", ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                p.store(out, "NetPilot hotspot login page");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
