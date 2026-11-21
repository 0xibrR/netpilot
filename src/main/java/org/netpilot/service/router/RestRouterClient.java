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
import org.netpilot.model.DnsAnswer;
import org.netpilot.model.DnsSettings;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.LogEntry;
import org.netpilot.model.PingReply;
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

    private static final System.Logger LOG = System.getLogger(RestRouterClient.class.getName());

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
    private final ApiLogin apiLogin;

    RestRouterClient(RouterEndpoint endpoint, HttpClient http, String authorizationHeader, Duration requestTimeout) {
        this(endpoint, http, authorizationHeader, requestTimeout, null);
    }

    RestRouterClient(RouterEndpoint endpoint, HttpClient http, String authorizationHeader, Duration requestTimeout,
            ApiLogin apiLogin) {
        this.apiLogin = apiLogin;
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

    private static final String PAGE_PROFILES = "/ip/hotspot/profile";

    @Override
    public List<PageProfile> hotspotPageProfiles() throws RouterException {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (JsonNode server : array(get("/ip/hotspot", "profile"), "hotspot servers")) {
            used.add(text(server, "profile"));
        }
        List<PageProfile> profiles = new ArrayList<>();
        for (JsonNode item : array(get(PAGE_PROFILES, ".id,name,html-directory"), "hotspot server profiles")) {
            if (used.contains(text(item, "name"))) {
                profiles.add(new PageProfile(text(item, ".id"), text(item, "name"), text(item, "html-directory")));
            }
        }
        return List.copyOf(profiles);
    }

    @Override
    public void setPageFolder(String routerId, String folder) throws RouterException {
        requireRouterId(routerId);
        String path = PAGE_PROFILES + "/" + routerId;
        byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("html-directory", folder));
        send(path, HttpRequest.newBuilder(URI.create(baseUri + path)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    /** The file's RouterOS id and size, when it exists. */
    private java.util.Optional<JsonNode> file(String name) throws RouterException {
        String query = "/file?name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                + "&.proplist=.id,name,size,type";
        JsonNode found = send("/file", HttpRequest.newBuilder(URI.create(baseUri + query)).GET());
        for (JsonNode item : array(found, "files")) {
            if (name.equals(text(item, "name"))) {
                return java.util.Optional.of(item);
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public void writeTextFile(String name, String contents) throws RouterException {
        int slash = name.lastIndexOf('/');
        if (slash > 0 && file(name.substring(0, slash)).isEmpty()) {
            byte[] folder = JSON.writeValueAsBytes(JSON.createObjectNode().put("name", name.substring(0, slash))
                    .put("type", "directory"));
            send("/file", HttpRequest.newBuilder(URI.create(baseUri + "/file"))
                    .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofByteArray(folder)));
        }
        java.util.Optional<JsonNode> existing = file(name);
        if (existing.isPresent()) {
            String path = "/file/" + text(existing.get(), ".id");
            byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("contents", contents));
            send(path, HttpRequest.newBuilder(URI.create(baseUri + path)).header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body)));
            return;
        }
        byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("name", name).put("contents", contents));
        send("/file", HttpRequest.newBuilder(URI.create(baseUri + "/file")).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    @Override
    public boolean hasFlashStorage() throws RouterException {
        return file("flash").map(f -> "disk".equals(text(f, "type"))).orElse(false);
    }

    @Override
    public long fileSize(String name) throws RouterException {
        return file(name).map(f -> number(f, "size")).orElse(-1L);
    }

    @Override
    public void fetchFile(String url, String name) throws RouterException {
        byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("url", url).put("dst-path", name)
                .put("mode", "http").put("check-certificate", "no"));
        send("/tool/fetch", HttpRequest.newBuilder(URI.create(baseUri + "/tool/fetch"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    @Override
    public java.util.OptionalInt apiServicePort(boolean tls) throws RouterException {
        String wanted = tls ? "api-ssl" : "api";
        for (JsonNode item : array(get("/ip/service", "name,port,disabled,dynamic"), "services")) {
            if (wanted.equals(text(item, "name")) && !"true".equals(text(item, "dynamic"))) {
                if ("true".equals(text(item, "disabled"))) {
                    return java.util.OptionalInt.empty();
                }
                try {
                    return java.util.OptionalInt.of(Integer.parseInt(text(item, "port")));
                } catch (NumberFormatException e) {
                    return java.util.OptionalInt.empty();
                }
            }
        }
        return java.util.OptionalInt.empty();
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
        if (user.limitBytes() > 0) {
            body.put("limit-bytes-total", String.valueOf(user.limitBytes()));
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
        JsonNode list = get(USERS,
                ".id,name,profile,server,uptime,limit-uptime,bytes-in,bytes-out,disabled,comment,limit-bytes-total");
        for (JsonNode item : array(list, "hotspot users")) {
            String id = text(item, ".id");
            String name = text(item, "name");
            if (ROUTER_ID.matcher(id).matches() && !name.isEmpty()) {
                users.add(new RouterUser(id, name, text(item, "profile"), text(item, "server"),
                        RouterDuration.parse(text(item, "uptime")).orElse(Duration.ZERO),
                        text(item, "limit-uptime"), number(item, "bytes-in"), number(item, "bytes-out"),
                        "true".equals(text(item, "disabled")), text(item, "comment"),
                        number(item, "limit-bytes-total")));
            }
        }
        return List.copyOf(users);
    }

    @Override
    public List<ActiveSession> activeSessions() throws RouterException {
        List<ActiveSession> sessions = new ArrayList<>();
        JsonNode list = get(ACTIVE, ".id,user,address,mac-address,uptime,bytes-in,bytes-out,session-time-left");
        for (JsonNode item : array(list, "active sessions")) {
            String id = text(item, ".id");
            String user = text(item, "user");
            if (ROUTER_ID.matcher(id).matches() && !user.isEmpty()) {
                sessions.add(new ActiveSession(id, user, text(item, "address"), text(item, "mac-address"),
                        RouterDuration.parse(text(item, "uptime")).orElse(Duration.ZERO),
                        number(item, "bytes-in"), number(item, "bytes-out"),
                        RouterDuration.parse(text(item, "session-time-left"))));
            }
        }
        return List.copyOf(sessions);
    }

    @Override
    public org.netpilot.model.CardDetails cardDetails(String routerId) throws RouterException {
        requireRouterId(routerId);
        String path = USERS + "/" + routerId;
        JsonNode u = send(path, HttpRequest.newBuilder(URI.create(baseUri + path + "?.proplist=.id,name,password,"
                + "server,profile,comment,disabled,address,mac-address,limit-uptime,limit-bytes-total,uptime,"
                + "bytes-in,bytes-out")).GET());
        if (u.isArray()) {
            u = u.isEmpty() ? JSON.createObjectNode() : u.get(0);
        }
        String limit = text(u, "limit-uptime");
        return new org.netpilot.model.CardDetails(routerId, text(u, "name"), text(u, "password"), text(u, "server"),
                text(u, "profile"), text(u, "comment"), "true".equals(text(u, "disabled")), text(u, "address"),
                text(u, "mac-address"), "0s".equals(limit) ? "" : limit, number(u, "limit-bytes-total"),
                RouterDuration.parse(text(u, "uptime")).orElse(java.time.Duration.ZERO), number(u, "bytes-in"),
                number(u, "bytes-out"));
    }

    @Override
    public void updateCard(String routerId, Map<String, String> changes) throws RouterException {
        requireRouterId(routerId);
        if (changes.isEmpty()) {
            return;
        }
        ObjectNode body = JSON.createObjectNode();
        changes.forEach(body::put);
        String path = USERS + "/" + routerId;
        send(path, HttpRequest.newBuilder(URI.create(baseUri + path)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))));
    }

    @Override
    public void resetCardCounters(String routerId) throws RouterException {
        requireRouterId(routerId);
        byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put(".id", routerId));
        send(USERS + "/reset-counters", HttpRequest.newBuilder(URI.create(baseUri + USERS + "/reset-counters"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)));
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

    /**
     * One probe through the router's own ping ({@code POST /rest/ping}, {@code count=1}), so the
     * test shows what the router sees, not this computer. The address is checked before it is sent.
     */
    @Override
    public PingReply ping(String address, int seq) throws RouterException {
        return pingOnce(address, seq, 0);
    }

    /**
     * A ping with a short TTL: the router that drops it on the way answers "TTL exceeded", which
     * tells who that router is. Sending TTL 1, 2, 3 … walks the path one router at a time.
     */
    @Override
    public PingReply probe(String address, int ttl) throws RouterException {
        if (ttl < 1 || ttl > 255) {
            throw new IllegalArgumentException("TTL must be 1 to 255: " + ttl);
        }
        return pingOnce(address, ttl - 1, ttl);
    }

    /** @param ttl 0 for the router's default */
    private PingReply pingOnce(String address, int seq, int ttl) throws RouterException {
        String target = PingReply.target(address);
        ObjectNode body = JSON.createObjectNode().put("address", target).put("count", "1");
        if (ttl > 0) {
            body.put("ttl", String.valueOf(ttl));
        }
        JsonNode result = send("/ping", HttpRequest.newBuilder(URI.create(baseUri + "/ping"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))));
        JsonNode line = result.isArray() && !result.isEmpty() ? result.get(result.size() - 1) : result;
        boolean received = number(line, "received") > 0;
        String status = text(line, "status");
        boolean ttlExceeded = new PingReply(0, "", java.util.Optional.empty(), 0, 0, status).ttlExceeded();
        java.util.Optional<java.time.Duration> time = received || ttlExceeded
                ? PingReply.parseTime(text(line, "time"))
                : java.util.Optional.empty();
        if (ttlExceeded && time.isEmpty()) {
            time = java.util.Optional.of(java.time.Duration.ZERO); // answered, time not given
        }
        if (!received && status.isEmpty()) {
            status = "timeout";
        }
        String host = text(line, "host");
        return new PingReply(seq, host.isEmpty() ? target : host, time, (int) number(line, "ttl"),
                (int) number(line, "size"), received && !ttlExceeded ? "" : status);
    }

    @Override
    public DnsSettings dnsSettings() throws RouterException {
        JsonNode dns = get("/ip/dns", "servers,dynamic-servers,allow-remote-requests");
        JsonNode item = dns.isArray() && !dns.isEmpty() ? dns.get(0) : dns;
        return new DnsSettings(DnsSettings.split(text(item, "servers")),
                DnsSettings.split(text(item, "dynamic-servers")), "true".equals(text(item, "allow-remote-requests")));
    }

    /**
     * Has the router look the name up (a single ping to it: RouterOS resolves the name first, with
     * its own DNS settings and cache), then reads what it learned from its DNS cache, following
     * CNAMEs. Only uses commands the ping page already needs; no scripts run on the router.
     */
    @Override
    public DnsAnswer resolve(String name) throws RouterException {
        String question = DnsAnswer.validName(name);
        PingReply reply = pingOnce(question, 0, 0);
        List<DnsAnswer.Record> records = new ArrayList<>();
        java.util.Set<String> asked = new java.util.HashSet<>();
        java.util.ArrayDeque<String> names = new java.util.ArrayDeque<>(List.of(question));
        while (!names.isEmpty() && asked.size() < 6) { // a CNAME chain, but never a loop
            String current = names.removeFirst();
            if (!asked.add(current.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            for (DnsAnswer.Record record : cached(current)) {
                records.add(record);
                if (record.type().equals("CNAME")) {
                    names.add(record.data());
                }
            }
        }
        boolean anyAddress = records.stream().anyMatch(DnsAnswer.Record::address);
        if (!anyAddress && PingReply.isIpAddress(reply.host())) {
            // the cache could not be read (or keeps only some types): the ping still shows the address
            records.add(new DnsAnswer.Record(question, reply.host().contains(":") ? "AAAA" : "A", reply.host(),
                    java.util.Optional.empty()));
        }
        return new DnsAnswer(question, records.stream().distinct().toList());
    }

    /** The router's DNS cache entries for one name; empty when the cache cannot be read. */
    private List<DnsAnswer.Record> cached(String name) {
        String query = "?name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                + "&.proplist=name,type,data,ttl";
        for (String path : List.of("/ip/dns/cache/all", "/ip/dns/cache")) {
            try {
                JsonNode list = send(path, HttpRequest.newBuilder(URI.create(baseUri + path + query)).GET());
                List<DnsAnswer.Record> found = new ArrayList<>();
                for (JsonNode item : array(list, "DNS cache")) {
                    String type = text(item, "type");
                    found.add(new DnsAnswer.Record(text(item, "name"), type.isEmpty() ? "A" : type,
                            text(item, "data"), RouterDuration.parse(text(item, "ttl"))));
                }
                return found;
            } catch (RouterException e) {
                LOG.log(System.Logger.Level.DEBUG, "DNS cache not read from {0}: {1}", path, e.getMessage());
            }
        }
        return List.of();
    }

    @Override
    public List<org.netpilot.model.InterfaceState> interfaces() throws RouterException {
        List<org.netpilot.model.InterfaceState> out = new ArrayList<>();
        for (JsonNode item : array(get("/interface", "name,type,running,disabled"), "interfaces")) {
            String name = text(item, "name");
            if (!name.isEmpty()) {
                out.add(new org.netpilot.model.InterfaceState(name, text(item, "type"),
                        "true".equals(text(item, "running")), "true".equals(text(item, "disabled"))));
            }
        }
        return List.copyOf(out);
    }

    /**
     * A rule in the {@code input} chain that drops or rejects UDP (or any protocol) to port 53 and is
     * not disabled. Whether it covers only the internet side is not judged: any such rule counts,
     * which keeps the check simple and never cries wolf over a rule the user wrote on purpose.
     */
    @Override
    public boolean firewallBlocksDns() throws RouterException {
        for (JsonNode rule : array(get("/ip/firewall/filter", "chain,action,protocol,dst-port,disabled"),
                "firewall rules")) {
            String action = text(rule, "action");
            String protocol = text(rule, "protocol");
            if ("input".equals(text(rule, "chain")) && ("drop".equals(action) || "reject".equals(action))
                    && (protocol.isEmpty() || "udp".equals(protocol)) && !"true".equals(text(rule, "disabled"))
                    && covers53(text(rule, "dst-port"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code 53}, {@code 53,123}, {@code 1-1024}: whether port 53 is in it. No port at all is every
     * port, as in the usual "drop everything not from the LAN" rule.
     */
    static boolean covers53(String ports) {
        if (ports.isBlank()) {
            return true;
        }
        for (String part : ports.split(",")) {
            String p = part.strip();
            try {
                if (p.contains("-")) {
                    String[] range = p.split("-", 2);
                    if (Integer.parseInt(range[0].strip()) <= 53 && Integer.parseInt(range[1].strip()) >= 53) {
                        return true;
                    }
                } else if (!p.isEmpty() && Integer.parseInt(p) == 53) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // a named port list: not judged
            }
        }
        return false;
    }

    @Override
    public List<LogEntry> log() throws RouterException {
        List<LogEntry> lines = new ArrayList<>();
        for (JsonNode item : array(get("/log", ".id,time,topics,message"), "log")) {
            String id = text(item, ".id");
            if (!id.isEmpty()) {
                lines.add(new LogEntry(id, text(item, "time"), LogEntry.topics(text(item, "topics")),
                        text(item, "message")));
            }
        }
        return List.copyOf(lines);
    }

    @Override
    public void close() {
        http.close();
        if (apiLogin != null) {
            apiLogin.close();
        }
    }

    @Override
    public java.util.Optional<ApiLogin> apiLogin() {
        return java.util.Optional.ofNullable(apiLogin);
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
