package org.netpilot.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.netpilot.model.RouterUser;
import org.netpilot.model.UsageRecord;
import org.netpilot.repository.UsageRepository;

/**
 * The one place that records first use: it saves the usage history (Earnings & sales) and the
 * points of sale's sales together, so both always agree. Every page that reads the router's
 * users goes through here, and so does the clean-up before it deletes cards.
 */
public final class UsageTracker {

    private final ReportService reports;
    private final UsageRepository usage;
    private final ResellerAccounts accounts;

    public UsageTracker(ReportService reports, UsageRepository usage, ResellerAccounts accounts) {
        this.reports = Objects.requireNonNull(reports);
        this.usage = Objects.requireNonNull(usage);
        this.accounts = Objects.requireNonNull(accounts);
    }

    /**
     * @param router the router's address, as usage history keys it
     * @param users  every user just read from that router
     * @return the whole usage history (all routers), with the new first uses
     * @throws IOException when either record cannot be saved
     */
    public List<UsageRecord> track(String router, List<RouterUser> users, LocalDate today) throws IOException {
        List<UsageRecord> history = reports.track(router, users, usage.findAll(), today);
        usage.saveAll(history);
        accounts.recordSales(router, history);
        return history;
    }
}
