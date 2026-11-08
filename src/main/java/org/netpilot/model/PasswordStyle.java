package org.netpilot.model;

/**
 * What the password of each card is.
 */
public enum PasswordStyle {
    /** Empty: the customer types the card number only. */
    NONE,
    /** Its own random password of {@link GenerationSettings#passwordLength()} characters. */
    RANDOM,
    /** The password is the card number again. */
    SAME_AS_CODE,
    /** One password for the whole batch, chosen by the user ({@link GenerationSettings#sharedPassword()}). */
    SHARED
}
