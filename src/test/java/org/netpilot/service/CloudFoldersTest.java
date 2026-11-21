package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudFoldersTest {

    @TempDir
    Path dir;

    @Test
    void findsOneDriveGoogleDriveAndDropboxThatExistOnce() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path oneDrive = Files.createDirectories(home.resolve("OneDrive"));
        Path work = Files.createDirectories(dir.resolve("OneDrive - Company"));
        Path g = Files.createDirectories(dir.resolve("G"));
        Path myDrive = Files.createDirectories(g.resolve("My Drive"));
        Path empty = Files.createDirectories(dir.resolve("E")); // a drive without Google Drive
        Path dropbox = Files.createDirectories(dir.resolve("Box files"));
        Path local = Files.createDirectories(dir.resolve("local"));
        Files.createDirectories(local.resolve("Dropbox"));
        Files.writeString(local.resolve("Dropbox").resolve("info.json"),
                "{\"personal\": {\"path\": \"" + dropbox.toString().replace("\\", "\\\\") + "\", \"host\": 1}}",
                StandardCharsets.UTF_8);

        List<CloudFolders.Folder> found = CloudFolders.find(Map.of("OneDrive", oneDrive.toString(),
                "OneDriveCommercial", work.toString(), "LOCALAPPDATA", local.toString()), home, List.of(empty, g),
                Files::isDirectory);

        assertEquals(List.of(new CloudFolders.Folder("OneDrive", work.toAbsolutePath().normalize()),
                new CloudFolders.Folder("OneDrive", oneDrive.toAbsolutePath().normalize()),
                new CloudFolders.Folder("Google Drive", myDrive.toAbsolutePath().normalize()),
                new CloudFolders.Folder("Dropbox", dropbox.toAbsolutePath().normalize())), found,
                "each folder once, only those that exist");
    }

    @Test
    void nothingInstalledMeansNothingFound() {
        assertEquals(List.of(), CloudFolders.find(Map.of("OneDrive", " "), dir.resolve("nobody"),
                List.of(dir.resolve("X")), p -> false));
        assertEquals(List.of(), CloudFolders.find(Map.of("LOCALAPPDATA", dir.toString()), dir, List.of(), p -> false),
                "no info.json, no Dropbox");
    }
}
