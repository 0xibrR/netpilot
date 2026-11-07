package org.netpilot.model;

import org.netpilot.i18n.Messages;

/**
 * How hotspot passwords are generated. Display names come from the {@code mode.password.*} texts.
 */
public enum PasswordMode {

    /** Random digits of a fixed length (easy to type on phones). */
    RANDOM_NUMERIC(true, false, false, false),

    /** Random letters and digits of a fixed length (strongest). */
    RANDOM_ALPHANUMERIC(true, false, false, false),

    /** Password equals the username. Convenient but weak. */
    SAME_AS_USERNAME(false, false, false, true),

    /** Prefix followed by the username. Weak. */
    PREFIX_USERNAME(false, false, true, true),

    /** Sequential numbers starting from the password start value. Predictable. */
    SEQUENTIAL(false, true, false, true);

    private final boolean usesLength;
    private final boolean usesSequence;
    private final boolean usesPrefix;
    private final boolean weak;

    PasswordMode(boolean usesLength, boolean usesSequence, boolean usesPrefix, boolean weak) {
        this.usesLength = usesLength;
        this.usesSequence = usesSequence;
        this.usesPrefix = usesPrefix;
        this.weak = weak;
    }

    public boolean usesLength() {
        return usesLength;
    }

    public boolean usesSequence() {
        return usesSequence;
    }

    public boolean usesPrefix() {
        return usesPrefix;
    }

    /** Whether passwords produced by this mode are easy to guess. */
    public boolean isWeak() {
        return weak;
    }

    @Override
    public String toString() {
        return Messages.get("mode.password." + name());
    }
}
