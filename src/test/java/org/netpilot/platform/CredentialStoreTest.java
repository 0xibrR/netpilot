package org.netpilot.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialStoreTest {

    private static final String KEY = "10.0.0.1:80|netpilot";
    private static final char[] SECRET = "كلمة-سر-2026".toCharArray();

    @TempDir
    Path dir;

    /** Stands in for DPAPI: reversible, so the file format can be checked. */
    private static final class XorProtector implements SecretProtector {

        private final boolean available;

        XorProtector(boolean available) {
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public byte[] protect(byte[] plain) {
            byte[] out = plain.clone();
            for (int i = 0; i < out.length; i++) {
                out[i] ^= 0x5A;
            }
            return out;
        }

        @Override
        public byte[] unprotect(byte[] encrypted) {
            return protect(encrypted);
        }
    }

    /** Stands in for the macOS Keychain: items by service and account. */
    private static final class FakeKeychain implements KeychainCredentialStore.KeychainApi {

        final Map<String, byte[]> items = new HashMap<>();
        boolean available = true;
        boolean denied;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<byte[]> find(String service, String account) throws IOException {
            if (denied) {
                throw new IOException("Keychain find failed (OSStatus -128)"); // the user pressed Deny
            }
            return Optional.ofNullable(items.get(service + "/" + account)).map(byte[]::clone);
        }

        @Override
        public void put(String service, String account, byte[] secret) {
            items.put(service + "/" + account, secret.clone());
        }

        @Override
        public boolean delete(String service, String account) {
            return items.remove(service + "/" + account) != null;
        }
    }

    @Test
    void windowsStoreKeepsTheEarlierFileFormat() throws Exception {
        Path file = dir.resolve("credentials");
        // a file written by an earlier NetPilot: Base64 of the protected bytes under the same key
        Properties old = new Properties();
        old.setProperty(KEY, Base64.getEncoder().encodeToString(new XorProtector(true).protect(
                new String(SECRET).getBytes(StandardCharsets.UTF_8))));
        try (var w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            old.store(w, null);
        }
        CredentialStore store = new ProtectedFileCredentialStore(file, new XorProtector(true));

        assertTrue(store.has(KEY));
        assertArrayEquals(SECRET, store.load(KEY).orElseThrow(), "a password remembered before still works");

        store.save("other|admin", "x".toCharArray());
        store.delete(KEY);
        assertFalse(store.has(KEY));
        assertTrue(store.has("other|admin"));
        assertFalse(Files.readString(file).contains("x\n"), "never in plain text");
        assertEquals("Windows DPAPI file", store.description());
    }

    @Test
    void windowsStoreOnAnotherSystemOrWithAnUnreadableEntryRemembersNothing() throws Exception {
        Path file = dir.resolve("credentials");
        Files.writeString(file, KEY + "=bm90LXByb3RlY3RlZA==\n");
        CredentialStore elsewhere = new ProtectedFileCredentialStore(file, new XorProtector(false));
        CredentialStore broken = new ProtectedFileCredentialStore(file, new SecretProtector() {

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public byte[] protect(byte[] plain) {
                return plain;
            }

            @Override
            public byte[] unprotect(byte[] encrypted) {
                throw new IllegalStateException("copied from another Windows account");
            }
        });

        assertFalse(elsewhere.available());
        assertEquals(Optional.empty(), elsewhere.load(KEY));
        elsewhere.save("new|key", SECRET);
        assertFalse(Files.readString(file).contains("new|key"), "nothing saved where it cannot be protected");
        assertEquals(Optional.empty(), broken.load(KEY), "unreadable: treated as not remembered");
    }

    @Test
    void macStoreUsesTheKeychainUnderTheNetPilotService() throws Exception {
        FakeKeychain keychain = new FakeKeychain();
        CredentialStore store = new KeychainCredentialStore(keychain, "NetPilot");

        store.save(KEY, SECRET);
        assertTrue(keychain.items.containsKey("NetPilot/" + KEY), "one generic password per router and user");
        assertArrayEquals(SECRET, store.load(KEY).orElseThrow());
        store.save(KEY, "new".toCharArray());
        assertArrayEquals("new".toCharArray(), store.load(KEY).orElseThrow(), "saving again replaces it");
        assertEquals(1, keychain.items.size());

        store.delete(KEY);
        assertFalse(store.has(KEY));
        store.delete(KEY); // nothing there: no error
        assertEquals("macOS Keychain", store.description());
    }

    @Test
    void macStoreTreatsADeniedOrMissingKeychainAsNotRemembered() throws Exception {
        FakeKeychain keychain = new FakeKeychain();
        CredentialStore store = new KeychainCredentialStore(keychain, "NetPilot");
        store.save(KEY, SECRET);

        keychain.denied = true;
        assertEquals(Optional.empty(), store.load(KEY), "the user said Deny: connect by typing it");
        assertFalse(store.has(KEY));

        keychain.denied = false;
        keychain.available = false;
        assertFalse(store.available());
        assertEquals(Optional.empty(), store.load(KEY));
        store.save("x", SECRET);
        assertFalse(keychain.items.containsKey("NetPilot/x"));
    }

    @Test
    void eachSystemGetsItsOwnStore() {
        Path file = dir.resolve("credentials");
        SecretProtector dpapi = new XorProtector(true);

        assertInstanceOf(ProtectedFileCredentialStore.class, CredentialStore.forPlatform(
                PlatformInfo.of(Map.of("os.name", "Windows 11", "os.arch", "amd64")), file, dpapi));
        assertInstanceOf(KeychainCredentialStore.class, CredentialStore.forPlatform(
                PlatformInfo.of(Map.of("os.name", "Mac OS X", "os.arch", "aarch64")), file, dpapi));
        CredentialStore linux = CredentialStore.forPlatform(
                PlatformInfo.of(Map.of("os.name", "Linux", "os.arch", "amd64")), file, dpapi);
        assertFalse(linux.available());
        assertEquals(Optional.empty(), linux.load(KEY));
        assertFalse(linux.has(KEY));
        assertEquals("none", linux.description());
    }
}
