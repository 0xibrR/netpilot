package org.netpilot.controller;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.netpilot.AppContext;
import org.netpilot.model.GenerationSettings;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.UploadReport;
import org.netpilot.service.router.RouterClient;
import org.netpilot.service.router.RouterUploadService;

/**
 * "Send to Router" window: shows progress while users are created, then a summary with
 * Print vouchers and Undo.
 *
 * <p>Use: load the view, show the stage, call {@link #start}, wait for the stage to close, then
 * read {@link #report()}, {@link #undone()} and {@link #printRequested()}.
 */
public class UploadController {

    private static final System.Logger LOG = System.getLogger(UploadController.class.getName());

    private final AppContext context;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private RouterClient client;
    private UploadReport report;
    private boolean running;
    private boolean undone;
    private boolean printRequested;

    @FXML
    private Label headerLabel;
    @FXML
    private VBox progressBox;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label progressLabel;
    @FXML
    private VBox summaryBox;
    @FXML
    private Label createdLabel;
    @FXML
    private Label replacedLabel;
    @FXML
    private Label skippedLabel;
    @FXML
    private Label failedLabel;
    @FXML
    private Label notSentLabel;
    @FXML
    private Label stopLabel;
    @FXML
    private Label commentLabel;
    @FXML
    private TextArea detailsArea;
    @FXML
    private Button undoButton;
    @FXML
    private Button cancelButton;
    @FXML
    private Button closeButton;
    @FXML
    private Button printButton;

    public UploadController(AppContext context) {
        this.context = context;
    }

    /** Starts creating the users in the background. Call after the stage is shown. */
    public void start(RouterClient routerClient, String routerName, List<HotspotUser> users,
            GenerationSettings settings) {
        this.client = routerClient;
        headerLabel.setText("Creating " + users.size() + " users on " + routerName + "…");
        stage().setOnCloseRequest(event -> {
            if (running) {
                event.consume(); // closing mid-way would hide what was created; cancel instead
                onCancel();
            }
        });

        Task<UploadReport> task = new Task<>() {

            @Override
            protected UploadReport call() throws Exception {
                return context.routerUploadService().upload(client, users, settings, (done, total, name) -> {
                    updateProgress(done, total);
                    updateMessage(name == null ? done + " / " + total : done + " / " + total + "  ·  " + name);
                }, cancelRequested::get);
            }
        };
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> finished(task.getValue()));
        task.setOnFailed(e -> failedBeforeStart(task.getException()));

        running = true;
        Thread thread = new Thread(task, "netpilot-upload");
        thread.setDaemon(true);
        thread.start();
    }

    public Optional<UploadReport> report() {
        return Optional.ofNullable(report);
    }

    /** Whether the user removed the batch again with Undo. */
    public boolean undone() {
        return undone;
    }

    /** Whether the user asked to print vouchers for the created users. */
    public boolean printRequested() {
        return printRequested;
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onCancel() {
        if (running) {
            cancelRequested.set(true);
            cancelButton.setDisable(true);
            cancelButton.setText("Stopping…");
        } else {
            stage().close();
        }
    }

    @FXML
    private void onClose() {
        stage().close();
    }

    @FXML
    private void onPrint() {
        printRequested = true;
        stage().close();
    }

    @FXML
    private void onUndo() {
        int count = report.created().size();
        if (!Dialogs.confirm(stage(), "Delete the " + count + " users just created?",
                "They are removed from the router. Users that existed before are not touched.")) {
            return;
        }
        Task<RouterUploadService.UndoResult> task = new Task<>() {

            @Override
            protected RouterUploadService.UndoResult call() {
                return context.routerUploadService().undo(client, report);
            }
        };
        task.setOnRunning(e -> {
            setVisible(undoButton, false);
            setVisible(printButton, false);
            closeButton.setDisable(true);
            headerLabel.setText("Deleting " + count + " users…");
        });
        task.setOnSucceeded(e -> {
            RouterUploadService.UndoResult result = task.getValue();
            undone = result.problems().isEmpty();
            closeButton.setDisable(false);
            headerLabel.setText(result.deleted() + " of " + count + " users deleted from the router.");
            if (!result.problems().isEmpty()) {
                showDetails("Could not delete:\n" + String.join("\n", result.problems()));
            }
        });
        task.setOnFailed(e -> {
            closeButton.setDisable(false);
            LOG.log(System.Logger.Level.ERROR, "Undo failed", task.getException());
            headerLabel.setText("Undo failed: " + task.getException().getMessage());
        });
        Thread thread = new Thread(task, "netpilot-undo");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------------ results

    private void finished(UploadReport result) {
        running = false;
        report = result;
        progressBar.progressProperty().unbind();
        progressBar.setProgress(1);
        progressLabel.textProperty().unbind();

        headerLabel.setText(result.completeSuccess() ? "Done ✓" : "Finished with problems");
        createdLabel.setText("✓ Created: " + result.created().size());
        show(replacedLabel, result.replaced() > 0,
                "↻ " + result.replaced() + " of them got a new name because the name already existed");
        show(skippedLabel, !result.skipped().isEmpty(),
                "⏭ Skipped (name already exists): " + result.skipped().size());
        show(failedLabel, !result.failed().isEmpty(), "✗ Failed: " + result.failed().size());
        show(notSentLabel, !result.notSent().isEmpty(), "⏸ Not sent: " + result.notSent().size());
        show(stopLabel, result.stopReason().isPresent(), result.stopReason().orElse(""));
        commentLabel.setText("Comment on the router: " + result.batchComment());

        List<String> details = new java.util.ArrayList<>();
        if (!result.failed().isEmpty()) {
            details.add("Failed users:\n" + result.failed().stream()
                    .map(f -> "  " + f.user().username() + ": " + f.reason())
                    .collect(Collectors.joining("\n")));
        }
        if (!result.skipped().isEmpty()) {
            details.add("Skipped users:\n  " + result.skipped().stream()
                    .map(HotspotUser::username)
                    .collect(Collectors.joining(", ")));
        }
        if (!details.isEmpty()) {
            showDetails(String.join("\n\n", details));
        }

        setVisible(progressBox, false);
        setVisible(summaryBox, true);
        setVisible(cancelButton, false);
        setVisible(closeButton, true);
        boolean anyCreated = !result.created().isEmpty();
        setVisible(printButton, anyCreated);
        setVisible(undoButton, anyCreated);
        printButton.setText("Print " + result.created().size() + " vouchers (PDF)");
        stage().sizeToScene();
    }

    private void failedBeforeStart(Throwable error) {
        running = false;
        LOG.log(System.Logger.Level.WARNING, "Upload could not start", error);
        progressBar.progressProperty().unbind();
        progressLabel.textProperty().unbind();
        headerLabel.setText("Nothing was created");
        progressLabel.setText(String.valueOf(error.getMessage()));
        progressLabel.getStyleClass().add("status-error");
        setVisible(cancelButton, false);
        setVisible(closeButton, true);
    }

    private void showDetails(String text) {
        detailsArea.setText(text);
        setVisible(detailsArea, true);
        stage().sizeToScene();
    }

    private static void show(Label label, boolean visible, String text) {
        label.setText(text);
        setVisible(label, visible);
    }

    private static void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private Stage stage() {
        return (Stage) headerLabel.getScene().getWindow();
    }
}
