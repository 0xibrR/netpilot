package org.netpilot.controller;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.netpilot.NetPilotApp;
import org.netpilot.i18n.Messages;
import org.netpilot.service.MonthlyReport;

/** Asks which month the monthly PDF report covers and which parts it includes. */
final class MonthlyReportDialog {

    /** What the user chose. */
    record Choice(YearMonth month, Set<MonthlyReport.Section> sections) {
    }

    private MonthlyReportDialog() {
    }

    /** @param months the months to offer, newest first; the first is selected */
    static Optional<Choice> show(Window owner, List<YearMonth> months) {
        YearMonth current = months.isEmpty() ? YearMonth.now() : months.getFirst();
        ComboBox<YearMonth> monthBox = new ComboBox<>();
        monthBox.getItems().setAll(months);
        DateTimeFormatter format = DateTimeFormatter.ofPattern("MMMM yyyy", Messages.locale());
        monthBox.setConverter(new StringConverter<>() {

            @Override
            public String toString(YearMonth month) {
                if (month == null) {
                    return "";
                }
                String name = month.format(format);
                return month.equals(YearMonth.now()) ? name + " " + Messages.get("monthly.dialog.current") : name;
            }

            @Override
            public YearMonth fromString(String text) {
                return current;
            }
        });
        // last month is the usual monthly report; the current one is offered too, as "so far"
        monthBox.setValue(months.size() > 1 && current.equals(YearMonth.now()) ? months.get(1) : current);
        monthBox.setPrefWidth(220);

        Map<MonthlyReport.Section, CheckBox> boxes = new EnumMap<>(MonthlyReport.Section.class);
        VBox sections = new VBox(6);
        for (MonthlyReport.Section section : MonthlyReport.Section.values()) {
            CheckBox box = new CheckBox(Messages.get("monthly.section." + section.name()));
            box.setSelected(true);
            boxes.put(section, box);
            sections.getChildren().add(box);
        }

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.setPadding(new Insets(10, 4, 4, 4));
        form.addRow(0, new Label(Messages.get("monthly.dialog.month")), monthBox);
        Label include = new Label(Messages.get("monthly.dialog.sections"));
        form.add(include, 0, 1);
        form.add(sections, 1, 1);
        GridPane.setValignment(include, javafx.geometry.VPos.TOP);

        Dialog<Choice> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(NetPilotApp.NAME);
        dialog.setHeaderText(Messages.get("monthly.dialog.title"));
        ButtonType create = new ButtonType(Messages.get("monthly.dialog.create"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(create, cancel);
        dialog.getDialogPane().setContent(form);
        Styling.apply(dialog.getDialogPane());
        // at least one part, or the report would be just a header
        dialog.getDialogPane().lookupButton(create).disableProperty().bind(javafx.beans.binding.Bindings
                .createBooleanBinding(() -> boxes.values().stream().noneMatch(CheckBox::isSelected),
                        boxes.values().stream().map(CheckBox::selectedProperty)
                                .toArray(javafx.beans.Observable[]::new)));
        dialog.setResultConverter(button -> {
            if (button != create || monthBox.getValue() == null) {
                return null;
            }
            Set<MonthlyReport.Section> chosen = EnumSet.noneOf(MonthlyReport.Section.class);
            boxes.forEach((section, box) -> {
                if (box.isSelected()) {
                    chosen.add(section);
                }
            });
            return new Choice(monthBox.getValue(), chosen);
        });
        return dialog.showAndWait();
    }
}
