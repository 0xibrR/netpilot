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
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.AppSettings;
import org.netpilot.model.CardImage;
import org.netpilot.model.CardSize;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.VoucherStyle;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.service.CardLayout;
import org.netpilot.service.QrCodeService;

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
    /** The user's card picture and where the details sit on it; spots change by dragging. */
    private CardImage cardImage = CardImage.defaults();
    private final Map<Path, Image> pictures = new java.util.HashMap<>();
    private Image sampleQr;

    @FXML
    private ComboBox<VoucherStyle> styleBox;
    @FXML
    private ComboBox<CardSize> sizeBox;
    @FXML
    private VBox imageBox;
    @FXML
    private Label imageLabel;
    @FXML
    private ColorPicker textColorPicker;
    @FXML
    private Slider scaleSlider;
    @FXML
    private CheckBox textBoxCheck;
    @FXML
    private Pane cardPane;
    @FXML
    private Label perPageLabel;

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
        styleBox.getItems().setAll(VoucherStyle.values());
        sizeBox.getItems().setAll(CardSize.values());
        styleBox.valueProperty().addListener((obs, old, value) -> updatePreview());
        sizeBox.valueProperty().addListener((obs, old, value) -> updatePreview());
        textColorPicker.valueProperty().addListener((obs, old, value) -> updatePreview());
        scaleSlider.valueProperty().addListener((obs, old, value) -> updatePreview());
        textBoxCheck.selectedProperty().addListener((obs, old, value) -> updatePreview());
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
        styleBox.setValue(t.style());
        sizeBox.setValue(t.size());
        cardImage = t.image();
        textColorPicker.setValue(Color.web(cardImage.textColor()));
        scaleSlider.setValue(cardImage.scale());
        textBoxCheck.setSelected(cardImage.textBox());
        showImageName();

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

    /** The user's own card design; the details are then dragged into place on the preview. */
    @FXML
    private void onChooseImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("design.chooseImage"));
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter(Messages.get("design.images"), "*.png", "*.jpg", "*.jpeg"));
        File chosen = chooser.showOpenDialog(stage());
        if (chosen == null) {
            return;
        }
        try {
            Path stored = context.settingsRepository().storeCardImage(chosen.toPath());
            pictures.remove(stored); // same file name, new content
            cardImage = cardImage.withBackground(stored);
            showImageName();
            // dark text on a light picture and white text on a dark one, so the details show at once
            Image picture = pictures.computeIfAbsent(stored, p -> new Image(p.toUri().toString()));
            textColorPicker.setValue(averageBrightness(picture) > 0.55 ? Color.web("#111111") : Color.WHITE);
            styleBox.setValue(VoucherStyle.IMAGE);
            updatePreview();
        } catch (IllegalArgumentException | IOException e) {
            Dialogs.error(stage(), Messages.get("design.logoFailed"), e.getMessage());
        }
    }

    private void showImageName() {
        Path name = cardImage.background() == null ? null : cardImage.background().getFileName();
        imageLabel.setText(name == null ? Messages.get("design.noImage") : name.toString());
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
        workspace.toast("✓ " + Messages.get("main.status.designSaved") + " " + Messages.get("design.savedBody"));
    }

    // ------------------------------------------------------------------ form <-> template

    private VoucherTemplate currentTemplate() {
        Map<String, String> prices = new TreeMap<>();
        for (PriceRow row : pricesTable.getItems()) {
            prices.put(row.profile(), row.priceProperty().get());
        }
        CardImage picture = new CardImage(cardImage.background(), cardImage.username(), cardImage.password(),
                cardImage.qr(), cardImage.price(), hex(textColorPicker.getValue()), scaleSlider.getValue(),
                textBoxCheck.isSelected());
        return new VoucherTemplate(networkNameField.getText(), noteField.getText(), languageBox.getValue(),
                hex(colorPicker.getValue()), prices, showQrCheck.isSelected(), showUptimeCheck.isSelected(),
                showPriceCheck.isSelected(), showProfileCheck.isSelected(), logo, styleBox.getValue(),
                sizeBox.getValue(), picture);
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
        boolean compact = t.style().compact();
        show(voucherCard, !compact);
        show(cardPane, compact);
        show(perPageLabel, compact);
        show(imageBox, t.style() == VoucherStyle.IMAGE);
        sizeBox.setDisable(!compact);
        if (compact) {
            drawCard(t);
            return;
        }
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

    // ------------------------------------------------------------------ compact card preview

    /** Preview width in pixels; the card keeps its printed proportions. */
    private static final double PREVIEW_WIDTH = 300;

    /** Draws the same {@link CardLayout} the PDF uses, scaled up. */
    private void drawCard(VoucherTemplate t) {
        CardSize size = t.size();
        double scale = PREVIEW_WIDTH / size.width();
        double w = size.width() * scale;
        double h = size.height() * scale;
        String profile = selectedProfile().orElse("default");
        HotspotUser sample = new HotspotUser("714133", "5821", profile, "all", SAMPLE_TIME);
        CardLayout.Card card = CardLayout.Card.of(sample, t, t.showQr());
        List<CardLayout.Element> elements = CardLayout.layout(t, card, size.width(), size.height(),
                (value, fontSize) -> textWidth(value, fontSize));

        List<Node> nodes = new ArrayList<>();
        Rectangle paper = new Rectangle(w, h, Color.WHITE);
        paper.setArcWidth(10);
        paper.setArcHeight(10);
        paper.setStroke(Color.web("#B8C0CC"));
        nodes.add(paper);
        for (CardLayout.Element element : elements) {
            nodes.add(switch (element) {
                case CardLayout.Background b -> background(t, b, scale);
                case CardLayout.Fill f -> {
                    Rectangle band = new Rectangle(f.x() * scale, f.y() * scale, f.width() * scale, f.height() * scale);
                    band.setFill(Color.web(f.color()));
                    yield band;
                }
                case CardLayout.Line l -> {
                    javafx.scene.shape.Line line = new javafx.scene.shape.Line(l.x1() * scale, l.y1() * scale,
                            l.x2() * scale, l.y2() * scale);
                    line.setStroke(Color.web(l.color()));
                    line.getStrokeDashArray().setAll(4.0, 4.0);
                    yield line;
                }
                case CardLayout.Logo g -> logoPreview(t, g, scale);
                case CardLayout.Box x -> {
                    Rectangle box = new Rectangle(x.x() * scale, x.y() * scale, x.width() * scale,
                            x.height() * scale);
                    box.setArcWidth(x.height() * scale / 2);
                    box.setArcHeight(x.height() * scale / 2);
                    box.setFill(Color.web(x.color(), x.opacity()));
                    box.setMouseTransparent(true);
                    yield box;
                }
                case CardLayout.Qr q -> draggable(qr(q, scale), q.slot(), t, w, h);
                case CardLayout.Text x -> draggable(text(x, scale), x.slot(), t, w, h);
            });
        }
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        cardPane.setClip(clip);
        cardPane.setPrefSize(w, h);
        cardPane.setMinSize(w, h);
        cardPane.setMaxSize(w, h);
        cardPane.getChildren().setAll(nodes);
        perPageLabel.setText(Messages.get("design.perPage", size.perPage(), size.columns(), size.rows()));
    }

    private Node background(VoucherTemplate t, CardLayout.Background b, double scale) {
        Path file = t.image().background();
        if (file == null) {
            Label empty = new Label(Messages.get("design.noImage"));
            empty.setPrefSize(b.width() * scale, b.height() * scale);
            empty.setAlignment(javafx.geometry.Pos.CENTER);
            empty.setStyle("-fx-background-color: #EEF1F5; -fx-text-fill: #7A8494;");
            return empty;
        }
        ImageView view = new ImageView(pictures.computeIfAbsent(file, p -> new Image(p.toUri().toString())));
        view.setX(b.x() * scale);
        view.setY(b.y() * scale);
        view.setFitWidth(b.width() * scale);
        view.setFitHeight(b.height() * scale);
        return view;
    }

    private Node logoPreview(VoucherTemplate t, CardLayout.Logo g, double scale) {
        ImageView view = new ImageView(pictures.computeIfAbsent(t.logo(), p -> new Image(p.toUri().toString())));
        view.setPreserveRatio(true);
        view.setFitWidth(g.width() * scale);
        view.setFitHeight(g.height() * scale);
        view.setX(g.x() * scale);
        view.setY(g.y() * scale);
        return view;
    }

    private Node qr(CardLayout.Qr q, double scale) {
        if (sampleQr == null) {
            sampleQr = new Image(new java.io.ByteArrayInputStream(new QrCodeService().png("NetPilot", 200)));
        }
        ImageView view = new ImageView(sampleQr);
        view.setX(q.x() * scale);
        view.setY(q.y() * scale);
        view.setFitWidth(q.size() * scale);
        view.setFitHeight(q.size() * scale);
        return view;
    }

    private static Node text(CardLayout.Text t, double scale) {
        Text node = new Text(t.text());
        node.setFont(Font.font("Cairo", t.size() * scale));
        node.setFill(Color.web(t.color()));
        double width = node.getLayoutBounds().getWidth();
        double x = t.x() * scale;
        node.setX(switch (t.align()) {
            case LEFT -> x;
            case CENTER -> x - width / 2;
            case RIGHT -> x - width;
        });
        node.setY(t.baseline() * scale);
        return node;
    }

    /** On the user's own picture, the details can be dragged to where the design has room for them. */
    private Node draggable(Node node, CardLayout.Slot slot, VoucherTemplate t, double w, double h) {
        if (t.style() != VoucherStyle.IMAGE || slot == CardLayout.Slot.NONE) {
            return node;
        }
        node.setCursor(javafx.scene.Cursor.MOVE);
        node.setOnMouseDragged(e -> {
            javafx.geometry.Point2D p = cardPane.sceneToLocal(e.getSceneX(), e.getSceneY());
            CardImage.Spot spot = new CardImage.Spot(p.getX() / w, p.getY() / h);
            cardImage = switch (slot) {
                case USERNAME -> cardImage.withUsername(spot);
                case PASSWORD -> cardImage.withPassword(spot);
                case QR -> cardImage.withQr(spot);
                case PRICE -> cardImage.withPrice(spot);
                case NONE -> cardImage;
            };
            updatePreview();
        });
        return node;
    }

    /** Mean brightness (0 = black, 1 = white) of a grid of sample points. */
    static double averageBrightness(Image image) {
        if (image.getPixelReader() == null || image.getWidth() < 1 || image.getHeight() < 1) {
            return 1;
        }
        double sum = 0;
        int samples = 0;
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 20; j++) {
                Color c = image.getPixelReader().getColor((int) ((i + 0.5) * image.getWidth() / 20),
                        (int) ((j + 0.5) * image.getHeight() / 20));
                sum += c.getBrightness();
                samples++;
            }
        }
        return sum / samples;
    }

    private static double textWidth(String value, double size) {
        Text probe = new Text(value);
        probe.setFont(Font.font("Cairo", size));
        return probe.getLayoutBounds().getWidth();
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
