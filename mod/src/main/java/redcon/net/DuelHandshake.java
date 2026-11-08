package redcon.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Version/asset handshake payload used by LAN discovery and pairing. */
public final class DuelHandshake {

    public static final int ASSET_HASH_BYTES = 32;
    public static final int MAX_PACKET_BYTES = 512;

    private static final int MAGIC = 0x52444834; // "RDH4"
    private static final int MAX_NAME_BYTES = 64;
    private static final int MAX_BUILD_BYTES = 64;

    // Keep existing wire ordinals. IDLE means a host accepting requests.
    public enum State { IDLE, BUSY, BROWSING }

    public enum Compatibility {
        COMPATIBLE,
        PROTOCOL_MISMATCH,
        BUILD_MISMATCH,
        ASSET_MISMATCH
    }

    public final int protocolVersion;
    public final long instanceId;
    public final long nonce;
    public final String playerName;
    public final String buildVersion;
    public final State state;
    public final int listenPort;
    private final byte[] assetHash;

    public DuelHandshake(int protocolVersion, long instanceId, long nonce,
                         String playerName, String buildVersion,
                         byte[] assetHash, State state, int listenPort) {
        if (protocolVersion <= 0 || protocolVersion > 0xffff
                || instanceId == 0 || playerName == null || playerName.isBlank()
                || buildVersion == null || buildVersion.isBlank()
                || assetHash == null || assetHash.length != ASSET_HASH_BYTES
                || state == null || listenPort <= 0 || listenPort > 0xffff) {
            throw new IllegalArgumentException("invalid duel handshake");
        }
        this.protocolVersion = protocolVersion;
        this.instanceId = instanceId;
        this.nonce = nonce;
        this.playerName = playerName;
        this.buildVersion = buildVersion;
        this.assetHash = assetHash.clone();
        this.state = state;
        this.listenPort = listenPort;
    }

    public byte[] assetHash() { return assetHash.clone(); }

    public Compatibility compatibilityWith(DuelHandshake peer) {
        if (peer == null || protocolVersion != peer.protocolVersion) {
            return Compatibility.PROTOCOL_MISMATCH;
        }
        if (!buildVersion.equals(peer.buildVersion)) {
            return Compatibility.BUILD_MISMATCH;
        }
        if (!Arrays.equals(assetHash, peer.assetHash)) {
            return Compatibility.ASSET_MISMATCH;
        }
        return Compatibility.COMPATIBLE;
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeShort(protocolVersion);
            out.writeLong(instanceId);
            out.writeLong(nonce);
            out.writeByte(state.ordinal());
            out.writeShort(listenPort);
            writeString(out, playerName, MAX_NAME_BYTES);
            writeString(out, buildVersion, MAX_BUILD_BYTES);
            out.write(assetHash);
            out.flush();
            byte[] body = bytes.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(body);
            out.writeInt((int) crc.getValue());
            out.flush();
            if (bytes.size() > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("handshake exceeds packet limit");
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("memory handshake codec failed", impossible);
        }
    }

    public static DuelHandshake decode(byte[] packet) {
        if (packet == null || packet.length < 64
                || packet.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("invalid handshake length");
        }
        verifyChecksum(packet);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                packet, 0, packet.length - Integer.BYTES));
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("not a duel handshake");
            }
            int protocol = in.readUnsignedShort();
            long instanceId = in.readLong();
            long nonce = in.readLong();
            State state = enumAt(State.values(), in.readUnsignedByte());
            int port = in.readUnsignedShort();
            String playerName = readString(in, MAX_NAME_BYTES);
            String buildVersion = readString(in, MAX_BUILD_BYTES);
            byte[] assetHash = new byte[ASSET_HASH_BYTES];
            in.readFully(assetHash);
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing handshake bytes");
            }
            return new DuelHandshake(protocol, instanceId, nonce, playerName,
                buildVersion, assetHash, state, port);
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("truncated duel handshake", truncated);
        } catch (IOException failed) {
            throw new IllegalArgumentException("invalid duel handshake", failed);
        }
    }

    /** Hash exact versioned asset bytes; callers choose the agreed asset set. */
    public static byte[] assetHash(byte[]... assets) {
        if (assets == null) {
            throw new IllegalArgumentException("asset list is null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] asset : assets) {
                if (asset == null) {
                    throw new IllegalArgumentException("asset bytes are null");
                }
                digest.update((byte) (asset.length >>> 24));
                digest.update((byte) (asset.length >>> 16));
                digest.update((byte) (asset.length >>> 8));
                digest.update((byte) asset.length);
                digest.update(asset);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeString(DataOutputStream out, String value, int limit)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > limit) {
            throw new IllegalArgumentException("handshake string exceeds limit");
        }
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int limit)
            throws IOException {
        int length = in.readUnsignedByte();
        if (length == 0 || length > limit) {
            throw new IllegalArgumentException("invalid handshake string length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static <T> T enumAt(T[] values, int index) {
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("unknown handshake state " + index);
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
            throw new IllegalArgumentException("handshake checksum mismatch");
        }
    }
}
