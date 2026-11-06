package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.HotspotUser;
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

        new PdfVoucherGenerator(new QrCodeService()).export(users, file, "http://10.10.0.1/login", 5);

        PdfReader reader = new PdfReader(Files.readAllBytes(file)); // byte[] so Windows can delete the temp file
        try {
            StringBuilder text = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page));
            }
            for (HotspotUser u : users) {
                assertTrue(text.toString().contains(u.username()), "missing " + u.username());
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void pdfWithoutQrWhenLoginUrlBlank() throws Exception {
        Path file = dir.resolve("plain.pdf");
        new PdfVoucherGenerator(new QrCodeService()).export(List.of(user("a1", "b1")), file, "", 4);
        assertTrue(Files.size(file) > 0);
    }
}
