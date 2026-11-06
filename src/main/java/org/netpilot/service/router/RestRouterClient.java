package org.netpilot.service.router;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterInfo;
import org.netpilot.service.router.RouterException.Kind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link RouterClient} over the RouterOS 7 REST API ({@code /rest/...}).
 *
 * <p>Requests ask only for the fields we use ({@code .proplist}) to keep responses small.
 * RouterOS returns every value as a string, including numbers and booleans.
 */
final class RestRouterClient implements RouterClient {

    private static final String USERS = "/ip/hotspot/user";
    private static final Pattern ROUTER_ID = Pattern.compile("^\\*[0-9A-Fa-f]{1,16}$");
    private static final JsonMapper JSON = JsonMapper.shared();

    private final RouterEndpoint endpoint;
    private final HttpClient http;
    private final String authorizationHeader;
    private final Duration requestTimeout;
    private final URI baseUri;

    RestRouterClient(RouterEndpoint endpoint, HttpClient http, String authorizationHeader, Duration requestTimeout) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.http = Objects.requireNonNull(http);
        this.authorizationHeader = Objects.requireNonNull(authorizationHeader);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        this.baseUri = endpoint.restBaseUri();
    }

    @Override
    public RouterEndpoint endpoint() {
        return endpoint;
    }

    @Override
    public RouterInfo info() throws RouterException {
        JsonNode identity = get("/system/identity", "name");
        JsonNode resource = get("/system/resource", "board-name,version,uptime");
        return new RouterInfo(
                text(identity, "name"),
                text(resource, "board-name"),
                text(resource, "version"),
                text(resource, "uptime"));
    }

    @Override
    public List<HotspotProfile> hotspotProfiles() throws RouterException {
        List<HotspotProfile> profiles = new ArrayList<>();
        for (JsonNode item : array(get("/ip/hotspot/user/profile", "name,rate-limit"), "hotspot profiles")) {
            String name = text(item, "name");
            if (!name.isEmpty()) {
                profiles.add(new HotspotProfile(name, text(item, "rate-limit")));
            }
        }
        return List.copyOf(profiles);
    }

    @Override
    public List<HotspotServer> hotspotServers() throws RouterException {
        List<HotspotServer> servers = new ArrayList<>();
        for (JsonNode item : array(get("/ip/hotspot", "name,interface,disabled"), "hotspot servers")) {
            String name = text(item, "name");
            if (!name.isEmpty()) {
                servers.add(new HotspotServer(name, text(item, "interface"), "true".equals(text(item, "disabled"))));
            }
        }
        return List.copyOf(servers);
    }

    @Override
    public Set<String> hotspotUsernames() throws RouterException {
        Set<String> names = new HashSet<>();
        for (JsonNode item : array(get(USERS, "name"), "hotspot users")) {
            String name = text(item, "name");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return Set.copyOf(names);
    }

    @Override
    public String createHotspotUser(HotspotUser user, String comment) throws RouterException {
        ObjectNode body = JSON.createObjectNode()
                .put("name", user.username())
                .put("password", user.password())
                .put("profile", user.profile())
                .put("server", user.server())
                .put("comment", comment);
        if (!user.limitUptime().isBlank()) {
            body.put("limit-uptime", user.limitUptime());
        }
        // RouterOS REST: PUT on a menu adds a new item and returns it, including its ".id"
        JsonNode created = send(USERS,
                HttpRequest.newBuilder(URI.create(baseUri + USERS))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))));
        String id = text(created, ".id");
        if (!ROUTER_ID.matcher(id).matches()) {
            throw new RouterException(Kind.UNEXPECTED_RESPONSE,
                    "The router created user " + user.username() + " but did not return its id.");
        }
        return id;
    }

    @Override
    public void deleteHotspotUser(String routerId) throws RouterException {
        // the id goes into the URL path, so accept only RouterOS ids like "*1A"
        if (!ROUTER_ID.matcher(routerId).matches()) {
            throw new IllegalArgumentException("Not a RouterOS id: " + routerId);
        }
        try {
            send(USERS + "/" + routerId, HttpRequest.newBuilder(URI.create(baseUri + USERS + "/" + routerId)).DELETE());
        } catch (RouterException e) {
            // 404: the user is already gone (e.g. removed in WinBox), which is what the caller wanted
            if (e.kind() != Kind.NOT_SUPPORTED) {
                throw e;
            }
        }
    }

    @Override
    public void close() {
        http.close();
    }

    private JsonNode get(String path, String proplist) throws RouterException {
        return send(path, HttpRequest.newBuilder(URI.create(baseUri + path + "?.proplist=" + proplist)).GET());
    }

    private JsonNode send(String path, HttpRequest.Builder builder) throws RouterException {
        HttpRequest request = builder
                .timeout(requestTimeout)
                .header("Authorization", authorizationHeader)
                .header("Accept", "application/json")
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw RouterErrors.fromIo(e, endpoint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RouterException(Kind.UNREACHABLE, "The connection was cancelled.", e);
        }
        return RouterErrors.fromResponse(response.statusCode(), response.body(), path, endpoint);
    }

    private static Iterable<JsonNode> array(JsonNode node, String what) throws RouterException {
        if (!node.isArray()) {
            throw new RouterException(Kind.UNEXPECTED_RESPONSE, "The router sent an unexpected list of " + what + ".");
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("").strip();
    }
}
