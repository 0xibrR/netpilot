package org.netpilot.service.router;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterInfo;
import org.netpilot.service.router.RouterException.Kind;
import tools.jackson.databind.JsonNode;

/**
 * {@link RouterClient} over the RouterOS 7 REST API ({@code /rest/...}).
 *
 * <p>Requests ask only for the fields we use ({@code .proplist}) to keep responses small.
 * RouterOS returns every value as a string, including numbers and booleans.
 */
final class RestRouterClient implements RouterClient {

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
    public void close() {
        http.close();
    }

    private JsonNode get(String path, String proplist) throws RouterException {
        URI uri = URI.create(baseUri + path + "?.proplist=" + proplist);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", authorizationHeader)
                .header("Accept", "application/json")
                .GET()
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
