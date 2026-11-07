package org.netpilot.model;

import java.util.Locale;

/**
 * How the application itself looks.
 *
 * @param language interface language; applied on the next start
 * @param theme    colour theme; applied at once
 */
public record UiPreferences(UiLanguage language, Theme theme) {

    public UiPreferences {
        language = language == null ? UiLanguage.forLocale(Locale.getDefault()) : language;
        theme = theme == null ? Theme.SYSTEM : theme;
    }

    /** The system language (Arabic or English) and the system theme. */
    public static UiPreferences defaults() {
        return new UiPreferences(null, null);
    }
}
