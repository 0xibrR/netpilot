package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;
import org.junit.jupiter.api.Test;

class PdfTextTest {

    private final PdfText text = PdfText.instance();
    private final PdfText.Style style = new PdfText.Style(8.5f, Color.BLACK);

    /**
     * Regression: long Arabic notes were wrapped by the PDF engine after reordering, which moved
     * the second word to the next line ("صالح 30 يوماً ... / لمدة").
     */
    @Test
    void arabicTextWrapsInReadingOrder() {
        String note = "صالح لمدة 30 يوماً من أول استخدام";

        List<String> lines = text.wrapLines(note, style, 120);

        assertTrue(lines.size() >= 2, "too wide for one line: " + lines);
        assertTrue(lines.getFirst().startsWith("صالح لمدة"), "first words stay on the first line: " + lines);
        assertEquals(note, String.join(" ", lines), "no word lost or reordered");
        lines.forEach(line -> assertTrue(text.width(line, style) <= 120 || !line.contains(" "), line));
    }

    @Test
    void shortTextStaysOnOneLineAndLongWordsAreNotSplit() {
        assertEquals(List.of("WiFi"), text.wrapLines("  WiFi ", style, 120));
        String longWord = "x".repeat(80);
        assertEquals(List.of(longWord), text.wrapLines(longWord, style, 50));
    }

    @Test
    void mixedLineKeepsEveryCharacter() {
        String line = "اسم المستخدم: 714133";
        String laidOut = text.phrase(line, style).getChunks().stream()
                .map(element -> ((org.openpdf.text.Chunk) element).getContent())
                .reduce("", String::concat);

        assertTrue(laidOut.contains("714133"), "the number is kept in reading order: " + laidOut);
        assertEquals(line.replace(" ", "").length(), laidOut.replace(" ", "").length());
    }
}
