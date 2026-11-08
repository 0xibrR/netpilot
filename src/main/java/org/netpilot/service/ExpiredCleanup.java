package org.netpilot.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.netpilot.model.RouterUser;
import org.netpilot.repository.UsageRepository;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;
import org.netpilot.service.router.RouterUserActions;
import org.netpilot.service.router.RouterUserActions.BulkResult;

/**
 * Deletes the cards that used up their time (or validity) from the router. Their sales are saved
 * first, so Earnings & sales still counts them once they are gone. Used by the "Delete expired"
 * button and by the automatic daily clean-up alike.
 */
public final class ExpiredCleanup {

    private final ReportService reports;
    private final UsageRepository usage;
    private final RouterUserActions actions;

    public ExpiredCleanup(ReportService reports, UsageRepository usage, RouterUserActions actions) {
        this.reports = Objects.requireNonNull(reports);
        this.usage = Objects.requireNonNull(usage);
        this.actions = Objects.requireNonNull(actions);
    }

    /** Reads the router's users and deletes the expired ones. */
    public BulkResult run(RouterClient client, LocalDate today) throws RouterException, IOException {
        return delete(client, client.hotspotUsers(), today);
    }

    /**
     * @param users every user on the router, as just read (their usage is recorded before deleting)
     * @return how many expired cards were deleted, of how many; nothing happens when none expired
     * @throws IOException when the sales cannot be saved: then nothing is deleted
     */
    public BulkResult delete(RouterClient client, List<RouterUser> users, LocalDate today) throws IOException {
        List<RouterUser> expired = RouterUserActions.expired(users);
        if (expired.isEmpty()) {
            return new BulkResult(0, 0, List.of());
        }
        usage.saveAll(reports.track(client.endpoint().address(), users, usage.findAll(), today));
        return actions.delete(client, expired);
    }
}
