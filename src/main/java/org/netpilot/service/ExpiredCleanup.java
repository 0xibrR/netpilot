package org.netpilot.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.netpilot.model.RouterUser;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterException;
import org.netpilot.service.router.RouterUserActions;
import org.netpilot.service.router.RouterUserActions.BulkResult;

/**
 * Deletes the cards that used up their time (or validity, or data) from the router. Their sales
 * are recorded first, in Earnings & sales and in the points of sale's accounts, so both still
 * count them once they are gone. Used by the "Delete expired" button and by the automatic daily
 * clean-up alike.
 */
public final class ExpiredCleanup {

    private final UsageTracker tracker;
    private final RouterUserActions actions;

    public ExpiredCleanup(UsageTracker tracker, RouterUserActions actions) {
        this.tracker = Objects.requireNonNull(tracker);
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
        tracker.track(client.endpoint().address(), users, today);
        return actions.delete(client, expired);
    }
}
