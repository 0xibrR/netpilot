package org.netpilot.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.concurrent.Task;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Window;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.model.LedgerEvent;
import org.netpilot.model.Reseller;
import org.netpilot.service.ResellerAccounts;
import org.netpilot.service.ResellerAccounts.Card;
import org.netpilot.service.ResellerAccounts.Plan;

/**
 * Handing cards to a point of sale, the same from every place it starts (Points of sale, Manage
 * users, after sending a batch): choose the point of sale, see what will happen, agree to move
 * cards another point of sale has, then the handover is written to the ledger.
 */
final class HandoverFlow {

    private HandoverFlow() {
    }

    /**
     * @param preselected the point of sale to offer first, or {@code null}
     * @param done        runs on the JavaFX thread after a handover was written
     */
    static void start(Workspace workspace, Window owner, List<Card> cards, Reseller preselected, Runnable done) {
        if (cards.isEmpty()) {
            Dialogs.info(owner, Messages.get("handover.title"), Messages.get("handover.noCards"));
            return;
        }
        ResellerAccounts accounts = workspace.context().resellerAccounts();
        List<Reseller> resellers;
        try {
            resellers = accounts.resellers();
        } catch (IOException e) {
            Dialogs.error(owner, Messages.get("handover.title"), e.getMessage());
            return;
        }
        if (resellers.isEmpty()) {
            Dialogs.info(owner, Messages.get("handover.title"), Messages.get("handover.noResellers"));
            return;
        }
        Reseller first = preselected != null ? preselected : resellers.getFirst();
        ChoiceDialog<Reseller> choose = new ChoiceDialog<>(
                resellers.stream().filter(r -> r.id().equals(first.id())).findFirst().orElse(resellers.getFirst()),
                resellers);
        choose.initOwner(owner);
        choose.setTitle(NetPilotApp.NAME);
        choose.setHeaderText(Messages.get("handover.choose", cards.size()));
        choose.setContentText(Messages.get("handover.reseller"));
        Styling.apply(choose.getDialogPane());
        Optional<Reseller> picked = choose.showAndWait();
        if (picked.isEmpty()) {
            return;
        }
        Reseller reseller = picked.get();
        Map<String, String> prices = workspace.settings().voucherTemplate().prices();
        Plan plan;
        try {
            plan = accounts.plan(reseller.id(), cards, prices);
        } catch (IOException e) {
            Dialogs.error(owner, Messages.get("handover.title"), e.getMessage());
            return;
        }
        boolean move = false;
        if (!plan.transfers().isEmpty()) {
            ButtonType yes = new ButtonType(Messages.get("handover.move"), ButtonBar.ButtonData.YES);
            ButtonType no = new ButtonType(Messages.get("handover.keep"), ButtonBar.ButtonData.NO);
            Optional<ButtonType> answer = Dialogs.choose(owner, Messages.get("handover.transfer.title"),
                    Messages.get("handover.transfer.body", plan.transfers().size(), reseller.name()), yes, no);
            if (answer.isEmpty()) {
                return;
            }
            move = answer.get() == yes;
        }
        if (plan.fresh().isEmpty() && (!move || plan.transfers().isEmpty())) {
            Dialogs.info(owner, Messages.get("handover.title"), summary(plan, 0, reseller));
            return;
        }
        boolean moveCards = move;
        Task<List<LedgerEvent>> task = new Task<>() {

            @Override
            protected List<LedgerEvent> call() throws IOException {
                return accounts.handOver(reseller, cards, prices, moveCards);
            }
        };
        task.setOnSucceeded(e -> {
            long given = task.getValue().stream().filter(ev -> ev.kind() == LedgerEvent.Kind.HANDOVER).count();
            workspace.toast("✓ " + Messages.get("handover.done", given, reseller.name()));
            String notes = summary(plan, (int) given, reseller);
            if (!plan.sold().isEmpty() || !plan.noPrice().isEmpty()
                    || (!plan.transfers().isEmpty() && !moveCards)) {
                Dialogs.info(owner, Messages.get("handover.title"), notes);
            }
            done.run();
        });
        task.setOnFailed(e -> Dialogs.error(owner, Messages.get("handover.title"),
                String.valueOf(task.getException().getMessage())));
        Thread thread = new Thread(task, "netpilot-handover");
        thread.setDaemon(true);
        thread.start();
    }

    /** What was done and what was left out, and why. */
    private static String summary(Plan plan, int given, Reseller reseller) {
        List<String> lines = new ArrayList<>();
        lines.add(Messages.get("handover.summary.given", given, reseller.name()));
        if (!plan.already().isEmpty()) {
            lines.add(Messages.get("handover.summary.already", plan.already().size()));
        }
        if (!plan.sold().isEmpty()) {
            lines.add(Messages.get("handover.summary.sold", plan.sold().size()));
        }
        if (!plan.noPrice().isEmpty()) {
            lines.add(Messages.get("handover.summary.noPrice", plan.noPrice().size(), String.join(", ",
                    plan.noPrice().stream().map(Card::profile).distinct().toList())));
        }
        return String.join("\n", lines);
    }
}
