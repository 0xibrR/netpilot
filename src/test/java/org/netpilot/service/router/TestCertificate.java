package org.netpilot.service.router;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A real self-signed certificate, like the ones routers use, created with the JDK's
 * {@code keytool}. Its common name is {@code router}, not the IP we connect to, which also
 * proves NetPilot does not depend on host-name matching.
 */
record TestCertificate(SSLContext serverContext, String fingerprint) {

    private static final char[] PASSWORD = "changeit".toCharArray();

    static TestCertificate create(Path dir) throws IOException, InterruptedException, GeneralSecurityException {
        Path keystore = dir.resolve("router.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(List.of(keytool, "-genkeypair",
                "-alias", "router", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=router", "-validity", "2",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD)))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool failed: " + output);
        }

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, PASSWORD);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);

        X509Certificate certificate = (X509Certificate) store.getCertificate("router");
        return new TestCertificate(context, CertificateFingerprints.sha256(certificate));
    }
}
