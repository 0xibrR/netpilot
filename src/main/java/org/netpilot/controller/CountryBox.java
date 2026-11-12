package org.netpilot.controller;

import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;
import org.netpilot.i18n.Messages;
import org.netpilot.model.CallingCodes;

/**
 * The calling-code list shown next to a phone number: country names in the app's language with
 * their code, so each customer's number gets the right code (in Palestine some use +970 and
 * others +972). Typing a few letters jumps to the country.
 */
final class CountryBox {

    private CountryBox() {
    }

    /** Fills {@code box} with the codes and selects {@code code} (the default from Settings). */
    static void setUp(ComboBox<CallingCodes.Entry> box, String code) {
        box.getItems().setAll(CallingCodes.all(Messages.locale()));
        box.setConverter(new StringConverter<>() {

            @Override
            public String toString(CallingCodes.Entry entry) {
                return entry == null ? "" : entry.label(Messages.locale());
            }

            @Override
            public CallingCodes.Entry fromString(String text) {
                return box.getValue();
            }
        });
        box.setVisibleRowCount(14);
        select(box, code);
        // a few typed letters jump to the first country whose name starts with them
        StringBuilder typed = new StringBuilder();
        long[] last = {0};
        box.setOnKeyTyped(e -> {
            long now = System.currentTimeMillis();
            if (now - last[0] > 1000) {
                typed.setLength(0);
            }
            last[0] = now;
            typed.append(e.getCharacter());
            String start = typed.toString().toLowerCase(Messages.locale());
            box.getItems().stream()
                    .filter(entry -> entry.label(Messages.locale()).toLowerCase(Messages.locale()).startsWith(start))
                    .findFirst()
                    .ifPresent(entry -> {
                        box.setValue(entry);
                        if (box.isShowing()) {
                            box.getSelectionModel().select(entry);
                        }
                    });
        });
    }

    /**
     * Fills {@code box} starting on the last code used, and remembers each new choice: the next
     * number field opens on it, with no setting to change (Palestine at first).
     */
    static void setUpRemembering(ComboBox<CallingCodes.Entry> box, Workspace workspace) {
        setUp(box, workspace.settings().notifications().countryCode());
        remember(workspace, box.getValue()); // an earlier code no longer listed is replaced by the one shown
        box.valueProperty().addListener((obs, old, entry) -> remember(workspace, entry));
    }

    private static void remember(Workspace workspace, CallingCodes.Entry entry) {
        if (entry != null && !entry.code().equals(workspace.settings().notifications().countryCode())) {
            workspace.updateSettings(s -> s.withNotifications(new org.netpilot.model.NotificationSettings(
                    entry.code(), s.notifications().cardTemplate(), s.notifications().reminderTemplate(),
                    s.notifications().remindHours())));
        }
    }

    /** Selects the entry with this code (the first, when two countries share it). */
    static void select(ComboBox<CallingCodes.Entry> box, String code) {
        // a code no longer in the list (an earlier choice) falls back to Palestine
        box.getItems().stream().filter(entry -> entry.code().equals(code)).findFirst()
                .or(() -> box.getItems().stream()
                        .filter(entry -> entry.code().equals(org.netpilot.model.NotificationSettings.DEFAULT_COUNTRY))
                        .findFirst())
                .ifPresent(box::setValue);
    }

    /** The chosen code, or the default when nothing is chosen. */
    static String code(ComboBox<CallingCodes.Entry> box, String fallback) {
        return box.getValue() == null ? fallback : box.getValue().code();
    }
}
