package org.netpilot.controller;

import java.io.IOException;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.netpilot.i18n.Messages;
import org.netpilot.service.AccessControl;
import org.netpilot.service.PinVault;

/**
 * Settings › App protection: turning it on (owner PIN, optional cashier PIN, recovery code),
 * changing either PIN, the idle time before NetPilot returns to the cashier, and turning it off.
 * Every change but the idle time asks for the owner's PIN again.
 */
final class ProtectionSettings {

    private static final System.Logger LOG = System.getLogger(ProtectionSettings.class.getName());

    private final Workspace workspace;
    private final AccessControl access;
    private final VBox box;

    ProtectionSettings(Workspace workspace, VBox box) {
        this.workspace = workspace;
        this.access = workspace.context().access();
        this.box = box;
    }

    private PinVault vault() {
        return access.vault();
    }

    /** Draws the section for the current state. */
    void show() {
        Label status = new Label();
        status.getStyleClass().add("protection-status");
        if (!vault().enabled()) {
            status.setText(Messages.get("protection.off"));
            Button enable = new Button(Messages.get("protection.enable"), Icons.of(Icons.LOCK));
            enable.getStyleClass().add("primary");
            enable.setOnAction(e -> enable());
            box.getChildren().setAll(status, enable);
            return;
        }
        status.setText(
                Messages.get(vault().hasCashierPin() ? "protection.on.cashierPin" : "protection.on.noCashierPin"));
        Button changeOwner = new Button(Messages.get("protection.changeOwner"));
        changeOwner.setOnAction(e -> changeOwner());
        Button cashier = new Button(Messages.get(vault().hasCashierPin()
                ? "protection.cashier.change"
                : "protection.cashier.set"));
        cashier.setOnAction(e -> cashier());
        Button disable = new Button(Messages.get("protection.disable"));
        disable.getStyleClass().add("danger");
        disable.setOnAction(e -> disable());
        FlowPane buttons = new FlowPane(8, 8, changeOwner, cashier, disable);

        Spinner<Integer> idle = new Spinner<>(1, 240, vault().idleMinutes());
        idle.setEditable(true);
        idle.setPrefWidth(90);
        idle.setMinWidth(90);
        idle.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                idle.increment(0);
            }
        });
        idle.valueProperty().addListener((obs, old, minutes) -> {
            try {
                vault().setIdleMinutes(minutes);
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "Cannot save the idle time", ex);
            }
        });
        HBox idleRow = new HBox(8, new Label(Messages.get("protection.idle")), idle,
                new Label(Messages.get("alert.settings.minutes")));
        idleRow.setAlignment(Pos.CENTER_LEFT);
        Label tips = new Label(Messages.get("protection.tips"));
        tips.getStyleClass().add("muted");
        tips.setWrapText(true);
        javafx.scene.control.CheckBox requireShift = new javafx.scene.control.CheckBox(
                Messages.get("protection.requireShift"));
        requireShift.setSelected(vault().requireShift());
        requireShift.selectedProperty().addListener((obs, was, on) -> {
            try {
                vault().setRequireShift(on);
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.WARNING, "Cannot save the shift rule", ex);
            }
        });
        box.getChildren().setAll(status, buttons, idleRow, requireShift, tips);
    }

    private void enable() {
        Optional<char[]> owner = PinDialogs.choose(workspace.window(), Messages.get("protection.enable.ownerTitle"),
                Messages.get("protection.enable.ownerHint"));
        if (owner.isEmpty()) {
            return;
        }
        Optional<char[]> cashier = Optional.empty();
        try {
            ButtonType set = new ButtonType(Messages.get("protection.cashier.set"), ButtonBar.ButtonData.YES);
            ButtonType none = new ButtonType(Messages.get("protection.cashier.none"), ButtonBar.ButtonData.NO);
            Optional<ButtonType> answer = Dialogs.choose(workspace.window(), Messages.get("protection.cashier.title"),
                    Messages.get("protection.cashier.question"), set, none);
            if (answer.isEmpty()) {
                return;
            }
            if (answer.get() == set) {
                cashier = PinDialogs.choose(workspace.window(), Messages.get("protection.cashier.title"),
                        Messages.get("protection.cashier.hint"));
                if (cashier.isEmpty()) {
                    return;
                }
            }
            String code = vault().enable(owner.get(), cashier, PinVault.DEFAULT_IDLE_MINUTES);
            PinDialogs.showCode(workspace.window(), code);
            access.protectionChanged();
            workspace.toast("🔒 " + Messages.get("protection.enabled"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            Dialogs.error(workspace.window(), Messages.get("protection.title"), PinDialogs.explain(e));
        } catch (IOException e) {
            failed(e);
        } finally {
            PinDialogs.clear(owner.get());
            cashier.ifPresent(PinDialogs::clear);
        }
        show();
    }

    private void changeOwner() {
        Optional<char[]> current = askOwner();
        if (current.isEmpty()) {
            return;
        }
        Optional<char[]> next = Optional.empty();
        try {
            next = PinDialogs.choose(workspace.window(), Messages.get("protection.changeOwner"),
                    Messages.get("protection.enable.ownerHint"));
            if (next.isPresent()) {
                vault().changeOwner(current.get(), next.get());
                workspace.toast("✓ " + Messages.get("protection.ownerChanged"));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Dialogs.error(workspace.window(), Messages.get("protection.changeOwner"), PinDialogs.explain(e));
        } catch (IOException e) {
            failed(e);
        } finally {
            PinDialogs.clear(current.get());
            next.ifPresent(PinDialogs::clear);
        }
        show();
    }

    private void cashier() {
        Optional<char[]> owner = askOwner();
        if (owner.isEmpty()) {
            return;
        }
        Optional<char[]> next = Optional.empty();
        try {
            ButtonType set = new ButtonType(Messages.get("protection.cashier.set"), ButtonBar.ButtonData.YES);
            ButtonType remove = new ButtonType(Messages.get("protection.cashier.remove"), ButtonBar.ButtonData.NO);
            Optional<ButtonType> answer = vault().hasCashierPin()
                    ? Dialogs.choose(workspace.window(), Messages.get("protection.cashier.title"),
                            Messages.get("protection.cashier.changeQuestion"), set, remove)
                    : Optional.of(set);
            if (answer.isEmpty()) {
                return;
            }
            if (answer.get() == set) {
                next = PinDialogs.choose(workspace.window(), Messages.get("protection.cashier.title"),
                        Messages.get("protection.cashier.hint"));
                if (next.isEmpty()) {
                    return;
                }
            }
            vault().setCashier(owner.get(), next);
            access.protectionChanged();
            workspace.toast("✓ " + Messages.get(next.isPresent()
                    ? "protection.cashierSaved"
                    : "protection.cashierRemoved"));
        } catch (SecurityException | IllegalArgumentException e) {
            Dialogs.error(workspace.window(), Messages.get("protection.cashier.title"), PinDialogs.explain(e));
        } catch (IOException e) {
            failed(e);
        } finally {
            PinDialogs.clear(owner.get());
            next.ifPresent(PinDialogs::clear);
            show();
        }
    }

    private void disable() {
        Optional<char[]> owner = askOwner();
        if (owner.isEmpty()) {
            return;
        }
        try {
            vault().disable(owner.get());
            access.protectionChanged();
            workspace.toast(Messages.get("protection.disabled"));
        } catch (SecurityException e) {
            Dialogs.error(workspace.window(), Messages.get("protection.disable"), PinDialogs.explain(e));
        } catch (IOException e) {
            failed(e);
        } finally {
            PinDialogs.clear(owner.get());
        }
        show();
    }

    private Optional<char[]> askOwner() {
        return PinDialogs.ask(workspace.window(), Messages.get("protection.ownerAgain.title"),
                Messages.get("protection.ownerAgain.hint"));
    }

    private void failed(IOException e) {
        LOG.log(System.Logger.Level.WARNING, "App protection change failed", e);
        Dialogs.error(workspace.window(), Messages.get("protection.title"), String.valueOf(e.getMessage()));
    }
}
