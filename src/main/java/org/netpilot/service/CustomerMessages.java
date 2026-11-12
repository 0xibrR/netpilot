package org.netpilot.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.netpilot.i18n.Messages;
import org.netpilot.model.NotificationSettings;

/**
 * The texts sent to customers and the WhatsApp link that opens a chat with the text written in.
 * Templates use fields in braces, in Arabic or English: {@code {الشبكة}} / {@code {network}},
 * {@code {المستخدم}} / {@code {user}}, {@code {كلمة_المرور}} / {@code {password}},
 * {@code {الباقة}} / {@code {package}}, {@code {الرابط}} / {@code {link}},
 * {@code {ينتهي}} / {@code {expires}}, {@code {المتبقي}} / {@code {left}}. A line whose field has
 * no value (no login link, say) is left out rather than sent half empty.
 */
public final class CustomerMessages {

    private static final Pattern FIELD = Pattern.compile("\\{([^{}\\s]+)}");

    /** Each field's names: English first, then Arabic. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("network", "network"), Map.entry("الشبكة", "network"),
            Map.entry("user", "user"), Map.entry("المستخدم", "user"),
            Map.entry("password", "password"), Map.entry("كلمة_المرور", "password"),
            Map.entry("package", "package"), Map.entry("الباقة", "package"),
            Map.entry("link", "link"), Map.entry("الرابط", "link"),
            Map.entry("expires", "expires"), Map.entry("ينتهي", "expires"),
            Map.entry("left", "left"), Map.entry("المتبقي", "left"));

    private CustomerMessages() {
    }

    /** The values of one message; blank values drop their line. */
    public record Fields(String network, String user, String password, String packageName, String link,
            String expires, String left) {

        Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("network", blank(network));
            m.put("user", blank(user));
            m.put("password", blank(password));
            m.put("package", blank(packageName));
            m.put("link", blank(link));
            m.put("expires", blank(expires));
            m.put("left", blank(left));
            return m;
        }

        private static String blank(String s) {
            return s == null ? "" : s.strip();
        }
    }

    /** The card's details, from the template in Settings or the built-in text. */
    public static String card(NotificationSettings settings, Fields fields) {
        return fill(
                settings.cardTemplate().isBlank() ? Messages.get("notify.template.card", "") : settings.cardTemplate(),
                fields);
    }

    /** The "ends soon" reminder, from the template in Settings or the built-in text. */
    public static String reminder(NotificationSettings settings, Fields fields) {
        return fill(settings.reminderTemplate().isBlank()
                ? Messages.get("notify.template.reminder", "")
                : settings.reminderTemplate(), fields);
    }

    /** Fills a template; unknown fields stay as written, so a typo shows instead of vanishing. */
    static String fill(String template, Fields fields) {
        Map<String, String> values = fields.asMap();
        List<String> out = new ArrayList<>();
        for (String line : template.replace("\r\n", "\n").split("\n", -1)) {
            Matcher m = FIELD.matcher(line);
            StringBuilder filled = new StringBuilder();
            boolean emptyField = false;
            while (m.find()) {
                String field = ALIASES.get(m.group(1));
                String value = field == null ? m.group() : values.get(field);
                if (field != null && value.isEmpty()) {
                    emptyField = true;
                }
                m.appendReplacement(filled, Matcher.quoteReplacement(value));
            }
            m.appendTail(filled);
            if (!emptyField) {
                out.add(filled.toString());
            } else if (!out.isEmpty() && out.getLast().strip().endsWith(":") && !FIELD.matcher(out.getLast()).find()) {
                out.removeLast(); // "tap the link:" without its link goes too
            }
        }
        return String.join("\n", out).strip();
    }

    /**
     * {@code https://wa.me/<number>?text=<message>}: opens WhatsApp (the app or WhatsApp Web) on the
     * customer's chat with the message written in; the user presses Send.
     *
     * @param phone international digits without {@code +}
     */
    public static String whatsAppLink(String phone, String message) {
        if (phone == null || !phone.matches("\\d{8,15}")) {
            throw new IllegalArgumentException("Not an international phone number: " + phone);
        }
        // URLEncoder writes spaces as '+', which WhatsApp would show; %20 is a space everywhere
        return "https://wa.me/" + phone + "?text="
                + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
