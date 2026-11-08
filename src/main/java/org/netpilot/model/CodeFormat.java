package org.netpilot.model;

import org.netpilot.i18n.Messages;

/**
 * Characters used for the random part of card numbers and passwords.
 */
public enum CodeFormat {

    /** 0-9: easiest to type on any phone keyboard. */
    DIGITS("0123456789"),
    /** Letters and digits without look-alikes (0/O/o, 1/l/I), so printed cards are easy to type. */
    LETTERS_AND_DIGITS("abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789");

    private final String alphabet;

    CodeFormat(String alphabet) {
        this.alphabet = alphabet;
    }

    public String alphabet() {
        return alphabet;
    }

    @Override
    public String toString() {
        return Messages.get("code.format." + name());
    }
}
