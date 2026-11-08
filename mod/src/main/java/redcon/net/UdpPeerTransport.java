package redcon.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Real UDP socket adapter for {@link ReliableUdpChannel}.
 *
 * <p>There is deliberately no receiver thread. The platform frame pump calls
 * {@link #update(long)}, and complete messages are drained on that same game
 * thread before invoking any duel consumer.</p>
 */
public final class UdpPeerTransport implements AutoCloseable {

    private static final int RECEIVE_BYTES = 2048;
    private static final int MAX_RECEIVES_PER_UPDATE = 256;

    private final DatagramSocket socket;
    private final ReliableUdpChannel reliable;
    private final Queue<byte[]> unreliableOutbound = new ArrayDeque<>();
    private final Queue<byte[]> unreliableInbound = new ArrayDeque<>();
    private InetSocketAddress remote;
    private int invalidDatagrams;
    private int foreignDatagrams;
    private boolean closed;

    /** Bind to a specific local address; port zero selects an ephemeral port. */
    public UdpPeerTransport(InetSocketAddress local, long sessionId)
            throws IOException {
        if (local == null) {
            throw new IllegalArgumentException("local UDP address is null");
        }
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(local);
        socket.setSoTimeout(1);
        reliable = new ReliableUdpChannel(sessionId);
    }

    public synchronized void connect(InetSocketAddress remote) {
        requireOpen();
        if (remote == null || remote.getPort() <= 0) {
            throw new IllegalArgumentException("invalid remote UDP address");
        }
        this.remote = remote;
    }

    public synchronized int send(byte[] message) {
        requireConnected();
        return reliable.send(message);
    }

    /** Queue one best-effort datagram beside, not inside, the ordered stream. */
    public synchronized void sendUnreliable(byte[] message) {
        requireConnected();
        if (message == null || message.length == 0
                || message.length > LockstepInputCodec.MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("invalid unreliable datagram");
        }
        unreliableOutbound.add(message.clone());
    }

    /** Send due frames, receive all currently queued datagrams, then ACK. */
    public synchronized void update(long nowMillis) throws IOException {
        requireConnected();
        transmitUnreliable();
        transmit(reliable.pollDatagrams(nowMillis));
        for (int i = 0; i < MAX_RECEIVES_PER_UPDATE; i++) {
            byte[] storage = new byte[RECEIVE_BYTES];
            DatagramPacket packet = new DatagramPacket(storage, storage.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException empty) {
                break;
            }
            if (!sameHost(packet.getAddress(), remote)) {
                foreignDatagrams++;
                continue;
            }
            byte[] exact = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(),
                             exact, 0, packet.getLength());
            if (isLockstep(exact)) {
                try {
                    if (magic(exact) == LockstepInputCodec.MAGIC) {
                        LockstepInputCodec.decode(exact);
                    } else if (magic(exact) == LockstepDelayCodec.MAGIC) {
                        LockstepDelayCodec.decode(exact);
                    } else {
                        LockstepDigestCodec.decode(exact);
                    }
                    if (unreliableInbound.size() < MAX_RECEIVES_PER_UPDATE) {
                        unreliableInbound.add(exact);
                    } else {
                        invalidDatagrams++;
                    }
                } catch (IllegalArgumentException invalid) {
                    invalidDatagrams++;
                }
            } else {
                try {
                    reliable.receive(exact, nowMillis);
                } catch (IllegalArgumentException invalid) {
                    invalidDatagrams++;
                }
            }
        }
        transmit(reliable.pollDatagrams(nowMillis));
    }

    public synchronized List<byte[]> drainDelivered() {
        requireOpen();
        List<byte[]> delivered = reliable.drainDelivered();
        List<byte[]> copies = new ArrayList<>();
        for (byte[] message : delivered) {
            copies.add(message.clone());
        }
        return copies;
    }

    public synchronized List<byte[]> drainUnreliable() {
        requireOpen();
        List<byte[]> result = new ArrayList<>();
        while (!unreliableInbound.isEmpty()) {
            result.add(unreliableInbound.remove().clone());
        }
        return result;
    }

    public synchronized InetSocketAddress localAddress() {
        requireOpen();
        SocketAddress local = socket.getLocalSocketAddress();
        return (InetSocketAddress) local;
    }

    public synchronized boolean isIdle() { return reliable.isIdle(); }
    public synchronized boolean timedOut() { return reliable.timedOut(); }
    public synchronized boolean disconnected(long nowMillis) {
        return reliable.disconnected(nowMillis);
    }
    public synchronized int retransmissions() { return reliable.retransmissions(); }
    public synchronized int invalidDatagrams() { return invalidDatagrams; }
    public synchronized int foreignDatagrams() { return foreignDatagrams; }

    private void transmit(List<byte[]> datagrams) throws IOException {
        for (byte[] bytes : datagrams) {
            DatagramPacket packet =
                new DatagramPacket(bytes, bytes.length, remote);
            socket.send(packet);
        }
    }

    private void transmitUnreliable() throws IOException {
        while (!unreliableOutbound.isEmpty()) {
            byte[] bytes = unreliableOutbound.remove();
            socket.send(new DatagramPacket(bytes, bytes.length, remote));
        }
    }

    private static boolean isLockstep(byte[] bytes) {
        int magic = magic(bytes);
        return magic == LockstepInputCodec.MAGIC
            || magic == LockstepDigestCodec.MAGIC
            || magic == LockstepDelayCodec.MAGIC;
    }

    private static int magic(byte[] bytes) {
        return bytes.length < 4 ? 0
            : (bytes[0] & 0xff) << 24 | (bytes[1] & 0xff) << 16
                | (bytes[2] & 0xff) << 8 | (bytes[3] & 0xff);
    }

    private static boolean sameHost(InetAddress address,
                                    InetSocketAddress expected) {
        // UDP NATs commonly rewrite the sender's source port. Outbound frames
        // still target the peer's advertised listening port, while inbound
        // identity is enforced by the reliable channel's random session id.
        // Requiring the translated source port here makes two Android
        // emulators pair successfully and then immediately lose the match.
        return address.equals(expected.getAddress());
    }

    private void requireConnected() {
        requireOpen();
        if (remote == null || remote.isUnresolved()) {
            throw new IllegalStateException("UDP peer is not connected");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("UDP transport is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            socket.close();
        }
    }
}
