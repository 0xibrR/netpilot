package org.netpilot.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the folders on this computer that a cloud app keeps in sync: OneDrive, Google Drive for
 * desktop and Dropbox. A file put in one is uploaded by that app, so NetPilot never needs the
 * user's account. Only folders that exist are offered.
 */
public final class CloudFolders {

    private static final Pattern DROPBOX_PATH = Pattern.compile("\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** A synced folder and the service behind it. */
    public record Folder(String service, Path path) {
    }

    private CloudFolders() {
    }

    /** The folders found on this computer. */
    public static List<Folder> find() {
        List<Path> roots = new ArrayList<>();
        for (java.io.File root : java.io.File.listRoots()) {
            roots.add(root.toPath());
        }
        return find(System.getenv(), Path.of(System.getProperty("user.home")), roots, Files::isDirectory);
    }

    /**
     * @param env     the environment (OneDrive sets {@code OneDrive}, {@code OneDriveConsumer},
     *                {@code OneDriveCommercial})
     * @param home    the user's home folder
     * @param roots   the drives (Google Drive for desktop appears as one, with "My Drive" in it)
     * @param present whether a folder exists
     */
    static List<Folder> find(Map<String, String> env, Path home, List<Path> roots, Predicate<Path> present) {
        Map<Path, Folder> found = new LinkedHashMap<>();
        for (String key : List.of("OneDriveConsumer", "OneDriveCommercial", "OneDrive")) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                add(found, "OneDrive", Path.of(value.strip()), present);
            }
        }
        add(found, "OneDrive", home.resolve("OneDrive"), present);

        for (Path root : roots) {
            add(found, "Google Drive", root.resolve("My Drive"), present);
        }
        add(found, "Google Drive", home.resolve("Google Drive").resolve("My Drive"), present);
        add(found, "Google Drive", home.resolve("My Drive"), present);

        dropboxFromInfo(env, home).ifPresent(path -> add(found, "Dropbox", path, present));
        add(found, "Dropbox", home.resolve("Dropbox"), present);
        return List.copyOf(found.values());
    }

    private static void add(Map<Path, Folder> found, String service, Path path, Predicate<Path> present) {
        Path normal = path.toAbsolutePath().normalize();
        if (present.test(normal)) {
            found.putIfAbsent(normal, new Folder(service, normal));
        }
    }

    /** Dropbox writes where its folder is in {@code %LOCALAPPDATA%\Dropbox\info.json}. */
    private static java.util.Optional<Path> dropboxFromInfo(Map<String, String> env, Path home) {
        String local = env.getOrDefault("LOCALAPPDATA", home.resolve("AppData/Local").toString());
        Path info = Path.of(local, "Dropbox", "info.json");
        try {
            if (!Files.isRegularFile(info)) {
                return java.util.Optional.empty();
            }
            Matcher m = DROPBOX_PATH.matcher(Files.readString(info, StandardCharsets.UTF_8));
            return m.find()
                    ? java.util.Optional.of(Path.of(m.group(1).replace("\\\\", "\\")))
                    : java.util.Optional.empty();
        } catch (IOException | java.nio.file.InvalidPathException e) {
            return java.util.Optional.empty();
        }
    }
}
