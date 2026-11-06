package org.netpilot.service.router;

import java.util.List;
import java.util.Set;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.RouterEndpoint;
import org.netpilot.model.RouterInfo;

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

    List<HotspotProfile> hotspotProfiles() throws RouterException;

    List<HotspotServer> hotspotServers() throws RouterException;

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

    @Override
    void close();
}
