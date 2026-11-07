package org.netpilot.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import org.netpilot.model.Money;
import org.netpilot.model.SalesReport;

/**
 * Writes a {@link SalesReport} as a CSV file with sections (summary, batches, profiles, days),
 * UTF-8 with BOM so Excel shows Arabic correctly.
 */
public final class ReportExporter {

    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public void export(SalesReport report, Path file) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write('﻿');
            row(w, "NetPilot sales report", report.period().toString());
            row(w, "Revenue", report.revenue().format());
            row(w, "Revenue still to come (unused cards)", report.potential().format());
            row(w, "Cards sold (first used)", String.valueOf(report.sold()));
            row(w, "Cards never used", String.valueOf(report.unused()));
            row(w, "Cards created", String.valueOf(report.created()));
            w.newLine();

            row(w, "Batch", "Created", "Profile", "Cards", "Used", "Unused", "Revenue");
            for (SalesReport.BatchRow b : report.batches()) {
                row(w, b.batch(), b.created().map(BATCH_TIME::format).orElse(""), b.profile(),
                        String.valueOf(b.cards()), String.valueOf(b.used()), String.valueOf(b.unused()),
                        b.revenue().format());
            }
            w.newLine();

            row(w, "Profile", "Price", "Sold", "Unused", "Revenue", "Data (bytes)");
            for (SalesReport.ProfileRow p : report.profiles()) {
                row(w, p.profile(), p.price().map(Money::format).orElse(""), String.valueOf(p.sold()),
                        String.valueOf(p.unused()), p.revenue().format(), String.valueOf(p.bytes()));
            }
            w.newLine();

            row(w, "Day", "Revenue");
            for (var day : report.dailyRevenue().entrySet()) {
                row(w, day.getKey().toString(), day.getValue().format());
            }

            if (!report.warnings().isEmpty()) {
                w.newLine();
                row(w, "Notes");
                for (String warning : report.warnings()) {
                    row(w, warning);
                }
            }
        }
    }

    private static void row(BufferedWriter w, String... fields) throws IOException {
        w.write(Csv.line(fields));
        w.newLine();
    }
}
