package org.netpilot.controller;

/**
 * A page shown in the main window's content area. Pages are created once and kept, so their
 * state (filters, scroll position, a generated preview) survives switching pages.
 */
interface Page extends RouterSession.Listener {

    /** Called once, right after the page is loaded. */
    void attach(Workspace workspace);

    /** Called each time the page becomes visible. */
    default void shown() {
    }

    /**
     * Called every few seconds while the page is visible and a router is connected, so changes
     * made elsewhere (WinBox, another NetPilot) show up without pressing Refresh. Must stay quiet:
     * no dialogs, no busy indicators, and keep the user's selection and filters.
     */
    default void tick() {
    }
}
