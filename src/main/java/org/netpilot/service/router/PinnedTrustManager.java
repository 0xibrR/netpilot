package org.netpilot.service.router;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Trusts exactly one certificate: the one whose SHA-256 fingerprint the user confirmed.
 *
 * <p>Routers use self-signed certificates, so normal CA validation cannot work. Accepting any
 * certificate would let an attacker read the router password; pinning the fingerprint ("trust
 * on first use") gives the same protection as SSH host keys.
 *
 * <p>Extending {@link X509ExtendedTrustManager} (not plain {@code X509TrustManager}) stops the JDK
 * from adding a host-name check on top: router certificates rarely name the IP the user types,
 * and the pinned fingerprint already proves identity.
 */
final class PinnedTrustManager extends X509ExtendedTrustManager {

    /** Raised during the TLS handshake; carries the fingerprint so the UI can show it. */
    static final class PinMismatchException extends CertificateException {

        private static final long serialVersionUID = 1L;

        private final String presentedFingerprint;
        private final boolean firstUse;

        PinMismatchException(String presentedFingerprint, boolean firstUse) {
            super(firstUse ? "Router certificate is not trusted yet" : "Router certificate has changed");
            this.presentedFingerprint = presentedFingerprint;
            this.firstUse = firstUse;
        }

        String presentedFingerprint() {
            return presentedFingerprint;
        }

        boolean firstUse() {
            return firstUse;
        }
    }

    private final String expectedFingerprint;

    /**
     * @param expectedFingerprint trusted SHA-256 fingerprint, or {@code null} on first use
     */
    PinnedTrustManager(String expectedFingerprint) {
        String normalized = CertificateFingerprints.normalize(expectedFingerprint);
        this.expectedFingerprint = normalized.isEmpty() ? null : normalized;
    }

    private void check(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Router sent no certificate");
        }
        String presented = CertificateFingerprints.sha256(chain[0]);
        if (expectedFingerprint == null) {
            throw new PinMismatchException(presented, true);
        }
        if (!expectedFingerprint.equals(presented)) {
            throw new PinMismatchException(presented, false);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("NetPilot does not accept client connections");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new CertificateException("NetPilot does not accept client connections");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("NetPilot does not accept client connections");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
