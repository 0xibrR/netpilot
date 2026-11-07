package org.netpilot.service.router;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;
import org.netpilot.i18n.Messages;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.UploadReport;
import org.netpilot.service.UserGenerator;
import org.netpilot.service.router.RouterException.Kind;

/**
 * Creates a batch of generated users on a router, one request at a time.
 *
 * <p>Rules:
 * <ul>
 * <li>Names that already exist on the router are replaced by new random names when the
 * username mode is random; sequential names are skipped (they would break the sequence).</li>
 * <li>Every created user gets the same comment, so the batch can be undone or found later.</li>
 * <li>Connection-level problems stop the upload; a problem with one user does not, unless the
 * same thing keeps failing (then it is clearly not about that user).</li>
 * </ul>
 * Passwords are never logged or put in failure reasons.
 */
public final class RouterUploadService {

    private static final System.Logger LOG = System.getLogger(RouterUploadService.class.getName());

    /** Stop after this many users in a row failed for the same reason. */
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** Give up on one user after this many "name already exists" answers. */
    private static final int MAX_NAME_ATTEMPTS = 5;

    private static final DateTimeFormatter COMMENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Receives progress on the uploading thread. */
    @FunctionalInterface
    public interface ProgressListener {

        /**
         * @param done     users processed so far
         * @param total    users in the batch
         * @param username user about to be created, or {@code null} when finished
         */
        void onProgress(int done, int total, String username);
    }

    /** Outcome of {@link #undo}. */
    public record UndoResult(int deleted, List<String> problems) {

        public UndoResult {
            problems = List.copyOf(problems);
        }
    }

    private final UserGenerator generator;
    private final Clock clock;
    private final RandomGenerator random;

    public RouterUploadService(UserGenerator generator) {
        this(generator, Clock.systemDefaultZone(), new SecureRandom());
    }

    /** For tests: fixed time and random batch tag. */
    public RouterUploadService(UserGenerator generator, Clock clock, RandomGenerator random) {
        this.generator = Objects.requireNonNull(generator);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * @param settings  the settings the users were generated with (used for replacement names)
     * @param cancelled checked before each user; when it returns {@code true} the upload stops
     * @throws RouterException if the router's existing users cannot be read; nothing was changed then
     */
    public UploadReport upload(RouterClient client, List<HotspotUser> users, GenerationSettings settings,
            ProgressListener progress, BooleanSupplier cancelled) throws RouterException {
        Batch batch = new Batch(client, settings, batchComment(), new HashSet<>(client.hotspotUsernames()), users);

        for (int i = 0; i < users.size(); i++) {
            if (cancelled.getAsBoolean()) {
                batch.stop(Messages.get("upload.cancelled"), users.subList(i, users.size()));
                break;
            }
            HotspotUser user = users.get(i);
            progress.onProgress(i, users.size(), user.username());
            if (!batch.send(user)) {
                batch.notSent.addAll(users.subList(i + 1, users.size()));
                break;
            }
        }
        progress.onProgress(users.size(), users.size(), null);

        UploadReport report = batch.report();
        LOG.log(System.Logger.Level.INFO,
                "Upload {0}: {1} created ({2} replaced), {3} skipped, {4} failed, {5} not sent",
                report.batchComment(), report.created().size(), report.replaced(), report.skipped().size(),
                report.failed().size(), report.notSent().size());
        return report;
    }

    /**
     * Deletes every user the batch created. Continues past single failures; stops on
     * connection problems.
     */
    public UndoResult undo(RouterClient client, UploadReport report) {
        int deleted = 0;
        List<String> problems = new ArrayList<>();
        for (UploadReport.CreatedUser created : report.created()) {
            try {
                client.deleteHotspotUser(created.routerId());
                deleted++;
            } catch (RouterException e) {
                problems.add(created.user().username() + ": " + e.getMessage());
                if (e.affectsEveryRequest()) {
                    break;
                }
            }
        }
        LOG.log(System.Logger.Level.INFO, "Undo {0}: {1} of {2} users deleted", report.batchComment(), deleted,
                report.created().size());
        return new UndoResult(deleted, problems);
    }

    private String batchComment() {
        return "NetPilot " + LocalDateTime.now(clock).format(COMMENT_TIME) + " #"
                + String.format("%04x", random.nextInt(0x10000));
    }

    /** State of one upload, kept together so each step stays small and readable. */
    private final class Batch {

        private final RouterClient client;
        private final GenerationSettings settings;
        private final String comment;
        /** Names that exist on the router. */
        private final Set<String> onRouter;
        /** Names a replacement must avoid: on the router or anywhere in this batch. */
        private final Set<String> reserved;

        private final List<UploadReport.CreatedUser> created = new ArrayList<>();
        private final List<HotspotUser> skipped = new ArrayList<>();
        private final List<UploadReport.Failure> failed = new ArrayList<>();
        private final List<HotspotUser> notSent = new ArrayList<>();
        private int replaced;
        private int nextReplacementIndex;
        private String lastFailure;
        private int consecutiveFailures;
        private String stopReason;

        Batch(RouterClient client, GenerationSettings settings, String comment, Set<String> onRouter,
                List<HotspotUser> users) {
            this.client = client;
            this.settings = settings;
            this.comment = comment;
            this.onRouter = onRouter;
            this.reserved = new HashSet<>(onRouter);
            users.forEach(u -> reserved.add(u.username()));
            this.nextReplacementIndex = Math.max(settings.count(), users.size());
        }

        /**
         * @return {@code false} if the upload must stop
         */
        boolean send(HotspotUser original) {
            HotspotUser candidate = original;
            for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
                if (onRouter.contains(candidate.username())) {
                    Optional<HotspotUser> replacement = generator.replacement(settings, nextReplacementIndex++,
                            reserved);
                    if (replacement.isEmpty()) {
                        skipped.add(original);
                        return true;
                    }
                    candidate = replacement.get();
                    reserved.add(candidate.username());
                }
                try {
                    String id = client.createHotspotUser(candidate, comment);
                    created.add(new UploadReport.CreatedUser(id, candidate));
                    onRouter.add(candidate.username());
                    if (candidate != original) {
                        replaced++;
                    }
                    consecutiveFailures = 0;
                    return true;
                } catch (RouterException e) {
                    if (e.kind() == Kind.ALREADY_EXISTS) {
                        onRouter.add(candidate.username()); // created by someone else meanwhile
                        continue;
                    }
                    return recordFailure(candidate, e);
                }
            }
            skipped.add(original);
            return true;
        }

        private boolean recordFailure(HotspotUser user, RouterException e) {
            failed.add(new UploadReport.Failure(user, e.getMessage()));
            if (e.affectsEveryRequest()) {
                stopReason = e.kind() == Kind.TIMEOUT
                        ? Messages.get("upload.timeoutMaybeCreated", e.getMessage())
                        : e.getMessage();
                return false;
            }
            consecutiveFailures = e.getMessage().equals(lastFailure) ? consecutiveFailures + 1 : 1;
            lastFailure = e.getMessage();
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                stopReason = Messages.get("upload.stoppedAfterFailures", MAX_CONSECUTIVE_FAILURES, e.getMessage());
                return false;
            }
            return true;
        }

        void stop(String reason, List<HotspotUser> remaining) {
            stopReason = reason;
            notSent.addAll(remaining);
        }

        UploadReport report() {
            return new UploadReport(comment, created, replaced, skipped, failed, notSent,
                    Optional.ofNullable(stopReason));
        }
    }
}
