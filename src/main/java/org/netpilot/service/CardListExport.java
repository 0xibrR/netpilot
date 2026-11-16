package org.netpilot.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.netpilot.i18n.Messages;
import org.netpilot.model.BatchTag;
import org.netpilot.model.Customer;
import org.netpilot.model.RouterDuration;
import org.netpilot.model.RouterUser;

/**
 * The cards on the router, as the card list shows them (filters applied), in Excel. Never with
 * passwords: those only go into voucher exports.
 */
public final class CardListExport {

    private static final double MB = 1_048_576.0;

    private CardListExport() {
    }

    /**
     * @param online    names logged in now
     * @param expiries  end dates the router's scheduler holds, by card name
     * @param customers linked customer numbers, by {@link Customer#key}
     */
    public static void write(String router, List<RouterUser> cards, Set<String> online, Map<String, String> expiries,
            Map<String, Customer> customers, Path file) throws IOException {
        List<List<Xlsx.Cell>> rows = new ArrayList<>();
        for (RouterUser u : cards) {
            String expiry = expiries.getOrDefault(u.name(), "");
            rows.add(List.of(Xlsx.text(u.name()), Xlsx.text(u.profile()), Xlsx.text(state(u)),
                    Xlsx.text(Messages.get(online.contains(u.name()) ? "users.online" : "users.offline")),
                    Xlsx.text(RouterDuration.clock(u.uptime())),
                    Xlsx.text(u.limit().map(RouterDuration::format).orElse("")),
                    Reminders.parse(expiry).map(Xlsx::moment).orElse(Xlsx.text(expiry)),
                    Xlsx.count(Math.round((u.bytesIn() + u.bytesOut()) / MB)),
                    u.limitBytes() > 0 ? Xlsx.count(Math.round(u.limitBytes() / MB)) : Xlsx.text(""),
                    Xlsx.moment(BatchTag.parse(u.comment()).map(BatchTag::created).orElse(null)),
                    Xlsx.text(java.util.Optional.ofNullable(customers.get(Customer.key(router, u.name())))
                            .map(Customer::phone).orElse("")),
                    Xlsx.text(u.comment())));
        }
        Xlsx.write(file, List.of(new Xlsx.Sheet(Messages.get("export.sheet.cards"), List.of(
                Messages.get("users.column.name"), Messages.get("column.profile"), Messages.get("users.column.state"),
                Messages.get("users.column.connection"), Messages.get("users.column.used"),
                Messages.get("users.column.limit"), Messages.get("users.column.expires"),
                Messages.get("export.column.usedMb"), Messages.get("export.column.limitMb"),
                Messages.get("report.column.created"), Messages.get("export.column.phone"),
                Messages.get("users.column.comment")), rows)), Messages.isArabic());
    }

    static String state(RouterUser user) {
        if (user.builtIn()) {
            return Messages.get("users.state.builtIn");
        }
        if (user.disabled()) {
            return Messages.get("users.state.disabled");
        }
        if (user.expired()) {
            return Messages.get("users.state.expired");
        }
        return Messages.get(user.neverUsed() ? "users.state.new" : "users.state.inUse");
    }
}
