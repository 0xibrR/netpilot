package org.netpilot.platform;

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

/**
 * The Windows store, unchanged from earlier versions: one file, each secret encrypted by a
 * {@link SecretProtector} (Windows DPAPI, so only the same Windows account on the same computer
 * can read it) and kept as Base64 under its key. Files written by earlier versions are read as
 * they are.
 */
public final class ProtectedFileCredentialStore implements CredentialStore {

    private static final System.Logger LOG = System.getLogger(ProtectedFileCredentialStore.class.getName());

    private final Path file;
    private final SecretProtector protector;

    public ProtectedFileCredentialStore(Path file, SecretProtector protector) {
        this.file = file;
        this.protector = protector;
    }

    @Override
    public boolean available() {
        return protector.available();
    }

    @Override
    public Optional<char[]> load(String key) {
        if (!available()) {
            return Optional.empty();
        }
        String stored = read().getProperty(key);
        if (stored == null) {
            return Optional.empty();
        }
        byte[] plain = null;
        try {
            plain = protector.unprotect(Base64.getDecoder().decode(stored));
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] secret = Arrays.copyOfRange(chars.array(), chars.position(), chars.limit());
            Arrays.fill(chars.array(), '\0');
            return Optional.of(secret);
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

    @Override
    public boolean has(String key) {
        return available() && read().containsKey(key);
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
            Properties p = read();
            p.setProperty(key, Base64.getEncoder().encodeToString(protector.protect(plain)));
            write(p);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Properties p = read();
        if (p.remove(key) != null) {
            write(p);
        }
    }

    @Override
    public String description() {
        return "Windows DPAPI file";
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
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(writer, "NetPilot remembered router passwords (encrypted for this Windows account)");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
