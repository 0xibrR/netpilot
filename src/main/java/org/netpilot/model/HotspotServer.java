package org.netpilot.model;

import java.util.Objects;

/**
 * A hotspot server on the router ({@code /ip hotspot}).
 *
 * @param name          server name used when creating users
 * @param interfaceName interface the server runs on, e.g. {@code bridge_hotspot}
 * @param disabled      whether the server is disabled
 */
public record HotspotServer(String name, String interfaceName, boolean disabled) {

    public HotspotServer {
        Objects.requireNonNull(name, "name");
        interfaceName = interfaceName == null ? "" : interfaceName;
    }
}
