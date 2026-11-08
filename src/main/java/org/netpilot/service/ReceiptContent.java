package org.netpilot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.TimeLimit;
import org.netpilot.model.VoucherTemplate;

/**
 * What one receipt says, top to bottom, independent of how it is drawn. Follows the card design's
 * choices (network name, logo, which fields show, labels' language, note), so the user sets them
 * once for A4 cards and receipts alike.
 *
 * @param lines the receipt from top to bottom
 * @param rtl   Arabic labels, drawn right to left
 */
public record ReceiptContent(List<Line> lines, boolean rtl) {

    /** One part of the receipt. */
    public sealed interface Line {
    }

    /** The network name, large and bold. */
    public record Title(String text) implements Line {
    }

    /** A label and its value on one row, e.g. "الباقة  2M". */
    public record Field(String label, String value) implements Line {
    }

    /** The card number (or password) under its label, large enough to type from. */
    public record Code(String label, String value) implements Line {
    }

    /** The QR auto-login link. */
    public record Qr(String link) implements Line {
    }

    /** Small text, e.g. the card design's note. */
    public record Note(String text) implements Line {
    }

    /** A dashed separator. */
    public record Rule() implements Line {
    }

    public ReceiptContent {
        lines = List.copyOf(lines);
    }

    /**
     * @param qrLink   QR auto-login link for this card, empty when there is none
     * @param validity the package's validity after first login, empty when it has none
     */
    public static ReceiptContent of(HotspotUser card, VoucherTemplate template, Optional<String> qrLink,
            Optional<TimeLimit> validity) {
        VoucherTemplate.Language words = template.language();
        List<Line> lines = new ArrayList<>();
        if (!template.networkName().isBlank()) {
            lines.add(new Title(template.networkName()));
        }
        lines.add(new Rule());

        boolean sameCode = card.password().isEmpty() || card.password().equals(card.username());
        lines.add(new Code(words.username(), card.username()));
        if (!sameCode) {
            lines.add(new Code(words.password(), card.password()));
        }
        lines.add(new Rule());

        if (template.showProfile() && !card.profile().isBlank()) {
            lines.add(new Field(words.profile(), card.profile()));
        }
        if (template.showUptime() && !card.limitUptime().isBlank()) {
            lines.add(new Field(words.time(), card.limitUptime()));
        }
        validity.ifPresent(v -> lines.add(new Field(words.validity(), v.routerText())));
        String price = template.prices().getOrDefault(card.profile(), "");
        if (template.showPrice() && !price.isBlank()) {
            lines.add(new Field(words.price(), price));
        }

        if (template.showQr()) {
            qrLink.filter(link -> !link.isBlank()).ifPresent(link -> lines.add(new Qr(link)));
        }
        if (!template.note().isBlank()) {
            lines.add(new Note(template.note()));
        }
        return new ReceiptContent(lines, words == VoucherTemplate.Language.ARABIC);
    }
}
