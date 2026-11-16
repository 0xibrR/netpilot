package org.netpilot.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A small Excel (.xlsx) writer: sheets of rows with real numbers, amounts, dates and text, a bold
 * header row that stays in view, a filter on it, fitting column widths and right-to-left sheets
 * for Arabic. No library: an .xlsx file is a zip of a few XML files, written here directly.
 */
public final class Xlsx {

    /** One cell. */
    public sealed interface Cell {
    }

    /** Text. */
    public record Text(String value) implements Cell {
    }

    /** A whole number (counts). */
    public record Count(long value) implements Cell {
    }

    /** An amount in a currency, kept as a number so Excel can add it up. */
    public record Amount(BigDecimal value, String currency) implements Cell {
    }

    /** A day. */
    public record Day(LocalDate value) implements Cell {
    }

    /** A moment, to the minute. */
    public record Moment(LocalDateTime value) implements Cell {
    }

    /** No value. */
    public record Empty() implements Cell {
    }

    public static Cell text(String value) {
        return value == null || value.isEmpty() ? new Empty() : new Text(value);
    }

    public static Cell count(long value) {
        return new Count(value);
    }

    public static Cell amount(org.netpilot.model.Money money) {
        return money == null ? new Empty() : new Amount(money.amount(), money.currency());
    }

    public static Cell day(LocalDate value) {
        return value == null ? new Empty() : new Day(value);
    }

    public static Cell moment(LocalDateTime value) {
        return value == null ? new Empty() : new Moment(value);
    }

    /**
     * One sheet.
     *
     * @param header column titles, shown bold, kept in view and filterable; empty for none
     */
    public record Sheet(String name, List<String> header, List<List<Cell>> rows) {

        public Sheet {
            Objects.requireNonNull(name);
            header = List.copyOf(header);
            rows = List.copyOf(rows.stream().map(List::copyOf).toList());
        }
    }

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    // fixed styles (cellXfs index); amounts get one style per currency after these
    private static final int HEADER = 1;
    private static final int COUNT = 2;
    private static final int DAY = 3;
    private static final int MOMENT = 4;
    private static final int FIRST_AMOUNT = 5;

    private Xlsx() {
    }

    /**
     * Writes the workbook; the file appears complete or not at all.
     *
     * @param rtl right-to-left sheets (Arabic)
     */
    public static void write(Path file, List<Sheet> sheets, boolean rtl) throws IOException {
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("A workbook needs a sheet");
        }
        Path folder = Objects.requireNonNullElse(file.toAbsolutePath().getParent(), Path.of("."));
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, ".export-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                write(out, sheets, rtl);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void write(OutputStream target, List<Sheet> sheets, boolean rtl) throws IOException {
        Map<String, Integer> currencies = new LinkedHashMap<>();
        for (Sheet sheet : sheets) {
            for (List<Cell> row : sheet.rows()) {
                for (Cell cell : row) {
                    if (cell instanceof Amount a) {
                        currencies.putIfAbsent(a.currency() == null ? "" : a.currency().strip(),
                                FIRST_AMOUNT + currencies.size());
                    }
                }
            }
        }
        List<String> names = sheetNames(sheets);
        try (ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"" + PACKAGE_RELATIONSHIPS + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + RELATIONSHIPS + "/officeDocument\" "
                    + "Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip, "xl/workbook.xml", workbook(names));
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size()));
            entry(zip, "xl/styles.xml", styles(currencies));
            for (int i = 0; i < sheets.size(); i++) {
                entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sheets.get(i), currencies, rtl));
            }
        }
    }

    // ------------------------------------------------------------------ parts

    private static String contentTypes(int sheets) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                + ".spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                + ".spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheets; i++) {
            x.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/")
                    .append("vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return x.append("</Types>").toString();
    }

    private static String workbook(List<String> names) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + RELATIONSHIPS + "\"><sheets>");
        for (int i = 0; i < names.size(); i++) {
            x.append("<sheet name=\"").append(escape(names.get(i))).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return x.append("</sheets></workbook>").toString();
    }

    private static String workbookRelationships(int sheets) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"" + PACKAGE_RELATIONSHIPS + "\">");
        for (int i = 1; i <= sheets; i++) {
            x.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(RELATIONSHIPS)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        int styles = sheets + 1;
        x.append("<Relationship Id=\"rId").append(styles).append("\" Type=\"").append(RELATIONSHIPS)
                .append("/styles\" Target=\"styles.xml\"/>");
        return x.append("</Relationships>").toString();
    }

    private static String styles(Map<String, Integer> currencies) {
        StringBuilder formats = new StringBuilder("<numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd\"/>"
                + "<numFmt numFmtId=\"165\" formatCode=\"yyyy-mm-dd hh:mm\"/>");
        StringBuilder amountStyles = new StringBuilder();
        int id = 166;
        for (String currency : currencies.keySet()) {
            String code = currency.isEmpty() ? "#,##0.##" : "#,##0.## \"" + currency.replace("\"", "") + "\"";
            formats.append("<numFmt numFmtId=\"").append(id).append("\" formatCode=\"").append(escape(code))
                    .append("\"/>");
            amountStyles.append("<xf numFmtId=\"").append(id)
                    .append("\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>");
            id++;
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"" + MAIN + "\">"
                + "<numFmts count=\"" + (2 + currencies.size()) + "\">" + formats + "</numFmts>"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F6FEB\"/><bgColor indexed=\"64\"/>"
                + "</patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"" + (FIRST_AMOUNT + currencies.size()) + "\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                + "<xf numFmtId=\"1\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" applyNumberFormat=\"1\"/>"
                + amountStyles + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    private static String sheet(Sheet sheet, Map<String, Integer> currencies, boolean rtl) {
        boolean header = !sheet.header().isEmpty();
        int columns = Math.max(sheet.header().size(), sheet.rows().stream().mapToInt(List::size).max().orElse(0));
        int[] widths = new int[Math.max(columns, 1)];
        for (int c = 0; c < sheet.header().size(); c++) {
            widths[c] = Math.max(widths[c], sheet.header().get(c).length());
        }
        for (List<Cell> row : sheet.rows()) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], shown(row.get(c)).length());
            }
        }
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"" + MAIN + "\" xmlns:r=\"" + RELATIONSHIPS + "\"><sheetViews>"
                + "<sheetView workbookViewId=\"0\"" + (rtl ? " rightToLeft=\"1\"" : "") + ">");
        if (header) {
            x.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>");
        }
        x.append("</sheetView></sheetViews><cols>");
        for (int c = 0; c < widths.length; c++) {
            // a little room for the filter arrow; never absurdly wide
            double width = Math.min(60, Math.max(8, widths[c] + 3));
            x.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                    .append(width).append("\" customWidth=\"1\"/>");
        }
        x.append("</cols><sheetData>");
        int r = 1;
        if (header) {
            x.append("<row r=\"1\">");
            for (int c = 0; c < sheet.header().size(); c++) {
                inline(x, ref(c, 1), HEADER, sheet.header().get(c));
            }
            x.append("</row>");
            r++;
        }
        for (List<Cell> row : sheet.rows()) {
            x.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < row.size(); c++) {
                cell(x, ref(c, r), row.get(c), currencies);
            }
            x.append("</row>");
            r++;
        }
        x.append("</sheetData>");
        if (header && columns > 0) {
            x.append("<autoFilter ref=\"").append(ref(0, 1)).append(':').append(ref(columns - 1, r - 1))
                    .append("\"/>");
        }
        return x.append("</worksheet>").toString();
    }

    private static void cell(StringBuilder x, String ref, Cell cell, Map<String, Integer> currencies) {
        switch (cell) {
            case Text t -> inline(x, ref, 0, t.value());
            case Count n -> number(x, ref, COUNT, String.valueOf(n.value()));
            case Amount a -> number(x, ref, currencies.get(a.currency() == null ? "" : a.currency().strip()),
                    a.value().stripTrailingZeros().toPlainString());
            case Day d -> number(x, ref, DAY, String.valueOf(serial(d.value())));
            case Moment m -> number(x, ref, MOMENT, serial(m.value()));
            case Empty e -> {
                // nothing written: Excel shows an empty cell
            }
        }
    }

    private static void inline(StringBuilder x, String ref, int style, String text) {
        x.append("<c r=\"").append(ref).append('"');
        if (style != 0) {
            x.append(" s=\"").append(style).append('"');
        }
        x.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(text)).append("</t></is></c>");
    }

    private static void number(StringBuilder x, String ref, int style, String value) {
        x.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>").append(value)
                .append("</v></c>");
    }

    // ------------------------------------------------------------------ helpers

    /** Excel's day number: days since 1899-12-30. */
    static long serial(LocalDate day) {
        return ChronoUnit.DAYS.between(EXCEL_EPOCH, day);
    }

    /** Day number plus the fraction of the day, to the minute. */
    static String serial(LocalDateTime moment) {
        long minutes = moment.getHour() * 60L + moment.getMinute();
        BigDecimal fraction = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(1440), 10,
                java.math.RoundingMode.HALF_UP);
        return BigDecimal.valueOf(serial(moment.toLocalDate())).add(fraction).stripTrailingZeros().toPlainString();
    }

    /** "A1", "B7", "AA3" … for column {@code c} (from 0) and row {@code r} (from 1). */
    static String ref(int c, int r) {
        StringBuilder letters = new StringBuilder();
        for (int n = c + 1; n > 0; n = (n - 1) / 26) {
            letters.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return letters.toString() + r;
    }

    /** Sheet names as Excel accepts them: at most 31 characters, none of []:*?/\ and all different. */
    static List<String> sheetNames(List<Sheet> sheets) {
        List<String> names = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Sheet sheet : sheets) {
            String base = sheet.name().replaceAll("[\\[\\]:*?/\\\\']", " ").strip();
            if (base.isEmpty()) {
                base = "Sheet";
            }
            base = base.length() > 31 ? base.substring(0, 31) : base;
            String name = base;
            for (int n = 2; !taken.add(name.toLowerCase(Locale.ROOT)); n++) {
                String suffix = " (" + n + ")";
                name = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
            }
            names.add(name);
        }
        return names;
    }

    /** The text a cell shows, roughly, for its column width. */
    private static String shown(Cell cell) {
        return switch (cell) {
            case Text t -> t.value();
            case Count n -> String.valueOf(n.value());
            case Amount a -> a.value().toPlainString() + " " + a.currency();
            case Day d -> "0000-00-00";
            case Moment m -> "0000-00-00 00:00";
            case Empty e -> "";
        };
    }

    /** XML text: markup characters escaped, characters XML cannot hold dropped. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                            || (cp >= 0xE000 && cp <= 0xFFFD) || cp >= 0x10000) {
                        out.appendCodePoint(cp);
                    }
                }
            }
        });
        return out.toString();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Writer w = new java.io.OutputStreamWriter(zip, StandardCharsets.UTF_8);
        w.write(content);
        w.flush(); // not closed: that would close the zip
        zip.closeEntry();
    }
}
