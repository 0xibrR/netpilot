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
        CleanupSettings cleanup) {

    public static final int MIN_PER_ROW = 2;
    public static final int MAX_PER_ROW = 8;

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
    }

    /** Settings with the default receipt printer and no automatic clean-up. */
    public AppSettings(GenerationSettings generation, String loginUrl, int vouchersPerRow, Path outputDirectory,
            RouterSettings router, VoucherTemplate voucherTemplate, UiPreferences ui) {
        this(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui, null, null);
    }

    /** Settings with the default interface preferences. */
    public AppSettings(GenerationSettings generation, String loginUrl, int vouchersPerRow, Path outputDirectory,
            RouterSettings router, VoucherTemplate voucherTemplate) {
        this(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, null, null, null);
    }

    /** The Create cards form's part; everything else stays as it is. */
    public AppSettings withForm(GenerationSettings newGeneration, String newLoginUrl, int newPerRow, Path newOutput) {
        return new AppSettings(newGeneration, newLoginUrl, newPerRow, newOutput, router, voucherTemplate, ui, receipt,
                cleanup);
    }

    public AppSettings withRouter(RouterSettings newRouter) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, newRouter, voucherTemplate, ui,
                receipt, cleanup);
    }

    public AppSettings withVoucherTemplate(VoucherTemplate newTemplate) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, newTemplate, ui,
                receipt, cleanup);
    }

    public AppSettings withUi(UiPreferences newUi) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, newUi,
                receipt, cleanup);
    }

    public AppSettings withReceipt(ReceiptSettings newReceipt) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui,
                newReceipt, cleanup);
    }

    public AppSettings withCleanup(CleanupSettings newCleanup) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, outputDirectory, router, voucherTemplate, ui,
                receipt, newCleanup);
    }
}
