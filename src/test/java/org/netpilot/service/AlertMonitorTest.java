package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.netpilot.model.Alert;
import org.netpilot.model.Alert.Kind;
import org.netpilot.model.AlertSettings;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.InterfaceState;
import org.netpilot.model.RouterResource;
import org.netpilot.repository.AlertLog;
import org.netpilot.repository.SettingsRepository;

class AlertMonitorTest {

    private static final String ROUTER = "10.0.0.1:80";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 16, 22, 0);
    private static final AlertSettings ALL = AlertSettings.defaults();

    @TempDir
    Path dir;

    private final AtomicInteger ids = new AtomicInteger();
    private final AlertMonitor monitor = new AlertMonitor(() -> "a" + ids.incrementAndGet());

    private static RouterResource load(int cpu, int freePercent, String uptime) {
        return new RouterResource(cpu, freePercent * 10L, 1000, uptime);
    }

    private List<AlertMonitor.Event> see(RouterResource r, LocalDateTime at) {
        return monitor.observe(ROUTER, "المحل", AlertMonitor.Reading.of(r), ALL, at);
    }

    private static List<String> raised(List<AlertMonitor.Event> events) {
        return events.stream().filter(AlertMonitor.Event::raised).map(e -> e.alert().kind().name()).toList();
    }

    private static List<String> ended(List<AlertMonitor.Event> events) {
        return events.stream().filter(e -> !e.raised()).map(e -> e.alert().kind().name()).toList();
    }

    @Test
    void highCpuIsAnAlertOnlyAfterItStaysHighAndEndsOnce() {
        assertTrue(see(load(95, 50, "1h"), T0).isEmpty(), "just went up");
        assertTrue(see(load(97, 50, "1h"), T0.plusSeconds(60)).isEmpty(), "one minute: not yet");
        List<AlertMonitor.Event> two = see(load(96, 50, "1h"), T0.plusSeconds(120));
        assertEquals(List.of("CPU_HIGH"), raised(two));
        assertEquals("96", two.getFirst().alert().detail());
        assertTrue(see(load(99, 50, "1h"), T0.plusSeconds(180)).isEmpty(), "never twice while it lasts");
        assertEquals(List.of("CPU_HIGH"), ended(see(load(20, 50, "1h"), T0.plusSeconds(240))));

        // a short spike: up and down within the minutes set
        assertTrue(see(load(99, 50, "1h"), T0.plusSeconds(300)).isEmpty());
        assertTrue(see(load(10, 50, "1h"), T0.plusSeconds(330)).isEmpty());
        assertTrue(see(load(99, 50, "1h"), T0.plusSeconds(360)).isEmpty(), "the wait starts again");
    }

    @Test
    void lowMemoryAndARestartAreNoticed() {
        assertEquals(List.of("MEMORY_LOW"), raised(see(load(5, 4, "2d3h"), T0)));
        assertEquals(List.of("MEMORY_LOW"), ended(see(load(5, 30, "2d3h1m"), T0.plusMinutes(1))));

        List<AlertMonitor.Event> restart = see(load(5, 30, "40s"), T0.plusMinutes(2));
        assertEquals(List.of("REBOOTED"), raised(restart));
        Alert reboot = restart.getFirst().alert();
        assertFalse(reboot.active(), "a restart is over as soon as it is seen");
        assertEquals("2d 03:01:00", reboot.detail(), "it had been up 2 days 3 hours 1 minute");
        assertTrue(see(load(5, 30, "1m40s"), T0.plusMinutes(3)).isEmpty(), "uptime growing again is fine");
    }

    @Test
    void aRouterIsNotRespondingAfterSeveralFailuresAndBackOnTheNextReading() {
        assertTrue(monitor.failed(ROUTER, "المحل", 3, ALL, T0).isEmpty());
        assertTrue(monitor.failed(ROUTER, "المحل", 3, ALL, T0.plusSeconds(5)).isEmpty());
        assertEquals(List.of("UNREACHABLE"), raised(monitor.failed(ROUTER, "المحل", 3, ALL, T0.plusSeconds(10))));
        assertTrue(monitor.failed(ROUTER, "المحل", 3, ALL, T0.plusSeconds(15)).isEmpty(), "once");
        assertEquals(List.of("UNREACHABLE"), ended(see(load(5, 50, "1h"), T0.plusSeconds(20))));
        assertTrue(monitor.failed(ROUTER, "المحل", 3, ALL, T0.plusSeconds(25)).isEmpty(), "counting starts again");
    }

    private List<AlertMonitor.Event> seeInterfaces(LocalDateTime at, InterfaceState... list) {
        return monitor.observe(ROUTER, "المحل", new AlertMonitor.Reading(Optional.empty(), Optional.of(List.of(list)),
                Optional.empty(), Optional.empty()), ALL, at);
    }

    @Test
    void anInterfaceThatGoesDownIsAnAlertButOneAlreadyDownOrTurnedOffIsNot() {
        assertTrue(seeInterfaces(T0, new InterfaceState("ether1", "ether", true, false),
                new InterfaceState("ether5", "ether", false, false)).isEmpty(), "ether5 was down from the start");

        List<AlertMonitor.Event> down = seeInterfaces(T0.plusMinutes(1),
                new InterfaceState("ether1", "ether", false, false),
                new InterfaceState("ether5", "ether", false, false));
        assertEquals(List.of("INTERFACE_DOWN"), raised(down));
        assertEquals("ether1", down.getFirst().alert().detail());
        assertEquals(List.of("INTERFACE_DOWN"), ended(seeInterfaces(T0.plusMinutes(2),
                new InterfaceState("ether1", "ether", true, false))));

        seeInterfaces(T0.plusMinutes(3), new InterfaceState("wlan1", "wlan", true, false));
        assertTrue(seeInterfaces(T0.plusMinutes(4), new InterfaceState("wlan1", "wlan", false, true)).isEmpty(),
                "turned off on purpose");
    }

    @Test
    void aStoppedHotspotServerAndOpenDnsAreNoticed() {
        AlertMonitor.Reading running = new AlertMonitor.Reading(Optional.empty(), Optional.empty(),
                Optional.of(List.of(new HotspotServer("hs1", "bridge", false))), Optional.of(false));
        assertTrue(monitor.observe(ROUTER, "المحل", running, ALL, T0).isEmpty());
        AlertMonitor.Reading stopped = new AlertMonitor.Reading(Optional.empty(), Optional.empty(),
                Optional.of(List.of(new HotspotServer("hs1", "bridge", true))), Optional.of(true));
        List<AlertMonitor.Event> events = monitor.observe(ROUTER, "المحل", stopped, ALL, T0.plusMinutes(1));
        assertEquals(Set.of("HOTSPOT_STOPPED", "DNS_OPEN"), Set.copyOf(raised(events)));
        assertEquals(Set.of("HOTSPOT_STOPPED", "DNS_OPEN"),
                Set.copyOf(ended(monitor.observe(ROUTER, "المحل", running, ALL, T0.plusMinutes(2)))));
    }

    @Test
    void anAlertTurnedOffInSettingsIsNeverRaised() {
        AlertSettings noCpu = new AlertSettings(EnumSet.complementOf(EnumSet.of(Kind.CPU_HIGH)), 85, 1, 10, true, 5,
                Set.of());
        monitor.observe(ROUTER, "x", AlertMonitor.Reading.of(load(99, 50, "1h")), noCpu, T0);
        assertTrue(monitor.observe(ROUTER, "x", AlertMonitor.Reading.of(load(99, 50, "1h")), noCpu, T0.plusMinutes(5))
                .isEmpty());
        assertTrue(monitor.observe("other:80", "y", AlertMonitor.Reading.of(load(5, 50, "1h")), noCpu, T0).isEmpty(),
                "each router is watched on its own");
    }

    @Test
    void theLogKeepsAlertsRecordsTheirEndAndClosesOldOnesAtStart() throws Exception {
        AlertLog log = new AlertLog(dir.resolve("alerts.csv"));
        Alert open = new Alert("a1", Kind.INTERFACE_DOWN, ROUTER, "المحل, الرئيسي", "ether1", T0, Optional.empty());
        log.save(open);
        log.save(new Alert("a2", Kind.UNREACHABLE, ROUTER, "المحل", "", T0, Optional.empty()));
        log.save(open.resolvedAt(T0.plusMinutes(5)));

        List<Alert> back = new AlertLog(dir.resolve("alerts.csv")).all();
        assertEquals(2, back.size());
        assertEquals(Optional.of(T0.plusMinutes(5)), back.getFirst().resolved());
        assertEquals("المحل, الرئيسي", back.getFirst().branch(), "commas kept");
        assertTrue(back.get(1).active());

        log.closeOpen(T0.plusHours(1));
        assertTrue(log.all().stream().noneMatch(Alert::active), "nothing is left open from an earlier run");
        for (int i = 0; i < AlertLog.KEEP + 10; i++) {
            log.save(new Alert("n" + i, Kind.REBOOTED, ROUTER, "", "", T0, Optional.of(T0)));
        }
        assertEquals(AlertLog.KEEP, log.all().size(), "the oldest go");
        assertEquals("n" + (AlertLog.KEEP + 9), log.all().getLast().id());
    }

    @Test
    void alertSettingsAreSavedAndReadBack() throws Exception {
        SettingsRepository repository = new SettingsRepository(dir.resolve("settings.properties"));
        AlertSettings own = new AlertSettings(EnumSet.of(Kind.UNREACHABLE, Kind.INTERFACE_DOWN), 90, 5, 15, false, 10,
                Set.of("branch-a"));
        repository.save(repository.load().withAlerts(own));

        assertEquals(own, new SettingsRepository(dir.resolve("settings.properties")).load().alerts());
        assertEquals(AlertSettings.defaults(), new SettingsRepository(dir.resolve("none.properties")).load().alerts(),
                "no settings yet: every alert on");
        assertEquals(Set.of(), own.watching("branch-a", false).watched());
        assertEquals(100, new AlertSettings(Set.of(), 500, 0, 0, true, 0, null).cpuPercent(), "kept in range");
    }
}
