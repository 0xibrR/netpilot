package org.netpilot.model;

import org.netpilot.i18n.Messages;

/**
 * Colour theme of the user interface.
 */
public enum Theme {

    SYSTEM,
    LIGHT,
    SEMI_DARK,
    DARK;

    /**
     * @param systemDark whether the operating system currently uses a dark colour scheme
     * @return whether the dark stylesheet should be used
     */
    public boolean isDark(boolean systemDark) {
        return switch (this) {
            case SYSTEM -> systemDark;
            case LIGHT, SEMI_DARK -> false;
            case DARK -> true;
        };
    }

    /** Light content with the dark WinBox-style side menu, top bar and status bar. */
    public boolean darkChrome(boolean systemDark) {
        return this == SEMI_DARK || isDark(systemDark);
    }

    @Override
    public String toString() {
        return Messages.get("theme." + name());
    }
}
