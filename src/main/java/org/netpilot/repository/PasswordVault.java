package org.netpilot.repository;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import org.netpilot.model.RouterEndpoint;

/**
 * Remembered router passwords, only when the user asks for it.
 *
 * <p>Each password is encrypted by a {@link Protector} (Windows DPAPI in the app, so only the same
 * Windows account on the same computer can read it) and stored in its own file, never in the
 * settings. One entry per router address and user name.
 */
public final class PasswordVault {

    private static final System.Logger LOG = System.getLogger(PasswordVault.class.getName());

    /** Encrypts and decrypts bytes; throws if the data cannot be read back. */
    public interface Protector {

        /** Whether this computer supports it (DPAPI exists only on Windows). */
        boolean available();

        byte[] protect(byte[] plain);

        byte[] unprotect(byte[] encrypted);
    }

    private final Path file;
    private final Protector protector;

    public PasswordVault(Path file, Protector protector) {
        this.file = file;
        this.protector = protector;
    }

    /** Whether passwords can be remembered on this computer. */
    public boolean available() {
        return protector.available();
    }

    /** The remembered password, or empty if there is none or it cannot be decrypted any more. */
    public Optional<char[]> load(RouterEndpoint endpoint) {
        if (!available()) {
            return Optional.empty();
        }
        String stored = read().getProperty(key(endpoint));
        if (stored == null) {
            return Optional.empty();
        }
        byte[] plain = null;
        try {
            plain = protector.unprotect(Base64.getDecoder().decode(stored));
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] password = Arrays.copyOfRange(chars.array(), chars.position(), chars.limit());
            Arrays.fill(chars.array(), '\0');
            return Optional.of(password);
        } catch (RuntimeException e) {
            // e.g. copied from another computer or Windows account: treat as not remembered
            LOG.log(System.Logger.Level.WARNING, "A remembered password could not be decrypted and is ignored");
            return Optional.empty();
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    public boolean has(RouterEndpoint endpoint) {
        return available() && read().containsKey(key(endpoint));
    }

    /** Encrypts and stores the password; the caller's array is left untouched. */
    public void save(RouterEndpoint endpoint, char[] password) throws IOException {
        if (!available()) {
            return;
        }
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] plain = Arrays.copyOfRange(bytes.array(), bytes.position(), bytes.limit());
        Arrays.fill(bytes.array(), (byte) 0);
        try {
            Properties p = read();
            p.setProperty(key(endpoint), Base64.getEncoder().encodeToString(protector.protect(plain)));
            write(p);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public void forget(RouterEndpoint endpoint) throws IOException {
        Properties p = read();
        if (p.remove(key(endpoint)) != null) {
            write(p);
        }
    }

    private static String key(RouterEndpoint endpoint) {
        return endpoint.address() + "|" + endpoint.username();
    }

    private Properties read() {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot read remembered passwords: {0}", e.getMessage());
            }
        }
        return p;
    }

    private void write(Properties p) throws IOException {
        FileSupport.createParentDirectories(file);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(writer, "NetPilot remembered router passwords (encrypted for this Windows account)");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
