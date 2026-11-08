package redcon.desktop;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import mojo.f;
import o3.v1;
import o3.z0;
import redcon.net.DuelLobby;
import redcon.net.DuelHandshake;
import redcon.net.DuelPacketCodec;

/**
 * Who this install is, and what content it would be playing with.
 *
 * <p>Pairing refuses a peer whose protocol, build, or content differs, so this
 * has to describe the things that would make two devices disagree about a
 * battle rather than merely about a menu. The asset hash therefore covers the
 * shape of the whole content table plus, explicitly, the room roster's
 * structure and footprint and every fort blueprint's layout: change a room's
 * hit points or a shipped fortress and the two installs stop being able to
 * play each other, and say so.</p>
 */
public final class DuelIdentity {

    /**
     * Bumped whenever the mod's own behaviour changes incompatibly.
     *
     * <p>Phase 06 changed the wire: lockstep input, digests, and a second
     * revision of the clock-sync probe. A Phase 04 build shares the pairing
     * protocol version and would otherwise pair, then quietly lose the shared
     * Build deadline instead of saying why - which is exactly the confusion a
     * side-loaded stale handset would cause during hardware acceptance.</p>
     */
    public static final String BUILD_VERSION = "redcon-pvp-p6-build13";

    private static final String PLAYER_PROPERTY = "redcon.playerName";
    private static final String PORT_PROPERTY = "redcon.duelPort";
    private static final String ANNOUNCE_PROPERTY = "redcon.duelAnnounce";

    private static final long INSTANCE_ID = newInstanceId();
    private static byte[] assetHash;

    private DuelIdentity() {
    }

    /** This device's announcement, bound to the port the lobby actually got. */
    public static DuelHandshake local(int listenPort) {
        return new DuelHandshake(DuelPacketCodec.PROTOCOL_VERSION,
            INSTANCE_ID, new SecureRandom().nextLong(), playerName(),
            BUILD_VERSION, assetHash(), DuelHandshake.State.IDLE, listenPort);
    }

    /** Whatever this player is called on the Browse and Pairing screens. */
    public static String playerName() {
        String saved = DuelPlayerProfile.name();
        if (saved != null) return saved;
        String configured = configured(PLAYER_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.length() > 24
                ? configured.substring(0, 24) : configured;
        }
        return DuelStrings.of(DuelStrings.PLAYER_ONE);
    }

    /**
     * The lobby port. Zero asks the platform for an ephemeral one, which is
     * how a second instance on one machine can still announce and be found.
     */
    public static int lobbyPort() {
        String configured = configured(PORT_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return DuelLobby.DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(configured.trim());
            return port > 0 && port <= 65535
                ? port : DuelLobby.DEFAULT_PORT;
        } catch (NumberFormatException invalid) {
            return DuelLobby.DEFAULT_PORT;
        }
    }

    /** Where announcements go: the LAN broadcast address by default. */
    public static InetSocketAddress announceTarget() {
        String configured = configured(ANNOUNCE_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return new InetSocketAddress(
                "255.255.255.255", DuelLobby.DEFAULT_PORT);
        }
        int colon = configured.lastIndexOf(':');
        if (colon <= 0) {
            return new InetSocketAddress(configured, DuelLobby.DEFAULT_PORT);
        }
        return new InetSocketAddress(configured.substring(0, colon),
            Integer.parseInt(configured.substring(colon + 1)));
    }

    /**
     * A boolean switch, read the same way on both platforms.
     *
     * <p>{@code Boolean.getBoolean} reads a system property and nothing else.
     * The desktop launcher passes {@code -Dredcon.lockstepTrace=true}, so it
     * works there; the phone launcher passes {@code --es redcon.lockstepTrace
     * true}, an intent extra, which a system-property read cannot see. Every
     * switch that used {@code Boolean.getBoolean} was therefore desktop-only,
     * including the checkpoint diagnostic block that a cross-device comparison
     * most wants from the handset - and including the correction switch, which
     * writes to the simulation.</p>
     */
    public static boolean flag(String key) {
        if (DuelBuildFlavor.RELEASE) {
            return false;
        }
        return Boolean.parseBoolean(configured(key));
    }

    /** True only in local developer builds, never from an Android intent. */
    public static boolean developerControlsEnabled() {
        return !DuelBuildFlavor.RELEASE;
    }

    /**
     * Desktop launches use Java properties. Android test launches cannot set
     * per-process Java properties, so they may provide the same keys as
     * string extras on MainActivity's intent (for example through adb am).
     * Reflection keeps the desktop closure free of Android SDK classes.
     */
    public static String configured(String key) {
        String value = System.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            return value;
        }
        try {
            Class<?> platform = Class.forName("mojo.Platform");
            Object activity = platform.getField("C").get(null);
            if (activity == null) {
                return null;
            }
            Object intent = activity.getClass().getMethod("getIntent")
                .invoke(activity);
            if (intent == null) {
                return null;
            }
            Object extra = intent.getClass()
                .getMethod("getStringExtra", String.class)
                .invoke(intent, key);
            return extra instanceof String ? (String) extra : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    /** SHA-256 over the battle-meaningful content this install would play. */
    public static synchronized byte[] assetHash() {
        if (assetHash == null) {
            assetHash = DuelHandshake.assetHash(contentFingerprint());
        }
        return assetHash.clone();
    }

    private static byte[] contentFingerprint() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            f[] table = f.c;
            if (table == null) {
                // Nothing is loaded yet. An install with no content cannot
                // play anybody, and saying so beats hashing emptiness as if
                // it agreed with another empty one.
                throw new IllegalStateException(
                    "the content table has not been loaded");
            }
            out.writeInt(table.length);
            for (f object : table) {
                out.writeUTF(object == null
                    ? "null" : object.getClass().getName());
                if (object instanceof v1) {
                    v1 room = (v1) object;
                    out.writeFloat(room.V0);
                    out.writeFloat(room.W0);
                    out.writeFloat(room.T);
                    out.writeShort(room.B);
                    out.writeShort(room.C);
                    out.writeByte(room.z);
                } else if (object instanceof z0) {
                    z0 fort = (z0) object;
                    out.writeUTF(fort.d == null ? "" : fort.d);
                    out.writeInt(fort.n);
                    out.writeInt(fort.p);
                    writeShorts(out, fort.o);
                    writeShorts(out, fort.q);
                    writeBytes(out, fort.w);
                }
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(
                "in-memory content fingerprint failed", impossible);
        }
        return bytes.toByteArray();
    }

    private static void writeShorts(DataOutputStream out, short[] values)
            throws IOException {
        out.writeInt(values == null ? -1 : values.length);
        if (values != null) {
            for (short value : values) {
                out.writeShort(value);
            }
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] values)
            throws IOException {
        out.writeInt(values == null ? -1 : values.length);
        if (values != null) {
            out.write(values);
        }
    }

    private static long newInstanceId() {
        long value = new SecureRandom().nextLong();
        return value == 0 ? 1 : value;
    }
}
