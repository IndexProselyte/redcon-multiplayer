package redcon.net;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/** Fixed-width, big-endian codec for lockstep state checkpoints. */
public final class LockstepDigestCodec {
    public static final int MAGIC = 0x4c534431; // LSD1
    public static final int BYTES = 4 + 1 + 8 + 4 + 1 + 8 + 8 + 4;

    public static byte[] encode(LockstepDigestPacket packet) {
        if (packet == null) throw new IllegalArgumentException("digest is null");
        ByteBuffer out = ByteBuffer.allocate(BYTES);
        out.putInt(MAGIC).put((byte) 2).putLong(packet.matchId).putInt(packet.round)
           .put((byte) packet.player).putLong(packet.frame).putLong(packet.digest);
        CRC32 crc = new CRC32();
        crc.update(out.array(), 0, BYTES - 4);
        out.putInt((int) crc.getValue());
        return out.array();
    }

    public static LockstepDigestPacket decode(byte[] bytes) {
        if (bytes == null || bytes.length != BYTES) {
            throw new IllegalArgumentException("invalid digest packet size");
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, BYTES - 4);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (in.getInt() != MAGIC || in.get() != 2) {
            throw new IllegalArgumentException("not a digest packet");
        }
        long match = in.getLong();
        int round = in.getInt();
        int player = in.get() & 0xff;
        long frame = in.getLong();
        long digest = in.getLong();
        if (in.getInt() != (int) crc.getValue()) {
            throw new IllegalArgumentException("digest checksum mismatch");
        }
        return new LockstepDigestPacket(match, round, player, frame, digest);
    }

    private LockstepDigestCodec() { }
}
