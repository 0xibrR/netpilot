package org.netpilot.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import javafx.concurrent.Task;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.AppSettings;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.TimeLimit;
import org.netpilot.repository.AppPaths;
import org.netpilot.service.QrCodeService;
import org.netpilot.service.ReceiptContent;

/**
 * What every page shares: services, the router session, the saved settings and a few actions
 * that belong to other pages (printing vouchers, switching page).
 */
final class Workspace {

    private static final System.Logger LOG = System.getLogger(Workspace.class.getName());

    private final AppContext context;
    private final RouterSession router;
    private final Supplier<Window> window;
    private AppSettings settings;
    private Consumer<List<HotspotUser>> printer = users -> {
    };
    private Consumer<PageId> navigator = page -> {
    };
    private Consumer<String> toaster = message -> {
    };
    private Runnable connector = () -> {
    };
    private Runnable languageReloader = () -> {
    };

    Workspace(AppContext context, RouterSession router, Supplier<Window> window, AppSettings settings) {
        this.context = context;
        this.router = router;
        this.window = window;
        this.settings = settings;
    }

    AppContext context() {
        return context;
    }

    RouterSession router() {
        return router;
    }

    Window window() {
        return window.get();
    }

    AppSettings settings() {
        return settings;
    }

    /** Changes and saves the settings. */
    void updateSettings(UnaryOperator<AppSettings> change) {
        settings = change.apply(settings);
        try {
            context.settingsRepository().save(settings);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save settings", e);
        }
    }

    /** Profile names from the router when connected, otherwise the ones that have a price. */
    List<String> profileNames() {
        Optional<RouterSession.RouterLists> lists = router.lists();
        if (lists.isPresent()) {
            return lists.get().profiles().stream().map(HotspotProfile::name).toList();
        }
        return List.copyOf(settings.voucherTemplate().prices().keySet());
    }

    /** Exports PDF/CSV/RSC for these users (done by the Generate page). */
    void printVouchers(List<HotspotUser> users) {
        printer.accept(users);
    }

    /**
     * Prints one receipt per card on the receipt printer chosen in Card design, in the background.
     * The A4 PDF ({@link #printVouchers}) stays available next to it.
     */
    void printReceipts(List<HotspotUser> cards) {
        if (cards.isEmpty()) {
            return;
        }
        AppSettings s = settings;
        Map<String, Optional<TimeLimit>> validity = router.lists()
                .map(l -> l.profiles().stream()
                        .collect(Collectors.toMap(HotspotProfile::name, HotspotProfile::validity, (a, b) -> a)))
                .orElse(Map.of());
        Optional<Path> logo = Optional.ofNullable(s.voucherTemplate().logo())
                .map(p -> p.isAbsolute() ? p : AppPaths.dataDirectory().resolve(p));
        // drawing needs the JavaFX thread; printing then runs in the background
        List<WritableImage> receipts = cards.stream()
                .map(card -> ReceiptPrinter.render(ReceiptContent.of(card, s.voucherTemplate(),
                        qrLink(s.loginUrl(), card), validity.getOrDefault(card.profile(), Optional.empty())),
                        s.receipt(), logo))
                .toList();
        Task<Void> task = new Task<>() {

            @Override
            protected Void call() {
                ReceiptPrinter.print(receipts, s.receipt());
                return null;
            }
        };
        task.setOnSucceeded(e -> toast("✓ " + Messages.get("receipt.printed", cards.size())));
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            LOG.log(System.Logger.Level.WARNING, "Receipt printing failed", error);
            Dialogs.error(window(), Messages.get("receipt.error.title"),
                    error instanceof IllegalStateException
                            ? error.getMessage()
                            : Messages.get("receipt.error.failed", s.receipt().printer()));
        });
        Thread thread = new Thread(task, "netpilot-receipts");
        thread.setDaemon(true);
        thread.start();
    }

    /** The QR auto-login link, or empty when the login address is not a valid http(s) URL. */
    static Optional<String> qrLink(String loginUrl, HotspotUser card) {
        try {
            return Optional.of(new QrCodeService().loginLink(loginUrl, card));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    void show(PageId page) {
        navigator.accept(page);
    }

    void setPrinter(Consumer<List<HotspotUser>> printer) {
        this.printer = printer;
    }

    void setNavigator(Consumer<PageId> navigator) {
        this.navigator = navigator;
    }

    /** Shows a short message that fades out by itself. */
    void toast(String message) {
        toaster.accept(message);
    }

    /** Opens the router connection dialog. */
    void connect() {
        connector.run();
    }

    void setToaster(Consumer<String> toaster) {
        this.toaster = toaster;
    }

    void setConnector(Runnable connector) {
        this.connector = connector;
    }

    /** Rebuilds the main window in the language saved in the settings, keeping the connection. */
    void reloadLanguage() {
        languageReloader.run();
    }

    void setLanguageReloader(Runnable reloader) {
        this.languageReloader = reloader;
    }
}
