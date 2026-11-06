package org.netpilot.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File helpers shared by the repositories.
 */
final class FileSupport {

    private FileSupport() {
    }

    /** Creates the folder that will hold {@code file}, if it has one (a bare root path has none). */
    static void createParentDirectories(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
