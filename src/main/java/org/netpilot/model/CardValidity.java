package org.netpilot.model;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Card validity counted from the first login (e.g. 7 days), kept in a profile's RouterOS
 * {@code on-login} script so the router enforces it even while NetPilot is closed.
 *
 * <p>On a card's first login the script adds a scheduler task named {@code np-exp-<card>}. When it
 * runs, the router ends the card's session and closes the card: its time limit becomes the time
 * it used, so it shows as expired, and it is disabled. The task then removes itself.
 *
 * <p>NetPilot owns only the lines between its markers; any script of the user's own in the same
 * profile is kept as it is.
 */
public final class CardValidity {

    /** Scheduler tasks NetPilot adds start with this, followed by the card name. */
    public static final String TASK_PREFIX = "np-exp-";

    static final String BEGIN = "# NetPilot validity begin";
    static final String END = "# NetPilot validity end";

    private static final Pattern BLOCK = Pattern.compile("\\r?\\n?" + Pattern.quote(BEGIN) + ".*?"
            + Pattern.quote(END) + "\\r?\\n?", Pattern.DOTALL);
    private static final Pattern AMOUNT = Pattern.compile(":local npValidity (\\S+)");

    private CardValidity() {
    }

    /** The validity set in this {@code on-login} script, empty when it has none. */
    public static Optional<TimeLimit> read(String onLogin) {
        if (onLogin == null) {
            return Optional.empty();
        }
        Matcher block = BLOCK.matcher(onLogin);
        if (!block.find()) {
            return Optional.empty();
        }
        Matcher amount = AMOUNT.matcher(block.group());
        return amount.find() ? TimeLimit.parse(amount.group(1)) : Optional.empty();
    }

    /**
     * @return {@code onLogin} with NetPilot's part replaced by {@code validity}, or removed when
     *         it is empty; the user's own lines stay as they were
     */
    public static String apply(String onLogin, Optional<TimeLimit> validity) {
        String own = onLogin == null ? "" : BLOCK.matcher(onLogin).replaceAll("\n").strip();
        if (validity.isEmpty()) {
            return own;
        }
        return own.isEmpty() ? script(validity.get()) : own + "\n" + script(validity.get());
    }

    /**
     * The RouterOS script. {@code $user} is the card that just logged in. The task starts one
     * minute in the past so it can never fire at once; the card therefore ends at most a minute
     * early.
     */
    static String script(TimeLimit validity) {
        String q = "\\\""; // a quote inside the task text
        return String.join("\n",
                BEGIN,
                ":local npValidity " + validity.routerText(),
                ":local npTask (\"" + TASK_PREFIX + "\" . $user)",
                ":if ([:len [/system scheduler find name=$npTask]] = 0) do={",
                ":local npStart [/system clock get time]",
                ":if ($npStart > 1m) do={:set npStart ($npStart - 1m)}",
                ":local npEnd (\"/ip hotspot active remove [find user=" + q + "\" . $user . \"" + q + "];"
                        + " :delay 2s; :local u [/ip hotspot user find name=" + q + "\" . $user . \"" + q + "];"
                        + " :if ([:len \\$u] > 0) do={:local t [/ip hotspot user get \\$u uptime];"
                        + " :if (\\$t < 1s) do={:set t 1s}; /ip hotspot user set \\$u limit-uptime=\\$t disabled=yes};"
                        + " /system scheduler remove [find name=" + q + "\" . $npTask . \"" + q + "]\")",
                "/system scheduler add name=$npTask start-date=[/system clock get date] start-time=$npStart"
                        + " interval=$npValidity on-event=$npEnd comment=\"NetPilot card validity\"",
                "}",
                END);
    }
}
