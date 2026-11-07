package org.netpilot.repository;

import com.sun.jna.platform.win32.Crypt32Util;
import java.util.Locale;

/**
 * Windows Data Protection API: data encrypted here can only be decrypted by the same Windows
 * account on the same computer. It is what Windows itself uses for saved Wi-Fi and browser
 * passwords.
 */
public final class DpapiProtector implements PasswordVault.Protector {

    @Override
    public boolean available() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    @Override
    public byte[] protect(byte[] plain) {
        return Crypt32Util.cryptProtectData(plain);
    }

    @Override
    public byte[] unprotect(byte[] encrypted) {
        return Crypt32Util.cryptUnprotectData(encrypted);
    }
}
