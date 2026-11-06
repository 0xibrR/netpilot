package org.netpilot.service.router;

import java.util.List;
import org.netpilot.model.HotspotProfile;
import org.netpilot.model.HotspotServer;
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

    @Override
    void close();
}
