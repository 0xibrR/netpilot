package org.netpilot.controller;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import org.netpilot.i18n.Messages;
import org.netpilot.model.ReceiptSettings;
import org.netpilot.service.QrCodeService;
import org.netpilot.service.ReceiptContent;

/**
 * Draws receipts for 58/80 mm thermal printers and prints them through the printer's Windows
 * driver, one print job per card so the printer cuts after each.
 *
 * <p>Each receipt is drawn as a pure black-and-white picture, one pixel per printer dot: Arabic
 * text and the QR code then come out sharp on any thermal printer that has a driver.
 */
final class ReceiptPrinter {

    private static final String ARABIC_FONT = "Cairo";
    private static final String LATIN_FONT = "Segoe UI";
    private static final String CODE_FONT = "Consolas";

    private ReceiptPrinter() {
    }

    /** Printer names as Windows lists them, the default one first. */
    static List<String> printerNames() {
        Printer fallback = Printer.getDefaultPrinter();
        return Printer.getAllPrinters().stream()
                .sorted(java.util.Comparator.comparing((Printer p) -> !p.equals(fallback))
                        .thenComparing(Printer::getName))
                .map(Printer::getName)
                .toList();
    }

    /** The receipt as a black-and-white picture, {@link ReceiptSettings#dots()} pixels wide. */
    static WritableImage render(ReceiptContent content, ReceiptSettings settings, Optional<Path> logo) {
        VBox view = layout(content, settings.dots(), logo);
        new Scene(new VBox(view)); // CSS-free, but a scene gives the labels their final size
        view.applyCss();
        view.layout();
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.WHITE);
        return blackAndWhite(view.snapshot(parameters, null));
    }

    /** The receipt's layout: everything centred, labels in the card design's language. */
    static VBox layout(ReceiptContent content, int width, Optional<Path> logo) {
        double unit = width / 576.0; // sizes below are for an 80 mm roll; 58 mm scales them down
        VBox box = new VBox(10 * unit);
        box.setPadding(new Insets(14 * unit, 8 * unit, 26 * unit, 8 * unit));
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefWidth(width);
        box.setMinWidth(width);
        box.setMaxWidth(width);
        box.setStyle("-fx-background-color: white;");
        box.setNodeOrientation(content.rtl() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        String font = content.rtl() ? ARABIC_FONT : LATIN_FONT;

        logo.filter(Files::isRegularFile).ifPresent(path -> {
            ImageView picture = new ImageView(new Image(path.toUri().toString(), width * 0.45, 110 * unit, true,
                    true));
            box.getChildren().add(picture);
        });

        for (ReceiptContent.Line line : content.lines()) {
            switch (line) {
                case ReceiptContent.Title(String text) -> box.getChildren()
                        .add(label(text, Font.font(font, FontWeight.BOLD, 40 * unit), width));
                case ReceiptContent.Rule() -> box.getChildren().add(rule(width - 16 * unit, unit));
                case ReceiptContent.Code(String label, String value) -> {
                    box.getChildren().add(label(label, Font.font(font, 24 * unit), width));
                    box.getChildren().add(label(value, Font.font(CODE_FONT, FontWeight.BOLD, 52 * unit), width));
                }
                case ReceiptContent.Field(String label, String value) -> box.getChildren()
                        .add(field(label, value, Font.font(font, 26 * unit), width - 16 * unit));
                case ReceiptContent.Qr(String link) -> {
                    int size = (int) Math.round(width * 0.5);
                    ImageView qr = new ImageView(
                            new Image(new ByteArrayInputStream(new QrCodeService().png(link, size))));
                    qr.setSmooth(false);
                    box.getChildren().add(qr);
                }
                case ReceiptContent.Note(String text) -> box.getChildren()
                        .add(label(text, Font.font(font, 22 * unit), width));
            }
        }
        return box;
    }

    /**
     * Prints each picture as its own job on the chosen printer, on its narrowest paper of about
     * the roll width (else its default paper), scaled to the printable width.
     *
     * @throws IllegalStateException with a message for the user when printing fails
     */
    static void print(List<WritableImage> receipts, ReceiptSettings settings) {
        Printer printer = find(settings.printer()).orElseThrow(
                () -> new IllegalStateException(Messages.get("receipt.error.noPrinter", settings.printer())));
        PageLayout page = printer.createPageLayout(paper(printer, settings), PageOrientation.PORTRAIT,
                Printer.MarginType.HARDWARE_MINIMUM);
        for (WritableImage receipt : receipts) {
            PrinterJob job = PrinterJob.createPrinterJob(printer);
            if (job == null) {
                throw new IllegalStateException(Messages.get("receipt.error.failed", printer.getName()));
            }
            ImageView view = new ImageView(receipt);
            view.setPreserveRatio(true);
            view.setSmooth(false);
            view.setFitWidth(page.getPrintableWidth());
            // a fixed-height paper (e.g. 80 x 297 mm) must hold the whole receipt
            double height = page.getPrintableWidth() * receipt.getHeight() / receipt.getWidth();
            if (height > page.getPrintableHeight()) {
                view.setFitHeight(page.getPrintableHeight());
            }
            if (!job.printPage(page, view) || !job.endJob()) {
                job.cancelJob();
                throw new IllegalStateException(Messages.get("receipt.error.failed", printer.getName()));
            }
        }
    }

    private static Optional<Printer> find(String name) {
        if (name.isBlank()) {
            return Optional.ofNullable(Printer.getDefaultPrinter());
        }
        return Printer.getAllPrinters().stream().filter(p -> p.getName().equals(name)).findFirst();
    }

    /** Roll papers are named by the driver ("80(72.1) x 297 mm", "Roll 58mm"…); pick by width. */
    private static Paper paper(Printer printer, ReceiptSettings settings) {
        double rollPoints = settings.paperMm() / 25.4 * 72;
        return printer.getPrinterAttributes().getSupportedPapers().stream()
                .filter(p -> Math.abs(p.getWidth() - rollPoints) <= 6 * 72 / 25.4) // within 6 mm
                .min(java.util.Comparator.comparingDouble((Paper p) -> Math.abs(p.getWidth() - rollPoints))
                        .thenComparing(Paper::getHeight, java.util.Comparator.reverseOrder()))
                .orElse(printer.getDefaultPageLayout().getPaper());
    }

    /** Every pixel black or white: grey edges print as faint dots on thermal paper. */
    static WritableImage blackAndWhite(Image source) {
        int w = (int) source.getWidth();
        int h = (int) source.getHeight();
        WritableImage out = new WritableImage(w, h);
        PixelReader in = source.getPixelReader();
        PixelWriter pixels = out.getPixelWriter();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Color c = in.getColor(x, y);
                double light = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) * c.getOpacity()
                        + (1 - c.getOpacity());
                pixels.setColor(x, y, light < 0.6 ? Color.BLACK : Color.WHITE);
            }
        }
        return out;
    }

    private static Label label(String text, Font font, double width) {
        Label label = new Label(text);
        label.setFont(font);
        label.setTextFill(Color.BLACK);
        label.setWrapText(true);
        label.setTextAlignment(TextAlignment.CENTER);
        label.setAlignment(Pos.CENTER);
        label.setMaxWidth(width);
        return label;
    }

    /** Label at the start, value at the end of one row. */
    private static HBox field(String label, String value, Font font, double width) {
        Label name = label(label, font, width / 2);
        Label text = label(value, Font.font(font.getFamily(), FontWeight.BOLD, font.getSize()), width / 2);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(name, gap, text);
        row.setMaxWidth(width);
        row.setPrefWidth(width);
        return row;
    }

    private static Line rule(double length, double unit) {
        Line line = new Line(0, 0, length, 0);
        line.setStroke(Color.BLACK);
        line.setStrokeWidth(Math.max(1, 2 * unit));
        line.getStrokeDashArray().setAll(8 * unit, 6 * unit);
        return line;
    }
}
