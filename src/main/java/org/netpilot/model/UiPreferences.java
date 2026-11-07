package org.netpilot.model;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How the application itself looks.
 *
 * @param language interface language; applied on the next start
 * @param theme    colour theme; applied at once
 * @param accent   main colour of buttons and selections, {@code #RRGGBB}; applied at once
 */
public record UiPreferences(UiLanguage language, Theme theme, String accent) {

    /** WinBox blue. */
    public static final String DEFAULT_ACCENT = "#2F7BD8";
    /** Colours offered in Settings. */
    public static final List<String> ACCENTS = List.of(DEFAULT_ACCENT, "#7367F0", "#1E9E6A", "#E5484D", "#F08C00",
            "#0FA3B1", "#6B7280");

    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    public UiPreferences {
        language = language == null ? UiLanguage.forLocale(Locale.getDefault()) : language;
        theme = theme == null ? Theme.SYSTEM : theme;
        accent = accent != null && HEX.matcher(accent.strip()).matches()
                ? accent.strip().toUpperCase(Locale.ROOT)
                : DEFAULT_ACCENT;
    }

    public UiPreferences(UiLanguage language, Theme theme) {
        this(language, theme, null);
    }

    /** The system language (Arabic or English), the system theme and WinBox blue. */
    public static UiPreferences defaults() {
        return new UiPreferences(null, null, null);
    }
}
