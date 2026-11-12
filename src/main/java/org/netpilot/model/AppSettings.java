package org.netpilot.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Persisted application preferences.
 *
 * @param generation      last used generation settings
 * @param loginUrl        hotspot login page used for QR auto-login, e.g. {@code http://10.10.0.1/login}
 * @param vouchersPerRow  vouchers per row on the printed A4 page
 * @param outputDirectory where export folders are created
 * @param router          last router endpoint and trusted HTTPS certificates
 * @param voucherTemplate how printed vouchers look
 * @param ui              language, theme and colour
 * @param receipt         receipt printer and paper width
 * @param cleanup         automatic daily deletion of expired cards
 * @param lowStockBelow   warn when a package has fewer unused NetPilot cards than this; 0 = never
 * @param backup          automatic daily backup
 * @param notifications   messages to customers: country code, texts, when to remind
 */
public record AppSettings(
        GenerationSettings generation,
        String loginUrl,
        int vouchersPerRow,
        Path outputDirectory,
        RouterSettings router,
        VoucherTemplate voucherTemplate,
        UiPreferences ui,
        ReceiptSettings receipt,
        CleanupSettings cleanup,
        int lowStockBelow,
        BackupSettings backup,
        NotificationSettings notifications) {

    public static final int MIN_PER_ROW = 2;
    public static final int MAX_PER_ROW = 8;
    public static final int DEFAULT_LOW_STOCK = 10;
    public static final int MAX_LOW_STOCK = 9_999;

    public AppSettings {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        loginUrl = loginUrl == null ? "" : loginUrl.strip();
        vouchersPerRow = Math.clamp(vouchersPerRow, MIN_PER_ROW, MAX_PER_ROW);
        router = router == null ? RouterSettings.defaults() : router;
        voucherTemplate = voucherTemplate == null ? VoucherTemplate.defaults() : voucherTemplate;
        ui = ui == null ? UiPreferences.defaults() : ui;
        receipt = receipt == null ? ReceiptSettings.defaults() : receipt;
        cleanup = cleanup == null ? CleanupSettings.defaults() : cleanup;
        lowStockBelow = Math.clamp(lowStockBelow, 0, MAX_LOW_STOCK);
        backup = backup == null ? BackupSettings.defaults() : backup;
        notifications = notifications == null ? NotificationSettings.defaults() : notifications;
    }

    /** Settings with the default customer messages. */
    public AppSettings(GenerationSettings generation, String loginUrl, int vouchersPerRow, Path outputDirectory,
            RouterSettings router, VoucherTemplate voucherTemplate, UiPreferences ui, ReceiptSettings receipt,
            CleanupSettings cleanup, int lowStockBelow, BackupSettings backup) {
        this(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui, receipt, cleanup,
                lowStockBelow, backup, null);
    }

    /** Settings without automatic backup. */
    public AppSettings(GenerationSettings generation, String loginUrl, int vouchersPerRow, Path outputDirectory,
            RouterSettings router, VoucherTemplate voucherTemplate, UiPreferences ui, ReceiptSettings receipt,
            CleanupSettings cleanup, int lowStockBelow) {
        this(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui, receipt, cleanup,
                lowStockBelow, null, null);
    }

    /** Settings with the default receipt printer and no automatic clean-up. */
    public AppSettings(GenerationSettings generation, String loginUrl, int vouchersPerRow, Path outputDirectory,
            RouterSettings router, VoucherTemplate voucherTemplate, UiPreferences ui) {
        this(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui, null, null,
                DEFAULT_LOW_STOCK, null, null);
    }

    /** Settings with the default interface preferences. */
    public AppSettings(GenerationSettings generation, String loginUrl, int vouchersPerRow, Path outputDirectory,
            RouterSettings router, VoucherTemplate voucherTemplate) {
        this(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, null, null, null,
                DEFAULT_LOW_STOCK, null, null);
    }

    /** The Create cards form's part; everything else stays as it is. */
    public AppSettings withForm(GenerationSettings newGeneration, String newLoginUrl, int newPerRow, Path newOutput) {
        return new AppSettings(newGeneration, newLoginUrl, newPerRow, newOutput, router, voucherTemplate, ui, receipt,
                cleanup, lowStockBelow, backup, notifications);
    }

    public AppSettings withRouter(RouterSettings newRouter) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, newRouter, voucherTemplate, ui,
                receipt, cleanup, lowStockBelow, backup, notifications);
    }

    public AppSettings withVoucherTemplate(VoucherTemplate newTemplate) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, newTemplate, ui,
                receipt, cleanup, lowStockBelow, backup, notifications);
    }

    public AppSettings withUi(UiPreferences newUi) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, newUi,
                receipt, cleanup, lowStockBelow, backup, notifications);
    }

    public AppSettings withReceipt(ReceiptSettings newReceipt) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui,
                newReceipt, cleanup, lowStockBelow, backup, notifications);
    }

    public AppSettings withLowStockBelow(int below) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui,
                receipt, cleanup, below, backup, notifications);
    }

    public AppSettings withCleanup(CleanupSettings newCleanup) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui,
                receipt, newCleanup, lowStockBelow, backup, notifications);
    }

    public AppSettings withBackup(BackupSettings newBackup) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui,
                receipt, cleanup, lowStockBelow, newBackup, notifications);
    }

    public AppSettings withNotifications(NotificationSettings newNotifications) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui,
                receipt, cleanup, lowStockBelow, backup, newNotifications);
    }
}
