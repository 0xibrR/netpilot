package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReceiptSettingsTest {

    @Test
    void onlyTheTwoRollWidthsExistAndEachHasItsPrintableWidth() {
        ReceiptSettings narrow = new ReceiptSettings(" XP-58 ", 58);
        ReceiptSettings odd = new ReceiptSettings(null, 75);

        assertEquals("XP-58", narrow.printer());
        assertEquals(48, narrow.printableMm());
        assertEquals(384, narrow.dots(), "48 mm at 8 dots/mm");
        assertEquals("", odd.printer(), "empty = the Windows default printer");
        assertEquals(ReceiptSettings.WIDE, odd.paperMm(), "anything else falls back to 80 mm");
        assertEquals(576, ReceiptSettings.defaults().dots());
    }

    @Test
    void settingsWithoutReceiptPartGetTheDefault() {
        AppSettings old = new AppSettings(GenerationSettings.defaults(), "", 4, java.nio.file.Path.of("out"), null,
                null, null);

        assertEquals(ReceiptSettings.defaults(), old.receipt());
        assertEquals(new ReceiptSettings("P", 58), old.withReceipt(new ReceiptSettings("P", 58)).receipt());
        assertEquals(old.receipt(), old.withUi(UiPreferences.defaults()).receipt(), "other changes keep it");
    }
}
