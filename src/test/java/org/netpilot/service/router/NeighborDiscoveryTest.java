package org.netpilot.service.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.netpilot.model.Neighbor;

class NeighborDiscoveryTest {

    @Test
    void readsAnAnnouncementLikeWinBoxShowsIt() throws Exception {
        byte[] packet = packet(
                tlv(1, new byte[]{(byte) 0xD4, 0x01, (byte) 0xC3, 0x79, 0x48, (byte) 0xB4}),
                tlv(5, "hEX"),
                tlv(7, "7.25.1 (stable)"),
                tlv(8, "MikroTik"),
                tlv(10, littleEndian(8603)),
                tlv(12, "RB960PGS"),
                tlv(16, "bridge_LAN/ether2"),
                tlv(17, new byte[]{10, 0, 0, 1}));

        Optional<Neighbor> neighbor = NeighborDiscovery.parse(packet, packet.length, InetAddress.getByName("10.0.0.9"));

        assertEquals(Optional.of(new Neighbor("D4:01:C3:79:48:B4", "10.0.0.1", "hEX", "7.25.1 (stable)", "RB960PGS",
                Duration.ofSeconds(8603), "bridge_LAN/ether2")), neighbor);
        assertTrue(neighbor.get().reachable());
    }

    @Test
    void usesTheSenderWhenTheAnnouncementHasNoAddress() throws Exception {
        byte[] packet = packet(tlv(5, "office"));

        Neighbor neighbor = NeighborDiscovery.parse(packet, packet.length, InetAddress.getByName("192.168.88.1"))
                .orElseThrow();

        assertEquals("192.168.88.1", neighbor.address());
        assertEquals("192.168.88.1", neighbor.key(), "no MAC: the address identifies it");
    }

    @Test
    void routerWithoutIpCannotBeReached() {
        Neighbor neighbor = new Neighbor("AA:BB:CC:DD:EE:FF", "", "x", "", "", null, null);

        assertFalse(neighbor.reachable());
        assertEquals(Duration.ZERO, neighbor.uptime());
    }

    @Test
    void ignoresRequestsAndBrokenPackets() throws Exception {
        InetAddress sender = InetAddress.getByName("10.0.0.2");
        assertEquals(Optional.empty(), NeighborDiscovery.parse(new byte[4], 4, sender), "our own empty request");
        byte[] truncated = packet(tlv(5, "hEX"));
        assertEquals(Optional.empty(), NeighborDiscovery.parse(truncated, truncated.length - 1, sender));
        byte[] unknownOnly = packet(tlv(99, "x"));
        assertEquals(Optional.empty(), NeighborDiscovery.parse(unknownOnly, unknownOnly.length, sender));
    }

    private static byte[] packet(byte[]... fields) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, 0, 1}); // header: type, ttl, sequence
        for (byte[] field : fields) {
            out.write(field);
        }
        return out.toByteArray();
    }

    private static byte[] tlv(int type, String value) {
        return tlv(type, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] tlv(int type, byte[] value) {
        return ByteBuffer.allocate(4 + value.length).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) type).putShort((short) value.length).put(value).array();
    }

    private static byte[] littleEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
