package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VoucherTemplateTest {

    @Test
    void defaultsAreValidAndEnglish() {
        VoucherTemplate d = VoucherTemplate.defaults();
        assertTrue(d.validate().isEmpty());
        assertEquals(VoucherTemplate.Language.ENGLISH, d.language());
        assertEquals("User", d.language().username());
        assertEquals("اسم المستخدم", VoucherTemplate.Language.ARABIC.username());
    }

    @Test
    void pricesAreCleanedAndLookedUpByProfile() {
        Map<String, String> raw = new HashMap<>();
        raw.put(" 2M ", " 1000 ل.س ");
        raw.put("1M", "");
        raw.put("5M", "two\nlines");
        VoucherTemplate t = new VoucherTemplate("x", "", null, "#123456", raw, true, true, true, true, null);

        assertEquals(Optional.of("1000 ل.س"), t.priceFor("2M"));
        assertEquals(Optional.empty(), t.priceFor("1M"), "blank prices are dropped");
        assertEquals(Optional.of("two lines"), t.priceFor("5M"), "line breaks would break the layout");
        raw.put("9M", "late");
        assertEquals(Optional.empty(), t.priceFor("9M"), "the template keeps its own copy");
    }

    @Test
    void invalidColorFallsBackAndTooLongTextIsReported() {
        VoucherTemplate t = new VoucherTemplate("n".repeat(41), "x".repeat(91), null, "red",
                Map.of("2M", "1".repeat(25)), true, true, true, true, null);

        assertEquals("#1F5FA8", t.accentColor());
        assertEquals(3, t.validate().size());
    }
}
