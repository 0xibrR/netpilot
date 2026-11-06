package org.netpilot.model;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the user typed in the router address field. People often paste a browser URL such as
 * {@code http://10.0.0.1/} or {@code https://router.lan:8443/webfig}; this extracts the host and
 * any scheme or port it contains so the form can fill those fields in.
 *
 * @param host  the host part only, e.g. {@code 10.0.0.1}
 * @param https {@code true}/{@code false} if the text had an {@code https://}/{@code http://} prefix
 * @param port  the port if the text contained one
 */
public record AddressInput(String host, Optional<Boolean> https, OptionalInt port) {

    /** [scheme://] host-or-[ipv6] [:port] [/anything] */
    private static final Pattern URL_LIKE = Pattern.compile(
            "^(?:(https?)://)?(\\[[^\\]/]+]|[^/:\\s\\[\\]]+)(?::(\\d{1,5}))?(?:/.*)?$",
            Pattern.CASE_INSENSITIVE);

    public static AddressInput parse(String text) {
        String trimmed = text == null ? "" : text.strip();
        Matcher m = URL_LIKE.matcher(trimmed);
        if (!m.matches()) {
            // e.g. a bare IPv6 address like fe80::1; validation decides if it is usable
            return new AddressInput(trimmed, Optional.empty(), OptionalInt.empty());
        }
        Optional<Boolean> https = Optional.ofNullable(m.group(1))
                .map(scheme -> scheme.toLowerCase(Locale.ROOT).equals("https"));
        OptionalInt port = m.group(3) == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(m.group(3)));
        return new AddressInput(m.group(2), https, port);
    }

    /** Whether the text was more than a plain host (so the form should be updated). */
    public boolean hadExtras(String original) {
        return !host.equals(original == null ? "" : original.strip());
    }
}
