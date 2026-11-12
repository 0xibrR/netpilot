package org.netpilot;

import java.time.Clock;
import javafx.application.HostServices;
import org.netpilot.repository.AppPaths;
import org.netpilot.repository.DpapiProtector;
import org.netpilot.repository.HistoryRepository;
import org.netpilot.repository.PasswordVault;
import org.netpilot.repository.ResellerRepository;
import org.netpilot.repository.SettingsRepository;
import org.netpilot.repository.UsageRepository;
import org.netpilot.service.CsvExporter;
import org.netpilot.service.ExportService;
import org.netpilot.service.PdfVoucherGenerator;
import org.netpilot.service.QrCodeService;
import org.netpilot.service.ReportExporter;
import org.netpilot.service.ReportService;
import org.netpilot.service.ResellerAccounts;
import org.netpilot.service.RscExporter;
import org.netpilot.service.UsageTracker;
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
        PasswordVault passwordVault,
        ResellerAccounts resellerAccounts,
        UsageTracker usageTracker,
        org.netpilot.service.BackupService backupService,
        org.netpilot.repository.CustomerRepository customerRepository,
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
        ReportService reports = new ReportService();
        UsageRepository usage = new UsageRepository(AppPaths.usageFile());
        ResellerAccounts resellers = new ResellerAccounts(
                new ResellerRepository(AppPaths.resellersFile(), AppPaths.resellerLedgerFile()),
                Clock.systemDefaultZone());
        return new AppContext(
                generator,
                export,
                new SettingsRepository(AppPaths.settingsFile()),
                history,
                new RouterConnector(),
                new RouterUploadService(generator),
                new RouterUserActions(),
                reports,
                usage,
                new ReportExporter(),
                new NeighborDiscovery(),
                new PasswordVault(org.netpilot.platform.CredentialStore.forPlatform(
                        org.netpilot.platform.PlatformInfo.current(), AppPaths.credentialsFile(),
                        new DpapiProtector())),
                resellers,
                new UsageTracker(reports, usage, resellers),
                new org.netpilot.service.BackupService(AppPaths.dataDirectory(), Clock.systemDefaultZone(),
                        NetPilotApp.VERSION),
                new org.netpilot.repository.CustomerRepository(AppPaths.customersFile()),
                hostServices);
    }
}
