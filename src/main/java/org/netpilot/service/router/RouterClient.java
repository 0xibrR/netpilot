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

    @Override
    void close();
}
