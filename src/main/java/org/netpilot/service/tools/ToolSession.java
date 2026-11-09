package org.netpilot.service.tools;

/**
 * A developer tool running in the background (ping today; traceroute, bandwidth test or DNS
 * lookup later). It reports through its own listener, off the JavaFX thread, and can be stopped
 * at any time.
 */
public interface ToolSession {

    /** Asks the tool to stop; it finishes its current step and reports that it ended. */
    void stop();

    boolean running();
}
