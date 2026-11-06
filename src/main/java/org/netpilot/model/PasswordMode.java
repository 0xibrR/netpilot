package org.netpilot.model;

/**
 * How hotspot passwords are generated.
 */
public enum PasswordMode {

    /** Random digits of a fixed length (easy to type on phones). */
    RANDOM_NUMERIC("Random (digits only)", true, false, false, false),

    /** Random letters and digits of a fixed length (strongest). */
    RANDOM_ALPHANUMERIC("Random (letters + digits)", true, false, false, false),

    /** Password equals the username. Convenient but weak. */
    SAME_AS_USERNAME("Same as username", false, false, false, true),

    /** Prefix followed by the username. Weak. */
    PREFIX_USERNAME("Prefix + username", false, false, true, true),

    /** Sequential numbers starting from the password start value. Predictable. */
    SEQUENTIAL("Sequential numbers", false, true, false, true);

    private final String label;
    private final boolean usesLength;
    private final boolean usesSequence;
    private final boolean usesPrefix;
    private final boolean weak;

    PasswordMode(String label, boolean usesLength, boolean usesSequence, boolean usesPrefix, boolean weak) {
        this.label = label;
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
        return label;
    }
}
