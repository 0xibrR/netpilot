package org.netpilot.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.RouterUser;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.UsageRepository;

/**
 * The one place that records first use: it saves the usage history (Earnings & sales) and the
 * points of sale's sales together, so both always agree. Every page that reads the router's
 * users goes through here, and so does the clean-up before it deletes cards.
 */
public final class UsageTracker {

    private static final System.Logger LOG = System.getLogger(UsageTracker.class.getName());

    private final ReportService reports;
    private final UsageRepository usage;
    private final ResellerAccounts accounts;

    public UsageTracker(ReportService reports, UsageRepository usage, ResellerAccounts accounts) {
        this.reports = Objects.requireNonNull(reports);
        this.usage = Objects.requireNonNull(usage);
        this.accounts = Objects.requireNonNull(accounts);
    }

    /** Records first use from the user list alone (cards that already logged out at least once). */
    public List<UsageRecord> track(String router, List<RouterUser> users, LocalDate today) throws IOException {
        return track(router, users, Set.of(), today);
    }

    /**
     * @param router the router's address, as usage history keys it
     * @param users  every user just read from that router
     * @param online names with an active session: a card on its first login counts as used at
     *               once, though RouterOS adds its uptime to the user only when it logs out
     * @return the whole usage history (all routers), with the new first uses
     * @throws IOException when either record cannot be saved
     */
    public List<UsageRecord> track(String router, List<RouterUser> users, Set<String> online, LocalDate today)
            throws IOException {
        List<UsageRecord> before = usage.findAll();
        List<UsageRecord> history = reports.track(router, users, online, before, today);
        if (history.size() > before.size()) {
            usage.saveAll(history);
            LOG.log(System.Logger.Level.INFO, "Recorded first use of {0} card(s) on {1}",
                    history.size() - before.size(), router);
        }
        List<LedgerEvent> sales = accounts.recordSales(router, history);
        if (!sales.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Recorded {0} point-of-sale sale(s) on {1}: {2}", sales.size(), router,
                    sales.stream().map(LedgerEvent::card).toList());
        }
        return history;
    }
}
