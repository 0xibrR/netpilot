package org.netpilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class UiPreferencesTest {

    @Test
    void systemThemeFollowsTheOperatingSystem() {
        assertTrue(Theme.SYSTEM.isDark(true));
        assertFalse(Theme.SYSTEM.isDark(false));
        assertTrue(Theme.DARK.isDark(false));
        assertFalse(Theme.LIGHT.isDark(true));
    }

    @Test
    void semiDarkHasDarkChromeOnLightContent() {
        assertFalse(Theme.SEMI_DARK.isDark(true));
        assertTrue(Theme.SEMI_DARK.darkChrome(false));
        assertTrue(Theme.DARK.darkChrome(false));
        assertFalse(Theme.LIGHT.darkChrome(true));
        assertTrue(Theme.SYSTEM.darkChrome(true));
    }

    @Test
    void accentMustBeAHexColour() {
        assertEquals("#7367F0", new UiPreferences(UiLanguage.ENGLISH, Theme.LIGHT, " #7367f0 ").accent());
        assertEquals(UiPreferences.DEFAULT_ACCENT, new UiPreferences(UiLanguage.ENGLISH, Theme.LIGHT, "red").accent());
        assertEquals(UiPreferences.DEFAULT_ACCENT, new UiPreferences(UiLanguage.ENGLISH, Theme.LIGHT).accent());
        assertTrue(UiPreferences.ACCENTS.contains(UiPreferences.DEFAULT_ACCENT));
    }

    @Test
    void routerResourceIsClamped() {
        RouterResource resource = new RouterResource(140, 10, 5, null);
        assertEquals(100, resource.cpuLoad());
        assertEquals(0, resource.usedMemory());
        assertEquals("", resource.uptime());
    }

    @Test
    void arabicSystemsStartInArabic() {
        assertEquals(UiLanguage.ARABIC, UiLanguage.forLocale(Locale.of("ar", "EG")));
        assertEquals(UiLanguage.ENGLISH, UiLanguage.forLocale(Locale.GERMANY));
        assertEquals("ar", UiLanguage.ARABIC.locale().getLanguage());
    }

    @Test
    void missingValuesUseDefaults() {
        UiPreferences prefs = new UiPreferences(UiLanguage.ARABIC, null);
        assertEquals(Theme.SYSTEM, prefs.theme());
        assertEquals(UiLanguage.forLocale(Locale.getDefault()), UiPreferences.defaults().language());
    }

    @Test
    void settingsKeepOtherFieldsWhenUiChanges() {
        AppSettings base = new AppSettings(GenerationSettings.defaults(), "", 5, Path.of("out"), null, null);
        AppSettings changed = base.withUi(new UiPreferences(UiLanguage.ENGLISH, Theme.DARK));
        assertEquals(Theme.DARK, changed.ui().theme());
        assertEquals(base.generation(), changed.generation());
        assertEquals(Theme.DARK, changed.withRouter(RouterSettings.defaults()).ui().theme());
        assertEquals(Theme.DARK, changed.withVoucherTemplate(VoucherTemplate.defaults()).ui().theme());
    }
}
