package org.netpilot.controller;

import org.netpilot.model.Role;
import org.netpilot.model.Role.Permission;

/**
 * The pages of the side menu, in menu order.
 *
 * @param view         FXML file under {@code view/}
 * @param icon         SVG path drawn in the side menu (24×24 outline icons)
 * @param needsRouter  whether the page only works while a router is connected
 * @param permission   what the role must be allowed to open it (app protection); null for everyone
 */
enum PageId {

    DASHBOARD("dashboard-view.fxml", Icons.DASHBOARD, false, null),
    GENERATE("generate-view.fxml", Icons.TICKET, false, Permission.CREATE_BATCHES),
    HOTSPOT("hotspot-view.fxml", Icons.USERS, true, Permission.VIEW_USERS),
    DESIGN("voucher-design-view.fxml", Icons.PALETTE, false, Permission.CARD_DESIGN),
    RESELLERS("resellers-view.fxml", Icons.STORE, false, Permission.RESELLER_PAYMENT),
    REPORTS("reports-view.fxml", Icons.CHART, true, Permission.REPORTS),
    HISTORY("history-view.fxml", Icons.HISTORY, false, Permission.REPORTS),
    PING("ping-view.fxml", Icons.PULSE, true, Permission.DEVELOPER_TOOLS),
    TRACEROUTE("traceroute-view.fxml", Icons.ROUTE, true, Permission.DEVELOPER_TOOLS),
    DNS("dns-view.fxml", Icons.GLOBE, true, Permission.DEVELOPER_TOOLS),
    ROUTER_LOG("router-log-view.fxml", Icons.LOG, true, Permission.DEVELOPER_TOOLS),
    SETTINGS("settings-view.fxml", Icons.SETTINGS, false, Permission.SETTINGS);

    private final String view;
    private final String icon;
    private final boolean needsRouter;
    /** What the role must be allowed to open the page; null: everyone. */
    private final Permission permission;

    PageId(String view, String icon, boolean needsRouter, Permission permission) {
        this.permission = permission;
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

    /** Whether this role may open the page. */
    boolean openFor(Role role) {
        return permission == null || role.can(permission);
    }

}
