package org.netpilot.model;

/**
 * How hotspot usernames are generated.
 */
public enum UsernameMode {

    /** Random letters and digits of a fixed length. */
    RANDOM_ALPHANUMERIC("Random (letters + digits)", true, false),

    /** Random digits of a fixed length. */
    RANDOM_NUMERIC("Random (digits only)", true, false),

    /** {@code user0001}, {@code user0002}, ... starting from the sequence start. */
    SEQUENTIAL("Sequential (user0001)", false, true),

    /** Profile name followed by a sequence number, e.g. {@code 2M0001}. */
    PROFILE_BASED("Profile based (2M0001)", false, true);

    private final String label;
    private final boolean usesLength;
    private final boolean usesSequence;

    UsernameMode(String label, boolean usesLength, boolean usesSequence) {
        this.label = label;
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
        return label;
    }
}
