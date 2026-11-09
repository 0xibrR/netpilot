package org.netpilot.platform;

/** Encrypts and decrypts bytes for the current user; throws if the data cannot be read back. */
public interface SecretProtector {

    /** Whether this computer supports it (Windows DPAPI exists only on Windows). */
    boolean available();

    byte[] protect(byte[] plain);

    byte[] unprotect(byte[] encrypted);
}
