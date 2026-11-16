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

    /**
     * The same report as an Excel workbook: summary, batches, packages and daily revenue on their
     * own sheets, amounts as numbers Excel can add up.
     *
     * @param branches every branch side by side, added as a sheet when there are two or more
     */
    public void exportXlsx(SalesReport report, java.util.Optional<BranchReport> branches, Path file)
            throws IOException {
        java.util.List<Xlsx.Sheet> sheets = new java.util.ArrayList<>();
        sheets.add(new Xlsx.Sheet(t("export.sheet.summary"), java.util.List.of(t("export.column.item"),
                t("export.column.value")),
                java.util.List.of(
                        java.util.List.of(Xlsx.text(t("export.period")), Xlsx.text(report.period().toString())),
                        java.util.List.of(Xlsx.text(t("report.revenue")), Xlsx.amount(report.revenue())),
                        java.util.List.of(Xlsx.text(t("report.potential")), Xlsx.amount(report.potential())),
                        java.util.List.of(Xlsx.text(t("report.sold")), Xlsx.count(report.sold())),
                        java.util.List.of(Xlsx.text(t("report.unused")), Xlsx.count(report.unused())),
                        java.util.List.of(Xlsx.text(t("report.created")), Xlsx.count(report.created())))));

        sheets.add(new Xlsx.Sheet(t("export.sheet.batches"), java.util.List.of(t("report.column.batch"),
                t("report.column.created"), t("report.column.profile"), t("report.column.cards"),
                t("report.column.used"), t("report.column.unused"), t("report.column.revenue")),
                report.batches().stream().map(b -> java.util.List.of(Xlsx.text(ReportService.batchLabel(b.batch())),
                        Xlsx.moment(b.created().orElse(null)), Xlsx.text(ReportService.profileLabel(b.profile())),
                        Xlsx.count(b.cards()), Xlsx.count(b.used()), Xlsx.count(b.unused()),
                        Xlsx.amount(b.revenue()))).toList()));

        sheets.add(new Xlsx.Sheet(t("export.sheet.packages"), java.util.List.of(t("report.column.profile"),
                t("report.column.price"), t("report.column.sold"), t("report.column.unused"),
                t("report.column.revenue"), t("export.column.megabytes")),
                report.profiles().stream().map(p -> java.util.List.of(Xlsx.text(p.profile()),
                        Xlsx.amount(p.price().orElse(null)), Xlsx.count(p.sold()), Xlsx.count(p.unused()),
                        Xlsx.amount(p.revenue()), Xlsx.count(Math.round(p.bytes() / 1_048_576.0)))).toList()));

        sheets.add(new Xlsx.Sheet(t("export.sheet.daily"), java.util.List.of(t("report.csv.day"),
                t("report.column.revenue")),
                report.dailyRevenue().entrySet().stream()
                        .map(d -> java.util.List.of(Xlsx.day(d.getKey()), Xlsx.amount(d.getValue()))).toList()));

        branches.filter(b -> b.lines().size() > 1).ifPresent(b -> {
            java.util.List<java.util.List<Xlsx.Cell>> rows = new java.util.ArrayList<>();
            for (BranchReport.Line line : b.lines()) {
                rows.add(branchRow(line, line.name()));
            }
            rows.add(branchRow(b.total(), t("reports.allBranches")));
            sheets.add(new Xlsx.Sheet(t("export.sheet.branches"), java.util.List.of(t("reports.column.branch"),
                    t("report.column.sold"), t("report.column.revenue"), t("report.unused"),
                    t("report.potential"), t("reports.column.readAt")), rows));
        });

        if (!report.warnings().isEmpty()) {
            sheets.add(new Xlsx.Sheet(t("report.csv.notes"), java.util.List.of(t("report.csv.notes")),
                    report.warnings().stream().map(w -> java.util.List.of(Xlsx.text(w))).toList()));
        }
        Xlsx.write(file, sheets, Messages.isArabic());
    }

    private static java.util.List<Xlsx.Cell> branchRow(BranchReport.Line line, String name) {
        return java.util.List.of(Xlsx.text(name), Xlsx.count(line.sold()), Xlsx.amount(line.revenue()),
                line.stock().map(s -> Xlsx.count(s.unused())).orElse(Xlsx.text("")),
                line.stock().map(s -> Xlsx.amount(s.value())).orElse(Xlsx.text("")),
                line.stock().map(s -> Xlsx.moment(s.at())).orElse(Xlsx.text("")));
    }

    private static String t(String key) {
        return Messages.get(key);
    }

    private static void row(BufferedWriter w, String... fields) throws IOException {
        w.write(Csv.line(fields));
        w.newLine();
    }
}
