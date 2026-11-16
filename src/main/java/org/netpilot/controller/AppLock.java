package org.netpilot.controller;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.Role;
import org.netpilot.service.AccessControl;
import org.netpilot.service.PinVault;

/**
 * App protection in the main window: the lock screen, the owner/cashier button in the top bar,
 * "Lock now" (Ctrl+L) and the return to the cashier after the owner leaves NetPilot idle.
 *
 * <p>The lock screen replaces the whole window content, and the window's keyboard shortcuts are
 * set aside while it shows, so nothing behind it can be reached.
 */
final class AppLock {

    private static final System.Logger LOG = System.getLogger(AppLock.class.getName());
    private static final KeyCombination LOCK_KEYS = new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN);

    private final Workspace workspace;
    private final AccessControl access;
    private final Parent content;
    private final MenuButton roleButton;
    private final Timeline idleCheck = new Timeline(new KeyFrame(Duration.seconds(20), e -> checkIdle()));
    private final EventHandler<InputEvent> activity = e -> lastActivity = Instant.now();
    private Instant lastActivity = Instant.now();
    private Scene scene;
    private boolean locked;
    private Map<KeyCombination, Runnable> setAside = Map.of();

    AppLock(Workspace workspace, Parent content, MenuButton roleButton) {
        this.workspace = workspace;
        this.access = workspace.context().access();
        this.content = content;
        this.roleButton = roleButton;
        roleButton.setGraphic(Icons.of(Icons.LOCK));
        idleCheck.setCycleCount(Timeline.INDEFINITE);
        refresh();
    }

    private PinVault vault() {
        return access.vault();
    }

    /** Called once the window has its scene; at launch, a cashier PIN means the lock screen first. */
    void start(Scene windowScene, boolean atLaunch) {
        if (windowScene == scene) {
            // the window content coming back after the lock screen fires this again: nothing to start
            return;
        }
        this.scene = windowScene;
        scene.addEventFilter(InputEvent.ANY, activity);
        scene.getAccelerators().put(LOCK_KEYS, this::lock);
        idleCheck.play();
        if (atLaunch && vault().enabled() && vault().hasCashierPin()) {
            lock();
        }
    }

    void stop() {
        idleCheck.stop();
        if (scene != null) {
            scene.removeEventFilter(InputEvent.ANY, activity);
        }
    }

    /** The top-bar button: shown only with protection on; its menu follows the role. */
    void refresh() {
        boolean on = vault().enabled();
        roleButton.setVisible(on);
        roleButton.setManaged(on);
        if (!on) {
            return;
        }
        Role role = access.role();
        roleButton.setText(Messages.get(role == Role.OWNER ? "role.owner" : "role.cashier"));
        roleButton.getStyleClass().removeAll("role-owner", "role-cashier");
        roleButton.getStyleClass().add(role == Role.OWNER ? "role-owner" : "role-cashier");
        MenuItem switchRole = role == Role.OWNER
                ? new MenuItem(Messages.get("role.toCashier"))
                : new MenuItem(Messages.get("role.toOwner"));
        switchRole.setOnAction(e -> {
            if (access.role() == Role.OWNER) {
                access.setRole(Role.CASHIER);
            } else {
                becomeOwner();
            }
        });
        roleButton.getItems().setAll(switchRole);
        if (vault().hasCashierPin()) {
            MenuItem lockNow = new MenuItem(Messages.get("role.lockNow"));
            lockNow.setAccelerator(LOCK_KEYS);
            lockNow.setOnAction(e -> lock());
            roleButton.getItems().add(lockNow);
        }
    }

    /** Asks for the owner's PIN and, when right, makes the owner the user. */
    void becomeOwner() {
        Optional<char[]> pin = PinDialogs.ask(workspace.window(), Messages.get("role.toOwner.title"),
                Messages.get("role.toOwner.hint"));
        if (pin.isEmpty()) {
            return;
        }
        try {
            PinVault.Result result = vault().check(pin.get());
            switch (result) {
                case PinVault.Opened(Role role) when role == Role.OWNER -> access.setRole(Role.OWNER);
                case PinVault.Opened opened -> Dialogs.error(workspace.window(), Messages.get("role.toOwner.title"),
                        Messages.get("lock.error.cashierPin"));
                case PinVault.Wrong(int left) -> Dialogs.error(workspace.window(), Messages.get("role.toOwner.title"),
                        Messages.get("lock.error.wrong", left));
                case PinVault.Waiting(Instant until) -> Dialogs.error(workspace.window(),
                        Messages.get("role.toOwner.title"), PinDialogs.waitText(until));
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot check the PIN", e);
            Dialogs.error(workspace.window(), Messages.get("role.toOwner.title"), String.valueOf(e.getMessage()));
        } finally {
            PinDialogs.clear(pin.get());
        }
    }

    /**
     * Locks NetPilot: the lock screen when a cashier PIN is set (the next person types theirs);
     * without one, NetPilot simply goes back to the cashier.
     */
    void lock() {
        // after the key press or menu click is fully handled: JavaFX is still going through the
        // window's shortcuts while Ctrl+L runs, and the lock screen sets them aside
        javafx.application.Platform.runLater(this::lockNow);
    }

    private void lockNow() {
        if (!vault().enabled() || locked) {
            return;
        }
        if (!vault().hasCashierPin()) {
            access.setRole(Role.CASHIER);
            return;
        }
        if (scene == null) {
            return;
        }
        access.setRole(Role.CASHIER);
        Parent screen = lockScreen();
        Styling.apply(screen); // the theme, colours and language direction of the window
        setAside = new HashMap<>(scene.getAccelerators());
        scene.getAccelerators().clear(); // no shortcut reaches the window behind the lock screen
        scene.setRoot(screen);
        locked = true; // only once the lock screen really shows
        LOG.log(System.Logger.Level.INFO, "NetPilot locked");
    }

    boolean locked() {
        return locked;
    }

    private void unlock(Role role) {
        locked = false;
        Styling.apply(content); // styled now: at launch, or after a theme change, it was not on screen
        scene.setRoot(content);
        scene.getAccelerators().putAll(setAside);
        setAside = Map.of();
        lastActivity = Instant.now();
        access.setRole(role);
        LOG.log(System.Logger.Level.INFO, "NetPilot unlocked as the {0}", role);
    }

    /** The owner left NetPilot idle: back to the cashier, so the next person cannot use owner tools. */
    private void checkIdle() {
        if (locked || access.role() != Role.OWNER || !vault().enabled()) {
            return;
        }
        if (Instant.now().isAfter(lastActivity.plusSeconds(vault().idleMinutes() * 60L))) {
            access.setRole(Role.CASHIER);
            workspace.toast("🔒 " + Messages.get("role.idleReturn"));
            LOG.log(System.Logger.Level.INFO, "Owner idle: back to the cashier");
        }
    }

    // ------------------------------------------------------------------ lock screen

    private Parent lockScreen() {
        Label brand = new Label(NetPilotApp.NAME);
        brand.getStyleClass().add("brand");
        javafx.scene.Node icon = Icons.of(Icons.LOCK);
        icon.setScaleX(2.2);
        icon.setScaleY(2.2);
        Label title = new Label(Messages.get("lock.title"));
        title.getStyleClass().add("section-title");
        PasswordField pin = PinDialogs.pinField();
        pin.setMaxWidth(220);
        pin.setAlignment(Pos.CENTER);
        Label message = new Label(Messages.get("lock.hint"));
        message.getStyleClass().add("muted");
        message.setWrapText(true);
        message.setMaxWidth(340);
        Button open = new Button(Messages.get("lock.open"));
        open.getStyleClass().add("primary");
        open.setDefaultButton(true);
        open.disableProperty().bind(pin.textProperty().length().lessThan(PinVault.MIN_DIGITS));
        Hyperlink forgot = new Hyperlink(Messages.get("lock.forgot"));

        Timeline countdown = new Timeline();
        Runnable showWait = () -> vault().waitingUntil().ifPresentOrElse(until -> {
            pin.setDisable(true);
            message.setText(PinDialogs.waitText(until));
        }, () -> {
            countdown.stop();
            pin.setDisable(false);
            message.setText(Messages.get("lock.hint"));
            pin.requestFocus();
        });
        countdown.getKeyFrames().setAll(new KeyFrame(Duration.seconds(1), e -> showWait.run()));
        countdown.setCycleCount(Timeline.INDEFINITE);

        open.setOnAction(e -> {
            char[] typed = pin.getText().toCharArray();
            pin.clear();
            pin.setDisable(true);
            Task<PinVault.Result> check = new Task<>() {

                @Override
                protected PinVault.Result call() throws Exception {
                    try {
                        return vault().check(typed);
                    } finally {
                        PinDialogs.clear(typed);
                    }
                }
            };
            check.setOnSucceeded(done -> {
                switch (check.getValue()) {
                    case PinVault.Opened(Role role) -> {
                        countdown.stop();
                        unlock(role);
                    }
                    case PinVault.Wrong(int left) -> {
                        pin.setDisable(false);
                        message.setText(Messages.get("lock.error.wrong", left));
                        pin.requestFocus();
                    }
                    case PinVault.Waiting w -> {
                        showWait.run();
                        countdown.play();
                    }
                }
            });
            check.setOnFailed(done -> {
                pin.setDisable(false);
                LOG.log(System.Logger.Level.WARNING, "Cannot check the PIN", check.getException());
                message.setText(String.valueOf(check.getException().getMessage()));
            });
            Thread thread = new Thread(check, "netpilot-pin");
            thread.setDaemon(true);
            thread.start();
        });
        forgot.setOnAction(e -> recover());

        VBox box = new VBox(14, brand, icon, title, message, pin, open, forgot);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().addAll("app-root", "lock-screen");
        box.setNodeOrientation(content.getNodeOrientation());
        if (vault().waitingUntil().isPresent()) {
            showWait.run();
            countdown.play();
        }
        javafx.application.Platform.runLater(pin::requestFocus);
        return box;
    }

    /** "Forgot the PIN?": the recovery code sets a new owner PIN, and a new code replaces it. */
    private void recover() {
        PinDialogs.recovery(workspace.window()).ifPresent(r -> {
            try {
                String next = vault().recover(r.code(), r.newOwner());
                PinDialogs.showCode(workspace.window(), next);
                unlock(Role.OWNER);
            } catch (SecurityException | IllegalArgumentException e) {
                Dialogs.error(workspace.window(), Messages.get("lock.recover.title"), PinDialogs.explain(e));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot reset the PIN", e);
                Dialogs.error(workspace.window(), Messages.get("lock.recover.title"), String.valueOf(e.getMessage()));
            } finally {
                PinDialogs.clear(r.newOwner());
            }
        });
    }
}
