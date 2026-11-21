package org.netpilot.service.router;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.netpilot.model.ActiveSession;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterInfo;
import org.netpilot.model.RouterResource;
import org.netpilot.model.RouterUser;

/**
 * An authenticated session with one router.
 *
 * <p>An interface so other transports (e.g. the RouterOS v6 binary API) can be added later
 * without touching callers. Methods block; call them from a background thread. Close the
 * client to end the session.
 */
public interface RouterClient extends AutoCloseable {

    RouterEndpoint endpoint();

    RouterInfo info() throws RouterException;

    /** Current CPU, memory and uptime; cheap enough to poll every few seconds. */
    RouterResource resource() throws RouterException;

    List<HotspotProfile> hotspotProfiles() throws RouterException;

    List<HotspotServer> hotspotServers() throws RouterException;

    /**
     * Adds a hotspot user profile.
     *
     * @return the router's id for the new profile
     */
    String createHotspotProfile(HotspotProfile profile) throws RouterException;

    /** Changes name, speed and shared users of an existing profile ({@link HotspotProfile#routerId()}). */
    void updateHotspotProfile(HotspotProfile profile) throws RouterException;

    void deleteHotspotProfile(String routerId) throws RouterException;

    /** Names of all hotspot users that exist on the router. */
    Set<String> hotspotUsernames() throws RouterException;

    /**
     * Creates one hotspot user.
     *
     * @return the router's id for the new user (e.g. {@code *1A}), needed to delete it again
     * @throws RouterException with {@link RouterException.Kind#ALREADY_EXISTS} if the name is taken
     */
    String createHotspotUser(HotspotUser user, String comment) throws RouterException;

    /** Deletes the hotspot user with the given router id. Succeeds if it is already gone. */
    void deleteHotspotUser(String routerId) throws RouterException;

    /** All hotspot users, without passwords. */
    List<RouterUser> hotspotUsers() throws RouterException;

    /** Users logged in right now. */
    List<ActiveSession> activeSessions() throws RouterException;

    void setHotspotUserDisabled(String routerId, boolean disabled) throws RouterException;

    /** Ends an active session (the user can log in again). Succeeds if it already ended. */
    void disconnect(String sessionId) throws RouterException;

    /**
     * Reads passwords, only for printing vouchers. Never log the result.
     *
     * @return password per router id; ids that no longer exist are missing from the map
     */
    Map<String, String> hotspotPasswords(Collection<String> routerIds) throws RouterException;

    /**
     * When cards with a validity end, from the router's scheduler tasks (see
     * {@link org.netpilot.model.CardValidity}).
     *
     * @return the end time as RouterOS shows it ({@code 2026-11-15 10:00:00}) per card name
     */
    Map<String, String> cardExpiries() throws RouterException;

    /** Whether the router sets its clock from the internet (NTP client enabled). */
    boolean clockSynced() throws RouterException;

    /**
     * Sends one ping probe from the router to {@code address} and waits for its reply (or timeout).
     *
     * @throws IllegalArgumentException when the address is not an IP address or host name
     */
    org.netpilot.model.PingReply ping(String address, int seq) throws RouterException;

    /**
     * One ping probe with this TTL, for traceroute: the router where it runs out answers "TTL
     * exceeded" ({@link org.netpilot.model.PingReply#ttlExceeded()}); with a TTL long enough the address
     * itself answers.
     *
     * @param ttl 1 to 255
     */
    org.netpilot.model.PingReply probe(String address, int ttl) throws RouterException;

    /** The router's DNS settings (read only). */
    org.netpilot.model.DnsSettings dnsSettings() throws RouterException;

    /**
     * Has the router look a name up with its own DNS settings.
     *
     * @throws IllegalArgumentException when it is not a host name
     */
    org.netpilot.model.DnsAnswer resolve(String name) throws RouterException;

    /** The router's log, oldest line first (read only). */
    java.util.List<org.netpilot.model.LogEntry> log() throws RouterException;

    /** The router's interfaces with their link state (read only), for the alerts. */
    java.util.List<org.netpilot.model.InterfaceState> interfaces() throws RouterException;

    /**
     * Whether a firewall rule drops or rejects DNS (port 53) coming in to the router, so it does
     * not answer DNS questions from the internet. Read only.
     */
    boolean firewallBlocksDns() throws RouterException;

    /**
     * The port of the router's {@code api} service ({@code api-ssl} with {@code tls}) as set in
     * IP > Services, when it is turned on; empty when it is off or cannot be read. Read only.
     */
    default java.util.OptionalInt apiServicePort(boolean tls) throws RouterException {
        return java.util.OptionalInt.empty();
    }

    /**
     * The session's login for a RouterOS API connection (live updates), while the session is open;
     * empty for clients that have none.
     */
    default java.util.Optional<ApiLogin> apiLogin() {
        return java.util.Optional.empty();
    }

    /**
     * A hotspot server profile (IP > Hotspot > Server Profiles) and the folder its login pages
     * come from.
     */
    record PageProfile(String routerId, String name, String folder) {
    }

    /** The server profiles the hotspot servers use, with their page folders. Read only. */
    default java.util.List<PageProfile> hotspotPageProfiles() throws RouterException {
        throw new RouterException(RouterException.Kind.NOT_SUPPORTED, "not supported");
    }

    /** Makes a server profile take its pages from {@code folder}. */
    default void setPageFolder(String routerId, String folder) throws RouterException {
        throw new RouterException(RouterException.Kind.NOT_SUPPORTED, "not supported");
    }

    /** Creates or replaces a text file in the router's storage (its folder is made when missing). */
    default void writeTextFile(String name, String contents) throws RouterException {
        throw new RouterException(RouterException.Kind.NOT_SUPPORTED, "not supported");
    }

    /** Whether the router keeps files on a {@code flash} disk (files outside it are lost on restart). */
    default boolean hasFlashStorage() throws RouterException {
        return false;
    }

    /** A file's size in bytes, or -1 when there is no such file. Read only. */
    default long fileSize(String name) throws RouterException {
        return -1;
    }

    /** Has the router download {@code url} into the file {@code name}. */
    default void fetchFile(String url, String name) throws RouterException {
        throw new RouterException(RouterException.Kind.NOT_SUPPORTED, "not supported");
    }

    /** One card in full, as WinBox's user window shows it (password included). Read only. */
    default org.netpilot.model.CardDetails cardDetails(String routerId) throws RouterException {
        throw new RouterException(RouterException.Kind.NOT_SUPPORTED, "not supported");
    }

    /** Changes the given RouterOS properties of a card (only those, like WinBox's Apply). */
    default void updateCard(String routerId, Map<String, String> changes) throws RouterException {
        throw new RouterException(RouterException.Kind.NOT_SUPPORTED, "not supported");
    }

    /** Sets a card's used time and data back to zero (WinBox's Reset Counters). */
    default void resetCardCounters(String routerId) throws RouterException {
        throw new RouterException(RouterException.Kind.NOT_SUPPORTED, "not supported");
    }

    @Override
    void close();
}
