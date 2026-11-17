package org.netpilot.controller;

import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.netpilot.i18n.Messages;
import org.netpilot.model.RouterSettings;
import org.netpilot.service.router.ApiConnection;
import org.netpilot.service.router.LiveFeed;

/**
 * Settings › Live updates: on or off, the API port, and what the connection is doing now, with the
 * router-side steps when the API cannot be reached.
 */
final class LiveSettings {

    private final Workspace workspace;
    private final VBox box;
    private final CheckBox enabled = new CheckBox(Messages.get("live.settings.enabled"));
    private final TextField port = new TextField();
    private final Label state = new Label();
    private final Label hint = new Label();
    private boolean loading;

    LiveSettings(Workspace workspace, VBox box) {
        this.workspace = workspace;
        this.box = box;
    }

    void show() {
        RouterSettings router = workspace.settings().router();
        loading = true;
        enabled.setSelected(router.live());
        port.setText(router.livePort() > 0 ? String.valueOf(router.livePort()) : "");
        loading = false;
        port.setPromptText(Messages.get("live.settings.portAuto"));
        port.setPrefColumnCount(9);
        port.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE); // never narrower than its hint
        port.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().matches("\\d{0,5}") ? c : null));
        port.disableProperty().bind(enabled.selectedProperty().not());
        enabled.selectedProperty().addListener((obs, was, on) -> save());
        port.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                save();
            }
        });
        port.setOnAction(e -> save());
        HBox portRow = new HBox(8, new Label(Messages.get("live.settings.port")), port);
        portRow.setAlignment(Pos.CENTER_LEFT);
        state.getStyleClass().add("protection-status");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        workspace.live().statusProperty().addListener((obs, old, status) -> showState(status));
        showState(workspace.live().statusProperty().get());
        box.getChildren().setAll(enabled, portRow, state, hint);
    }

    private void save() {
        if (loading) {
            return;
        }
        int chosen = port.getText().isBlank() ? 0 : Integer.parseInt(port.getText());
        RouterSettings now = workspace.settings().router();
        if (now.live() == enabled.isSelected() && now.livePort() == chosen) {
            return;
        }
        workspace.updateSettings(s -> s.withRouter(s.router().withLive(enabled.isSelected(), chosen)));
        workspace.restartLive();
    }

    private void showState(LiveFeed.Status status) {
        String key = switch (status.state()) {
            case LIVE -> "live.state.live";
            case CONNECTING -> "live.state.connecting";
            case UNAVAILABLE -> "live.state.unavailable";
            case STOPPED -> workspace.router().client().isEmpty()
                    ? "live.state.noRouter"
                    : enabled.isSelected() ? "live.state.connecting" : "live.state.off";
        };
        state.setText(Messages.get(key));
        String help = status.problem().map(LiveSettings::problemKey).map(Messages::get).orElse("");
        hint.setText(status.state() == LiveFeed.State.LIVE
                ? Messages.get("live.settings.help.live")
                : help.isEmpty() ? Messages.get("live.settings.help") : help);
    }

    private static String problemKey(ApiConnection.ApiException.Kind kind) {
        return switch (kind) {
            case UNREACHABLE -> "live.problem.unreachable";
            case LOGIN_FAILED -> "live.problem.login";
            case CERTIFICATE -> "live.problem.certificate";
            case BROKEN -> "live.problem.broken";
        };
    }
}
