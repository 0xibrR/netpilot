package org.netpilot.repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.platform.CredentialStore;
import org.netpilot.platform.ProtectedFileCredentialStore;
import org.netpilot.platform.SecretProtector;

/**
 * Remembered router passwords, only when the user asks for it, one per router address and user
 * name. Where they are kept is the platform's {@link CredentialStore}: the DPAPI-encrypted file on
 * Windows (unchanged), the Keychain on macOS. Never in the settings.
 */
public final class PasswordVault {

    /** Kept for the Windows DPAPI protector and existing callers; see {@link SecretProtector}. */
    public interface Protector extends SecretProtector {
    }

    private final CredentialStore store;

    public PasswordVault(CredentialStore store) {
        this.store = store;
    }

    /** The Windows file store, as in earlier versions. */
    public PasswordVault(Path file, Protector protector) {
        this(new ProtectedFileCredentialStore(file, protector));
    }

    /** Whether passwords can be remembered on this computer. */
    public boolean available() {
        return store.available();
    }

    /** The remembered password, or empty if there is none or it cannot be read any more. */
    public Optional<char[]> load(RouterEndpoint endpoint) {
        return store.load(key(endpoint));
    }

    public boolean has(RouterEndpoint endpoint) {
        return store.has(key(endpoint));
    }

    /** Stores the password; the caller's array is left untouched. */
    public void save(RouterEndpoint endpoint, char[] password) throws IOException {
        store.save(key(endpoint), password);
    }

    public void forget(RouterEndpoint endpoint) throws IOException {
        store.delete(key(endpoint));
    }

    /** Same keys as earlier versions, so passwords remembered before are found. */
    private static String key(RouterEndpoint endpoint) {
        return endpoint.address() + "|" + endpoint.username();
    }
}
