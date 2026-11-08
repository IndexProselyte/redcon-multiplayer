package redcon.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Four-timestamp host-clock sampling for shared duel deadlines. */
public final class DuelClockSync {

    public static final int MAX_PACKET_BYTES = 64;
    private static final int MAGIC = 0x52444334; // "RDC4"
    private static final int VERSION = 2;
    private static final int PROBE = 1;
    private static final int REPLY = 2;
    private static final int MAX_SAMPLES = 8;

    public static final class Probe {
        public final long id;
        public final long clientSendMillis;
        /**
         * The guest's own best measurement so far, or -1 before it has one.
         *
         * <p>Only the guest measures a round trip - the host merely answers -
         * so the host cannot choose an informed input delay without being
         * told. The probe already crosses to the host every pump, so the
         * measurement rides on it rather than earning a packet of its own.</p>
         */
        public final long reportedRoundTripMillis;

        public Probe(long id, long clientSendMillis) {
            this(id, clientSendMillis, -1);
        }

        public Probe(long id, long clientSendMillis,
                     long reportedRoundTripMillis) {
            if (id <= 0 || clientSendMillis < 0
                    || reportedRoundTripMillis < -1) {
                throw new IllegalArgumentException("invalid clock probe");
            }
            this.id = id;
            this.clientSendMillis = clientSendMillis;
            this.reportedRoundTripMillis = reportedRoundTripMillis;
        }
    }

    public static final class Reply {
        public final long id;
        public final long clientSendMillis;
        public final long hostReceiveMillis;
        public final long hostSendMillis;

        public Reply(long id, long clientSendMillis,
                     long hostReceiveMillis, long hostSendMillis) {
            if (id <= 0 || clientSendMillis < 0 || hostReceiveMillis < 0
                    || hostSendMillis < hostReceiveMillis) {
                throw new IllegalArgumentException("invalid clock reply");
            }
            this.id = id;
            this.clientSendMillis = clientSendMillis;
            this.hostReceiveMillis = hostReceiveMillis;
            this.hostSendMillis = hostSendMillis;
        }
    }

    public static final class Sample {
        public final long id;
        public final long roundTripMillis;
        public final double hostOffsetMillis;

        private Sample(long id, long roundTripMillis, double hostOffsetMillis) {
            this.id = id;
            this.roundTripMillis = roundTripMillis;
            this.hostOffsetMillis = hostOffsetMillis;
        }
    }

    /** Guest-side estimator; the lowest-delay recent sample is authoritative. */
    public static final class Estimator {
        private final Map<Long, Probe> outstanding = new HashMap<>();
        private final List<Sample> samples = new ArrayList<>();
        private long nextId = 1;

        public synchronized Probe begin(long clientNowMillis) {
            if (outstanding.size() >= MAX_SAMPLES * 2) {
                throw new IllegalStateException("too many outstanding clock probes");
            }
            Probe probe = new Probe(nextId++, clientNowMillis,
                samples.isEmpty() ? -1 : bestSample().roundTripMillis);
            outstanding.put(probe.id, probe);
            return probe;
        }

        public synchronized Sample accept(Reply reply, long clientReceiveMillis) {
            if (reply == null || clientReceiveMillis < 0) {
                throw new IllegalArgumentException("invalid received clock reply");
            }
            Probe probe = outstanding.get(reply.id);
            if (probe == null || probe.clientSendMillis != reply.clientSendMillis) {
                throw new IllegalArgumentException("unknown or mismatched clock reply");
            }
            if (clientReceiveMillis < probe.clientSendMillis) {
                throw new IllegalArgumentException("client clock moved backwards");
            }
            long hostWork = reply.hostSendMillis - reply.hostReceiveMillis;
            long roundTrip = clientReceiveMillis - probe.clientSendMillis - hostWork;
            if (roundTrip < 0) {
                throw new IllegalArgumentException("negative network round trip");
            }
            outstanding.remove(reply.id);
            double offset = ((reply.hostReceiveMillis - probe.clientSendMillis)
                           + (reply.hostSendMillis - clientReceiveMillis)) / 2.0;
            Sample sample = new Sample(reply.id, roundTrip, offset);
            samples.add(sample);
            if (samples.size() > MAX_SAMPLES) {
                samples.remove(0);
            }
            return sample;
        }

        public synchronized boolean ready() { return !samples.isEmpty(); }

        public synchronized int outstandingCount() { return outstanding.size(); }

        public synchronized Sample bestSample() {
            if (samples.isEmpty()) {
                throw new IllegalStateException("no clock samples");
            }
            return samples.stream()
                .min(Comparator.comparingLong(sample -> sample.roundTripMillis))
                .orElseThrow();
        }

        public synchronized double hostOffsetMillis() {
            return bestSample().hostOffsetMillis;
        }

        /** Convert a local monotonic timestamp onto the host's clock. */
        public synchronized long hostTimeMillis(long localMillis) {
            return Math.round(localMillis + hostOffsetMillis());
        }
    }

    /** Host stamps receipt and send immediately around creation of this reply. */
    public static Reply answer(Probe probe, long hostReceiveMillis,
                               long hostSendMillis) {
        if (probe == null) {
            throw new IllegalArgumentException("clock probe is null");
        }
        return new Reply(probe.id, probe.clientSendMillis,
                         hostReceiveMillis, hostSendMillis);
    }

    public static byte[] encode(Probe probe) {
        if (probe == null) {
            throw new IllegalArgumentException("clock probe is null");
        }
        return encode(PROBE, probe.id, probe.clientSendMillis,
                      probe.reportedRoundTripMillis, 0);
    }

    public static byte[] encode(Reply reply) {
        if (reply == null) {
            throw new IllegalArgumentException("clock reply is null");
        }
        return encode(REPLY, reply.id, reply.clientSendMillis,
                      reply.hostReceiveMillis, reply.hostSendMillis);
    }

    public static Object decode(byte[] packet) {
        if (packet == null || packet.length < 34
                || packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("invalid clock packet length");
        }
        verifyChecksum(packet);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                packet, 0, packet.length - Integer.BYTES));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("unknown clock packet");
            }
            int type = in.readUnsignedByte();
            long id = in.readLong();
            long clientSend = in.readLong();
            Object result;
            if (type == PROBE) {
                result = new Probe(id, clientSend, in.readLong());
            } else if (type == REPLY) {
                result = new Reply(id, clientSend, in.readLong(), in.readLong());
            } else {
                throw new IllegalArgumentException("unknown clock packet type " + type);
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing clock packet bytes");
            }
            return result;
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated clock packet", truncated);
        } catch (IOException failed) {
            throw new IllegalArgumentException("invalid clock packet", failed);
        }
    }

    /**
     * A probe's third word is its reported round trip; a reply's third and
     * fourth are the host's receive and send stamps.
     */
    private static byte[] encode(int type, long id, long clientSend,
                                 long third, long fourth) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(type);
            out.writeLong(id);
            out.writeLong(clientSend);
            out.writeLong(third);
            if (type == REPLY) {
                out.writeLong(fourth);
            }
            out.flush();
            byte[] body = bytes.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            out.writeInt((int) crc.getValue());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("memory clock codec failed", impossible);
        }
    }

    private static void verifyChecksum(byte[] packet) {
        int at = packet.length - Integer.BYTES;
        int expected = ((packet[at] & 0xff) << 24)
                     | ((packet[at + 1] & 0xff) << 16)
                     | ((packet[at + 2] & 0xff) << 8)
                     | (packet[at + 3] & 0xff);
        CRC32 crc = new CRC32();
        crc.update(packet, 0, at);
        if ((int) crc.getValue() != expected) {
            throw new IllegalArgumentException("clock checksum mismatch");
        }
    }

    private DuelClockSync() {
    }
}
