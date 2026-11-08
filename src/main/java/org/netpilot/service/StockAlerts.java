package org.netpilot.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.netpilot.model.BatchTag;
import org.netpilot.model.RouterUser;

/**
 * Packages that are running out of cards ready to sell: unused NetPilot cards (never logged in,
 * not disabled, not expired) per package, compared with the user's limit. Only packages with
 * NetPilot cards on the router count, so a package never sold from (like {@code default}) is not
 * reported; hand-made users are not stock.
 */
public final class StockAlerts {

    /** A package with fewer unused cards than the limit; {@code unused} may be 0 (sold out). */
    public record Low(String profile, int unused) {
    }

    private StockAlerts() {
    }

    /**
     * @param below warn when a package has fewer unused cards than this; 0 turns alerts off
     * @return low packages, the emptiest first
     */
    public static List<Low> low(List<RouterUser> users, int below) {
        if (below <= 0) {
            return List.of();
        }
        Map<String, Integer> unused = new TreeMap<>();
        for (RouterUser card : users) {
            if (card.builtIn() || card.profile().isBlank() || BatchTag.parse(card.comment()).isEmpty()) {
                continue;
            }
            boolean ready = card.neverUsed() && !card.disabled() && !card.expired();
            unused.merge(card.profile(), ready ? 1 : 0, Integer::sum);
        }
        return unused.entrySet().stream()
                .filter(e -> e.getValue() < below)
                .map(e -> new Low(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(Low::unused).thenComparing(Low::profile))
                .toList();
    }
}
