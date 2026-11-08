package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.netpilot.model.CodeFormat;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.UploadReport;
import org.netpilot.service.UserGenerator;

class RouterUploadServiceTest {

    private static final String USER = "netpilot";
    private static final String PASSWORD = "secret";

    private final UserGenerator generator = new UserGenerator(new Random(7));
    private final RouterUploadService service = new RouterUploadService(generator,
            Clock.fixed(Instant.parse("2026-11-06T21:40:00Z"), ZoneOffset.UTC), new Random(1));

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

    private static GenerationSettings settings(CodeFormat format, int count) {
        return new GenerationSettings(count, format, "", "", 6, 6, "2M", "all", "1h");
    }

    /** A generator that always produces the same number, so a taken number cannot be replaced. */
    private static UserGenerator stuckGenerator() {
        int[] digits = {5, 8, 2, 9, 1, 7}; // always "582917": strong, but never a different number
        return new UserGenerator(new java.util.random.RandomGenerator() {

            private int next;

            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public int nextInt(int bound) {
                return digits[next++ % digits.length] % bound;
            }
        });
    }

    private UploadReport upload(List<HotspotUser> users, GenerationSettings settings) throws RouterException {
        return service.upload(client, users, settings, (done, total, name) -> {
        }, () -> false);
    }

    @Test
    void createsEveryUserWithTheBatchComment() throws Exception {
        GenerationSettings settings = settings(CodeFormat.LETTERS_AND_DIGITS, 5);
        List<HotspotUser> users = generator.generate(settings);
        List<String> progress = new ArrayList<>();

        UploadReport report = service.upload(client, users, settings,
                (done, total, name) -> progress.add(done + "/" + total), () -> false);

        assertTrue(report.completeSuccess());
        assertEquals(users, report.createdUsers());
        assertEquals("NetPilot 2026-11-06 21:40 #" + String.format("%04x", new Random(1).nextInt(0x10000)),
                report.batchComment());
        assertEquals(List.of("0/5", "1/5", "2/5", "3/5", "4/5", "5/5"), progress);

        assertEquals(5, router.users().size());
        FakeRouter.StoredUser stored = router.users().get(report.created().getFirst().routerId());
        assertEquals(users.getFirst().username(), stored.name());
        assertEquals(users.getFirst().password(), stored.password());
        assertEquals("2M", stored.profile());
        assertEquals("all", stored.server());
        assertEquals("1h", stored.limitUptime());
        assertEquals(report.batchComment(), stored.comment());
    }

    @Test
    void existingRandomNamesAreReplacedSoTheCountStaysTheSame() throws Exception {
        GenerationSettings settings = settings(CodeFormat.LETTERS_AND_DIGITS, 4);
        List<HotspotUser> users = generator.generate(settings);
        router.addExistingUser(users.get(1).username());

        UploadReport report = upload(users, settings);

        assertEquals(4, report.created().size());
        assertEquals(1, report.replaced());
        assertTrue(report.skipped().isEmpty());
        assertFalse(report.createdUsers().contains(users.get(1)));
        assertEquals(5, router.users().size(), "4 new + 1 that existed before");
    }

    @Test
    void takenNumberIsSkippedWhenNoFreeNumberIsFound() throws Exception {
        UserGenerator stuck = stuckGenerator();
        RouterUploadService stuckService = new RouterUploadService(stuck,
                Clock.fixed(Instant.parse("2026-11-06T21:40:00Z"), ZoneOffset.UTC), new Random(1));
        GenerationSettings settings = settings(CodeFormat.DIGITS, 1);
        List<HotspotUser> users = stuck.generate(settings);
        router.addExistingUser(users.getFirst().username());

        UploadReport report = stuckService.upload(client, users, settings, (done, total, name) -> {
        }, () -> false);

        assertEquals(users, report.skipped());
        assertFalse(report.completeSuccess());
    }

    @Test
    void numberTakenDuringTheUploadIsHandledLikeAnExistingNumber() throws Exception {
        UserGenerator stuck = stuckGenerator();
        RouterUploadService stuckService = new RouterUploadService(stuck,
                Clock.fixed(Instant.parse("2026-11-06T21:40:00Z"), ZoneOffset.UTC), new Random(1));
        GenerationSettings settings = settings(CodeFormat.DIGITS, 1);
        List<HotspotUser> users = stuck.generate(settings);
        // the router reports the number as taken only when we try to create it
        router.route("/rest/ip/hotspot/user", 200, "[]");
        router.addExistingUser(users.getFirst().username());

        UploadReport report = stuckService.upload(client, users, settings, (done, total, name) -> {
        }, () -> false);

        assertEquals(users, report.skipped());
        assertEquals(0, report.created().size());
    }

    @Test
    void connectionProblemStopsAndReportsTheRest() throws Exception {
        GenerationSettings settings = settings(CodeFormat.DIGITS, 6);
        List<HotspotUser> users = generator.generate(settings);
        router.failCreatesAfter(2, 403, "{\"error\":403,\"message\":\"Forbidden\"}");

        UploadReport report = upload(users, settings);

        assertEquals(2, report.created().size());
        assertEquals(1, report.failed().size());
        assertEquals(3, report.notSent().size());
        assertTrue(report.stopReason().orElseThrow().contains("'write'"), report.stopReason().get());
        assertFalse(report.failed().getFirst().reason().contains(users.get(2).password()), "no passwords in reasons");
    }

    @Test
    void repeatedSameFailureStopsTheUpload() throws Exception {
        GenerationSettings settings = settings(CodeFormat.DIGITS, 10);
        List<HotspotUser> users = generator.generate(settings);
        router.failCreatesAfter(0, 400,
                "{\"detail\":\"input does not match any value of profile\",\"error\":400,\"message\":\"Bad Request\"}");

        UploadReport report = upload(users, settings);

        assertEquals(RouterUploadService.MAX_CONSECUTIVE_FAILURES, report.failed().size());
        assertEquals(10 - RouterUploadService.MAX_CONSECUTIVE_FAILURES, report.notSent().size());
        assertTrue(report.stopReason().orElseThrow().contains("profile"));
    }

    @Test
    void cancellingStopsBeforeTheNextUser() throws Exception {
        GenerationSettings settings = settings(CodeFormat.DIGITS, 5);
        List<HotspotUser> users = generator.generate(settings);
        AtomicInteger checks = new AtomicInteger();

        UploadReport report = service.upload(client, users, settings, (done, total, name) -> {
        }, () -> checks.incrementAndGet() > 2);

        assertEquals(2, report.created().size());
        assertEquals(3, report.notSent().size());
        assertTrue(report.stopReason().orElseThrow().startsWith("Cancelled"));
    }

    @Test
    void undoDeletesOnlyTheUsersOfTheBatch() throws Exception {
        router.addExistingUser("keep-me");
        GenerationSettings settings = settings(CodeFormat.LETTERS_AND_DIGITS, 3);
        UploadReport report = upload(generator.generate(settings), settings);

        RouterUploadService.UndoResult undo = service.undo(client, report);

        assertEquals(3, undo.deleted());
        assertTrue(undo.problems().isEmpty());
        assertEquals(List.of("keep-me"), router.users().values().stream().map(FakeRouter.StoredUser::name).toList());
    }

    /** Regression: undo used to stop at a user that had been removed in WinBox meanwhile. */
    @Test
    void undoTreatsUsersRemovedMeanwhileAsDone() throws Exception {
        GenerationSettings settings = settings(CodeFormat.LETTERS_AND_DIGITS, 3);
        UploadReport report = upload(generator.generate(settings), settings);
        client.deleteHotspotUser(report.created().getFirst().routerId());

        RouterUploadService.UndoResult undo = service.undo(client, report);

        assertEquals(3, undo.deleted());
        assertTrue(undo.problems().isEmpty());
        assertTrue(router.users().isEmpty());
    }

    @Test
    void undoStopsOnConnectionProblems() throws Exception {
        GenerationSettings settings = settings(CodeFormat.LETTERS_AND_DIGITS, 3);
        UploadReport report = upload(generator.generate(settings), settings);
        router.route("/rest/ip/hotspot/user/" + report.created().getFirst().routerId(), 401, "");

        RouterUploadService.UndoResult undo = service.undo(client, report);

        assertEquals(0, undo.deleted());
        assertEquals(1, undo.problems().size());
    }

    @Test
    void nothingIsCreatedWhenExistingUsersCannotBeRead() {
        router.route("/rest/ip/hotspot/user", 403, "{\"error\":403,\"message\":\"Forbidden\"}");
        GenerationSettings settings = settings(CodeFormat.DIGITS, 2);

        assertThrows(RouterException.class, () -> upload(generator.generate(settings), settings));
        assertTrue(router.users().isEmpty());
    }

    @Test
    void routerIdsAreValidatedBeforeUseInUrls() {
        assertThrows(IllegalArgumentException.class, () -> client.deleteHotspotUser("../../system/reboot"));
    }

    @Test
    void replacementUsersGetNewNumbersAndKeepSettings() {
        GenerationSettings settings = settings(CodeFormat.LETTERS_AND_DIGITS, 1);
        HotspotUser replacement = generator.replacement(settings, java.util.Set.of()).orElseThrow();

        assertEquals("2M", replacement.profile());
        assertNotEquals("", replacement.username());
        String only = stuckGenerator().example(settings);
        assertTrue(stuckGenerator().replacement(settings, java.util.Set.of(only)).isEmpty());
    }
}
