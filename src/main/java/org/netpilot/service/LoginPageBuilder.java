package org.netpilot.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.netpilot.model.LoginPageDesign;
import org.netpilot.model.LoginPageDesign.Language;
import org.netpilot.model.Money;

/**
 * Builds the hotspot pages the router serves to customers: login (with the packages and the points
 * of sale on their own tabs), after login, status, logout and error, plus the MD5 script the login
 * needs. Everything is inside the files (styles, icons, the logo as an embedded picture), because
 * a customer has no internet before logging in.
 *
 * <p>The router replaces {@code $(...)} variables in these files; text the user typed has its
 * {@code $} escaped, so it can never become one. The login keeps MikroTik's CHAP: the card's
 * password is sent only as an MD5 answer to the router's challenge, never as plain text.
 */
public final class LoginPageBuilder {

    /** The folder on the router that holds these pages; the router's own pages stay where they are. */
    public static final String FOLDER = "netpilot-hotspot";

    private LoginPageBuilder() {
    }

    /** Whether a hotspot page folder is NetPilot's, with or without the {@code flash/} disk in front. */
    public static boolean isOurs(String folder) {
        return folder != null && (folder.equals(FOLDER) || folder.endsWith("/" + FOLDER));
    }

    /** A point of sale shown on the page: where customers can buy cards. */
    public record Seller(String name, String phone) {
    }

    /** Logos are shrunk to fit this box, so the page stays small and quick on a phone. */
    static final int LOGO_WIDTH = 360;
    static final int LOGO_HEIGHT = 180;

    /**
     * The logo as a small PNG {@code data:} URI for the page, or empty when there is no readable
     * picture.
     */
    public static Optional<String> logo(java.nio.file.Path file) {
        try {
            if (file == null || !java.nio.file.Files.isRegularFile(file)) {
                return Optional.empty();
            }
            java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(file.toFile());
            if (source == null) {
                return Optional.empty();
            }
            double scale = Math.min(1.0, Math.min((double) LOGO_WIDTH / source.getWidth(),
                    (double) LOGO_HEIGHT / source.getHeight()));
            int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
            java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(w, h,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = small.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(source, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(small, "png", png);
            return Optional.of("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(
                    png.toByteArray()));
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static final java.util.regex.Pattern CONDITION = java.util.regex.Pattern.compile(
            "\\$\\(if ([a-z-]+)\\)(.*?)\\$\\(endif\\)", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern VARIABLE = java.util.regex.Pattern.compile("\\$\\(([a-z-]+)\\)");

    /** Example values for the preview, as the router would fill them in. */
    private static final Map<String, String> EXAMPLE = Map.of("username", "", "uptime", "00:12:30",
            "session-time-left", "1h47m30s", "bytes-in-nice", "4.2 MiB", "bytes-out-nice", "156.0 MiB",
            "bytes-in", "4404019", "bytes-out", "163577856", "remain-bytes-total", "3758096384");

    /**
     * A page as a customer would see it, for the preview on this computer: the router's variables
     * filled with examples, its {@code $(if …)} parts shown only when they would show on a phone
     * (no error, no challenge, no automatic refresh).
     */
    public static String preview(String page) {
        String shown = CONDITION.matcher(page).replaceAll(m -> switch (m.group(1)) {
            case "session-time-left", "remain-bytes-total" -> java.util.regex.Matcher.quoteReplacement(m.group(2));
            default -> "";
        });
        return VARIABLE.matcher(shown).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                m.group(1).startsWith("link-") ? "#" : EXAMPLE.getOrDefault(m.group(1), "")));
    }

    /** The words of the pages in one language. */
    private record Words(String code, String password, String login, String remember, String tabLogin,
            String tabPrices, String tabSellers, String call, String whatsApp, String welcome, String loggedIn,
            String continueTo, String account, String timeLeft, String timeUsed, String download, String upload,
            String dataLeft, String unlimited, String logout, String logoutAsk, String loggedOut, String loginAgain,
            String error, String back, String card, String noSellers, String today) {
    }

    private static final Words ARABIC = new Words("رقم الكرت", "كلمة المرور", "دخول", "تذكّر الكرت على هذا الجهاز",
            "الدخول", "الباقات", "نقاط البيع", "اتصال", "واتساب", "أهلاً بك", "تم الدخول بنجاح", "ابدأ التصفح",
            "معلومات الحساب", "الوقت المتبقي", "مدة الاتصال", "التنزيل", "الرفع", "البيانات المتبقية", "غير محدود",
            "خروج", "هل تريد الخروج", "تم الخروج", "دخول من جديد", "تعذّر الدخول", "رجوع", "الكرت",
            "لا توجد نقاط بيع بعد", "اليوم");

    private static final Words ENGLISH = new Words("Card code", "Password", "Log in",
            "Remember the card on this device",
            "Log in", "Packages", "Points of sale", "Call", "WhatsApp", "Welcome", "You are connected",
            "Start browsing", "Your account", "Time left", "Connected for", "Download", "Upload", "Data left",
            "Unlimited", "Log out", "Log out now", "You are logged out", "Log in again", "Could not log in", "Back",
            "Card", "No points of sale yet", "Today");

    /** The pages, by file name, without points of sale. */
    public static Map<String, String> build(LoginPageDesign design, String networkName, Map<String, String> prices,
            Optional<String> logo) {
        return build(design, networkName, prices, List.of(), logo);
    }

    /**
     * The pages, by file name.
     *
     * @param networkName the card design's network name, when the design has no title of its own
     * @param prices      price text per package (as in Card design)
     * @param sellers     the points of sale, shown on their own tab when the design asks for them
     * @param logo        the logo as a {@code data:} URI, when it is shown
     */
    public static Map<String, String> build(LoginPageDesign design, String networkName, Map<String, String> prices,
            List<Seller> sellers, Optional<String> logo) {
        String title = design.title().isEmpty() ? networkName : design.title();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("login.html", login(design, title, prices, sellers, logo));
        files.put("alogin.html",
                page(design, title, logo, "<meta http-equiv=\"refresh\" content=\"3; url=$(link-redirect)\">",
                        """
                                <div class="badge ok">%CHECK%</div>
                                <h2>%LOGGED_IN%</h2>
                                <p class="muted">$(username)</p>
                                <a class="button" href="$(link-redirect)">%CONTINUE% %ARROW%</a>
                                <a class="ghost" href="$(link-status)">%ACCOUNT%</a>
                                """.replace("%LOGGED_IN%", label(design, Words::loggedIn))
                                .replace("%CONTINUE%", label(design, Words::continueTo))
                                .replace("%ACCOUNT%", label(design, Words::account))));
        files.put("status.html", page(design, title, logo,
                "$(if refresh-timeout)<meta http-equiv=\"refresh\" content=\"$(refresh-timeout-secs)\">$(endif)",
                """
                        <div class="hello">%USER% <b>$(username)</b></div>
                        <div class="tiles">
                        <div class="tile">%CLOCK%<span>%TIME_USED%</span><b>$(uptime)</b></div>
                        $(if session-time-left)<div class="tile hot">%HOURGLASS%<span>%TIME_LEFT%</span><b>$(session-time-left)</b></div>$(endif)
                        <div class="tile">%DOWN%<span>%DOWNLOAD%</span><b class="bytes">$(bytes-out)</b></div>
                        <div class="tile">%UP%<span>%UPLOAD%</span><b class="bytes">$(bytes-in)</b></div>
                        $(if remain-bytes-total)<div class="tile hot wide">%DATA%<span>%DATA_LEFT%</span><b class="bytes">$(remain-bytes-total)</b></div>$(endif)
                        </div>
                        <form action="$(link-logout)" name="logout" onsubmit="return confirm('%LOGOUT_ASK%?')">
                        <button type="submit" class="danger">%LOGOUT% %EXIT%</button>
                        </form>
                        """
                        .replace("%USER%", label(design, Words::card))
                        .replace("%TIME_USED%", label(design, Words::timeUsed))
                        .replace("%TIME_LEFT%", label(design, Words::timeLeft))
                        .replace("%DOWNLOAD%", label(design, Words::download))
                        .replace("%UPLOAD%", label(design, Words::upload))
                        .replace("%DATA_LEFT%", label(design, Words::dataLeft))
                        .replace("%LOGOUT_ASK%", first(design).logoutAsk())
                        .replace("%LOGOUT%", label(design, Words::logout))));
        files.put("logout.html",
                page(design, title, logo, "",
                        """
                                <div class="badge">%EXIT%</div>
                                <h2>%LOGGED_OUT%</h2>
                                <div class="tiles">
                                <div class="tile">%CARD%<span>%USER%</span><b>$(username)</b></div>
                                <div class="tile">%CLOCK%<span>%TIME_USED%</span><b>$(uptime)</b></div>
                                </div>
                                <a class="button" href="$(link-login)">%AGAIN% %ARROW%</a>
                                """.replace("%LOGGED_OUT%", label(design, Words::loggedOut))
                                .replace("%USER%", label(design, Words::card))
                                .replace("%TIME_USED%", label(design, Words::timeUsed))
                                .replace("%AGAIN%", label(design, Words::loginAgain))));
        files.put("error.html", page(design, title, logo, "", """
                <div class="badge bad">%WARN%</div>
                <h2>%ERROR%</h2>
                <p class="alert" id="error">$(error)</p>
                <a class="button" href="$(link-login-only)">%BACK%</a>
                """.replace("%ERROR%", label(design, Words::error)).replace("%BACK%", label(design, Words::back))
                + errorScript(design)));
        String redirect = page(design, title, logo, "<meta http-equiv=\"refresh\" content=\"0; url=$(link-redirect)\">",
                "<a class=\"button\" href=\"$(link-redirect)\">" + label(design, Words::login) + "</a>\n");
        files.put("rlogin.html", redirect);
        files.put("redirect.html", redirect);
        files.put("md5.js", MD5);
        return files;
    }

    private static String login(LoginPageDesign d, String title, Map<String, String> prices, List<Seller> sellers,
            Optional<String> logo) {
        Map<String, String> priced = new LinkedHashMap<>();
        prices.forEach((profile, price) -> {
            if (Money.parse(price).isPresent()) {
                priced.put(profile, price);
            }
        });
        boolean pricesTab = d.showPrices() && !priced.isEmpty();
        boolean sellersTab = d.showSellers();
        StringBuilder body = new StringBuilder();
        if (!d.welcome().isEmpty()) {
            body.append("<div class=\"ticker\"><span>").append(text(d.welcome())).append("</span></div>\n");
        }
        if (pricesTab || sellersTab) {
            body.append("<nav class=\"tabs\" id=\"tabs\">\n<a href=\"#login\" class=\"on\" data-tab=\"login\">")
                    .append(icon("key")).append(label(d, Words::tabLogin)).append("</a>\n");
            if (pricesTab) {
                body.append("<a href=\"#prices\" data-tab=\"prices\">").append(icon("tag"))
                        .append(label(d, Words::tabPrices)).append("</a>\n");
            }
            if (sellersTab) {
                body.append("<a href=\"#sellers\" data-tab=\"sellers\">").append(icon("pin"))
                        .append(label(d, Words::tabSellers)).append("</a>\n");
            }
            body.append("</nav>\n");
        }
        body.append("<section id=\"login\" class=\"panel on\">\n");
        body.append("$(if error)<p class=\"alert\" id=\"error\">$(error)</p>$(endif)\n");
        body.append(
                """
                        <form name="login" action="$(link-login-only)" method="post" $(if chap-id)onsubmit="return doLogin()"$(endif)>
                        <input type="hidden" name="dst" value="$(link-orig)">
                        <input type="hidden" name="popup" value="true">
                        <label class="field">%CARD_ICON%<input name="username" value="$(username)" placeholder="%CODE%"
                        autocomplete="off" autocapitalize="off" spellcheck="false" required></label>
                        """
                        .replace("%CARD_ICON%", icon("card")).replace("%CODE%", label(d, Words::code)));
        if (d.passwordField()) {
            body.append("<label class=\"field\">").append(icon("lock"))
                    .append("<input name=\"password\" type=\"password\" placeholder=\"")
                    .append(label(d, Words::password)).append("\" autocomplete=\"off\"></label>\n");
        } else {
            body.append("<input name=\"password\" type=\"hidden\" value=\"\">\n");
        }
        body.append("<label class=\"remember\"><input type=\"checkbox\" id=\"remember\"> ")
                .append(label(d, Words::remember)).append("</label>\n");
        body.append("<button type=\"submit\">").append(label(d, Words::login)).append(" ").append(icon("arrow"))
                .append("</button>\n</form>\n</section>\n");
        if (pricesTab) {
            body.append("<section id=\"prices\" class=\"panel\">\n<div class=\"plans\">\n");
            priced.forEach((profile, price) -> body.append("<div class=\"plan\"><span>").append(text(profile))
                    .append("</span><b>").append(text(price)).append("</b></div>\n"));
            body.append("</div>\n</section>\n");
        }
        if (sellersTab) {
            body.append("<section id=\"sellers\" class=\"panel\">\n");
            if (sellers.isEmpty()) {
                body.append("<p class=\"muted\">").append(label(d, Words::noSellers)).append("</p>\n");
            }
            for (Seller s : sellers) {
                String phone = s.phone() == null ? "" : s.phone().replaceAll("[^0-9+]", "");
                body.append("<div class=\"seller\">").append(icon("pin")).append("<span>").append(text(s.name()))
                        .append("</span>");
                if (!phone.isEmpty()) {
                    body.append("<a href=\"tel:").append(phone).append("\" class=\"round\" aria-label=\"")
                            .append(label(d, Words::call)).append("\">").append(icon("phone")).append("</a>");
                }
                body.append("</div>\n");
            }
            body.append("</section>\n");
        }
        if (!d.phone().isEmpty() || !d.whatsApp().isEmpty()) {
            body.append("<div class=\"contact\">");
            if (!d.phone().isEmpty()) {
                body.append("<a href=\"tel:").append(d.phone()).append("\">").append(icon("phone"))
                        .append(label(d, Words::call)).append("</a>");
            }
            if (!d.whatsApp().isEmpty()) {
                body.append("<a href=\"https://wa.me/").append(d.whatsApp()).append("\" class=\"wa\">")
                        .append(icon("chat")).append(label(d, Words::whatsApp)).append("</a>");
            }
            body.append("</div>\n");
        }
        body.append(
                """
                        $(if chap-id)
                        <form name="sendin" action="$(link-login-only)" method="post" style="display:none">
                        <input type="hidden" name="username"><input type="hidden" name="password">
                        <input type="hidden" name="dst" value="$(link-orig)"><input type="hidden" name="popup" value="true">
                        </form>
                        <script src="md5.js"></script>
                        <script>
                        function doLogin() {
                          rememberCard();
                          document.sendin.username.value = document.login.username.value;
                          document.sendin.password.value = hexMD5('$(chap-id)' + document.login.password.value + '$(chap-challenge)');
                          document.sendin.submit();
                          return false;
                        }
                        </script>
                        $(endif)
                        <script>
                        function rememberCard() {
                          try {
                            var on = document.getElementById('remember').checked;
                            if (on) { localStorage.setItem('np-card', document.login.username.value); }
                            else { localStorage.removeItem('np-card'); }
                          } catch (e) { }
                        }
                        (function () {
                          try {
                            var saved = localStorage.getItem('np-card');
                            if (saved && !document.login.username.value) {
                              document.login.username.value = saved;
                              document.getElementById('remember').checked = true;
                            }
                          } catch (e) { }
                          if (!document.login.onsubmit) { document.login.onsubmit = function () { rememberCard(); return true; }; }
                          var tabs = document.getElementById('tabs');
                          if (!tabs) { return; }
                          var links = tabs.getElementsByTagName('a');
                          for (var i = 0; i < links.length; i++) {
                            links[i].onclick = function () {
                              for (var j = 0; j < links.length; j++) {
                                links[j].className = '';
                                document.getElementById(links[j].getAttribute('data-tab')).className = 'panel';
                              }
                              this.className = 'on';
                              document.getElementById(this.getAttribute('data-tab')).className = 'panel on';
                              return false;
                            };
                          }
                        })();
                        </script>
                        """);
        body.append(errorScript(d));
        return page(d, title, logo, "", body.toString());
    }

    /** The router's error messages are English; in Arabic the common ones are said in Arabic. */
    private static String errorScript(LoginPageDesign d) {
        if (d.language() == Language.ENGLISH) {
            return "";
        }
        return """
                <script>
                (function () {
                  var e = document.getElementById('error');
                  if (!e) { return; }
                  var t = e.textContent.toLowerCase();
                  var says = [
                    ['invalid username or password', 'رقم الكرت أو كلمة المرور غير صحيح'],
                    ['invalid password', 'رقم الكرت أو كلمة المرور غير صحيح'],
                    ['no such user', 'رقم الكرت غير صحيح'],
                    ['uptime limit', 'انتهى وقت هذا الكرت'],
                    ['traffic limit', 'انتهت بيانات هذا الكرت'],
                    ['session time', 'انتهى وقت هذا الكرت'],
                    ['no more sessions', 'هذا الكرت مستخدَم الآن على جهاز آخر'],
                    ['already logged in', 'هذا الكرت مستخدَم الآن على جهاز آخر'],
                    ['disabled', 'هذا الكرت موقوف'],
                    ['expired', 'انتهت صلاحية هذا الكرت']
                  ];
                  for (var i = 0; i < says.length; i++) {
                    if (t.indexOf(says[i][0]) >= 0) {
                      e.textContent = says[i][1];
                      return;
                    }
                  }
                })();
                </script>
                """;
    }

    /** Small line icons, drawn inline: nothing to download. */
    private static String icon(String name) {
        String path = switch (name) {
            case "card" -> "M3 6h18v12H3z M3 10h18 M7 15h4";
            case "lock" -> "M6 11h12v9H6z M9 11V8a3 3 0 0 1 6 0v3";
            case "key" -> "M14 10a4 4 0 1 0-4 4l1 1h2v2h2v2h3v-3l-4-4";
            case "tag" -> "M3 12V4h8l9 9-8 8z M7.5 8.5h.01";
            case "pin" -> "M12 21s-6-5.5-6-11a6 6 0 0 1 12 0c0 5.5-6 11-6 11z M12 12a2 2 0 1 0 0-4 2 2 0 0 0 0 4z";
            case "phone" ->
                "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2";
            case "chat" -> "M4 20l1.5-4A8 8 0 1 1 9 19.5z";
            case "arrow" -> "M5 12h14 M13 6l6 6-6 6";
            case "check" -> "M5 12l5 5L20 7";
            case "exit" -> "M15 4h4v16h-4 M10 8l-4 4 4 4 M6 12h10";
            case "warn" -> "M12 4l9 16H3z M12 10v4 M12 17h.01";
            case "clock" -> "M12 7v5l3 2 M3 12a9 9 0 1 0 18 0 9 9 0 1 0-18 0";
            case "hourglass" -> "M7 3h10 M7 21h10 M8 3c0 5 8 5 8 9s-8 4-8 9 M16 3c0 5-8 5-8 9";
            case "down" -> "M12 4v12 M6 11l6 6 6-6 M5 20h14";
            case "up" -> "M12 20V8 M6 13l6-6 6 6 M5 4h14";
            case "data" ->
                "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6 M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6";
            default -> "";
        };
        return "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path d=\"" + path + "\"/></svg>";
    }

    /** A whole page: the shared head, styles, logo, title and clock, then {@code body}. */
    private static String page(LoginPageDesign d, String title, Optional<String> logo, String head, String body) {
        boolean rtl = d.language() != Language.ENGLISH;
        String logoTag = d.showLogo()
                ? logo.map(src -> "<img class=\"logo\" alt=\"\" src=\"" + src + "\">").orElse("")
                : "";
        String filled = body.replace("%CHECK%", icon("check")).replace("%ARROW%", icon("arrow"))
                .replace("%EXIT%", icon("exit")).replace("%WARN%", icon("warn")).replace("%CLOCK%", icon("clock"))
                .replace("%HOURGLASS%", icon("hourglass")).replace("%DOWN%", icon("down")).replace("%UP%", icon("up"))
                .replace("%DATA%", icon("data")).replace("%CARD%", icon("card"));
        return PAGE.replace("%LANG%", rtl ? "ar" : "en").replace("%DIR%", rtl ? "rtl" : "ltr").replace("%HEAD%", head)
                .replace("%MAIN%", d.color()).replace("%DARK%", shade(d.color(), 0.45))
                .replace("%LIGHT%", shade(d.color(), -0.82)).replace("%TITLE%", text(title))
                .replace("%LOGO%", logoTag).replace("%LOCALE%", rtl ? "ar" : "en")
                .replace("%UNLIMITED%", first(d).unlimited()).replace("%BODY%", filled);
    }

    /**
     * The colour made darker ({@code amount} above 0) or lighter (below 0), for the background and
     * the soft tints.
     */
    static String shade(String color, double amount) {
        int r = Integer.parseInt(color.substring(1, 3), 16);
        int g = Integer.parseInt(color.substring(3, 5), 16);
        int b = Integer.parseInt(color.substring(5, 7), 16);
        java.util.function.IntUnaryOperator mix = amount >= 0
                ? c -> (int) Math.round(c * (1 - amount))
                : c -> (int) Math.round(c + (255 - c) * -amount);
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", mix.applyAsInt(r), mix.applyAsInt(g),
                mix.applyAsInt(b));
    }

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="%LANG%" dir="%DIR%">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <meta name="theme-color" content="%DARK%">
            <meta http-equiv="pragma" content="no-cache">
            <meta http-equiv="expires" content="-1">
            %HEAD%
            <title>%TITLE%</title>
            <style>
            :root { --main: %MAIN%; --dark: %DARK%; --light: %LIGHT%; }
            * { box-sizing: border-box; margin: 0; }
            html, body { min-height: 100%; }
            body { font-family: "Segoe UI", Tahoma, "Noto Sans Arabic", "Geeza Pro", Roboto, Arial, sans-serif;
              color: #1f2328; background: var(--dark); display: flex; justify-content: center; align-items: center;
              padding: 22px 14px; min-height: 100vh; position: relative; overflow-x: hidden; }
            body::before, body::after { content: ""; position: fixed; border-radius: 50%; filter: blur(60px);
              opacity: .55; z-index: 0; }
            body::before { width: 70vmax; height: 70vmax; background: var(--main); top: -30vmax; inset-inline-start: -25vmax; }
            body::after { width: 55vmax; height: 55vmax; background: var(--light); bottom: -28vmax; inset-inline-end: -22vmax;
              opacity: .35; }
            .wrap { width: 100%; max-width: 400px; position: relative; z-index: 1; }
            main { position: relative; z-index: 1; width: 100%; max-width: 400px; border-radius: 26px; overflow: hidden;
              background: rgba(255, 255, 255, .94); box-shadow: 0 24px 60px rgba(0, 0, 0, .35), 0 0 0 1px rgba(255, 255, 255, .4);
              -webkit-backdrop-filter: blur(12px); backdrop-filter: blur(12px); animation: rise .5s ease-out; }
            @keyframes rise { from { opacity: 0; transform: translateY(18px) scale(.98); } to { opacity: 1; transform: none; } }
            header { background: linear-gradient(135deg, var(--main), var(--dark)); color: #fff; text-align: center;
              padding: 22px 20px 30px; position: relative; }
            header::after { content: ""; position: absolute; inset-inline: 0; bottom: -1px; height: 22px;
              background: rgba(255, 255, 255, .94); border-radius: 22px 22px 0 0; }
            .logo { display: block; max-width: 62%; max-height: 92px; margin: 0 auto 10px; background: #fff;
              border-radius: 16px; padding: 6px 10px; box-shadow: 0 6px 18px rgba(0, 0, 0, .2); }
            h1 { font-size: 22px; letter-spacing: .3px; }
            .clock { margin-top: 6px; font-size: 13px; opacity: .85; direction: ltr; unicode-bidi: plaintext; }
            .content { padding: 4px 22px 24px; text-align: center; }
            h2 { font-size: 19px; margin: 6px 0 6px; }
            .muted { color: #6e7781; font-size: 14px; margin-bottom: 8px; }
            svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round;
              stroke-linejoin: round; vertical-align: middle; flex: none; }
            .ticker { overflow: hidden; white-space: nowrap; background: var(--light); color: var(--dark); border-radius: 999px;
              padding: 8px 0; margin: 4px 0 14px; font-size: 14px; font-weight: bold; }
            .ticker span { display: inline-block; padding-inline-start: 100%; animation: move 14s linear infinite; }
            [dir="rtl"] .ticker span { animation-name: move-rtl; }
            @keyframes move { to { transform: translateX(-100%); } }
            @keyframes move-rtl { to { transform: translateX(100%); } }
            .tabs { display: flex; gap: 4px; background: #f0f2f5; border-radius: 14px; padding: 4px; margin-bottom: 16px; }
            .tabs a { flex: 1; display: flex; align-items: center; justify-content: center; gap: 6px; padding: 9px 4px;
              border-radius: 11px; color: #57606a; text-decoration: none; font-size: 13px; font-weight: bold;
              white-space: nowrap; min-width: 0; }
            .tabs a.on { background: #fff; color: var(--main); box-shadow: 0 2px 8px rgba(0, 0, 0, .1); }
            .tabs svg { width: 17px; height: 17px; }
            .panel { display: none; } .panel.on { display: block; }
            .field { display: flex; align-items: center; gap: 10px; background: #f6f8fa; border: 2px solid #e6e9ed;
              border-radius: 16px; padding: 4px 14px; margin-bottom: 12px; color: #8c959f; transition: border-color .2s, box-shadow .2s; }
            .field:focus-within { border-color: var(--main); box-shadow: 0 0 0 4px rgba(0, 0, 0, .04); color: var(--main);
              background: #fff; }
            .field input { flex: 1; min-width: 0; border: 0; background: transparent; padding: 12px 0; font-size: 18px;
              font-weight: bold; letter-spacing: 1px; text-align: center; direction: ltr; color: #1f2328; outline: none; }
            .field input::placeholder { font-weight: normal; letter-spacing: 0; color: #8c959f; font-size: 15px; }
            .remember { display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 13px;
              color: #6e7781; margin: 2px 0 6px; }
            .remember input { width: 17px; height: 17px; accent-color: var(--main); }
            button, .button { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%;
              margin-top: 12px; padding: 14px; font: inherit; font-size: 17px; font-weight: bold; color: #fff; border: 0;
              border-radius: 16px; cursor: pointer; text-decoration: none;
              background: linear-gradient(135deg, var(--main), var(--dark)); box-shadow: 0 10px 22px rgba(0, 0, 0, .22); }
            button:active, .button:active { transform: scale(.98); }
            [dir="rtl"] button svg, [dir="rtl"] .button svg { transform: scaleX(-1); }
            .danger { background: linear-gradient(135deg, #e5534b, #a40e26); }
            .ghost { display: inline-block; margin-top: 12px; color: var(--main); text-decoration: none; font-weight: bold;
              font-size: 14px; }
            .alert { background: #ffebe9; color: #cf222e; border-radius: 14px; padding: 11px 12px; font-size: 14px;
              font-weight: bold; margin-bottom: 12px; animation: shake .4s; }
            @keyframes shake { 25% { transform: translateX(-6px); } 75% { transform: translateX(6px); } }
            .plans { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
            .plan { background: #f6f8fa; border: 1px solid #e6e9ed; border-radius: 16px; padding: 14px 8px; }
            .plan span { display: block; color: #57606a; font-size: 14px; }
            .plan b { display: block; margin-top: 6px; font-size: 20px; color: var(--main); }
            .seller { display: flex; align-items: center; gap: 10px; text-align: start; background: #f6f8fa; border-radius: 14px;
              padding: 10px 12px; margin-bottom: 8px; color: var(--main); }
            .seller span { flex: 1; color: #1f2328; font-weight: bold; font-size: 15px; }
            .round { display: inline-flex; width: 38px; height: 38px; border-radius: 50%; align-items: center;
              justify-content: center; background: var(--main); color: #fff; }
            .contact { display: flex; gap: 10px; margin-top: 16px; }
            .contact a { flex: 1; display: flex; align-items: center; justify-content: center; gap: 6px; padding: 11px;
              border-radius: 14px; background: #f0f2f5; color: #1f2328; text-decoration: none; font-weight: bold; font-size: 14px; }
            .contact a.wa { background: #e7f8ee; color: #128c4a; }
            .badge { width: 72px; height: 72px; margin: 4px auto 10px; border-radius: 50%; display: flex; align-items: center;
              justify-content: center; background: var(--light); color: var(--dark); animation: pop .5s ease-out; }
            .badge svg { width: 38px; height: 38px; stroke-width: 2.2; }
            .badge.ok { background: #dafbe1; color: #1a7f37; } .badge.bad { background: #ffebe9; color: #cf222e; }
            @keyframes pop { from { transform: scale(.4); opacity: 0; } 70% { transform: scale(1.1); } to { transform: none; } }
            .hello { font-size: 15px; color: #57606a; margin: 4px 0 14px; } .hello b { color: #1f2328; direction: ltr;
              unicode-bidi: embed; }
            .tiles { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 6px; }
            .tile { background: #f6f8fa; border-radius: 16px; padding: 12px 8px; color: var(--main); }
            .tile.hot { background: var(--light); color: var(--dark); } .tile.wide { grid-column: span 2; }
            .tile span { display: block; color: #6e7781; font-size: 12px; margin-top: 4px; }
            .tile b { display: block; color: #1f2328; font-size: 16px; margin-top: 2px; direction: ltr; }
            footer { text-align: center; font-size: 11px; color: rgba(255, 255, 255, .55); margin-top: 14px; position: relative;
              z-index: 1; }
            </style>
            </head>
            <body>
            <div class="wrap">
            <main>
            <header>%LOGO%<h1>%TITLE%</h1><div class="clock" id="clock"></div></header>
            <div class="content">
            %BODY%
            </div>
            </main>
            </div>
            <script>
            (function () {
              var c = document.getElementById('clock');
              function tick() {
                try {
                  c.textContent = new Date().toLocaleString('%LOCALE%', { weekday: 'long', hour: '2-digit', minute: '2-digit' });
                } catch (e) { c.textContent = new Date().toLocaleTimeString(); }
              }
              tick(); setInterval(tick, 30000);
              var units = ['B', 'KB', 'MB', 'GB', 'TB'];
              var shown = document.querySelectorAll ? document.querySelectorAll('.bytes') : [];
              for (var i = 0; i < shown.length; i++) {
                var n = parseFloat(shown[i].textContent), u = 0;
                if (isNaN(n)) { continue; }
                if (n <= 0 && shown[i].parentNode.className.indexOf('hot') >= 0) { shown[i].textContent = '%UNLIMITED%'; continue; }
                while (n >= 1024 && u < units.length - 1) { n /= 1024; u++; }
                shown[i].textContent = (u ? n.toFixed(1) : n) + ' ' + units[u];
              }
            })();
            </script>
            </body>
            </html>
            """;

    private static Words first(LoginPageDesign d) {
        return d.language() == Language.ENGLISH ? ENGLISH : ARABIC;
    }

    /** A label in the page's language. */
    private static String label(LoginPageDesign d, java.util.function.Function<Words, String> word) {
        return switch (d.language()) {
            case ARABIC -> word.apply(ARABIC);
            case ENGLISH -> word.apply(ENGLISH);
        };
    }

    /**
     * Text the user typed, safe in a page: HTML markup escaped and {@code $} escaped, so the router
     * never reads it as one of its variables.
     */
    static String text(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;").replace("$", "&#36;");
    }

    /** MD5 for CHAP, on the bytes of a string (the router's challenge holds bytes 0-255). */
    static final String MD5 = """
            function md5cycle(x, k) {
              var a = x[0], b = x[1], c = x[2], d = x[3];
              a = ff(a, b, c, d, k[0], 7, -680876936); d = ff(d, a, b, c, k[1], 12, -389564586);
              c = ff(c, d, a, b, k[2], 17, 606105819); b = ff(b, c, d, a, k[3], 22, -1044525330);
              a = ff(a, b, c, d, k[4], 7, -176418897); d = ff(d, a, b, c, k[5], 12, 1200080426);
              c = ff(c, d, a, b, k[6], 17, -1473231341); b = ff(b, c, d, a, k[7], 22, -45705983);
              a = ff(a, b, c, d, k[8], 7, 1770035416); d = ff(d, a, b, c, k[9], 12, -1958414417);
              c = ff(c, d, a, b, k[10], 17, -42063); b = ff(b, c, d, a, k[11], 22, -1990404162);
              a = ff(a, b, c, d, k[12], 7, 1804603682); d = ff(d, a, b, c, k[13], 12, -40341101);
              c = ff(c, d, a, b, k[14], 17, -1502002290); b = ff(b, c, d, a, k[15], 22, 1236535329);
              a = gg(a, b, c, d, k[1], 5, -165796510); d = gg(d, a, b, c, k[6], 9, -1069501632);
              c = gg(c, d, a, b, k[11], 14, 643717713); b = gg(b, c, d, a, k[0], 20, -373897302);
              a = gg(a, b, c, d, k[5], 5, -701558691); d = gg(d, a, b, c, k[10], 9, 38016083);
              c = gg(c, d, a, b, k[15], 14, -660478335); b = gg(b, c, d, a, k[4], 20, -405537848);
              a = gg(a, b, c, d, k[9], 5, 568446438); d = gg(d, a, b, c, k[14], 9, -1019803690);
              c = gg(c, d, a, b, k[3], 14, -187363961); b = gg(b, c, d, a, k[8], 20, 1163531501);
              a = gg(a, b, c, d, k[13], 5, -1444681467); d = gg(d, a, b, c, k[2], 9, -51403784);
              c = gg(c, d, a, b, k[7], 14, 1735328473); b = gg(b, c, d, a, k[12], 20, -1926607734);
              a = hh(a, b, c, d, k[5], 4, -378558); d = hh(d, a, b, c, k[8], 11, -2022574463);
              c = hh(c, d, a, b, k[11], 16, 1839030562); b = hh(b, c, d, a, k[14], 23, -35309556);
              a = hh(a, b, c, d, k[1], 4, -1530992060); d = hh(d, a, b, c, k[4], 11, 1272893353);
              c = hh(c, d, a, b, k[7], 16, -155497632); b = hh(b, c, d, a, k[10], 23, -1094730640);
              a = hh(a, b, c, d, k[13], 4, 681279174); d = hh(d, a, b, c, k[0], 11, -358537222);
              c = hh(c, d, a, b, k[3], 16, -722521979); b = hh(b, c, d, a, k[6], 23, 76029189);
              a = hh(a, b, c, d, k[9], 4, -640364487); d = hh(d, a, b, c, k[12], 11, -421815835);
              c = hh(c, d, a, b, k[15], 16, 530742520); b = hh(b, c, d, a, k[2], 23, -995338651);
              a = ii(a, b, c, d, k[0], 6, -198630844); d = ii(d, a, b, c, k[7], 10, 1126891415);
              c = ii(c, d, a, b, k[14], 15, -1416354905); b = ii(b, c, d, a, k[5], 21, -57434055);
              a = ii(a, b, c, d, k[12], 6, 1700485571); d = ii(d, a, b, c, k[3], 10, -1894986606);
              c = ii(c, d, a, b, k[10], 15, -1051523); b = ii(b, c, d, a, k[1], 21, -2054922799);
              a = ii(a, b, c, d, k[8], 6, 1873313359); d = ii(d, a, b, c, k[15], 10, -30611744);
              c = ii(c, d, a, b, k[6], 15, -1560198380); b = ii(b, c, d, a, k[13], 21, 1309151649);
              a = ii(a, b, c, d, k[4], 6, -145523070); d = ii(d, a, b, c, k[11], 10, -1120210379);
              c = ii(c, d, a, b, k[2], 15, 718787259); b = ii(b, c, d, a, k[9], 21, -343485551);
              x[0] = add32(a, x[0]); x[1] = add32(b, x[1]); x[2] = add32(c, x[2]); x[3] = add32(d, x[3]);
            }
            function cmn(q, a, b, x, s, t) { a = add32(add32(a, q), add32(x, t)); return add32((a << s) | (a >>> (32 - s)), b); }
            function ff(a, b, c, d, x, s, t) { return cmn((b & c) | ((~b) & d), a, b, x, s, t); }
            function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & (~d)), a, b, x, s, t); }
            function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
            function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | (~d)), a, b, x, s, t); }
            function md5blk(s) {
              var b = [], i;
              for (i = 0; i < 64; i += 4) {
                b[i >> 2] = (s.charCodeAt(i) & 255) + ((s.charCodeAt(i + 1) & 255) << 8)
                  + ((s.charCodeAt(i + 2) & 255) << 16) + ((s.charCodeAt(i + 3) & 255) << 24);
              }
              return b;
            }
            function md51(s) {
              var n = s.length, state = [1732584193, -271733879, -1732584194, 271733878], i;
              for (i = 64; i <= n; i += 64) { md5cycle(state, md5blk(s.substring(i - 64, i))); }
              s = s.substring(i - 64);
              var tail = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
              for (i = 0; i < s.length; i++) { tail[i >> 2] |= (s.charCodeAt(i) & 255) << ((i % 4) << 3); }
              tail[i >> 2] |= 0x80 << ((i % 4) << 3);
              if (i > 55) { md5cycle(state, tail); for (i = 0; i < 16; i++) { tail[i] = 0; } }
              tail[14] = n * 8;
              md5cycle(state, tail);
              return state;
            }
            var hexChars = '0123456789abcdef'.split('');
            function rhex(n) {
              var s = '', j = 0;
              for (; j < 4; j++) { s += hexChars[(n >> (j * 8 + 4)) & 15] + hexChars[(n >> (j * 8)) & 15]; }
              return s;
            }
            function add32(a, b) { return (a + b) & 0xFFFFFFFF; }
            function hexMD5(s) {
              var x = md51(s), i;
              for (i = 0; i < x.length; i++) { x[i] = rhex(x[i]); }
              return x.join('');
            }
            """;
}
