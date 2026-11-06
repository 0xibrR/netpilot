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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;

/**
 * A minimal stand-in for a RouterOS REST API, served by the JDK's built-in HTTP(S) server.
 * Answers 401 unless the expected Basic credentials are sent, like a real router.
 */
final class FakeRouter implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final String expectedAuthorization;
    private final Map<String, Reply> routes = new ConcurrentHashMap<>();
    private final Map<String, String> lastQuery = new ConcurrentHashMap<>();
    private volatile Duration delay = Duration.ZERO;

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
            } else {
                reply = routes.getOrDefault(path,
                        new Reply(404, "{\"error\":404,\"message\":\"Not Found\"}", Map.of()));
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

    @Override
    public void close() {
        server.stop(0);
    }
}
