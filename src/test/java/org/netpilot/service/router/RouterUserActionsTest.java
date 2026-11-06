package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterUser;

class RouterUserActionsTest {

    private static final String USER = "netpilot";
    private static final String PASSWORD = "secret";

    private final RouterUserActions actions = new RouterUserActions();
    private FakeRouter router;
    private RouterClient client;

    @BeforeEach
    void connect() throws Exception {
        router = FakeRouter.http(USER, PASSWORD);
        client = new RouterConnector(Duration.ofSeconds(2), Duration.ofSeconds(2))
                .connect(new RouterEndpoint("127.0.0.1", router.port(), false, USER), PASSWORD.toCharArray(), null)
                .client();
    }

    @AfterEach
    void disconnect() {
        client.close();
        router.close();
    }

    @Test
    void readsUsersWithUsageButWithoutPasswords() throws Exception {
        router.addUser("714133", "pw1", "1M", "8h", "NetPilot 2026-11-06 21:56 #b062", "1h20m", 1_000, 230_000_000L,
                false);
        router.addUser("old", "pw2", "2M", "", "", "4d06:13:16", 0, 0, true);

        List<RouterUser> users = client.hotspotUsers();

        assertEquals(2, users.size());
        RouterUser first = users.getFirst();
        assertEquals("714133", first.name());
        assertEquals("1M", first.profile());
        assertEquals(Duration.ofMinutes(80), first.uptime());
        assertEquals("8h", first.limitUptime());
        assertEquals(230_001_000L, first.totalBytes());
        assertEquals("NetPilot 2026-11-06 21:56 #b062", first.comment());
        assertFalse(first.expired());
        assertTrue(users.get(1).disabled());
        assertEquals(Duration.ofDays(4).plusHours(6).plusMinutes(13).plusSeconds(16), users.get(1).uptime());
        assertFalse(router.lastQuery("/rest/ip/hotspot/user").contains("password"),
                "the user list must not ask for passwords");
    }

    @Test
    void readsActiveSessions() throws Exception {
        router.addSession("714133", "10.5.50.20");

        List<ActiveSession> sessions = client.activeSessions();

        assertEquals(1, sessions.size());
        assertEquals("714133", sessions.getFirst().user());
        assertEquals("10.5.50.20", sessions.getFirst().address());
        assertEquals(Duration.ofMinutes(5), sessions.getFirst().uptime());
    }

    @Test
    void disablesAndEnablesUsers() throws Exception {
        router.addUser("a", "p", "1M", "", "", "0s", 0, 0, false);
        router.addUser("b", "p", "1M", "", "", "0s", 0, 0, false);

        RouterUserActions.BulkResult disabled = actions.disable(client, client.hotspotUsers());

        assertEquals(new RouterUserActions.BulkResult(2, 2, List.of()), disabled);
        assertTrue(router.users().values().stream().allMatch(FakeRouter.StoredUser::disabled));

        actions.enable(client, client.hotspotUsers());
        assertTrue(router.users().values().stream().noneMatch(FakeRouter.StoredUser::disabled));
    }

    @Test
    void deletesSelectedUsersOnly() throws Exception {
        router.addUser("expired", "p", "1M", "1h", "", "1h", 0, 0, false);
        router.addUser("fresh", "p", "1M", "1h", "", "0s", 0, 0, false);
        List<RouterUser> expired = client.hotspotUsers().stream().filter(RouterUser::expired).toList();

        RouterUserActions.BulkResult result = actions.delete(client, expired);

        assertEquals(1, result.succeeded());
        assertEquals(List.of("fresh"), router.users().values().stream().map(FakeRouter.StoredUser::name).toList());
    }

    @Test
    void disconnectsSessions() throws Exception {
        router.addSession("u1", "10.5.50.20");
        router.addSession("u2", "10.5.50.21");

        RouterUserActions.BulkResult result = actions.disconnect(client, client.activeSessions());

        assertEquals(2, result.succeeded());
        assertTrue(router.sessions().isEmpty());
    }

    @Test
    void bulkActionStopsOnPermissionProblem() throws Exception {
        String first = router.addUser("a", "p", "1M", "", "", "0s", 0, 0, false);
        router.addUser("b", "p", "1M", "", "", "0s", 0, 0, false);
        router.route("/rest/ip/hotspot/user/" + first, 403, "{\"error\":403,\"message\":\"Forbidden\"}");

        RouterUserActions.BulkResult result = actions.delete(client, client.hotspotUsers());

        assertEquals(0, result.succeeded());
        assertEquals(1, result.problems().size(), "stops instead of failing every user");
        assertTrue(result.problems().getFirst().startsWith("a: "));
        assertEquals(2, router.users().size());
    }

    /** Regression: "Never used" + Select all + Delete would have removed RouterOS's trial user. */
    @Test
    void builtInTrialUserIsNeverDeletedOrDisabled() throws Exception {
        router.addUser("default-trial", "", "", "", "counters and limits for trial users", "0s", 0, 0, false);
        router.addUser("card1", "p", "1M", "", "", "0s", 0, 0, false);
        List<RouterUser> all = client.hotspotUsers();

        RouterUserActions.BulkResult disabled = actions.disable(client, all);
        RouterUserActions.BulkResult deleted = actions.delete(client, all);

        assertEquals(1, disabled.succeeded());
        assertEquals(1, deleted.succeeded(), "the other user is still processed");
        assertTrue(deleted.problems().getFirst().startsWith("default-trial: built-in"));
        assertEquals(List.of("default-trial"),
                router.users().values().stream().map(FakeRouter.StoredUser::name).toList());
        assertFalse(router.users().values().iterator().next().disabled());
    }

    @Test
    void reprintFetchesPasswordsOnlyForSelectedUsers() throws Exception {
        router.addUser("a", "pa", "1M", "8h", "", "0s", 0, 0, false);
        router.addUser("b", "pb", "2M", "", "", "0s", 0, 0, false);
        RouterUser a = client.hotspotUsers().getFirst();

        List<HotspotUser> vouchers = actions.voucherUsers(client, List.of(a));

        assertEquals(List.of(new HotspotUser("a", "pa", "1M", "all", "8h")), vouchers);
    }

    @Test
    void reprintSkipsUsersDeletedMeanwhile() throws Exception {
        router.addUser("a", "pa", "1M", "", "", "0s", 0, 0, false);
        RouterUser a = client.hotspotUsers().getFirst();
        client.deleteHotspotUser(a.routerId());

        assertTrue(actions.voucherUsers(client, List.of(a)).isEmpty());
    }

    @Test
    void sessionIdsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> client.disconnect("1;/system/reboot"));
        assertThrows(IllegalArgumentException.class, () -> client.setHotspotUserDisabled("../x", true));
    }
}
