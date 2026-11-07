package org.netpilot.service.router;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A minimal stand-in for a RouterOS REST API, served by the JDK's built-in HTTP(S) server.
 * Answers 401 unless the expected Basic credentials are sent, like a real router, and keeps
 * hotspot users ({@code /rest/ip/hotspot/user}) and active sessions
 * ({@code /rest/ip/hotspot/active}) in memory.
 */
final class FakeRouter implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
    }

    /** A hotspot user as stored by the fake router. Durations use RouterOS text like {@code 1h20m}. */
    record StoredUser(String id, String name, String password, String profile, String server, String limitUptime,
            String comment, String uptime, long bytesIn, long bytesOut, boolean disabled) {

        StoredUser withDisabled(boolean value) {
            return new StoredUser(id, name, password, profile, server, limitUptime, comment, uptime, bytesIn, bytesOut,
                    value);
        }
    }

    record StoredSession(String id, String user, String address, String mac, String uptime) {
    }

    private static final String USERS = "/rest/ip/hotspot/user";
    private static final String ACTIVE = "/rest/ip/hotspot/active";
    private static final String PROFILES = "/rest/ip/hotspot/user/profile";
    private static final JsonMapper JSON = JsonMapper.shared();

    private final HttpServer server;
    private final String expectedAuthorization;
    private final Map<String, Reply> routes = new ConcurrentHashMap<>();
    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();
    /** Last body sent with each method to a profile path, e.g. {@code "PATCH /rest/.../*1"}. */
    private final Map<String, String> lastProfileWrite = new ConcurrentHashMap<>();
    private final Map<String, StoredUser> users = new LinkedHashMap<>();
    private final Map<String, StoredSession> sessions = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(0x10);
    private volatile Duration delay = Duration.ZERO;
    private volatile Reply createFailure;
    private volatile int createFailureAfter = Integer.MAX_VALUE;
    private volatile int creates;

    private FakeRouter(HttpServer server, String username, String password) {
        this.server = server;
        this.expectedAuthorization = RouterConnector.basicAuthorization(username, password.toCharArray());
        server.createContext("/", this::handle);
        server.start();
        routeDefaults();
    }

    static FakeRouter http(String username, String password) throws IOException {
        return new FakeRouter(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0),
                username, password);
    }

    static FakeRouter https(SSLContext tls, String username, String password) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        return new FakeRouter(server, username, password);
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Overrides the reply for a REST path such as {@code /rest/ip/hotspot}. */
    void route(String path, int status, String body) {
        routes.put(path, new Reply(status, body, Map.of()));
    }

    void redirect(String path, String location) {
        routes.put(path, new Reply(302, "", Map.of("Location", location)));
    }

    void delayReplies(Duration value) {
        this.delay = value;
    }

    String lastQuery(String path) {
        return lastQuery.get(path);
    }

    /** @param request method and path, e.g. {@code PUT /rest/ip/hotspot/user/profile} */
    String lastProfileWrite(String request) {
        return lastProfileWrite.get(request);
    }

    /** A user that exists on the "router" before the test starts. */
    void addExistingUser(String name) {
        addUser(name, "x", "default", "", "", "0s", 0, 0, false);
    }

    /** @return the router id of the new user */
    synchronized String addUser(String name, String password, String profile, String limitUptime, String comment,
            String uptime, long bytesIn, long bytesOut, boolean disabled) {
        String id = nextId();
        users.put(id, new StoredUser(id, name, password, profile, "all", limitUptime, comment, uptime, bytesIn,
                bytesOut, disabled));
        return id;
    }

    /** @return the router id of the session */
    synchronized String addSession(String user, String address) {
        String id = nextId();
        sessions.put(id, new StoredSession(id, user, address, "AA:BB:CC:DD:EE:FF", "5m"));
        return id;
    }

    /** From the {@code n+1}-th create on, answer every create with this reply. */
    void failCreatesAfter(int n, int status, String body) {
        createFailureAfter = n;
        createFailure = new Reply(status, body, Map.of());
    }

    synchronized Map<String, StoredUser> users() {
        return Map.copyOf(users);
    }

    synchronized Map<String, StoredSession> sessions() {
        return Map.copyOf(sessions);
    }

    private String nextId() {
        return "*" + Integer.toHexString(nextId.getAndIncrement()).toUpperCase();
    }

    private void routeDefaults() {
        route("/rest/system/identity", 200, "{\"name\":\"hEX\"}");
        route("/rest/system/resource", 200,
                "{\"board-name\":\"hEX\",\"version\":\"7.25.1 (stable)\",\"uptime\":\"14h10m53s\","
                        + "\"cpu-load\":\"7\",\"free-memory\":\"55050240\",\"total-memory\":\"134217728\"}");
        route("/rest/ip/hotspot/user/profile", 200,
                "[{\".id\":\"*0\",\"name\":\"default\"},{\".id\":\"*1\",\"name\":\"2M\",\"rate-limit\":\"2M/2M\"}]");
        route("/rest/ip/hotspot", 200,
                "[{\"name\":\"hotspot1\",\"interface\":\"bridge_hotspot\",\"disabled\":\"false\"},"
                        + "{\"name\":\"old\",\"interface\":\"ether5\",\"disabled\":\"true\"}]");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!delay.isZero()) {
                Thread.sleep(delay);
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            lastQuery.put(path, String.valueOf(exchange.getRequestURI().getRawQuery()));

            Reply reply;
            if (!expectedAuthorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply = new Reply(401, "{\"error\":401,\"message\":\"Unauthorized\"}", Map.of());
            } else if (routes.containsKey(path) && !isCreate(method, path)) {
                // route() can override listing, changing and deleting; creating always uses the store
                reply = routes.get(path);
            } else if (path.startsWith(USERS) && !path.startsWith(USERS + "/profile")) {
                reply = handleUsers(method, path, exchange.getRequestBody().readAllBytes());
            } else if (path.startsWith(ACTIVE)) {
                reply = handleSessions(method, path);
            } else if (path.startsWith(PROFILES)) {
                lastProfileWrite.put(method + " " + path,
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                reply = switch (method) {
                    case "PUT" -> ok("{\".id\":\"*9\",\"name\":\"new\"}");
                    case "PATCH" -> ok("{}");
                    case "DELETE" -> new Reply(204, "", Map.of());
                    default -> notFound();
                };
            } else {
                reply = notFound();
            }

            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            reply.headers().forEach(exchange.getResponseHeaders()::add);
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized Reply handleUsers(String method, String path, byte[] requestBody) {
        if (method.equals("GET") && path.equals(USERS)) {
            ArrayNode list = JSON.createArrayNode();
            for (StoredUser u : users.values()) {
                list.addObject()
                        .put(".id", u.id())
                        .put("name", u.name())
                        .put("password", u.password())
                        .put("profile", u.profile())
                        .put("server", u.server())
                        .put("limit-uptime", u.limitUptime())
                        .put("comment", u.comment())
                        .put("uptime", u.uptime())
                        .put("bytes-in", String.valueOf(u.bytesIn()))
                        .put("bytes-out", String.valueOf(u.bytesOut()))
                        .put("disabled", String.valueOf(u.disabled()));
            }
            return ok(list.toString());
        }
        if (isCreate(method, path)) {
            if (creates++ >= createFailureAfter) {
                return createFailure;
            }
            JsonNode in = JSON.readTree(requestBody);
            String name = in.path("name").asString("");
            if (users.values().stream().anyMatch(u -> u.name().equals(name))) {
                return new Reply(400, "{\"detail\":\"failure: already have user with this name\",\"error\":400,"
                        + "\"message\":\"Bad Request\"}", Map.of());
            }
            String id = nextId();
            users.put(id, new StoredUser(id, name, in.path("password").asString(""), in.path("profile").asString(""),
                    in.path("server").asString(""), in.path("limit-uptime").asString(""),
                    in.path("comment").asString(""), "0s", 0, 0, false));
            ObjectNode out = JSON.createObjectNode().put(".id", id).put("name", name);
            return ok(out.toString());
        }
        String id = path.substring(USERS.length() + 1);
        StoredUser user = users.get(id);
        if (user == null) {
            return notFound();
        }
        if (method.equals("PATCH")) {
            boolean disabled = "true".equals(JSON.readTree(requestBody).path("disabled").asString(""));
            users.put(id, user.withDisabled(disabled));
            return ok("{\".id\":\"" + id + "\"}");
        }
        if (method.equals("DELETE")) {
            users.remove(id);
            return new Reply(204, "", Map.of());
        }
        return notFound();
    }

    private synchronized Reply handleSessions(String method, String path) {
        if (method.equals("GET") && path.equals(ACTIVE)) {
            ArrayNode list = JSON.createArrayNode();
            sessions.values().forEach(s -> list.addObject().put(".id", s.id()).put("user", s.user())
                    .put("address", s.address()).put("mac-address", s.mac()).put("uptime", s.uptime()));
            return ok(list.toString());
        }
        if (method.equals("DELETE")) {
            return sessions.remove(path.substring(ACTIVE.length() + 1)) == null
                    ? notFound()
                    : new Reply(204, "", Map.of());
        }
        return notFound();
    }

    private static boolean isCreate(String method, String path) {
        return method.equals("PUT") && (path.equals(USERS) || path.equals(PROFILES));
    }

    private static Reply ok(String body) {
        return new Reply(200, body, Map.of());
    }

    private static Reply notFound() {
        return new Reply(404, "{\"error\":404,\"message\":\"Not Found\"}", Map.of());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
