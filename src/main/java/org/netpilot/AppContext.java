package org.netpilot;

import java.time.Clock;
import javafx.application.HostServices;
import org.netpilot.repository.AppPaths;
import org.netpilot.repository.HistoryRepository;
import org.netpilot.repository.SettingsRepository;
import org.netpilot.repository.UsageRepository;
import org.netpilot.service.CsvExporter;
import org.netpilot.service.ExportService;
import org.netpilot.service.PdfVoucherGenerator;
import org.netpilot.service.QrCodeService;
import org.netpilot.service.ReportExporter;
import org.netpilot.service.ReportService;
import org.netpilot.service.RscExporter;
import org.netpilot.service.UserGenerator;
import org.netpilot.service.router.NeighborDiscovery;
import org.netpilot.service.router.RouterConnector;
import org.netpilot.service.router.RouterUploadService;
import org.netpilot.service.router.RouterUserActions;

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
        RouterUserActions routerUserActions,
        ReportService reportService,
        UsageRepository usageRepository,
        ReportExporter reportExporter,
        NeighborDiscovery neighborDiscovery,
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
                new RouterUserActions(),
                new ReportService(),
                new UsageRepository(AppPaths.usageFile()),
                new ReportExporter(),
                new NeighborDiscovery(),
                hostServices);
    }
}
