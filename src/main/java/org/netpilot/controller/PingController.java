package org.netpilot.controller;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.PingReply;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.tools.PingSession;
import org.netpilot.service.tools.PingStatistics;

/**
 * "Ping" page (developer tools): the connected router pings an address, one probe a second, and
 * the page shows each reply and the totals. The test runs in a {@link PingSession}; this page only
 * starts, stops and shows it.
 */
public class PingController implements Page {

    /** Replies kept in the table; a long test keeps only the newest. */
    private static final int MAX_ROWS = 500;
    private static final Duration INTERVAL = Duration.ofSeconds(1);

    private RouterClient client;
    private PingSession session;

    @FXML
    private TextField addressField;
    @FXML
    private ComboBox<Integer> countBox;
    @FXML
    private Button startButton;
    @FXML
    private Button stopButton;
    @FXML
    private ProgressIndicator progress;
    @FXML
    private Label problemLabel;
    @FXML
    private Label sentValue;
    @FXML
    private Label receivedValue;
    @FXML
    private Label lossValue;
    @FXML
    private Label latencyValue;
    @FXML
    private TableView<PingReply> repliesTable;
    @FXML
    private TableColumn<PingReply, String> seqColumn;
    @FXML
    private TableColumn<PingReply, String> hostColumn;
    @FXML
    private TableColumn<PingReply, String> timeColumn;
    @FXML
    private TableColumn<PingReply, String> ttlColumn;
    @FXML
    private TableColumn<PingReply, String> sizeColumn;
    @FXML
    private TableColumn<PingReply, String> statusColumn;

    public PingController(AppContext context) {
        // the router comes from the shared connection; nothing else is needed
    }

    @FXML
    private void initialize() {
        countBox.getItems().setAll(4, 10, 20, 100, 0);
        countBox.setValue(4);
        countBox.setConverter(new javafx.util.StringConverter<>() {

            @Override
            public String toString(Integer n) {
                return n == null ? "" : n == 0 ? Messages.get("ping.count.continuous") : String.valueOf(n);
            }

            @Override
            public Integer fromString(String text) {
                return 4;
            }
        });
        seqColumn.setCellValueFactory(c -> text(String.valueOf(c.getValue().seq() + 1)));
        hostColumn.setCellValueFactory(c -> text(c.getValue().host()));
        timeColumn.setCellValueFactory(c -> text(c.getValue().time().map(PingController::millis).orElse("—")));
        ttlColumn.setCellValueFactory(c -> text(c.getValue().ttl() == 0 ? "—" : String.valueOf(c.getValue().ttl())));
        sizeColumn.setCellValueFactory(c -> text(c.getValue().size() == 0 ? "—" : String.valueOf(c.getValue().size())));
        statusColumn.setCellValueFactory(c -> text(c.getValue().answered()
                ? Messages.get("ping.status.ok")
                : Messages.get("ping.status.lost", c.getValue().status())));
        startButton.setGraphic(Icons.of(Icons.PULSE));
        updateButtons(false);
    }

    @Override
    public void attach(Workspace shared) {
        shared.router().client().ifPresent(c -> client = c);
        updateButtons(false);
    }

    @Override
    public void routerConnected(RouterClient routerClient, String name) {
        client = routerClient;
        updateButtons(running());
    }

    @Override
    public void routerDisconnected() {
        onStop();
        client = null;
        updateButtons(false);
    }

    @FXML
    private void onStart() {
        RouterClient target = client;
        if (target == null || running()) {
            return;
        }
        showProblem(null);
        Integer count = countBox.getValue();
        try {
            session = new PingSession(target, addressField.getText(), count == null ? 4 : count, INTERVAL,
                    new PingSession.Listener() {

                        @Override
                        public void reply(PingReply reply, PingStatistics totals) {
                            Platform.runLater(() -> show(reply, totals));
                        }

                        @Override
                        public void finished(PingStatistics totals, Optional<Exception> error) {
                            Platform.runLater(() -> {
                                showTotals(totals);
                                error.ifPresent(e -> showProblem(Messages.get("ping.failed", e.getMessage())));
                                updateButtons(false);
                            });
                        }
                    });
        } catch (IllegalArgumentException invalid) {
            showProblem(invalid.getMessage());
            return;
        }
        repliesTable.getItems().clear();
        showTotals(PingStatistics.EMPTY);
        session.start();
        updateButtons(true);
    }

    @FXML
    private void onStop() {
        if (session != null) {
            session.stop();
        }
    }

    private boolean running() {
        return session != null && session.running();
    }

    private void show(PingReply reply, PingStatistics totals) {
        repliesTable.getItems().add(reply);
        if (repliesTable.getItems().size() > MAX_ROWS) {
            repliesTable.getItems().remove(0);
        }
        repliesTable.scrollTo(repliesTable.getItems().size() - 1);
        showTotals(totals);
    }

    private void showTotals(PingStatistics t) {
        sentValue.setText(String.valueOf(t.sent()));
        receivedValue.setText(String.valueOf(t.received()));
        lossValue.setText(t.sent() == 0 ? "—" : t.lossPercent() + "%");
        lossValue.getStyleClass().removeAll("stat-online", "warning");
        if (t.sent() > 0) {
            lossValue.getStyleClass().add(t.lossPercent() == 0 ? "stat-online" : "warning");
        }
        latencyValue.setText(t.average().map(avg -> Messages.get("ping.latency.value", millis(t.min().orElseThrow()),
                millis(avg), millis(t.max().orElseThrow()))).orElse("—"));
    }

    private void updateButtons(boolean busy) {
        startButton.setDisable(busy || client == null);
        stopButton.setDisable(!busy);
        addressField.setDisable(busy);
        countBox.setDisable(busy);
        progress.setVisible(busy);
        progress.setManaged(busy);
        if (client == null && !busy) {
            showProblem(Messages.get("ping.noRouter"));
        } else if (problemLabel.getText() != null && problemLabel.getText().equals(Messages.get("ping.noRouter"))) {
            showProblem(null);
        }
    }

    private void showProblem(String message) {
        boolean show = message != null && !message.isBlank();
        problemLabel.setText(show ? message : "");
        problemLabel.setVisible(show);
        problemLabel.setManaged(show);
    }

    /** {@code 12.4 ms}. */
    static String millis(Duration d) {
        return String.format(Locale.ROOT, "%.1f ms", d.toNanos() / 1_000_000.0);
    }

    private static ReadOnlyStringWrapper text(String value) {
        return new ReadOnlyStringWrapper(value);
    }
}
