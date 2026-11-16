package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.Customer;
import org.netpilot.model.Money;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.Reseller;
import org.netpilot.model.RouterUser;
import org.netpilot.model.SalesReport;
import org.netpilot.repository.ResellerRepository;
import org.netpilot.repository.StockReadings;
import org.w3c.dom.Document;

class XlsxTest {

    @TempDir
    Path dir;

    /** Every file in the workbook, by name; each XML part must parse. */
    private static Map<String, String> open(Path file) throws Exception {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                parse(xml); // well-formed, or the test fails here
                parts.put(e.getName(), xml);
            }
        }
        return parts;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aWorkbookHasRealNumbersDatesAndSafeText() throws Exception {
        Path file = dir.resolve("out.xlsx");
        Xlsx.write(file, List.of(
                new Xlsx.Sheet("ملخص", List.of("البند", "القيمة"), List.of(
                        List.of(Xlsx.text("الإيراد"), Xlsx.amount(new Money(new BigDecimal("1500.50"), "ل.س"))),
                        List.of(Xlsx.text("A & B <c> \"q\"\u0001"), Xlsx.count(42)),
                        List.of(Xlsx.day(LocalDate.of(2026, 11, 16)),
                                Xlsx.moment(LocalDateTime.of(2026, 11, 16, 12, 0))),
                        List.of(Xlsx.text(""), Xlsx.amount(null), Xlsx.day(null), Xlsx.moment(null),
                                Xlsx.amount(new Money(BigDecimal.TEN, ""))))),
                new Xlsx.Sheet("No header", List.of(), List.of(List.of(Xlsx.text("x"))))), true);

        Map<String, String> parts = open(file);
        assertTrue(parts.keySet().containsAll(List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml")));
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("rightToLeft=\"1\""), "Arabic sheets read right to left");
        assertTrue(sheet.contains("state=\"frozen\""), "the header stays in view");
        assertTrue(sheet.contains("<autoFilter ref=\"A1:E5\"/>"));
        assertTrue(sheet.contains("<c r=\"B2\" s=\"5\"><v>1500.5</v></c>"), "an amount is a number");
        assertTrue(sheet.contains("<c r=\"B3\" s=\"2\"><v>42</v></c>"), "a count is a number");
        assertTrue(sheet.contains("<v>46342</v>"), "a date is Excel's day number");
        assertTrue(sheet.contains("<v>46342.5</v>"), "noon is half a day");
        assertTrue(sheet.contains("A &amp; B &lt;c&gt; &quot;q&quot;</t>"), "markup escaped, control character gone");
        assertFalse(sheet.contains("<c r=\"B5\""), "an empty cell is left out");
        assertTrue(parts.get("xl/styles.xml").contains("#,##0.## &quot;ل.س&quot;"), "the currency shows in Excel");
        assertFalse(parts.get("xl/worksheets/sheet2.xml").contains("frozen"));
        assertTrue(parts.get("xl/workbook.xml").contains("name=\"ملخص\""));

        assertThrows(IllegalArgumentException.class, () -> Xlsx.write(dir.resolve("none.xlsx"), List.of(), false));
        assertFalse(Files.list(dir).anyMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no leftovers");
    }

    @Test
    void cellReferencesSerialsAndSheetNamesFollowExcelRules() {
        assertEquals("A1", Xlsx.ref(0, 1));
        assertEquals("Z9", Xlsx.ref(25, 9));
        assertEquals("AA3", Xlsx.ref(26, 3));
        assertEquals("AZ1", Xlsx.ref(51, 1));
        assertEquals(1, Xlsx.serial(LocalDate.of(1899, 12, 31)));
        assertEquals("46342.75", Xlsx.serial(LocalDateTime.of(2026, 11, 16, 18, 0)));

        List<String> names = Xlsx.sheetNames(List.of(sheet("a/b:c"), sheet("A B C"), sheet("x".repeat(40)),
                sheet("x".repeat(40)), sheet("  ")));
        assertEquals("a b c", names.get(0));
        assertEquals("A B C (2)", names.get(1), "names differ, whatever the case");
        assertEquals(31, names.get(2).length());
        assertTrue(names.get(3).endsWith(" (2)") && names.get(3).length() == 31);
        assertEquals("Sheet", names.get(4));
        assertEquals("a", Xlsx.escape("a\u0000"));
    }

    private static Xlsx.Sheet sheet(String name) {
        return new Xlsx.Sheet(name, List.of(), List.of());
    }

    @Test
    void theReportPointsOfSaleAndCardListBecomeWorkbooks() throws Exception {
        Money price = new Money(new BigDecimal("1000"), "ل.س");
        SalesReport report = new SalesReport(ReportPeriod.THIS_MONTH, price, price, 1, 2, 3,
                new TreeMap<>(Map.of(LocalDate.of(2026, 11, 16), price)),
                List.of(new SalesReport.BatchRow("NetPilot 2026-11-01 10:00 #aaaa",
                        Optional.of(LocalDateTime.of(2026, 11, 1, 10, 0)), "1M", 3, 1, price)),
                List.of(new SalesReport.ProfileRow("1M", Optional.of(price), 1, 2, price, 5_242_880)),
                List.of("a warning"));
        BranchReport.Line shop = new BranchReport.Line("10.0.0.1:80", "المحل", "#fff", 1, price,
                Optional.of(new StockReadings.Reading(LocalDateTime.of(2026, 11, 16, 9, 0), 2, price)));
        BranchReport.Line home = new BranchReport.Line("10.0.0.2:80", "البيت", "#000", 0,
                new Money(BigDecimal.ZERO, "ل.س"), Optional.empty());
        BranchReport branches = new BranchReport(List.of(shop, home), shop, List.of(), new TreeMap<>());
        Path reportFile = dir.resolve("report.xlsx");
        new ReportExporter().exportXlsx(report, Optional.of(branches), reportFile);
        Map<String, String> parts = open(reportFile);
        assertEquals(6, parts.keySet().stream().filter(n -> n.startsWith("xl/worksheets/")).count(),
                "summary, batches, packages, days, branches, notes");
        assertTrue(parts.get("xl/worksheets/sheet3.xml").contains("<v>5</v>"), "5 MB of data");

        ResellerAccounts accounts = new ResellerAccounts(new ResellerRepository(dir.resolve("r.csv"),
                dir.resolve("l.csv")), Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC));
        Reseller ali = accounts.save(new Reseller("ali", "محل علي", "0599", Reseller.CommissionType.PERCENT,
                BigDecimal.TEN, Reseller.Billing.ON_HANDOVER));
        accounts.handOver(ali, List.of(new ResellerAccounts.Card("10.0.0.1:80", "c1", "1M")), Map.of("1M", "1000 ل.س"),
                false);
        accounts.pay("p1", ali, new BigDecimal("400"), "ل.س", LocalDate.of(2026, 11, 16), "نقداً");
        Path statement = dir.resolve("statement.xlsx");
        ResellerExport.statement(accounts.statements().getFirst(), statement);
        String moves = open(statement).get("xl/worksheets/sheet2.xml");
        assertTrue(moves.contains("<v>400</v>") && moves.contains("نقداً"));
        Path all = dir.resolve("all.xlsx");
        ResellerExport.all(accounts.statements(), all);
        String points = open(all).get("xl/worksheets/sheet1.xml");
        assertTrue(points.contains("محل علي") && points.contains("0599") && points.contains("<v>500</v>"),
                "1000 less 10% commission less 400 paid");

        RouterUser used = new RouterUser("*1", "c1", "1M", "all", Duration.ofMinutes(90), "8h", 1_048_576,
                2_097_152, false, "NetPilot 2026-11-01 10:00 #aaaa", 10_485_760L);
        RouterUser off = new RouterUser("*2", "c2", "1M", "all", Duration.ZERO, "", 0, 0, true, "", 0);
        Path cards = dir.resolve("cards.xlsx");
        CardListExport.write("10.0.0.1:80", List.of(used, off), Set.of("c1"), Map.of("c1", "2026-11-20 10:00:00",
                "c2", "soon"),
                Map.of(Customer.key("10.0.0.1:80", "c1"), new Customer("10.0.0.1:80", "c1",
                        "+970599000000", LocalDateTime.of(2026, 11, 1, 10, 0), Optional.empty())),
                cards);
        String list = open(cards).get("xl/worksheets/sheet1.xml");
        assertTrue(list.contains("+970599000000") && list.contains("soon") && list.contains("01:30:00"));
        assertTrue(list.contains("<v>3</v>") && list.contains("<v>10</v>"), "3 MB used of a 10 MB limit");
        assertFalse(list.toLowerCase(java.util.Locale.ROOT).contains("password"));
    }
}
