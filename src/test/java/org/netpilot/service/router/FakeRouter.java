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
 * hotspot users in memory ({@code GET/PUT/DELETE /rest/ip/hotspot/user}).
 */
final class FakeRouter implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
    }

    /** A hotspot user as stored by the fake router. */
    record StoredUser(String id, String name, String password, String profile, String server, String limitUptime,
            String comment) {
    }

    private static final String USERS = "/rest/ip/hotspot/user";
    private static final JsonMapper JSON = JsonMapper.shared();

    private final HttpServer server;
    private final String expectedAuthorization;
    private final Map<String, Reply> routes = new ConcurrentHashMap<>();
    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();
    private final Map<String, StoredUser> users = new LinkedHashMap<>();
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

    /** Users that exist on the "router" before the test starts. */
    synchronized void addExistingUser(String name) {
        String id = "*" + Integer.toHexString(nextId.getAndIncrement()).toUpperCase();
        users.put(id, new StoredUser(id, name, "x", "default", "all", "", ""));
    }

    /** From the {@code n+1}-th create on, answer every create with this reply. */
    void failCreatesAfter(int n, int status, String body) {
        createFailureAfter = n;
        createFailure = new Reply(status, body, Map.of());
    }

    synchronized Map<String, StoredUser> users() {
        return Map.copyOf(users);
    }

    private void routeDefaults() {
        route("/rest/system/identity", 200, "{\"name\":\"hEX\"}");
        route("/rest/system/resource", 200,
                "{\"board-name\":\"hEX\",\"version\":\"7.25.1 (stable)\",\"uptime\":\"14h10m53s\"}");
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
            String path = exchange.getRequestURI().getPath();
            lastQuery.put(path, String.valueOf(exchange.getRequestURI().getRawQuery()));

            Reply reply;
            if (!expectedAuthorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply = new Reply(401, "{\"error\":401,\"message\":\"Unauthorized\"}", Map.of());
            } else if (path.startsWith(USERS) && !path.startsWith(USERS + "/profile")
                    && (!routes.containsKey(path) || isCreate(exchange.getRequestMethod(), path))) {
                // route() can override listing and deleting; creating always uses the in-memory store
                reply = handleUsers(exchange.getRequestMethod(), path, exchange.getRequestBody().readAllBytes());
            } else {
                reply = routes.getOrDefault(path, notFound());
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
            users.values().forEach(u -> list.addObject().put(".id", u.id()).put("name", u.name()));
            return new Reply(200, list.toString(), Map.of());
        }
        if (method.equals("PUT") && path.equals(USERS)) {
            if (creates++ >= createFailureAfter) {
                return createFailure;
            }
            JsonNode in = JSON.readTree(requestBody);
            String name = in.path("name").asString("");
            if (users.values().stream().anyMatch(u -> u.name().equals(name))) {
                return new Reply(400, "{\"detail\":\"failure: already have user with this name\",\"error\":400,"
                        + "\"message\":\"Bad Request\"}", Map.of());
            }
            String id = "*" + Integer.toHexString(nextId.getAndIncrement()).toUpperCase();
            users.put(id, new StoredUser(id, name, in.path("password").asString(""), in.path("profile").asString(""),
                    in.path("server").asString(""), in.path("limit-uptime").asString(""),
                    in.path("comment").asString("")));
            ObjectNode out = JSON.createObjectNode().put(".id", id).put("name", name);
            return new Reply(200, out.toString(), Map.of());
        }
        if (method.equals("DELETE")) {
            String id = path.substring(USERS.length() + 1);
            return users.remove(id) == null ? notFound() : new Reply(204, "", Map.of());
        }
        return notFound();
    }

    private static boolean isCreate(String method, String path) {
        return method.equals("PUT") && path.equals(USERS);
    }

    private static Reply notFound() {
        return new Reply(404, "{\"error\":404,\"message\":\"Not Found\"}", Map.of());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
