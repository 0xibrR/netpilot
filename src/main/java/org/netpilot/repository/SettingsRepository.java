package org.netpilot.repository;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.netpilot.i18n.Messages;
import org.netpilot.model.AppSettings;
import org.netpilot.model.CardImage;
import org.netpilot.model.CardSize;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.PasswordMode;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterSettings;
import org.netpilot.model.Theme;
import org.netpilot.model.UiLanguage;
import org.netpilot.model.UiPreferences;
import org.netpilot.model.UsernameMode;
import org.netpilot.model.VoucherStyle;
import org.netpilot.model.VoucherTemplate;

/**
 * Stores {@link AppSettings} in a properties file. Enums are stored by name, and every
 * missing or broken value falls back to its default on its own.
 */
public class SettingsRepository {

    private static final System.Logger LOG = System.getLogger(SettingsRepository.class.getName());

    public static final String DEFAULT_LOGIN_URL = "http://10.10.0.1/login";
    public static final int DEFAULT_PER_ROW = 5;

    private static final String TRUST_PREFIX = "router.trustedCertificate.";
    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");
    private static final String PRICE_PREFIX = "voucher.price.";
    private static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;
    private static final long MAX_CARD_IMAGE_BYTES = 5L * 1024 * 1024;

    private final Path file;

    public SettingsRepository(Path file) {
        this.file = file;
    }

    public AppSettings defaults() {
        return new AppSettings(GenerationSettings.defaults(), DEFAULT_LOGIN_URL, DEFAULT_PER_ROW,
                AppPaths.defaultOutputDirectory(), RouterSettings.defaults(), VoucherTemplate.defaults(),
                UiPreferences.defaults());
    }

    /**
     * Copies the user's own card picture next to the settings, like {@link #storeLogo}.
     *
     * @throws IllegalArgumentException if the file is not a PNG/JPEG image of at most 5 MB
     */
    public Path storeCardImage(Path source) throws IOException {
        return storePicture(source, "voucher-background", MAX_CARD_IMAGE_BYTES);
    }

    /**
     * Copies a logo image next to the settings, so the template keeps working if the original
     * file is moved or deleted.
     *
     * @return the copy's path, to put in the template
     * @throws IllegalArgumentException if the file is not a PNG/JPEG image of at most 2 MB
     */
    public Path storeLogo(Path source) throws IOException {
        return storePicture(source, "voucher-logo", MAX_LOGO_BYTES);
    }

    private Path storePicture(Path source, String baseName, long maxBytes) throws IOException {
        Path fileName = source.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        String extension = name.endsWith(".png") ? "png" : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "jpg" : "";
        if (extension.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("logo.notImage"));
        }
        if (Files.size(source) > maxBytes) {
            throw new IllegalArgumentException(Messages.get("logo.tooBig", maxBytes / (1024 * 1024)));
        }
        FileSupport.createParentDirectories(file);
        Path target = file.resolveSibling(baseName + "." + extension);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public AppSettings load() {
        if (!Files.exists(file)) {
            return defaults();
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read settings, using defaults", e);
            return defaults();
        }

        AppSettings d = defaults();
        GenerationSettings g = d.generation();
        GenerationSettings generation = new GenerationSettings(
                intValue(p, "count", g.count()),
                enumValue(p, "usernameMode", UsernameMode.class, g.usernameMode()),
                intValue(p, "usernameLength", g.usernameLength()),
                intValue(p, "usernameStart", g.usernameStart()),
                enumValue(p, "passwordMode", PasswordMode.class, g.passwordMode()),
                intValue(p, "passwordLength", g.passwordLength()),
                intValue(p, "passwordStart", g.passwordStart()),
                p.getProperty("passwordPrefix", g.passwordPrefix()),
                p.getProperty("profile", g.profile()),
                p.getProperty("server", g.server()),
                p.getProperty("limitUptime", g.limitUptime()));

        Path output = d.outputDirectory();
        String outputValue = p.getProperty("outputDirectory");
        if (outputValue != null && !outputValue.isBlank()) {
            try {
                output = Path.of(outputValue);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Invalid output directory in settings: " + outputValue);
            }
        }

        return new AppSettings(
                generation,
                p.getProperty("loginUrl", d.loginUrl()),
                intValue(p, "vouchersPerRow", d.vouchersPerRow()),
                output,
                loadRouter(p, d.router()),
                loadTemplate(p, d.voucherTemplate()),
                new UiPreferences(enumValue(p, "ui.language", UiLanguage.class, d.ui().language()),
                        enumValue(p, "ui.theme", Theme.class, d.ui().theme()),
                        p.getProperty("ui.accent", d.ui().accent())));
    }

    private static VoucherTemplate loadTemplate(Properties p, VoucherTemplate d) {
        Map<String, String> prices = new HashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(PRICE_PREFIX)) {
                prices.put(key.substring(PRICE_PREFIX.length()), p.getProperty(key));
            }
        }
        Path logo = existingFile(p.getProperty("voucher.logo", ""));
        CardImage di = d.image();
        CardImage image = new CardImage(existingFile(p.getProperty("voucher.image.file", "")),
                spot(p, "voucher.image.user", di.username()),
                spot(p, "voucher.image.pass", di.password()),
                spot(p, "voucher.image.qr", di.qr()),
                spot(p, "voucher.image.price", di.price()),
                p.getProperty("voucher.image.color", di.textColor()),
                doubleValue(p, "voucher.image.scale", di.scale()),
                booleanValue(p, "voucher.image.textBox", di.textBox()));
        return new VoucherTemplate(
                p.getProperty("voucher.networkName", d.networkName()),
                p.getProperty("voucher.note", d.note()),
                enumValue(p, "voucher.language", VoucherTemplate.Language.class, d.language()),
                p.getProperty("voucher.accentColor", d.accentColor()),
                prices,
                booleanValue(p, "voucher.showQr", d.showQr()),
                booleanValue(p, "voucher.showUptime", d.showUptime()),
                booleanValue(p, "voucher.showPrice", d.showPrice()),
                booleanValue(p, "voucher.showProfile", d.showProfile()),
                logo,
                enumValue(p, "voucher.style", VoucherStyle.class, d.style()),
                enumValue(p, "voucher.size", CardSize.class, d.size()),
                image);
    }

    /** A picture that was deleted or moved is simply dropped. */
    private static Path existingFile(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path candidate = Path.of(value);
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Invalid picture path in settings: " + value);
            return null;
        }
    }

    private static CardImage.Spot spot(Properties p, String key, CardImage.Spot fallback) {
        return new CardImage.Spot(doubleValue(p, key + ".x", fallback.x()), doubleValue(p, key + ".y", fallback.y()));
    }

    private static double doubleValue(Properties p, String key, double fallback) {
        try {
            return Double.parseDouble(p.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void putSpot(Properties p, String key, CardImage.Spot spot) {
        p.setProperty(key + ".x", String.valueOf(spot.x()));
        p.setProperty(key + ".y", String.valueOf(spot.y()));
    }

    /** Router password is never part of the settings; only the endpoint and trusted certificates are. */
    private static RouterSettings loadRouter(Properties p, RouterSettings defaults) {
        RouterEndpoint d = defaults.lastEndpoint();
        RouterEndpoint endpoint = new RouterEndpoint(
                p.getProperty("router.host", d.host()),
                intValue(p, "router.port", d.port()),
                Boolean.parseBoolean(p.getProperty("router.https", String.valueOf(d.https()))),
                p.getProperty("router.username", d.username()));

        Map<String, String> trusted = new HashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(TRUST_PREFIX)) {
                String fingerprint = p.getProperty(key).strip();
                if (FINGERPRINT.matcher(fingerprint).matches()) {
                    trusted.put(key.substring(TRUST_PREFIX.length()), fingerprint);
                }
            }
        }
        return new RouterSettings(endpoint, trusted);
    }

    public void save(AppSettings settings) throws IOException {
        GenerationSettings g = settings.generation();
        Properties p = new Properties();
        p.setProperty("count", String.valueOf(g.count()));
        p.setProperty("usernameMode", g.usernameMode().name());
        p.setProperty("usernameLength", String.valueOf(g.usernameLength()));
        p.setProperty("usernameStart", String.valueOf(g.usernameStart()));
        p.setProperty("passwordMode", g.passwordMode().name());
        p.setProperty("passwordLength", String.valueOf(g.passwordLength()));
        p.setProperty("passwordStart", String.valueOf(g.passwordStart()));
        p.setProperty("passwordPrefix", g.passwordPrefix());
        p.setProperty("profile", g.profile());
        p.setProperty("server", g.server());
        p.setProperty("limitUptime", g.limitUptime());
        p.setProperty("loginUrl", settings.loginUrl());
        p.setProperty("vouchersPerRow", String.valueOf(settings.vouchersPerRow()));
        p.setProperty("outputDirectory", settings.outputDirectory().toString());

        RouterEndpoint router = settings.router().lastEndpoint();
        p.setProperty("router.host", router.host());
        p.setProperty("router.port", String.valueOf(router.port()));
        p.setProperty("router.https", String.valueOf(router.https()));
        p.setProperty("router.username", router.username());
        settings.router().trustedFingerprints()
                .forEach((address, fingerprint) -> p.setProperty(TRUST_PREFIX + address, fingerprint));

        VoucherTemplate t = settings.voucherTemplate();
        p.setProperty("voucher.networkName", t.networkName());
        p.setProperty("voucher.note", t.note());
        p.setProperty("voucher.language", t.language().name());
        p.setProperty("voucher.accentColor", t.accentColor());
        p.setProperty("voucher.showQr", String.valueOf(t.showQr()));
        p.setProperty("voucher.showUptime", String.valueOf(t.showUptime()));
        p.setProperty("voucher.showPrice", String.valueOf(t.showPrice()));
        p.setProperty("voucher.showProfile", String.valueOf(t.showProfile()));
        p.setProperty("voucher.logo", t.logoFile().map(Path::toString).orElse(""));
        p.setProperty("voucher.style", t.style().name());
        p.setProperty("voucher.size", t.size().name());
        CardImage image = t.image();
        p.setProperty("voucher.image.file", image.background() == null ? "" : image.background().toString());
        putSpot(p, "voucher.image.user", image.username());
        putSpot(p, "voucher.image.pass", image.password());
        putSpot(p, "voucher.image.qr", image.qr());
        putSpot(p, "voucher.image.price", image.price());
        p.setProperty("voucher.image.color", image.textColor());
        p.setProperty("voucher.image.scale", String.valueOf(image.scale()));
        p.setProperty("voucher.image.textBox", String.valueOf(image.textBox()));
        t.prices().forEach((profile, price) -> p.setProperty(PRICE_PREFIX + profile, price));
        p.setProperty("ui.language", settings.ui().language().name());
        p.setProperty("ui.theme", settings.ui().theme().name());
        p.setProperty("ui.accent", settings.ui().accent());

        FileSupport.createParentDirectories(file);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            p.store(writer, "NetPilot settings");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static int intValue(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, "").strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean booleanValue(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, "").strip();
        return value.isEmpty() ? fallback : Boolean.parseBoolean(value);
    }

    private static <E extends Enum<E>> E enumValue(Properties p, String key, Class<E> type, E fallback) {
        try {
            return Enum.valueOf(type, p.getProperty(key, "").strip());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
