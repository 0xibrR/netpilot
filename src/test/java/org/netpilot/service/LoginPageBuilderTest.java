package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.LoginPageDesign;
import org.netpilot.model.LoginPageDesign.Language;
import org.netpilot.repository.LoginPageStore;

class LoginPageBuilderTest {

    @TempDir
    Path dir;

    private static final Map<String, String> PRICES = new LinkedHashMap<>(Map.of("1M", "1 ₪", "odd", "ask"));

    private static LoginPageDesign design(String title, String welcome, boolean password, Language language) {
        return new LoginPageDesign(title, welcome, "0599 123-456", "+970 599 000000", "#ABCDEF", true, true, password,
                language);
    }

    @Test
    void theLoginPageKeepsChapAndEveryPageTheRouterNeeds() {
        Map<String, String> files = LoginPageBuilder.build(design("", "", false, Language.ARABIC), "شبكة الأمل",
                PRICES, Optional.of("data:image/png;base64,AAAA"));

        assertEquals(java.util.List.of("login.html", "alogin.html", "status.html", "logout.html", "error.html",
                "rlogin.html", "redirect.html", "md5.js"), java.util.List.copyOf(files.keySet()));
        String login = files.get("login.html");
        assertTrue(login.contains("action=\"$(link-login-only)\""));
        assertTrue(login.contains("hexMD5('$(chap-id)' + document.login.password.value + '$(chap-challenge)')"),
                "the password is sent only as the CHAP answer");
        assertTrue(login.contains("$(if chap-id)") && login.contains("$(endif)"));
        assertTrue(login.contains("<input name=\"password\" type=\"hidden\""), "cards without passwords: one field");
        assertTrue(login.contains("dir=\"rtl\"") && login.contains("رقم الكرت"));
        assertTrue(login.contains("<title>شبكة الأمل</title>"), "the card design's name when no title is set");
        assertTrue(login.contains("--main: #abcdef"));
        assertTrue(login.contains("src=\"data:image/png;base64,AAAA\""));
        assertTrue(login.contains("<div class=\"plan\"><span>1M</span><b>1 ₪</b></div>"));
        assertTrue(login.contains("data-tab=\"prices\"") && !login.contains("data-tab=\"sellers\""),
                "a packages tab; no points of sale unless asked");
        assertTrue(login.contains("localStorage.setItem('np-card'"), "remember the card");
        assertFalse(login.contains("ask"), "a price that cannot be read is not shown");
        assertTrue(login.contains("href=\"tel:0599123456\"") && login.contains("href=\"https://wa.me/970599000000\""));
        assertTrue(login.contains("انتهى وقت هذا الكرت"), "router errors said in Arabic");
        assertTrue(
                files.get("status.html").contains("$(link-logout)") && files.get("status.html").contains("$(uptime)"));
        assertTrue(files.get("alogin.html").contains("url=$(link-redirect)"));
        assertTrue(files.get("md5.js").contains("function hexMD5(s)"));
        assertFalse(login.contains("http://") || login.contains("https://fonts"), "nothing from the internet");
    }

    @Test
    void typedTextCanNeverBecomeMarkupOrARouterVariable() {
        String login = LoginPageBuilder.build(design("<b>$(username)</b>", "خصم & \"عرض\" 'اليوم'", true,
                Language.ENGLISH), "x", Map.of(), Optional.empty()).get("login.html");

        assertTrue(login.contains("&lt;b&gt;&#36;(username)&lt;/b&gt;"));
        assertTrue(login.contains("خصم &amp; &quot;عرض&quot; &#39;اليوم&#39;"));
        assertTrue(login.contains("<input name=\"password\" type=\"password\""));
        assertTrue(login.contains("dir=\"ltr\"") && login.contains("Card code"));
        assertFalse(login.contains("<img"), "no logo given");
        assertFalse(login.contains("class=\"plans\"") || login.contains("id=\"tabs\""), "no prices, so no tabs");
        assertFalse(login.contains("انتهى"), "English pages keep the router's words");
        assertFalse(login.contains("رقم الكرت"), "one language only");
    }

    @Test
    void thePreviewShowsWhatAPhoneWouldSee() {
        Map<String, String> files = LoginPageBuilder.build(design("", "", false, Language.ARABIC), "x", PRICES,
                Optional.empty());
        String login = LoginPageBuilder.preview(files.get("login.html"));
        assertFalse(login.contains("$("), "no router variable left");
        assertFalse(login.contains("name=\"sendin\""), "no challenge without a router");
        assertTrue(login.contains("action=\"#\""));
        String status = LoginPageBuilder.preview(files.get("status.html"));
        assertTrue(status.contains("00:12:30") && status.contains("1h47m30s"));
        assertTrue(status.contains("<b class=\"bytes\">163577856</b>"), "bytes are made readable on the phone");
        assertFalse(status.contains("http-equiv=\"refresh\""));
    }

    @Test
    void pointsOfSaleGetTheirOwnTabWithACallButton() {
        LoginPageDesign d = new LoginPageDesign("", "", "", "", "#336699", false, false, false, Language.ARABIC,
                true);
        String login = LoginPageBuilder.build(d, "x", PRICES, java.util.List.of(
                new LoginPageBuilder.Seller("محل <علي>", "0599-111 222"), new LoginPageBuilder.Seller("كشك", "")),
                Optional.empty()).get("login.html");
        assertTrue(login.contains("data-tab=\"sellers\"") && !login.contains("data-tab=\"prices\""));
        assertTrue(login.contains("<span>محل &lt;علي&gt;</span><a href=\"tel:0599111222\""));
        assertTrue(login.contains("<span>كشك</span></div>"), "no number, no call button");
        String none = LoginPageBuilder.build(d, "x", Map.of(), java.util.List.of(), Optional.empty())
                .get("login.html");
        assertTrue(none.contains("لا توجد نقاط بيع بعد"));
        assertEquals("#1a334d", LoginPageBuilder.shade("#336699", 0.5));
        assertEquals("#99b3cc", LoginPageBuilder.shade("#336699", -0.5));
    }

    @Test
    void logosAreShrunkIntoThePage() throws Exception {
        Path big = dir.resolve("logo.png");
        ImageIO.write(new BufferedImage(1200, 400, BufferedImage.TYPE_INT_ARGB), "png", big.toFile());
        String uri = LoginPageBuilder.logo(big).orElseThrow();
        assertTrue(uri.startsWith("data:image/png;base64,"));
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder()
                .decode(uri.substring("data:image/png;base64,".length()))));
        assertEquals(LoginPageBuilder.LOGO_WIDTH, back.getWidth());
        assertEquals(120, back.getHeight(), "the shape is kept");

        Files.writeString(dir.resolve("not.png"), "text");
        assertEquals(Optional.empty(), LoginPageBuilder.logo(dir.resolve("not.png")));
        assertEquals(Optional.empty(), LoginPageBuilder.logo(dir.resolve("missing.png")));
        assertEquals(Optional.empty(), LoginPageBuilder.logo(null));
    }

    @Test
    void thePagesOwnLogoIsAPlainFileNameAndIsKept() throws Exception {
        LoginPageDesign own = new LoginPageDesign("", "", "", "", "#123456", true, true, false, Language.ARABIC, false,
                "login-page-logo.png");
        assertEquals("login-page-logo.png", own.logo());
        for (String bad : new String[]{"../secret.png", "C:\\x.png", "a/b.png", " ", "x".repeat(81)}) {
            assertEquals("", new LoginPageDesign("", "", "", "", "#123456", true, true, false, Language.ARABIC, false,
                    bad).logo(), "never a path out of the data folder: " + bad);
        }
        assertEquals("", LoginPageDesign.defaults().logo(), "the card design's logo by default");
        LoginPageStore store = new LoginPageStore(dir.resolve("logo.properties"));
        store.save(own);
        assertEquals("login-page-logo.png", new LoginPageStore(dir.resolve("logo.properties")).design().logo());
    }

    @Test
    void theDesignIsSavedAndEachRoutersPreviousFolderRemembered() throws Exception {
        LoginPageStore store = new LoginPageStore(dir.resolve("login-page.properties"));
        assertEquals(LoginPageDesign.defaults(), store.design());
        LoginPageDesign mine = new LoginPageDesign("  شبكتي  ", "عرض\nاليوم", "x0599", "+970-599", "red", false,
                false, true, Language.ENGLISH);
        assertEquals("شبكتي", mine.title());
        assertEquals("عرض اليوم", mine.welcome());
        assertEquals("0599", mine.phone());
        assertEquals("970599", mine.whatsApp());
        assertEquals(LoginPageDesign.DEFAULT_COLOR, mine.color(), "not a colour: the default");
        store.save(mine);
        assertEquals(mine, new LoginPageStore(dir.resolve("login-page.properties")).design());
        java.nio.file.Files.writeString(dir.resolve("old.properties"), "language=BOTH");
        assertEquals(Language.ARABIC, new LoginPageStore(dir.resolve("old.properties")).design().language(),
                "a page saved with both languages opens as Arabic");

        store.rememberPrevious("10.0.0.1:80", Map.of("hsprof1", "hotspot", "other", "flash/hotspot"));
        store.rememberPrevious("10.0.0.1:80", Map.of("hsprof1", "netpilot-hotspot")); // never overwritten
        store.rememberPrevious("10.0.0.2:80", Map.of("hsprof1", "x"));
        assertEquals(Map.of("hsprof1", "hotspot", "other", "flash/hotspot"), store.previous("10.0.0.1:80"));
        store.forgetPrevious("10.0.0.1:80");
        assertEquals(Map.of(), store.previous("10.0.0.1:80"));
        assertEquals(Map.of("hsprof1", "x"), store.previous("10.0.0.2:80"), "each router on its own");
    }
}
