package org.netpilot.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.RouterEndpoint;

class PasswordVaultTest {

    private static final RouterEndpoint HEX = new RouterEndpoint("10.0.0.1", 80, false, "admin");
    private static final RouterEndpoint OTHER_USER = new RouterEndpoint("10.0.0.1", 80, false, "netpilot");

    @TempDir
    Path dir;

    /** Reversible stand-in for DPAPI that fails on data it did not produce. */
    private static final class FakeProtector implements PasswordVault.Protector {

        private final boolean available;

        FakeProtector(boolean available) {
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public byte[] protect(byte[] plain) {
            byte[] out = new byte[plain.length + 1];
            out[0] = 42;
            for (int i = 0; i < plain.length; i++) {
                out[i + 1] = (byte) (plain[i] ^ 0x5A);
            }
            return out;
        }

        @Override
        public byte[] unprotect(byte[] encrypted) {
            if (encrypted.length == 0 || encrypted[0] != 42) {
                throw new IllegalStateException("not ours");
            }
            byte[] out = new byte[encrypted.length - 1];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (encrypted[i + 1] ^ 0x5A);
            }
            return out;
        }
    }

    @Test
    void remembersPerRouterAndUserWithoutStoringPlainText() throws Exception {
        Path file = dir.resolve("credentials");
        PasswordVault vault = new PasswordVault(file, new FakeProtector(true));
        char[] password = "S3cret-كلمة".toCharArray();

        vault.save(HEX, password);

        assertArrayEquals("S3cret-كلمة".toCharArray(), password, "the caller's array is left as it was");
        assertTrue(vault.has(HEX));
        assertFalse(vault.has(OTHER_USER), "another user name on the same router has its own entry");
        assertArrayEquals("S3cret-كلمة".toCharArray(), vault.load(HEX).orElseThrow());
        String stored = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(stored.contains("S3cret"), "only the encrypted form is written");

        vault.forget(HEX);
        assertEquals(Optional.empty(), vault.load(HEX));
        assertFalse(vault.has(HEX));
    }

    @Test
    void unreadableEntriesAreIgnored() throws Exception {
        Path file = dir.resolve("credentials");
        Files.writeString(file, "10.0.0.1\\:80|admin=bm90IG91cnM=\n", StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), new PasswordVault(file, new FakeProtector(true)).load(HEX),
                "e.g. a file copied from another Windows account");
    }

    @Test
    void nothingIsStoredWhereDpapiDoesNotExist() throws Exception {
        Path file = dir.resolve("credentials");
        PasswordVault vault = new PasswordVault(file, new FakeProtector(false));

        vault.save(HEX, "x".toCharArray());

        assertFalse(vault.available());
        assertFalse(Files.exists(file));
        assertEquals(Optional.empty(), vault.load(HEX));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDpapiRoundTrip() throws Exception {
        PasswordVault vault = new PasswordVault(dir.resolve("credentials"), new DpapiProtector());

        vault.save(HEX, "router-pass".toCharArray());

        assertTrue(vault.available());
        assertArrayEquals("router-pass".toCharArray(), vault.load(HEX).orElseThrow());
    }
}
