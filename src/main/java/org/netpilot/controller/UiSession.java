package org.netpilot.controller;

/**
 * What survives when the main window is rebuilt in another language: the router connection
 * and the page that was open. The application creates one and keeps it for the whole run.
 */
public final class UiSession {

    private RouterSession router;
    private PageId startPage = PageId.DASHBOARD;
    private boolean autoConnectTried;
    private Runnable reloader = () -> {
    };

    /** How the application rebuilds the main window (after a language change). */
    public void setReloader(Runnable reloader) {
        this.reloader = reloader;
    }

    RouterSession router() {
        return router;
    }

    void keep(RouterSession session) {
        this.router = session;
    }

    PageId startPage() {
        return startPage;
    }

    /** Rebuilds the main window and reopens {@code page}, keeping the router connected. */
    void reload(PageId page) {
        this.startPage = page;
        reloader.run();
    }

    /** Automatic connection runs once per start of the app, not after each language switch. */
    boolean firstAutoConnect() {
        boolean first = !autoConnectTried;
        autoConnectTried = true;
        return first;
    }
}
