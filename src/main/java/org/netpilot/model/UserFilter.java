package org.netpilot.model;

import java.util.Locale;
import java.util.Set;

/**
 * Which router users to show. All conditions must match; empty text/batch/profile means "any".
 *
 * @param text    matched case-insensitively against name and comment
 * @param batch   exact comment, e.g. {@code NetPilot 2026-11-06 21:56 #b062}
 * @param profile exact profile name
 * @param status  online / never used / expired / disabled / all
 */
public record UserFilter(String text, String batch, String profile, Status status) {

    public enum Status {

        ALL("All"),
        ONLINE("Online now"),
        NEVER_USED("Never used"),
        EXPIRED("Expired (time used up)"),
        DISABLED("Disabled");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final UserFilter ALL = new UserFilter("", "", "", Status.ALL);

    public UserFilter {
        text = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        batch = batch == null ? "" : batch;
        profile = profile == null ? "" : profile;
        status = status == null ? Status.ALL : status;
    }

    /**
     * @param onlineNames names of users with an active session
     */
    public boolean matches(RouterUser user, Set<String> onlineNames) {
        if (!text.isEmpty() && !user.name().toLowerCase(Locale.ROOT).contains(text)
                && !user.comment().toLowerCase(Locale.ROOT).contains(text)) {
            return false;
        }
        if (!batch.isEmpty() && !batch.equals(user.comment())) {
            return false;
        }
        if (!profile.isEmpty() && !profile.equals(user.profile())) {
            return false;
        }
        return switch (status) {
            case ALL -> true;
            case ONLINE -> onlineNames.contains(user.name());
            case NEVER_USED -> user.neverUsed();
            case EXPIRED -> user.expired();
            case DISABLED -> user.disabled();
        };
    }
}
