package org.netpilot.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * All text the user sees, in the chosen language (English or Arabic).
 *
 * <p>Texts live in {@code org/netpilot/i18n/messages_en.properties} (English) and
 * {@code messages_ar.properties} (Arabic). The language is chosen once at startup (changing it
 * needs a restart), so a single static bundle is enough and every layer, including services,
 * can use it without depending on JavaFX.
 *
 * <p>Pattern rules: {@code {0}}, {@code {1}} are arguments. In texts that have arguments, a
 * literal apostrophe must be written twice ({@code ''}), as {@link MessageFormat} requires.
 * Numbers are inserted as plain digits, so they look the same in both languages.
 */
public final class Messages {

    private static final System.Logger LOG = System.getLogger(Messages.class.getName());
    private static final String BUNDLE = "org.netpilot.i18n.messages";

    /** English unless the app chooses otherwise, so tests and logs are predictable. */
    private static volatile ResourceBundle bundle = load(Locale.ENGLISH);

    private Messages() {
    }

    /** Switches the language; call once at startup, before any window is built. */
    public static void use(Locale locale) {
        bundle = load(locale);
    }

    public static Locale locale() {
        return bundle.getLocale();
    }

    public static boolean isArabic() {
        return "ar".equals(bundle.getLocale().getLanguage());
    }

    /** For FXMLLoader, so FXML can use {@code %key}. */
    public static ResourceBundle bundle() {
        return bundle;
    }

    /**
     * @return the text for {@code key}, with arguments filled in; a visible {@code !key!} if the
     *         key is missing, so a forgotten translation shows up instead of crashing
     */
    public static String get(String key, Object... args) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            LOG.log(System.Logger.Level.WARNING, "Missing text: " + key);
            return "!" + key + "!";
        }
        if (args.length == 0) {
            return pattern;
        }
        Object[] plain = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            plain[i] = args[i] instanceof Number ? String.valueOf(args[i]) : args[i];
        }
        return new MessageFormat(pattern, bundle.getLocale()).format(plain);
    }

    /**
     * Each language has its own complete file ({@code messages_en}, {@code messages_ar}), so the
     * requested one is always found directly and Java never falls back to the Windows language.
     * (A custom ResourceBundle.Control would avoid that too, but named modules do not allow it.)
     */
    private static ResourceBundle load(Locale locale) {
        Locale language = "ar".equals(locale.getLanguage()) ? Locale.of("ar") : Locale.ENGLISH;
        return ResourceBundle.getBundle(BUNDLE, language);
    }
}
