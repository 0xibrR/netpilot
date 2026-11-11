package org.netpilot.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsInstallTest {

    /** What {@code reg query HKLM\...\Uninstall /s /f NetPilot /d} printed on a computer with NetPilot installed. */
    private static final String INSTALLED = """

            HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{CE021412-E498-3EC4-8F21-3FD048A27F5D}
                InstallLocation    REG_SZ    C:\\Users\\hp\\AppData\\Local\\NetPilot\\
                URLInfoAbout    REG_SZ    https://github.com/0xibrR/netpilot
                DisplayName    REG_SZ    NetPilot

            HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{0000-OTHER}
                DisplayName    REG_SZ    NetPilot Helper
                InstallLocation    REG_SZ    C:\\Program Files\\NetPilot Helper\\

            End of search: 5 match(es) found.
            """;

    @Test
    void readsTheInstallFolderOfTheEntryWithThisExactName() {
        assertEquals(List.of("C:\\Users\\hp\\AppData\\Local\\NetPilot\\"),
                WindowsInstall.installFolders(INSTALLED, "NetPilot"), "another program with a longer name is ignored");
        assertEquals(List.of(), WindowsInstall.installFolders("End of search: 0 match(es) found.", "NetPilot"));
        assertEquals(List.of(), WindowsInstall.installFolders("", "NetPilot"));
    }

    @Test
    void onlyAProgramInsideTheInstallFolderIsTheInstalledCopy() {
        List<String> folders = WindowsInstall.installFolders(INSTALLED, "NetPilot");

        assertTrue(WindowsInstall.contains(folders, "C:\\Users\\hp\\AppData\\Local\\NetPilot\\NetPilot.exe"));
        assertTrue(WindowsInstall.contains(folders, "c:\\users\\HP\\appdata\\local\\netpilot\\NetPilot.exe"),
                "Windows paths ignore case");
        assertFalse(WindowsInstall.contains(folders, "D:\\Tools\\NetPilot-1.2.0\\NetPilot.exe"),
                "a portable copy unzipped elsewhere");
        assertFalse(WindowsInstall.contains(folders, "C:\\Users\\hp\\AppData\\Local\\NetPilotOld\\NetPilot.exe"),
                "a folder that only starts with the same name");
        assertTrue(WindowsInstall.contains(List.of("C:\\Apps\\NetPilot"), "C:\\Apps\\NetPilot\\NetPilot.exe"),
                "an install folder written without the last backslash");
        assertFalse(WindowsInstall.contains(List.of(), "C:\\Apps\\NetPilot\\NetPilot.exe"));
    }

    @Test
    void notStartedFromItsLauncherIsNeverTheInstalledCopy() {
        assertFalse(WindowsInstall.installed("NetPilot", null), "run from the IDE");
        assertFalse(WindowsInstall.installed("NetPilot", " "));
    }
}
