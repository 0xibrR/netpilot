package org.netpilot.platform;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * The macOS store: each secret is a generic password in the user's login Keychain, under the
 * service {@code NetPilot} and the key as account (router address and user name). The Keychain
 * encrypts it and macOS may ask the user to allow NetPilot to read it.
 */
public final class KeychainCredentialStore implements CredentialStore {

    private static final System.Logger LOG = System.getLogger(KeychainCredentialStore.class.getName());

    /** The Keychain calls this store needs; {@link org.netpilot.platform.mac.NativeKeychain} on a Mac. */
    public interface KeychainApi {

        /** Whether the Keychain can be used (macOS with its Security framework). */
        boolean available();

        /** The stored bytes, or empty when there is no such item. */
        Optional<byte[]> find(String service, String account) throws IOException;

        /** Adds the item, or replaces its data when it exists. */
        void put(String service, String account, byte[] secret) throws IOException;

        /** @return whether an item was removed */
        boolean delete(String service, String account) throws IOException;
    }

    private final KeychainApi keychain;
    private final String service;

    public KeychainCredentialStore(KeychainApi keychain, String service) {
        this.keychain = keychain;
        this.service = service;
    }

    @Override
    public boolean available() {
        return keychain.available();
    }

    @Override
    public Optional<char[]> load(String key) {
        if (!available()) {
            return Optional.empty();
        }
        byte[] plain = null;
        try {
            Optional<byte[]> found = keychain.find(service, key);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            plain = found.get();
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] secret = Arrays.copyOfRange(chars.array(), chars.position(), chars.limit());
            Arrays.fill(chars.array(), '\0');
            return Optional.of(secret);
        } catch (IOException | RuntimeException e) {
            // denied by the user, locked Keychain...: treat as not remembered; never log the secret
            LOG.log(System.Logger.Level.WARNING, "The Keychain did not give the remembered password: {0}",
                    e.getMessage());
            return Optional.empty();
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    @Override
    public boolean has(String key) {
        if (!available()) {
            return false;
        }
        try {
            Optional<byte[]> found = keychain.find(service, key);
            found.ifPresent(bytes -> Arrays.fill(bytes, (byte) 0));
            return found.isPresent();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void save(String key, char[] secret) throws IOException {
        if (!available()) {
            return;
        }
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] plain = Arrays.copyOfRange(bytes.array(), bytes.position(), bytes.limit());
        Arrays.fill(bytes.array(), (byte) 0);
        try {
            keychain.put(service, key, plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        if (available()) {
            keychain.delete(service, key);
        }
    }

    @Override
    public String description() {
        return "macOS Keychain";
    }
}
