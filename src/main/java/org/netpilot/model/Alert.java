package org.netpilot.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * A router problem NetPilot noticed: raised once when it starts, resolved once when it ends.
 *
 * @param id       unique, to resolve the right one later
 * @param kind     what happened
 * @param address  the router's address (its records' key)
 * @param branch   the branch name at the time, for the list
 * @param detail   which interface or hotspot server, or how long it had been up; empty when none
 * @param started  when it was noticed
 * @param resolved when it ended; empty while it lasts
 */
public record Alert(String id, Kind kind, String address, String branch, String detail, LocalDateTime started,
        Optional<LocalDateTime> resolved) {

    /** What can be noticed. */
    public enum Kind {
        /** CPU load above the threshold for the set minutes. */
        CPU_HIGH,
        /** Free memory below the threshold. */
        MEMORY_LOW,
        /** Several reads in a row failed. */
        UNREACHABLE,
        /** The router started again (its uptime went back); over as soon as it is seen. */
        REBOOTED,
        /** An interface that was up is down. */
        INTERFACE_DOWN,
        /** A hotspot server that was running is disabled. */
        HOTSPOT_STOPPED,
        /** The router answers DNS for the network and no firewall rule keeps the internet out. */
        DNS_OPEN
    }

    public Alert {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(started, "started");
        branch = branch == null ? "" : branch;
        detail = detail == null ? "" : detail;
        resolved = resolved == null ? Optional.empty() : resolved;
    }

    public boolean active() {
        return resolved.isEmpty();
    }

    public Alert resolvedAt(LocalDateTime when) {
        return new Alert(id, kind, address, branch, detail, started, Optional.of(when));
    }
}
