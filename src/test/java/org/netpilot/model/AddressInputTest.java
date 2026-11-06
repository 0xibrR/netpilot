package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class AddressInputTest {

    @Test
    void plainHostIsKept() {
        AddressInput input = AddressInput.parse(" 10.0.0.1 ");

        assertEquals(new AddressInput("10.0.0.1", Optional.empty(), OptionalInt.empty()), input);
        assertFalse(input.hadExtras(" 10.0.0.1 "));
    }

    /** Regression: pasting "http://10.0.0.1/" from the browser was rejected. */
    @Test
    void pastedBrowserUrlIsSplit() {
        AddressInput input = AddressInput.parse("http://10.0.0.1/");

        assertEquals(new AddressInput("10.0.0.1", Optional.of(false), OptionalInt.empty()), input);
        assertTrue(input.hadExtras("http://10.0.0.1/"));
    }

    @Test
    void schemePortAndPathAreRecognized() {
        assertEquals(new AddressInput("router.lan", Optional.of(true), OptionalInt.of(8443)),
                AddressInput.parse("HTTPS://router.lan:8443/webfig/#Interfaces"));
        assertEquals(new AddressInput("10.0.0.1", Optional.empty(), OptionalInt.of(8080)),
                AddressInput.parse("10.0.0.1:8080"));
        assertEquals(new AddressInput("[fe80::1]", Optional.of(true), OptionalInt.of(443)),
                AddressInput.parse("https://[fe80::1]:443/"));
    }

    @Test
    void bareIpv6IsLeftForValidation() {
        assertEquals(new AddressInput("fe80::1", Optional.empty(), OptionalInt.empty()), AddressInput.parse("fe80::1"));
        assertEquals("", AddressInput.parse(null).host());
    }
}
