package org.netpilot.service;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.netpilot.i18n.Messages;
import org.netpilot.model.DataSize;
import org.netpilot.model.Money;
import org.netpilot.model.SalesReport;
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
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Writes a {@link MonthlyReport} as an A4 PDF: header, month summary with the change from the
 * month before, a daily sales chart, the best-selling packages, the points of sale, the batches
 * and notes. Arabic reads right to left (columns too) in the Cairo font the vouchers use.
 *
 * <p>The file is written next to its target first and moved into place only when complete, so a
 * failure never leaves a half-written report behind.
 */
public final class MonthlyReportPdf {

    private static final float MARGIN = 36;
    private static final float WIDTH = PageSize.A4.getWidth() - 2 * MARGIN;
    private static final Color ACCENT = new Color(0x1E5FB4);
    private static final Color TEXT = new Color(0x1F2328);
    private static final Color MUTED = new Color(0x6B7280);
    private static final Color LINE = new Color(0xDDE3EA);
    private static final Color HEAD = new Color(0xEEF3FB);
    private static final Color GOOD = new Color(0x1E7E34);
    private static final Color BAD = new Color(0xC62828);
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String LOGO = "/org/netpilot/icons/netpilot-128.png";

    private final PdfText text;
    private final boolean rtl;
    private final String version;

    /** @param version the NetPilot version printed in the footer */
    public MonthlyReportPdf(String version) {
        this(PdfText.instance(), Messages.isArabic(), version);
    }

    MonthlyReportPdf(PdfText text, boolean rtl, String version) {
        this.text = text;
        this.rtl = rtl;
        this.version = version == null ? "" : version;
    }

    /** Writes the report to {@code target}, replacing it only once the new file is complete. */
    public void write(MonthlyReport report, Path target) throws IOException {
        Path folder = java.util.Objects.requireNonNullElse(target.toAbsolutePath().getParent(), Path.of("."));
        Path temp = Files.createTempFile(folder, ".netpilot-report-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                render(report, out);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void render(MonthlyReport report, OutputStream out) throws IOException {
        Document document = new Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN + 20);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new Footer());
            document.addTitle(Messages.get("monthly.title", monthName(report)));
            document.addCreator("NetPilot " + version);
            document.open();
            header(document, report);
            if (report.includes(MonthlyReport.Section.SUMMARY)) {
                summary(document, report);
            }
            if (report.includes(MonthlyReport.Section.CHART)) {
                chart(document, writer, report);
            }
            if (report.includes(MonthlyReport.Section.PACKAGES)) {
                packages(document, report.sales());
            }
            if (report.includes(MonthlyReport.Section.RESELLERS)) {
                resellers(document, report);
            }
            if (report.includes(MonthlyReport.Section.BATCHES)) {
                batches(document, report.sales());
            }
            if (report.showsBranches()) {
                branches(document, report.branches());
            }
            if (report.includes(MonthlyReport.Section.NOTES)) {
                notes(document, report);
            }
            document.close();
        } catch (DocumentException e) {
            throw new IOException("Failed to write the monthly report: " + e.getMessage(), e);
        }
    }

    /** {@code November 2026} / {@code نوفمبر 2026}, in the app's language. */
    public static String monthName(MonthlyReport report) {
        return report.month().format(DateTimeFormatter.ofPattern("MMMM yyyy", Messages.locale()));
    }

    // ------------------------------------------------------------------ parts

    private void header(Document document, MonthlyReport report) throws DocumentException, IOException {
        PdfPTable table = new PdfPTable(2);
        float logoWidth = 58;
        table.setTotalWidth(ordered(new float[]{logoWidth, WIDTH - logoWidth}));
        table.setLockedWidth(true);

        PdfPCell logo = new PdfPCell();
        logo.setBorder(Rectangle.NO_BORDER);
        logo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        try (InputStream in = MonthlyReportPdf.class.getResourceAsStream(LOGO)) {
            if (in != null) {
                Image image = Image.getInstance(in.readAllBytes());
                image.scaleToFit(48, 48);
                logo.addElement(image);
            }
        }

        PdfPCell titles = new PdfPCell();
        titles.setBorder(Rectangle.NO_BORDER);
        titles.setPaddingLeft(8);
        titles.setPaddingRight(8);
        String network = report.network().isEmpty() ? "NetPilot" : report.network();
        float titleWidth = WIDTH - logoWidth - 16;
        put(titles, network, new PdfText.Style(18, ACCENT), titleWidth);
        put(titles, Messages.get("monthly.title", monthName(report)), new PdfText.Style(13, TEXT), titleWidth);
        String meta = (report.router().isEmpty() ? "" : Messages.get("monthly.router", report.router()) + "   ·   ")
                + Messages.get("monthly.created", CREATED.format(report.created()));
        put(titles, meta, new PdfText.Style(9, MUTED), titleWidth);

        addInOrder(table, logo, titles);
        document.add(table);
        document.add(rule(ACCENT, 1.5f, 6));
    }

    private void summary(Document document, MonthlyReport report) throws DocumentException {
        heading(document, Messages.get("monthly.section.summary"));
        SalesReport now = report.sales();
        SalesReport before = report.previous();
        List<PdfPCell> cards = List.of(
                card(Messages.get("report.revenue"), now.revenue().format(),
                        MonthlyReport.change(now.revenue().amount(), before.revenue().amount())),
                card(Messages.get("report.sold"), String.valueOf(now.sold()),
                        MonthlyReport.change(now.sold(), before.sold())),
                card(Messages.get("report.created"), String.valueOf(now.created()),
                        MonthlyReport.change(now.created(), before.created())),
                card(Messages.get("monthly.stockNow"), now.potential().format(), null));
        PdfPTable table = new PdfPTable(cards.size());
        table.setTotalWidth(WIDTH);
        table.setLockedWidth(true);
        addInOrder(table, cards.toArray(PdfPCell[]::new));
        document.add(table);
    }

    /** @param change empty: nothing to compare with; null: no comparison line at all */
    private PdfPCell card(String label, String value, Optional<Integer> change) {
        float width = WIDTH / 4 - 16;
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(LINE);
        cell.setPadding(8);
        put(cell, label, new PdfText.Style(8.5f, MUTED), width);
        put(cell, value, new PdfText.Style(15, TEXT), width);
        if (change != null) {
            String note;
            Color color;
            if (change.isEmpty()) {
                note = Messages.get("monthly.change.none");
                color = MUTED;
            } else if (change.get() > 0) {
                note = Messages.get("monthly.change.up", change.get());
                color = GOOD;
            } else if (change.get() < 0) {
                note = Messages.get("monthly.change.down", -change.get());
                color = BAD;
            } else {
                note = Messages.get("monthly.change.same");
                color = MUTED;
            }
            put(cell, note, new PdfText.Style(8, color), width);
        }
        return cell;
    }

    /** One bar per day of the month, days left to right in every language like the app's chart. */
    private void chart(Document document, PdfWriter writer, MonthlyReport report) throws DocumentException {
        heading(document, Messages.get("monthly.section.chart"));
        Map<LocalDate, Money> daily = report.sales().dailyRevenue();
        BigDecimal max = daily.values().stream().map(Money::amount).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (max.signum() <= 0) {
            muted(document, Messages.get("monthly.noSales"));
            return;
        }
        float height = 150;
        PdfTemplate canvas = writer.getDirectContent().createTemplate(WIDTH, height);
        float left = 46;
        float bottom = 18;
        float top = 8;
        float plotWidth = WIDTH - left - 6;
        float plotHeight = height - bottom - top;
        int days = report.month().lengthOfMonth();
        PdfText.Style small = new PdfText.Style(7.5f, MUTED);

        canvas.setColorStroke(LINE);
        canvas.setLineWidth(0.5f);
        for (int step = 0; step <= 2; step++) { // 0, half, max
            float y = bottom + plotHeight * step / 2;
            canvas.moveTo(left, y);
            canvas.lineTo(left + plotWidth, y);
            canvas.stroke();
            BigDecimal value = max.multiply(BigDecimal.valueOf(step)).divide(BigDecimal.valueOf(2), 0,
                    RoundingMode.HALF_UP);
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, text.phrase(value.toPlainString(), small),
                    left - 4, y - 2.5f, 0);
        }

        float slot = plotWidth / days;
        float bar = Math.max(1.5f, slot * 0.68f);
        canvas.setColorFill(ACCENT);
        for (int day = 1; day <= days; day++) {
            Money money = daily.get(report.month().atDay(day));
            float x = left + slot * (day - 1) + (slot - bar) / 2;
            if (money != null && money.amount().signum() > 0) {
                float h = money.amount().divide(max, 6, RoundingMode.HALF_UP).floatValue() * plotHeight;
                canvas.rectangle(x, bottom, bar, Math.max(0.8f, h));
                canvas.fill();
            }
            if (day == 1 || day % 5 == 0 || day == days) {
                ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, text.phrase(String.valueOf(day), small),
                        x + bar / 2, 5, 0);
            }
        }
        Image image = Image.getInstance(canvas);
        image.setSpacingBefore(2);
        document.add(image);
        String currency = report.sales().revenue().currency();
        if (!currency.isEmpty()) {
            muted(document, Messages.get("monthly.chartUnit", currency));
        }
    }

    private void packages(Document document, SalesReport sales) throws DocumentException {
        heading(document, Messages.get("monthly.section.packages"));
        List<SalesReport.ProfileRow> sold = sales.profiles().stream().filter(p -> p.sold() > 0).toList();
        if (sold.isEmpty()) {
            muted(document, Messages.get("monthly.noSales"));
            return;
        }
        BigDecimal total = sales.revenue().amount();
        List<List<String>> rows = new ArrayList<>();
        for (SalesReport.ProfileRow p : sold) {
            String share = total.signum() == 0
                    ? "—"
                    : p.revenue().amount().multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.HALF_UP)
                            + "%";
            rows.add(List.of(ReportService.profileLabel(p.profile()), p.price().map(Money::format).orElse("—"),
                    String.valueOf(p.sold()), p.revenue().format(), share, DataSize.format(p.bytes())));
        }
        document.add(table(new float[]{3, 2, 1.3f, 2.2f, 1.3f, 1.8f},
                List.of(Messages.get("report.column.profile"), Messages.get("report.column.price"),
                        Messages.get("report.column.sold"), Messages.get("report.column.revenue"),
                        Messages.get("monthly.column.share"), Messages.get("monthly.column.data")),
                rows, 0));
    }

    private void resellers(Document document, MonthlyReport report) throws DocumentException {
        heading(document, Messages.get("monthly.section.resellers"));
        if (report.resellers().isEmpty()) {
            muted(document, Messages.get("monthly.noResellers"));
        } else {
            List<List<String>> rows = new ArrayList<>();
            List<Color> balanceColors = new ArrayList<>();
            for (ResellerAccounts.MonthStatement line : report.resellers()) {
                var m = line.month();
                var end = line.atEnd();
                String balance;
                if (end.due().signum() > 0) {
                    balance = Messages.get("monthly.balance.due", end.format(end.due()));
                    balanceColors.add(BAD);
                } else if (end.credit().signum() > 0) {
                    balance = Messages.get("monthly.balance.credit", end.format(end.credit()));
                    balanceColors.add(GOOD);
                } else {
                    balance = Messages.get("monthly.balance.settled");
                    balanceColors.add(TEXT);
                }
                rows.add(List.of(line.reseller().name(), String.valueOf(m.given()), String.valueOf(m.sold()),
                        m.format(m.salesValue()), m.format(m.commission()), m.format(m.paid()), balance));
            }
            PdfPTable table = table(new float[]{2.6f, 1.1f, 1.1f, 1.9f, 1.7f, 1.8f, 2.4f},
                    List.of(Messages.get("monthly.column.reseller"), Messages.get("monthly.column.given"),
                            Messages.get("report.column.sold"), Messages.get("monthly.column.salesValue"),
                            Messages.get("monthly.column.commission"), Messages.get("monthly.column.paid"),
                            Messages.get("monthly.column.balance")),
                    rows, 0);
            colorLastColumn(table, balanceColors);
            document.add(table);
        }
        ResellerAccounts.DirectMonth direct = report.direct();
        if (direct.sold() > 0) {
            paragraph(document, Messages.get("monthly.direct", direct.sold(), direct.format()),
                    new PdfText.Style(9.5f, TEXT), 6);
        }
    }

    /** Every branch's month side by side, and the total; stock is each branch's last reading. */
    private void branches(Document document, BranchReport all) throws DocumentException {
        heading(document, Messages.get("monthly.section.branches"));
        List<List<String>> rows = new ArrayList<>();
        for (BranchReport.Line line : all.lines()) {
            rows.add(branchRow(line.name(), line));
        }
        rows.add(branchRow(Messages.get("reports.allBranches"), all.total()));
        document.add(table(new float[]{3, 1.3f, 2.2f, 2.6f, 2.2f},
                List.of(Messages.get("reports.column.branch"), Messages.get("report.column.sold"),
                        Messages.get("report.column.revenue"), Messages.get("reports.column.stock"),
                        Messages.get("reports.column.readAt")),
                rows, 0));
    }

    private static List<String> branchRow(String name, BranchReport.Line line) {
        return List.of(name, String.valueOf(line.sold()), line.revenue().format(),
                line.stock().map(s -> Messages.get("reports.stockValue", s.unused(), s.value().format())).orElse("—"),
                line.stock().map(s -> CREATED.format(s.at())).orElse(Messages.get("reports.neverRead")));
    }

    private void batches(Document document, SalesReport sales) throws DocumentException {
        heading(document, Messages.get("monthly.section.batches"));
        if (sales.batches().isEmpty()) {
            muted(document, Messages.get("monthly.noBatches"));
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (SalesReport.BatchRow b : sales.batches()) {
            rows.add(List.of(ReportService.batchLabel(b.batch()), b.created().map(CREATED::format).orElse("—"),
                    ReportService.profileLabel(b.profile()), String.valueOf(b.cards()), String.valueOf(b.used()),
                    String.valueOf(b.unused()), b.revenue().format()));
        }
        document.add(table(new float[]{3.2f, 2.2f, 1.6f, 1.1f, 1.1f, 1.1f, 2},
                List.of(Messages.get("report.column.batch"), Messages.get("report.column.created"),
                        Messages.get("report.column.profile"), Messages.get("report.column.cards"),
                        Messages.get("report.column.used"), Messages.get("report.column.unused"),
                        Messages.get("report.column.revenue")),
                rows, 0));
    }

    private void notes(Document document, MonthlyReport report) throws DocumentException {
        heading(document, Messages.get("monthly.section.notes"));
        List<String> notes = new ArrayList<>(report.sales().warnings());
        notes.add(Messages.get("monthly.note.soldDay"));
        notes.add(Messages.get("monthly.note.stock"));
        notes.add(Messages.get("monthly.note.frozen"));
        for (String note : notes) {
            paragraph(document, "•  " + note, new PdfText.Style(9, MUTED), 2);
        }
    }

    // ------------------------------------------------------------------ building blocks

    /**
     * A table with a header row repeated on each page.
     *
     * @param textColumn the column with free text (start-aligned); the others are centred
     */
    private PdfPTable table(float[] weights, List<String> headers, List<List<String>> rows, int textColumn)
            throws DocumentException {
        float sum = 0;
        for (float w : weights) {
            sum += w;
        }
        float[] widths = new float[weights.length];
        for (int i = 0; i < weights.length; i++) {
            widths[i] = WIDTH * weights[i] / sum;
        }
        PdfPTable table = new PdfPTable(weights.length);
        table.setTotalWidth(ordered(widths));
        table.setLockedWidth(true);
        table.setHeaderRows(1);
        table.setSpacingBefore(2);
        PdfText.Style head = new PdfText.Style(8.5f, ACCENT);
        PdfText.Style body = new PdfText.Style(8.5f, TEXT);
        addRow(table, headers, widths, head, HEAD, textColumn);
        for (List<String> row : rows) {
            addRow(table, row, widths, body, null, textColumn);
        }
        return table;
    }

    private void addRow(PdfPTable table, List<String> values, float[] widths, PdfText.Style style, Color background,
            int textColumn) {
        PdfPCell[] cells = new PdfPCell[values.size()];
        for (int i = 0; i < values.size(); i++) {
            int align = i == textColumn ? start() : Element.ALIGN_CENTER;
            PdfPCell cell = new PdfPCell();
            cell.setBorderColor(LINE);
            cell.setPadding(4);
            cell.setPaddingBottom(5);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            if (background != null) {
                cell.setBackgroundColor(background);
            }
            lines(values.get(i), style, widths[i] - 9, align).forEach(cell::addElement);
            cells[i] = cell;
        }
        addInOrder(table, cells);
    }

    /** Recolours the balance column (owes red, credit green) after the rows were laid out. */
    private void colorLastColumn(PdfPTable table, List<Color> colors) {
        int columns = table.getNumberOfColumns();
        int column = rtl ? 0 : columns - 1;
        for (int r = 0; r < colors.size(); r++) {
            PdfPCell cell = table.getRow(r + 1).getCells()[column];
            // each chunk has its own font object (PdfText makes one per chunk), so this recolours only here
            for (Object e : cell.getCompositeElements()) {
                if (e instanceof Paragraph p) {
                    for (Object chunk : p.getChunks()) {
                        if (chunk instanceof org.openpdf.text.Chunk c) {
                            c.getFont().setColor(colors.get(r));
                        }
                    }
                }
            }
        }
    }

    private void heading(Document document, String title) throws DocumentException {
        List<Paragraph> lines = lines(title, new PdfText.Style(12.5f, ACCENT), WIDTH, start());
        lines.getFirst().setSpacingBefore(14);
        lines.getLast().setSpacingAfter(5);
        for (Paragraph p : lines) {
            document.add(p);
        }
    }

    private void muted(Document document, String message) throws DocumentException {
        paragraph(document, message, new PdfText.Style(9, MUTED), 2);
    }

    private void paragraph(Document document, String content, PdfText.Style style, float spacingBefore)
            throws DocumentException {
        List<Paragraph> lines = lines(content, style, WIDTH, start());
        lines.getFirst().setSpacingBefore(spacingBefore);
        for (Paragraph p : lines) {
            document.add(p);
        }
    }

    /** Text in a table cell or the header, start-aligned. */
    private void put(PdfPCell cell, String content, PdfText.Style style, float width) {
        lines(content, style, width, start()).forEach(cell::addElement);
    }

    /**
     * Text wrapped to {@code width}, one paragraph per line, each placed by its printed width.
     *
     * <p>OpenPDF aligns a line by the width of its unjoined letters, which for Arabic is wider than
     * the joined text it prints, so right-aligned and centred Arabic stopped short of its place.
     * Each line is therefore left-aligned and moved in by the free space measured from the printed
     * text ({@link PdfText#printedWidth}). The negative right indent gives OpenPDF the room its own
     * (wider) measure asks for, so it never breaks the line again.
     */
    private List<Paragraph> lines(String content, PdfText.Style style, float width, int align) {
        List<String> wrapped = text.wrapLines(content == null ? "" : content, style, width);
        if (wrapped.isEmpty()) {
            wrapped = List.of(" ");
        }
        List<Paragraph> out = new ArrayList<>();
        for (String line : wrapped) {
            Paragraph p = new Paragraph(text.phrase(line, style));
            p.setAlignment(Element.ALIGN_LEFT);
            float printed = text.printedWidth(line, style);
            float measured = text.width(line, style);
            float free = Math.max(0, width - printed);
            float left = switch (align) {
                case Element.ALIGN_RIGHT -> free;
                case Element.ALIGN_CENTER -> free / 2;
                default -> 0;
            };
            p.setIndentationLeft(left);
            p.setIndentationRight(-(Math.max(0, measured - printed) + 2));
            out.add(p);
        }
        return out;
    }

    private static PdfPTable rule(Color color, float thickness, float spacingAfter) {
        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(WIDTH);
        table.setLockedWidth(true);
        table.setSpacingBefore(4);
        table.setSpacingAfter(spacingAfter);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(color);
        cell.setBorderWidthBottom(thickness);
        cell.setFixedHeight(2);
        table.addCell(cell);
        return table;
    }

    private int start() {
        return rtl ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT;
    }

    /** Column widths in reading order: reversed for Arabic, so the first column is on the right. */
    private float[] ordered(float[] widths) {
        if (!rtl) {
            return widths;
        }
        float[] reversed = new float[widths.length];
        for (int i = 0; i < widths.length; i++) {
            reversed[i] = widths[widths.length - 1 - i];
        }
        return reversed;
    }

    private void addInOrder(PdfPTable table, PdfPCell... cells) {
        List<PdfPCell> list = new ArrayList<>(List.of(cells));
        if (rtl) {
            Collections.reverse(list);
        }
        list.forEach(table::addCell);
    }

    /** Page number and "made with NetPilot" at the bottom of every page. */
    private final class Footer extends PdfPageEventHelper {

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            PdfText.Style style = new PdfText.Style(8, MUTED);
            float y = MARGIN - 6;
            Phrase page = text.phrase(Messages.get("monthly.page", writer.getPageNumber()), style);
            Phrase made = text.phrase(Messages.get("monthly.madeWith", version), style);
            float left = MARGIN;
            float right = PageSize.A4.getWidth() - MARGIN;
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, rtl ? made : page, left, y, 0);
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, rtl ? page : made, right, y, 0);
        }
    }
}
