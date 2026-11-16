package org.netpilot.controller;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.netpilot.i18n.Messages;

/** The save dialog of an export: Excel first, CSV as the other choice where it is offered. */
final class ExportFiles {

    private ExportFiles() {
    }

    /** Where to save, and whether as Excel or CSV. */
    record Target(Path file, boolean excel) {
    }

    /**
     * @param baseName the suggested file name without extension
     * @param csvToo   offer CSV too (the exports that had it before)
     */
    static Optional<Target> choose(Window owner, String title, String baseName, boolean csvToo) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        String safe = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        chooser.setInitialFileName(safe + ".xlsx");
        FileChooser.ExtensionFilter excel = new FileChooser.ExtensionFilter(Messages.get("export.file.excel"),
                "*.xlsx");
        chooser.getExtensionFilters().add(excel);
        if (csvToo) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        }
        File file = chooser.showSaveDialog(owner);
        if (file == null) {
            return Optional.empty();
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        boolean csv = csvToo && (name.endsWith(".csv") || chooser.getSelectedExtensionFilter() != excel);
        Path path = file.toPath();
        // the chosen type wins over a missing or other extension
        if (csv && !name.endsWith(".csv")) {
            path = path.resolveSibling(file.getName().replaceAll("\\.xlsx$", "") + ".csv");
        } else if (!csv && !name.endsWith(".xlsx")) {
            path = path.resolveSibling(file.getName() + ".xlsx");
        }
        return Optional.of(new Target(path, !csv));
    }
}
