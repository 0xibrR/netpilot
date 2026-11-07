package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.AppSettings;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;
import org.netpilot.repository.HistoryRepository;

class ExportServiceTest {

    @TempDir
    Path dir;

    @Test
    void writesAllFilesIntoNewFolderAndRecordsHistory() throws Exception {
        HistoryRepository history = new HistoryRepository(dir.resolve("history.csv"));
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T18:30:00Z"), ZoneOffset.UTC);
        ExportService service = new ExportService(new CsvExporter(), new RscExporter("t"),
                new PdfVoucherGenerator(new QrCodeService()), history, clock);
        AppSettings settings = new AppSettings(GenerationSettings.defaults(), "http://10.10.0.1/login", 5,
                dir.resolve("out"), null, null);
        List<HotspotUser> users = List.of(new HotspotUser("u1", "p1", "2M/x", "all", "1h"));

        ExportService.ExportResult first = service.export(users, settings);
        ExportService.ExportResult second = service.export(users, settings);

        assertEquals("2026-11-06_18-30-00_2M_x_1users", first.folder().getFileName().toString());
        assertNotEquals(first.folder(), second.folder(), "same-second exports must not overwrite each other");
        assertTrue(Files.exists(first.csvFile()));
        assertTrue(Files.exists(first.rscFile()));
        assertTrue(Files.exists(first.pdfFile()));
        assertEquals(2, history.findAll().size());
    }
}
