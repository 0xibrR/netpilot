package org.netpilot.model;

import java.util.Objects;

/**
 * One router interface as {@code /interface} reports it.
 *
 * @param name     e.g. {@code ether1}, {@code wlan1}, {@code bridge-hotspot}
 * @param type     e.g. {@code ether}, {@code wlan}, {@code bridge}
 * @param running  the link is up
 * @param disabled turned off on purpose (never an alert)
 */
public record InterfaceState(String name, String type, boolean running, boolean disabled) {

    public InterfaceState {
        Objects.requireNonNull(name, "name");
        type = type == null ? "" : type;
    }
}
