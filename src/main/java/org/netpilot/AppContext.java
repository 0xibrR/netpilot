package org.netpilot;

import java.time.Clock;
import javafx.application.HostServices;
import org.netpilot.repository.AppPaths;
import org.netpilot.repository.HistoryRepository;
import org.netpilot.repository.SettingsRepository;
import org.netpilot.service.CsvExporter;
import org.netpilot.service.ExportService;
import org.netpilot.service.PdfVoucherGenerator;
import org.netpilot.service.QrCodeService;
import org.netpilot.service.RscExporter;
import org.netpilot.service.UserGenerator;
import org.netpilot.service.router.RouterConnector;
import org.netpilot.service.router.RouterUploadService;

/**
 * Wires the application's objects together (manual dependency injection).
 */
public record AppContext(
        UserGenerator userGenerator,
        ExportService exportService,
        SettingsRepository settingsRepository,
        HistoryRepository historyRepository,
        RouterConnector routerConnector,
        RouterUploadService routerUploadService,
        HostServices hostServices) {

    public static AppContext create(HostServices hostServices) {
        UserGenerator generator = new UserGenerator();
        HistoryRepository history = new HistoryRepository(AppPaths.historyFile());
        ExportService export = new ExportService(
                new CsvExporter(),
                new RscExporter(),
                new PdfVoucherGenerator(new QrCodeService()),
                history,
                Clock.systemDefaultZone());
        return new AppContext(
                generator,
                export,
                new SettingsRepository(AppPaths.settingsFile()),
                history,
                new RouterConnector(),
                new RouterUploadService(generator),
                hostServices);
    }
}
