package org.netpilot.platform;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Whether this NetPilot was installed by its Windows installer, so a newer installer can replace it
 * in place. Read from the "installed programs" entries Windows keeps: depending on how the installer
 * ran they are in the user's or the machine's part of the registry (found in practice: the per-user
 * installer writes the machine's part), so all three places are searched. The entry's install
 * folder must contain the running program: a portable copy unzipped elsewhere is not "installed",
 * even when an installed NetPilot also exists on the computer.
 */
public final class WindowsInstall {

    private static final System.Logger LOG = System.getLogger(WindowsInstall.class.getName());

    private static final List<String> UNINSTALL_KEYS = List.of(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall");

    private WindowsInstall() {
    }

    /**
     * @param appName  the name in "installed programs", e.g. {@code NetPilot}
     * @param appPath  the running launcher ({@code jpackage.app-path}); null when not started from it
     */
    public static boolean installed(String appName, String appPath) {
        if (appPath == null || appPath.isBlank()
                || PlatformInfo.current().os() != PlatformInfo.OperatingSystem.WINDOWS) {
            return false;
        }
        List<String> folders = new ArrayList<>();
        for (String key : UNINSTALL_KEYS) {
            folders.addAll(installFolders(query(key, appName), appName));
        }
        boolean inside = contains(folders, appPath);
        LOG.log(System.Logger.Level.INFO, "Installed copy: {0} (running from {1}, install folders {2})", inside,
                appPath, folders);
        return inside;
    }

    /**
     * The install folders of the entries named {@code appName} in the output of
     * {@code reg query <key> /s /f <name> /d}: lines are a key ({@code HKEY_...}) followed by its
     * matching values ({@code    Name    REG_SZ    data}).
     */
    static List<String> installFolders(String regOutput, String appName) {
        List<String> folders = new ArrayList<>();
        String folder = null;
        boolean named = false;
        for (String line : regOutput.split("\\R")) {
            if (line.startsWith("HKEY_")) {
                if (named && folder != null) {
                    folders.add(folder);
                }
                folder = null;
                named = false;
                continue;
            }
            String[] parts = line.strip().split("\\s{2,}|\\t", 3);
            if (parts.length == 3 && parts[1].startsWith("REG_")) {
                if (parts[0].equals("DisplayName") && parts[2].strip().equalsIgnoreCase(appName)) {
                    named = true;
                } else if (parts[0].equals("InstallLocation") && !parts[2].isBlank()) {
                    folder = parts[2].strip();
                }
            }
        }
        if (named && folder != null) {
            folders.add(folder);
        }
        return folders;
    }

    /** The program lies inside one of the folders (compared as paths, ignoring case as Windows does). */
    static boolean contains(List<String> folders, String appPath) {
        String program = normal(appPath);
        for (String folder : folders) {
            String inside = normal(folder);
            if (!inside.endsWith("/")) {
                inside += "/";
            }
            if (program.startsWith(inside)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code C:\Users\HP\AppData\Local\NetPilot\} → {@code c:/users/hp/appdata/local/netpilot/}: compared as
     * text, so the result is the same on any computer the tests run on.
     */
    private static String normal(String path) {
        return path.strip().replace('\\', '/').replaceAll("/{2,}", "/").toLowerCase(Locale.ROOT);
    }

    private static String query(String key, String appName) {
        try {
            Process reg = new ProcessBuilder("reg", "query", key, "/s", "/f", appName, "/d")
                    .redirectErrorStream(true).start();
            // reg writes in the console's code page; the values compared here are plain ASCII anyway
            String output = new String(reg.getInputStream().readAllBytes(), Charset.defaultCharset());
            reg.waitFor(10, TimeUnit.SECONDS);
            return output;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot read {0}: {1}", key, e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
