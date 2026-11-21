package org.netpilot.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.netpilot.i18n.Messages;

/**
 * Everything about one card (hotspot user) as WinBox shows it: the settings that can be changed,
 * and its counters, which are read only.
 *
 * @param address    a fixed IP address for the card; empty for none
 * @param macAddress the only device the card works on; empty for any
 * @param limitUptime RouterOS duration such as {@code 8h}; empty for no limit
 * @param limitBytes data limit in bytes (download and upload together); 0 for none
 */
public record CardDetails(String routerId, String name, String password, String server, String profile,
        String comment, boolean disabled, String address, String macAddress, String limitUptime, long limitBytes,
        Duration uptime, long bytesIn, long bytesOut) {

    private static final Pattern DURATION = Pattern.compile("^$|^(\\d+[wdhms])+$|^\\d{1,2}:\\d{2}:\\d{2}$");
    private static final Pattern MAC = Pattern.compile("^$|^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$");
    private static final Pattern IPV4 = Pattern.compile(
            "^$|^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern SAFE = Pattern.compile("^[^\"\\\\\\r\\n]*$");

    public CardDetails {
        Objects.requireNonNull(routerId);
        name = strip(name);
        password = password == null ? "" : password;
        server = strip(server).isEmpty() ? "all" : strip(server);
        profile = strip(profile);
        comment = comment == null ? "" : comment.strip();
        address = strip(address);
        macAddress = strip(macAddress).toUpperCase(java.util.Locale.ROOT);
        limitUptime = strip(limitUptime);
        limitBytes = Math.max(0, limitBytes);
        uptime = uptime == null ? Duration.ZERO : uptime;
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** What is wrong with these settings, in the user's words; empty when they can be saved. */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (name.isEmpty() || name.length() > 64 || !SAFE.matcher(name).matches()) {
            out.add(Messages.get("card.edit.problem.name"));
        }
        if (password.length() > 64 || !SAFE.matcher(password).matches()) {
            out.add(Messages.get("card.edit.problem.password"));
        }
        if (!SAFE.matcher(comment).matches() || comment.length() > 200) {
            out.add(Messages.get("card.edit.problem.comment"));
        }
        if (!DURATION.matcher(limitUptime).matches()) {
            out.add(Messages.get("card.edit.problem.uptime"));
        }
        if (!MAC.matcher(macAddress).matches()) {
            out.add(Messages.get("card.edit.problem.mac"));
        }
        if (!IPV4.matcher(address).matches()) {
            out.add(Messages.get("card.edit.problem.address"));
        }
        return out;
    }

    /**
     * The RouterOS properties to send so the router holds {@code edited}: only those that changed,
     * like WinBox's Apply.
     */
    public Map<String, String> changesTo(CardDetails edited) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "name", name, edited.name());
        put(out, "password", password, edited.password());
        put(out, "server", server, edited.server());
        put(out, "profile", profile, edited.profile());
        put(out, "comment", comment, edited.comment());
        put(out, "disabled", String.valueOf(disabled), String.valueOf(edited.disabled()));
        put(out, "address", address, edited.address());
        put(out, "mac-address", macAddress, edited.macAddress());
        put(out, "limit-uptime", limitUptime.isEmpty() ? "0s" : limitUptime,
                edited.limitUptime().isEmpty() ? "0s" : edited.limitUptime());
        put(out, "limit-bytes-total", String.valueOf(limitBytes), String.valueOf(edited.limitBytes()));
        return out;
    }

    private static void put(Map<String, String> out, String key, String before, String after) {
        if (!before.equals(after)) {
            out.put(key, after);
        }
    }

    /** Never used yet: no time on it and no data through it. */
    public boolean neverUsed() {
        return uptime.isZero() && bytesIn == 0 && bytesOut == 0;
    }
}
