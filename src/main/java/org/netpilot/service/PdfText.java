package org.netpilot.service;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openpdf.text.Chunk;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Font;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.LayoutProcessor;

/**
 * Turns mixed Arabic/English text into correctly shaped, correctly ordered PDF text.
 *
 * <p>Why this is needed, as found by testing OpenPDF with the Cairo font:
 * <ul>
 * <li>OpenPDF's classic Arabic mode uses old "presentation form" characters that modern fonts
 * do not fully include, so letters went missing. {@link LayoutProcessor} instead shapes text with
 * Java's own text engine and the font's real shaping tables: every letter is correct.</li>
 * <li>LayoutProcessor lays a whole string out in one direction, which reversed numbers and Latin
 * text inside Arabic ({@code 714133} became {@code 331417}, a wrong username). So each line is
 * split into direction runs with {@link Bidi}, each run is drawn in its own direction, and the
 * runs are placed in visual order.</li>
 * <li>The run direction is stored per font file, so two copies of the font are loaded: one for
 * right-to-left runs and one for left-to-right runs.</li>
 * </ul>
 * LayoutProcessor is deprecated in OpenPDF but is its only way to use a font's shaping tables.
 */
@SuppressWarnings("deprecation")
final class PdfText {

    private static final String FONT_RESOURCE = "/org/netpilot/fonts/Cairo.ttf";

    /** Size and color of a piece of text. */
    record Style(float size, Color color) {
    }

    /** A piece of a line in one style, e.g. a label and its value. */
    record Segment(String text, Style style) {
    }

    private static PdfText instance;

    private final BaseFont rtlFont;
    private final BaseFont ltrFont;

    private PdfText(BaseFont rtlFont, BaseFont ltrFont) {
        this.rtlFont = rtlFont;
        this.ltrFont = ltrFont;
    }

    /** Loads the embedded font once per run of the app. */
    static synchronized PdfText instance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static PdfText load() {
        try {
            // LayoutProcessor reads the font from a file, so copy the bundled font out once
            Path dir = Files.createTempDirectory("netpilot-fonts");
            Path rtlFile = copyFont(dir.resolve("Cairo-rtl.ttf"));
            Path ltrFile = copyFont(dir.resolve("Cairo-ltr.ttf"));

            LayoutProcessor.enable();
            BaseFont rtl = BaseFont.createFont(rtlFile.toString(), BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            LayoutProcessor.loadFont(rtl, rtlFile.toString());
            BaseFont ltr = BaseFont.createFont(ltrFile.toString(), BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            LayoutProcessor.loadFont(ltr, ltrFile.toString());
            LayoutProcessor.setRunDirectionRtl(new Font(rtl));
            LayoutProcessor.setRunDirectionLtr(new Font(ltr));
            return new PdfText(rtl, ltr);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load the voucher font", e);
        } catch (DocumentException e) {
            throw new IllegalStateException("Cannot load the voucher font", e);
        }
    }

    private static Path copyFont(Path target) throws IOException {
        try (InputStream in = PdfText.class.getResourceAsStream(FONT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing font resource " + FONT_RESOURCE);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        Path dir = target.getParent();
        if (dir != null) {
            dir.toFile().deleteOnExit();
        }
        return target;
    }

    Phrase phrase(String text, Style style) {
        return phrase(List.of(new Segment(text, style)));
    }

    /**
     * Splits text into lines that fit {@code maxWidth}, in reading order, then lays out each line.
     *
     * <p>PDF line breaking works left to right on the already reordered text, which would put the
     * first Arabic words on the wrong line; wrapping here, before reordering, keeps sentences intact.
     */
    List<Phrase> wrapped(String text, Style style, float maxWidth) {
        return wrapLines(text, style, maxWidth).stream().map(line -> phrase(line, style)).toList();
    }

    /** The lines, in reading order, before layout. A single word wider than the line stays whole. */
    List<String> wrapLines(String text, Style style, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && width(candidate, style) > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    /**
     * Width in points as OpenPDF's own line breaking measures it: from unjoined letters, which is
     * wider than the printed (joined) Arabic. Wrapping by this measure guarantees OpenPDF never
     * breaks a line we laid out.
     */
    float width(String text, Style style) {
        return ltrFont.getWidthPoint(text, style.size());
    }

    /**
     * Builds one line from segments, laid out right-to-left when it starts with Arabic.
     */
    Phrase phrase(List<Segment> segments) {
        StringBuilder all = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        for (Segment segment : segments) {
            starts.add(all.length());
            all.append(segment.text());
        }
        String text = all.toString();
        Phrase out = new Phrase();
        if (text.isBlank()) {
            return out;
        }

        Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
        int runCount = bidi.getRunCount();
        byte[] levels = new byte[runCount];
        Integer[] order = new Integer[runCount];
        for (int i = 0; i < runCount; i++) {
            levels[i] = (byte) bidi.getRunLevel(i);
            order[i] = i;
        }
        Bidi.reorderVisually(levels, 0, order, 0, runCount);

        Style spaceStyle = segments.getFirst().style();
        boolean first = true;
        boolean pendingSpace = false;
        for (int run : order) {
            boolean rtl = (bidi.getRunLevel(run) & 1) == 1;
            int start = bidi.getRunStart(run);
            int end = bidi.getRunLimit(run);

            // Spaces at run edges get lost in layout, so strip them and add explicit space chunks.
            int trimmedStart = start;
            while (trimmedStart < end && Character.isWhitespace(text.charAt(trimmedStart))) {
                trimmedStart++;
            }
            int trimmedEnd = end;
            while (trimmedEnd > trimmedStart && Character.isWhitespace(text.charAt(trimmedEnd - 1))) {
                trimmedEnd--;
            }
            if (trimmedStart == trimmedEnd) {
                pendingSpace = !first;
                continue;
            }
            boolean spaceOnLeft = rtl ? trimmedEnd < end : trimmedStart > start;
            boolean spaceOnRight = rtl ? trimmedStart > start : trimmedEnd < end;
            if (!first && (pendingSpace || spaceOnLeft)) {
                out.add(new Chunk(" ", font(ltrFont, spaceStyle)));
            }

            List<Chunk> pieces = new ArrayList<>();
            for (int s = 0; s < segments.size(); s++) {
                int segStart = Math.max(starts.get(s), trimmedStart);
                int segEnd = Math.min(starts.get(s) + segments.get(s).text().length(), trimmedEnd);
                if (segStart < segEnd) {
                    pieces.add(new Chunk(text.substring(segStart, segEnd),
                            font(rtl ? rtlFont : ltrFont, segments.get(s).style())));
                }
            }
            if (rtl) {
                Collections.reverse(pieces); // pieces of a right-to-left run read from right to left
            }
            pieces.forEach(out::add);

            pendingSpace = spaceOnRight;
            first = false;
        }
        return out;
    }

    private static Font font(BaseFont base, Style style) {
        return new Font(base, style.size(), Font.NORMAL, style.color());
    }
}
