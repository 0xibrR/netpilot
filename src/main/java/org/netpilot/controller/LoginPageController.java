package org.netpilot.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.LoginPageDesign;
import org.netpilot.model.Role.Permission;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.repository.AppPaths;
import org.netpilot.repository.LoginPageRepository;
import org.netpilot.service.LoginPageBuilder;
import org.netpilot.service.router.HotspotPagePublisher;
import org.netpilot.service.router.RouterClient;

/**
 * The hotspot login page (owner): design it without any code, preview it in the browser, send it
 * to the router, and go back to the router's previous page with one click.
 */
public class LoginPageController implements Page {

    private static final System.Logger LOG = System.getLogger(LoginPageController.class.getName());

    private final AppContext context;
    private final LoginPageRepository repository = new LoginPageRepository(AppPaths.loginPageFile());
    private Workspace workspace;
    private RouterClient client;
    private boolean busy;

    @FXML
    private TextField titleField;
    @FXML
    private TextField welcomeField;
    @FXML
    private ColorPicker colorPicker;
    @FXML
    private ComboBox<LoginPageDesign.Language> languageBox;
    @FXML
    private TextField phoneField;
    @FXML
    private TextField whatsAppField;
    @FXML
    private CheckBox logoCheck;
    @FXML
    private Label logoLabel;
    @FXML
    private Button chooseLogoButton;
    @FXML
    private Button removeLogoButton;
    /** The page's own logo (a file in the data folder), or empty for the card design's. */
    private String ownLogo = "";
    @FXML
    private CheckBox pricesCheck;
    @FXML
    private CheckBox passwordCheck;
    @FXML
    private CheckBox sellersCheck;
    @FXML
    private Label stateLabel;
    @FXML
    private Button previewButton;
    @FXML
    private Button publishButton;
    @FXML
    private Button revertButton;

    public LoginPageController(AppContext context) {
        this.context = context;
    }

    @FXML
    private void initialize() {
        languageBox.getItems().setAll(LoginPageDesign.Language.values());
        languageBox.setConverter(new StringConverter<>() {

            @Override
            public String toString(LoginPageDesign.Language language) {
                return language == null ? "" : Messages.get("loginPage.language." + language.name());
            }

            @Override
            public LoginPageDesign.Language fromString(String text) {
                return null;
            }
        });
        previewButton.setGraphic(Icons.of(Icons.GLOBE));
        publishButton.setGraphic(Icons.of(Icons.SEND));
        revertButton.setGraphic(Icons.of(Icons.REFRESH));
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
        shared.router().client().ifPresent(c -> client = c);
        LoginPageDesign d = repository.design();
        VoucherTemplate card = shared.settings().voucherTemplate();
        titleField.setText(d.title());
        titleField.setPromptText(card.networkName());
        welcomeField.setText(d.welcome());
        colorPicker.setValue(Color.web(d.color().equals(LoginPageDesign.DEFAULT_COLOR) && card.accentColor() != null
                && card.accentColor().matches("#[0-9a-fA-F]{6}") ? card.accentColor() : d.color()));
        languageBox.setValue(d.language());
        phoneField.setText(d.phone());
        whatsAppField.setText(d.whatsApp());
        logoCheck.setSelected(d.showLogo());
        ownLogo = d.logo();
        showLogo();
        pricesCheck.setSelected(d.showPrices());
        // cards made with passwords need the password field
        sellersCheck.setSelected(d.showSellers());
        passwordCheck.setSelected(d.passwordField()
                || shared.settings().generation().passwordStyle() != org.netpilot.model.PasswordStyle.NONE);
        showState(Optional.empty());
    }

    @Override
    public void shown() {
        readState();
    }

    @Override
    public void routerConnected(RouterClient routerClient, String name) {
        client = routerClient;
        readState();
    }

    @Override
    public void routerDisconnected() {
        client = null;
        showState(Optional.empty());
    }

    private LoginPageDesign design() {
        Color c = colorPicker.getValue();
        String color = String.format(java.util.Locale.ROOT, "#%02x%02x%02x", Math.round(c.getRed() * 255),
                Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
        return new LoginPageDesign(titleField.getText(), welcomeField.getText(), phoneField.getText(),
                whatsAppField.getText(), color, pricesCheck.isSelected(), logoCheck.isSelected(),
                passwordCheck.isSelected(), languageBox.getValue(), sellersCheck.isSelected(), ownLogo);
    }

    private Map<String, String> pages(LoginPageDesign d) {
        VoucherTemplate card = workspace.settings().voucherTemplate();
        Optional<Path> logo = d.logo().isEmpty()
                ? Optional.ofNullable(card.logo()).map(p -> p.isAbsolute() ? p : AppPaths.dataDirectory().resolve(p))
                : Optional.of(AppPaths.dataDirectory().resolve(d.logo()));
        List<LoginPageBuilder.Seller> sellers = List.of();
        if (d.showSellers()) {
            try {
                sellers = context.resellerAccounts().resellers().stream()
                        .map(r -> new LoginPageBuilder.Seller(r.name(), r.phone())).toList();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot read the points of sale for the login page", e);
            }
        }
        return LoginPageBuilder.build(d, card.networkName(), card.prices(), sellers,
                d.showLogo() ? logo.flatMap(LoginPageBuilder::logo) : Optional.empty());
    }

    private void save(LoginPageDesign d) {
        try {
            repository.save(d);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save the login page design", e);
        }
    }

    /** Which logo the page shows: its own, or the card design's. */
    private void showLogo() {
        logoLabel.setText(ownLogo.isEmpty()
                ? Messages.get("loginPage.logo.card")
                : Messages.get("loginPage.logo.own"));
        removeLogoButton.setDisable(ownLogo.isEmpty());
        chooseLogoButton.setGraphic(Icons.of(Icons.FOLDER));
    }

    /** A logo for the page only, copied into NetPilot's data folder (so backups keep it). */
    @FXML
    private void onChooseLogo() {
        if (!workspace.allowed(Permission.CARD_DESIGN)) {
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(Messages.get("loginPage.chooseLogo"));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                Messages.get("design.images"), "*.png", "*.jpg", "*.jpeg"));
        java.io.File file = chooser.showOpenDialog(workspace.window());
        if (file == null) {
            return;
        }
        if (LoginPageBuilder.logo(file.toPath()).isEmpty()) {
            Dialogs.error(workspace.window(), Messages.get("loginPage.chooseLogo"), Messages.get("loginPage.logo.bad"));
            return;
        }
        String ext = file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "png" : "jpg";
        String name = "login-page-logo." + ext;
        try {
            removeLogoFiles();
            Files.copy(file.toPath(), AppPaths.dataDirectory().resolve(name),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ownLogo = name;
            logoCheck.setSelected(true);
            save(design());
            showLogo();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot copy the login page logo", e);
            Dialogs.error(workspace.window(), Messages.get("loginPage.chooseLogo"), e.getMessage());
        }
    }

    /** Back to the card design's logo. */
    @FXML
    private void onRemoveLogo() {
        if (!workspace.allowed(Permission.CARD_DESIGN)) {
            return;
        }
        try {
            removeLogoFiles();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot remove the login page logo", e);
        }
        ownLogo = "";
        save(design());
        showLogo();
    }

    private static void removeLogoFiles() throws IOException {
        for (String ext : List.of("png", "jpg")) {
            Files.deleteIfExists(AppPaths.dataDirectory().resolve("login-page-logo." + ext));
        }
    }

    /** Opens the login page (and the after-login one) in the browser, as a phone would show them. */
    @FXML
    private void onPreview() {
        if (!workspace.allowed(Permission.CARD_DESIGN)) {
            return;
        }
        LoginPageDesign d = design();
        save(d);
        try {
            Path folder = Files.createDirectories(AppPaths.dataDirectory().resolve("login-page-preview"));
            for (Map.Entry<String, String> page : pages(d).entrySet()) {
                Files.writeString(folder.resolve(page.getKey()), page.getKey().endsWith(".html")
                        ? LoginPageBuilder.preview(page.getValue())
                        : page.getValue(), StandardCharsets.UTF_8);
            }
            context.hostServices().showDocument(folder.resolve("login.html").toUri().toString());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot write the login page preview", e);
            Dialogs.error(workspace.window(), Messages.get("loginPage.preview"), e.getMessage());
        }
    }

    /** Sends the pages to the router and makes the hotspot use them, after a clear yes. */
    @FXML
    private void onPublish() {
        RouterClient target = client;
        if (target == null || busy || !workspace.allowed(Permission.CARD_DESIGN)) {
            return;
        }
        if (!Dialogs.confirm(workspace.window(), Messages.get("loginPage.publish.title"),
                Messages.get("loginPage.publish.body"))) {
            return;
        }
        LoginPageDesign d = design();
        save(d);
        Map<String, String> files = pages(d);
        String router = target.endpoint().address();
        run(() -> {
            Map<String, String> before = HotspotPagePublisher.publish(target, files);
            repository.rememberPrevious(router, before);
            return Messages.get("loginPage.published");
        });
    }

    /** Puts the router back on the pages it used before NetPilot's. */
    @FXML
    private void onRevert() {
        RouterClient target = client;
        if (target == null || busy || !workspace.allowed(Permission.CARD_DESIGN)) {
            return;
        }
        if (!Dialogs.confirm(workspace.window(), Messages.get("loginPage.revert"),
                Messages.get("loginPage.revert.body"))) {
            return;
        }
        String router = target.endpoint().address();
        run(() -> {
            HotspotPagePublisher.revert(target, repository.previous(router));
            repository.forgetPrevious(router);
            return Messages.get("loginPage.reverted");
        });
    }

    private interface Work {

        String call() throws Exception;
    }

    private void run(Work work) {
        busy = true;
        setDisabled(true);
        Task<String> task = new Task<>() {

            @Override
            protected String call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> {
            busy = false;
            setDisabled(false);
            workspace.toast("✓ " + task.getValue());
            readState();
        });
        task.setOnFailed(e -> {
            busy = false;
            setDisabled(false);
            LOG.log(System.Logger.Level.WARNING, "Login page action failed", task.getException());
            Dialogs.error(workspace.window(), Messages.get("loginPage.failed"),
                    String.valueOf(task.getException().getMessage()));
            readState();
        });
        Thread thread = new Thread(task, "netpilot-login-page");
        thread.setDaemon(true);
        thread.start();
    }

    private void setDisabled(boolean disabled) {
        publishButton.setDisable(disabled || client == null);
        revertButton.setDisable(disabled || client == null);
    }

    /** Which pages the router's hotspot uses now. */
    private void readState() {
        RouterClient target = client;
        if (target == null || busy) {
            showState(Optional.empty());
            return;
        }
        Task<List<RouterClient.PageProfile>> task = new Task<>() {

            @Override
            protected List<RouterClient.PageProfile> call() throws Exception {
                return target.hotspotPageProfiles();
            }
        };
        task.setOnSucceeded(e -> showState(Optional.of(task.getValue())));
        task.setOnFailed(e -> {
            LOG.log(System.Logger.Level.DEBUG, "Cannot read the hotspot page folders", task.getException());
            showState(Optional.empty());
        });
        Thread thread = new Thread(task, "netpilot-login-page-state");
        thread.setDaemon(true);
        thread.start();
    }

    private void showState(Optional<List<RouterClient.PageProfile>> profiles) {
        setDisabled(busy);
        if (client == null) {
            stateLabel.setText(Messages.get("loginPage.state.noRouter"));
            return;
        }
        if (profiles.isEmpty()) {
            stateLabel.setText("");
            return;
        }
        boolean ours = !profiles.get().isEmpty()
                && profiles.get().stream().allMatch(p -> LoginPageBuilder.isOurs(p.folder()));
        stateLabel.setText(profiles.get().isEmpty()
                ? Messages.get("loginPage.noHotspot")
                : ours
                        ? Messages.get("loginPage.state.ours")
                        : Messages.get("loginPage.state.theirs", String.join(", ", profiles.get().stream()
                                .map(p -> p.folder().isEmpty() ? "hotspot" : p.folder()).distinct().toList())));
        revertButton
                .setDisable(busy || profiles.get().stream().noneMatch(p -> LoginPageBuilder.isOurs(p.folder())));
    }
}
