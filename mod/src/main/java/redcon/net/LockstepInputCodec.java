package redcon.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import redcon.duel.Order;

/** Architecture-neutral codec for redundant direct lockstep input packets. */
public final class LockstepInputCodec {
    public static final int MAGIC = 0x4c533631; // LS61
    public static final int VERSION = 2;
    public static final int MAX_PACKET_BYTES = 1200;

    public static byte[] encode(LockstepInputPacket packet) {
        if (packet == null) throw new IllegalArgumentException("packet is null");
        try {
            ByteArrayOutputStream storage = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(storage);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeLong(packet.matchId);
            out.writeInt(packet.round);
            out.writeByte(packet.player);
            out.writeLong(packet.confirmedFrame);
            List<LockstepInputFrame> frames = packet.frames();
            out.writeByte(frames.size());
            for (LockstepInputFrame frame : frames) {
                out.writeLong(frame.frame);
                List<Order> orders = frame.orders();
                out.writeByte(orders.size());
                for (Order order : orders) {
                    out.writeByte(order.kind.ordinal());
                    out.writeInt(order.a);
                    out.writeInt(order.b);
                }
            }
            out.flush();
            byte[] body = storage.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            out.writeInt((int) crc.getValue());
            out.flush();
            byte[] encoded = storage.toByteArray();
            if (encoded.length > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("lockstep packet is too large");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static LockstepInputPacket decode(byte[] encoded) {
        if (encoded == null || encoded.length < 31
                || encoded.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("invalid lockstep packet size");
        }
        int bodyLength = encoded.length - 4;
        CRC32 crc = new CRC32();
        crc.update(encoded, 0, bodyLength);
        int expected = ((encoded[bodyLength] & 0xff) << 24)
            | ((encoded[bodyLength + 1] & 0xff) << 16)
            | ((encoded[bodyLength + 2] & 0xff) << 8)
            | (encoded[bodyLength + 3] & 0xff);
        if ((int) crc.getValue() != expected) {
            throw new IllegalArgumentException("lockstep packet checksum mismatch");
        }
        try {
            DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(encoded, 0, bodyLength));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("not a lockstep input packet");
            }
            long matchId = in.readLong();
            int round = in.readInt();
            int player = in.readUnsignedByte();
            long confirmed = in.readLong();
            int frameCount = in.readUnsignedByte();
            if (frameCount < 1 || frameCount > LockstepInputPacket.HISTORY) {
                throw new IllegalArgumentException("bad lockstep history length");
            }
            List<LockstepInputFrame> frames = new ArrayList<>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                long frame = in.readLong();
                int orderCount = in.readUnsignedByte();
                if (orderCount > LockstepInputFrame.MAX_ORDERS) {
                    throw new IllegalArgumentException("too many lockstep orders");
                }
                List<Order> orders = new ArrayList<>(orderCount);
                for (int j = 0; j < orderCount; j++) {
                    int kind = in.readUnsignedByte();
                    int a = in.readInt();
                    int b = in.readInt();
                    orders.add(order(kind, a, b));
                }
                frames.add(new LockstepInputFrame(player, frame, orders));
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing lockstep bytes");
            }
            return new LockstepInputPacket(matchId, round, player, confirmed, frames);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated lockstep packet", truncated);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("invalid lockstep packet", invalid);
        }
    }

    private static Order order(int ordinal, int a, int b) {
        Order.Kind[] kinds = Order.Kind.values();
        if (ordinal < 0 || ordinal >= kinds.length) {
            throw new IllegalArgumentException("bad lockstep order kind");
        }
        return DuelPacketCodec.readOrder(kinds[ordinal], a, b);
    }

    private LockstepInputCodec() { }
}
