package org.netpilot.model;

import java.util.Locale;
import org.netpilot.i18n.Messages;

/**
 * Language of the user interface. Voucher labels have their own setting in {@link VoucherTemplate}.
 */
public enum UiLanguage {

    ENGLISH(Locale.ENGLISH),
    ARABIC(Locale.of("ar"));

    private final Locale locale;

    UiLanguage(Locale locale) {
        this.locale = locale;
    }

    public Locale locale() {
        return locale;
    }

    /** Arabic for an Arabic system locale, English for anything else. */
    public static UiLanguage forLocale(Locale systemLocale) {
        return "ar".equals(systemLocale.getLanguage()) ? ARABIC : ENGLISH;
    }

    /** Each language is shown in its own script, so a user can always find theirs. */
    @Override
    public String toString() {
        return Messages.get("language." + name());
    }
}
