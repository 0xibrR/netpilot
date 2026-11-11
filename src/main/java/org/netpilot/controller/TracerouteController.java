package org.netpilot.controller;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.netpilot.AppContext;
import org.netpilot.i18n.Messages;
import org.netpilot.model.TraceHop;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.tools.TraceSession;

/**
 * "Traceroute" page (developer tools): the routers on the way from the connected router to an
 * address, one row per hop, filled in as probes come back. The work runs in a {@link TraceSession}.
 */
public class TracerouteController implements Page {

    private RouterClient client;
    private TraceSession session;

    @FXML
    private TextField addressField;
    @FXML
    private Button startButton;
    @FXML
    private Button stopButton;
    @FXML
    private ProgressIndicator progress;
    @FXML
    private Label summaryLabel;
    @FXML
    private Label problemLabel;
    @FXML
    private TableView<TraceHop> hopsTable;
    @FXML
    private TableColumn<TraceHop, String> hopColumn;
    @FXML
    private TableColumn<TraceHop, String> hostColumn;
    @FXML
    private TableColumn<TraceHop, String> lossColumn;
    @FXML
    private TableColumn<TraceHop, String> sentColumn;
    @FXML
    private TableColumn<TraceHop, String> lastColumn;
    @FXML
    private TableColumn<TraceHop, String> averageColumn;
    @FXML
    private TableColumn<TraceHop, String> bestColumn;
    @FXML
    private TableColumn<TraceHop, String> worstColumn;
    @FXML
    private TableColumn<TraceHop, String> statusColumn;

    public TracerouteController(AppContext context) {
        // the router comes from the shared connection; nothing else is needed
    }

    @FXML
    private void initialize() {
        hopColumn.setCellValueFactory(c -> text(String.valueOf(c.getValue().hop())));
        hostColumn.setCellValueFactory(c -> text(c.getValue().host().isEmpty() ? "*" : c.getValue().host()));
        hostColumn.getStyleClass().add("monospace");
        lossColumn.setCellValueFactory(c -> text(c.getValue().sent() == 0 ? "—" : c.getValue().lossPercent() + "%"));
        sentColumn.setCellValueFactory(c -> text(String.valueOf(c.getValue().sent())));
        lastColumn.setCellValueFactory(c -> text(time(c.getValue().last())));
        averageColumn.setCellValueFactory(c -> text(time(c.getValue().average())));
        bestColumn.setCellValueFactory(c -> text(time(c.getValue().best())));
        worstColumn.setCellValueFactory(c -> text(time(c.getValue().worst())));
        statusColumn.setCellValueFactory(c -> text(status(c.getValue())));
        startButton.setGraphic(Icons.of(Icons.ROUTE));
        updateButtons(false);
    }

    private static String status(TraceHop hop) {
        if (hop.target()) {
            return Messages.get("trace.status.target");
        }
        if (hop.responded()) {
            return Messages.get("trace.status.router");
        }
        return hop.sent() == 0 ? "…" : Messages.get("trace.status.noReply");
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
        try {
            session = new TraceSession(target, addressField.getText(), new TraceSession.Listener() {

                @Override
                public void hop(TraceHop hop) {
                    Platform.runLater(() -> show(hop));
                }

                @Override
                public void finished(List<TraceHop> hops, boolean reached, Optional<Exception> error) {
                    Platform.runLater(() -> {
                        summaryLabel.setText(reached
                                ? Messages.get("trace.reached", hops.size())
                                : Messages.get("trace.notReached", hops.size()));
                        error.ifPresent(e -> showProblem(Messages.get("ping.failed", e.getMessage())));
                        updateButtons(false);
                    });
                }
            });
        } catch (IllegalArgumentException invalid) {
            showProblem(invalid.getMessage());
            return;
        }
        hopsTable.getItems().clear();
        summaryLabel.setText(Messages.get("trace.running", session.address()));
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

    /** A new hop is added; an updated one replaces its row. */
    private void show(TraceHop hop) {
        int index = hop.hop() - 1;
        if (index < hopsTable.getItems().size()) {
            hopsTable.getItems().set(index, hop);
        } else {
            hopsTable.getItems().add(hop);
            hopsTable.scrollTo(index);
        }
    }

    private void updateButtons(boolean busy) {
        startButton.setDisable(busy || client == null);
        stopButton.setDisable(!busy);
        addressField.setDisable(busy);
        progress.setVisible(busy);
        progress.setManaged(busy);
        if (client == null && !busy) {
            showProblem(Messages.get("ping.noRouter"));
        } else if (Messages.get("ping.noRouter").equals(problemLabel.getText())) {
            showProblem(null);
        }
    }

    private void showProblem(String message) {
        boolean show = message != null && !message.isBlank();
        problemLabel.setText(show ? message : "");
        problemLabel.setVisible(show);
        problemLabel.setManaged(show);
    }

    private static String time(Optional<Duration> d) {
        return d.map(PingController::millis).orElse("—");
    }

    private static ReadOnlyStringWrapper text(String value) {
        return new ReadOnlyStringWrapper(value);
    }
}
