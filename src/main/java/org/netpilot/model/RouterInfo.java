package org.netpilot.model;

/**
 * What a connected router reports about itself.
 *
 * @param identity  router name ({@code /system identity})
 * @param boardName hardware model, e.g. {@code hEX}
 * @param version   RouterOS version, e.g. {@code 7.25.1 (stable)}
 * @param uptime    RouterOS duration since boot, e.g. {@code 2d14h10m}
 */
public record RouterInfo(String identity, String boardName, String version, String uptime) {

    public RouterInfo {
        identity = orEmpty(identity);
        boardName = orEmpty(boardName);
        version = orEmpty(version);
        uptime = orEmpty(uptime);
    }

    /** Short label for the status bar, e.g. {@code hEX · RouterOS 7.25.1 (stable)}. */
    public String summary() {
        String name = identity.isEmpty() ? boardName : identity;
        return name + " · RouterOS " + version;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.strip();
    }
}
