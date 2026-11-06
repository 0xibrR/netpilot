package org.netpilot.service;

import org.netpilot.model.AppSettings;
import org.netpilot.model.HistoryRecord;
import org.netpilot.model.HotspotUser;
import org.netpilot.repository.HistoryRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Exports one batch of users to a new folder (CSV + RSC + PDF) and records it in the history.
 */
public class ExportService {

    private static final DateTimeFormatter FOLDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final CsvExporter csvExporter;
    private final RscExporter rscExporter;
    private final PdfVoucherGenerator pdfGenerator;
    private final HistoryRepository historyRepository;
    private final Clock clock;

    public ExportService(CsvExporter csvExporter,
                         RscExporter rscExporter,
                         PdfVoucherGenerator pdfGenerator,
                         HistoryRepository historyRepository,
                         Clock clock) {
        this.csvExporter = Objects.requireNonNull(csvExporter);
        this.rscExporter = Objects.requireNonNull(rscExporter);
        this.pdfGenerator = Objects.requireNonNull(pdfGenerator);
        this.historyRepository = Objects.requireNonNull(historyRepository);
        this.clock = Objects.requireNonNull(clock);
    }

    public record ExportResult(Path folder, Path csvFile, Path rscFile, Path pdfFile, int userCount) {
    }

    public ExportResult export(List<HotspotUser> users, AppSettings settings) throws IOException {
        if (users.isEmpty()) {
            throw new IllegalArgumentException("Generate users before exporting.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String profile = users.getFirst().profile();
        String baseName = safeFileName(profile) + "_" + users.size() + "users";

        Path folder = uniqueFolder(settings.outputDirectory(), now.format(FOLDER_TIME) + "_" + baseName);
        Files.createDirectories(folder);

        Path csv = folder.resolve(baseName + ".csv");
        Path rsc = folder.resolve(baseName + ".rsc");
        Path pdf = folder.resolve(baseName + ".pdf");

        csvExporter.export(users, csv);
        rscExporter.export(users, rsc);
        pdfGenerator.export(users, pdf, settings.loginUrl(), settings.vouchersPerRow());

        historyRepository.append(new HistoryRecord(now, users.size(), profile, folder.toAbsolutePath()));
        return new ExportResult(folder, csv, rsc, pdf, users.size());
    }

    private static Path uniqueFolder(Path parent, String name) {
        Path candidate = parent.resolve(name);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = parent.resolve(name + "_" + i);
        }
        return candidate;
    }

    /** Keeps file names valid on Windows, macOS and RouterOS. */
    static String safeFileName(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "users" : cleaned;
    }
}
