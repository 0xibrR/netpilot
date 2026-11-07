package org.netpilot.service;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.netpilot.model.CardSize;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.VoucherStyle;
import org.netpilot.model.VoucherTemplate;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPCellEvent;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Renders printable A4 voucher sheets from a {@link VoucherTemplate}: network name, optional logo,
 * credentials, time limit, price, QR auto-login code and a note. Arabic and English both work,
 * through {@link PdfText} and the embedded Cairo font.
 */
public final class PdfVoucherGenerator {

    private static final float PAGE_MARGIN = 18f;
    private static final float CELL_PADDING = 4f;
    /** Cairo has tall letters and marks (e.g. the hamza in لإ), so lines need extra room. */
    private static final float LINE_SPACING = 1.45f;
    private static final Color BORDER = new Color(0x9E, 0x9E, 0x9E);
    private static final Color LABEL = new Color(0x55, 0x5E, 0x6B);
    private static final Color VALUE = Color.BLACK;

    private final QrCodeService qrCodeService;

    public PdfVoucherGenerator(QrCodeService qrCodeService) {
        this.qrCodeService = Objects.requireNonNull(qrCodeService, "qrCodeService");
    }

    /**
     * @param loginUrl       hotspot login URL for QR codes; blank to print vouchers without QR
     * @param vouchersPerRow number of vouchers per row (2-8)
     */
    public void export(List<HotspotUser> users, Path file, String loginUrl, int vouchersPerRow,
            VoucherTemplate template) throws IOException {
        if (users.isEmpty()) {
            throw new IllegalArgumentException("No users to export");
        }
        boolean withQr = template.showQr() && loginUrl != null && !loginUrl.isBlank();
        if (withQr) {
            QrCodeService.validateLoginUrl(loginUrl);
        }
        if (template.style().compact()) {
            exportCompact(users, file, withQr ? loginUrl : null, template);
            return;
        }
        Optional<Image> logo = loadLogo(template);

        Document document = new Document(PageSize.A4, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN);
        try (OutputStream out = Files.newOutputStream(file)) {
            PdfWriter.getInstance(document, out);
            document.addTitle("NetPilot hotspot vouchers");
            document.addCreator("NetPilot");
            document.open();

            float cellWidth = (PageSize.A4.getWidth() - 2 * PAGE_MARGIN) / vouchersPerRow;
            Layout layout = Layout.forWidth(cellWidth, withQr);
            Voucher voucher = new Voucher(PdfText.instance(), template, layout, logo, withQr ? loginUrl : null);

            PdfPTable table = new PdfPTable(vouchersPerRow);
            table.setWidthPercentage(100);
            table.setSplitLate(true);
            for (HotspotUser user : users) {
                table.addCell(voucher.cell(user));
            }
            // PdfPTable silently drops an incomplete last row; pad it with invisible empty cells.
            table.getDefaultCell().setBorder(Rectangle.NO_BORDER);
            table.completeRow();

            document.add(table);
            document.close(); // must happen before the stream is closed
        } catch (DocumentException e) {
            throw new IOException("Failed to write PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Small cards in a fixed grid ({@link CardSize}); each card is drawn from {@link CardLayout},
     * the same layout the preview shows. Light dashed borders mark where to cut.
     */
    private void exportCompact(List<HotspotUser> users, Path file, String loginUrl, VoucherTemplate template)
            throws IOException {
        CardSize size = template.size();
        float margin = (float) CardSize.MARGIN;
        Optional<Image> background = template.style() == VoucherStyle.IMAGE
                ? loadImage(Optional.ofNullable(template.image().background()))
                : Optional.empty();
        Optional<Image> logo = loadImage(template.logoFile());
        PdfText text = PdfText.instance();
        Document document = new Document(PageSize.A4, margin, margin, margin, margin);
        try (OutputStream out = Files.newOutputStream(file)) {
            PdfWriter.getInstance(document, out);
            document.addTitle("NetPilot hotspot vouchers");
            document.addCreator("NetPilot");
            document.open();

            PdfPTable table = new PdfPTable(size.columns());
            table.setTotalWidth((float) (size.width() * size.columns()));
            table.setLockedWidth(true);
            for (HotspotUser user : users) {
                PdfPCell cell = new PdfPCell();
                cell.setFixedHeight((float) size.height());
                cell.setBorder(Rectangle.NO_BORDER);
                CardLayout.Card card = CardLayout.Card.of(user, template, loginUrl != null);
                byte[] qr = card.qr() ? qrCodeService.png(qrCodeService.loginLink(loginUrl, user), 240) : null;
                List<CardLayout.Element> elements = CardLayout.layout(template, card, size.width(), size.height(),
                        (value, fontSize) -> text.width(value, new PdfText.Style((float) fontSize, Color.BLACK)));
                cell.setCellEvent(new CardDrawer(text, elements, background, logo, qr));
                table.addCell(cell);
            }
            table.getDefaultCell().setBorder(Rectangle.NO_BORDER);
            table.getDefaultCell().setFixedHeight((float) size.height());
            table.completeRow();
            document.add(table);
            document.close();
        } catch (DocumentException e) {
            throw new IOException("Failed to write PDF: " + e.getMessage(), e);
        }
    }

    /** Draws one compact card at its place on the page. */
    private record CardDrawer(PdfText text, List<CardLayout.Element> elements, Optional<Image> background,
            Optional<Image> logo, byte[] qrPng) implements PdfPCellEvent {

        private static final Color CUT = new Color(0xC8, 0xCE, 0xD6);

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte back = canvases[PdfPTable.BACKGROUNDCANVAS];
            PdfContentByte front = canvases[PdfPTable.TEXTCANVAS];
            float left = position.getLeft();
            float top = position.getTop();
            for (CardLayout.Element element : elements) {
                switch (element) {
                    case CardLayout.Background b -> background.ifPresent(image -> place(back, image, left, top,
                            b.x(), b.y(), b.width(), b.height()));
                    case CardLayout.Fill f -> {
                        back.setColorFill(Color.decode(f.color()));
                        back.rectangle(left + (float) f.x(), top - (float) (f.y() + f.height()), (float) f.width(),
                                (float) f.height());
                        back.fill();
                    }
                    case CardLayout.Line l -> {
                        front.saveState();
                        front.setLineDash(2f, 2f, 0f);
                        front.setLineWidth(0.5f);
                        front.setColorStroke(Color.decode(l.color()));
                        front.moveTo(left + (float) l.x1(), top - (float) l.y1());
                        front.lineTo(left + (float) l.x2(), top - (float) l.y2());
                        front.stroke();
                        front.restoreState();
                    }
                    case CardLayout.Logo g -> logo.ifPresent(image -> fit(front, image, left, top, g));
                    case CardLayout.Box x -> {
                        front.saveState();
                        PdfGState state = new PdfGState();
                        state.setFillOpacity((float) x.opacity());
                        front.setGState(state);
                        front.setColorFill(Color.decode(x.color()));
                        front.roundRectangle(left + (float) x.x(), top - (float) (x.y() + x.height()),
                                (float) x.width(), (float) x.height(), (float) (x.height() / 4));
                        front.fill();
                        front.restoreState();
                    }
                    case CardLayout.Qr q -> drawQr(front, left, top, q);
                    case CardLayout.Text t -> drawText(front, left, top, t);
                }
            }
            // cut marks
            front.saveState();
            front.setLineDash(1.5f, 2.5f, 0f);
            front.setLineWidth(0.4f);
            front.setColorStroke(CUT);
            front.rectangle(left, position.getBottom(), position.getWidth(), position.getHeight());
            front.stroke();
            front.restoreState();
        }

        private void drawQr(PdfContentByte canvas, float left, float top, CardLayout.Qr q) {
            if (qrPng == null) {
                return;
            }
            try {
                place(canvas, Image.getInstance(qrPng), left, top, q.x(), q.y(), q.size(), q.size());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        private void drawText(PdfContentByte canvas, float left, float top, CardLayout.Text t) {
            if (t.text().isEmpty()) {
                return;
            }
            Phrase phrase = text.phrase(t.text(), new PdfText.Style((float) t.size(), Color.decode(t.color())));
            int align = switch (t.align()) {
                case LEFT -> Element.ALIGN_LEFT;
                case CENTER -> Element.ALIGN_CENTER;
                case RIGHT -> Element.ALIGN_RIGHT;
            };
            ColumnText.showTextAligned(canvas, align, phrase, left + (float) t.x(), top - (float) t.baseline(), 0);
        }

        private static void fit(PdfContentByte canvas, Image source, float left, float top, CardLayout.Logo box) {
            Image image = Image.getInstance(source);
            image.scaleToFit((float) box.width(), (float) box.height());
            double x = box.x() + (box.width() - image.getScaledWidth()) / 2;
            double y = box.y() + (box.height() - image.getScaledHeight()) / 2;
            image.setAbsolutePosition(left + (float) x, top - (float) y - image.getScaledHeight());
            canvas.addImage(image);
        }

        private static void place(PdfContentByte canvas, Image source, float left, float top, double x, double y,
                double width, double height) {
            Image image = Image.getInstance(source);
            image.scaleAbsolute((float) width, (float) height);
            image.setAbsolutePosition(left + (float) x, top - (float) (y + height));
            canvas.addImage(image);
        }
    }

    /** A missing or unreadable picture is left out rather than failing the whole export. */
    private static Optional<Image> loadImage(Optional<Path> file) {
        Optional<Path> path = file.filter(Files::isRegularFile);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Image.getInstance(Files.readAllBytes(path.get())));
        } catch (IOException | RuntimeException e) {
            System.getLogger(PdfVoucherGenerator.class.getName()).log(System.Logger.Level.WARNING,
                    "Voucher picture could not be read and is left out: " + path.get(), e);
            return Optional.empty();
        }
    }

    /** A missing or unreadable logo is left out rather than failing the whole export. */
    private static Optional<Image> loadLogo(VoucherTemplate template) {
        Optional<Path> path = template.logoFile().filter(Files::isRegularFile);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Image.getInstance(Files.readAllBytes(path.get())));
        } catch (IOException | RuntimeException e) { // BadElementException is a RuntimeException in OpenPDF 3
            System.getLogger(PdfVoucherGenerator.class.getName()).log(System.Logger.Level.WARNING,
                    "Voucher logo could not be read and is left out: " + path.get(), e);
            return Optional.empty();
        }
    }

    /** Builds one voucher cell; created once per export. */
    private final class Voucher {

        private final PdfText text;
        private final VoucherTemplate template;
        private final Layout layout;
        private final Optional<Image> logo;
        private final String loginUrl;
        private final PdfText.Style title;
        private final PdfText.Style label;
        private final PdfText.Style value;
        private final PdfText.Style small;

        Voucher(PdfText text, VoucherTemplate template, Layout layout, Optional<Image> logo, String loginUrl) {
            this.text = text;
            this.template = template;
            this.layout = layout;
            this.logo = logo;
            this.loginUrl = loginUrl;
            this.title = new PdfText.Style(layout.titleSize(), Color.decode(template.accentColor()));
            this.label = new PdfText.Style(layout.textSize(), LABEL);
            this.value = new PdfText.Style(layout.textSize() + 1.5f, VALUE);
            this.small = new PdfText.Style(Math.max(5f, layout.textSize() - 1.5f), LABEL);
        }

        PdfPCell cell(HotspotUser user) throws IOException {
            PdfPCell cell = new PdfPCell();
            cell.setPadding(CELL_PADDING);
            cell.setPaddingBottom(6);
            cell.setMinimumHeight(layout.minHeight());
            cell.setBorderColor(BORDER);
            cell.setUseAscender(true);

            if (logo.isPresent()) {
                Image image = Image.getInstance(logo.get());
                image.scaleToFit(layout.qrSize() * 0.8f, layout.textSize() * 3.2f);
                image.setAlignment(Element.ALIGN_CENTER);
                cell.addElement(image);
            }
            if (!template.networkName().isEmpty()) {
                addWrapped(cell, template.networkName(), title);
            }
            if (template.showProfile()) {
                cell.addElement(line(text.phrase(user.profile(), small), small));
            }
            cell.addElement(labeled(template.language().username(), user.username()));
            cell.addElement(labeled(template.language().password(), user.password()));
            if (template.showUptime() && !user.limitUptime().isBlank()) {
                cell.addElement(labeled(template.language().time(), user.limitUptime()));
            }
            Optional<String> price = template.priceFor(user.profile());
            if (template.showPrice() && price.isPresent()) {
                cell.addElement(labeled(template.language().price(), price.get()));
            }
            if (loginUrl != null) {
                byte[] png = qrCodeService.png(qrCodeService.loginLink(loginUrl, user), 300);
                Image qr = Image.getInstance(png);
                qr.scaleToFit(layout.qrSize(), layout.qrSize());
                qr.setAlignment(Element.ALIGN_CENTER);
                qr.setSpacingBefore(layout.textSize() * 0.4f);
                cell.addElement(qr);
            }
            if (!template.note().isEmpty()) {
                addWrapped(cell, template.note(), small);
            }
            return cell;
        }

        /** Long free text (title, note) is wrapped in reading order so Arabic sentences stay intact. */
        private void addWrapped(PdfPCell cell, String content, PdfText.Style style) {
            for (Phrase phrase : text.wrapped(content, style, layout.contentWidth())) {
                cell.addElement(line(phrase, style));
            }
        }

        /** {@code label: value}, with the value darker and slightly larger. */
        private Paragraph labeled(String labelText, String valueText) {
            return line(text.phrase(List.of(new PdfText.Segment(labelText + ": ", label),
                    new PdfText.Segment(valueText, value))), value);
        }

        private static Paragraph line(Phrase phrase, PdfText.Style style) {
            Paragraph p = new Paragraph(style.size() * LINE_SPACING);
            p.add(phrase);
            p.setAlignment(Element.ALIGN_CENTER);
            return p;
        }
    }

    /** Font and QR sizes that scale with the voucher width. */
    private record Layout(float titleSize, float textSize, float qrSize, float minHeight, float contentWidth) {

        /** Without a QR code the voucher is shorter, so more fit on a page. */
        static Layout forWidth(float cellWidth, boolean withQr) {
            float text = Math.clamp(cellWidth / 14f, 6f, 10f);
            float qr = Math.min(cellWidth * 0.62f, 90f);
            float content = cellWidth - 2 * CELL_PADDING - 2; // padding on both sides, plus the border
            return new Layout(text + 2.5f, text, qr, (withQr ? qr : 0) + text * 7f, content);
        }
    }
}
