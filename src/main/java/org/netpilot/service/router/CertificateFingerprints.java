package org.netpilot.service.router;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;

/**
 * SHA-256 certificate fingerprints in the format RouterOS shows
 * ({@code /certificate print detail}): 64 lowercase hex characters, no separators.
 */
public final class CertificateFingerprints {

    private CertificateFingerprints() {
    }

    public static String sha256(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("Cannot encode certificate", e);
        }
    }

    /** Accepts upper case and ':' or space separators, as users may paste them from other tools. */
    public static String normalize(String fingerprint) {
        return fingerprint == null ? "" : fingerprint.replaceAll("[\\s:]", "").toLowerCase(Locale.ROOT);
    }

    /** Groups of four for easier reading, e.g. {@code 3f2a 9c01 ...}. */
    public static String readable(String fingerprint) {
        String hex = normalize(fingerprint);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) {
                sb.append(i % 32 == 0 ? '\n' : ' ');
            }
            sb.append(hex, i, Math.min(i + 4, hex.length()));
        }
        return sb.toString();
    }
}
