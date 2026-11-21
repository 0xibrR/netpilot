package org.netpilot.service.router;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.netpilot.i18n.Messages;
import org.netpilot.service.LoginPageBuilder;

/**
 * Sends NetPilot's login pages to the router and makes the hotspot use them, without touching the
 * router's own pages: they go into their own folder, and only the server profiles' page folder
 * setting changes, which {@link #revert} sets back.
 *
 * <p>Each file is written through the REST API and its size checked. A file that does not arrive
 * whole is fetched by the router from this computer instead, over the local network, from a
 * short-lived server that answers only one secret path and stops as soon as the copy is done.
 */
public final class HotspotPagePublisher {

    private static final System.Logger LOG = System.getLogger(HotspotPagePublisher.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();

    private HotspotPagePublisher() {
    }

    /**
     * @return each server profile's page folder before, by profile name (profiles already on
     *         NetPilot's folder are left out)
     * @throws RouterException when the router has no hotspot, or a page cannot be sent
     */
    public static Map<String, String> publish(RouterClient client, Map<String, String> files) throws RouterException {
        List<RouterClient.PageProfile> profiles = client.hotspotPageProfiles();
        if (profiles.isEmpty()) {
            throw new RouterException(RouterException.Kind.NOT_SUPPORTED, Messages.get("loginPage.noHotspot"));
        }
        // routers with a flash disk keep files outside it in memory only: lost on restart, and not where
        // the hotspot looks (RouterOS itself turns the setting into flash/...)
        String base = client.hasFlashStorage() ? "flash/" + LoginPageBuilder.FOLDER : LoginPageBuilder.FOLDER;
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            String name = base + "/" + f.getKey();
            try {
                client.writeTextFile(name, f.getValue());
                if (client.fileSize(name) != bytes(f.getValue()).length) {
                    missing.add(f.getKey());
                }
            } catch (RouterException e) {
                if (e.affectsEveryRequest()) {
                    throw e;
                }
                LOG.log(System.Logger.Level.INFO, "Writing {0} through REST failed: {1}", name, e.getMessage());
                missing.add(f.getKey());
            }
        }
        if (!missing.isEmpty()) {
            fetchFromHere(client, base, files, missing);
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (RouterClient.PageProfile p : profiles) {
            if (!LoginPageBuilder.isOurs(p.folder())) {
                before.put(p.name(), p.folder());
                client.setPageFolder(p.routerId(), base);
            }
        }
        LOG.log(System.Logger.Level.INFO, "Login pages sent ({0} files, {1} by download); profiles {2}", files.size(),
                missing.size(), before.keySet());
        return before;
    }

    /**
     * Puts each server profile back on the folder it used before NetPilot's pages; a profile with
     * nothing remembered goes back to the router's default {@code hotspot} folder.
     */
    public static void revert(RouterClient client, Map<String, String> before) throws RouterException {
        for (RouterClient.PageProfile p : client.hotspotPageProfiles()) {
            if (before.containsKey(p.name())) {
                client.setPageFolder(p.routerId(), before.get(p.name()));
            } else if (LoginPageBuilder.isOurs(p.folder())) {
                client.setPageFolder(p.routerId(), client.hasFlashStorage() ? "flash/hotspot" : "hotspot");
            }
        }
        LOG.log(System.Logger.Level.INFO, "Hotspot pages set back to {0}", before);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The files the REST API could not write whole, downloaded by the router from this computer. */
    private static void fetchFromHere(RouterClient client, String base, Map<String, String> files, List<String> names)
            throws RouterException {
        InetAddress here = addressTowards(client.endpoint().host());
        byte[] random = new byte[12];
        RANDOM.nextBytes(random);
        String secret = HexFormat.of().formatHex(random);
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(here, 0));
            server.setSoTimeout(15_000);
            for (String name : names) {
                String url = "http://" + here.getHostAddress() + ":" + server.getLocalPort() + "/" + secret + "/"
                        + name;
                Thread serving = serveOnce(server, secret, files);
                client.fetchFile(url, base + "/" + name);
                serving.join(15_000);
                if (client.fileSize(base + "/" + name) != bytes(files.get(name)).length) {
                    throw new RouterException(RouterException.Kind.UNEXPECTED_RESPONSE,
                            Messages.get("loginPage.notWhole", name));
                }
            }
        } catch (IOException e) {
            throw new RouterException(RouterException.Kind.UNREACHABLE, Messages.get("loginPage.fetchFailed"), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RouterException(RouterException.Kind.UNREACHABLE, Messages.get("router.error.cancelled"), e);
        }
    }

    /** Answers one request: the file named after the secret path, else 404. */
    private static Thread serveOnce(ServerSocket server, String secret, Map<String, String> files) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(10_000);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.ISO_8859_1));
                String[] request = String.valueOf(in.readLine()).split(" ");
                String prefix = "/" + secret + "/";
                String name = request.length > 1 && request[1].startsWith(prefix)
                        ? request[1].substring(prefix.length())
                        : "";
                byte[] body = files.containsKey(name) ? bytes(files.get(name)) : new byte[0];
                String status = files.containsKey(name) ? "200 OK" : "404 Not Found";
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.0 " + status + "\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.INFO, "Serving a login page to the router failed: {0}", e.getMessage());
            }
        }, "netpilot-page-server");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** This computer's address on the network that reaches the router (no packet is sent). */
    static InetAddress addressTowards(String routerHost) throws RouterException {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(routerHost), 80);
            InetAddress local = probe.getLocalAddress();
            if (local == null || local.isAnyLocalAddress()) {
                throw new IOException("no local address towards " + routerHost);
            }
            return local;
        } catch (IOException e) {
            throw new RouterException(RouterException.Kind.UNREACHABLE, Messages.get("loginPage.fetchFailed"), e);
        }
    }
}
