package redcon.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Unreliable one-hertz LAN announcement socket. */
public final class UdpDiscoverySocket implements AutoCloseable {

    public static final long ANNOUNCE_MILLIS = 1000;
    private static final int MAX_RECEIVES_PER_UPDATE = 128;
    private static final int MAX_DISCOVERED_ENDPOINTS = 128;

    public static final class Announcement {
        public final InetSocketAddress source;
        public final DuelHandshake handshake;

        private Announcement(InetSocketAddress source,
                             DuelHandshake handshake) {
            this.source = source;
            this.handshake = handshake;
        }
    }

    /** A pre-match pairing message and the socket endpoint which sent it. */
    public static final class PairingDatagram {
        public final InetSocketAddress source;
        public final DuelPairingMessage message;

        private PairingDatagram(InetSocketAddress source,
                                DuelPairingMessage message) {
            this.source = source;
            this.message = message;
        }
    }

    private final DatagramSocket socket;
    private final Queue<Announcement> announcements = new ArrayDeque<>();
    private final Queue<PairingDatagram> pairing = new ArrayDeque<>();
    private final Map<InetSocketAddress, Long> discovered = new LinkedHashMap<>();
    private long lastAnnouncement = Long.MIN_VALUE;
    private int invalidDatagrams;
    private boolean closed;

    public UdpDiscoverySocket(InetSocketAddress local) throws IOException {
        if (local == null) {
            throw new IllegalArgumentException("local discovery address is null");
        }
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(local);
        socket.setSoTimeout(1);
    }

    /**
     * Collect announcements and advertise once per second. Also send directly
     * to recently heard peers: some Wi-Fi devices can send broadcasts but do
     * not receive them. Replies share the regular cadence, avoiding echo loops.
     */
    public List<Announcement> update(InetSocketAddress target,
                                     DuelHandshake local,
                                     long nowMillis) throws IOException {
        requireOpen();
        if (target == null || local == null) {
            throw new IllegalArgumentException("target and handshake are required");
        }
        List<Announcement> received = poll();
        for (Announcement announcement : received) {
            if (announcement.handshake.instanceId == local.instanceId) {
                continue;
            }
            discovered.remove(announcement.source);
            discovered.put(announcement.source, nowMillis);
            if (discovered.size() > MAX_DISCOVERED_ENDPOINTS) {
                discovered.remove(discovered.keySet().iterator().next());
            }
        }
        Iterator<Long> seen = discovered.values().iterator();
        while (seen.hasNext()) {
            if (nowMillis - seen.next() >= DuelPeerDirectory.EXPIRE_MILLIS) {
                seen.remove();
            }
        }
        if (lastAnnouncement == Long.MIN_VALUE
                || nowMillis - lastAnnouncement >= ANNOUNCE_MILLIS) {
            byte[] bytes = local.encode();
            Set<InetSocketAddress> targets = announcementTargets(target);
            targets.addAll(discovered.keySet());
            IOException failure = null;
            boolean sent = false;
            for (InetSocketAddress destination : targets) {
                try {
                    socket.send(new DatagramPacket(bytes, bytes.length, destination));
                    sent = true;
                } catch (IOException failed) {
                    // An unavailable adapter must not prevent Wi-Fi or direct
                    // discovery from working on the other interfaces.
                    failure = failed;
                }
            }
            if (!sent && failure != null) {
                throw failure;
            }
            lastAnnouncement = nowMillis;
        }
        return received;
    }

    private Set<InetSocketAddress> announcementTargets(InetSocketAddress target) {
        Set<InetSocketAddress> targets = new LinkedHashSet<>();
        targets.add(target);
        if (target.getAddress() == null
                || !target.getAddress().getHostAddress().equals("255.255.255.255")) {
            // Explicit destinations are used for loopback tests and direct
            // connections. Do not turn those into LAN broadcasts.
            return targets;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isPointToPoint()) {
                    continue;
                }
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null && (socket.getLocalAddress().isAnyLocalAddress()
                            || socket.getLocalAddress().equals(address.getAddress()))) {
                        targets.add(new InetSocketAddress(broadcast, target.getPort()));
                    }
                }
            }
        } catch (SocketException | SecurityException unavailable) {
            // Restricted platforms can still use the limited broadcast and
            // the direct replies above.
        }
        return targets;
    }

    public List<Announcement> poll() throws IOException {
        requireOpen();
        receiveAvailable();
        List<Announcement> result = new ArrayList<>();
        while (!announcements.isEmpty()) {
            result.add(announcements.remove());
        }
        return result;
    }

    /** Send one Pair Request/response/OK datagram on the discovery socket. */
    public void sendPairing(InetSocketAddress target,
                            DuelPairingMessage message) throws IOException {
        requireOpen();
        if (target == null || target.isUnresolved() || target.getPort() <= 0) {
            throw new IllegalArgumentException("invalid pairing target");
        }
        byte[] bytes = DuelPairingCodec.encode(message);
        socket.send(new DatagramPacket(bytes, bytes.length, target));
        System.err.println("duel pairing send "
            + message.getClass().getSimpleName() + " -> " + target);
    }

    /** Return currently waiting pairing messages without losing announcements. */
    public List<PairingDatagram> pollPairing() throws IOException {
        requireOpen();
        receiveAvailable();
        List<PairingDatagram> result = new ArrayList<>();
        while (!pairing.isEmpty()) {
            result.add(pairing.remove());
        }
        return result;
    }

    private void receiveAvailable() throws IOException {
        for (int i = 0; i < MAX_RECEIVES_PER_UPDATE; i++) {
            byte[] storage = new byte[Math.max(
                DuelHandshake.MAX_PACKET_BYTES, DuelPairingCodec.MAX_PACKET_BYTES)];
            DatagramPacket packet = new DatagramPacket(storage, storage.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException empty) {
                break;
            }
            byte[] exact = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(),
                             exact, 0, packet.getLength());
            try {
                DuelHandshake handshake = DuelHandshake.decode(exact);
                InetSocketAddress source = new InetSocketAddress(
                    packet.getAddress(), handshake.listenPort);
                announcements.add(new Announcement(source, handshake));
                continue;
            } catch (IllegalArgumentException notHandshake) {
                // Pairing has a distinct magic and shares this pre-match socket.
            }
            try {
                DuelPairingMessage message = DuelPairingCodec.decode(exact);
                InetSocketAddress source = new InetSocketAddress(
                    packet.getAddress(), packet.getPort());
                pairing.add(new PairingDatagram(source, message));
                System.err.println("duel pairing recv "
                    + message.getClass().getSimpleName() + " <- " + source);
            } catch (IllegalArgumentException invalid) {
                invalidDatagrams++;
            }
        }
    }

    public InetSocketAddress localAddress() {
        requireOpen();
        SocketAddress address = socket.getLocalSocketAddress();
        return (InetSocketAddress) address;
    }

    public int invalidDatagrams() { return invalidDatagrams; }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("discovery socket is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            socket.close();
        }
    }
}
