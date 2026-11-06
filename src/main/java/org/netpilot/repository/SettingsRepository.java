package org.netpilot.repository;

import org.netpilot.model.AppSettings;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.PasswordMode;
import org.netpilot.model.UsernameMode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Stores {@link AppSettings} in a properties file. Enums are stored by name, and every
 * missing or broken value falls back to its default on its own.
 */
public class SettingsRepository {

    private static final System.Logger LOG = System.getLogger(SettingsRepository.class.getName());

    public static final String DEFAULT_LOGIN_URL = "http://10.10.0.1/login";
    public static final int DEFAULT_PER_ROW = 5;

    private final Path file;

    public SettingsRepository(Path file) {
        this.file = file;
    }

    public AppSettings defaults() {
        return new AppSettings(GenerationSettings.defaults(), DEFAULT_LOGIN_URL, DEFAULT_PER_ROW,
                AppPaths.defaultOutputDirectory());
    }

    public AppSettings load() {
        if (!Files.exists(file)) {
            return defaults();
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read settings, using defaults", e);
            return defaults();
        }

        AppSettings d = defaults();
        GenerationSettings g = d.generation();
        GenerationSettings generation = new GenerationSettings(
                intValue(p, "count", g.count()),
                enumValue(p, "usernameMode", UsernameMode.class, g.usernameMode()),
                intValue(p, "usernameLength", g.usernameLength()),
                intValue(p, "usernameStart", g.usernameStart()),
                enumValue(p, "passwordMode", PasswordMode.class, g.passwordMode()),
                intValue(p, "passwordLength", g.passwordLength()),
                intValue(p, "passwordStart", g.passwordStart()),
                p.getProperty("passwordPrefix", g.passwordPrefix()),
                p.getProperty("profile", g.profile()),
                p.getProperty("server", g.server()),
                p.getProperty("limitUptime", g.limitUptime()));

        Path output = d.outputDirectory();
        String outputValue = p.getProperty("outputDirectory");
        if (outputValue != null && !outputValue.isBlank()) {
            try {
                output = Path.of(outputValue);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Invalid output directory in settings: " + outputValue);
            }
        }

        return new AppSettings(
                generation,
                p.getProperty("loginUrl", d.loginUrl()),
                intValue(p, "vouchersPerRow", d.vouchersPerRow()),
                output);
    }

    public void save(AppSettings settings) throws IOException {
        GenerationSettings g = settings.generation();
        Properties p = new Properties();
        p.setProperty("count", String.valueOf(g.count()));
        p.setProperty("usernameMode", g.usernameMode().name());
        p.setProperty("usernameLength", String.valueOf(g.usernameLength()));
        p.setProperty("usernameStart", String.valueOf(g.usernameStart()));
        p.setProperty("passwordMode", g.passwordMode().name());
        p.setProperty("passwordLength", String.valueOf(g.passwordLength()));
        p.setProperty("passwordStart", String.valueOf(g.passwordStart()));
        p.setProperty("passwordPrefix", g.passwordPrefix());
        p.setProperty("profile", g.profile());
        p.setProperty("server", g.server());
        p.setProperty("limitUptime", g.limitUptime());
        p.setProperty("loginUrl", settings.loginUrl());
        p.setProperty("vouchersPerRow", String.valueOf(settings.vouchersPerRow()));
        p.setProperty("outputDirectory", settings.outputDirectory().toString());

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(writer, "NetPilot settings");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static int intValue(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, "").strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(Properties p, String key, Class<E> type, E fallback) {
        try {
            return Enum.valueOf(type, p.getProperty(key, "").strip());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
