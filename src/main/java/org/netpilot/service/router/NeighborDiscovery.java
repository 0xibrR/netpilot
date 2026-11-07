package org.netpilot.service.router;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.netpilot.model.Neighbor;

/**
 * Finds MikroTik routers on the local network with the MikroTik Neighbor Discovery Protocol
 * (MNDP, UDP port 5678), the way WinBox fills its Neighbors list.
 *
 * <p>Routers announce themselves about once a minute; sending an empty request makes them answer
 * at once. Discovery only reads announcements and never sends credentials.
 */
public final class NeighborDiscovery {

    public static final int PORT = 5678;

    private static final System.Logger LOG = System.getLogger(NeighborDiscovery.class.getName());

    private static final int TLV_MAC = 1;
    private static final int TLV_IDENTITY = 5;
    private static final int TLV_VERSION = 7;
    private static final int TLV_BOARD = 12;
    private static final int TLV_UPTIME = 10;
    private static final int TLV_INTERFACE = 16;
    private static final int TLV_IPV4 = 17;
    private static final int HEADER = 4;

    /**
     * Asks the network for routers and collects the answers.
     *
     * @param wait how long to listen
     * @return routers found, one entry per answering interface, sorted by address
     * @throws IOException if no UDP socket can be opened at all
     */
    public List<Neighbor> scan(Duration wait) throws IOException {
        Map<String, Neighbor> found = new LinkedHashMap<>();
        try (DatagramSocket socket = open()) {
            socket.setBroadcast(true);
            solicit(socket);
            long end = System.nanoTime() + wait.toNanos();
            byte[] buffer = new byte[1500];
            while (System.nanoTime() < end) {
                long left = Math.max(1, (end - System.nanoTime()) / 1_000_000);
                socket.setSoTimeout((int) Math.min(left, 500));
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                parse(packet.getData(), packet.getLength(), packet.getAddress())
                        .ifPresent(n -> found.merge(n.key(), n, NeighborDiscovery::prefer));
            }
        }
        List<Neighbor> result = new ArrayList<>(found.values());
        result.sort(java.util.Comparator.comparing(Neighbor::reachable).reversed()
                .thenComparing(Neighbor::address).thenComparing(Neighbor::identity));
        return result;
    }

    /**
     * Reads one MNDP packet: a 4-byte header, then type-length-value fields (big-endian 16-bit type
     * and length).
     *
     * @param sender used as the address when the packet has no IPv4 field
     * @return empty for packets that are not MNDP announcements (e.g. our own request)
     */
    static Optional<Neighbor> parse(byte[] data, int length, InetAddress sender) {
        if (length <= HEADER) {
            return Optional.empty();
        }
        ByteBuffer in = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);
        in.position(HEADER);
        String mac = "";
        String identity = "";
        String version = "";
        String board = "";
        String interfaceName = "";
        String ipv4 = "";
        Duration uptime = Duration.ZERO;
        while (in.remaining() >= 4) {
            int type = Short.toUnsignedInt(in.getShort());
            int size = Short.toUnsignedInt(in.getShort());
            if (size > in.remaining()) {
                return Optional.empty(); // truncated or not MNDP
            }
            byte[] value = new byte[size];
            in.get(value);
            switch (type) {
                case TLV_MAC -> mac = mac(value);
                case TLV_IDENTITY -> identity = text(value);
                case TLV_VERSION -> version = text(value);
                case TLV_BOARD -> board = text(value);
                case TLV_INTERFACE -> interfaceName = text(value);
                case TLV_UPTIME -> uptime = uptime(value);
                case TLV_IPV4 -> ipv4 = value.length == 4 ? ipv4(value) : "";
                default -> {
                    // other fields (software id, IPv6, unpack) are not shown
                }
            }
        }
        if (mac.isEmpty() && identity.isEmpty()) {
            return Optional.empty();
        }
        if (ipv4.isEmpty() && sender != null && sender.getAddress().length == 4 && !sender.isAnyLocalAddress()) {
            ipv4 = sender.getHostAddress();
        }
        return Optional.of(new Neighbor(mac, ipv4, identity, version, board, uptime, interfaceName));
    }

    /** Keeps the answer that has an address, and the newest uptime. */
    private static Neighbor prefer(Neighbor old, Neighbor now) {
        return now.reachable() || !old.reachable() ? now : old;
    }

    /** Port 5678 receives the routers' regular announcements too; any port works for answers. */
    private static DatagramSocket open() throws SocketException {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            return socket;
        } catch (SocketException e) {
            LOG.log(System.Logger.Level.DEBUG, "Port {0} is busy, listening on a free port", PORT);
            return new DatagramSocket();
        }
    }

    private static void solicit(DatagramSocket socket) {
        byte[] request = new byte[HEADER];
        for (InetAddress target : broadcastAddresses()) {
            try {
                socket.send(new DatagramPacket(request, request.length, target, PORT));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "Cannot send discovery to {0}: {1}", target, e.getMessage());
            }
        }
    }

    /** 255.255.255.255 plus the broadcast address of every active IPv4 interface. */
    private static Collection<InetAddress> broadcastAddresses() {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            targets.add(InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}));
            for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) {
                        targets.add(address.getBroadcast());
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot list network interfaces: {0}", e.getMessage());
        }
        return targets;
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8).strip();
    }

    private static String mac(byte[] value) {
        if (value.length != 6) {
            return "";
        }
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(value);
    }

    private static String ipv4(byte[] value) {
        return Byte.toUnsignedInt(value[0]) + "." + Byte.toUnsignedInt(value[1]) + "."
                + Byte.toUnsignedInt(value[2]) + "." + Byte.toUnsignedInt(value[3]);
    }

    /** Seconds since boot, little-endian (unlike the TLV headers). */
    private static Duration uptime(byte[] value) {
        if (value.length != 4) {
            return Duration.ZERO;
        }
        long seconds = Integer.toUnsignedLong(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt());
        return Duration.ofSeconds(seconds);
    }
}
