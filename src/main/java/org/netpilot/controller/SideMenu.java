package org.netpilot.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.netpilot.i18n.Messages;

/**
 * The side menu: single entries and folding groups, like "Cards" holding everything about cards.
 *
 * <p>An entry opens a page, and optionally one part of it ({@link Page#open(String)}), e.g. the
 * Hotspot page on its "active" tab.
 */
final class SideMenu {

    /** A menu line: the page it opens and, optionally, the part of the page. */
    record Entry(String labelKey, PageId page, String section) {

        String key() {
            return page.name() + "/" + (section == null ? "" : section);
        }
    }

    /** A folding group of entries. */
    record Group(String labelKey, String icon, List<Entry> entries) {
    }

    /** The menu, top to bottom: entries are single lines, groups fold. */
    static final List<Object> LAYOUT = List.of(
            new Entry("nav.DASHBOARD", PageId.DASHBOARD, null),
            new Group("nav.group.cards", Icons.WIFI, List.of(
                    new Entry("nav.GENERATE", PageId.GENERATE, null),
                    // one page holds the card list, who is online (tab) and the search box
                    new Entry("nav.cards.users", PageId.HOTSPOT, HotspotController.USERS),
                    new Entry("nav.cards.sales", PageId.REPORTS, null),
                    new Entry("nav.DESIGN", PageId.DESIGN, null),
                    new Entry("nav.HISTORY", PageId.HISTORY, null))),
            new Entry("nav.SETTINGS", PageId.SETTINGS, null));

    private final ToggleGroup toggles = new ToggleGroup();
    private final Map<String, ToggleButton> buttons = new LinkedHashMap<>();
    private final Map<String, VBox> groupOf = new LinkedHashMap<>();

    /**
     * @param open called with the page and section when an entry is clicked
     */
    SideMenu(VBox host, BiConsumer<PageId, String> open) {
        for (Object item : LAYOUT) {
            if (item instanceof Entry entry) {
                host.getChildren().add(entryButton(entry, iconFor(entry.page()), "nav-button", open));
            } else if (item instanceof Group group) {
                host.getChildren().add(group(group, open));
            }
        }
        // a selected entry stays selected when clicked again
        toggles.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });
    }

    /** Marks the entry for this page and section as selected, and unfolds its group. */
    void select(PageId page, String section) {
        Optional<ToggleButton> exact = Optional.ofNullable(buttons.get(page.name() + "/" + (section == null
                ? ""
                : section)));
        Optional<ToggleButton> button = exact.or(() -> buttons.entrySet().stream()
                .filter(e -> e.getKey().startsWith(page.name() + "/")).map(Map.Entry::getValue).findFirst());
        button.ifPresent(b -> {
            b.setSelected(true);
            VBox items = groupOf.get(keyOf(b));
            if (items != null) {
                fold(items, true);
            }
        });
    }

    private VBox group(Group group, BiConsumer<PageId, String> open) {
        Label chevron = new Label("›");
        chevron.getStyleClass().add("nav-chevron");
        Label title = new Label(Messages.get(group.labelKey()), Icons.of(group.icon()));
        title.getStyleClass().add("nav-group-title");
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox headerContent = new HBox(10, title, space, chevron);
        headerContent.setAlignment(Pos.CENTER_LEFT);
        Button header = new Button();
        header.setGraphic(headerContent);
        header.getStyleClass().add("nav-group");
        header.setMaxWidth(Double.MAX_VALUE);
        headerContent.prefWidthProperty().bind(header.widthProperty().subtract(28));

        VBox items = new VBox(1);
        items.getStyleClass().add("nav-group-items");
        for (Entry entry : group.entries()) {
            // only the group has an icon; its entries are plain, indented text
            ToggleButton button = entryButton(entry, null, "nav-sub", open);
            groupOf.put(entry.key(), items);
            items.getChildren().add(button);
        }
        header.setOnAction(e -> fold(items, !items.isVisible()));
        // "⌄" when open; "›" when folded (shown as "‹" in right-to-left, pointing into the menu)
        items.visibleProperty().addListener((obs, was, unfolded) -> chevron.setText(unfolded ? "⌄" : "›"));
        fold(items, false);
        return new VBox(header, items);
    }

    private ToggleButton entryButton(Entry entry, javafx.scene.Node graphic, String style,
            BiConsumer<PageId, String> open) {
        ToggleButton button = new ToggleButton(Messages.get(entry.labelKey()), graphic);
        button.getStyleClass().add(style);
        button.setToggleGroup(toggles);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setUserData(entry.key());
        button.setOnAction(e -> open.accept(entry.page(), entry.section()));
        buttons.put(entry.key(), button);
        return button;
    }

    private static void fold(VBox items, boolean open) {
        items.setVisible(open);
        items.setManaged(open);
    }

    private static String keyOf(ToggleButton button) {
        return String.valueOf(button.getUserData());
    }

    private static javafx.scene.Node iconFor(PageId page) {
        return Icons.of(page.icon());
    }
}
