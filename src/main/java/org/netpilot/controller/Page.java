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
}
