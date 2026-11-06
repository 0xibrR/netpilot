package org.netpilot.model;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One completed export.
 *
 * @param createdAt when the export finished
 * @param userCount number of users exported
 * @param profile   hotspot profile of the exported users
 * @param folder    folder that holds the exported files
 */
public record HistoryRecord(
        LocalDateTime createdAt,
        int userCount,
        String profile,
        Path folder) {

    public HistoryRecord {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(folder, "folder");
    }
}
