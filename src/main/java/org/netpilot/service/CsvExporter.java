package org.netpilot.service;

import org.netpilot.model.HotspotUser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes users to a CSV file (UTF-8 with BOM so Excel detects the encoding).
 */
public class CsvExporter {

    public void export(List<HotspotUser> users, Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write('﻿');
            writer.write(Csv.line("Username", "Password", "Profile", "Server", "Uptime Limit"));
            writer.newLine();
            for (HotspotUser user : users) {
                writer.write(Csv.line(
                        user.username(), user.password(), user.profile(), user.server(), user.limitUptime()));
                writer.newLine();
            }
        }
    }
}
