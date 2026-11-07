package org.netpilot.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.netpilot.model.PasswordMode;
import org.netpilot.model.ReportPeriod;
import org.netpilot.model.Theme;
import org.netpilot.model.UiLanguage;
import org.netpilot.model.UserFilter;
import org.netpilot.model.UsernameMode;

/**
 * Keeps the two languages complete: every text used by the code or the views exists in English
 * and Arabic, with the same placeholders.
 */
class MessagesTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path VIEWS = Path.of("src/main/resources/org/netpilot/view");
    /** View attributes that are not translatable text (names, symbols, sample voucher values). */
    private static final Set<String> NOT_TEXT = Set.of("NetPilot", "…", "#", "●", "—", "QR", "714133", "5821", "");

    @AfterEach
    void backToEnglish() {
        Messages.use(Locale.ENGLISH);
    }

    @Test
    void bothLanguagesHaveTheSameKeys() throws IOException {
        assertEquals(load("en").stringPropertyNames(), load("ar").stringPropertyNames());
    }

    @Test
    void translationsKeepThePlaceholders() throws IOException {
        Properties en = load("en");
        Properties ar = load("ar");
        for (String key : en.stringPropertyNames()) {
            assertEquals(placeholders(en.getProperty(key)), placeholders(ar.getProperty(key)), key);
        }
    }

    @Test
    void everyKeyUsedInTheCodeExists() throws IOException {
        Set<String> keys = load("en").stringPropertyNames();
        // a key ending in "." is a prefix completed with an enum name; enumLabelsExistForEveryConstant covers those
        Pattern literal = Pattern.compile("Messages\\.get\\(\\s*\"([a-zA-Z.]*[a-zA-Z])\"");
        Pattern choice = Pattern.compile("\\? \"([a-z]+\\.[a-zA-Z.]+)\" : \"([a-z]+\\.[a-zA-Z.]+)\"");
        List<String> missing = new ArrayList<>();
        for (String source : read(SOURCES, ".java")) {
            collect(literal.matcher(source), keys, missing);
            collect(choice.matcher(source), keys, missing);
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everyKeyUsedInTheViewsExists() throws IOException {
        Set<String> keys = load("en").stringPropertyNames();
        Pattern reference = Pattern.compile("\"%([a-zA-Z.]+)\"");
        List<String> missing = new ArrayList<>();
        for (String view : read(VIEWS, ".fxml")) {
            collect(reference.matcher(view), keys, missing);
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void viewsHaveNoUntranslatedText() throws IOException {
        Pattern attribute = Pattern.compile("\\b(?:text|promptText|title)=\"([^\"]*)\"");
        Set<String> untranslated = new TreeSet<>();
        for (String view : read(VIEWS, ".fxml")) {
            Matcher m = attribute.matcher(view);
            while (m.find()) {
                String value = m.group(1);
                if (!value.startsWith("%") && !NOT_TEXT.contains(value) && !value.matches("\\d+")) {
                    untranslated.add(value);
                }
            }
        }
        assertEquals(Set.of(), untranslated);
    }

    @Test
    void enumLabelsExistForEveryConstant() {
        List<Enum<?>[]> enums = List.of(UsernameMode.values(), PasswordMode.values(), ReportPeriod.values(),
                UserFilter.Status.values(), Theme.values(), UiLanguage.values());
        for (Enum<?>[] values : enums) {
            for (Enum<?> value : values) {
                assertFalse(value.toString().startsWith("!"), value.name());
            }
        }
    }

    @Test
    void formatsArgumentsInTheChosenLanguage() {
        assertEquals("Exported 3 users to out", Messages.get("main.status.exported", 3, "out"));
        assertEquals("Profile '2M' does not exist on the router. Choose one from the list.",
                Messages.get("main.status.profileMissing", "2M"));
        assertEquals("12345 users", Messages.get("main.count.users", 12345), "numbers are not grouped");
        assertFalse(Messages.isArabic());

        Messages.use(Locale.of("ar", "SY"));
        assertTrue(Messages.isArabic());
        assertEquals("حفظ", Messages.get("button.save"));
        assertEquals("12345 مستخدم", Messages.get("main.count.users", 12345));
    }

    @Test
    void anyOtherLanguageFallsBackToEnglish() {
        Messages.use(Locale.FRENCH);
        assertEquals("Save", Messages.get("button.save"));
        assertFalse(Messages.isArabic());
    }

    @Test
    void missingKeysAreVisibleButDoNotFail() {
        assertEquals("!no.such.key!", Messages.get("no.such.key"));
    }

    private static void collect(Matcher m, Set<String> keys, List<String> missing) {
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (!keys.contains(m.group(g))) {
                    missing.add(m.group(g));
                }
            }
        }
    }

    /** {@code {0}}, {@code {1}}... used by a pattern; also checks that it parses. */
    private static Set<Integer> placeholders(String pattern) {
        Set<Integer> used = new TreeSet<>();
        if (!pattern.contains("{")) {
            return used;
        }
        new MessageFormat(pattern);
        Matcher m = Pattern.compile("\\{(\\d+)}").matcher(pattern);
        while (m.find()) {
            used.add(Integer.parseInt(m.group(1)));
        }
        return used;
    }

    private static Properties load(String language) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream("messages_" + language + ".properties");
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return p;
    }

    private static List<String> read(Path dir, String extension) throws IOException {
        List<String> texts = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(extension)).toList()) {
                texts.add(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return texts;
    }
}
