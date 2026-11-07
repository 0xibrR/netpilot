package org.netpilot.model;

/**
 * Live load figures of a router ({@code /system/resource}), shown in the status bar.
 *
 * @param cpuLoad     CPU load in percent
 * @param freeMemory  free RAM in bytes
 * @param totalMemory total RAM in bytes
 * @param uptime      RouterOS duration since boot, e.g. {@code 2d14h10m}
 */
public record RouterResource(int cpuLoad, long freeMemory, long totalMemory, String uptime) {

    public RouterResource {
        cpuLoad = Math.clamp(cpuLoad, 0, 100);
        freeMemory = Math.max(0, freeMemory);
        totalMemory = Math.max(0, totalMemory);
        uptime = uptime == null ? "" : uptime.strip();
    }

    public long usedMemory() {
        return Math.max(0, totalMemory - freeMemory);
    }
}
