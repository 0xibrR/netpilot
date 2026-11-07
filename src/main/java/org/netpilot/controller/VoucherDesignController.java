package org.netpilot.controller;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.AppSettings;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.VoucherTemplate;

/**
 * "Voucher Design" window: edits the {@link VoucherTemplate} with a live preview card, and can
 * print a test PDF with the exact layout.
 *
 * <p>Use: load the view, call {@link #prepare}, show the stage and wait, then read {@link #result()}.
 */
public class VoucherDesignController implements Page {

    private static final System.Logger LOG = System.getLogger(VoucherDesignController.class.getName());
    private static final String SAMPLE_TIME = "1h";

    private final AppContext context;

    private AppSettings settings;
    private Path logo;
    private VoucherTemplate result;
    private Workspace workspace;

    @FXML
    private TextField networkNameField;
    @FXML
    private TextField noteField;
    @FXML
    private ComboBox<VoucherTemplate.Language> languageBox;
    @FXML
    private ColorPicker colorPicker;
    @FXML
    private Label logoLabel;
    @FXML
    private Button removeLogoButton;
    @FXML
    private CheckBox showQrCheck;
    @FXML
    private CheckBox showUptimeCheck;
    @FXML
    private CheckBox showPriceCheck;
    @FXML
    private CheckBox showProfileCheck;
    @FXML
    private TableView<PriceRow> pricesTable;
    @FXML
    private TableColumn<PriceRow, String> profileColumn;
    @FXML
    private TableColumn<PriceRow, String> priceColumn;
    @FXML
    private VBox voucherCard;
    @FXML
    private ImageView logoView;
    @FXML
    private Label titleLabel;
    @FXML
    private Label profileLabel;
    @FXML
    private HBox userRow;
    @FXML
    private Label userLabel;
    @FXML
    private HBox passRow;
    @FXML
    private Label passLabel;
    @FXML
    private HBox timeRow;
    @FXML
    private Label timeLabel;
    @FXML
    private Label timeValue;
    @FXML
    private HBox priceRow;
    @FXML
    private Label priceLabel;
    @FXML
    private Label priceValue;
    @FXML
    private Label qrBox;
    @FXML
    private Label noteLabel;
    @FXML
    private Button testPrintButton;

    /** One editable line of the price table. */
    public static final class PriceRow {

        private final String profile;
        private final StringProperty price;

        PriceRow(String profile, String price) {
            this.profile = profile;
            this.price = new SimpleStringProperty(price);
        }

        String profile() {
            return profile;
        }

        StringProperty priceProperty() {
            return price;
        }
    }

    public VoucherDesignController(AppContext context) {
        this.context = context;
    }

    @Override
    public void attach(Workspace shared) {
        this.workspace = shared;
        prepare(shared.settings(), shared.profileNames());
    }

    /** Adds a price row for router profiles that are not listed yet, keeping unsaved edits. */
    @Override
    public void routerListsLoaded(RouterSession.RouterLists lists) {
        Set<String> listed = new LinkedHashSet<>();
        pricesTable.getItems().forEach(row -> listed.add(row.profile()));
        lists.profiles().stream()
                .map(org.netpilot.model.HotspotProfile::name)
                .filter(name -> !listed.contains(name))
                .forEach(name -> pricesTable.getItems().add(new PriceRow(name, "")));
        updatePreview();
    }

    @FXML
    private void initialize() {
        languageBox.getItems().setAll(VoucherTemplate.Language.values());
        profileColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().profile()));
        priceColumn.setCellValueFactory(c -> c.getValue().priceProperty());
        priceColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        priceColumn.setOnEditCommit(e -> {
            String value = e.getNewValue() == null ? "" : e.getNewValue().strip();
            e.getRowValue().priceProperty().set(value.length() > VoucherTemplate.MAX_PRICE
                    ? value.substring(0, VoucherTemplate.MAX_PRICE)
                    : value);
            updatePreview();
        });
        pricesTable.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> updatePreview());

        networkNameField.textProperty().addListener((obs, old, value) -> updatePreview());
        noteField.textProperty().addListener((obs, old, value) -> updatePreview());
        languageBox.valueProperty().addListener((obs, old, value) -> updatePreview());
        colorPicker.valueProperty().addListener((obs, old, value) -> updatePreview());
        for (CheckBox box : List.of(showQrCheck, showUptimeCheck, showPriceCheck, showProfileCheck)) {
            box.selectedProperty().addListener((obs, old, value) -> updatePreview());
        }
    }

    /**
     * @param current  settings holding the template to edit (and login URL / per-row for test prints)
     * @param profiles profile names to offer prices for (from the router or the main form)
     */
    public void prepare(AppSettings current, List<String> profiles) {
        this.settings = current;
        VoucherTemplate t = current.voucherTemplate();
        networkNameField.setText(t.networkName());
        noteField.setText(t.note());
        languageBox.setValue(t.language());
        colorPicker.setValue(Color.web(t.accentColor()));
        showQrCheck.setSelected(t.showQr());
        showUptimeCheck.setSelected(t.showUptime());
        showPriceCheck.setSelected(t.showPrice());
        showProfileCheck.setSelected(t.showProfile());
        setLogo(t.logo());

        // offer every known profile, plus any that already has a price
        Set<String> names = new LinkedHashSet<>(profiles);
        names.addAll(t.prices().keySet());
        List<PriceRow> rows = new ArrayList<>();
        for (String name : names) {
            rows.add(new PriceRow(name, t.priceFor(name).orElse("")));
        }
        pricesTable.getItems().setAll(rows);
        if (!rows.isEmpty()) {
            pricesTable.getSelectionModel().selectFirst();
        }
        updatePreview();
    }

    /** The saved template, if the user pressed Save. */
    public Optional<VoucherTemplate> result() {
        return Optional.ofNullable(result);
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onChooseLogo() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("design.chooseLogo.title"));
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter(Messages.get("design.images"), "*.png", "*.jpg", "*.jpeg"));
        File chosen = chooser.showOpenDialog(stage());
        if (chosen == null) {
            return;
        }
        try {
            setLogo(context.settingsRepository().storeLogo(chosen.toPath()));
            updatePreview();
        } catch (IllegalArgumentException | IOException e) {
            Dialogs.error(stage(), Messages.get("design.logoFailed"), e.getMessage());
        }
    }

    @FXML
    private void onRemoveLogo() {
        setLogo(null);
        updatePreview();
    }

    @FXML
    private void onTestPrint() {
        VoucherTemplate template = currentTemplate();
        List<String> problems = template.validate();
        if (!problems.isEmpty()) {
            Dialogs.error(stage(), Messages.get("design.fix"), String.join("\n", problems));
            return;
        }
        List<HotspotUser> samples = sampleUsers();
        AppSettings preview = settings.withVoucherTemplate(template);
        Task<Path> task = new Task<>() {

            @Override
            protected Path call() throws IOException {
                return context.exportService().previewPdf(samples, preview);
            }
        };
        task.setOnRunning(e -> testPrintButton.setDisable(true));
        task.setOnSucceeded(e -> {
            testPrintButton.setDisable(false);
            context.hostServices().showDocument(task.getValue().toUri().toString());
        });
        task.setOnFailed(e -> {
            testPrintButton.setDisable(false);
            LOG.log(System.Logger.Level.ERROR, "Test print failed", task.getException());
            Dialogs.error(stage(), Messages.get("design.testPrintFailed"),
                    String.valueOf(task.getException().getMessage()));
        });
        Thread thread = new Thread(task, "netpilot-test-print");
        thread.setDaemon(true);
        thread.start();
    }

    /** Drops unsaved changes. */
    @FXML
    private void onCancel() {
        prepare(workspace.settings(), workspace.profileNames());
    }

    @FXML
    private void onSave() {
        VoucherTemplate template = currentTemplate();
        List<String> problems = template.validate();
        if (!problems.isEmpty()) {
            Dialogs.error(stage(), Messages.get("design.fix"), String.join("\n", problems));
            return;
        }
        result = template;
        workspace.updateSettings(s -> s.withVoucherTemplate(template));
        settings = workspace.settings();
        Dialogs.info(stage(), Messages.get("main.status.designSaved"), Messages.get("design.savedBody"));
    }

    // ------------------------------------------------------------------ form <-> template

    private VoucherTemplate currentTemplate() {
        Map<String, String> prices = new TreeMap<>();
        for (PriceRow row : pricesTable.getItems()) {
            prices.put(row.profile(), row.priceProperty().get());
        }
        return new VoucherTemplate(networkNameField.getText(), noteField.getText(), languageBox.getValue(),
                hex(colorPicker.getValue()), prices, showQrCheck.isSelected(), showUptimeCheck.isSelected(),
                showPriceCheck.isSelected(), showProfileCheck.isSelected(), logo);
    }

    /** Eight vouchers for the test print, using the selected profile (or the first one). */
    private List<HotspotUser> sampleUsers() {
        String profile = selectedProfile().orElse("default");
        List<HotspotUser> users = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            users.add(new HotspotUser("71413" + i, "58" + (20 + i), profile, "all", SAMPLE_TIME));
        }
        return users;
    }

    private Optional<String> selectedProfile() {
        PriceRow selected = pricesTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            return Optional.of(selected.profile());
        }
        return pricesTable.getItems().stream().findFirst().map(PriceRow::profile);
    }

    private void setLogo(Path path) {
        logo = path;
        Path fileName = path == null ? null : path.getFileName();
        logoLabel.setText(fileName == null ? Messages.get("design.noLogo") : fileName.toString());
        removeLogoButton.setDisable(path == null);
        logoView.setImage(path == null ? null : new Image(path.toUri().toString(), true));
    }

    // ------------------------------------------------------------------ live preview

    private void updatePreview() {
        if (languageBox.getValue() == null) {
            return; // not prepared yet
        }
        VoucherTemplate t = currentTemplate();
        boolean arabic = t.language() == VoucherTemplate.Language.ARABIC;
        voucherCard.setNodeOrientation(arabic ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        String profile = selectedProfile().orElse("default");

        show(logoView, logo != null);
        titleLabel.setText(t.networkName());
        titleLabel.setStyle("-fx-text-fill: " + t.accentColor() + ";");
        show(titleLabel, !t.networkName().isEmpty());
        profileLabel.setText(profile);
        show(profileLabel, t.showProfile());
        userLabel.setText(t.language().username() + ":");
        passLabel.setText(t.language().password() + ":");
        timeLabel.setText(t.language().time() + ":");
        timeValue.setText(SAMPLE_TIME);
        show(timeRow, t.showUptime());
        Optional<String> price = t.priceFor(profile);
        priceLabel.setText(t.language().price() + ":");
        priceValue.setText(price.orElse(""));
        show(priceRow, t.showPrice() && price.isPresent());
        show(qrBox, t.showQr());
        noteLabel.setText(t.note());
        show(noteLabel, !t.note().isEmpty());
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static String hex(Color color) {
        Function<Double, Integer> channel = v -> (int) Math.round(v * 255);
        return String.format(Locale.ROOT, "#%02X%02X%02X", channel.apply(color.getRed()),
                channel.apply(color.getGreen()), channel.apply(color.getBlue()));
    }

    private Window stage() {
        return voucherCard.getScene().getWindow();
    }
}
