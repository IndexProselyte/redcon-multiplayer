package redcon.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** Fixed-width architecture-neutral codec for a measured lockstep delay. */
public final class LockstepDelayCodec {
    public static final int MAGIC = 0x4c535231; // LSR1
    public static final int VERSION = 1;
    public static final int BYTES = 4 + 1 + 8 + 1 + 8 + 4;

    public static byte[] encode(LockstepDelayPacket packet) {
        if (packet == null) throw new IllegalArgumentException("packet is null");
        ByteBuffer out = ByteBuffer.allocate(BYTES).order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC).put((byte) VERSION).putLong(packet.matchId)
            .put((byte) packet.player).putLong(packet.roundTripMillis);
        CRC32 crc = new CRC32();
        crc.update(out.array(), 0, BYTES - 4);
        out.putInt((int) crc.getValue());
        return out.array();
    }

    public static LockstepDelayPacket decode(byte[] bytes) {
        if (bytes == null || bytes.length != BYTES) {
            throw new IllegalArgumentException("invalid delay packet size");
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, BYTES - 4);
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (in.getInt() != MAGIC || (in.get() & 0xff) != VERSION) {
            throw new IllegalArgumentException("not a delay packet");
        }
        long match = in.getLong();
        int player = in.get() & 0xff;
        long rtt = in.getLong();
        if ((int) crc.getValue() != in.getInt()) {
            throw new IllegalArgumentException("delay packet checksum mismatch");
        }
        return new LockstepDelayPacket(match, player, rtt);
    }

    private LockstepDelayCodec() { }
}
