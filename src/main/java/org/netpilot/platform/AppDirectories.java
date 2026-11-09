package org.netpilot.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.netpilot.platform.PlatformInfo.OperatingSystem;

/**
 * Where NetPilot keeps its files on this kind of computer.
 *
 * <ul>
 * <li><b>Windows</b>: {@code ~/.netpilot} for data, settings and logs, exactly as before, so
 * existing data, backups and remembered passwords stay where they are.</li>
 * <li><b>macOS</b>: {@code ~/Library/Application Support/NetPilot}, logs in
 * {@code ~/Library/Logs/NetPilot}, cache in {@code ~/Library/Caches/NetPilot}.</li>
 * <li><b>Linux and others</b>: the XDG folders ({@code ~/.local/share/netpilot},
 * {@code ~/.config/netpilot}, {@code ~/.cache/netpilot}).</li>
 * </ul>
 * Outside Windows, an existing {@code ~/.netpilot} (from an earlier NetPilot or development) is
 * kept as long as the platform folder does not exist yet, so no data is left behind.
 *
 * @param data      settings, history, sales, points of sale ledger
 * @param config    settings file location (the same as {@code data} on Windows and macOS)
 * @param cache     files that can be rebuilt any time
 * @param logs      log files
 * @param documents where exported vouchers go by default
 */
public record AppDirectories(Path data, Path config, Path cache, Path logs, Path documents) {

    private static final AppDirectories CURRENT = of(PlatformInfo.current().os(), System.getenv(),
            Path.of(System.getProperty("user.home")));

    public AppDirectories {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(logs, "logs");
        Objects.requireNonNull(documents, "documents");
    }

    /** The folders of the computer NetPilot is running on now. */
    public static AppDirectories current() {
        return CURRENT;
    }

    /**
     * @param env  environment variables (for {@code XDG_*} on Linux)
     * @param home the user's home folder
     */
    public static AppDirectories of(OperatingSystem os, Map<String, String> env, Path home) {
        Path legacy = home.resolve(".netpilot");
        Path documents = documents(home);
        return switch (os) {
            case WINDOWS -> new AppDirectories(legacy, legacy, legacy.resolve("cache"), legacy.resolve("logs"),
                    documents);
            case MACOS -> {
                Path data = keepLegacy(legacy, home.resolve("Library/Application Support/NetPilot"));
                boolean old = data.equals(legacy);
                yield new AppDirectories(data, data,
                        old ? legacy.resolve("cache") : home.resolve("Library/Caches/NetPilot"),
                        old ? legacy.resolve("logs") : home.resolve("Library/Logs/NetPilot"), documents);
            }
            case LINUX, OTHER -> {
                Path data = keepLegacy(legacy, xdg(env, "XDG_DATA_HOME", home.resolve(".local/share")));
                boolean old = data.equals(legacy);
                yield new AppDirectories(data, old ? legacy : xdg(env, "XDG_CONFIG_HOME", home.resolve(".config")),
                        old ? legacy.resolve("cache") : xdg(env, "XDG_CACHE_HOME", home.resolve(".cache")),
                        data.resolve("logs"), documents);
            }
        };
    }

    private static Path keepLegacy(Path legacy, Path platform) {
        return Files.isDirectory(legacy) && !Files.isDirectory(platform) ? legacy : platform;
    }

    private static Path xdg(Map<String, String> env, String variable, Path fallback) {
        String value = env.getOrDefault(variable, "");
        Path base = value.isBlank() || !Path.of(value).isAbsolute() ? fallback : Path.of(value);
        return base.resolve("netpilot");
    }

    /** {@code ~/Documents/NetPilot} when a Documents folder exists, otherwise {@code ~/NetPilot}. */
    private static Path documents(Path home) {
        Path folder = home.resolve("Documents");
        return (Files.isDirectory(folder) ? folder : home).resolve("NetPilot");
    }
}
