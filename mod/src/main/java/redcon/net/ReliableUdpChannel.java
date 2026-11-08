package redcon.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Small reliable-ordered message channel over unreliable UDP datagrams.
 *
 * <p>This class owns no socket and no thread. A platform transport sends the
 * datagrams returned by {@link #pollDatagrams(long)}, passes received bytes to
 * {@link #receive(byte[], long)}, then drains complete messages on the game
 * thread. That keeps the reliability algorithm deterministic and testable
 * under loss, duplication, delay, and reordering.</p>
 */
public final class ReliableUdpChannel {

    public static final int MAX_MESSAGE_BYTES = 1200;
    public static final int MAX_IN_FLIGHT = 1024;
    public static final long RETRANSMIT_MILLIS = 100;
    public static final int MAX_ATTEMPTS = 40;
    public static final long HEARTBEAT_MILLIS = 1000;
    public static final long DISCONNECT_MILLIS = 5000;

    private static final int MAGIC = 0x52553431; // "RU41"
    private static final int VERSION = 1;
    private static final int FLAG_DATA = 1;
    private static final int FLAG_HEARTBEAT = 2;
    private static final int HEADER_BYTES = 4 + 1 + 8 + 1 + 4 + 4 + 2;
    private static final int CHECKSUM_BYTES = 4;

    private static final class Pending {
        final int sequence;
        final byte[] payload;
        long lastSent = Long.MIN_VALUE;
        int attempts;

        Pending(int sequence, byte[] payload) {
            this.sequence = sequence;
            this.payload = payload;
        }
    }

    private static final class Frame {
        final int sequence;
        final int acknowledgement;
        final byte[] payload;
        final boolean heartbeat;

        Frame(int sequence, int acknowledgement, byte[] payload,
              boolean heartbeat) {
            this.sequence = sequence;
            this.acknowledgement = acknowledgement;
            this.payload = payload;
            this.heartbeat = heartbeat;
        }
    }

    private final long sessionId;
    private final LinkedHashMap<Integer, Pending> pending = new LinkedHashMap<>();
    private final TreeMap<Integer, byte[]> reordered = new TreeMap<>();
    private final Queue<byte[]> delivered = new ArrayDeque<>();

    private int nextSequence = 1;
    private int receivedThrough;
    private int acknowledgedThrough;
    private boolean acknowledgementDirty;
    private boolean timedOut;
    private int retransmissions;
    private int duplicates;
    private boolean clockStarted;
    private long firstPollMillis;
    private long lastSentMillis;
    private long lastReceivedMillis = Long.MIN_VALUE;

    public ReliableUdpChannel(long sessionId) {
        if (sessionId == 0) {
            throw new IllegalArgumentException("zero UDP session id");
        }
        this.sessionId = sessionId;
    }

    /** Queue one complete application message for reliable delivery. */
    public synchronized int send(byte[] message) {
        if (message == null || message.length == 0
                || message.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("invalid reliable message length");
        }
        if (pending.size() >= MAX_IN_FLIGHT || nextSequence == Integer.MAX_VALUE) {
            throw new IllegalStateException("reliable send window is full");
        }
        int sequence = nextSequence++;
        pending.put(sequence, new Pending(sequence, message.clone()));
        return sequence;
    }

    /**
     * Return new transmissions, due retransmissions, and an ACK-only frame
     * when needed. The caller sends each byte array as one UDP datagram.
     */
    public synchronized List<byte[]> pollDatagrams(long nowMillis) {
        if (!clockStarted) {
            clockStarted = true;
            firstPollMillis = nowMillis;
            lastSentMillis = nowMillis;
        }
        List<byte[]> result = new ArrayList<>();
        boolean carriedAck = false;
        for (Pending item : pending.values()) {
            boolean first = item.attempts == 0;
            boolean due = !first && nowMillis - item.lastSent >= RETRANSMIT_MILLIS;
            if (!first && !due) {
                continue;
            }
            if (item.attempts >= MAX_ATTEMPTS) {
                timedOut = true;
                continue;
            }
            if (!first) {
                retransmissions++;
            }
            result.add(encodeFrame(
                sessionId, item.sequence, receivedThrough, item.payload, false));
            item.lastSent = nowMillis;
            item.attempts++;
            carriedAck = true;
        }
        if (acknowledgementDirty && !carriedAck) {
            result.add(encodeFrame(
                sessionId, 0, receivedThrough, null, false));
        } else if (result.isEmpty()
                && nowMillis - lastSentMillis >= HEARTBEAT_MILLIS) {
            result.add(encodeFrame(
                sessionId, 0, receivedThrough, null, true));
        }
        if (acknowledgementDirty && (carriedAck || !result.isEmpty())) {
            acknowledgementDirty = false;
        }
        if (!result.isEmpty()) {
            lastSentMillis = nowMillis;
        }
        return result;
    }

    /** Accept one untrusted UDP datagram. */
    public synchronized void receive(byte[] datagram, long nowMillis) {
        Frame frame = decodeFrame(sessionId, datagram);
        lastReceivedMillis = nowMillis;
        acknowledge(frame.acknowledgement);
        if (frame.heartbeat) {
            acknowledgementDirty = true;
        }
        if (frame.sequence == 0) {
            return;
        }
        acknowledgementDirty = true;
        if (frame.sequence <= receivedThrough || reordered.containsKey(frame.sequence)) {
            duplicates++;
            return;
        }
        if (frame.sequence > receivedThrough + MAX_IN_FLIGHT) {
            throw new IllegalArgumentException("reliable receive window exceeded");
        }
        reordered.put(frame.sequence, frame.payload);
        while (true) {
            byte[] next = reordered.remove(receivedThrough + 1);
            if (next == null) {
                break;
            }
            receivedThrough++;
            delivered.add(next);
        }
    }

    /** Messages are returned exactly once and in sender order. */
    public synchronized List<byte[]> drainDelivered() {
        List<byte[]> result = new ArrayList<>();
        while (!delivered.isEmpty()) {
            result.add(delivered.remove().clone());
        }
        return result;
    }

    private void acknowledge(int sequence) {
        if (sequence < 0 || sequence >= nextSequence) {
            throw new IllegalArgumentException("invalid acknowledgement " + sequence);
        }
        if (sequence <= acknowledgedThrough) {
            return;
        }
        acknowledgedThrough = sequence;
        Iterator<Map.Entry<Integer, Pending>> iterator =
            pending.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getKey() <= sequence) {
                iterator.remove();
            }
        }
    }

    public synchronized boolean isIdle() { return pending.isEmpty(); }
    public synchronized boolean timedOut() { return timedOut; }
    public synchronized boolean disconnected(long nowMillis) {
        if (timedOut) {
            return true;
        }
        if (!clockStarted) {
            return false;
        }
        long heard = lastReceivedMillis == Long.MIN_VALUE
            ? firstPollMillis : lastReceivedMillis;
        return nowMillis - heard >= DISCONNECT_MILLIS;
    }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized int receivedThrough() { return receivedThrough; }
    public synchronized int acknowledgedThrough() { return acknowledgedThrough; }
    public synchronized int retransmissions() { return retransmissions; }
    public synchronized int duplicates() { return duplicates; }

    private static byte[] encodeFrame(long sessionId, int sequence,
                                      int acknowledgement, byte[] payload,
                                      boolean heartbeat) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeLong(sessionId);
            out.writeByte((payload == null ? 0 : FLAG_DATA)
                        | (heartbeat ? FLAG_HEARTBEAT : 0));
            out.writeInt(sequence);
            out.writeInt(acknowledgement);
            int length = payload == null ? 0 : payload.length;
            out.writeShort(length);
            if (payload != null) {
                out.write(payload);
            }
            out.flush();
            byte[] body = bytes.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            out.writeInt((int) crc.getValue());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("memory reliability codec failed", impossible);
        }
    }

    private static Frame decodeFrame(long expectedSession, byte[] datagram) {
        if (datagram == null || datagram.length < HEADER_BYTES + CHECKSUM_BYTES
                || datagram.length > HEADER_BYTES + MAX_MESSAGE_BYTES
                    + CHECKSUM_BYTES) {
            throw new IllegalArgumentException("invalid reliable datagram length");
        }
        verifyChecksum(datagram);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                datagram, 0, datagram.length - CHECKSUM_BYTES));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("unknown reliable UDP frame");
            }
            long session = in.readLong();
            if (session != expectedSession) {
                throw new IllegalArgumentException("UDP session mismatch");
            }
            int flags = in.readUnsignedByte();
            if ((flags & ~(FLAG_DATA | FLAG_HEARTBEAT)) != 0) {
                throw new IllegalArgumentException("unknown reliable UDP flags");
            }
            int sequence = in.readInt();
            int acknowledgement = in.readInt();
            int length = in.readUnsignedShort();
            boolean hasData = (flags & FLAG_DATA) != 0;
            boolean heartbeat = (flags & FLAG_HEARTBEAT) != 0;
            if (sequence < 0 || acknowledgement < 0
                    || hasData != (sequence > 0)
                    || hasData != (length > 0)
                    || (hasData && heartbeat)
                    || length > MAX_MESSAGE_BYTES
                    || length != in.available()) {
                throw new IllegalArgumentException("invalid reliable UDP frame fields");
            }
            byte[] payload = null;
            if (hasData) {
                payload = new byte[length];
                in.readFully(payload);
            }
            return new Frame(sequence, acknowledgement, payload, heartbeat);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated reliable UDP frame", truncated);
        } catch (IOException failed) {
            throw new IllegalArgumentException("invalid reliable UDP frame", failed);
        }
    }

    private static void verifyChecksum(byte[] bytes) {
        int at = bytes.length - CHECKSUM_BYTES;
        int expected = ((bytes[at] & 0xff) << 24)
                     | ((bytes[at + 1] & 0xff) << 16)
                     | ((bytes[at + 2] & 0xff) << 8)
                     | (bytes[at + 3] & 0xff);
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, at);
        if ((int) crc.getValue() != expected) {
            throw new IllegalArgumentException("reliable UDP checksum mismatch");
        }
    }
}
