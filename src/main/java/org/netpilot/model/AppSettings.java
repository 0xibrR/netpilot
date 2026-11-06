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
 */
public record AppSettings(
        GenerationSettings generation,
        String loginUrl,
        int vouchersPerRow,
        Path outputDirectory) {

    public static final int MIN_PER_ROW = 2;
    public static final int MAX_PER_ROW = 8;

    public AppSettings {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        loginUrl = loginUrl == null ? "" : loginUrl.strip();
        vouchersPerRow = Math.clamp(vouchersPerRow, MIN_PER_ROW, MAX_PER_ROW);
    }

    public AppSettings withGeneration(GenerationSettings newGeneration) {
        return new AppSettings(newGeneration, loginUrl, vouchersPerRow, outputDirectory);
    }

    public AppSettings withLoginUrl(String newLoginUrl) {
        return new AppSettings(generation, newLoginUrl, vouchersPerRow, outputDirectory);
    }

    public AppSettings withVouchersPerRow(int newVouchersPerRow) {
        return new AppSettings(generation, loginUrl, newVouchersPerRow, outputDirectory);
    }

    public AppSettings withOutputDirectory(Path newOutputDirectory) {
        return new AppSettings(generation, loginUrl, vouchersPerRow, newOutputDirectory);
    }
}
