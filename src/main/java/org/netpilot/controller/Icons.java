package org.netpilot.controller;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * Small outline icons drawn as SVG paths on a 24×24 grid, styled by CSS ({@code .icon}).
 */
final class Icons {

    static final String DASHBOARD = "M4 4h6v8H4z M14 4h6v4h-6z M14 12h6v8h-6z M4 16h6v4H4z";
    static final String TICKET = "M15 5v2 M15 11v2 M15 17v2 M5 5h14a2 2 0 0 1 2 2v3a2 2 0 0 0 0 4v3a2 2 0 0 1-2 2H5"
            + "a2 2 0 0 1-2-2v-3a2 2 0 0 0 0-4V7a2 2 0 0 1 2-2";
    static final String WIFI = "M12 18h.01 M9.2 15.2a4 4 0 0 1 5.6 0 M6.3 12.3a8 8 0 0 1 11.4 0"
            + " M3.5 9.5a12 12 0 0 1 17 0";
    /** A command prompt, for the developer tools group. */
    static final String TERMINAL = "M4 17l6-5-6-5 M12 19h8";
    /** A heartbeat line, for ping. */
    static final String PULSE = "M3 12h4l3-8 4 16 3-8h4";
    /** Two people, for Manage users. */
    static final String USERS = "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M2 21v-1a6 6 0 0 1 12 0v1 M16 3.1a4 4 0 0 1 0 7.8 M22 21v-1a6 6 0 0 0-4-5.6";
    /** A rising line, for the reports and money group. */
    static final String FINANCE = "M3 20h18 M5 16l4-5 4 3 6-8 M15 6h4v4";
    /** A shop front, for points of sale. */
    static final String STORE = "M3 9l1.5-5h15L21 9 M3 9h18v1.5a3 3 0 0 1-6 0 3 3 0 0 1-6 0 3 3 0 0 1-6 0z M5 13v7h14v-7 M10 20v-4h4v4";
    static final String CHART = "M4 13h4v7H4z M10 9h4v11h-4z M16 5h4v15h-4z";
    static final String PALETTE = "M12 21a9 9 0 0 1 0-18c5 0 9 3.6 9 8 0 2.2-1.9 4-4.5 4H14a2 2 0 0 0-1 3.7"
            + "A1.3 1.3 0 0 1 12 21 M7.5 10.5h.01 M12 7.5h.01 M16.5 10.5h.01";
    static final String HISTORY = "M12 8v4l2 2 M3.1 11a9 9 0 1 1 .5 4 M3 20v-5h5";
    static final String SETTINGS = "M4 6h8 M16 6h4 M4 12h2 M10 12h10 M4 18h11 M19 18h1 M14 4v4 M8 10v4"
            + " M17 16v4";
    static final String ROUTER = "M3 13h18v6H3z M7 16h.01 M11 16h.01 M15 9.5a3 3 0 0 1 4 0 M13 7a6 6 0 0 1 8 0";
    static final String PLUS = "M12 5v14 M5 12h14";
    static final String CHECK = "M5 12l5 5L20 7";
    static final String PAUSE = "M8 5v14 M16 5v14";
    static final String CROSS = "M18 6L6 18 M6 6l12 12";
    static final String PRINTER = "M17 17h2a2 2 0 0 0 2-2v-4a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v4a2 2 0 0 0 2 2h2"
            + " M17 9V5a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2v4 M7 13h10v8H7z";
    static final String REFRESH = "M20 11a8 8 0 0 0-15.5-2 M4 5v4h4 M4 13a8 8 0 0 0 15.5 2 M20 19v-4h-4";
    static final String UNPLUG = "M7 7l10 10 M12 3v4 M8 7h8v4a4 4 0 0 1-8 0z M12 15v6";
    static final String CLOCK = "M12 7v5l3 2 M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0";
    static final String MOON = "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9";
    static final String SEND = "M10 14L21 3 M21 3l-6.5 18a.6.6 0 0 1-1 0L10 14l-7-3.5a.6.6 0 0 1 0-1z";
    static final String DOWNLOAD = "M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2 M7 11l5 5 5-5 M12 4v12";
    static final String FOLDER = "M5 4h4l3 3h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2";

    private Icons() {
    }

    /** An 18 px icon whose stroke colour comes from CSS. */
    static Node of(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("icon");
        icon.setScaleX(0.75);
        icon.setScaleY(0.75);
        return new javafx.scene.Group(icon);
    }
}
