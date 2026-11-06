package org.netpilot;

import javafx.application.Application;

/**
 * Entry point. Kept separate from {@link NetPilotApp} so the app also starts from a plain
 * classpath (e.g. {@code java -jar}) where the main class must not extend {@code Application}.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(NetPilotApp.class, args);
    }
}
