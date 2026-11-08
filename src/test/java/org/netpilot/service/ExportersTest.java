package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.VoucherTemplate;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class ExportersTest {

    @TempDir
    Path dir;

    private static HotspotUser user(String name, String password) {
        return new HotspotUser(name, password, "2M", "all", "1h");
    }

    @Test
    void csvHasHeaderAndEscapesValues() throws Exception {
        Path file = dir.resolve("users.csv");
        new CsvExporter().export(List.of(user("a,b", "p\"1")), file);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals("﻿Username,Password,Profile,Server,Uptime Limit", lines.get(0));
        assertEquals("\"a,b\",\"p\"\"1\",2M,all,1h", lines.get(1));
        assertEquals(List.of("a,b", "p\"1", "2M", "all", "1h"), Csv.parseLine(lines.get(1)));
    }

    @Test
    void rscQuotesEveryValueAndSkipsDuplicates() {
        String command = new RscExporter("test").command(user("user0001", "1234"));

        assertEquals(":do { /ip hotspot user add name=\"user0001\" password=\"1234\" profile=\"2M\""
                + " server=\"all\" limit-uptime=\"1h\" comment=\"test\" }"
                + " on-error={ :log warning \"NetPilot: skipped existing user user0001\" }", command);
    }

    @Test
    void rscEscapingPreventsScriptInjection() {
        String quoted = RscExporter.quote("x\"; /system reset-configuration; $a?");

        assertEquals("\"x\\\"; /system reset-configuration; \\$a\\?\"", quoted);
    }

    @Test
    void rscOmitsUptimeWhenUnlimited() {
        String command = new RscExporter("t").command(new HotspotUser("u", "p", "2M", "all", ""));
        assertFalse(command.contains("limit-uptime"));
        assertFalse(command.contains("limit-bytes-total"));
    }

    @Test
    void rscCarriesTheDataLimitInBytes() {
        String command = new RscExporter("t").command(new HotspotUser("u", "p", "2M", "all", "", 1L << 30));
        assertTrue(command.contains(" limit-bytes-total=1073741824 "), command);
    }

    @Test
    void qrLinkIsUrlEncoded() {
        String link = new QrCodeService().loginLink("http://10.10.0.1/login", user("a&b", "p w+"));
        assertEquals("http://10.10.0.1/login?username=a%26b&password=p+w%2B", link);

        String withQuery = new QrCodeService().loginLink("http://hs.lan/login?dst=x", user("a", "b"));
        assertEquals("http://hs.lan/login?dst=x&username=a&password=b", withQuery);
    }

    @Test
    void qrRejectsInvalidLoginUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new QrCodeService().loginLink("10.10.0.1/login", user("a", "b")));
    }

    @Test
    void qrPngIsAnImage() {
        byte[] png = new QrCodeService().png("http://10.10.0.1/login?username=a&password=b", 200);
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
    }

    /** Regression: an incomplete last row used to be dropped from the PDF. */
    @Test
    void pdfContainsEveryVoucherIncludingIncompleteLastRow() throws Exception {
        List<HotspotUser> users = IntStream.rangeClosed(1, 23)
                .mapToObj(i -> user("user%04d".formatted(i), "pw" + i))
                .toList();
        Path file = dir.resolve("vouchers.pdf");

        new PdfVoucherGenerator(new QrCodeService()).export(users, file, "http://10.10.0.1/login", 5,
                VoucherTemplate.defaults());

        String text = pdfText(file);
        for (HotspotUser u : users) {
            assertTrue(text.contains(u.username()), "missing " + u.username());
        }
    }

    @Test
    void pdfWithoutQrWhenLoginUrlBlank() throws Exception {
        Path file = dir.resolve("plain.pdf");
        new PdfVoucherGenerator(new QrCodeService()).export(List.of(user("a1", "b1")), file, "", 4,
                VoucherTemplate.defaults());
        assertTrue(Files.size(file) > 0);
    }

    /**
     * Regression for the Arabic layout: with right-to-left text, numbers and Latin credentials
     * were once printed reversed (714133 as 331417), which would hand out wrong usernames.
     */
    @Test
    void arabicVouchersKeepCredentialsInTheirOriginalOrder() throws Exception {
        VoucherTemplate arabic = new VoucherTemplate("شبكة الأمل", "صالح لمدة 30 يوماً",
                VoucherTemplate.Language.ARABIC, "#AA3300", Map.of("2M", "1000 ل.س"), false, true, true, true, null);
        Path file = dir.resolve("arabic.pdf");

        new PdfVoucherGenerator(new QrCodeService()).export(
                List.of(new HotspotUser("714133", "aB3x9", "2M", "all", "1h")), file, "", 3, arabic);

        String text = pdfText(file);
        assertTrue(text.contains("714133"), text);
        assertFalse(text.contains("331417"), "username must not be reversed");
        assertTrue(text.contains("aB3x9"), text);
        assertFalse(text.contains("9x3Ba"), "password must not be reversed");
        assertTrue(text.contains("1000"), text);
        // Shaped Arabic is stored as joined letter forms (U+FE70-U+FEFF) in visual order, like in any
        // PDF; seeing those forms proves the letters were joined rather than printed one by one.
        assertTrue(text.chars().anyMatch(c -> c >= 0xFE70 && c <= 0xFEFF), "Arabic letters are joined: " + text);
        assertTrue(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1).contains("Cairo"),
                "the Arabic font is embedded so the PDF prints the same everywhere");
    }

    @Test
    void templateOptionsControlWhatIsPrinted() throws Exception {
        VoucherTemplate minimal = new VoucherTemplate("NetZone", "", VoucherTemplate.Language.ENGLISH, "#000000",
                Map.of("5M", "9 USD"), false, false, false, false, null);
        Path file = dir.resolve("minimal.pdf");

        new PdfVoucherGenerator(new QrCodeService()).export(
                List.of(new HotspotUser("u1", "p1", "5M", "all", "8h")), file, "http://10.10.0.1/login", 4, minimal);

        String text = pdfText(file);
        assertTrue(text.contains("NetZone"));
        assertTrue(text.contains("u1") && text.contains("p1"));
        assertFalse(text.contains("8h"), "time hidden");
        assertFalse(text.contains("9 USD"), "price hidden");
        assertFalse(text.contains("5M"), "profile hidden");
    }

    @Test
    void logoIsPrintedAndAMissingLogoIsSkipped() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(40, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        Path logo = dir.resolve("logo.png");
        javax.imageio.ImageIO.write(image, "png", logo.toFile());
        HotspotUser u = user("u1", "p1");
        PdfVoucherGenerator generator = new PdfVoucherGenerator(new QrCodeService());

        Path withLogo = dir.resolve("with-logo.pdf");
        generator.export(List.of(u), withLogo, "", 4, VoucherTemplate.defaults().withLogo(logo));
        Path missingLogo = dir.resolve("missing-logo.pdf");
        generator.export(List.of(u), missingLogo, "", 4, VoucherTemplate.defaults().withLogo(dir.resolve("gone.png")));

        assertTrue(Files.size(withLogo) > Files.size(missingLogo), "the logo image is embedded");
        assertTrue(pdfText(missingLogo).contains("u1"), "a missing logo never blocks printing");
    }

    private static String pdfText(Path file) throws Exception {
        PdfReader reader = new PdfReader(Files.readAllBytes(file)); // byte[] so Windows can delete the temp file
        try {
            StringBuilder text = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }
}
