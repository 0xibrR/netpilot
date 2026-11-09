package org.netpilot.platform;

import java.io.IOException;
import java.util.Optional;

/**
 * Where the system keeps secrets for the signed-in user: the remembered router password. Each
 * platform has its own implementation; the rest of NetPilot only sees this interface.
 *
 * <p>Implementations never log, show or return secrets other than through {@link #load}, and the
 * caller clears the arrays it gets and gives.
 */
public interface CredentialStore {

    /** Whether secrets can be kept on this computer at all. */
    boolean available();

    /** The secret, or empty when there is none or it cannot be read on this computer/account. */
    Optional<char[]> load(String key);

    boolean has(String key);

    /** Stores (or replaces) the secret; the caller's array is left untouched. */
    void save(String key, char[] secret) throws IOException;

    /** Removes the secret; nothing happens when there is none. */
    void delete(String key) throws IOException;

    /** For logs: e.g. {@code Windows DPAPI file} or {@code macOS Keychain}. */
    String description();

    /** The store of this computer: DPAPI file on Windows, Keychain on macOS, none elsewhere. */
    static CredentialStore forPlatform(PlatformInfo platform, java.nio.file.Path credentialsFile,
            SecretProtector windowsProtector) {
        return switch (platform.os()) {
            case WINDOWS -> new ProtectedFileCredentialStore(credentialsFile, windowsProtector);
            case MACOS -> new KeychainCredentialStore(new org.netpilot.platform.mac.NativeKeychain(), "NetPilot");
            case LINUX, OTHER -> Unavailable.INSTANCE;
        };
    }

    /** A computer without a supported store: nothing is remembered, as before on such systems. */
    enum Unavailable implements CredentialStore {

        INSTANCE;

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<char[]> load(String key) {
            return Optional.empty();
        }

        @Override
        public boolean has(String key) {
            return false;
        }

        @Override
        public void save(String key, char[] secret) {
            // nothing to keep it in
        }

        @Override
        public void delete(String key) {
            // nothing kept
        }

        @Override
        public String description() {
            return "none";
        }
    }
}
