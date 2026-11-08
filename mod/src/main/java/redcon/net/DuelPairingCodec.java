package redcon.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.zip.CRC32;

/** Bounded, checksummed wire format for pre-match pairing messages. */
public final class DuelPairingCodec {

    public static final int MAX_PACKET_BYTES = 768;

    private static final int MAGIC = 0x52445052; // "RDPR"
    private static final int VERSION = 1;

    public static byte[] encode(DuelPairingMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("pairing message is null");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(message.kind().ordinal());
            switch (message.kind()) {
                case REQUEST: {
                    DuelPairingMessage.Request request =
                        (DuelPairingMessage.Request) message;
                    out.writeLong(request.requestId);
                    out.writeLong(request.targetInstanceId);
                    byte[] handshake = request.requester.encode();
                    out.writeShort(handshake.length);
                    out.write(handshake);
                    break;
                }
                case RESPONSE: {
                    DuelPairingMessage.Response response =
                        (DuelPairingMessage.Response) message;
                    out.writeLong(response.requestId);
                    out.writeLong(response.hostInstanceId);
                    out.writeByte(response.decision.ordinal());
                    out.writeByte(response.compatibility.ordinal());
                    break;
                }
                case OK: {
                    DuelPairingMessage.Ok ok = (DuelPairingMessage.Ok) message;
                    out.writeLong(ok.requestId);
                    out.writeLong(ok.hostInstanceId);
                    out.writeLong(ok.guestInstanceId);
                    out.writeLong(ok.matchId);
                    out.writeLong(ok.transportSessionId);
                    out.writeLong(ok.sessionSeed);
                    out.writeLong(ok.rulesRevision);
                    break;
                }
                default:
                    throw new IllegalArgumentException(
                        "unsupported pairing message " + message.kind());
            }
            out.flush();
            if (bytes.size() + Integer.BYTES > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("pairing packet exceeds limit");
            }
            byte[] body = bytes.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            out.writeInt((int) crc.getValue());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("memory pairing codec failed", impossible);
        }
    }

    public static DuelPairingMessage decode(byte[] packet) {
        if (packet == null || packet.length < 18
                || packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("invalid pairing packet length");
        }
        verifyChecksum(packet);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                packet, 0, packet.length - Integer.BYTES));
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("unknown pairing packet");
            }
            DuelPairingMessage.Kind kind = enumAt(
                DuelPairingMessage.Kind.values(), in.readUnsignedByte(), "kind");
            DuelPairingMessage result;
            switch (kind) {
                case REQUEST: {
                    long requestId = in.readLong();
                    long target = in.readLong();
                    int length = in.readUnsignedShort();
                    if (length == 0 || length > DuelHandshake.MAX_PACKET_BYTES
                            || length > in.available()) {
                        throw new IllegalArgumentException(
                            "invalid nested handshake length");
                    }
                    byte[] handshake = new byte[length];
                    in.readFully(handshake);
                    result = new DuelPairingMessage.Request(
                        requestId, target, DuelHandshake.decode(handshake));
                    break;
                }
                case RESPONSE:
                    result = new DuelPairingMessage.Response(
                        in.readLong(), in.readLong(),
                        enumAt(DuelPairingMessage.Decision.values(),
                               in.readUnsignedByte(), "decision"),
                        enumAt(DuelHandshake.Compatibility.values(),
                               in.readUnsignedByte(), "compatibility"));
                    break;
                case OK:
                    result = new DuelPairingMessage.Ok(
                        in.readLong(), in.readLong(), in.readLong(),
                        in.readLong(), in.readLong(), in.readLong(), in.readLong());
                    break;
                default:
                    throw new IllegalArgumentException(
                        "unsupported pairing message " + kind);
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing pairing packet bytes");
            }
            return result;
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated pairing packet", truncated);
        } catch (IOException failed) {
            throw new IllegalArgumentException("invalid pairing packet", failed);
        }
    }

    private static <T> T enumAt(T[] values, int index, String what) {
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("unknown pairing " + what + " " + index);
        }
        return values[index];
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
            throw new IllegalArgumentException("pairing checksum mismatch");
        }
    }

    private DuelPairingCodec() {
    }
}
