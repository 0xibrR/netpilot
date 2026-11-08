package org.netpilot.service.router;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.netpilot.i18n.Messages;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterUser;

/**
 * Bulk operations on users that already exist on the router (Router Users window).
 *
 * <p>Each operation goes item by item, continues past problems with single items, and stops
 * at the first problem that would affect every item (lost connection, missing permission).
 */
public final class RouterUserActions {

    private static final System.Logger LOG = System.getLogger(RouterUserActions.class.getName());

    /** How a bulk operation went. Problems name the user but never contain passwords. */
    public record BulkResult(int succeeded, int total, List<String> problems) {

        public BulkResult {
            problems = List.copyOf(problems);
        }
    }

    @FunctionalInterface
    private interface Action<T> {

        void apply(T item) throws RouterException;
    }

    public BulkResult disable(RouterClient client, List<RouterUser> users) {
        return forEach("disable", users, RouterUser::name, u -> {
            protectBuiltIn(u);
            client.setHotspotUserDisabled(u.routerId(), true);
        });
    }

    public BulkResult enable(RouterClient client, List<RouterUser> users) {
        return forEach("enable", users, RouterUser::name, u -> client.setHotspotUserDisabled(u.routerId(), false));
    }

    public BulkResult delete(RouterClient client, List<RouterUser> users) {
        return forEach("delete", users, RouterUser::name, u -> {
            protectBuiltIn(u);
            client.deleteHotspotUser(u.routerId());
        });
    }

    /** Cards that used up their whole time limit, without RouterOS's own trial user. */
    public static List<RouterUser> expired(List<RouterUser> users) {
        return users.stream().filter(u -> u.expired() && !u.builtIn()).toList();
    }

    /** "Select all" must never take RouterOS's own trial user with it. */
    private static void protectBuiltIn(RouterUser user) throws RouterException {
        if (user.builtIn()) {
            throw new RouterException(RouterException.Kind.NOT_SUPPORTED, Messages.get("actions.builtInSkipped"));
        }
    }

    public BulkResult disconnect(RouterClient client, List<ActiveSession> sessions) {
        return forEach("disconnect", sessions, ActiveSession::user, s -> client.disconnect(s.routerId()));
    }

    /**
     * Builds voucher data (with passwords) for reprinting. Users deleted meanwhile are left out.
     */
    public List<HotspotUser> voucherUsers(RouterClient client, List<RouterUser> users) throws RouterException {
        Map<String, String> passwords = client.hotspotPasswords(users.stream().map(RouterUser::routerId).toList());
        List<HotspotUser> vouchers = new ArrayList<>();
        for (RouterUser user : users) {
            String password = passwords.get(user.routerId());
            if (password != null) {
                vouchers.add(new HotspotUser(user.name(), password, user.profile(), user.server(), user.limitUptime(),
                        user.limitBytes()));
            }
        }
        return vouchers;
    }

    private static <T> BulkResult forEach(String verb, List<T> items, Function<T, String> name, Action<T> action) {
        int succeeded = 0;
        List<String> problems = new ArrayList<>();
        for (T item : items) {
            try {
                action.apply(item);
                succeeded++;
            } catch (RouterException e) {
                problems.add(name.apply(item) + ": " + e.getMessage());
                if (e.affectsEveryRequest()) {
                    break;
                }
            }
        }
        LOG.log(System.Logger.Level.INFO, "Bulk {0}: {1} of {2} succeeded", verb, succeeded, items.size());
        return new BulkResult(succeeded, items.size(), problems);
    }
}
