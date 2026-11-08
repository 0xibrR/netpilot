package org.netpilot.service.router;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.netpilot.i18n.Messages;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.CardValidity;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterDuration;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterInfo;
import org.netpilot.model.RouterResource;
import org.netpilot.model.RouterUser;
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
    private static final String ACTIVE = "/ip/hotspot/active";
    private static final String PROFILES = "/ip/hotspot/user/profile";
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
    public RouterResource resource() throws RouterException {
        JsonNode resource = get("/system/resource", "cpu-load,free-memory,total-memory,uptime");
        return new RouterResource((int) number(resource, "cpu-load"), number(resource, "free-memory"),
                number(resource, "total-memory"), text(resource, "uptime"));
    }

    @Override
    public List<HotspotProfile> hotspotProfiles() throws RouterException {
        List<HotspotProfile> profiles = new ArrayList<>();
        for (JsonNode item : array(get(PROFILES, ".id,name,rate-limit,shared-users,on-login"), "hotspot profiles")) {
            String name = text(item, "name");
            if (!name.isEmpty()) {
                profiles.add(new HotspotProfile(text(item, ".id"), name, text(item, "rate-limit"),
                        (int) Math.max(1, number(item, "shared-users")), text(item, "on-login")));
            }
        }
        return List.copyOf(profiles);
    }

    @Override
    public String createHotspotProfile(HotspotProfile profile) throws RouterException {
        JsonNode created = send(PROFILES, HttpRequest.newBuilder(URI.create(baseUri + PROFILES))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(profileBody(profile)))));
        String id = text(created, ".id");
        if (!ROUTER_ID.matcher(id).matches()) {
            throw new RouterException(Kind.UNEXPECTED_RESPONSE, Messages.get("router.error.noId", profile.name()));
        }
        return id;
    }

    @Override
    public void updateHotspotProfile(HotspotProfile profile) throws RouterException {
        requireRouterId(profile.routerId());
        String path = PROFILES + "/" + profile.routerId();
        send(path, HttpRequest.newBuilder(URI.create(baseUri + path))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(profileBody(profile)))));
    }

    @Override
    public void deleteHotspotProfile(String routerId) throws RouterException {
        deleteIgnoringMissing(PROFILES, routerId);
    }

    /** An empty rate limit removes the limit. */
    private static ObjectNode profileBody(HotspotProfile profile) {
        return JSON.createObjectNode()
                .put("name", profile.name())
                .put("rate-limit", profile.speed().format())
                .put("shared-users", String.valueOf(profile.sharedUsers()))
                .put("on-login", profile.onLogin());
    }

    @Override
    public Map<String, String> cardExpiries() throws RouterException {
        Map<String, String> ends = new java.util.TreeMap<>();
        for (JsonNode item : array(get("/system/scheduler", "name,next-run"), "scheduler")) {
            String name = text(item, "name");
            if (name.startsWith(CardValidity.TASK_PREFIX) && name.length() > CardValidity.TASK_PREFIX.length()) {
                ends.put(name.substring(CardValidity.TASK_PREFIX.length()), text(item, "next-run"));
            }
        }
        return ends;
    }

    @Override
    public boolean clockSynced() throws RouterException {
        return "true".equals(text(get("/system/ntp/client", "enabled"), "enabled"));
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
                    Messages.get("router.error.noId", user.username()));
        }
        return id;
    }

    @Override
    public void deleteHotspotUser(String routerId) throws RouterException {
        deleteIgnoringMissing(USERS, routerId);
    }

    @Override
    public List<RouterUser> hotspotUsers() throws RouterException {
        List<RouterUser> users = new ArrayList<>();
        JsonNode list = get(USERS, ".id,name,profile,server,uptime,limit-uptime,bytes-in,bytes-out,disabled,comment");
        for (JsonNode item : array(list, "hotspot users")) {
            String id = text(item, ".id");
            String name = text(item, "name");
            if (ROUTER_ID.matcher(id).matches() && !name.isEmpty()) {
                users.add(new RouterUser(id, name, text(item, "profile"), text(item, "server"),
                        RouterDuration.parse(text(item, "uptime")).orElse(Duration.ZERO),
                        text(item, "limit-uptime"), number(item, "bytes-in"), number(item, "bytes-out"),
                        "true".equals(text(item, "disabled")), text(item, "comment")));
            }
        }
        return List.copyOf(users);
    }

    @Override
    public List<ActiveSession> activeSessions() throws RouterException {
        List<ActiveSession> sessions = new ArrayList<>();
        for (JsonNode item : array(get(ACTIVE, ".id,user,address,mac-address,uptime"), "active sessions")) {
            String id = text(item, ".id");
            String user = text(item, "user");
            if (ROUTER_ID.matcher(id).matches() && !user.isEmpty()) {
                sessions.add(new ActiveSession(id, user, text(item, "address"), text(item, "mac-address"),
                        RouterDuration.parse(text(item, "uptime")).orElse(Duration.ZERO)));
            }
        }
        return List.copyOf(sessions);
    }

    @Override
    public void setHotspotUserDisabled(String routerId, boolean disabled) throws RouterException {
        requireRouterId(routerId);
        byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("disabled", String.valueOf(disabled)));
        // RouterOS REST: PATCH on an item changes only the given properties
        send(USERS + "/" + routerId, HttpRequest.newBuilder(URI.create(baseUri + USERS + "/" + routerId))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    @Override
    public void disconnect(String sessionId) throws RouterException {
        deleteIgnoringMissing(ACTIVE, sessionId);
    }

    @Override
    public Map<String, String> hotspotPasswords(Collection<String> routerIds) throws RouterException {
        Set<String> wanted = Set.copyOf(routerIds);
        Map<String, String> passwords = new HashMap<>();
        // one request for the whole list is much faster than one per user
        for (JsonNode item : array(get(USERS, ".id,password"), "hotspot users")) {
            String id = text(item, ".id");
            if (wanted.contains(id)) {
                passwords.put(id, item.path("password").asString(""));
            }
        }
        return passwords;
    }

    /** Deletes an item; succeeds if it is already gone (404), e.g. removed in WinBox meanwhile. */
    private void deleteIgnoringMissing(String menu, String routerId) throws RouterException {
        requireRouterId(routerId);
        try {
            send(menu + "/" + routerId, HttpRequest.newBuilder(URI.create(baseUri + menu + "/" + routerId)).DELETE());
        } catch (RouterException e) {
            if (e.kind() != Kind.NOT_SUPPORTED) {
                throw e;
            }
        }
    }

    /** Ids go into URL paths, so accept only RouterOS ids like "*1A". */
    private static void requireRouterId(String routerId) {
        if (routerId == null || !ROUTER_ID.matcher(routerId).matches()) {
            throw new IllegalArgumentException("Not a RouterOS id: " + routerId);
        }
    }

    private static long number(JsonNode node, String field) {
        try {
            return Long.parseLong(text(node, field));
        } catch (NumberFormatException e) {
            return 0;
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
            throw new RouterException(Kind.UNREACHABLE, Messages.get("router.error.cancelled"), e);
        }
        return RouterErrors.fromResponse(response.statusCode(), response.body(), path, endpoint);
    }

    private static Iterable<JsonNode> array(JsonNode node, String what) throws RouterException {
        if (!node.isArray()) {
            throw new RouterException(Kind.UNEXPECTED_RESPONSE, Messages.get("router.error.badList", what));
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("").strip();
    }
}
