package org.netpilot.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Where and as whom to reach a router's REST API. Holds no password on purpose: passwords are
 * never stored, only passed to {@code RouterConnector.connect} for the current session.
 *
 * @param host     IP address or host name, e.g. {@code 10.0.0.1}
 * @param port     TCP port of the router's {@code www-ssl} (HTTPS) or {@code www} (HTTP) service
 * @param https    whether to use HTTPS (strongly recommended)
 * @param username RouterOS user name
 */
public record RouterEndpoint(String host, int port, boolean https, String username) {

    public static final int DEFAULT_HTTPS_PORT = 443;
    public static final int DEFAULT_HTTP_PORT = 80;

    /** Host names, IPv4 addresses, and IPv6 addresses (optionally in brackets). */
    private static final Pattern HOST = Pattern
            .compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$|^\\[?[0-9A-Fa-f:.]{2,45}]?$");

    /** HTTP Basic authentication cannot carry a colon in the user name. */
    private static final Pattern USERNAME = Pattern.compile("^[^:\\s]{1,64}$");

    public RouterEndpoint {
        host = host == null ? "" : host.strip();
        username = username == null ? "" : username.strip();
    }

    /** MikroTik's factory address with HTTPS. */
    public static RouterEndpoint defaults() {
        return new RouterEndpoint("192.168.88.1", DEFAULT_HTTPS_PORT, true, "netpilot");
    }

    /**
     * @return human-readable problems, empty when the endpoint is usable
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (!HOST.matcher(host).matches()) {
            errors.add("Enter the router address, e.g. 10.0.0.1.");
        }
        if (port < 1 || port > 65_535) {
            errors.add("Port must be between 1 and 65535.");
        }
        if (!USERNAME.matcher(username).matches()) {
            errors.add("Enter a user name without spaces or ':' (max 64 characters).");
        }
        return errors;
    }

    /** Base URI of the REST API, e.g. {@code https://10.0.0.1:443/rest}. */
    public URI restBaseUri() {
        return URI.create((https ? "https" : "http") + "://" + uriHost() + ":" + port + "/rest");
    }

    /** Key used to remember this router's trusted certificate. */
    public String address() {
        return uriHost() + ":" + port;
    }

    private String uriHost() {
        boolean ipv6 = host.indexOf(':') >= 0 && !host.startsWith("[");
        return ipv6 ? "[" + host + "]" : host;
    }
}
