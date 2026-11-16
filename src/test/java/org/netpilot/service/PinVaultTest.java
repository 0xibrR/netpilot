package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.Role;
import org.netpilot.model.Role.Permission;

class PinVaultTest {

    @TempDir
    Path dir;

    /** A clock the test moves forward by hand. */
    private static final class TestClock extends Clock {

        Instant now = Instant.parse("2026-11-16T20:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void pass(Duration d) {
            now = now.plus(d);
        }
    }

    private final TestClock clock = new TestClock();

    private PinVault vault() {
        return new PinVault(dir.resolve("security.properties"), clock);
    }

    private static char[] pin(String digits) {
        return digits.toCharArray();
    }

    @Test
    void protectionStoresFingerprintsOnlyAndOpensTheRightRole() throws Exception {
        PinVault vault = vault();
        assertFalse(vault.enabled());
        String code = vault.enable(pin("4321"), Optional.of(pin("1111")), 7);

        assertTrue(vault.enabled());
        assertTrue(vault.hasCashierPin());
        assertEquals(7, vault.idleMinutes());
        assertEquals(12, code.length());
        assertTrue(code.matches("[A-HJ-KM-NP-Z2-9]{12}"), "no look-alike letters: " + code);
        assertEquals(code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8),
                PinVault.formatCode(code));

        String file = Files.readString(dir.resolve("security.properties"), StandardCharsets.UTF_8);
        assertFalse(file.contains("4321") || file.contains("1111") || file.contains(code), "never a PIN or code");

        PinVault again = vault(); // as after a restart
        assertEquals(new PinVault.Opened(Role.OWNER), again.check(pin("4321")));
        assertEquals(new PinVault.Opened(Role.CASHIER), again.check(pin("1111")));
        assertEquals(new PinVault.Wrong(PinVault.FREE_TRIES - 1), again.check(pin("9999")));
    }

    @Test
    void pinsAreFourToEightDigitsAndTheOwnerDiffersFromTheCashier() throws Exception {
        PinVault vault = vault();
        assertEquals("pin-length",
                assertThrows(IllegalArgumentException.class, () -> vault.enable(pin("123"), Optional.empty(), 10))
                        .getMessage());
        assertEquals("pin-length", assertThrows(IllegalArgumentException.class,
                () -> vault.enable(pin("123456789"), Optional.empty(), 10)).getMessage());
        assertEquals("pin-digits",
                assertThrows(IllegalArgumentException.class, () -> vault.enable(pin("12a4"), Optional.empty(), 10))
                        .getMessage());
        assertEquals("same-as-cashier", assertThrows(IllegalArgumentException.class,
                () -> vault.enable(pin("2580"), Optional.of(pin("2580")), 10)).getMessage());
        assertFalse(vault.enabled(), "nothing saved after a refusal");

        vault.enable(pin("2580"), Optional.of(pin("1470")), 10);
        assertThrows(IllegalStateException.class, () -> vault.enable(pin("3690"), Optional.empty(), 10));
        assertEquals("same-as-cashier", assertThrows(IllegalArgumentException.class,
                () -> vault.changeOwner(pin("2580"), pin("1470"))).getMessage());
        assertEquals("same-as-cashier", assertThrows(IllegalArgumentException.class,
                () -> vault.setCashier(pin("2580"), Optional.of(pin("2580")))).getMessage());
    }

    @Test
    void wrongTriesWaitLongerEachTimeAtMostFifteenMinutesAndSurviveARestart() throws Exception {
        vault().enable(pin("4321"), Optional.empty(), 10);
        for (int i = 1; i < PinVault.FREE_TRIES; i++) {
            assertEquals(new PinVault.Wrong(PinVault.FREE_TRIES - i), vault().check(pin("0000")));
        }
        PinVault.Result fifth = vault().check(pin("0000"));
        assertEquals(new PinVault.Waiting(clock.now.plusSeconds(30)), fifth);

        // even the right PIN is not checked while waiting, and a restart (new vault) does not reset it
        assertInstanceOf(PinVault.Waiting.class, vault().check(pin("4321")));
        assertTrue(vault().waitingUntil().isPresent());

        clock.pass(Duration.ofSeconds(31));
        assertEquals(new PinVault.Waiting(clock.now.plusSeconds(60)), vault().check(pin("0000")), "doubled");
        clock.pass(Duration.ofSeconds(61));
        assertEquals(new PinVault.Opened(Role.OWNER), vault().check(pin("4321")), "the right PIN after the wait");
        assertEquals(new PinVault.Wrong(PinVault.FREE_TRIES - 1), vault().check(pin("0000")), "counting restarts");

        assertEquals(Duration.ZERO, PinVault.waitAfter(4));
        assertEquals(Duration.ofSeconds(30), PinVault.waitAfter(5));
        assertEquals(Duration.ofMinutes(8), PinVault.waitAfter(9));
        assertEquals(PinVault.MAX_WAIT, PinVault.waitAfter(10));
        assertEquals(PinVault.MAX_WAIT, PinVault.waitAfter(500), "never more than 15 minutes");
    }

    @Test
    void changesAndTurningOffAskForTheOwnerPinAgain() throws Exception {
        PinVault vault = vault();
        vault.enable(pin("4321"), Optional.of(pin("1111")), 10);

        assertEquals("wrong-owner-pin",
                assertThrows(SecurityException.class, () -> vault.changeOwner(pin("1111"), pin("5555"))).getMessage(),
                "the cashier PIN is not enough");
        assertThrows(SecurityException.class, () -> vault.setCashier(pin("0000"), Optional.empty()));
        assertThrows(SecurityException.class, () -> vault.disable(pin("1111")));
        assertTrue(vault.enabled());

        vault.changeOwner(pin("4321"), pin("5555"));
        assertEquals(new PinVault.Opened(Role.OWNER), vault.check(pin("5555")));
        assertInstanceOf(PinVault.Wrong.class, vault.check(pin("4321")), "the old owner PIN no longer works");

        vault.setCashier(pin("5555"), Optional.empty());
        assertFalse(vault.hasCashierPin());
        vault.setCashier(pin("5555"), Optional.of(pin("2222")));
        assertEquals(new PinVault.Opened(Role.CASHIER), vault.check(pin("2222")));

        vault.setIdleMinutes(999);
        assertEquals(240, vault.idleMinutes());

        vault.disable(pin("5555"));
        assertFalse(vault.enabled());
        assertFalse(Files.exists(dir.resolve("security.properties")));
    }

    @Test
    void theRecoveryCodeWorksOnceAndHasItsOwnWrongTryLimit() throws Exception {
        PinVault vault = vault();
        String code = vault.enable(pin("4321"), Optional.of(pin("1111")), 10);

        for (int i = 0; i < PinVault.FREE_TRIES; i++) {
            assertEquals("wrong-recovery-code",
                    assertThrows(SecurityException.class, () -> vault.recover("AAAA-AAAA-AAAA", pin("6666")))
                            .getMessage());
        }
        assertTrue(assertThrows(SecurityException.class, () -> vault.recover(code, pin("6666"))).getMessage()
                .startsWith("wait:"), "the right code must wait too");
        assertEquals(new PinVault.Opened(Role.OWNER), vault.check(pin("4321")),
                "wrong codes do not lock out the PINs");

        clock.pass(Duration.ofSeconds(31));
        assertThrows(IllegalArgumentException.class, () -> vault.recover(code, pin("1111")), "not the cashier's");
        String next = vault.recover(code.toLowerCase(java.util.Locale.ROOT).substring(0, 4) + " "
                + code.substring(4), pin("6666"));
        assertEquals(new PinVault.Opened(Role.OWNER), vault.check(pin("6666")));
        assertTrue(!next.equals(code));

        assertThrows(SecurityException.class, () -> vault.recover(code, pin("7777")), "used once");
        vault.recover(next, pin("7777"));
        assertEquals(new PinVault.Opened(Role.OWNER), vault.check(pin("7777")));
    }

    @Test
    void anUnreadableFileMeansNoProtectionAndBadFingerprintsNeverMatch() throws Exception {
        Files.writeString(dir.resolve("security.properties"), "owner=\\u12", StandardCharsets.ISO_8859_1);
        assertFalse(vault().enabled());
        assertFalse(PinVault.matches(pin("1234"), "v1$x$y"));
        assertFalse(PinVault.matches(pin("1234"), "v1$1000$%%%$%%%"));
        assertFalse(PinVault.matches(pin(""), vault().hash(pin("1234"))));
        assertTrue(PinVault.matches(pin("1234"), vault().hash(pin("1234"))));
    }

    @Test
    void theCashierMayOnlyDoCounterWorkAndTheRoleFollowsProtection() throws Exception {
        for (Permission p : Permission.values()) {
            assertTrue(Role.OWNER.can(p));
        }
        List<Permission> cashier = new ArrayList<>();
        for (Permission p : Permission.values()) {
            if (Role.CASHIER.can(p)) {
                cashier.add(p);
            }
        }
        assertEquals(List.of(Permission.QUICK_SALE, Permission.VIEW_USERS, Permission.MESSAGE_CUSTOMERS,
                Permission.RESELLER_PAYMENT), cashier);

        PinVault vault = vault();
        AccessControl off = new AccessControl(vault);
        assertEquals(Role.OWNER, off.role(), "no protection: the owner");
        off.setRole(Role.CASHIER);
        assertEquals(Role.OWNER, off.role(), "cannot be the cashier without protection");

        vault.enable(pin("4321"), Optional.empty(), 10);
        AccessControl on = new AccessControl(vault);
        assertEquals(Role.CASHIER, on.role(), "protection on: starts as the cashier");
        assertEquals("owner-only:REPORTS",
                assertThrows(SecurityException.class, () -> on.require(Permission.REPORTS)).getMessage());
        on.require(Permission.QUICK_SALE);

        List<Role> seen = new ArrayList<>();
        java.util.function.Consumer<Role> listener = seen::add;
        on.addListener(listener);
        on.setRole(Role.OWNER);
        on.setRole(Role.OWNER);
        assertEquals(List.of(Role.OWNER), seen, "told once per change");
        assertTrue(on.can(Permission.SETTINGS));

        vault.disable(pin("4321"));
        on.protectionChanged();
        on.protectionChanged();
        assertEquals(List.of(Role.OWNER, Role.OWNER, Role.OWNER), seen, "told when protection changes");
        on.removeListener(listener);
        on.setRole(Role.CASHIER);
        assertEquals(3, seen.size());
    }
}
