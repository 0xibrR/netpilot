package org.netpilot.model;

import org.netpilot.i18n.Messages;

/**
 * Colour theme of the user interface.
 */
public enum Theme {

    SYSTEM,
    LIGHT,
    DARK;

    /**
     * @param systemDark whether the operating system currently uses a dark colour scheme
     * @return whether the dark stylesheet should be used
     */
    public boolean isDark(boolean systemDark) {
        return switch (this) {
            case SYSTEM -> systemDark;
            case LIGHT -> false;
            case DARK -> true;
        };
    }

    @Override
    public String toString() {
        return Messages.get("theme." + name());
    }
}
