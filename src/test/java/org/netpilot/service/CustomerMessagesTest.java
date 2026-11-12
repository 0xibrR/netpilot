package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.Customer;
import org.netpilot.model.NotificationSettings;
import org.netpilot.model.PhoneNumber;
import org.netpilot.model.RouterUser;
import org.netpilot.repository.CustomerRepository;

class CustomerMessagesTest {

    private static final String ROUTER = "10.0.0.1:80";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 18, 0);

    @TempDir
    Path dir;

    @Test
    void numbersAreReadAsPeopleTypeThem() {
        assertEquals(Optional.of("963944123456"), PhoneNumber.normalize("0944 123 456", "963"));
        assertEquals(Optional.of("963944123456"), PhoneNumber.normalize("+963 (944) 123-456", "963"));
        assertEquals(Optional.of("963944123456"), PhoneNumber.normalize("00963944123456", "963"));
        assertEquals(Optional.of("963944123456"), PhoneNumber.normalize("944123456", "963"), "without the 0");
        assertEquals(Optional.of("963944123456"), PhoneNumber.normalize("963944123456", "963"));
        assertEquals(Optional.of("963944123456"), PhoneNumber.normalize("٠٩٤٤١٢٣٤٥٦", "963"), "Arabic digits");
        assertEquals(Optional.of("201001234567"), PhoneNumber.normalize("+20 100 123 4567", "963"),
                "another country, typed with +");
        for (String bad : List.of("", "abc", "12", "0944-12x-456", "+0123456789", "1234567890123456")) {
            assertEquals(Optional.empty(), PhoneNumber.normalize(bad, "963"), bad);
        }
        assertEquals(Optional.empty(), PhoneNumber.normalize(null, "963"));
        assertEquals("+963 944 123 456", PhoneNumber.display("963944123456"));
        assertEquals("", PhoneNumber.display(""));
    }

    @Test
    void templatesFillInArabicOrEnglishFieldsAndDropEmptyLines() {
        CustomerMessages.Fields fields = new CustomerMessages.Fields("شبكة الأمل", "714133", "k9x2", "3M", "", "", "");

        String filled = CustomerMessages.fill("بطاقتك على {الشبكة}\nالمستخدم: {user}\nكلمة المرور: {كلمة_المرور}\n"
                + "الباقة: {package}\nللدخول: {الرابط}\n{unknown} stays", fields);

        assertEquals("بطاقتك على شبكة الأمل\nالمستخدم: 714133\nكلمة المرور: k9x2\nالباقة: 3M\n{unknown} stays",
                filled, "the link line is left out; a typo stays visible");
        assertEquals("a $1 b", CustomerMessages.fill("a {user} b", new CustomerMessages.Fields("", "$1", "", "", "", "",
                "")), "values are not read as regex replacements");
    }

    @Test
    void theBuiltInTextsAreUsedWhenTheTemplateIsEmpty() {
        CustomerMessages.Fields fields = new CustomerMessages.Fields("WiFi", "714133", "k9x2", "3M",
                "http://10.5.50.1/login?username=714133&password=k9x2", "2026-11-13 20:00", "1:00:00");
        NotificationSettings defaults = NotificationSettings.defaults();

        String card = CustomerMessages.card(defaults, fields);
        assertTrue(card.contains("714133") && card.contains("k9x2") && card.contains("username=714133"));
        assertTrue(card.lines().anyMatch(line -> line.equals(fields.link())),
                "the link stands alone on its line, so WhatsApp can make it tappable");
        String noLink = CustomerMessages.card(defaults, new CustomerMessages.Fields("WiFi", "714133", "k9x2", "3M",
                "", "", ""));
        assertFalse(noLink.strip().endsWith(":"), "without a link, its label line goes too: " + noLink);
        assertEquals("Name: 714133", CustomerMessages.fill("Name: {user}\nTap the link:\n{link}",
                new CustomerMessages.Fields("", "714133", "", "", "", "", "")));
        assertTrue(CustomerMessages.reminder(defaults, fields).contains("2026-11-13 20:00"));
        NotificationSettings own = new NotificationSettings("963", "Card {user}", "Bye {user}", 24);
        assertEquals("Card 714133", CustomerMessages.card(own, fields));
        assertEquals("Bye 714133", CustomerMessages.reminder(own, fields));
    }

    @Test
    void theWhatsAppLinkCarriesTheNumberAndTheWholeText() {
        String link = CustomerMessages.whatsAppLink("963944123456", "مرحباً 714133 & كلمة+المرور\nسطر");

        assertTrue(link.startsWith("https://wa.me/963944123456?text="));
        assertFalse(link.contains(" ") || link.contains("\n"));
        assertFalse(link.substring(link.indexOf('?')).contains("+"), "spaces are %20, never '+'");
        assertEquals("مرحباً 714133 & كلمة+المرور\nسطر",
                URLDecoder.decode(link.substring(link.indexOf("text=") + 5), StandardCharsets.UTF_8)
                        .replace(' ', ' '));
        assertThrows(IllegalArgumentException.class, () -> CustomerMessages.whatsAppLink("+963944", "x"));
    }

    @Test
    void theCodeListHasMostCountriesAndPalestineAsItsDefault() {
        java.util.Locale arabic = java.util.Locale.forLanguageTag("ar");
        List<org.netpilot.model.CallingCodes.Entry> all = org.netpilot.model.CallingCodes.all(arabic);

        assertTrue(all.size() > 60);
        assertEquals(List.of("970"), all.stream().filter(e -> e.region().equals("PS"))
                .map(org.netpilot.model.CallingCodes.Entry::code).toList(), "Palestine is +970");
        assertEquals("IL", org.netpilot.model.CallingCodes.forCode("972").orElseThrow().region(),
                "+972 is its own entry");
        assertTrue(all.stream().anyMatch(e -> e.code().equals("963"))
                && all.stream().anyMatch(e -> e.code().equals("962")));
        assertEquals("PS", org.netpilot.model.CallingCodes.forCode("970").orElseThrow().region());
        assertEquals(all.size(), all.stream().map(org.netpilot.model.CallingCodes.Entry::code).distinct().count(),
                "no calling code appears twice");
        java.util.Locale english = java.util.Locale.ENGLISH;
        assertEquals("United States / Canada  +1",
                org.netpilot.model.CallingCodes.forCode("1").orElseThrow().label(english), "+1 is shared");
        assertEquals("Russia / Kazakhstan  +7",
                org.netpilot.model.CallingCodes.forCode("7").orElseThrow().label(english), "+7 is shared");
        assertTrue(all.get(0).label(arabic).endsWith("+" + all.get(0).code()));
        assertEquals("970", NotificationSettings.defaults().countryCode(), "Home starts on Palestine");
        assertEquals(Optional.of("970599123456"), PhoneNumber.normalize("0599 123 456", "970"));
        assertEquals(Optional.of("972599123456"), PhoneNumber.normalize("+972 599 123 456", "970"),
                "a code not in the list can still be typed in full with +");
    }

    @Test
    void settingsCleanTheCountryCodeAndKeepHoursInRange() {
        assertEquals("963", new NotificationSettings("+963", "", "", 24).countryCode());
        assertEquals("970", new NotificationSettings("", "", "", 24).countryCode(), "empty: the default (Palestine)");
        assertEquals("20", new NotificationSettings(" 20 ", "", "", 24).countryCode());
        assertEquals(1, new NotificationSettings("963", "", "", 0).remindHours());
        assertEquals(NotificationSettings.MAX_HOURS, new NotificationSettings("963", "", "", 99_999).remindHours());
    }

    private static RouterUser card(String name, Duration used, String limit, long bytes, long limitBytes) {
        return new RouterUser("*" + name, name, "3M", "all", used, limit, bytes / 2, bytes - bytes / 2, false, "",
                limitBytes);
    }

    private static Customer customer(String name) {
        return new Customer(ROUTER, name, "963944123456", NOW.minusDays(1), Optional.empty());
    }

    @Test
    void remindersPickCardsThatEndSoonOrHaveLittleLeftOnlyOnce() {
        List<RouterUser> users = List.of(
                card("ends", Duration.ofHours(2), "", 1000, 0),
                card("later", Duration.ofHours(2), "", 1000, 0),
                card("time", Duration.ofMinutes(55), "1h", 1000, 0),
                card("data", Duration.ofMinutes(5), "", 950, 1000),
                card("plenty", Duration.ofMinutes(5), "10h", 100, 1000),
                card("over", Duration.ofHours(2), "1h", 1000, 0),
                card("unused", Duration.ZERO, "1h", 0, 0));
        Map<String, String> expiries = Map.of("ends", "2026-11-13 10:00:00", "later", "nov/20/2026 10:00:00");
        Map<String, Customer> customers = new java.util.LinkedHashMap<>();
        for (String name : List.of("ends", "later", "time", "data", "plenty", "over", "unused", "missing")) {
            customers.put(Customer.key(ROUTER, name), customer(name));
        }
        Customer reminded = customer("time").remindedAt(NOW);
        Map<String, Customer> withReminded = new java.util.LinkedHashMap<>(customers);
        withReminded.put(reminded.key(), reminded);

        List<Reminders.Due> due = Reminders.due(ROUTER, users, expiries, customers, NOW, 24);
        assertEquals(List.of("ends", "data", "time"), due.stream().map(d -> d.customer().card()).toList(),
                "soonest first, then by name; nothing for plenty, over, unused or missing cards");
        assertEquals(Reminders.Reason.ENDS_SOON, due.get(0).reason());
        assertEquals(Reminders.Reason.DATA_LOW, due.get(1).reason());
        assertEquals(Reminders.Reason.TIME_LOW, due.get(2).reason());
        assertEquals(Duration.ofMinutes(5), due.get(2).timeLeft().orElseThrow());

        assertEquals(List.of("ends", "data"),
                Reminders.due(ROUTER, users, expiries, withReminded, NOW, 24).stream().map(d -> d.customer().card())
                        .toList(),
                "a customer is reminded once");
        assertTrue(Reminders.due("other-router", users, expiries, customers, NOW, 24).isEmpty());
        assertEquals(Optional.empty(), Reminders.parse("someday"));
    }

    @Test
    void numbersAreKeptOnThisComputerAndCanBeRemoved() throws Exception {
        Path file = dir.resolve("customers.csv");
        CustomerRepository repository = new CustomerRepository(file);
        assertTrue(repository.all().isEmpty());

        repository.save(customer("714133"));
        repository.save(customer("714133").remindedAt(NOW));
        repository.save(new Customer(ROUTER, "a,b \"c\"", "963933000111", NOW, Optional.empty()));

        CustomerRepository again = new CustomerRepository(file);
        assertEquals(2, again.all().size());
        assertEquals(Optional.of(NOW), again.find(ROUTER, "714133").orElseThrow().reminded());
        assertEquals("963933000111", again.find(ROUTER, "a,b \"c\"").orElseThrow().phone(), "commas and quotes");
        again.remove(ROUTER, "714133");
        assertTrue(new CustomerRepository(file).find(ROUTER, "714133").isEmpty());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary file left");
        }
    }
}
