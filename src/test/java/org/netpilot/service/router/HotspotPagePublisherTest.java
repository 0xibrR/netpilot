package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.netpilot.model.RouterEndpoint;

class HotspotPagePublisherTest {

    /** A router in memory: its files, its server profiles and what was asked of it. */
    private static final class Memory {

        final Map<String, byte[]> files = new HashMap<>();
        final Map<String, String> folders = new LinkedHashMap<>(Map.of("*1", "hotspot", "*2", "flash/hotspot"));
        final List<String> fetched = new ArrayList<>();
        /** Files the REST write cuts short, as a router with a size limit would. */
        final List<String> cutShort = new ArrayList<>();
        /** Like a hEX: files belong on the flash disk. */
        boolean flash;

        RouterClient client() {
            return (RouterClient) Proxy.newProxyInstance(RouterClient.class.getClassLoader(),
                    new Class<?>[]{RouterClient.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "endpoint" -> new RouterEndpoint("127.0.0.1", 80, false, "admin");
                        case "hasFlashStorage" -> flash;
                        case "hotspotPageProfiles" -> folders.entrySet().stream()
                                .map(e -> new RouterClient.PageProfile(e.getKey(), "prof" + e.getKey(), e.getValue()))
                                .toList();
                        case "setPageFolder" -> {
                            folders.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "writeTextFile" -> {
                            byte[] bytes = ((String) args[1]).getBytes(StandardCharsets.UTF_8);
                            String name = (String) args[0];
                            files.put(name, cutShort.stream().anyMatch(name::endsWith)
                                    ? java.util.Arrays.copyOf(bytes, Math.min(10, bytes.length))
                                    : bytes);
                            yield null;
                        }
                        case "fileSize" -> files.containsKey((String) args[0])
                                ? (long) files.get((String) args[0]).length
                                : -1L;
                        case "fetchFile" -> {
                            fetched.add((String) args[1]);
                            HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                                    HttpRequest.newBuilder(URI.create((String) args[0])).timeout(Duration.ofSeconds(5))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofByteArray());
                            if (r.statusCode() == 200) {
                                files.put((String) args[1], r.body());
                            }
                            yield null;
                        }
                        case "toString" -> "memory router";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final Map<String, String> PAGES = new LinkedHashMap<>(Map.of("login.html", "<html>دخول</html>",
            "md5.js", "function hexMD5(s) {}"));

    @Test
    void pagesGoToTheirOwnFolderAndTheOldFoldersAreRemembered() throws Exception {
        Memory router = new Memory();
        Map<String, String> before = HotspotPagePublisher.publish(router.client(), PAGES);

        assertEquals(Map.of("prof*1", "hotspot", "prof*2", "flash/hotspot"), before);
        assertEquals("<html>دخول</html>", new String(router.files.get("netpilot-hotspot/login.html"),
                StandardCharsets.UTF_8));
        assertEquals(Map.of("*1", "netpilot-hotspot", "*2", "netpilot-hotspot"), router.folders);
        assertEquals(List.of(), router.fetched, "everything arrived through REST");

        assertEquals(Map.of(), HotspotPagePublisher.publish(router.client(), PAGES),
                "sent again: the old folders are not forgotten by being overwritten");

        HotspotPagePublisher.revert(router.client(), before);
        assertEquals(Map.of("*1", "hotspot", "*2", "flash/hotspot"), router.folders, "exactly as before");

        router.folders.put("*1", "netpilot-hotspot");
        HotspotPagePublisher.revert(router.client(), Map.of());
        assertEquals("hotspot", router.folders.get("*1"), "nothing remembered: the router's default folder");
    }

    @Test
    void aFileCutShortIsDownloadedByTheRouterFromThisComputer() throws Exception {
        Memory router = new Memory();
        router.cutShort.add("login.html");
        HotspotPagePublisher.publish(router.client(), PAGES);

        assertEquals(List.of("netpilot-hotspot/login.html"), router.fetched);
        assertEquals("<html>دخول</html>", new String(router.files.get("netpilot-hotspot/login.html"),
                StandardCharsets.UTF_8), "whole after the download");
        assertTrue(HotspotPagePublisher.addressTowards("127.0.0.1").isLoopbackAddress());
    }

    @Test
    void onARouterWithFlashThePagesGoOnTheFlashDisk() throws Exception {
        Memory router = new Memory();
        router.flash = true;
        router.folders.put("*2", "netpilot-hotspot"); // an earlier send that went to memory
        Map<String, String> before = HotspotPagePublisher.publish(router.client(), PAGES);

        assertTrue(router.files.containsKey("flash/netpilot-hotspot/login.html"), "kept across a restart");
        assertEquals(Map.of("prof*1", "hotspot"), before, "our own old folder is never remembered as theirs");
        assertEquals("flash/netpilot-hotspot", router.folders.get("*1"));
        assertEquals("netpilot-hotspot", router.folders.get("*2"), "already ours: left as it is");

        HotspotPagePublisher.revert(router.client(), before);
        assertEquals("hotspot", router.folders.get("*1"));
        assertEquals("flash/hotspot", router.folders.get("*2"), "nothing remembered: the flash default");
        assertTrue(org.netpilot.service.LoginPageBuilder.isOurs("flash/netpilot-hotspot"));
        assertTrue(!org.netpilot.service.LoginPageBuilder.isOurs("flash/hotspot"));
    }

    @Test
    void aRouterWithoutAHotspotIsToldSoAndNothingIsChanged() {
        Memory router = new Memory();
        router.folders.clear();
        assertThrows(RouterException.class, () -> HotspotPagePublisher.publish(router.client(), PAGES));
        assertEquals(Map.of(), router.files);
    }
}
