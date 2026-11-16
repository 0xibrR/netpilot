package org.netpilot.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.netpilot.model.Alert;
import org.netpilot.model.Alert.Kind;
import org.netpilot.model.AlertSettings;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.InterfaceState;
import org.netpilot.model.RouterDuration;
import org.netpilot.model.RouterResource;

/**
 * Turns router readings into alerts. Each problem is raised once, when it starts, and resolved
 * once, when it ends, so a problem that lasts an hour is one alert, not seven hundred. Only
 * changes count for interfaces and hotspot servers: one that was already down when NetPilot first
 * looked was put that way on purpose, so it is no alert. Not tied to JavaFX; safe from any thread.
 */
public final class AlertMonitor {

    /** Failed reads in a row before the connected router counts as not responding. */
    public static final int CONNECTED_FAILURES = 3;

    /** Failed checks in a row before a branch watched in the background counts as not responding. */
    public static final int BACKGROUND_FAILURES = 2;

    /**
     * What one reading changed.
     *
     * @param alert  the alert, with {@code resolved} set when it ended
     * @param raised true when it started, false when it ended
     */
    public record Event(Alert alert, boolean raised) {
    }

    /**
     * One look at a router; a part that was not read is empty and leaves its alerts as they are.
     *
     * @param dnsOpen remote DNS requests on and no firewall rule blocking port 53
     */
    public record Reading(Optional<RouterResource> resource, Optional<List<InterfaceState>> interfaces,
            Optional<List<HotspotServer>> servers, Optional<Boolean> dnsOpen) {

        public static Reading of(RouterResource resource) {
            return new Reading(Optional.of(resource), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** What is known about one router between readings. */
    private static final class State {

        Optional<LocalDateTime> cpuHighSince = Optional.empty();
        Optional<Duration> lastUptime = Optional.empty();
        final Map<String, Boolean> interfaceUp = new HashMap<>();
        final Map<String, Boolean> serverOff = new HashMap<>();
        int failures;
        final Map<String, Alert> active = new HashMap<>();
    }

    private final Map<String, State> routers = new HashMap<>();
    private final Supplier<String> ids;

    public AlertMonitor() {
        this(() -> UUID.randomUUID().toString());
    }

    AlertMonitor(Supplier<String> ids) {
        this.ids = ids;
    }

    /** A successful reading of a router. */
    public synchronized List<Event> observe(String address, String branch, Reading reading, AlertSettings settings,
            LocalDateTime now) {
        State s = routers.computeIfAbsent(address, a -> new State());
        List<Event> events = new ArrayList<>();
        s.failures = 0;
        resolve(s, Kind.UNREACHABLE, "", now, events);

        reading.resource().ifPresent(r -> {
            // high CPU only after it stayed high for the set minutes
            if (r.cpuLoad() >= settings.cpuPercent()) {
                s.cpuHighSince = Optional.of(s.cpuHighSince.orElse(now));
                if (!s.cpuHighSince.get().plusMinutes(settings.cpuMinutes()).isAfter(now)) {
                    raise(s, settings, Kind.CPU_HIGH, address, branch, String.valueOf(r.cpuLoad()), now, events);
                }
            } else {
                s.cpuHighSince = Optional.empty();
                resolve(s, Kind.CPU_HIGH, "", now, events);
            }
            if (r.totalMemory() > 0) {
                long freePercent = r.freeMemory() * 100 / r.totalMemory();
                if (freePercent < settings.memoryPercent()) {
                    raise(s, settings, Kind.MEMORY_LOW, address, branch, String.valueOf(freePercent), now, events);
                } else {
                    resolve(s, Kind.MEMORY_LOW, "", now, events);
                }
            }
            Optional<Duration> uptime = RouterDuration.parse(r.uptime());
            if (uptime.isPresent() && s.lastUptime.isPresent() && uptime.get().compareTo(s.lastUptime.get()) < 0
                    && settings.on(Kind.REBOOTED)) {
                // over as soon as it is seen: raised and resolved at once, with how long it had been up
                Alert reboot = new Alert(ids.get(), Kind.REBOOTED, address, branch,
                        RouterDuration.clock(s.lastUptime.get()), now, Optional.of(now));
                events.add(new Event(reboot, true));
            }
            uptime.ifPresent(u -> s.lastUptime = Optional.of(u));
        });

        reading.interfaces().ifPresent(list -> {
            for (InterfaceState i : list) {
                Boolean before = s.interfaceUp.get(i.name());
                if (i.disabled()) {
                    s.interfaceUp.remove(i.name());
                    resolve(s, Kind.INTERFACE_DOWN, i.name(), now, events); // turned off on purpose
                    continue;
                }
                if (Boolean.TRUE.equals(before) && !i.running()) {
                    raise(s, settings, Kind.INTERFACE_DOWN, address, branch, i.name(), now, events);
                } else if (i.running()) {
                    resolve(s, Kind.INTERFACE_DOWN, i.name(), now, events);
                }
                s.interfaceUp.put(i.name(), i.running());
            }
        });

        reading.servers().ifPresent(list -> {
            for (HotspotServer server : list) {
                Boolean wasOff = s.serverOff.get(server.name());
                if (Boolean.FALSE.equals(wasOff) && server.disabled()) {
                    raise(s, settings, Kind.HOTSPOT_STOPPED, address, branch, server.name(), now, events);
                } else if (!server.disabled()) {
                    resolve(s, Kind.HOTSPOT_STOPPED, server.name(), now, events);
                }
                s.serverOff.put(server.name(), server.disabled());
            }
        });

        reading.dnsOpen().ifPresent(open -> {
            if (open) {
                raise(s, settings, Kind.DNS_OPEN, address, branch, "", now, events);
            } else {
                resolve(s, Kind.DNS_OPEN, "", now, events);
            }
        });
        return events;
    }

    /**
     * A failed reading. The router counts as not responding after {@code threshold} failures in
     * a row; its other alerts stay as they are until it answers again.
     */
    public synchronized List<Event> failed(String address, String branch, int threshold, AlertSettings settings,
            LocalDateTime now) {
        State s = routers.computeIfAbsent(address, a -> new State());
        List<Event> events = new ArrayList<>();
        s.failures++;
        if (s.failures >= threshold) {
            raise(s, settings, Kind.UNREACHABLE, address, branch, "", now, events);
        }
        return events;
    }

    /** Forgets a router, e.g. a branch removed from the list. */
    public synchronized void forget(String address) {
        routers.remove(address);
    }

    private void raise(State s, AlertSettings settings, Kind kind, String address, String branch, String detail,
            LocalDateTime now, List<Event> events) {
        String key = key(kind, kind == Kind.INTERFACE_DOWN || kind == Kind.HOTSPOT_STOPPED ? detail : "");
        if (!settings.on(kind) || s.active.containsKey(key)) {
            return; // off, or already raised: never twice
        }
        Alert alert = new Alert(ids.get(), kind, address, branch, detail, now, Optional.empty());
        s.active.put(key, alert);
        events.add(new Event(alert, true));
    }

    private static void resolve(State s, Kind kind, String detail, LocalDateTime now, List<Event> events) {
        Alert alert = s.active.remove(key(kind, detail));
        if (alert != null) {
            events.add(new Event(alert.resolvedAt(now), false));
        }
    }

    private static String key(Kind kind, String detail) {
        return kind + ":" + detail;
    }
}
