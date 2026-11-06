package org.netpilot.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of sending one batch of users to a router.
 *
 * @param batchComment comment written on every created user, e.g. {@code NetPilot 2026-11-06 21:40 #a7f3}
 * @param created      users that now exist on the router, with their router ids
 * @param replaced     how many created users replaced a name that already existed on the router
 * @param skipped      users not created because the name already existed and could not be replaced
 * @param failed       users the router refused, with the reason
 * @param notSent      users never attempted because the upload stopped early
 * @param stopReason   why the upload stopped early (cancelled, lost connection, ...), if it did
 */
public record UploadReport(
        String batchComment,
        List<CreatedUser> created,
        int replaced,
        List<HotspotUser> skipped,
        List<Failure> failed,
        List<HotspotUser> notSent,
        Optional<String> stopReason) {

    /** A user that was created on the router. */
    public record CreatedUser(String routerId, HotspotUser user) {

        public CreatedUser {
            Objects.requireNonNull(routerId, "routerId");
            Objects.requireNonNull(user, "user");
        }
    }

    /** A user the router refused. The reason never contains the user's password. */
    public record Failure(HotspotUser user, String reason) {

        public Failure {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public UploadReport {
        Objects.requireNonNull(batchComment, "batchComment");
        created = List.copyOf(created);
        skipped = List.copyOf(skipped);
        failed = List.copyOf(failed);
        notSent = List.copyOf(notSent);
        stopReason = stopReason == null ? Optional.empty() : stopReason;
    }

    public List<HotspotUser> createdUsers() {
        return created.stream().map(CreatedUser::user).toList();
    }

    public boolean completeSuccess() {
        return skipped.isEmpty() && failed.isEmpty() && notSent.isEmpty() && stopReason.isEmpty();
    }
}
