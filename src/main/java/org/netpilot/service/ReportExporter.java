package org.netpilot.service;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import org.netpilot.i18n.Messages;
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
            row(w, t("report.csv.title"), report.period().toString());
            row(w, t("report.revenue"), report.revenue().format());
            row(w, t("report.potential"), report.potential().format());
            row(w, t("report.sold"), String.valueOf(report.sold()));
            row(w, t("report.unused"), String.valueOf(report.unused()));
            row(w, t("report.created"), String.valueOf(report.created()));
            w.newLine();

            row(w, t("report.column.batch"), t("report.column.created"), t("report.column.profile"),
                    t("report.column.cards"), t("report.column.used"), t("report.column.unused"),
                    t("report.column.revenue"));
            for (SalesReport.BatchRow b : report.batches()) {
                row(w, ReportService.batchLabel(b.batch()), b.created().map(BATCH_TIME::format).orElse(""),
                        ReportService.profileLabel(b.profile()), String.valueOf(b.cards()), String.valueOf(b.used()),
                        String.valueOf(b.unused()), b.revenue().format());
            }
            w.newLine();

            row(w, t("report.column.profile"), t("report.column.price"), t("report.column.sold"),
                    t("report.column.unused"), t("report.column.revenue"), t("report.csv.bytes"));
            for (SalesReport.ProfileRow p : report.profiles()) {
                row(w, p.profile(), p.price().map(Money::format).orElse(""), String.valueOf(p.sold()),
                        String.valueOf(p.unused()), p.revenue().format(), String.valueOf(p.bytes()));
            }
            w.newLine();

            row(w, t("report.csv.day"), t("report.column.revenue"));
            for (var day : report.dailyRevenue().entrySet()) {
                row(w, day.getKey().toString(), day.getValue().format());
            }

            if (!report.warnings().isEmpty()) {
                w.newLine();
                row(w, t("report.csv.notes"));
                for (String warning : report.warnings()) {
                    row(w, warning);
                }
            }
        }
    }

    private static String t(String key) {
        return Messages.get(key);
    }

    private static void row(BufferedWriter w, String... fields) throws IOException {
        w.write(Csv.line(fields));
        w.newLine();
    }
}
