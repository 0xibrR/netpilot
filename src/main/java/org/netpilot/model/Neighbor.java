package org.netpilot.model;

import java.time.Duration;
import java.util.Objects;

/**
 * A MikroTik router found on the local network, as WinBox lists it under Neighbors.
 *
 * @param macAddress    MAC address of the interface that answered, e.g. {@code D4:01:C3:79:48:B4}
 * @param address       IPv4 address, or empty if the interface has none (then NetPilot cannot connect)
 * @param identity      router name ({@code /system identity})
 * @param version       RouterOS version, e.g. {@code 7.25.1 (stable)}
 * @param board         hardware model, e.g. {@code RB960PGS}
 * @param uptime        time since boot
 * @param interfaceName router interface that answered, e.g. {@code bridge_LAN/ether2}
 */
public record Neighbor(String macAddress, String address, String identity, String version, String board,
        Duration uptime, String interfaceName) {

    public Neighbor {
        macAddress = orEmpty(macAddress);
        address = orEmpty(address);
        identity = orEmpty(identity);
        version = orEmpty(version);
        board = orEmpty(board);
        uptime = Objects.requireNonNullElse(uptime, Duration.ZERO);
        interfaceName = orEmpty(interfaceName);
    }

    /** NetPilot talks to the REST API, which needs an IP address. */
    public boolean reachable() {
        return !address.isEmpty();
    }

    /** One router can answer on several interfaces; the MAC address identifies the answer. */
    public String key() {
        return macAddress.isEmpty() ? address : macAddress;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.strip();
    }
}
