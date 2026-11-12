package org.netpilot.model;

/**
 * Messages to customers (sent by WhatsApp with one click).
 *
 * @param countryCode      the country's calling code without {@code +}, e.g. {@code 963}; local numbers
 *                         ({@code 0944…}) get it in front
 * @param cardTemplate     the text that sends a card's details; blank = the built-in text in the app's language
 * @param reminderTemplate the text that reminds a customer their card ends soon; blank = the built-in text
 * @param remindHours      remind when a card's validity ends within this many hours
 */
public record NotificationSettings(String countryCode, String cardTemplate, String reminderTemplate,
        int remindHours) {

    public static final String DEFAULT_COUNTRY = "970";
    public static final int DEFAULT_HOURS = 24;
    public static final int MAX_HOURS = 24 * 14;

    public NotificationSettings {
        String digits = countryCode == null ? "" : countryCode.replaceAll("\\D", "");
        countryCode = digits.isEmpty() || digits.length() > 4 ? DEFAULT_COUNTRY : digits;
        cardTemplate = cardTemplate == null ? "" : cardTemplate.strip();
        reminderTemplate = reminderTemplate == null ? "" : reminderTemplate.strip();
        remindHours = Math.clamp(remindHours, 1, MAX_HOURS);
    }

    public static NotificationSettings defaults() {
        return new NotificationSettings(DEFAULT_COUNTRY, "", "", DEFAULT_HOURS);
    }
}
