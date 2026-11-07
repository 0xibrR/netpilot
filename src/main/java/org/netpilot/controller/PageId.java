package org.netpilot.controller;

/**
 * The pages of the side menu, in menu order.
 *
 * @param view         FXML file under {@code view/}
 * @param icon         SVG path drawn in the side menu (24×24 outline icons)
 * @param needsRouter  whether the page only works while a router is connected
 */
enum PageId {

    DASHBOARD("dashboard-view.fxml", Icons.DASHBOARD, false),
    GENERATE("generate-view.fxml", Icons.TICKET, false),
    HOTSPOT("hotspot-view.fxml", Icons.WIFI, true),
    REPORTS("reports-view.fxml", Icons.CHART, true),
    DESIGN("voucher-design-view.fxml", Icons.PALETTE, false),
    HISTORY("history-view.fxml", Icons.HISTORY, false),
    SETTINGS("settings-view.fxml", Icons.SETTINGS, false);

    private final String view;
    private final String icon;
    private final boolean needsRouter;

    PageId(String view, String icon, boolean needsRouter) {
        this.view = view;
        this.icon = icon;
        this.needsRouter = needsRouter;
    }

    String view() {
        return "view/" + view;
    }

    String icon() {
        return icon;
    }

    boolean needsRouter() {
        return needsRouter;
    }

    /** Message key of the menu label, e.g. {@code nav.HOTSPOT}. */
    String labelKey() {
        return "nav." + name();
    }
}
