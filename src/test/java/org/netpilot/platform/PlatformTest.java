package org.netpilot.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.platform.PlatformInfo.Architecture;
import org.netpilot.platform.PlatformInfo.OperatingSystem;

class PlatformTest {

    @TempDir
    Path home;

    private static PlatformInfo platform(String name, String arch, String version) {
        return PlatformInfo.of(Map.of("os.name", name, "os.arch", arch, "os.version", version));
    }

    @Test
    void detectsTheSystemAndTheProcessorIncludingIntelAndAppleSiliconMacs() {
        PlatformInfo windows = platform("Windows 11", "amd64", "10.0");
        PlatformInfo windowsArm = platform("Windows 11", "aarch64", "10.0");
        PlatformInfo appleSilicon = platform("Mac OS X", "aarch64", "14.5");
        PlatformInfo intelMac = platform("Mac OS X", "x86_64", "13.6");
        PlatformInfo linux = platform("Linux", "amd64", "6.8");

        assertTrue(windows.windows());
        assertEquals(Architecture.X64, windows.arch());
        assertEquals(Architecture.ARM64, windowsArm.arch());
        assertFalse(windowsArm.appleSilicon(), "ARM, but not a Mac");

        assertTrue(appleSilicon.macos());
        assertTrue(appleSilicon.appleSilicon());
        assertEquals("macOS ARM64 (Apple Silicon) 14.5", appleSilicon.describe());
        assertTrue(intelMac.macos());
        assertFalse(intelMac.appleSilicon());
        assertEquals("macOS x64 (Intel) 13.6", intelMac.describe());

        assertEquals(OperatingSystem.LINUX, linux.os());
        assertEquals("Windows X64 10.0", windows.describe());
        assertEquals(OperatingSystem.OTHER, platform("FreeBSD", "riscv64", "").os());
        assertEquals(Architecture.OTHER, platform("FreeBSD", "riscv64", "").arch());
        assertEquals(Architecture.X86, platform("Windows XP", "x86", "5.1").arch());
        assertTrue(PlatformInfo.current().describe().length() > 3, "the running computer is recognised");
    }

    @Test
    void windowsKeepsItsFoldersExactlyAsBefore() {
        AppDirectories dirs = AppDirectories.of(OperatingSystem.WINDOWS, Map.of(), home);

        assertEquals(home.resolve(".netpilot"), dirs.data(), "existing data, backups and passwords stay put");
        assertEquals(home.resolve(".netpilot"), dirs.config());
        assertEquals(home.resolve(".netpilot/logs"), dirs.logs());
        assertEquals(home.resolve("NetPilot"), dirs.documents(), "no Documents folder in this home");
    }

    @Test
    void macOsUsesItsLibraryFoldersUnlessOldDataIsThere() throws Exception {
        Files.createDirectories(home.resolve("Documents"));
        AppDirectories fresh = AppDirectories.of(OperatingSystem.MACOS, Map.of(), home);

        assertEquals(home.resolve("Library/Application Support/NetPilot"), fresh.data());
        assertEquals(fresh.data(), fresh.config());
        assertEquals(home.resolve("Library/Logs/NetPilot"), fresh.logs());
        assertEquals(home.resolve("Library/Caches/NetPilot"), fresh.cache());
        assertEquals(home.resolve("Documents/NetPilot"), fresh.documents());

        Files.createDirectories(home.resolve(".netpilot"));
        AppDirectories old = AppDirectories.of(OperatingSystem.MACOS, Map.of(), home);
        assertEquals(home.resolve(".netpilot"), old.data(), "data from an earlier NetPilot is not left behind");
        assertEquals(home.resolve(".netpilot/logs"), old.logs());
    }

    @Test
    void linuxFollowsTheXdgFolders() {
        AppDirectories defaults = AppDirectories.of(OperatingSystem.LINUX, Map.of(), home);
        AppDirectories custom = AppDirectories.of(OperatingSystem.LINUX, Map.of(
                "XDG_DATA_HOME", home.resolve("d").toString(),
                "XDG_CONFIG_HOME", home.resolve("c").toString(),
                "XDG_CACHE_HOME", "relative/ignored"), home);

        assertEquals(home.resolve(".local/share/netpilot"), defaults.data());
        assertEquals(home.resolve(".config/netpilot"), defaults.config());
        assertEquals(home.resolve(".cache/netpilot"), defaults.cache());
        assertEquals(home.resolve("d/netpilot"), custom.data());
        assertEquals(home.resolve("c/netpilot"), custom.config());
        assertEquals(home.resolve(".cache/netpilot"), custom.cache(), "XDG paths must be absolute");
        assertEquals(custom.data().resolve("logs"), custom.logs());
    }
}
