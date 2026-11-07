package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.CardImage;
import org.netpilot.model.CardSize;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.VoucherStyle;
import org.netpilot.model.VoucherTemplate;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class CardLayoutTest {

    /** Roughly like a sans-serif font: half the font size per character. */
    private static final CardLayout.Measure MEASURE = (text, size) -> text.length() * size * 0.5;

    @TempDir
    Path dir;

    private static VoucherTemplate template(VoucherStyle style, CardSize size, VoucherTemplate.Language language) {
        return new VoucherTemplate("Al Amal", "", language, "#2F7BD8", Map.of("2M", "500"), true, true, true, true,
                null, style, size, CardImage.defaults());
    }

    private static List<CardLayout.Element> layout(VoucherTemplate t, HotspotUser user, boolean qr) {
        CardLayout.Card card = CardLayout.Card.of(user, t, qr);
        return CardLayout.layout(t, card, t.size().width(), t.size().height(), MEASURE);
    }

    private static List<CardLayout.Text> texts(List<CardLayout.Element> elements) {
        return elements.stream().filter(CardLayout.Text.class::isInstance).map(CardLayout.Text.class::cast).toList();
    }

    @Test
    void everyStyleKeepsEverythingInsideTheCard() {
        HotspotUser user = new HotspotUser("aVeryLongUserName123", "Pass-98765", "2M", "all", "1h");
        for (VoucherStyle style : VoucherStyle.values()) {
            for (CardSize size : CardSize.values()) {
                for (VoucherTemplate.Language language : VoucherTemplate.Language.values()) {
                    VoucherTemplate t = template(style, size, language);
                    for (CardLayout.Element e : layout(t, user, true)) {
                        String where = style + "/" + size + "/" + language + ": " + e;
                        if (e instanceof CardLayout.Text text) {
                            double w = MEASURE.width(text.text(), text.size());
                            double left = switch (text.align()) {
                                case LEFT -> text.x();
                                case CENTER -> text.x() - w / 2;
                                case RIGHT -> text.x() - w;
                            };
                            assertTrue(left >= -0.5 && left + w <= size.width() + 0.5, where);
                            assertTrue(text.baseline() > 0 && text.baseline() <= size.height() + 0.5, where);
                        } else if (e instanceof CardLayout.Qr qr) {
                            assertTrue(qr.x() >= 0 && qr.x() + qr.size() <= size.width() + 0.01, where);
                            assertTrue(qr.y() >= 0 && qr.y() + qr.size() <= size.height() + 0.01, where);
                        }
                    }
                }
            }
        }
    }

    @Test
    void modernShowsNetworkPriceAndBothCodes() {
        List<CardLayout.Element> elements = layout(template(VoucherStyle.MODERN, CardSize.SMALL,
                VoucherTemplate.Language.ENGLISH), new HotspotUser("714133", "5821", "2M", "all", "1h"), true);

        List<String> shown = texts(elements).stream().map(CardLayout.Text::text).toList();
        assertTrue(shown.contains("Al Amal"));
        assertTrue(shown.contains("500"));
        assertTrue(shown.contains("714133"));
        assertTrue(shown.contains("Pass: 5821"));
        assertTrue(shown.contains("1h · 2M"));
        assertTrue(elements.stream().anyMatch(CardLayout.Fill.class::isInstance), "coloured band");
        assertTrue(elements.stream().anyMatch(CardLayout.Qr.class::isInstance));
    }

    @Test
    void sameUsernameAndPasswordIsPrintedOnce() {
        List<CardLayout.Text> shown = texts(layout(template(VoucherStyle.MINIMAL, CardSize.TINY,
                VoucherTemplate.Language.ENGLISH), new HotspotUser("90001390", "90001390", "2M", "all", ""), true));

        assertEquals(1, shown.stream().filter(t -> t.text().contains("90001390")).count());
    }

    @Test
    void minimalNeverPrintsAQrCode() {
        List<CardLayout.Element> elements = layout(template(VoucherStyle.MINIMAL, CardSize.TINY,
                VoucherTemplate.Language.ENGLISH), new HotspotUser("a", "b", "2M", "all", ""), true);

        assertFalse(elements.stream().anyMatch(CardLayout.Qr.class::isInstance));
    }

    @Test
    void arabicPutsTheQrOnTheLeft() {
        HotspotUser user = new HotspotUser("714133", "5821", "2M", "all", "1h");
        VoucherTemplate arabic = template(VoucherStyle.MODERN, CardSize.SMALL, VoucherTemplate.Language.ARABIC);
        CardLayout.Qr qr = layout(arabic, user, true).stream().filter(CardLayout.Qr.class::isInstance)
                .map(CardLayout.Qr.class::cast).findFirst().orElseThrow();

        assertTrue(qr.x() < CardSize.SMALL.width() / 2);
    }

    @Test
    void ownPictureFollowsTheDraggedSpots() {
        CardImage picture = new CardImage(Path.of("card.png"), new CardImage.Spot(0.3, 0.4), null,
                new CardImage.Spot(0.8, 0.5), null, "#000000", 1);
        VoucherTemplate t = template(VoucherStyle.IMAGE, CardSize.MEDIUM, VoucherTemplate.Language.ENGLISH)
                .withImage(picture);

        List<CardLayout.Element> elements = layout(t, new HotspotUser("714133", "5821", "2M", "all", "1h"), true);

        assertTrue(elements.getFirst() instanceof CardLayout.Background, "picture first, details on top");
        CardLayout.Text username = texts(elements).stream().filter(x -> x.slot() == CardLayout.Slot.USERNAME)
                .findFirst().orElseThrow();
        assertEquals(0.3 * CardSize.MEDIUM.width(), username.x(), 0.01);
        assertEquals("#000000", username.color());
        CardLayout.Qr qr = elements.stream().filter(CardLayout.Qr.class::isInstance).map(CardLayout.Qr.class::cast)
                .findFirst().orElseThrow();
        assertEquals(0.8 * CardSize.MEDIUM.width(), qr.x() + qr.size() / 2, 0.01);
    }

    @Test
    void textOnOwnPictureGetsAContrastingBox() {
        HotspotUser user = new HotspotUser("714133", "5821", "2M", "all", "1h");
        VoucherTemplate base = template(VoucherStyle.IMAGE, CardSize.SMALL, VoucherTemplate.Language.ENGLISH);

        List<CardLayout.Element> white = layout(base.withImage(
                new CardImage(Path.of("c.png"), null, null, null, null, "#FFFFFF", 1, true)), user, false);
        int username = white.indexOf(texts(white).stream().filter(t -> t.slot() == CardLayout.Slot.USERNAME)
                .findFirst().orElseThrow());
        CardLayout.Box box = (CardLayout.Box) white.get(username - 1);
        assertEquals("#000000", box.color(), "white text gets a dark box, drawn just before it");

        List<CardLayout.Element> dark = layout(base.withImage(
                new CardImage(Path.of("c.png"), null, null, null, null, "#111111", 1, true)), user, false);
        assertTrue(dark.stream().filter(CardLayout.Box.class::isInstance).map(CardLayout.Box.class::cast)
                .allMatch(b -> b.color().equals("#FFFFFF")), "dark text gets a light box");

        List<CardLayout.Element> plain = layout(base.withImage(
                new CardImage(Path.of("c.png"), null, null, null, null, "#111111", 1, false)), user, false);
        assertFalse(plain.stream().anyMatch(CardLayout.Box.class::isInstance), "box can be switched off");
    }

    @Test
    void cardSizesFitTheirGridOnA4() {
        for (CardSize size : CardSize.values()) {
            assertEquals(CardSize.PAGE_WIDTH - 2 * CardSize.MARGIN, size.width() * size.columns(), 0.01);
            assertEquals(CardSize.PAGE_HEIGHT - 2 * CardSize.MARGIN, size.height() * size.rows(), 0.01);
        }
        assertEquals(65, CardSize.TINY.perPage());
        assertEquals(40, CardSize.SMALL.perPage());
    }

    @Test
    void compactPdfPrintsCredentialsAndFillsPages() throws Exception {
        List<HotspotUser> users = new java.util.ArrayList<>();
        for (int i = 0; i < 41; i++) {
            users.add(new HotspotUser("u" + (1000 + i), "p" + i, "2M", "all", "1h"));
        }
        PdfVoucherGenerator generator = new PdfVoucherGenerator(new QrCodeService());
        for (VoucherStyle style : List.of(VoucherStyle.MODERN, VoucherStyle.QR_SIDE, VoucherStyle.MINIMAL)) {
            Path file = dir.resolve(style + ".pdf");
            generator.export(users, file, "http://10.10.0.1/login", 4,
                    template(style, CardSize.SMALL, VoucherTemplate.Language.ARABIC));

            PdfReader reader = new PdfReader(Files.readAllBytes(file));
            try {
                assertEquals(2, reader.getNumberOfPages(), style + ": 40 small cards per page");
                String first = new PdfTextExtractor(reader).getTextFromPage(1);
                assertTrue(first.contains("u1000") && first.contains("u1039"), style + ": " + first);
                assertFalse(first.contains("0001u"), "codes are not reversed in Arabic layouts");
            } finally {
                reader.close();
            }
        }
    }

    @Test
    void ownPicturePdfEmbedsThePicture() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(300, 170,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        Path picture = dir.resolve("card.png");
        javax.imageio.ImageIO.write(image, "png", picture.toFile());
        List<HotspotUser> users = List.of(new HotspotUser("714133", "5821", "2M", "all", "1h"));
        PdfVoucherGenerator generator = new PdfVoucherGenerator(new QrCodeService());
        VoucherTemplate base = template(VoucherStyle.IMAGE, CardSize.MEDIUM, VoucherTemplate.Language.ENGLISH);

        Path with = dir.resolve("with.pdf");
        generator.export(users, with, "", 3, base.withImage(CardImage.defaults().withBackground(picture)));
        Path without = dir.resolve("without.pdf");
        generator.export(users, without, "", 3, base.withImage(CardImage.defaults().withBackground(dir.resolve("x"))));

        assertTrue(Files.size(with) > Files.size(without), "the picture is embedded");
        PdfReader reader = new PdfReader(Files.readAllBytes(without));
        try {
            assertTrue(new PdfTextExtractor(reader).getTextFromPage(1).contains("714133"),
                    "a missing picture never blocks printing");
        } finally {
            reader.close();
        }
    }
}
