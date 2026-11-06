package org.netpilot.service;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.netpilot.model.HotspotUser;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Renders printable A4 voucher sheets, optionally with an auto-login QR code per voucher.
 */
public final class PdfVoucherGenerator {

    private static final float PAGE_MARGIN = 18f;
    private static final Color BORDER = new Color(0x9E, 0x9E, 0x9E);
    private static final Color ACCENT = new Color(0x1F, 0x5F, 0xA8);

    private final QrCodeService qrCodeService;

    public PdfVoucherGenerator(QrCodeService qrCodeService) {
        this.qrCodeService = Objects.requireNonNull(qrCodeService, "qrCodeService");
    }

    /**
     * @param loginUrl       hotspot login URL for QR codes; blank to print vouchers without QR
     * @param vouchersPerRow number of vouchers per row (2-8)
     */
    public void export(List<HotspotUser> users, Path file, String loginUrl, int vouchersPerRow)
            throws IOException {
        if (users.isEmpty()) {
            throw new IllegalArgumentException("No users to export");
        }
        boolean withQr = loginUrl != null && !loginUrl.isBlank();
        if (withQr) {
            QrCodeService.validateLoginUrl(loginUrl);
        }

        Document document = new Document(PageSize.A4, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN);
        try (OutputStream out = Files.newOutputStream(file)) {
            PdfWriter.getInstance(document, out);
            document.addTitle("NetPilot hotspot vouchers");
            document.addCreator("NetPilot");
            document.open();

            float cellWidth = (PageSize.A4.getWidth() - 2 * PAGE_MARGIN) / vouchersPerRow;
            Layout layout = Layout.forWidth(cellWidth);

            PdfPTable table = new PdfPTable(vouchersPerRow);
            table.setWidthPercentage(100);
            table.setSplitLate(true);
            for (HotspotUser user : users) {
                table.addCell(voucher(user, withQr ? loginUrl : null, layout));
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

    private PdfPCell voucher(HotspotUser user, String loginUrl, Layout layout) throws IOException {
        Font titleFont = new Font(Font.HELVETICA, layout.titleSize, Font.BOLD, ACCENT);
        Font labelFont = new Font(Font.HELVETICA, layout.textSize, Font.NORMAL, Color.DARK_GRAY);
        Font valueFont = new Font(Font.COURIER, layout.textSize + 1, Font.BOLD, Color.BLACK);

        PdfPCell cell = new PdfPCell();
        cell.setPadding(4);
        cell.setPaddingBottom(6);
        cell.setMinimumHeight(layout.minHeight);
        cell.setBorderColor(BORDER);
        cell.setUseAscender(true);

        cell.addElement(centered("WiFi  ·  " + user.profile(), titleFont));
        cell.addElement(centered("User: ", labelFont, user.username(), valueFont));
        cell.addElement(centered("Pass: ", labelFont, user.password(), valueFont));
        if (!user.limitUptime().isBlank()) {
            cell.addElement(centered("Time: " + user.limitUptime(), labelFont));
        }

        if (loginUrl != null) {
            byte[] png = qrCodeService.png(qrCodeService.loginLink(loginUrl, user), 300);
            Image qr = Image.getInstance(png);
            qr.scaleToFit(layout.qrSize, layout.qrSize);
            qr.setAlignment(Element.ALIGN_CENTER);
            qr.setSpacingBefore(layout.textSize * 0.6f); // keep the QR clear of the text above
            cell.addElement(qr);
        }
        return cell;
    }

    private static Paragraph centered(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static Paragraph centered(String label, Font labelFont, String value, Font valueFont) {
        Paragraph p = new Paragraph(label, labelFont);
        p.add(new org.openpdf.text.Chunk(value, valueFont));
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    /** Font and QR sizes that scale with the voucher width. */
    private record Layout(float titleSize, float textSize, float qrSize, float minHeight) {

        static Layout forWidth(float cellWidth) {
            float text = Math.clamp(cellWidth / 14f, 6f, 10f);
            float qr = Math.min(cellWidth * 0.62f, 90f);
            return new Layout(text + 1.5f, text, qr, qr + text * 6f);
        }
    }
}
