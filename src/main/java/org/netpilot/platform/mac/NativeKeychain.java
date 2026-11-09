package org.netpilot.platform.mac;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.netpilot.platform.KeychainCredentialStore.KeychainApi;
import org.netpilot.platform.PlatformInfo;

/**
 * The macOS Keychain through its own Security framework (loaded with JNA, which NetPilot already
 * uses for Windows DPAPI: no new dependency, no helper program, and the secret is never passed on
 * a command line).
 *
 * <p>Uses the generic-password calls ({@code SecKeychainFindGenericPassword} and friends), which
 * work on every supported macOS on Intel and Apple Silicon. The framework is loaded only when the
 * Keychain is first used, and only on a Mac.
 */
public final class NativeKeychain implements KeychainApi {

    private static final int ERR_SEC_ITEM_NOT_FOUND = -25300;

    /** The Security framework functions used. */
    interface Security extends Library {

        int SecKeychainFindGenericPassword(Pointer keychainOrArray, int serviceNameLength, byte[] serviceName,
                int accountNameLength, byte[] accountName, IntByReference passwordLength,
                PointerByReference passwordData, PointerByReference itemRef);

        int SecKeychainAddGenericPassword(Pointer keychain, int serviceNameLength, byte[] serviceName,
                int accountNameLength, byte[] accountName, int passwordLength, byte[] passwordData,
                PointerByReference itemRef);

        int SecKeychainItemModifyAttributesAndData(Pointer itemRef, Pointer attrList, int length, byte[] data);

        int SecKeychainItemDelete(Pointer itemRef);

        int SecKeychainItemFreeContent(Pointer attrList, Pointer data);
    }

    /** CoreFoundation, to release the items the Security calls return. */
    interface CoreFoundation extends Library {

        void CFRelease(Pointer object);
    }

    private Security security;
    private CoreFoundation coreFoundation;

    @Override
    public boolean available() {
        return PlatformInfo.current().macos();
    }

    private synchronized Security security() throws IOException {
        if (!available()) {
            throw new IOException("The Keychain exists only on macOS");
        }
        if (security == null) {
            try {
                security = Native.load("Security", Security.class);
                coreFoundation = Native.load("CoreFoundation", CoreFoundation.class);
            } catch (UnsatisfiedLinkError e) {
                throw new IOException("The macOS Security framework could not be loaded", e);
            }
        }
        return security;
    }

    @Override
    public Optional<byte[]> find(String service, String account) throws IOException {
        Security sec = security();
        byte[] s = service.getBytes(StandardCharsets.UTF_8);
        byte[] a = account.getBytes(StandardCharsets.UTF_8);
        IntByReference length = new IntByReference();
        PointerByReference data = new PointerByReference();
        int status = sec.SecKeychainFindGenericPassword(null, s.length, s, a.length, a, length, data, null);
        if (status == ERR_SEC_ITEM_NOT_FOUND) {
            return Optional.empty();
        }
        check(status, "find");
        try {
            return Optional.of(data.getValue().getByteArray(0, length.getValue()));
        } finally {
            sec.SecKeychainItemFreeContent(null, data.getValue());
        }
    }

    @Override
    public void put(String service, String account, byte[] secret) throws IOException {
        Security sec = security();
        byte[] s = service.getBytes(StandardCharsets.UTF_8);
        byte[] a = account.getBytes(StandardCharsets.UTF_8);
        PointerByReference item = new PointerByReference();
        int status = sec.SecKeychainFindGenericPassword(null, s.length, s, a.length, a, null, null, item);
        if (status == ERR_SEC_ITEM_NOT_FOUND) {
            check(sec.SecKeychainAddGenericPassword(null, s.length, s, a.length, a, secret.length, secret, null),
                    "add");
            return;
        }
        check(status, "find");
        try {
            check(sec.SecKeychainItemModifyAttributesAndData(item.getValue(), null, secret.length, secret), "update");
        } finally {
            coreFoundation.CFRelease(item.getValue());
        }
    }

    @Override
    public boolean delete(String service, String account) throws IOException {
        Security sec = security();
        byte[] s = service.getBytes(StandardCharsets.UTF_8);
        byte[] a = account.getBytes(StandardCharsets.UTF_8);
        PointerByReference item = new PointerByReference();
        int status = sec.SecKeychainFindGenericPassword(null, s.length, s, a.length, a, null, null, item);
        if (status == ERR_SEC_ITEM_NOT_FOUND) {
            return false;
        }
        check(status, "find");
        try {
            check(sec.SecKeychainItemDelete(item.getValue()), "delete");
            return true;
        } finally {
            coreFoundation.CFRelease(item.getValue());
        }
    }

    /** The status code only, never any data: secrets must not reach logs or messages. */
    private static void check(int status, String operation) throws IOException {
        if (status != 0) {
            throw new IOException("Keychain " + operation + " failed (OSStatus " + status + ")");
        }
    }
}
