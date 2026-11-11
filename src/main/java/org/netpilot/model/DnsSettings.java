package org.netpilot.model;

import java.util.Arrays;
import java.util.List;

/**
 * The router's DNS settings ({@code /ip dns}), read only.
 *
 * @param servers        servers set by hand
 * @param dynamicServers servers learned automatically (DHCP or PPPoE from the internet provider)
 * @param remoteRequests the router answers DNS questions from the network (hotspot users need this)
 */
public record DnsSettings(List<String> servers, List<String> dynamicServers, boolean remoteRequests) {

    public DnsSettings {
        servers = List.copyOf(servers);
        dynamicServers = List.copyOf(dynamicServers);
    }

    /** {@code 8.8.8.8,1.1.1.1} → both; blank → none. */
    public static List<String> split(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        return Arrays.stream(commaSeparated.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** Neither a hand-set nor a learned server: the router cannot look names up. */
    public boolean noServer() {
        return servers.isEmpty() && dynamicServers.isEmpty();
    }
}
