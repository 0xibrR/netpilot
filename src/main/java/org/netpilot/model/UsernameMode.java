package org.netpilot.model;

import org.netpilot.i18n.Messages;

/**
 * How hotspot usernames are generated. Display names come from the {@code mode.username.*} texts.
 */
public enum UsernameMode {

    /** Random letters and digits of a fixed length. */
    RANDOM_ALPHANUMERIC(true, false),

    /** Random digits of a fixed length. */
    RANDOM_NUMERIC(true, false),

    /** {@code user0001}, {@code user0002}, ... starting from the sequence start. */
    SEQUENTIAL(false, true),

    /** Profile name followed by a sequence number, e.g. {@code 2M0001}. */
    PROFILE_BASED(false, true);

    private final boolean usesLength;
    private final boolean usesSequence;

    UsernameMode(boolean usesLength, boolean usesSequence) {
        this.usesLength = usesLength;
        this.usesSequence = usesSequence;
    }

    public boolean usesLength() {
        return usesLength;
    }

    public boolean usesSequence() {
        return usesSequence;
    }

    /** Random names can be swapped for another free name; sequential ones cannot. */
    public boolean isRandom() {
        return !usesSequence;
    }

    @Override
    public String toString() {
        return Messages.get("mode.username." + name());
    }
}
